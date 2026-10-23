package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.ProviderSetting

internal object BuiltinProviders {
    const val DEFAULT_SYSTEM_PROMPT =
        "你是 Eta，运行在 Android 设备上的 AI 助手。你可以回答问题、与用户交流，也可以通过当前可用的工具了解设备情况并执行操作。" +
            "回答使用用户的语言，简洁、直接、自然。"

    const val OPENAI_ID = "builtin-openai"
    const val ANTHROPIC_ID = "builtin-anthropic"
    const val BAILIAN_ID = "builtin-dashscope"
    const val DEEPSEEK_ID = "builtin-deepseek"
    const val KIMI_ID = "builtin-kimi"
    const val MIMO_ID = "builtin-mimo"
    const val MINIMAX_ID = "builtin-minimax"
    const val STEPFUN_ID = "builtin-stepfun"
    const val SILICONFLOW_ID = "builtin-siliconflow"
    const val OPENROUTER_ID = "builtin-openrouter"
    const val XAI_ID = "builtin-xai"

    val PROVIDERS: List<ProviderSetting> = emptyList()

    fun providerById(id: String): ProviderSetting? =
        PROVIDERS.firstOrNull { it.id == id }
}
