package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderAuthModes
import org.json.JSONArray
import org.json.JSONObject

/** Codex 使用无服务端存储的 Responses，请求约束在编码边界统一处理。 */
internal object CodexResponsesRequest {
    fun configure(request: JSONObject, config: AgentModelClient.ModelConfig) {
        if (config.authMode != ProviderAuthModes.OPENAI_CHATGPT) return
        val unsupported = UNSUPPORTED_FIELDS.filter(request::has)
        require(unsupported.isEmpty()) { "Codex 不支持请求参数：${unsupported.joinToString()}" }
        val include = request.optJSONArray("include") ?: JSONArray()
        if ((0 until include.length()).none { include.optString(it) == "reasoning.encrypted_content" }) {
            include.put("reasoning.encrypted_content")
        }
        request.put("include", include)
        // 官方 Codex 不认 "none"："关闭推理" 只能表达为回落到最小受支持档位，
        // 直接把 none 发过去会被服务端拒（历史上就是这样丢过请求）。
        request.optJSONObject("reasoning")?.let { reasoning ->
            if (reasoning.optString("effort") == "none") {
                reasoning.put("effort", codexFallbackEffort(config))
                reasoning.put("summary", "auto")
            }
        }
        if (!request.has("parallel_tool_calls")) request.put("parallel_tool_calls", true)
    }

    /** Codex 能接受的最低档：取模型支持列表里的第一档，没给能力表时用 medium。 */
    private fun codexFallbackEffort(config: AgentModelClient.ModelConfig): String =
        config.reasoningCapabilities?.supportedEfforts?.firstOrNull()?.wireValue ?: "medium"

    private val UNSUPPORTED_FIELDS = setOf("max_output_tokens", "max_tokens", "temperature", "top_p", "background", "conversation")
}
