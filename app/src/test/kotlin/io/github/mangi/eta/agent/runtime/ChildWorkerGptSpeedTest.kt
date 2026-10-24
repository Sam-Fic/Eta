package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.GptServiceTier
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.OpenAiChatCompletionsProvider
import io.github.mangi.eta.agent.model.ResponsesRequestBuilder
import io.github.mangi.eta.agent.runtime.ChildTaskConfigPolicy as Policy
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChildWorkerGptSpeedTest {
    private val profile = SubAgentProfile("worker", "Worker", role = "review", providerId = "p", modelId = "m", reasoning = ReasoningEffort.HIGH)
    private val model = Model("m", "gpt-5", "GPT", reasoning = true,
        reasoningCapabilities = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.HIGH), canDisable = true))
    private val provider = OpenAiCompatibleProviderSetting("p", "Provider", "https://example.invalid", apiKey = "test", models = listOf(model))
    private fun speed(mode: GptSpeedMode, base: SubAgentProfile = profile) = base.copy(
        gptSpeedByModel = mapOf(SubAgentProfile.modelReasoningKey(base.providerId, base.modelId) to mode))
    private suspend fun resolve(selected: SubAgentProfile = profile, source: ProviderSetting = provider,
        incoming: GptSpeedMode = GptSpeedMode.ULTRA_FAST,
    ) = ChildWorkerConfigResolver.resolveWorker("owner", ConversationSubAgentConfig(listOf(selected)), selected.id, selected.role,
        providerLookup = { source }, modelResolver = {
            RuntimeConfigRepository.buildRuntimeConfig(source, source.models.single()).copy(gptSpeedMode = incoming)
        })

    @Test fun resolvedChildControlsBothActualRequestTiersAndNeverChangesReasoning() = runBlocking {
        for (endpoint in listOf(OpenAiEndpointMode.CHAT_COMPLETIONS, OpenAiEndpointMode.RESPONSES)) {
            val source = provider.copy(endpointMode = endpoint)
            for ((mode, tier) in listOf(GptSpeedMode.NORMAL to "default", GptSpeedMode.FAST to "fast", GptSpeedMode.ULTRA_FAST to "ultrafast")) {
                val candidate = resolve(speed(mode), source)
                assertEquals(Policy.Availability.AVAILABLE, candidate.availability)
                val config = requireNotNull(candidate.configuration).model
                assertEquals(mode, config.gptSpeedMode)
                assertEquals(ReasoningEffort.HIGH, config.effectiveReasoningEffort)
                assertTrue(config.thinkingEnabled)
                val request = if (endpoint == OpenAiEndpointMode.RESPONSES)
                    ResponsesRequestBuilder.build(config, JSONArray(), JSONArray())
                else OpenAiChatCompletionsProvider.buildRequestJson(config, JSONArray(), JSONArray())
                assertEquals(tier, request.getString("service_tier"))
                assertEquals("high", if (endpoint == OpenAiEndpointMode.RESPONSES)
                    request.getJSONObject("reasoning").getString("effort") else request.getString("reasoning_effort"))
            }
        }
        val default = requireNotNull(resolve().configuration).model
        assertEquals(GptSpeedMode.NORMAL, default.gptSpeedMode)
        val off = requireNotNull(resolve(speed(GptSpeedMode.FAST).copy(reasoning = ReasoningEffort.OFF)).configuration).model
        assertEquals(GptSpeedMode.FAST, off.gptSpeedMode)
        assertEquals(ReasoningEffort.OFF, off.effectiveReasoningEffort)
        assertFalse(off.thinkingEnabled)
    }

    @Test fun oauthCodexChildUsesSubscriptionSpeedWireValues() = runBlocking {
        // Codex 由订阅认证方式识别，光把 baseUrl 设成官方域名已经不够了。
        val oauth = provider.copy(
            baseUrl = "https://chatgpt.com/backend-api/codex",
            endpointMode = OpenAiEndpointMode.RESPONSES,
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        val expected = mapOf(GptSpeedMode.NORMAL to null, GptSpeedMode.FAST to "priority")
        for ((mode, tier) in expected) {
            val result = resolve(speed(mode), oauth)
            assertEquals(Policy.Availability.AVAILABLE, result.availability)
            val config = requireNotNull(result.configuration).model
            assertEquals(mode, config.gptSpeedMode)
            assertEquals(ReasoningEffort.HIGH, config.effectiveReasoningEffort)
            val request = ResponsesRequestBuilder.build(config, JSONArray(), JSONArray())
            if (tier == null) assertFalse(request.has("service_tier")) else assertEquals(tier, request.getString("service_tier"))
            assertEquals("high", request.getJSONObject("reasoning").getString("effort"))
        }
    }

    @Test fun oauthCodexChildRejectsUltraFastWithoutSilentDowngrade() = runBlocking {
        // Codex 由订阅认证方式识别，光把 baseUrl 设成官方域名已经不够了。
        val oauth = provider.copy(
            baseUrl = "https://chatgpt.com/backend-api/codex",
            endpointMode = OpenAiEndpointMode.RESPONSES,
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        val result = resolve(speed(GptSpeedMode.ULTRA_FAST), oauth)
        assertEquals(Policy.Availability.AVAILABLE, result.availability)
        val config = requireNotNull(result.configuration).model
        assertEquals(GptSpeedMode.ULTRA_FAST, config.gptSpeedMode)
        assertEquals(ReasoningEffort.HIGH, config.effectiveReasoningEffort)
        val failure = assertThrows(AgentModelFailure::class.java) {
            ResponsesRequestBuilder.build(config, JSONArray(), JSONArray())
        }
        assertEquals(GptServiceTier.UNSUPPORTED_CODEX_TIER, failure.code)
        assertFalse(failure.retryable)
    }

    @Test fun customProviderChildTiersReachBothWireFormats() = runBlocking {
        for (endpoint in listOf(OpenAiEndpointMode.RESPONSES, OpenAiEndpointMode.CHAT_COMPLETIONS)) {
            val source = CustomProviderSetting("p", "GPT", "https://example.invalid", apiKey = "test",
                endpointMode = endpoint, models = listOf(model.copy(modelId = "gpt-6-astra")))
            for ((mode, tier) in listOf(GptSpeedMode.NORMAL to "default", GptSpeedMode.FAST to "fast", GptSpeedMode.ULTRA_FAST to "ultrafast")) {
                val result = resolve(speed(mode), source)
                assertEquals(Policy.Availability.AVAILABLE, result.availability)
                val config = requireNotNull(result.configuration).model
                assertEquals(mode, config.gptSpeedMode)
                val body = if (endpoint == OpenAiEndpointMode.RESPONSES)
                    ResponsesRequestBuilder.build(config, JSONArray(), JSONArray())
                else OpenAiChatCompletionsProvider.buildRequestJson(config, JSONArray(), JSONArray())
                assertEquals(tier, body.getString("service_tier"))
                assertEquals("gpt-6-astra", body.getString("model"))
            }
        }
    }

    @Test fun onlyNonGptBindingClearsIncomingSpeedAndIgnoresStoredMemory() = runBlocking {
        val nonGpt = provider.copy(models = listOf(model.copy(modelId = "claude-sonnet")))
        val anthropic = AnthropicProviderSetting("p", "Anthropic", "https://example.invalid", apiKey = "test", models = listOf(model))
        val image = provider.copy(models = listOf(model.copy(outputModalities = listOf(Model.IMAGE_MODALITY))))
        val media = speed(GptSpeedMode.FAST).copy(role = "image_generation", reasoning = null)
        for ((selected, source) in listOf(speed(GptSpeedMode.FAST) to nonGpt)) {
            val candidate = resolve(selected, source)
            assertEquals(Policy.Availability.AVAILABLE, candidate.availability)
            assertNull(requireNotNull(candidate.configuration).model.gptSpeedMode)
            assertEquals(resolve(selected.copy(gptSpeedByModel = emptyMap()), source).configurationRevision, candidate.configurationRevision)
        }
        for ((selected, source) in listOf(speed(GptSpeedMode.FAST) to anthropic, media to image)) {
            val candidate = resolve(selected, source)
            assertEquals(Policy.Availability.AVAILABLE, candidate.availability)
            assertEquals(GptSpeedMode.FAST, requireNotNull(candidate.configuration).model.gptSpeedMode)
        }
        val returned = resolve(speed(GptSpeedMode.FAST))
        assertEquals(GptSpeedMode.FAST, returned.configuration?.model?.gptSpeedMode)
    }

    @Test fun revisionsUseOnlyCurrentEffectiveSpeedAndFrozenContinuationNeverReresolves() = runBlocking {
        val memory = mutableMapOf("p\u0000m" to GptSpeedMode.FAST)
        val initial = resolve(profile.copy(gptSpeedByModel = memory))
        val original = requireNotNull(initial.configuration)
        val revision = requireNotNull(initial.configurationRevision)
        memory["p\u0000m"] = GptSpeedMode.ULTRA_FAST
        assertEquals(GptSpeedMode.FAST, original.profile.gptSpeedForModel())
        assertEquals(GptSpeedMode.FAST, original.model.gptSpeedMode)
        val edited = resolve(speed(GptSpeedMode.ULTRA_FAST))
        assertNotEquals(revision, edited.configurationRevision)
        val otherMemory = speed(GptSpeedMode.FAST).copy(gptSpeedByModel = mapOf(
            "p\u0000m" to GptSpeedMode.FAST, "p\u0000other" to GptSpeedMode.NORMAL, "other\u0000m" to GptSpeedMode.ULTRA_FAST))
        assertEquals(revision, resolve(otherMemory).configurationRevision)
        val frozen = Policy.Snapshot(initial.worker, "generation", revision, original)
        val ordinary = Policy.ordinary(initial.worker, frozen, true) { error("must not resolve new settings") }
        assertSame(original, ordinary.configuration)
        val task = Policy.TaskKey(initial.worker, "generation", "task")
        val evidence = Policy.Evidence(task, "review", "awaiting_decision", observationVersion = 1)
        val continued = Policy.continuation(task, frozen, evidence)
        assertSame(original, continued.configuration)
        assertEquals(GptSpeedMode.FAST, continued.configuration?.model?.gptSpeedMode)
        val restored = Json.decodeFromString<AgentModelClient.ModelConfig>(RuntimeConfigRepository.runtimeConfigJson(original.model))
        assertEquals(GptSpeedMode.FAST, restored.gptSpeedMode)
        assertEquals(original.model.reasoningEffort, restored.reasoningEffort)
    }

    @Test fun mediaMetadataChangeDuringResolutionPreservesGptSpeed() = runBlocking {
        var reads = 0
        val changed = provider.copy(models = listOf(model.copy(outputModalities = listOf(Model.AUDIO_MODALITY))))
        val selected = speed(GptSpeedMode.FAST)
        val candidate = ChildWorkerConfigResolver.resolveWorker("owner", ConversationSubAgentConfig(listOf(selected)), selected.id, selected.role,
            providerLookup = { if (reads++ == 0) provider else changed },
            modelResolver = { RuntimeConfigRepository.buildRuntimeConfig(provider, model) })
        assertEquals(Policy.Availability.AVAILABLE, candidate.availability)
        assertEquals(GptSpeedMode.FAST, requireNotNull(candidate.configuration).model.gptSpeedMode)
    }

    @Test fun actualModelChangeDuringResolutionCannotMixRevisions() = runBlocking {
        var reads = 0
        val changed = provider.copy(models = listOf(model.copy(modelId = "claude-sonnet")))
        val selected = speed(GptSpeedMode.FAST)
        val candidate = ChildWorkerConfigResolver.resolveWorker("owner", ConversationSubAgentConfig(listOf(selected)), selected.id, selected.role,
            providerLookup = { if (reads++ == 0) provider else changed },
            modelResolver = { RuntimeConfigRepository.buildRuntimeConfig(provider, model) })
        assertEquals(Policy.Availability.SELECTION_CHANGED_DURING_RESOLUTION, candidate.availability)
        assertNull(candidate.configuration)
    }
}
