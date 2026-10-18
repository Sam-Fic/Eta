package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.repository.MainAgentSpeedDefaultsRepository
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real owner entry points and preferences/Room restore, without network requests or startup jobs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class AgentAppStateGptSpeedMemoryTest {
    @Test fun cycleAndModelProviderSwitchRestoreOnlyTheirOwnSelectionIncludingSameApiAlias() = fixture { f ->
        val app = f.app
        app.selectModel(f.a.id, f.p.id)
        assertEquals(GptSpeedMode.NORMAL, app.homeState.gptSpeedMode)
        app.cycleGptSpeedMode()
        assertEquals(GptSpeedMode.FAST, app.homeState.gptSpeedMode)
        app.cycleGptSpeedMode()
        assertEquals(GptSpeedMode.ULTRA_FAST, app.homeState.gptSpeedMode)
        app.selectModel(f.b.id, f.p.id) // Same API model, distinct selection identity.
        assertEquals(GptSpeedMode.NORMAL, app.homeState.gptSpeedMode)
        app.cycleGptSpeedMode()
        assertEquals(GptSpeedMode.FAST, app.homeState.gptSpeedMode)
        app.selectModel(f.a.id, f.q.id)
        assertEquals(GptSpeedMode.NORMAL, app.homeState.gptSpeedMode)
        app.cycleGptSpeedMode()
        app.selectModel(f.a.id, f.p.id)
        assertEquals(GptSpeedMode.ULTRA_FAST, app.homeState.gptSpeedMode)
        app.cycleGptSpeedMode()
        assertEquals(GptSpeedMode.NORMAL, app.homeState.gptSpeedMode)
        app.selectModel(f.b.id, f.p.id)
        assertEquals(GptSpeedMode.FAST, app.homeState.gptSpeedMode)
        app.selectModel(f.a.id, f.q.id)
        assertEquals(GptSpeedMode.FAST, app.homeState.gptSpeedMode)
    }

    @Test fun nonGptAndUnavailableProjectionNeverEraseGptMemoryOrAcceptSpeedActions() = fixture { f ->
        val app = f.app
        app.selectModel(f.a.id, f.p.id)
        app.cycleGptSpeedMode()
        val stored = f.scalar()
        app.selectModel(f.other.id, f.p.id)
        assertEquals(GptSpeedMode.NORMAL, app.homeState.gptSpeedMode)
        app.cycleGptSpeedMode()
        assertEquals(stored, f.scalar())
        app.selectModel(f.a.id, f.p.id)
        assertEquals(GptSpeedMode.FAST, app.homeState.gptSpeedMode)
        val nonGpt = f.p.copy(models = f.p.models.map { if (it.id == f.a.id) it.copy(modelId = "claude-sonnet") else it })
        call(app, "updateSelectionProviders", listOf(nonGpt, f.q))
        call(app, "refreshBoundModelPicker")
        assertEquals(GptSpeedMode.NORMAL, app.homeState.gptSpeedMode)
        app.cycleGptSpeedMode()
        assertEquals(stored, f.scalar())
        call(app, "updateSelectionProviders", emptyList<OpenAiCompatibleProviderSetting>())
        call(app, "refreshBoundModelPicker")
        app.cycleGptSpeedMode()
        assertEquals(stored, f.scalar())
        call(app, "updateSelectionProviders", listOf(f.p, f.q))
        call(app, "refreshBoundModelPicker")
        assertEquals(GptSpeedMode.FAST, app.homeState.gptSpeedMode)
    }

    @Test fun duplicateOrMissingProviderModelCannotMisrouteSelectionOrOverwriteMemory() = fixture { f ->
        val app = f.app
        app.selectModel(f.a.id, f.p.id)
        app.cycleGptSpeedMode()
        val stored = f.scalar()
        for (providers in listOf(
            listOf(f.p, f.p.copy(isEnabled = false), f.q),
            listOf(f.p.copy(models = listOf(f.a, f.a.copy(isEnabled = false))), f.q),
            listOf(f.p.copy(models = emptyList()), f.q),
            listOf(f.q),
        )) {
            call(app, "updateSelectionProviders", providers)
            call(app, "refreshBoundModelPicker")
            assertEquals(GptSpeedMode.NORMAL, app.homeState.gptSpeedMode)
            assertNull(app.modelPickerState.selectedModel)
            app.cycleGptSpeedMode()
            app.selectModel(f.a.id, f.p.id)
            assertEquals(stored, f.scalar())
            assertEquals(f.p.id, app.homeState.providerId)
        }
        call(app, "updateSelectionProviders", listOf(f.p, f.q))
        call(app, "refreshBoundModelPicker")
        assertEquals(GptSpeedMode.FAST, app.homeState.gptSpeedMode)
    }

    @Test fun conversationSwitchSharedModelChangesAndRoomRestartRestorePersistedChoices() = fixture { f ->
        val app = f.app
        app.selectModel(f.a.id, f.p.id)
        app.cycleGptSpeedMode()
        f.put("a-one", app.homeState)
        f.put("a-two", app.homeState)
        app.selectModel(f.b.id, f.p.id)
        app.cycleGptSpeedMode(); app.cycleGptSpeedMode()
        f.put("b-one", app.homeState)
        app.selectConversation("a-one")
        assertEquals(GptSpeedMode.FAST, app.homeState.gptSpeedMode)
        app.cycleGptSpeedMode()
        app.selectConversation("a-two")
        assertEquals(GptSpeedMode.ULTRA_FAST, app.homeState.gptSpeedMode)
        app.selectConversation("b-one")
        assertEquals(GptSpeedMode.ULTRA_FAST, app.homeState.gptSpeedMode)
        app.cycleGptSpeedMode()
        assertEquals(GptSpeedMode.NORMAL, app.homeState.gptSpeedMode)
        app.selectConversation("a-one")
        assertEquals(GptSpeedMode.ULTRA_FAST, app.homeState.gptSpeedMode)
        @Suppress("UNCHECKED_CAST")
        val states = get(app, "conversationsById") as Map<String, AgentChatHomeUiState>
        runBlocking {
            AgentConversationStore.save(f.context, "a-one", states, emptyMap(),
                mapOf("a-one" to 30L, "a-two" to 20L, "b-one" to 10L))
        }
        val restarted = f.newApp()
        // 启动水合已改为离开主线程异步（见 AgentAppState.init 注释），测试 scope 被取消；
        // 按下方 b-one 同款「手动水合」路径驱动：providers → conversationState → selectConversation。
        val hydrated = runBlocking { AgentConversationStore.loadSuspending(f.context, selectedOnly = true) }
        set(restarted, "conversationsById", hydrated.conversationsById)
        call(restarted, "updateSelectionProviders", listOf(f.p, f.q))
        call(restarted, "refreshBoundModelPicker")
        call(restarted, "conversationState", "a-one")
        restarted.selectConversation("a-one")
        assertEquals(f.a.id, restarted.homeState.modelId)
        assertEquals(GptSpeedMode.ULTRA_FAST, restarted.homeState.gptSpeedMode)
        // Hydrate the unloaded preview through the real Room-backed state loader (no startup job).
        call(restarted, "conversationState", "b-one")
        restarted.selectConversation("b-one")
        assertEquals(GptSpeedMode.NORMAL, restarted.homeState.gptSpeedMode)
        restarted.selectConversation("a-one")
        assertEquals(GptSpeedMode.ULTRA_FAST, restarted.homeState.gptSpeedMode)
        // An external full restore changes the scalar while the same AppState is alive.
        val backup = Prefs.exportAgentPreferences()
        restarted.cycleGptSpeedMode()
        assertEquals(GptSpeedMode.NORMAL, restarted.homeState.gptSpeedMode)
        Prefs.restoreAgentPreferences(backup)
        call(restarted, "refreshBoundModelPicker")
        assertEquals(GptSpeedMode.ULTRA_FAST, restarted.homeState.gptSpeedMode)
    }

    @Test fun ownerChoicesFreezeIntoBothRequestBodiesAndNonGptRequestsHaveNoTier() = fixture { f ->
        fun snapshot(provider: OpenAiCompatibleProviderSetting, model: Model) = GptSpeedModePolicy.snapshot(
            io.github.mangi.eta.data.repository.RuntimeConfigRepository.buildRuntimeConfig(provider, model),
            call(f.app, "rememberedGptSpeedMode", provider.id, model.id) as GptSpeedMode,
            io.github.mangi.eta.data.model.supportsGptSpeedBinding(provider, model),
        )
        fun request(config: io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig, responses: Boolean) =
            if (responses) io.github.mangi.eta.agent.model.ResponsesRequestBuilder.build(
                config, org.json.JSONArray(), org.json.JSONArray(),
            ) else io.github.mangi.eta.agent.model.OpenAiChatCompletionsProvider.buildRequestJson(
                config, org.json.JSONArray(), org.json.JSONArray(),
            )
        f.app.selectModel(f.a.id, f.p.id)
        f.app.cycleGptSpeedMode()
        val frozen = snapshot(f.p, f.a)
        f.app.cycleGptSpeedMode()
        val ultra = snapshot(f.p, f.a)
        f.app.selectModel(f.b.id, f.p.id)
        val normal = snapshot(f.p, f.b)
        f.app.selectModel(f.other.id, f.p.id)
        f.app.cycleGptSpeedMode()
        val nonGpt = snapshot(f.p, f.other)
        assertNull(nonGpt.gptSpeedMode)
        for (responses in listOf(false, true)) {
            assertEquals("fast", request(frozen, responses).getString("service_tier"))
            assertEquals("ultrafast", request(ultra, responses).getString("service_tier"))
            assertEquals("default", request(normal, responses).getString("service_tier"))
            assertFalse(request(nonGpt, responses).has("service_tier"))
        }
        f.app.selectModel(f.a.id, f.p.id)
        assertEquals(GptSpeedMode.ULTRA_FAST, f.app.homeState.gptSpeedMode)
        assertEquals(GptSpeedMode.FAST, frozen.gptSpeedMode)
    }

    @Test fun oldConversationTransientTierDoesNotSeedNewOrDifferentBindings() = fixture { f ->
        f.put("legacy", f.app.homeState.copy(providerId = f.p.id, modelId = f.a.id,
            gptSpeedMode = GptSpeedMode.ULTRA_FAST))
        f.app.selectConversation("legacy")
        assertEquals(GptSpeedMode.NORMAL, f.app.homeState.gptSpeedMode)
        assertNull(f.scalar())
        f.app.selectModel(f.b.id, f.p.id)
        assertEquals(GptSpeedMode.NORMAL, f.app.homeState.gptSpeedMode)
        f.app.cycleGptSpeedMode()
        f.app.selectModel(f.a.id, f.p.id)
        assertEquals(GptSpeedMode.NORMAL, f.app.homeState.gptSpeedMode)
        assertEquals(GptSpeedMode.FAST, MainAgentSpeedDefaultsRepository(
            requireNotNull(Prefs.localAgentPreferences()),
        ).modeFor(f.p.id, f.b.id))
    }

    private class Fixture(val context: Context) {
        val a = Model("a", "gpt-5", "GPT A")
        val b = Model("b", "gpt-5", "GPT B same alias")
        val other = Model("other", "deepseek-chat", "Other")
        val p = OpenAiCompatibleProviderSetting("p", "Relay", "https://example.invalid", models = listOf(a, b, other))
        val q = p.copy(id = "q")
        val app = newApp().also { call(it, "updateSelectionProviders", listOf(p, q)) }
        fun newApp(): AgentAppState {
            val stoppedScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { it.cancel() }
            return AgentAppState(context, stoppedScope)
        }
        fun put(id: String, state: AgentChatHomeUiState) = call(app, "updateConversation", id, state, false)
        fun scalar() = Prefs.localAgentPreferences()?.getString(MainAgentSpeedDefaultsRepository.KEY, null)
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
        Prefs.initLocal(context)
        requireNotNull(Prefs.localAgentPreferences()).edit().clear().commit()
        try { block(Fixture(context)) }
        finally {
            EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
            Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        }
    }

    companion object {
        private fun call(target: Any, name: String, vararg args: Any?): Any? =
            target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
                .apply { isAccessible = true }.invoke(target, *args)
        private fun get(target: Any, name: String): Any? =
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        private fun set(target: Any, name: String, value: Any?) {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
    }
}
