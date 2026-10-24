package io.github.mangi.eta.data.model

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ProviderClientFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovedProviderPolicyTest {
    @Test fun removedModesAndHostsAreRejected() {
        assertTrue(RemovedProviderPolicy.isRemoved("https://proxy.example/v1", "antigravity"))
        assertTrue(RemovedProviderPolicy.isRemoved("https://proxy.example/v1", authMode = "oauth_antigravity"))
        assertTrue(RemovedProviderPolicy.isRemoved("https://proxy.example/v1", authMode = ProviderAuthModes.parse("oauth_antigravity")))
        listOf("cloudcode-pa.googleapis.com", "daily-cloudcode-pa.googleapis.com", "daily-cloudcode-pa.sandbox.googleapis.com").forEach {
            assertTrue(RemovedProviderPolicy.isRemoved("https://$it/v1internal"))
        }
    }

    @Test fun ordinaryGeminiCodexAndUnrelatedHostsRemainSupported() {
        listOf("https://generativelanguage.googleapis.com/v1beta/openai", "https://chatgpt.com/backend-api/codex", "https://api.anthropic.com", "https://cloudcode-pa.googleapis.com.example.org").forEach {
            assertFalse(RemovedProviderPolicy.isRemoved(it))
        }
        // 订阅登录只认现役取值；"oauth" 只是 parse() 的兼容别名，不再是可存储的认证方式。
        assertTrue(ProviderAuthModes.isOAuth(ProviderAuthModes.OPENAI_CHATGPT))
        assertTrue(ProviderAuthModes.isOAuth(ProviderAuthModes.XAI_GROK))
        assertFalse(ProviderAuthModes.isOAuth("oauth"))
        assertFalse(ProviderAuthModes.isOAuth("oauth_antigravity"))
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, ProviderAuthModes.parse("oauth"))
        assertEquals(ProviderAuthModes.REMOVED, ProviderAuthModes.parse("oauth_antigravity"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestHeadersRejectOldHostBeforeNetworkRequest() {
        // 拦截点随订阅改造移到连接入口（ProviderClientFactory / AuthorizedProviderHttp），
        // 请求头合并本身不再负责校验 host。
        ProviderClientFactory.getClient(
            AgentModelClient.ModelConfig(
                baseUrl = "https://daily-cloudcode-pa.googleapis.com",
                apiKey = "test", model = "test", systemPrompt = "",
            ),
        )
    }
}
