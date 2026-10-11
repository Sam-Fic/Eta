package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Route signatures are memoized per provider snapshot and binding, never across them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class AgentAppStateRouteSignatureCacheTest {
    private val strict = OpenAiCompatibleProviderSetting("p", "Test", "https://example.org/v1",
        apiKey = "first", models = listOf(Model("m", "gpt-5", "Model", contextWindow = 100000),
            Model("n", "gpt-5", "Alias", contextWindow = 100000)))
    private val custom = strict.copy(id = "c", customHeaders = listOf(CustomHeader("X-Route", "a")))

    @Test fun streamingStateChangesReuseTheSameSignatureForOneSnapshotAndBinding() = fixture { app ->
        call(app, "updateSelectionProviders", listOf(strict, custom))
        listOf("p", "c").forEach { providerId ->
            val base = state(providerId, "m")
            val first = signature(app, base)
            assertNotNull(first)
            val entry = entry(app)
            val streamed = base.copy(messages = listOf(AgentMessageUi("a", "x".repeat(4000))), livePromptTokens = 7)
            assertSame(first, signature(app, streamed))
            assertSame(entry, entry(app))
        }
    }

    @Test fun missingBindingIsCachedAndAnyBindingOrSnapshotChangeRecomputes() = fixture { app ->
        call(app, "updateSelectionProviders", listOf(strict))
        assertNull(signature(app, state("p", "missing")))
        val missing = entry(app)
        assertNull(signature(app, state("p", "missing")))
        assertSame(missing, entry(app))

        val m = signature(app, state("p", "m"))
        val n = signature(app, state("p", "n"))
        assertNotNull(m)
        assertNotEquals(m, n)
        assertEquals(m, signature(app, state("p", "m")))

        val before = entry(app)
        call(app, "updateSelectionProviders", listOf(strict.copy(models = strict.models.toList())))
        assertEquals(m, signature(app, state("p", "m")))
        assertNotSame("A new snapshot must never reuse the old entry", before, entry(app))
    }

    @Test fun sameIdConfigurationChangesNeverReturnTheOldSignature() = fixture { app ->
        call(app, "updateSelectionProviders", listOf(strict, custom))
        val strictOld = signature(app, state("p", "m"))
        val customOld = signature(app, state("c", "m"))
        call(app, "updateSelectionProviders", listOf(strict.copy(baseUrl = "https://other.example.org/v1"),
            custom.copy(apiKey = "second")))
        assertNotEquals(strictOld, signature(app, state("p", "m")))
        assertNotEquals(customOld, signature(app, state("c", "m")))
        call(app, "updateSelectionProviders",
            listOf(strict, custom.copy(customHeaders = listOf(CustomHeader("X-Route", "b")))))
        assertEquals(strictOld, signature(app, state("p", "m")))
        assertNotEquals(customOld, signature(app, state("c", "m")))
        call(app, "updateSelectionProviders", listOf(strict.copy(isEnabled = false), custom))
        assertNull(signature(app, state("p", "m")))
    }

    private fun state(providerId: String, modelId: String) =
        AgentChatHomeUiState(messages = emptyList(), input = "", isStreaming = false, thinkingEnabled = false,
            providerId = providerId, modelId = modelId)

    private fun signature(app: AgentAppState, state: AgentChatHomeUiState) =
        call(app, "contextRouteSignature", state) as String?

    private fun entry(app: AgentAppState): Any? = get(app, "contextRouteSignatureEntry")

    private fun fixture(block: (AgentAppState) -> Unit) {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { it.cancel() }
        try { block(AgentAppState(context, startupScope)) } finally { EtaDatabase.closeForTests() }
    }

    private companion object {
        fun call(target: Any, name: String, vararg args: Any?): Any? =
            target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
                .apply { isAccessible = true }.invoke(target, *args)
        fun get(target: Any, name: String): Any? =
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }
}
