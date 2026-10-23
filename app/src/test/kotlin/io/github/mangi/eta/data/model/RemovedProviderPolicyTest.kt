package io.github.mangi.eta.data.model

import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import okhttp3.Headers
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
        assertTrue(ProviderAuthModes.isOAuth("oauth"))
        assertFalse(ProviderAuthModes.isOAuth("oauth_antigravity"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun headersRejectOldHostBeforeNetworkRequest() {
        ProviderRequestHeaders.mergeInto(Headers.Builder(), "https://daily-cloudcode-pa.googleapis.com", emptyList())
    }
}
