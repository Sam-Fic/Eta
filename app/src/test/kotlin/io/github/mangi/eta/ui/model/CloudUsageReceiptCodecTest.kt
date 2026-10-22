package io.github.mangi.eta.ui.model

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CloudUsageReceiptCodecTest {
    @Test fun receiptRestoresOnlyForIdenticalConversationModelAndHistory() {
        val raw = CloudUsageReceiptCodec.encode("c", "p", "m", "history", 152885)
        assertEquals(152885, CloudUsageReceiptCodec.decode(raw, "c", "p", "m", "history"))
        assertNull(CloudUsageReceiptCodec.decode(raw, "other", "p", "m", "history"))
        assertNull(CloudUsageReceiptCodec.decode(raw, "c", "other", "m", "history"))
        assertNull(CloudUsageReceiptCodec.decode(raw, "c", "p", "other", "history"))
        assertNull(CloudUsageReceiptCodec.decode(raw, "c", "p", "m", "edited"))
        assertNull(CloudUsageReceiptCodec.decode("broken", "c", "p", "m", "history"))
        val unknown = CloudUsageReceiptCodec.encode("c", "p", "m", "history", null)
        assertNull(CloudUsageReceiptCodec.decodeReceipt(unknown, "c", "p", "m", "history"))
        assertEquals(CloudUsageReceiptCodec.DisplayState(false, false),
            CloudUsageReceiptCodec.decodeDisplayState(unknown, "c", "history"))
    }

    @Test fun unpairedLatestActualAndRequestEvidenceSurviveCheckpointWhileCompressionFailsClosed() {
        val actual = CloudUsageReceiptCodec.encode("c", "p", "m", "grown history", 43687,
            routeSignature = "route", requestId = "old-run:16")
        val restored = requireNotNull(CloudUsageReceiptCodec.decodeReceipt(actual, "c", "p", "m", "grown history"))
        assertEquals(43687, restored.inputTokens)
        assertEquals("old-run:16", restored.requestId)
        assertEquals("route", restored.routeSignature)
        assertNull(restored.historyTokens)
        assertNull(restored.overheadTokens)
        // A real compression boundary suppresses even an accidentally retained input.
        val compacted = CloudUsageReceiptCodec.encode("c", "p", "m", "summary", 43687,
            hasStarted = true, awaitingReceipt = true, requestId = "old-run:16")
        assertNull(CloudUsageReceiptCodec.decodeReceipt(compacted, "c", "p", "m", "summary"))
        assertEquals(CloudUsageReceiptCodec.DisplayState(true, true),
            CloudUsageReceiptCodec.decodeDisplayState(compacted, "c", "summary"))
        // Legacy receipts have no request identity; never manufacture one on recovery.
        val legacy = CloudUsageReceiptCodec.encode("c", "p", "m", "history", 12345)
        assertNull(CloudUsageReceiptCodec.decodeReceipt(legacy, "c", "p", "m", "history")?.requestId)
    }

    @Test fun unknownCloudUsageStaysUnknownAndDoesNotBecomeMeasuredUsage() {
        val usage = AgentContextUsageUi(null, 260000)
        // Unknown must read as unknown; only a real measurement may show 0K / 0.0%.
        // An unmeasured state still names the configured window it is compared against.
        assertEquals(
            "Unknown / 260K tokens",
            formatContextUsage(usage, noUsageText = "暂无上下文用量", locale = Locale.US),
        )
        assertEquals("0k / 260K tokens", formatContextUsage(usage.copy(firstTurn = true), locale = Locale.US))
        assertNull(usage.contextTokens)
        assertNull(usage.progress)
        assertFalse(isContextWindowExceeded(usage))
        assertEquals("0K / 260K tokens · 0.0%", formatContextUsage(AgentContextUsageUi(0, 260000), locale = Locale.US))
    }
}
