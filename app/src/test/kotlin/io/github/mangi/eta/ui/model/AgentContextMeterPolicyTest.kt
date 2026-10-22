package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test

class AgentContextMeterPolicyTest {
    @Test fun protocolPreviewDropsUnsentReasoningWithoutChangingSilentBudget() {
        val history = listOf(AgentModelClient.ConversationMessage("assistant", "answer", reasoningContent = "private".repeat(1000)))
        val base = AgentModelOptionUi("m", "p", "P", "custom", "m", "M", 100000)
        val chat = compressionContextUsage(history, "", emptyList(), base)
        for (endpoint in listOf(io.github.mangi.eta.agent.model.EndpointKind.RESPONSES,
            io.github.mangi.eta.agent.model.EndpointKind.ANTHROPIC_MESSAGES)) {
            val model = base.copy(requestEndpoint = endpoint)
            val preview = liveContextUsage(selectedModel = model)
            assertNull(preview.contextTokens) // Protocol preview is not a learned or measured display value.
            assertTrue(io.github.mangi.eta.agent.model.AgentRequestTokenEstimate.history(history, false, false, endpoint) < requireNotNull(chat.contextTokens))
            val silent = compressionContextUsage(history, "", emptyList(), model, projectedContextTokens = 999999)
            assertEquals(chat.contextTokens, silent.contextTokens)
        }
    }

    @Test fun unmeasuredDisplayIsNeverProjectedAndOnlyACloudReceiptCounts() {
        val first = liveContextUsage(contextDisplayPolicy = ContextDisplayPolicy(firstTurn = true))
        assertNull(first.contextTokens)
        assertEquals("0k", formatContextUsage(first))
        val cloud = liveContextUsage(billedContextTokens = 4321)
        assertEquals(4321, cloud.contextTokens)
        assertFalse(cloud.estimated)
        val compacted = liveContextUsage(contextDisplayPolicy = ContextDisplayPolicy(awaitingReceipt = true))
        assertNull(compacted.contextTokens)
        assertEquals("Unknown", formatContextUsage(compacted))
    }

    @Test fun emptyFirstDraftIncludesSystemAndToolOverhead() {
        val usage = liveContextUsage(contextDisplayPolicy = ContextDisplayPolicy(firstTurn = true))
        assertNull(usage.contextTokens)
        assertEquals("0k", formatContextUsage(usage))
        assertEquals(12000, compressionContextUsage(emptyList(), "", emptyList(), null, requestOverheadTokens = 12000).contextTokens)
    }

    @Test fun cloudRingStaysFixedWhileSilentBudgetAddsHistoryAndToolDeltas() {
        val ring = liveContextUsage(billedContextTokens = 10000)
        val budget = compressionContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 1200, billedContextTokens = 10000, requestOverheadTokens = 700,
            billedHistoryTokens = 1000, billedOverheadTokens = 500)
        assertEquals(10000, ring.contextTokens)
        assertFalse(ring.estimated)
        assertEquals(10400, budget.contextTokens)
        assertTrue(budget.estimated)
    }

    @Test fun unchangedCloudBaselineIsNotMarkedEstimated() {
        val budget = compressionContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 1000, billedContextTokens = 10000, requestOverheadTokens = 700,
            billedHistoryTokens = 1000, billedOverheadTokens = 700)
        assertEquals(10000, budget.contextTokens)
        assertFalse(budget.estimated)
    }

    @Test fun legacyCloudReceiptDoesNotDisableSilentLocalPressure() {
        val ring = liveContextUsage(billedContextTokens = 10000)
        val budget = compressionContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 20000, localHistoryTokenCount = 20000,
            billedContextTokens = 10000, requestOverheadTokens = 3000)
        assertEquals(10000, ring.contextTokens)
        assertEquals(23000, budget.contextTokens)
        val conservative = compressionContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 1000, billedContextTokens = 10000, requestOverheadTokens = 500)
        assertEquals(10000, conservative.contextTokens)
    }

    @Test fun summaryWithoutNewCloudUsageUsesNewHistoryNotOldBill() {
        val history = listOf(AgentModelClient.ConversationMessage("system", "short summary"))
        val usage = liveContextUsage()
        assertNull(usage.contextTokens)
        assertEquals("Unknown", formatContextUsage(usage))
        assertEquals(500 + AgentContextBudget.countMessage(history.single()),
            compressionContextUsage(history, "", emptyList(), null, requestOverheadTokens = 500).contextTokens)
    }

    @Test fun validReceiptPersistsCalibrationAndInvalidHistoryCannotRestoreIt() {
        val raw = CloudUsageReceiptCodec.encode("c", "p", "m", "history", 12345, 6000, 700)
        val receipt = requireNotNull(CloudUsageReceiptCodec.decodeReceipt(raw, "c", "p", "m", "history"))
        assertEquals(12345, receipt.inputTokens)
        assertEquals(6000, receipt.historyTokens)
        assertEquals(700, receipt.overheadTokens)
        assertNull(CloudUsageReceiptCodec.decodeReceipt(raw, "c", "p", "other", "history"))
        assertNull(CloudUsageReceiptCodec.decodeReceipt(raw, "c", "p", "m", "edited"))
        val compacted = CloudUsageReceiptCodec.encode("c", "p", "m", "history", null,
            hasStarted = true, awaitingReceipt = true)
        assertNull(CloudUsageReceiptCodec.decodeReceipt(compacted, "c", "p", "m", "history"))
        assertEquals(CloudUsageReceiptCodec.DisplayState(true, true),
            CloudUsageReceiptCodec.decodeDisplayState(compacted, "c", "history"))
    }

    @Test fun unsentHistoryMediaDoesNotDriftRawCloudCalibration() {
        val part = org.json.JSONObject().put("type", "video_url")
            .put("video_url", org.json.JSONObject().put("url", "data:video/mp4;base64,AAAA"))
        val history = listOf(AgentModelClient.ConversationMessage("user", "", contentJson = org.json.JSONArray().put(part).toString()))
        val raw = history.sumOf { AgentContextBudget.countMessage(it) }
        val preview = liveContextUsage()
        assertNull(preview.contextTokens)
        assertTrue(requireNotNull(compressionContextUsage(history, "", emptyList(), null, requestOverheadTokens = 100).contextTokens) < raw + 100)
        val budget = compressionContextUsage(history, "", emptyList(), null,
            billedContextTokens = 9000, billedHistoryTokens = raw,
            billedOverheadTokens = 100, requestOverheadTokens = 100)
        assertEquals(9000, budget.contextTokens)
        assertEquals(9000, liveContextUsage(billedContextTokens = 9000).contextTokens)
    }

    @Test fun videoOnlyModelStillCountsAcceptedDraftVideo() {
        val model = AgentModelOptionUi("m", "p", "P", "custom", "m", "M", 100000,
            supportsVision = false, supportsVideo = true)
        val attachment = PendingImageUi("v", "content://video", "data:image/jpeg;base64,AAAA", "video/mp4",
            isVideo = true, byteSize = 2 * 1024 * 1024)
        val usage = compressionContextUsage(emptyList(), "watch", listOf(attachment), model)
        assertNull(liveContextUsage(selectedModel = model).contextTokens)
        assertEquals(AgentContextBudget.countCurrentTurn("watch", listOf(attachment.toOutboundModelImage(true))), usage.contextTokens)
    }

    @Test fun anInFlightRunKeepsTheWindowItWasLaunchedWith() {
        // The picker already reports the new 500k limit, but the running request was
        // built against 200k: the percentage must stay on the window it really uses.
        val model = AgentModelOptionUi("m", "p", "P", "custom", "m", "M", 500_000)
        val ring = liveContextUsage(selectedModel = model,
            billedContextTokens = 100_000, activeRunContextWindow = 200_000)
        assertEquals(200_000, ring.contextWindow)
        assertEquals(100_000, ring.contextTokens)

        val budget = compressionContextUsage(emptyList(), "", emptyList(), model,
            historyTokenCount = 1_200, billedContextTokens = 100_000, requestOverheadTokens = 700,
            billedHistoryTokens = 1_000, billedOverheadTokens = 500,
            activeRunContextWindow = 200_000)
        assertEquals(200_000, budget.contextWindow)
    }

    @Test fun withoutAnInFlightRunThePickerWindowIsUsed() {
        val model = AgentModelOptionUi("m", "p", "P", "custom", "m", "M", 500_000)
        assertEquals(500_000,
            liveContextUsage(selectedModel = model, billedContextTokens = 100_000).contextWindow)
        // A non-positive override is ignored rather than hiding the real window.
        assertEquals(500_000,
            liveContextUsage(selectedModel = model,
                billedContextTokens = 100_000, activeRunContextWindow = 0).contextWindow)
    }

}
