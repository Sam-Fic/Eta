package io.github.mangi.eta.data.model

internal object ProviderAuthModes {
    const val API_KEY = "api_key"
    const val OPENAI_CHATGPT = "openai_chatgpt"
    const val XAI_GROK = "xai_grok"
    // Retain a tombstone when reading old backups; never reinterpret as a subscription login.
    const val REMOVED = "removed_oauth"

    const val DEFAULT = API_KEY

    /** 旧版的 "oauth" 一律视为 Codex 订阅登录，迁移时无需改写本地数据库。 */
    fun parse(value: String?): String {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            OPENAI_CHATGPT, "oauth" -> OPENAI_CHATGPT
            XAI_GROK -> XAI_GROK
            "oauth_antigravity", REMOVED -> REMOVED
            else -> API_KEY
        }
    }

    fun isOAuth(mode: String): Boolean = mode == OPENAI_CHATGPT || mode == XAI_GROK
}

internal val ProviderSetting.usesOAuth: Boolean
    get() = ProviderAuthModes.isOAuth(authMode)
