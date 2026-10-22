package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentPruningUsageRegressionTest {
    @Test fun pruningBetweenRealBillsKeepsCloudAnchorAndOnlySummaryOpensNewBoundary() {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val app = AgentAppState(context, scope)
            call(app, "updateSelectionProviders", listOf(
                OpenAiCompatibleProviderSetting("p", "Test", "https://example.org/v1",
                    models = listOf(Model("m", "model", "Model")))))
            val oldHistory = listOf(AgentModelClient.ConversationMessage("user", "original"))
            call(app, "updateConversation", "prune-c", AgentChatHomeUiState(
                messages = listOf(UserMessageUi("old-user", "original")), history = oldHistory,
                input = "", isStreaming = true, thinkingEnabled = false,
                providerId = "p", modelId = "m"), false)
            call(app, "bindUsageRun", "prune-r", "prune-c")
            fun send(event: AgentEvent) { call(app, "applyRunEvent", "prune-r", event, false, true) }
            fun state() = call(app, "conversationStateForRun", "prune-r") as AgentChatHomeUiState
            fun markerCount() = state().messages.count { it is ContextCompactedMessageUi }

            send(AgentEvent.ProviderRequestStarted(147))
            send(AgentEvent.UsageReceived(147, AgentTokenUsage(inputTokens = 209563),
                requestHistoryTokens = 150000, requestOverheadTokens = 10000))
            assertEquals(209563, state().livePromptTokens)
            assertEquals(150000, state().cloudHistoryTokens)
            val beforeMarkers = markerCount()
            val prunedHistory = listOf(AgentModelClient.ConversationMessage("user", "pruned tool body"))
            send(AgentEvent.ContextCompacted(148, true, 190, 190, history = prunedHistory,
                compressorLabel = "工具输出预算修剪（原文可回读）"))
            assertEquals(prunedHistory, state().history)
            assertEquals(beforeMarkers, markerCount())
            assertEquals(209563, state().livePromptTokens)
            assertEquals(150000, state().cloudHistoryTokens)
            assertEquals(10000, state().cloudRequestOverheadTokens)
            send(AgentEvent.UsageReceived(148, AgentTokenUsage(inputTokens = 162108), projected = true))
            // A legacy/in-flight projected event must not replace a retained cloud bill.
            assertEquals(209563, state().livePromptTokens)
            assertFalse(state().livePromptIsProjected)
            assertEquals(150000, state().cloudHistoryTokens)
            assertEquals(10000, state().cloudRequestOverheadTokens)
            send(AgentEvent.UsageReceived(148, AgentTokenUsage(inputTokens = 212441),
                requestHistoryTokens = 151000, requestOverheadTokens = 10000))
            assertEquals(212441, state().livePromptTokens)
            assertFalse(state().livePromptIsProjected)
            assertEquals(beforeMarkers, markerCount())

            send(AgentEvent.ContextCompacted(149, true, 194, 69,
                history = listOf(AgentModelClient.ConversationMessage("system", "summary")),
                compressorLabel = "摘要压缩"))
            assertNull(state().livePromptTokens)
            assertNull(state().cloudHistoryTokens)
            assertTrue(markerCount() > beforeMarkers)
            send(AgentEvent.UsageReceived(149, AgentTokenUsage(inputTokens = 80750), projected = true))
            // Runtime projection remains budget-only while a fresh summary receipt is pending.
            assertNull(state().livePromptTokens)
            assertNull(state().cloudHistoryTokens)
            assertNull(state().contextBudgetReceiptTokens)
            assertNull(state().receiptPredictionTokens)
            assertTrue(state().contextAwaitingReceipt)
            assertEquals("Unknown", io.github.mangi.eta.ui.model.formatContextUsage(
                io.github.mangi.eta.ui.model.liveContextUsage(
                    contextDisplayPolicy = io.github.mangi.eta.ui.model.ContextDisplayPolicy(
                        awaitingReceipt = state().contextAwaitingReceipt))))
            send(AgentEvent.UsageReceived(149, AgentTokenUsage(inputTokens = 95095),
                requestHistoryTokens = 70000, requestOverheadTokens = 10000))
            assertEquals(95095, state().livePromptTokens)
            assertEquals(70000, state().cloudHistoryTokens)
            assertEquals(95095, state().contextBudgetReceiptTokens)
            assertFalse(state().contextAwaitingReceipt)
            assertFalse(state().livePromptIsProjected)
        } finally { scope.cancel(); EtaDatabase.closeForTests() }
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
}
