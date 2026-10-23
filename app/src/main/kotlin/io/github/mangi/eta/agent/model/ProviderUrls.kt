package io.github.mangi.eta.agent.model

import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.data.provider.ProviderConnectionProfile

internal object ProviderUrls {
    fun normalizeBaseUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/')

    fun openAiChatCompletionsUrl(baseUrl: String): String =
        appendPath(baseUrl, "chat/completions")

    fun openAiResponsesUrl(baseUrl: String): String =
        appendPath(baseUrl, "responses")

    fun openAiModelsUrl(baseUrl: String): String {
        val url = appendPath(baseUrl, "models")
        return if (normalizeBaseUrl(baseUrl) == ProviderConnectionProfile.OPENAI_CODEX.baseUrl) {
            "$url?client_version=${BuildConfig.VERSION_NAME.substringBefore('-').substringBefore('+')}&limit=100"
        } else {
            url
        }
    }

    fun openAiAudioSpeechUrl(baseUrl: String): String =
        appendPath(baseUrl, "audio/speech")

    fun openAiImagesGenerationsUrl(baseUrl: String): String =
        appendPath(baseUrl, "images/generations")

    fun openAiImagesEditsUrl(baseUrl: String): String =
        appendPath(baseUrl, "images/edits")

    fun openAiVideosUrl(baseUrl: String): String =
        appendPath(baseUrl, "videos")

    fun openAiVideoUrl(baseUrl: String, videoId: String): String =
        appendPath(baseUrl, "videos/${videoId.trim().trim('/')}")

    fun openAiVideoContentUrl(baseUrl: String, videoId: String): String =
        appendPath(baseUrl, "videos/${videoId.trim().trim('/')}/content")

    fun openAiVideoGenerationsUrl(baseUrl: String): String =
        appendPath(baseUrl, "video/generations")

    fun openAiVideoGenerationUrl(baseUrl: String, taskId: String): String =
        appendPath(baseUrl, "video/generations/${taskId.trim().trim('/')}")

    fun openAiVideosGenerationsUrl(baseUrl: String): String =
        appendPath(baseUrl, "videos/generations")

    fun anthropicMessagesUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/messages")

    fun anthropicModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/models")

    private fun appendPath(baseUrl: String, path: String): String =
        "${normalizeBaseUrl(baseUrl)}/${path.trimStart('/')}"
}
