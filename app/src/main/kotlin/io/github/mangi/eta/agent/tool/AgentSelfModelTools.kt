package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.voice.tts.ReadAloudVoiceHistory
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackProfile
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.agent.model.oauth.OpenAiCodexOAuth
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置 Eta 自己的模型：供应商、模型、以及朗读 / 语音对话 / 压缩各自使用的模型。
 *
 * 供应商与模型实体在 Room，指向当前选择的指针在 DataStore，语音侧的选择在 SharedPreferences。
 * 目录在任务开始时冻结，所以任何选择都从下一次回复生效，而不是当前这条。
 */
internal class AgentSelfModelTools(private val context: Context) {

    fun execute(args: JSONObject): String {
        val action = args.optString("action").trim()
        return when (action) {
            "list" -> io { list() }
            "select" -> io { select(args) }
            "save_provider" -> io { saveProvider(args) }
            "delete_provider" -> io { deleteProvider(args) }
            "save_model" -> io { saveModel(args) }
            "delete_model" -> io { deleteModel(args) }
            "fetch_models" -> io { fetchModels(args) }
            else -> error("INVALID_ARGUMENT", "action 必须是 list / select / save_provider / delete_provider")
        }
    }

    private suspend fun list(): String {
        // 被禁用的供应商也列出，否则模型无从知道还有什么可以重新启用。
        val providers = ProviderRepository.allProviders()
        val settings = SettingsDataStore.settings()
        val current = providers.firstOrNull { it.id == settings.selectedProviderId }
        val currentModel = current?.models?.firstOrNull { it.id == settings.selectedModelId }

        val items = JSONArray()
        providers.forEach { provider ->
            val enabledModels = provider.models.filter { it.isEnabled }
            val models = JSONArray()
            enabledModels.take(MAX_LISTED_MODELS).forEach { model ->
                models.put(modelSummary(model))
            }
            // 禁用的模型也要列出来，否则模型无从知道还有什么可以重新启用。
            val disabledModels = JSONArray()
            provider.models.filterNot { it.isEnabled }.take(MAX_LISTED_MODELS).forEach { model ->
                disabledModels.put(modelSummary(model))
            }
            items.put(
                JSONObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("built_in", provider.isBuiltIn)
                    .put("enabled", provider.isEnabled)
                    .put("has_api_key", provider.apiKey.isNotBlank())
                    .put("model_count", enabledModels.size)
                    .put("truncated", enabledModels.size > MAX_LISTED_MODELS)
                    .put("models", models)
                    .put("disabled_models", disabledModels),
            )
        }

        val chat = JSONObject()
            .put("provider", current?.name ?: "")
            .put("model", currentModel?.modelId ?: "")
        val speech = JSONArray()
        listOf(SpeechPlaybackProfile.READ_ALOUD, SpeechPlaybackProfile.CONVERSATION).forEach { profile ->
            val provider = providers.firstOrNull { it.id == Prefs.getString(profile.providerKey) }
            val model = provider?.models?.firstOrNull { it.id == Prefs.getString(profile.modelKey) }
            speech.put(
                JSONObject()
                    .put("target", if (profile == SpeechPlaybackProfile.READ_ALOUD) "tts" else "voice")
                    .put("mode", Prefs.getString(profile.modeKey).ifBlank { "system" })
                    .put("provider", provider?.name ?: "")
                    .put("model", model?.modelId ?: ""),
            )
        }
        val compressProvider = providers.firstOrNull { it.id == Prefs.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID) }
        val compressModel = compressProvider?.models?.firstOrNull { it.id == Prefs.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID) }

        return JSONObject()
            .put("ok", true)
            .put("action", "list")
            .put("chat", chat)
            .put("speech", speech)
            .put(
                "compression",
                JSONObject()
                    .put("custom_model_enabled", Prefs.isCustomCompressModelEnabled())
                    .put("provider", compressProvider?.name ?: "")
                    .put("model", compressModel?.modelId ?: ""),
            )
            .put("providers", items)
            .toString()
    }

    private suspend fun select(args: JSONObject): String {
        val target = args.optString("target").trim().ifBlank { "chat" }
        if (target !in TARGETS) return error("INVALID_ARGUMENT", "target 必须是 chat / tts / voice / compress")
        val providerInput = args.optString("provider").trim()
        if (providerInput.isBlank()) return error("INVALID_ARGUMENT", "select 需要提供 provider")
        val provider = findProvider(enabledProviders(), providerInput)
            ?: return error("PROVIDER_NOT_FOUND", "找不到可用的供应商：$providerInput")
        val modelInput = args.optString("model").trim()
        val model = if (modelInput.isBlank()) {
            null
        } else {
            findModel(provider, modelInput) ?: return error("MODEL_NOT_FOUND", "供应商 ${provider.name} 下找不到模型：$modelInput")
        }
        if (target == "chat") {
            val remembered = SettingsDataStore.selectedModelIdForProvider(provider.id)
            val enabled = provider.models.filter { it.isEnabled }
            val chosen = model ?: enabled.firstOrNull { it.id == remembered } ?: enabled.firstOrNull()
            ?: return error("MODEL_NOT_FOUND", "供应商 ${provider.name} 没有可用模型")
            SettingsDataStore.setSelection(provider.id, chosen.id)
            ProviderRepository.repairSelection()
            return selectionResult(target, provider, chosen)
        }
        val chosen = model ?: return error("INVALID_ARGUMENT", "$target 需要指定 model")
        return when (target) {
            "tts" -> applySpeech(SpeechPlaybackProfile.READ_ALOUD, provider, chosen)
            "voice" -> applySpeech(SpeechPlaybackProfile.CONVERSATION, provider, chosen)
            else -> {
                Prefs.putString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, provider.id)
                Prefs.putString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID, chosen.id)
                Prefs.putBoolean(Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED, true)
                runCatching { Prefs.reconcileAgentPreferences(EtaApp.serviceInstance) }
                selectionResult(target, provider, chosen)
            }
        }
    }

    private suspend fun saveProvider(args: JSONObject): String {
        val name = args.optString("name").trim()
        val baseUrl = args.optString("base_url").trim()
        val apiKey = args.optString("api_key").trim()
        val providerInput = args.optString("provider").trim()
        val providers = ProviderRepository.allProviders()
        val existing = when {
            providerInput.isNotBlank() -> findProvider(providers, providerInput)
            name.isNotBlank() -> findProvider(providers, name)
            else -> null
        } ?: providers.firstOrNull { baseUrl.isNotBlank() && it.baseUrl.trimEnd('/').equals(baseUrl.trimEnd('/'), ignoreCase = true) }

        if (existing == null) {
            if (name.isBlank()) return error("INVALID_ARGUMENT", "新建供应商需要提供 name")
            if (baseUrl.isBlank()) return error("INVALID_ARGUMENT", "新建供应商需要提供 base_url")
            val created = ProviderRepository.addProvider(
                OpenAiCompatibleProviderSetting(
                    id = ProviderRepository.newId(),
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    isEnabled = true,
                    models = emptyList(),
                ),
            )
            val added = addModels(created.id, args)
            return providerResult("created", ProviderRepository.providerById(created.id) ?: created, added)
        }

        val updated = when (existing) {
            is OpenAiCompatibleProviderSetting -> existing.copy(
                name = name.ifBlank { existing.name },
                baseUrl = if (existing.isBuiltIn) existing.baseUrl else baseUrl.ifBlank { existing.baseUrl },
                apiKey = if (args.has("api_key")) apiKey else existing.apiKey,
                isEnabled = isEnabled(args, existing),
            )
            is CustomProviderSetting -> existing.copy(
                name = name.ifBlank { existing.name },
                baseUrl = if (existing.isBuiltIn) existing.baseUrl else baseUrl.ifBlank { existing.baseUrl },
                apiKey = if (args.has("api_key")) apiKey else existing.apiKey,
                isEnabled = isEnabled(args, existing),
            )
            is AnthropicProviderSetting -> existing.copy(
                name = name.ifBlank { existing.name },
                baseUrl = if (existing.isBuiltIn) existing.baseUrl else baseUrl.ifBlank { existing.baseUrl },
                apiKey = if (args.has("api_key")) apiKey else existing.apiKey,
                isEnabled = isEnabled(args, existing),
            )
        }
        ProviderRepository.updateProvider(updated)
        val added = addModels(existing.id, args)
        // 禁用供应商也要把指针挪开，否则选中的是一个已经不可用的供应商。
        if (args.has("is_enabled")) ProviderRepository.repairSelection()
        return providerResult("updated", ProviderRepository.providerById(existing.id) ?: updated, added)
    }

    private suspend fun deleteProvider(args: JSONObject): String {
        val input = args.optString("provider").trim()
        if (input.isBlank()) return error("INVALID_ARGUMENT", "delete_provider 需要提供 provider")
        val provider = findProvider(ProviderRepository.allProviders(), input)
            ?: return error("PROVIDER_NOT_FOUND", "找不到供应商：$input")
        if (provider.isBuiltIn) return error("PROVIDER_PROTECTED", "内置供应商不能删除")
        ProviderRepository.deleteProvider(provider.id)
        return JSONObject()
            .put("ok", true)
            .put("action", "delete_provider")
            .put("provider", providerSummary(provider))
            .put("effect", "next_reply")
            .toString()
    }

    private suspend fun saveModel(args: JSONObject): String {
        val providerInput = args.optString("provider").trim()
        if (providerInput.isBlank()) return error("INVALID_ARGUMENT", "save_model 需要提供 provider")
        val provider = findProvider(ProviderRepository.allProviders(), providerInput)
            ?: return error("PROVIDER_NOT_FOUND", "找不到供应商：$providerInput")
        val modelInput = args.optString("model").trim()
        if (modelInput.isBlank()) return error("INVALID_ARGUMENT", "save_model 需要提供 model")
        val existing = findModel(provider, modelInput)
            ?: return error("MODEL_NOT_FOUND", "供应商 ${provider.name} 下找不到模型：$modelInput")
        listOf("vision", "reasoning").firstOrNull { key ->
            args.has(key) && args.optString(key).trim() !in TRI_STATES
        }?.let { return error("INVALID_ARGUMENT", "$it 只接受 on / off / auto") }
        val effort = if (args.has("reasoning_effort")) {
            ReasoningEffort.fromWireValue(args.optString("reasoning_effort").trim())
                ?: return error("INVALID_ARGUMENT", "reasoning_effort 取值无效")
        } else {
            existing.preferredReasoningEffort
        }
        val contextWindow = if (args.has("context_window")) {
            val requested = args.optInt("context_window", 0)
            if (requested <= 0) return error("INVALID_ARGUMENT", "context_window 必须是正整数")
            requested
        } else {
            existing.contextWindowOverride
        }
        val updated = existing.copy(
            displayName = args.optString("display_name").trim().takeIf { it.isNotBlank() } ?: existing.displayName,
            isEnabled = if (args.has("enabled")) args.optBoolean("enabled") else existing.isEnabled,
            visionOverride = triState(args, "vision", existing.visionOverride),
            reasoningOverride = triState(args, "reasoning", existing.reasoningOverride),
            contextWindowOverride = contextWindow,
            preferredReasoningEffort = effort,
        )
        ModelRepository.saveModel(provider.id, updated)
        // 关掉当前选中的模型时把指针挪到该供应商剩下的可用模型上。
        if (args.has("enabled")) ProviderRepository.repairSelection()
        return modelResult("save_model", provider, updated)
    }

    private suspend fun deleteModel(args: JSONObject): String {
        val providerInput = args.optString("provider").trim()
        if (providerInput.isBlank()) return error("INVALID_ARGUMENT", "delete_model 需要提供 provider")
        val provider = findProvider(ProviderRepository.allProviders(), providerInput)
            ?: return error("PROVIDER_NOT_FOUND", "找不到供应商：$providerInput")
        val modelInput = args.optString("model").trim()
        if (modelInput.isBlank()) return error("INVALID_ARGUMENT", "delete_model 需要提供 model")
        val existing = findModel(provider, modelInput)
            ?: return error("MODEL_NOT_FOUND", "供应商 ${provider.name} 下找不到模型：$modelInput")
        ModelRepository.deleteModel(provider.id, existing.id)
        return JSONObject()
            .put("ok", true)
            .put("action", "delete_model")
            .put("provider", providerSummary(provider))
            .put("model", modelSummary(existing))
            .put("effect", "next_reply")
            .toString()
    }

    /** 读供应商自己的 /models，刷新已存储的模型并回报新发现的模型。 */
    private suspend fun fetchModels(args: JSONObject): String {
        val input = args.optString("provider").trim()
        val provider = if (input.isBlank()) {
            val selected = SettingsDataStore.settings().selectedProviderId
            enabledProviders().firstOrNull { it.id == selected }
                ?: return error("PROVIDER_NOT_FOUND", "没有可用于拉取模型的供应商")
        } else {
            findProvider(enabledProviders(), input)
                ?: return error("PROVIDER_NOT_FOUND", "找不到可用的供应商：$input")
        }
        val fetched = RemoteModelFetcher.fetch(OpenAiCodexOAuth.withResolvedAuth(context, provider))
            .getOrElse { throwable -> return error("FETCH_FAILED", throwable.message ?: "拉取模型失败") }
        val candidates = fetched.filter(RemoteModelFetcher::isChatCapableModel)
        val sync = ModelRepository.syncRemoteModels(provider.id, candidates, includeNewModels = false)
        val known = ModelRepository.modelsByProvider(provider.id).map { it.modelId.trim().lowercase() }.toSet()
        val fresh = candidates
            .distinctBy { it.modelId.trim().lowercase() }
            .filter { it.modelId.trim().lowercase() !in known }
        val added = if (args.optBoolean("add_new") && fresh.isNotEmpty()) {
            ModelRepository.addSelectedRemoteModels(provider.id, fresh)
        } else {
            0
        }
        val listed = JSONArray()
        fresh.take(MAX_LISTED_MODELS).forEach { listed.put(it.modelId) }
        return JSONObject()
            .put("ok", true)
            .put("action", "fetch_models")
            .put("provider", providerSummary(provider))
            .put("candidates", candidates.size)
            .put("removed", sync.removedCount)
            .put("added", added)
            .put("new_model_count", fresh.size)
            .put("new_models", listed)
            .put("effect", "next_reply")
            .toString()
    }

    private fun triState(args: JSONObject, key: String, current: Boolean?): Boolean? {
        if (!args.has(key)) return current
        return when (args.optString(key).trim()) {
            "on" -> true
            "off" -> false
            else -> null
        }
    }

    private fun isEnabled(args: JSONObject, provider: ProviderSetting): Boolean =
        if (args.has("is_enabled")) args.optBoolean("is_enabled") else provider.isEnabled

    private fun applySpeech(profile: SpeechPlaybackProfile, provider: ProviderSetting, model: Model): String {
        if (!SpeechSynthesisModels.isReadAloudModel(model, provider)) {
            return error("MODEL_NOT_SUPPORTED", "${model.modelId} 不支持语音合成；换一个 tts 模型")
        }
        ReadAloudVoiceHistory.rememberCurrent(context, profile)
        Prefs.putString(profile.providerKey, provider.id)
        Prefs.putString(profile.modelKey, model.id)
        Prefs.putString(profile.voiceKey, ReadAloudVoiceHistory.restore(context, provider.id, model.id, profile))
        return selectionResult(if (profile == SpeechPlaybackProfile.READ_ALOUD) "tts" else "voice", provider, model)
    }

    private suspend fun addModels(providerId: String, args: JSONObject): Int {
        val raw = args.optJSONArray("models") ?: return 0
        val known = ModelRepository.modelsByProvider(providerId).map { it.modelId }.toMutableList()
        var added = 0
        for (index in 0 until raw.length().coerceAtMost(MAX_SAVED_MODELS)) {
            val modelId = raw.optString(index).trim()
            if (modelId.isBlank()) continue
            if (known.any { it.equals(modelId, ignoreCase = true) }) continue
            ModelRepository.saveModel(
                providerId,
                Model(id = "", modelId = modelId, displayName = modelId, isEnabled = true),
            )
            known += modelId
            added++
        }
        if (added > 0) ProviderRepository.repairSelection()
        return added
    }

    private suspend fun enabledProviders(): List<ProviderSetting> =
        ProviderRepository.allProviders().filter { it.isEnabled }

    private fun selectionResult(target: String, provider: ProviderSetting, model: Model): String =
        JSONObject()
            .put("ok", true)
            .put("action", "select")
            .put("target", target)
            .put("provider", providerSummary(provider))
            .put("model", modelSummary(model))
            .put("effect", "next_reply")
            .toString()

    private fun modelResult(action: String, provider: ProviderSetting, model: Model): String =
        JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("provider", providerSummary(provider))
            .put("model", modelDetail(model))
            .put("effect", "next_reply")
            .toString()

    private fun providerSummary(provider: ProviderSetting): JSONObject =
        JSONObject()
            .put("id", provider.id)
            .put("name", provider.name)

    /** 输出模型的生效值，让模型看到改动后的结果而不是自己记的草稿。 */
    private fun modelDetail(model: Model): JSONObject =
        JSONObject()
            .put("model_id", model.modelId)
            .put("name", model.displayName)
            .put("enabled", model.isEnabled)
            .put("vision", model.supportsVision)
            .put("reasoning", model.supportsReasoning)
            .put("context_window", model.effectiveContextWindow ?: 0)
            .put("reasoning_effort", model.preferredReasoningEffort?.wireValue ?: "")

    private fun providerResult(result: String, provider: ProviderSetting, added: Int): String {
        val models = JSONArray()
        provider.models.filter { it.isEnabled }.take(MAX_LISTED_MODELS).forEach { models.put(modelSummary(it)) }
        return JSONObject()
            .put("ok", true)
            .put("action", "save_provider")
            .put("result", result)
            .put(
                "provider",
                JSONObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("base_url", provider.baseUrl)
                    .put("built_in", provider.isBuiltIn),
            )
            .put("models_added", added)
            .put("models", models)
            .put("effect", "next_reply")
            .toString()
    }

    private fun modelSummary(model: Model): JSONObject =
        JSONObject()
            .put("model_id", model.modelId)
            .put("name", model.displayName)

    private fun findProvider(providers: List<ProviderSetting>, input: String): ProviderSetting? =
        providers.firstOrNull { it.id.equals(input, ignoreCase = true) }
            ?: providers.firstOrNull { it.name.equals(input, ignoreCase = true) }
            ?: providers.firstOrNull { it.name.contains(input, ignoreCase = true) }
            ?: providers.firstOrNull { it.baseUrl.trimEnd('/').equals(input.trimEnd('/'), ignoreCase = true) }

    private fun findModel(provider: ProviderSetting, input: String): Model? =
        matchModel(provider.models.filter { it.isEnabled }, input) ?: matchModel(provider.models, input)

    private fun matchModel(models: List<Model>, input: String): Model? =
        models.firstOrNull { it.id.equals(input, ignoreCase = true) }
            ?: models.firstOrNull { it.modelId.equals(input, ignoreCase = true) }
            ?: models.firstOrNull { it.displayName.equals(input, ignoreCase = true) }
            ?: models.firstOrNull { it.modelId.contains(input, ignoreCase = true) }
            ?: models.firstOrNull { it.displayName.contains(input, ignoreCase = true) }

    private fun io(block: suspend () -> String): String = runBlocking(Dispatchers.IO) { block() }

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private companion object {
        val TARGETS = setOf("chat", "tts", "voice", "compress")
        val TRI_STATES = setOf("on", "off", "auto")
        const val MAX_LISTED_MODELS = 40
        const val MAX_SAVED_MODELS = 60
    }
}
