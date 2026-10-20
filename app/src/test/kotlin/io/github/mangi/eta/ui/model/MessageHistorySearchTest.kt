package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageHistorySearchTest {
    private val roles = MessageSearchRoleLabels(
        user = "You",
        assistant = "Eta",
        thinking = "Thinking",
        tool = "Tool",
    )

    @Test
    fun returnsOldestMatchesFirst() {
        val older = AgentChatUiState(
            messages = listOf(
                UserMessageUi(id = "u1", content = "remember the apple pie"),
                AgentMessageUi(id = "a1", content = "Noted the apple request"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        val newer = AgentChatUiState(
            messages = listOf(
                UserMessageUi(id = "u2", content = "another apple later"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )

        val hits = searchConversationMessages(
            conversations = mapOf("old" to older, "new" to newer),
            titles = mapOf("old" to "Old chat", "new" to "New chat"),
            updatedAt = mapOf("old" to 1L, "new" to 9L),
            query = "apple",
            unnamedTitle = "Untitled",
            roleLabels = roles,
        )

        assertEquals(listOf("u1", "a1", "u2"), hits.map { it.messageId })
        assertEquals("Old chat", hits.first().conversationTitle)
        assertEquals("You", hits.first().roleLabel)
    }

    @Test
    fun ignoresBlankQuery() {
        val hits = searchConversationMessages(
            conversations = mapOf(
                "c" to AgentChatUiState(
                    messages = listOf(UserMessageUi(id = "u1", content = "hello")),
                    input = "",
                    isStreaming = false,
                    thinkingEnabled = false,
                ),
            ),
            titles = emptyMap(),
            updatedAt = emptyMap(),
            query = "   ",
            unnamedTitle = "Untitled",
            roleLabels = roles,
        )
        assertEquals(emptyList<MessageSearchHit>(), hits)
    }

    @Test
    fun titleMatchCarriesNoMessageTarget() {
        val hit = searchConversationTitle("c1", "翻译请求", "翻译", "标题")
        assertNotNull(hit)
        assertEquals("c1", hit!!.conversationId)
        assertEquals("翻译请求", hit.conversationTitle)
        assertEquals("", hit.messageId)
        assertEquals("标题", hit.roleLabel)
        assertTrue(hit.isTitleMatch)
        assertEquals(MessageSearchHitKind.Title, hit.kind)
    }

    @Test
    fun titleMatchIsCaseInsensitiveAndExcerptsAroundTheNeedle() {
        val hit = searchConversationTitle("c1", "Weekly Report", "weekly", "Title")
        assertNotNull(hit)
        assertTrue(hit!!.snippet.contains("Weekly Report"))
    }

    @Test
    fun titleMatchSkipsBlankTitlesAndNonMatchingQueries() {
        assertNull(searchConversationTitle("c1", "   ", "报告", "标题"))
        assertNull(searchConversationTitle("c1", "翻译请求", "日程", "标题"))
        assertNull(searchConversationTitle("c1", "翻译请求", "  ", "标题"))
    }

    @Test
    fun bodyMatchesAreNotTitleMatches() {
        val hits = searchConversationMessages(
            conversations = mapOf(
                "c" to AgentChatUiState(
                    messages = listOf(UserMessageUi(id = "u1", content = "翻译请求")),
                    input = "",
                    isStreaming = false,
                    thinkingEnabled = false,
                ),
            ),
            titles = mapOf("c" to "会话标题"),
            updatedAt = mapOf("c" to 1L),
            query = "翻译",
            unnamedTitle = "Untitled",
            roleLabels = roles,
        )
        assertEquals(1, hits.size)
        assertFalse(hits.first().isTitleMatch)
        assertEquals(MessageSearchHitKind.Message, hits.first().kind)
    }
}
