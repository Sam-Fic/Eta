package io.github.mangi.eta.ui.pages.providers

import androidx.compose.runtime.saveable.mapSaver
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.CustomHeaderFilter
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.BalanceOption
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.SessionGatewayRule
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.util.UUID

internal data class ProviderHeaderDraft(
    val id: String = UUID.randomUUID().toString(),
    val header: CustomHeader = CustomHeader("", ""),
)

internal data class ProviderConfigDraft(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val authMode: String = ProviderAuthModes.DEFAULT,
    val isEnabled: Boolean,
    val endpointMode: String,
    val responsesStripReasoningStatus: Boolean = false,
    val hostedWebSearchEnabled: Boolean,
    val anthropicVersion: String,
    val headers: List<ProviderHeaderDraft> = emptyList(),
    val bodies: List<ProviderBodyDraft> = emptyList(),
    val balanceOption: BalanceOption = BalanceOption(),
    val sessionModelPattern: String = SessionGatewayRule.DEFAULT_MODEL,
    val sessionPathPattern: String = SessionGatewayRule.DEFAULT_PATH,
    val sessionRetention: String = SessionGatewayRule.DEFAULT_RETENTION,
    val sessionKeySource: String = SessionGatewayRule.DEFAULT_SOURCE,
    val sessionKeyField: String = SessionGatewayRule.DEFAULT_FIELD,
) {
    companion object {
        fun from(provider: ProviderSetting): ProviderConfigDraft = ProviderConfigDraft(
            headers = provider.customHeaders.map { ProviderHeaderDraft(header = it) },
            bodies = provider.customBody.map { ProviderBodyDraft(key = it.key, valueJson = it.value.toString()) },
            name = provider.name,
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            authMode = ProviderAuthModes.parse(provider.authMode),
            isEnabled = provider.isEnabled,
            endpointMode = when (provider) {
                is OpenAiCompatibleProviderSetting -> provider.endpointMode
                is CustomProviderSetting -> provider.endpointMode
                is AnthropicProviderSetting -> ""
            },
            responsesStripReasoningStatus = provider.responsesStripReasoningStatus,
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
            balanceOption = provider.balanceOption,
            sessionModelPattern = SessionGatewayRule.decode(provider.sessionGatewayJson).modelPattern,
            sessionPathPattern = SessionGatewayRule.decode(provider.sessionGatewayJson).pathPattern,
            sessionRetention = SessionGatewayRule.decode(provider.sessionGatewayJson).retention,
            sessionKeySource = SessionGatewayRule.decode(provider.sessionGatewayJson).keySource,
            sessionKeyField = SessionGatewayRule.decode(provider.sessionGatewayJson).keyField,
        )
    }
}

internal val ProviderConfigDraftSaver = mapSaver(
    save = { draft ->
        mapOf(
            "headers" to ArrayList(draft.headers.flatMap { listOf(it.id, it.header.name, it.header.value) }),
            "bodies" to ArrayList(draft.bodies.flatMap { listOf(it.id, it.key, it.valueJson) }),
            "name" to draft.name,
            "baseUrl" to draft.baseUrl,
            "apiKey" to draft.apiKey,
            "authMode" to draft.authMode,
            "isEnabled" to draft.isEnabled,
            "endpointMode" to draft.endpointMode,
            "responsesStripReasoningStatus" to draft.responsesStripReasoningStatus,
            "hostedWebSearchEnabled" to draft.hostedWebSearchEnabled,
            "anthropicVersion" to draft.anthropicVersion,
            "balanceOptionEnabled" to draft.balanceOption.enabled,
            "balanceOptionPreset" to draft.balanceOption.preset,
            "balanceOptionApiPath" to draft.balanceOption.apiPath,
            "balanceOptionResultPath" to draft.balanceOption.resultPath,
            "balanceOptionUserId" to draft.balanceOption.userId,
            "balanceOptionAccessToken" to draft.balanceOption.accessToken,
            "sessionModelPattern" to draft.sessionModelPattern,
            "sessionPathPattern" to draft.sessionPathPattern,
            "sessionRetention" to draft.sessionRetention,
            "sessionKeySource" to draft.sessionKeySource,
            "sessionKeyField" to draft.sessionKeyField,
        )
    },
    restore = { state ->
        ProviderConfigDraft(
            headers = (state["headers"] as? List<*>)?.chunked(3)?.map {
                ProviderHeaderDraft(it[0] as String, CustomHeader(it[1] as String, it[2] as String))
            }.orEmpty(),
            bodies = (state["bodies"] as? List<*>)?.chunked(3)?.mapNotNull { fields ->
                if (fields.size == 3 && fields.all { it is String }) {
                    ProviderBodyDraft(fields[0] as String, fields[1] as String, fields[2] as String)
                } else null
            }.orEmpty(),
            name = state.getValue("name") as String,
            baseUrl = state.getValue("baseUrl") as String,
            apiKey = state.getValue("apiKey") as String,
            authMode = ProviderAuthModes.parse(state["authMode"] as? String),
            isEnabled = state.getValue("isEnabled") as Boolean,
            endpointMode = state.getValue("endpointMode") as String,
            responsesStripReasoningStatus = state["responsesStripReasoningStatus"] as? Boolean ?: false,
            hostedWebSearchEnabled = state.getValue("hostedWebSearchEnabled") as Boolean,
            anthropicVersion = state.getValue("anthropicVersion") as String,
            balanceOption = BalanceOption(
                enabled = state["balanceOptionEnabled"] as? Boolean ?: false,
                preset = state["balanceOptionPreset"] as? String ?: BalanceOption.PRESET_CUSTOM,
                apiPath = state["balanceOptionApiPath"] as? String ?: "",
                resultPath = state["balanceOptionResultPath"] as? String ?: "",
                userId = state["balanceOptionUserId"] as? String ?: "",
                accessToken = state["balanceOptionAccessToken"] as? String ?: "",
            ),
            sessionModelPattern = state["sessionModelPattern"] as? String ?: SessionGatewayRule.DEFAULT_MODEL,
            sessionPathPattern = state["sessionPathPattern"] as? String ?: SessionGatewayRule.DEFAULT_PATH,
            sessionRetention = state["sessionRetention"] as? String ?: SessionGatewayRule.DEFAULT_RETENTION,
            sessionKeySource = state["sessionKeySource"] as? String ?: SessionGatewayRule.DEFAULT_SOURCE,
            sessionKeyField = state["sessionKeyField"] as? String ?: SessionGatewayRule.DEFAULT_FIELD,
        )
    },
)

internal fun buildUpdatedProvider(
    source: ProviderSetting,
    name: String,
    baseUrl: String,
    apiKey: String,
    authMode: String = ProviderAuthModes.DEFAULT,
    isEnabled: Boolean,
    endpointMode: String,
    responsesStripReasoningStatus: Boolean = source.responsesStripReasoningStatus,
    hostedWebSearchEnabled: Boolean,
    anthropicVersion: String,
    customHeaders: List<CustomHeader>,
    customBody: List<CustomBody> = source.customBody,
    balanceOption: BalanceOption,
    sessionGatewayJson: String = "",
): ProviderSetting {
    val profile = ProviderConnectionProfile.forAuthMode(authMode)
    return when (source) {
        is OpenAiCompatibleProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            customBody = customBody,
            balanceOption = balanceOption,
            name = name.trim(),
            baseUrl = profile?.baseUrl ?: baseUrl.trim(),
            apiKey = apiKey.trim(),
            authMode = ProviderAuthModes.parse(authMode),
            isEnabled = isEnabled,
            endpointMode = profile?.endpointMode ?: endpointMode,
            responsesStripReasoningStatus = responsesStripReasoningStatus,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
            sessionGatewayJson = sessionGatewayJson,
        )
        is CustomProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            customBody = customBody,
            balanceOption = balanceOption,
            name = name.trim(),
            baseUrl = profile?.baseUrl ?: baseUrl.trim(),
            apiKey = apiKey.trim(),
            authMode = ProviderAuthModes.parse(authMode),
            isEnabled = isEnabled,
            endpointMode = profile?.endpointMode ?: endpointMode,
            responsesStripReasoningStatus = responsesStripReasoningStatus,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
            sessionGatewayJson = sessionGatewayJson,
        )
        is AnthropicProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            customBody = customBody,
            balanceOption = balanceOption,
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            authMode = ProviderAuthModes.parse(authMode),
            isEnabled = isEnabled,
            anthropicVersion = anthropicVersion.trim().ifBlank { AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION },
            sessionGatewayJson = sessionGatewayJson,
        )
    }
}

internal fun ProviderConfigDraft.encodedSessionGateway(): String = SessionGatewayRule.encode(
    SessionGatewayRule(
        modelPattern = sessionModelPattern.ifBlank { SessionGatewayRule.DEFAULT_MODEL },
        pathPattern = sessionPathPattern.ifBlank { SessionGatewayRule.DEFAULT_PATH },
        retention = sessionRetention.ifBlank { SessionGatewayRule.DEFAULT_RETENTION },
        keySource = sessionKeySource.ifBlank { SessionGatewayRule.DEFAULT_SOURCE },
        keyField = sessionKeyField.ifBlank { SessionGatewayRule.DEFAULT_FIELD },
    )
)

internal fun ProviderConfigDraft.toProvider(source: ProviderSetting): ProviderSetting = buildUpdatedProvider(
    source = source,
    name = name,
    baseUrl = baseUrl,
    apiKey = apiKey,
    authMode = authMode,
    isEnabled = isEnabled,
    endpointMode = endpointMode,
    responsesStripReasoningStatus = responsesStripReasoningStatus,
    hostedWebSearchEnabled = hostedWebSearchEnabled,
    anthropicVersion = anthropicVersion,
    customHeaders = headers.map { it.header },
    customBody = parseProviderBodies(bodies).getOrDefault(source.customBody),
    balanceOption = balanceOption,
    sessionGatewayJson = encodedSessionGateway(),
)

internal fun providerAccountAuthMode(provider: ProviderSetting): String? {
    if (provider is AnthropicProviderSetting) return null
    if (ProviderAuthModes.isOAuth(provider.authMode)) return provider.authMode
    return when (ProviderSourceRegistry.resolve(provider)) {
        ProviderSourceTypes.OPENAI -> ProviderAuthModes.OPENAI_CHATGPT
        ProviderSourceTypes.XAI -> ProviderAuthModes.XAI_GROK
        else -> null
    }
}

internal fun ProviderConfigDraft.withAuthMode(mode: String, provider: ProviderSetting): ProviderConfigDraft {
    val profile = ProviderConnectionProfile.forAuthMode(mode)
    if (profile != null) return copy(
        authMode = mode,
        baseUrl = profile.baseUrl,
        endpointMode = profile.endpointMode,
        hostedWebSearchEnabled = hostedWebSearchEnabled && profile.endpointMode == OpenAiEndpointMode.RESPONSES,
    )
    // 从订阅登录切回 API Key 时，地址必须离开订阅网关，避免把密钥发往第三方通道。
    if (!ProviderAuthModes.isOAuth(provider.authMode)) return copy(authMode = mode)
    val chatGpt = provider.authMode == ProviderAuthModes.OPENAI_CHATGPT
    return copy(
        authMode = mode,
        baseUrl = if (chatGpt) ProviderConnectionProfile.OPENAI_API_BASE_URL else ProviderConnectionProfile.XAI_API_BASE_URL,
        endpointMode = if (chatGpt) OpenAiEndpointMode.RESPONSES else OpenAiEndpointMode.CHAT_COMPLETIONS,
    )
}

internal fun validateProviderDraft(context: android.content.Context, draft: ProviderConfigDraft): String? {
    if (draft.name.isBlank()) return context.getString(R.string.page_name_cannot_be_empty_ca8984)
    val uri = runCatching { java.net.URI(draft.baseUrl.trim()) }.getOrNull()
    if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return context.getString(R.string.page_base_url_must_be_a_valid_http_s_address_0e7d58)
    }
    CustomHeaderFilter.validationError(draft.headers.map { it.header })?.let { return it }
    return parseProviderBodies(draft.bodies).exceptionOrNull()?.message
}
