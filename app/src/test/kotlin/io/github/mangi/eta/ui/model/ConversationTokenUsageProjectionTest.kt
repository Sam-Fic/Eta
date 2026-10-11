package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationTokenUsageProjectionTest {
    private val usage = TokenUsageUi(inputTokens = 100, outputTokens = 20, cachedTokens = 40)
    private val base: AgentIncrementalList<AgentChatMessageUi> = listOf<AgentChatMessageUi>(
        UserMessageUi(id = "u1", content = "hello"),
        AgentMessageUi(id = "a1", content = "done", usage = usage),
        AgentMessageUi(id = "a2", content = "stream", isStreaming = true),
    ).incrementalSnapshot()

    @Test fun textOnlyReplacementReusesTheTotal() {
        val projection = ConversationTokenUsageProjection()
        val first = projection.project(base)
        val next = base.replacing(2, AgentMessageUi(id = "a2", content = "stream more", isStreaming = true))
        assertSame(first, projection.project(next))
        assertEquals(conversationTokenUsage(next), projection.project(next))
    }

    @Test fun usageChangeOrUncertifiedListAlwaysRescans() {
        val projection = ConversationTokenUsageProjection()
        projection.project(base)
        val receipt = TokenUsageUi(inputTokens = 7, outputTokens = 3)
        val withUsage = base.replacing(2, AgentMessageUi(id = "a2", content = "stream", usage = receipt))
        assertEquals(conversationTokenUsage(withUsage), projection.project(withUsage))
        assertEquals(107L, projection.project(withUsage).inputTokens)

        val appended = withUsage + AgentMessageUi(id = "a3", content = "x", usage = receipt)
        assertEquals(conversationTokenUsage(appended), projection.project(appended))

        val compacted = listOf(ContextCompactedMessageUi(id = "c", compactedCount = 2, summary = "s",
            preservedUsage = ConversationTokenUsageUi(inputTokens = 5)))
        assertEquals(5L, projection.project(compacted).inputTokens)
    }

    @Test fun replacementFromAnOlderSnapshotIsNotTrusted() {
        val projection = ConversationTokenUsageProjection()
        val list = base
        val skipped = list.replacing(1, AgentMessageUi(id = "a1", content = "done", usage = null))
        projection.project(list.replacing(2, AgentMessageUi(id = "a2", content = "other", isStreaming = true)))
        val next = skipped.replacing(2, AgentMessageUi(id = "a2", content = "stream more", isStreaming = true))
        assertEquals(0L, projection.project(next).inputTokens)
    }
}
