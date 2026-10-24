package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.auth.CodexOAuth
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.ProviderAuthModes
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequestHeadersTest {
    @Test
    fun openCodeSessionIsStableAndIsolatedFromOtherConversations() {
        val first = headers("https://opencode.ai/zen/go/v1", "conversation-1")
        val retry = headers("https://opencode.ai/zen/go/v1", "conversation-1")
        val next = headers("https://opencode.ai/zen/go/v1", "conversation-2")
        assertEquals(first["x-opencode-session"], retry["x-opencode-session"])
        assertNotEquals(first["x-opencode-session"], next["x-opencode-session"])
        assertEquals("Eta", first["User-Agent"])
    }

    @Test
    fun sessionHeaderDoesNotLeakToUnrelatedHosts() {
        listOf("https://example.com/v1", "https://opencode.ai.example.com/v1").forEach {
            assertNull(headers(it, "conversation-1")["x-opencode-session"])
        }
    }

    @Test
    fun customUserAgentOverridesDefaultButSessionAndAuthStayManaged() {
        val builder = Headers.Builder().add("Authorization", "Bearer test-key")
        ProviderRequestHeaders.mergeInto(
            builder, "https://opencode.ai/zen/go/v1",
            listOf(
                CustomHeader("user-agent", "test-client"),
                CustomHeader("Authorization", "bad"),
                CustomHeader("X-OpenCode-Session", "fixed"),
            ),
            "conversation-1",
        )
        val result = builder.build()
        assertEquals("test-client", result["User-Agent"])
        assertEquals(1, result.values("User-Agent").size)
        assertEquals("Bearer test-key", result["Authorization"])
        assertNotEquals("fixed", result["x-opencode-session"])
    }

    @Test
    fun editorRejectsInvalidAndDuplicateHeadersWithoutEchoingValues() {
        assertNull(CustomHeaderFilter.validationError(listOf(CustomHeader("User-Agent", "test-client"))))
        listOf(
            listOf(CustomHeader("X-Test", "secret\r\nInjected: value")),
            listOf(CustomHeader("bad:name", "secret")),
            listOf(CustomHeader("Authorization", "secret")),
            listOf(CustomHeader("X-Test", "1"), CustomHeader(" x-test ", "2")),
        ).forEach { headers ->
            val error = CustomHeaderFilter.validationError(headers)
            assertTrue(error != null && !error.contains("secret"))
        }
    }

    @Test
    fun chatgptCodexSendsSubscriptionSessionHeaders() {
        val result = headers(
            "https://chatgpt.com/backend-api/codex", "conversation-1",
            ProviderAuthModes.OPENAI_CHATGPT,
        )
        // Codex 订阅连接才会带会话身份头；API Key 连接不带。
        assertEquals("conversation-1", result["session-id"])
        assertEquals(CodexOAuth.ORIGINATOR, result["originator"])
        assertEquals("responses=experimental", result["OpenAI-Beta"])
    }

    @Test
    fun sessionIdentityIsStablePerConversationOnSubscriptionConnections() {
        val first = headers(
            "https://chatgpt.com/backend-api/codex", "conversation-1", ProviderAuthModes.OPENAI_CHATGPT,
        )
        val again = headers(
            "https://chatgpt.com/backend-api/codex", "conversation-1", ProviderAuthModes.OPENAI_CHATGPT,
        )
        val other = headers(
            "https://chatgpt.com/backend-api/codex", "conversation-2", ProviderAuthModes.OPENAI_CHATGPT,
        )
        assertEquals("conversation-1", first["session-id"])
        assertEquals(first["session-id"], again["session-id"])
        assertNotEquals(first["session-id"], other["session-id"])
    }

    @Test
    fun apiKeyConnectionsDoNotLeakSubscriptionSessionHeaders() {
        // 收窄后：非订阅连接不再无条件发 session-id，避免把普通网关的请求串到同一路由。
        val result = headers("https://gateway.example/v1", "conversation-1")
        assertNull(result["session-id"])
        assertNull(result["originator"])
    }

    private fun headers(
        url: String,
        session: String,
        authMode: String = ProviderAuthModes.API_KEY,
    ): Headers = Headers.Builder().also {
        ProviderRequestHeaders.mergeInto(it, url, emptyList(), session, authMode)
    }.build()
}
