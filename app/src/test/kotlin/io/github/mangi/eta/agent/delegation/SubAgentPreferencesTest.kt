package io.github.mangi.eta.agent.delegation

import android.app.Application
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import org.junit.Assert.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SubAgentPreferencesTest {
    @Before fun explicitLegacyFixture() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val profiles = (0..3).map { slot -> SubAgentProfile("legacy-$slot", "Legacy $slot", if (slot == 1) "review" else "implementation") }
        Prefs.putString(SubAgentPreferences.PROFILES_KEY, org.json.JSONObject().put("version", 1)
            .put("agents", org.json.JSONArray(profiles.map { it.toJson() })).toString())
        Prefs.putString(SubAgentModelDefaults.KEY, SubAgentModelDefaults.encode(emptyMap()))
    }
    @Test fun additionalSlotsPreserveOriginalRoleAndModelReferences() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val original = SubAgentPreferences.selection(0)
        val review = SubAgentPreferences.selection(1)
        val saved = SubAgentPreferences.selection(3)
        val savedReasoning = SubAgentPreferences.reasoning(3)
        try {
            val extra = io.github.mangi.eta.agent.model.ModelFeatureSelection(true, "extra-provider", "extra-model")
            SubAgentPreferences.save(3, extra)
            assertEquals(extra, SubAgentPreferences.selection(3))
            assertEquals(original, SubAgentPreferences.selection(0))
            assertEquals(review, SubAgentPreferences.selection(1))
            assertEquals(listOf("implementation", "review", "implementation", "implementation"),
                (0 until SubAgentPreferences.SLOT_COUNT).map(SubAgentPreferences::role))
        } finally { SubAgentPreferences.save(3, saved); SubAgentPreferences.saveReasoning(3, savedReasoning) }
    }
    @Test fun draftSwitchPromotesOnceAndConversationsRemainIndependent() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val id = java.util.UUID.randomUUID().toString()
        assertFalse(SubAgentPreferences.enabled(id))
        SubAgentPreferences.setEnabled(null, false)
        SubAgentPreferences.promote(id)
        assertFalse(SubAgentPreferences.enabled(id))
        assertFalse(SubAgentPreferences.enabled(null))
        assertFalse(SubAgentPreferences.enabled("another-$id"))
    }
    @Test fun reasoningIsSlotLocalAndLeavesSharedModelAndParentSnapshotUnchanged() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val saved = (0 until SubAgentPreferences.SLOT_COUNT).map(SubAgentPreferences::reasoning)
        try {
            (0 until SubAgentPreferences.SLOT_COUNT).forEach { SubAgentPreferences.saveReasoning(it, null) }
            val shared = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
                model = "same-model", systemPrompt = "", reasoningEffort = ReasoningEffort.LOW, thinkingEnabled = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), canDisable = true))
            val parentSnapshot = shared.copy()
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.HIGH)
            SubAgentPreferences.saveReasoning(1, ReasoningEffort.OFF)
            assertEquals(ReasoningEffort.HIGH, SubAgentPreferences.applyReasoning(0, shared).reasoningEffort)
            assertFalse(SubAgentPreferences.applyReasoning(1, shared).thinkingEnabled)
            assertSame(shared, SubAgentPreferences.applyReasoning(2, shared))
            assertEquals(parentSnapshot, shared)
            assertEquals(ReasoningEffort.LOW, parentSnapshot.reasoningEffort)
            val runningChild = SubAgentPreferences.applyReasoning(0, shared)
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.OFF)
            assertEquals(ReasoningEffort.HIGH, runningChild.reasoningEffort)
            assertEquals(ReasoningEffort.OFF, SubAgentPreferences.applyReasoning(0, shared).reasoningEffort)
        } finally { saved.forEachIndexed(SubAgentPreferences::saveReasoning) }
    }

    @Test fun changingModelRestoresEachModelsOverride() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val savedModel = SubAgentPreferences.selection(0)
        val saved = (0..1).map(SubAgentPreferences::reasoning)
        try {
            val selection = ModelFeatureSelection(true, "provider-A", "model-A")
            SubAgentPreferences.save(0, selection)
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.HIGH)
            SubAgentPreferences.saveReasoning(1, ReasoningEffort.LOW)
            SubAgentPreferences.save(0, selection)
            assertEquals(ReasoningEffort.HIGH, SubAgentPreferences.reasoning(0))
            SubAgentPreferences.save(0, selection.copy(modelId = "model-B"))
            assertNull(SubAgentPreferences.reasoning(0))
            assertEquals(ReasoningEffort.LOW, SubAgentPreferences.reasoning(1))
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.LOW)
            SubAgentPreferences.save(0, selection)
            assertEquals(ReasoningEffort.HIGH, SubAgentPreferences.reasoning(0))
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.HIGH)
            SubAgentPreferences.save(0, selection.copy(providerId = "provider-B", modelId = "model-B"))
            assertNull(SubAgentPreferences.reasoning(0))
            SubAgentPreferences.save(0, selection.copy(modelId = "model-B"))
            assertEquals(ReasoningEffort.LOW, SubAgentPreferences.reasoning(0))
        } finally {
            SubAgentPreferences.save(0, savedModel)
            saved.forEachIndexed(SubAgentPreferences::saveReasoning)
        }
    }

    @Test fun unsupportedOverridesAreNormalizedWithoutChangingStoredChoice() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val saved = SubAgentPreferences.reasoning(3)
        try {
            val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
                model = "mandatory", systemPrompt = "", reasoningEffort = ReasoningEffort.LOW, thinkingEnabled = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), mandatory = true))
            SubAgentPreferences.saveReasoning(3, ReasoningEffort.OFF)
            assertEquals(ReasoningEffort.LOW, SubAgentPreferences.applyReasoning(3, config).reasoningEffort)
            SubAgentPreferences.saveReasoning(3, ReasoningEffort.XHIGH)
            assertEquals(ReasoningEffort.HIGH, SubAgentPreferences.applyReasoning(3, config).reasoningEffort)
            assertEquals(ReasoningEffort.XHIGH, SubAgentPreferences.reasoning(3))
            assertEquals(ReasoningEffort.OFF, SubAgentPreferences.applyReasoning(3, config.copy(reasoningCapabilities = null)).reasoningEffort)
        } finally { SubAgentPreferences.saveReasoning(3, saved) }
    }

    @Test fun missingProfilesDoNotMigrateSlotsAndDeletingAllDoesNotResurrectThem() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val saved = Prefs.getString(SubAgentPreferences.PROFILES_KEY)
        val provider = Prefs.getString("agent_child_2_provider")
        val model = Prefs.getString("agent_child_2_model")
        try {
            Prefs.putString(SubAgentPreferences.PROFILES_KEY, "")
            Prefs.putString("agent_child_2_provider", "legacy-provider")
            Prefs.putString("agent_child_2_model", "legacy-model")
            val migrated = SubAgentPreferences.profiles()
            assertTrue(migrated.isEmpty())
            Prefs.putString("agent_child_2_model", "do-not-remigrate")
            assertEquals(migrated, SubAgentPreferences.profiles())
            migrated.forEach { SubAgentPreferences.remove(it.id) }
            assertTrue(SubAgentPreferences.profiles().isEmpty())
        } finally {
            Prefs.putString(SubAgentPreferences.PROFILES_KEY, saved)
            Prefs.putString("agent_child_2_provider", provider)
            Prefs.putString("agent_child_2_model", model)
        }
    }

    @Test fun dynamicProfilesKeepIdentityAndIndependentSettingsBeyondFour() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val saved = Prefs.getString(SubAgentPreferences.PROFILES_KEY)
        try {
            val added = List(8) { SubAgentPreferences.add() }
            assertEquals(8, added.map { it.id }.distinct().size)
            val first = added.first()
            val second = added[1]
            added.forEach { SubAgentPreferences.saveModel(it.id, ModelFeatureSelection(true, "p", "same-model")) }
            SubAgentPreferences.update(first.id) { it.copy(name = "审查重点", role = "review", tier = SubAgentTaskTier.COMPLEX,
                reasoning = ReasoningEffort.HIGH, enabled = false) }
            val updated = SubAgentPreferences.profiles().single { it.id == first.id }
            assertEquals("review", updated.role)
            assertNull(updated.tier)
            assertFalse(updated.enabled)
            assertNull(SubAgentPreferences.profiles().single { it.id == second.id }.reasoning)
            SubAgentPreferences.remove(first.id)
            SubAgentPreferences.update(first.id) { it.copy(name = "stale callback") }
            assertFalse(SubAgentPreferences.profiles().any { it.id == first.id })
            assertTrue(SubAgentPreferences.profiles().any { it.id == second.id })
            assertNull(updated.tier) // immutable running snapshot
        } finally { Prefs.putString(SubAgentPreferences.PROFILES_KEY, saved) }
    }

    @Test fun workerDescriptionContainsStableIdentityTierAndEffectiveReasoning() {
        val profile = SubAgentProfile("stable", "复杂实现", tier = SubAgentTaskTier.COMPLEX)
        val config = AgentModelClient.ModelConfig(baseUrl = "", apiKey = "never-advertise", model = "m", systemPrompt = "",
            reasoningEffort = ReasoningEffort.HIGH)
        val description = SubAgentPreferences.workerDescription(profile, 2, config)
        assertTrue(description.contains("2: agent_id=stable"))
        assertTrue(description.contains("complex"))
        assertTrue(description.contains("reasoning=high"))
        assertFalse(description.contains("never-advertise"))
    }

    @Test fun mediaRolesFilterModelsAndClearIncompatibleReferencesWhenRoleChanges() {
        val original = SubAgentProfile("id", "执行", providerId = "p", modelId = "m", reasoning = ReasoningEffort.HIGH)
        val review = original.withRole("review")
        assertEquals("m", review.modelId)
        assertEquals(ReasoningEffort.HIGH, review.reasoning)
        val image = original.withRole("image_generation")
        assertEquals("图片生成", image.roleLabel)
        assertTrue(image.isMedia)
        assertTrue(image.modelId.isBlank())
        assertNull(image.reasoning)
        assertTrue(image.acceptsModel(image = true, video = false))
        assertFalse(image.acceptsModel(image = false, video = true))
        val video = image.copy(providerId = "p", modelId = "image").withRole("video_generation")
        assertEquals("视频生成", video.roleLabel)
        assertTrue(video.modelId.isBlank())
        assertTrue(video.acceptsModel(image = false, video = true))
        assertFalse(video.acceptsModel(image = false, video = false))
        assertEquals(video, SubAgentProfile.fromJson(video.toJson()))
    }

    @Test fun nonImplementationRolesClearLegacyTiersOnReadWriteAndRoleChange() {
        val implementation = SubAgentProfile("test", "测试", tier = SubAgentTaskTier.COMPLEX)
        assertTrue(implementation.supportsTaskTier)
        assertEquals(SubAgentTaskTier.COMPLEX, implementation.withRole("implementation").tier)
        for (role in listOf("review", "image_generation", "video_generation")) {
            val changed = implementation.withRole(role)
            assertFalse(changed.supportsTaskTier)
            assertNull(changed.tier)
            assertNull(changed.withRole("implementation").tier)
            val legacyJson = implementation.toJson().put("role", role).put("tier", "complex")
            assertNull(SubAgentProfile.fromJson(legacyJson).tier)
            assertEquals("", implementation.copy(role = role).toJson().getString("tier"))
            val config = AgentModelClient.ModelConfig(baseUrl = "", apiKey = "", model = "m", systemPrompt = "")
            val description = SubAgentPreferences.workerDescription(implementation.copy(role = role), 1, config)
            assertTrue(description.contains("task tier=not applicable"))
            assertFalse(description.contains("complex"))
            assertFalse(description.contains("suited tasks"))
        }
    }

    @Test fun mediaEffortIsProfileLocalAndNeverInheritsChatDefaults() {
        val media=SubAgentProfile("media-test","图片","image_generation",reasoning=ReasoningEffort.HIGH)
        val cfg=AgentModelClient.ModelConfig(baseUrl="https://example.invalid",apiKey="test",model="same-model",systemPrompt="",
            thinkingEnabled=true,reasoningEffort=ReasoningEffort.XHIGH,
            extraBodyJson="""{"eta_media_reasoning":{"image_generation":{"field":"reasoning_effort","values":{"low":"low","high":"high"},"default":"low"}}}""")
        assertEquals(ReasoningEffort.HIGH,SubAgentPreferences.applyReasoning(media,cfg).reasoningEffort)
        assertEquals(ReasoningEffort.LOW,SubAgentPreferences.applyReasoning(media.copy(reasoning=null),cfg).reasoningEffort)
        assertEquals(ReasoningEffort.XHIGH,cfg.reasoningEffort)
        val unsupported=cfg.copy(extraBodyJson="")
        assertEquals(ReasoningEffort.OFF,SubAgentPreferences.applyReasoning(media.copy(reasoning=null),unsupported).reasoningEffort)
        assertTrue(SubAgentPreferences.workerDescription(media,1,unsupported).contains("current endpoint not adapted for native thinking"))
        assertEquals(ReasoningEffort.HIGH,SubAgentPreferences.applyReasoning(media,unsupported).reasoningEffort)
    }

    @Test fun modelLimitsArePerProviderAndApiModelWithoutArtificialCeiling() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val provider="parallel-${java.util.UUID.randomUUID()}"
        assertEquals(1,SubAgentPreferences.parallelLimit(provider,"same"))
        SubAgentPreferences.saveParallelLimit(provider,"same",1000)
        assertEquals(1000,SubAgentPreferences.parallelLimit(provider,"same"))
        assertEquals(1,SubAgentPreferences.parallelLimit(provider,"other"))
        assertEquals(1,SubAgentPreferences.parallelLimit(provider+"x","same"))
        SubAgentPreferences.saveParallelLimit(provider,"same",0)
        assertEquals(0,SubAgentPreferences.parallelLimit(provider,"same"))
        assertThrows(IllegalArgumentException::class.java) { SubAgentPreferences.saveParallelLimit(provider,"same",-1) }
    }
    @Test fun imageResolutionIsChildDefaultAndNeverMutatesSharedModel() {
        val profile=SubAgentProfile("image-default","image",role="image_generation",imageResolution="high")
        assertEquals("high",SubAgentProfile.fromJson(profile.toJson()).imageResolution)
        val shared=AgentModelClient.ModelConfig(baseUrl="https://example.invalid",apiKey="test",model="image",systemPrompt="",extraBodyJson="""{"size":"1024x1024","seed":1}""")
        val next=SubAgentPreferences.applyImageResolution(profile,shared)
        assertEquals("high",org.json.JSONObject(next.extraBodyJson).getString("resolution"))
        assertFalse(org.json.JSONObject(next.extraBodyJson).has("size"))
        assertTrue(shared.extraBodyJson.contains("1024x1024"))
        assertEquals(1,org.json.JSONObject(next.extraBodyJson).getInt("seed"))
    }

    @Test fun parallelLimitFlowsUpdateAllObserversOfTheBoundModelOnly() = kotlinx.coroutines.runBlocking {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val provider="flow-${java.util.UUID.randomUUID()}"
        val first=mutableListOf<Int>(); val second=mutableListOf<Int>(); val other=mutableListOf<Int>()
        val jobs=listOf(
            launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                SubAgentPreferences.parallelLimitFlow(provider,"shared").collect { first.add(it) }
            },
            launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                SubAgentPreferences.parallelLimitFlow(provider,"shared").collect { second.add(it) }
            },
            launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                SubAgentPreferences.parallelLimitFlow(provider,"other").collect { other.add(it) }
            })
        try {
            SubAgentPreferences.saveParallelLimit(provider,"shared",7)
            kotlinx.coroutines.yield()
            assertEquals(listOf(1,7),first); assertEquals(first,second)
            assertEquals(listOf(1),other)
            SubAgentPreferences.saveParallelLimit(provider,"shared",0)
            kotlinx.coroutines.yield()
            assertEquals(listOf(1,7,0),first); assertEquals(first,second)
        } finally { jobs.forEach { it.cancel() } }
    }

    @Test fun parallelEditorCannotSaveForRemovedOrReboundProfile() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val added=SubAgentPreferences.add()
        val provider="row-${java.util.UUID.randomUUID()}"
        try {
            SubAgentPreferences.saveModel(added.id,ModelFeatureSelection(true,provider,"record-a"))
            assertTrue(SubAgentPreferences.saveProfileParallelLimit(added.id,provider,"record-a","api-name",6))
            assertEquals(6,SubAgentPreferences.parallelLimit(provider,"api-name"))
            assertEquals(1,SubAgentPreferences.parallelLimit(provider,"record-a"))
            SubAgentPreferences.saveModel(added.id,ModelFeatureSelection(true,provider,"record-b"))
            assertFalse(SubAgentPreferences.saveProfileParallelLimit(added.id,provider,"record-a","api-name",9))
            SubAgentPreferences.remove(added.id)
            assertFalse(SubAgentPreferences.saveProfileParallelLimit(added.id,provider,"record-b","api-name",9))
            assertEquals(6,SubAgentPreferences.parallelLimit(provider,"api-name"))
        } finally { SubAgentPreferences.remove(added.id) }
    }

}
