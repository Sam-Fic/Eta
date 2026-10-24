package io.github.mangi.eta.agent.model

import io.github.mangi.eta.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderUrlsTest {
    @Test
    fun appendsProviderSpecificPathsToRootBaseUrl() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ProviderUrls.openAiChatCompletionsUrl("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            ProviderUrls.openAiModelsUrl("https://api.openai.com/v1")
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            ProviderUrls.openAiResponsesUrl("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            ProviderUrls.anthropicMessagesUrl("https://api.anthropic.com/")
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            ProviderUrls.anthropicModelsUrl("https://api.anthropic.com")
        )
        // client_version 取自 App 版本号，不在测试里写死：改版本号不该让这条断言变红。
        assertEquals(
            "https://chatgpt.com/backend-api/codex/models" +
                "?client_version=${BuildConfig.VERSION_NAME.substringBefore('-').substringBefore('+')}" +
                "&limit=100",
            ProviderUrls.openAiModelsUrl("https://chatgpt.com/backend-api/codex")
        )
    }
}
