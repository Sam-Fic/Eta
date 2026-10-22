package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.config.RequestOverheadCalibrationStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.ui.model.*
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
class AgentContextPendingRegressionTest {
    @Test fun firstPendingStaysNoneAndEveryTerminalResultConsumesItEvenWithoutARequest() = fixture { f ->
        for ((index, outcome) in listOf(
            AgentRuntimeWire.RunResult("", true, "done"),
            AgentRuntimeWire.RunResult("", false, "", "failed"),
            AgentRuntimeWire.RunResult("", false, "", "已停止"),
            AgentRuntimeWire.RunResult("", true, ""),
        ).withIndex()) {
            for (requestStarted in listOf(false, true)) {
                val id = "first-$index-$requestStarted"
                val run = "run-$id"
                f.put(id, f.pending())
                f.bind(run, id)
                assertFalse(f.state(id).contextHasStarted)
                assertEquals("0k", formatContextUsage(f.usage(id)))
                if (requestStarted) f.send(run, AgentEvent.ProviderRequestStarted(1))
                assertEquals("0k", formatContextUsage(f.usage(id)))
                call(f.app, "applyRunResult", run, outcome.copy(runId = run), false)
                assertTrue(f.state(id).contextHasStarted)
                assertEquals("Unknown", formatContextUsage(f.usage(id)))
                assertNull(f.usage(id).progress)
            }
        }
    }

    @Test fun selectingModelOnEmptyConversationDoesNotConsumeFirstTurn() = fixture { f ->
        val empty = f.pending().copy(history = emptyList(), messages = emptyList(), isStreaming = false,
            providerId = "", modelId = "")
        f.put("empty", empty)
        f.put("empty", empty.copy(providerId = f.provider.id, modelId = "m"))
        assertFalse(f.state("empty").contextHasStarted)
        assertFalse(f.state("empty").contextAwaitingReceipt)
        assertEquals("0k", formatContextUsage(f.usage("empty")))
        f.put("empty", f.state("empty").copy(modelId = "other"))
        assertEquals("0k", formatContextUsage(f.usage("empty")))
    }

    @Test fun providerAndRetryBoundariesKeepActualAndSameRequestEvidence() = fixture { f ->
        f.put("c", f.pending())
        f.bind("r", "c")
        f.send("r", AgentEvent.ProviderRequestStarted(1))
        val oldHistory = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        f.send("r", receipt(1, oldHistory))
        assertEquals(15000, f.usage("c").contextTokens)
        assertFalse(f.usage("c").estimated)
        f.put("c", f.state("c").copy(history = f.state("c").history +
            AgentModelClient.ConversationMessage("assistant", "more history")))
        f.send("r", AgentEvent.ProviderRequestStarted(2))
        val pending = f.state("c")
        assertEquals(15000, pending.livePromptTokens)
        assertEquals("r:1", pending.contextReceiptEvidence?.requestId)
        assertEquals("r:1", pending.cloudReceiptRequestId)
        assertEquals(15000, pending.contextBudgetReceiptTokens)
        assertEquals(oldHistory, pending.cloudHistoryTokens)
        assertEquals(10000, pending.cloudRequestOverheadTokens)
        assertFalse(f.usage("c").estimated)
        assertEquals(15000, f.usage("c").contextTokens)
        assertNull(pending.receiptPredictionTokens)
        assertEquals(15000, call(f.app, "budgetReceiptTokens", pending))
        assertEquals(15000, call(f.app, "budgetReceiptTokens", pending.copy(receiptPredictionTokens = 99999)))
        assertEquals(15000, call(f.app, "billedPromptTokens", pending))
        f.send("r", AgentEvent.ModelRetryScheduled(2, 1, 3, 1000, "NETWORK"))
        assertEquals(15000, f.state("c").livePromptTokens)
        assertFalse(f.usage("c").estimated)
        assertEquals(15000, f.state("c").contextBudgetReceiptTokens)
        f.send("r", AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 90000), projected = true))
        assertEquals(15000, f.state("c").livePromptTokens)
        assertNull(f.state("c").receiptPredictionTokens)
        f.send("r", receipt(2, oldHistory, input = 16000))
        assertEquals(16000, f.state("c").contextBudgetReceiptTokens)
        assertEquals(16000, f.usage("c").contextTokens)
        assertNull(f.state("c").receiptPredictionTokens)
        assertFalse(f.usage("c").estimated)
        // Retry/start are not evidence: a same-request partial usage correction retains its pair.
        f.send("r", AgentEvent.ModelRetryScheduled(2, 2, 3, 1000, "NETWORK"))
        f.send("r", AgentEvent.ProviderRequestStarted(2))
        f.send("r", AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 16000), requestHistoryTokens = oldHistory))
        assertEquals(10000, f.state("c").cloudRequestOverheadTokens)
        assertEquals(16000, f.state("c").contextBudgetReceiptTokens)
        // A genuinely new requestId replaces evidence, without borrowing the preceding basis.
        f.send("r", AgentEvent.ProviderRequestStarted(3))
        assertEquals(16000, f.state("c").livePromptTokens)
        f.send("r", AgentEvent.UsageReceived(3, AgentTokenUsage(inputTokens = 17000)))
        assertEquals(17000, f.state("c").livePromptTokens)
        assertEquals("r:3", f.state("c").contextReceiptEvidence?.requestId)
        assertNull(f.state("c").cloudRequestOverheadTokens)
        assertNull(f.state("c").contextBudgetReceiptTokens)
        assertFalse(f.usage("c").estimated)
    }

    @Test fun largeNormalGrowthKeepsActualButRouteChangeInvalidatesIt() = fixture { f ->
        f.put("c", f.pending())
        f.bind("r", "c")
        val history = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        f.send("r", receipt(1, history))
        f.put("c", f.state("c").copy(history = f.state("c").history +
            AgentModelClient.ConversationMessage("assistant", "x".repeat(100000))))
        f.send("r", AgentEvent.ProviderRequestStarted(2))
        assertEquals(15000, f.usage("c").contextTokens)
        assertFalse(f.usage("c").estimated)
        assertEquals(15000, f.state("c").contextBudgetReceiptTokens)
        f.providers(f.provider.copy(baseUrl = "https://other.example/v1"))
        f.send("r", AgentEvent.ProviderRequestStarted(3))
        assertNull(f.state("c").livePromptTokens)
        assertNull(f.state("c").contextBudgetReceiptTokens)
        assertNull(f.state("c").receiptPredictionTokens)
        assertEquals("Unknown", formatContextUsage(f.usage("c")))
    }

    @Test fun nextMessageThenToolsRetryStopAndDatabaseRestoreKeepLatestActual() = fixture { f ->
        f.put("c", f.pending())
        f.bind("old-run", "c")
        val oldHistory = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        f.send("old-run", receipt(1, oldHistory))
        val actual = f.state("c")
        // Ordinary send commits the visible reply before launchConversationRun's display helper.
        val visible = actual.copy(messages = actual.messages + AgentMessageUi("assistant-old-run-1", "done"))
        val nextHistory = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
            visible.history, visible.messages)
        val next = call(f.app, "contextStateForRequestHistory", visible, nextHistory) as AgentChatHomeUiState
        assertEquals(visible, next)
        f.put("c", next.copy(history = nextHistory + AgentModelClient.ConversationMessage("user", "next")))
        f.bind("next-run", "c")
        f.send("next-run", AgentEvent.ProviderRequestStarted(1))
        f.send("next-run", AgentEvent.ProviderRequestStarted(2)) // tool continuation
        f.send("next-run", AgentEvent.ModelRetryScheduled(2, 1, 3, 1000, "NETWORK"))
        f.send("next-run", AgentEvent.ProviderRequestStarted(2))
        assertEquals(15000, f.usage("c").contextTokens)
        assertFalse(f.usage("c").estimated)
        assertEquals(actual.contextReceiptEvidence, f.state("c").contextReceiptEvidence)
        call(f.app, "applyRunResult", "next-run",
            AgentRuntimeWire.RunResult("next-run", false, "", "已停止"), false)
        val stopped = f.state("c")
        assertFalse(stopped.isStreaming)
        assertEquals(15000, stopped.livePromptTokens)
        assertEquals("old-run:1", stopped.cloudReceiptRequestId)
        val context = RuntimeEnvironment.getApplication() as Context
        kotlinx.coroutines.runBlocking {
            AgentConversationStore.save(context, "c", mapOf("c" to stopped), mapOf("c" to "test"), mapOf("c" to 1L))
        }
        EtaDatabase.closeForTests()
        val restored = AgentConversationStore.load(context).conversationsById.getValue("c")
        assertEquals(15000, restored.livePromptTokens)
        assertEquals(stopped.contextReceiptEvidence, restored.contextReceiptEvidence)
        assertEquals(stopped.cloudReceiptRequestId, restored.cloudReceiptRequestId)
        assertEquals(15000, restored.contextBudgetReceiptTokens)
        assertFalse(restored.contextAwaitingReceipt)
        f.put("c", restored)
        f.bind("old-run", "c") // resumed/replayed request correction after process recovery
        f.send("old-run", AgentEvent.ProviderRequestStarted(1))
        f.send("old-run", AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 15000, outputTokens = 20)))
        assertEquals(oldHistory, f.state("c").cloudHistoryTokens)
        assertEquals(10000, f.state("c").cloudRequestOverheadTokens)
        f.bind("fresh-run", "c")
        f.send("fresh-run", AgentEvent.ProviderRequestStarted(1))
        assertEquals(15000, f.usage("c").contextTokens)
        f.send("fresh-run", AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 16000)))
        assertEquals(16000, f.usage("c").contextTokens)
        assertFalse(f.usage("c").estimated)
        assertEquals("fresh-run:1", f.state("c").cloudReceiptRequestId)
        assertNull(f.state("c").cloudHistoryTokens) // new request must not borrow restored evidence
        assertNull(f.state("c").cloudRequestOverheadTokens)
    }

    @Test fun ordinarySendMigratesRuntimeAssistantIdentityWithoutClearingActual() = fixture { f ->
        // Runtime drain does not carry turnId: the user is tagged but its reply is not.
        val pending = f.pending().copy(isStreaming = false,
            history = listOf(
                AgentModelClient.ConversationMessage("user", "question", turnId = "old-turn"),
                AgentModelClient.ConversationMessage("assistant", "done")),
            messages = listOf(UserMessageUi("u", "question"), AgentMessageUi("assistant-r-1", "done")))
        f.put("c", pending)
        f.bind("r", "c")
        f.send("r", receipt(1, pending.history.sumOf { AgentContextBudget.countMessage(it) }))
        val actual = f.state("c")
        val committed = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(actual.history, actual.messages)
        assertEquals(listOf("old-turn", "old-turn"), committed.map { it.turnId })
        assertEquals(actual.history.map { it.copy(turnId = "") }, committed.map { it.copy(turnId = "") })
        val next = call(f.app, "contextStateForRequestHistory", actual, committed) as AgentChatHomeUiState
        assertEquals(actual, next)
        f.put("c", next.copy(history = committed + AgentModelClient.ConversationMessage("user", "next", turnId = "next-turn")))
        f.bind("next-run", "c")
        f.send("next-run", AgentEvent.ProviderRequestStarted(1))
        assertEquals(15000, f.usage("c").contextTokens)
        assertFalse(f.usage("c").estimated)
        assertEquals(actual.contextReceiptEvidence, f.state("c").contextReceiptEvidence)
    }

    @Test fun ordinarySendCompletesOnlyTheVisibleTrailingAssistantAndRejectsRealEdits() = fixture { f ->
        val pending = f.pending().copy(isStreaming = false,
            history = listOf(
                AgentModelClient.ConversationMessage("user", "question", turnId = "old-turn"),
                AgentModelClient.ConversationMessage("assistant", "done")),
            messages = listOf(UserMessageUi("u", "question"), AgentMessageUi("assistant-r-1", "done continued")))
        f.put("c", pending)
        f.bind("r", "c")
        f.send("r", receipt(1, pending.history.sumOf { AgentContextBudget.countMessage(it) }))
        val actual = f.state("c")
        val committed = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(actual.history, actual.messages)
        assertEquals(actual.history.size, committed.size)
        assertEquals("done continued", committed.last().content)
        assertEquals("old-turn", committed.last().turnId)
        val next = call(f.app, "contextStateForRequestHistory", actual, committed) as AgentChatHomeUiState
        assertEquals(actual, next)
        f.put("c", next.copy(history = committed + AgentModelClient.ConversationMessage("user", "next")))
        f.bind("next-run", "c")
        f.send("next-run", AgentEvent.ProviderRequestStarted(1))
        assertEquals(15000, f.usage("c").contextTokens)
        assertFalse(f.usage("c").estimated)
        // A prefix-looking edit is not the exact visible commit. All other request fields matter too.
        val rewrites = listOf(
            emptyList(), actual.history.take(1),
            committed.dropLast(1) + committed.last().copy(content = "done arbitrary edit"),
            committed.dropLast(1) + committed.last().copy(content = "replacement"),
            listOf(committed.first().copy(content = "question edited"), committed.last()),
            listOf(committed.first().copy(contentJson = "[{\"type\":\"text\",\"text\":\"edited\"}]"), committed.last()),
            committed.dropLast(1) + committed.last().copy(toolCallsJson = "[{\"id\":\"changed\"}]"),
        )
        for (rewritten in rewrites) {
            val revised = call(f.app, "contextStateForRequestHistory", actual, rewritten) as AgentChatHomeUiState
            assertNull(revised.livePromptTokens)
            assertNull(revised.contextReceiptEvidence)
            assertTrue(revised.contextAwaitingReceipt)
            assertEquals("Unknown", formatContextUsage(liveContextUsage(
                billedContextTokens = revised.livePromptTokens, contextDisplayPolicy = contextDisplayPolicy(revised))))
        }
    }

    @Test fun customRouteKeepsActualAcrossRequestsAndReloadButCannotLearnRatios() = fixture { f ->
        val custom = f.provider.copy(customHeaders = listOf(io.github.mangi.eta.data.model.CustomHeader("X-Route", "custom")))
        f.providers(custom)
        assertEquals("", RequestOverheadCalibration.routeSignature(custom, custom.models.first()))
        f.put("c", f.pending())
        f.bind("r", "c")
        val history = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        for (round in 1..3) {
            f.send("r", AgentEvent.ProviderRequestStarted(round))
            f.send("r", receipt(round, history), replaying = false)
        }
        val actual = f.state("c")
        assertTrue(requireNotNull(actual.cloudRouteSignature).startsWith("actual-local-v1:"))
        val frozenScope = f.runScope("r")
        assertEquals(actual.cloudRouteSignature, frozenScope)
        f.bind("r", "c") // same-configuration reattachment preserves actual and the frozen run scope
        assertEquals(actual, f.state("c"))
        assertEquals(frozenScope, f.runScope("r"))
        f.send("r", receipt(4, history))
        assertEquals(15000, f.usage("c").contextTokens)
        assertFalse(f.usage("c").estimated)
        assertEquals("r:4", f.state("c").cloudReceiptRequestId)
        assertEquals(frozenScope, f.state("c").cloudRouteSignature)
        assertNull(RequestOverheadCalibrationStore.read(custom.id, "m"))
        // 学习签名已退场：同一有效实际回执同时解锁预算 seed（不再只看 learning 签名）。
        assertEquals(15000, call(f.app, "budgetReceiptTokens", actual))
        f.bind("next-run", "c")
        f.send("next-run", AgentEvent.ProviderRequestStarted(1))
        f.send("next-run", AgentEvent.ModelRetryScheduled(1, 1, 3, 1000, "NETWORK"))
        assertEquals(15000, f.usage("c").contextTokens)
        assertFalse(f.usage("c").estimated)
        val context = RuntimeEnvironment.getApplication() as Context
        kotlinx.coroutines.runBlocking {
            AgentConversationStore.save(context, "c", mapOf("c" to f.state("c")), mapOf("c" to "test"), mapOf("c" to 1L))
        }
        EtaDatabase.closeForTests()
        val restored = AgentConversationStore.load(context).conversationsById.getValue("c")
        assertEquals(actual.cloudRouteSignature, restored.cloudRouteSignature)
        f.put("c", restored)
        f.bind("fresh-run", "c")
        f.send("fresh-run", AgentEvent.ProviderRequestStarted(1))
        assertEquals(15000, f.usage("c").contextTokens)
    }

    @Test fun changingSameIdCustomConfigurationRevokesActualAndOldRunEligibility() = fixture { f ->
        val header = io.github.mangi.eta.data.model.CustomHeader("X-Route", "custom")
        val custom = f.provider.copy(customHeaders = listOf(header))
        val changedConfigurations = listOf(
            custom.copy(baseUrl = "https://other.example/v1"),
            custom.copy(customHeaders = listOf(header.copy(value = "changed"))),
            custom.copy(customBody = listOf(io.github.mangi.eta.data.model.CustomBody("route",
                kotlinx.serialization.json.JsonPrimitive("changed")))),
            custom.copy(models = custom.models.map { it.copy(customHeaders = listOf(header.copy(value = "model"))) }),
            custom.copy(baseUrl = "https://example.org/v1?route=changed"),
            custom.copy(baseUrl = "https://user@example.org/v1"),
            custom.copy(sessionGatewayJson = "{\"route\":\"changed\"}"),
        )
        for ((index, changed) in changedConfigurations.withIndex()) {
            f.providers(custom)
            val id = "custom-$index"
            val run = "old-$index"
            f.put(id, f.pending())
            f.bind(run, id)
            f.send(run, receipt(1, f.state(id).history.sumOf { AgentContextBudget.countMessage(it) }))
            assertEquals(15000, f.usage(id).contextTokens)
            f.providers(changed) // exact provider-flow invalidation, before a new request boundary
            assertEquals("Unknown", formatContextUsage(f.usage(id)))
            assertNull(f.state(id).contextReceiptEvidence)
            f.bind(run, id) // reattachment under B cannot revive the invalidated A run
            f.send(run, receipt(1, 10, input = 99000), replaying = false)
            assertNull(f.state(id).livePromptTokens)
            assertEquals("Unknown", formatContextUsage(f.usage(id)))
            f.providers(custom) // reverting settings must not re-authorize the old run
            f.bind(run, id)
            f.send(run, receipt(2, 10, input = 99000), replaying = false)
            assertNull(f.state(id).livePromptTokens)
            assertNull(f.state(id).contextReceiptEvidence)
            val freshRun = java.util.UUID.randomUUID().toString()
            f.bind(freshRun, id)
            f.send(freshRun, receipt(1, 10, input = 16000))
            assertEquals(16000, f.usage(id).contextTokens)
            assertFalse(f.usage(id).estimated)
            val freshActual = f.state(id)
            assertEquals("$freshRun:1", freshActual.cloudReceiptRequestId)
            f.bind(run, id) // rejected old bind must not steal the fresh run's usage ownership
            f.send(run, receipt(3, 10, input = 99000), replaying = false)
            // Historical bills may update messages; they must not change the current context receipt.
            val afterOldReceipt = f.state(id)
            assertEquals(freshActual.livePromptTokens, afterOldReceipt.livePromptTokens)
            assertEquals(freshActual.contextReceiptEvidence, afterOldReceipt.contextReceiptEvidence)
            assertEquals(freshActual.cloudReceiptRequestId, afterOldReceipt.cloudReceiptRequestId)
            assertEquals(freshActual.cloudRouteSignature, afterOldReceipt.cloudRouteSignature)
            f.send(freshRun, receipt(2, 10, input = 17000))
            assertEquals(17000, f.usage(id).contextTokens)
            assertFalse(f.usage(id).estimated)
            assertEquals("$freshRun:2", f.state(id).cloudReceiptRequestId)
        }
        assertNull(RequestOverheadCalibrationStore.read(custom.id, "m"))
    }

    @Test fun historyRevisionAndTrueCompressionOpenUnknownEpochUntilFreshReceipt() = fixture { f ->
        f.put("c", f.pending())
        f.bind("r", "c")
        val history = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        f.send("r", receipt(1, history))
        val actual = f.state("c")
        for (rewritten in listOf(emptyList(), listOf(AgentModelClient.ConversationMessage("user", "edited")))) {
            val revised = call(f.app, "contextStateForRequestHistory", actual, rewritten) as AgentChatHomeUiState
            assertNull(revised.livePromptTokens)
            assertNull(revised.contextReceiptEvidence)
            assertNull(revised.receiptPredictionTokens)
            assertTrue(revised.contextAwaitingReceipt)
            assertTrue(revised.contextHasStarted)
        }
        f.send("r", AgentEvent.ContextCompacted(2, false, 10, 10, history = actual.history))
        assertEquals(15000, f.usage("c").contextTokens) // unsuccessful compression cannot invalidate
        f.send("r", AgentEvent.ContextCompacted(2, true, 10, 2,
            history = listOf(AgentModelClient.ConversationMessage("system", "summary")), compressorLabel = "摘要压缩"))
        assertEquals("Unknown", formatContextUsage(f.usage("c")))
        assertNull(f.usage("c").progress)
        f.bind("r", "c") // reconnect must keep the post-compaction resume floor rejecting round 1
        assertEquals("Unknown", formatContextUsage(f.usage("c")))
        // Check before request-start, so only the retained compaction floor can reject this bill.
        f.send("r", AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 99000)))
        assertNull(f.state("c").livePromptTokens)
        assertEquals("Unknown", formatContextUsage(f.usage("c")))
        f.send("r", AgentEvent.ProviderRequestStarted(2))
        f.send("r", AgentEvent.ModelRetryScheduled(2, 1, 3, 1000, "NETWORK"))
        f.send("r", AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 99000)))
        f.send("r", AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 99000), projected = true))
        assertNull(f.state("c").livePromptTokens)
        assertEquals("Unknown", formatContextUsage(f.usage("c")))
        f.send("r", receipt(2, history, input = 8000))
        assertEquals(8000, f.usage("c").contextTokens)
        assertFalse(f.usage("c").estimated)
    }

    @Test fun liveReceiptsNeverPersistDisplayLearningButStillApplyTheRealBillAndBudgetSeed() = fixture { f ->
        f.put("c", f.pending())
        f.bind("zero-run", "c")
        // 这条回归故意投递 live usage：学习删除后仍不得写入持久样本。
        fun sendLive(event: AgentEvent) = f.send("zero-run", event, replaying = false)
        val history = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        for (round in 1..3) {
            sendLive(AgentEvent.ProviderRequestStarted(round))
            sendLive(receipt(round, history))
        }
        // 三样本跨请求/会话显示学习已删除：没有任何生产调用方写持久样本。
        assertNull(RequestOverheadCalibrationStore.read(f.provider.id, "m"))
        // 真实回执、预算 seed 与诊断仍然照常工作。
        assertEquals(15000, f.usage("c").contextTokens)
        assertFalse(f.usage("c").estimated)
        assertEquals(15000, f.state("c").contextBudgetReceiptTokens)
        assertEquals(15000, call(f.app, "budgetReceiptTokens", f.state("c")))
        sendLive(receipt(3, history, overhead = 0))
        assertEquals(15000, f.usage("c").contextTokens) // still a genuine cloud receipt
        assertNull(RequestOverheadCalibrationStore.read(f.provider.id, "m"))
        val context = RuntimeEnvironment.getApplication() as Context
        kotlinx.coroutines.runBlocking {
            AgentConversationStore.save(context, "c", mapOf("c" to f.state("c")), mapOf("c" to "test"), mapOf("c" to 1L))
        }
        EtaDatabase.closeForTests()
        assertNull(RequestOverheadCalibrationStore.read(f.provider.id, "m"))
    }

    @Test fun effectiveMeasurementStaysKnownForPrefixGrowthAndTurnIdMetadataButNotRouteChange() = fixture { f ->
        f.put("c", f.pending())
        f.bind("r", "c")
        val history = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        f.send("r", AgentEvent.ProviderRequestStarted(1))
        assertNull(call(f.app, "validMeasuredContextTokens", f.state("c"))) // 尚未收到回执 = 未知
        f.send("r", receipt(1, history))
        val actual = f.state("c")
        assertEquals(15000, call(f.app, "validMeasuredContextTokens", actual))
        // 普通前缀增长/补齐已显示 assistant 与 turnId 元数据差仍复用同一实测。
        val committed = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
            actual.history, actual.messages)
        val normalised = call(f.app, "contextStateForRequestHistory", actual, committed) as AgentChatHomeUiState
        assertEquals(actual, normalised)
        assertEquals(15000, call(f.app, "validMeasuredContextTokens", normalised))
        // 等待新回执、换路由、换模型都不再算实测。
        assertNull(call(f.app, "validMeasuredContextTokens", actual.copy(contextAwaitingReceipt = true)))
        assertNull(call(f.app, "validMeasuredContextTokens", actual.copy(cloudRouteSignature = "other-scope")))
        assertNull(call(f.app, "validMeasuredContextTokens", actual.copy(modelId = "other")))
        f.providers(f.provider.copy(baseUrl = "https://other.example/v1"))
        assertNull(call(f.app, "validMeasuredContextTokens", f.state("c")))
    }

    @Test fun customGatewayActualReceiptUnlocksNeitherExemptionNorLearningAndKeepsTheBudgetSeed() = fixture { f ->
        val custom = f.provider.copy(customHeaders = listOf(io.github.mangi.eta.data.model.CustomHeader("X-Route", "custom")))
        f.providers(custom)
        f.put("c", f.pending())
        f.bind("r", "c")
        val history = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        f.send("r", AgentEvent.ProviderRequestStarted(1))
        f.send("r", receipt(1, history))
        val actual = f.state("c")
        // learning 签名对 headers 一律 fail closed；实际路由摘要仍然可验证。
        assertEquals("", RequestOverheadCalibration.routeSignature(custom, custom.models.first()))
        assertTrue(requireNotNull(actual.cloudRouteSignature).startsWith("actual-local-v1:"))
        assertEquals(15000, call(f.app, "validMeasuredContextTokens", actual))
        // 预算 seed 必须与未测豁免共用同一有效实际回执判定，而不是旧的 learning 签名。
        assertEquals(15000, call(f.app, "budgetReceiptTokens", actual))
        assertNull(call(f.app, "budgetReceiptTokens", actual.copy(contextAwaitingReceipt = true)))
        // 跨模型/跨配置不得复用旧值。
        assertNull(call(f.app, "budgetReceiptTokens", actual.copy(modelId = "other")))
        f.providers(custom.copy(customHeaders = listOf(io.github.mangi.eta.data.model.CustomHeader("X-Route", "changed"))))
        assertNull(call(f.app, "validMeasuredContextTokens", f.state("c")))
        assertNull(call(f.app, "budgetReceiptTokens", f.state("c")))
        assertNull(RequestOverheadCalibrationStore.read(custom.id, "m"))
    }

    @Test fun runtimeUnmeasuredExemptionOnlyForTrulyUnknownOrCommittedSummary() = fixture { f ->
        f.put("c", f.pending())
        f.bind("r", "c")
        val history = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        // 真未知：允许 Runtime 依赖自身策略，UI 不因本地估计追加拦截。
        assertTrue(call(f.app, "unmeasuredContextSendAllowed", f.state("c"), false) as Boolean)
        f.send("r", AgentEvent.ProviderRequestStarted(1))
        f.send("r", receipt(1, history))
        // 有有效实测：不给未测豁免（known 仍受既有锚点/本地硬限约束）。
        assertFalse(call(f.app, "unmeasuredContextSendAllowed", f.state("c"), false) as Boolean)
        // 只有摘要真正提交过才放行；prune-only/摘要失败不会传 true。
        assertTrue(call(f.app, "unmeasuredContextSendAllowed", f.state("c"), true) as Boolean)
        // 换路由后退化为未知 → 再次允许。
        f.providers(f.provider.copy(baseUrl = "https://other.example/v1"))
        assertTrue(call(f.app, "unmeasuredContextSendAllowed", f.state("c"), false) as Boolean)
    }

    @Test fun uiPruneOnlyKeepsReceiptAndSeedOnTheNextOrdinarySend() = fixture { f ->
        val original = listOf(AgentModelClient.ConversationMessage("tool", "long output ".repeat(100), toolCallId = "tool-1"))
        val pruned = listOf(original.single().copy(content = "retained head and tail"))
        val nextUser = AgentModelClient.ConversationMessage("user", "next question")
        f.put("c", f.pending().copy(history = original + nextUser))
        f.bind("r", "c")
        f.send("r", receipt(1, original.sumOf { AgentContextBudget.countMessage(it) }))
        val before = f.state("c")
        call(f.app, "applyCompressedHistoryToConversation", "c", original, pruned, nextUser, "", false)
        val after = f.state("c")
        assertEquals(pruned + nextUser, after.history.map { it.copy(turnId = "") })
        assertEquals(before.livePromptTokens, after.livePromptTokens)
        assertEquals(before.contextReceiptEvidence, after.contextReceiptEvidence)
        assertEquals(before.cloudHistoryTokens, after.cloudHistoryTokens)
        assertEquals(before.cloudRequestOverheadTokens, after.cloudRequestOverheadTokens)
        assertEquals(before.messages, after.messages)
        val normalHistory = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(after.history, after.messages)
        val requestState = call(f.app, "contextStateForRequestHistory", after, normalHistory) as AgentChatHomeUiState
        assertEquals(after, requestState)
        assertEquals(15000, call(f.app, "validMeasuredContextTokens", requestState))
        assertEquals(15000, call(f.app, "budgetReceiptTokens", requestState))
        assertFalse(call(f.app, "unmeasuredContextSendAllowed", requestState, false) as Boolean)
        call(f.app, "applyRunResult", "r", AgentRuntimeWire.RunResult("r", false, "", "failed"), false)
        assertEquals(15000, call(f.app, "budgetReceiptTokens", f.state("c")))
        assertEquals(before.contextReceiptEvidence, f.state("c").contextReceiptEvidence)
    }

    @Test fun manualPruneKeepsReceiptButCommittedSummaryStartsUnknownEpoch() = fixture { f ->
        val original = listOf(AgentModelClient.ConversationMessage("tool", "long output ".repeat(100), toolCallId = "tool-1"))
        val pruned = listOf(original.single().copy(content = "short output"))
        f.put("c", f.pending().copy(history = original, isStreaming = false))
        f.bind("r", "c")
        f.send("r", receipt(1, original.sumOf { AgentContextBudget.countMessage(it) }))
        val before = f.state("c")
        call(f.app, "applyManualCompressedHistory", "c", original, pruned, "", false)
        val after = f.state("c")
        assertEquals(15000, after.livePromptTokens)
        assertEquals(before.contextReceiptEvidence, after.contextReceiptEvidence)
        val next = call(f.app, "contextStateForRequestHistory", after,
            after.history + AgentModelClient.ConversationMessage("user", "ordinary followup")) as AgentChatHomeUiState
        assertEquals(15000, call(f.app, "budgetReceiptTokens", next))
        assertFalse(call(f.app, "unmeasuredContextSendAllowed", next, false) as Boolean)
        val summary = listOf(AgentModelClient.ConversationMessage("user", "[对话摘要]\nsummary"))
        call(f.app, "applyManualCompressedHistory", "c", pruned, summary, "", true)
        assertNull(f.state("c").livePromptTokens)
        assertNull(f.state("c").contextBudgetReceiptTokens)
        assertTrue(f.state("c").contextAwaitingReceipt)
    }

    private fun receipt(round: Int, history: Int, input: Int = 15000, overhead: Int = 10000) =
        AgentEvent.UsageReceived(round, AgentTokenUsage(inputTokens = input),
            requestHistoryTokens = history, requestOverheadTokens = overhead)

    private class Fixture(val app: AgentAppState) {
        val provider = OpenAiCompatibleProviderSetting("pending-test", "Test", "https://example.org/v1",
            models = listOf(Model("m", "model", "Model", contextWindow = 100000), Model("other", "other", "Other")))
        init { providers(provider) }
        fun providers(provider: OpenAiCompatibleProviderSetting) {
            call(app, "updateSelectionProviders", listOf(provider))
        }
        fun pending() = AgentChatHomeUiState(messages = listOf(UserMessageUi("u", "question")),
            history = listOf(AgentModelClient.ConversationMessage("user", "question")),
            input = "", isStreaming = true, thinkingEnabled = false, providerId = provider.id, modelId = "m")
        fun put(id: String, state: AgentChatHomeUiState) { call(app, "updateConversation", id, state, false) }
        fun bind(run: String, id: String) { call(app, "bindUsageRun", run, id) }
        fun runScope(run: String): String? =
            (app.javaClass.getDeclaredField("runUsageRoutes").apply { isAccessible = true }.get(app) as Map<*, *>)[run] as String?
        fun state(id: String) = call(app, "conversationState", id) as AgentChatHomeUiState
        fun send(run: String, event: AgentEvent, replaying: Boolean = true) {
            call(app, "applyRunEvent", run, event, false, replaying)
        }
        fun usage(id: String): AgentContextUsageUi {
            val state = state(id)
            return liveContextUsage(
                billedContextTokens = state.livePromptTokens.takeUnless { state.livePromptIsProjected },
                contextDisplayPolicy = contextDisplayPolicy(state))
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        // Only exercise synchronous reducers: no runtime, persistence, or observer jobs escape the fixture.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { it.cancel() }
        try { block(Fixture(AgentAppState(context, scope))) }
        finally { scope.cancel(); EtaDatabase.closeForTests() }
    }

    companion object {
        private fun call(target: Any, name: String, vararg args: Any?): Any? {
            val method = target.javaClass.declaredMethods.single { it.name == name && it.parameterCount >= args.size }
            val values = if (method.parameterCount == args.size) args else {
                val extra = method.parameterTypes.drop(args.size)
                require(extra.all { it == Boolean::class.javaPrimitiveType })
                Array(method.parameterCount) { index -> if (index < args.size) args[index] else false }
            }
            return method.apply { isAccessible = true }.invoke(target, *values)
        }
    }
}
