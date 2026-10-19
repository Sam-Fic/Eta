package io.github.mangi.eta.data.model

import io.github.mangi.eta.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 识别专用 TTS 模型以隔离聊天选择器；名称不代表支持 OpenAI Speech 协议。
 */
internal object SpeechSynthesisModels {
    fun allowsSpeechEndpoint(provider: ProviderSetting): Boolean =
        provider !is AnthropicProviderSetting && !ProviderAuthMode.isOAuth(provider.authMode) &&
            !provider.baseUrl.contains("chatgpt.com", ignoreCase = true) && provider.isEnabled

    fun isReadAloudProvider(provider: ProviderSetting): Boolean =
        allowsSpeechEndpoint(provider) && provider.apiKey.isNotBlank() &&
            mergeCatalog(provider).any { it.isEnabled && isReadAloudModel(it, provider) }

    /** Read-aloud needs plain text + a selectable voice; creation-only models stay in the full catalog. */
    fun isReadAloudModel(model: Model, provider: ProviderSetting? = null): Boolean {
        val id = model.modelId.lowercase()
        return model.supportsSpeechSynthesis &&
            listOf("seed-audio", "voiceclone", "voice-clone", "voice_clone",
                "voicedesign", "voice-design", "voice_design").none { it in id }
    }

    fun isCompatibleSpeechProvider(provider: ProviderSetting): Boolean =
        provider.sourceType.equals(ProviderSourceTypes.COMPATIBLE_SPEECH, ignoreCase = true)

    fun isCompatibleSpeechModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        return "cosyvoice" in id || "moss-ttsd" in id || "moss_ttsd" in id
    }

    fun isRealtimeVoiceProvider(provider: ProviderSetting): Boolean =
        allowsSpeechEndpoint(provider) && provider.apiKey.isNotBlank() && isDoubaoSpeechHost(provider.baseUrl)

    fun isSpeechOnlyProvider(provider: ProviderSetting): Boolean {
        if (isCompatibleSpeechProvider(provider)) return true
        if (provider.sourceType.equals(ProviderSourceTypes.DOUBAO_SPEECH, ignoreCase = true)) return true
        return provider.baseUrl.trim().toHttpUrlOrNull()?.host.equals("openspeech.bytedance.com", ignoreCase = true)
    }

    fun isDoubaoSpeechHost(baseUrl: String): Boolean =
        baseUrl.trim().toHttpUrlOrNull()?.host.equals("openspeech.bytedance.com", ignoreCase = true)

    /** data 层没有 Context：资源解析失败（如纯 JVM 测试）时回退中文原文。 */
    private fun label(resId: Int, zh: String): String =
        runCatching { io.github.mangi.eta.agent.runtime.AgentAppContext.resolve()?.getString(resId) }.getOrNull() ?: zh

    fun catalogModels(provider: ProviderSetting): List<Model> {
        val host = provider.baseUrl.trim().toHttpUrlOrNull()?.host.orEmpty().lowercase()
        val source = provider.sourceType.trim().lowercase()
        return when {
            isCompatibleSpeechProvider(provider) -> listOf(
                catalogModel("CosyVoice2", "CosyVoice2"),
                catalogModel("MOSS-TTSD", "MOSS-TTSD"),
            )
            provider.sourceType.equals(ProviderSourceTypes.DOUBAO_SPEECH, ignoreCase = true) ||
                isDoubaoSpeechHost(provider.baseUrl) -> listOf(
                catalogModel("seed-tts-2.0", label(R.string.speech_model_doubao_tts_2, "豆包语音合成 2.0")),
                catalogModel("seed-audio-1.0", label(R.string.speech_model_doubao_audio_1, "豆包音频生成 1.0")),
            )
            host.contains("xiaomimimo") || source == ProviderSourceTypes.MIMO -> listOf(
                catalogModel("mimo-v2.5-tts", label(R.string.speech_model_xiaomi_25, "小米语音 2.5")),
                catalogModel("mimo-v2.5-tts-voiceclone", label(R.string.speech_model_xiaomi_clone_25, "小米语音克隆 2.5")),
            )
            host.contains("minimax") || source == ProviderSourceTypes.MINIMAX -> listOf(
                catalogModel("speech-2.8-hd", "MiniMax Speech 2.8 HD"),
                catalogModel("speech-2.6-hd", "MiniMax Speech 2.6 HD"),
            )
            host.contains("stepfun") || source == ProviderSourceTypes.STEPFUN -> listOf(
                catalogModel("step-tts-mini", "Step TTS Mini"),
                catalogModel("stepaudio-2.5-tts", "StepAudio 2.5 TTS"),
            )
            host.contains("dashscope") || host.contains("maas.aliyuncs.com") || source == ProviderSourceTypes.BAILIAN -> listOf(
                catalogModel("qwen-audio-3.0-tts-flash", "Qwen Audio 3.0 Flash"),
                catalogModel("qwen-audio-3.0-tts-plus", "Qwen Audio 3.0 Plus"),
            )
            host.contains("groq.com") -> listOf(
                catalogModel("canopylabs/orpheus-v1-english", "Orpheus English"),
            )
            else -> emptyList()
        }
    }

    fun mergeCatalog(provider: ProviderSetting): List<Model> {
        val extras = catalogModels(provider)
        val models = if (isSpeechOnlyProvider(provider)) {
            provider.models
        } else {
            provider.models.filterNot { it.modelId.lowercase() in DOUBAO_CATALOG_IDS }
        }
        if (extras.isEmpty()) return models
        val have = models.map { it.modelId.lowercase() }.toHashSet()
        return extras.filter { it.modelId.lowercase() !in have } + models
    }

    private val DOUBAO_CATALOG_IDS = setOf("seed-tts-2.0", "seed-audio-1.0")

    private fun catalogModel(id: String, displayName: String): Model =
        Model(
            id = id,
            modelId = id,
            displayName = displayName,
            ownedBy = "catalog",
            isBuiltIn = true,
            source = ModelSource.CATALOG,
            outputModalities = listOf(Model.AUDIO_MODALITY),
        )

    private val ID_MARKERS = listOf(
        "tts",
        "cosyvoice",
        "sambert",
        "speech-2",
        "orpheus",
        "playai",
        "speech-01",
        "speech_01",
        "gpt-4o-mini-tts",
        "fish-speech",
        "index-tts",
        "f5-tts",
        "openvoice",
        "bark-tts",
        "seed-audio",
    )
    private val STT_MARKERS = listOf(
        "asr", "whisper", "paraformer", "sensevoice", "gummy", "transcribe", "stt",
    )

    fun matches(model: Model): Boolean = matches(model.modelId, model.outputModalities)

    fun matches(modelId: String, outputModalities: List<String> = emptyList()): Boolean {
        val id = modelId.lowercase()
        if (id.isBlank()) return false
        if (STT_MARKERS.any { it in id }) return false
        return ID_MARKERS.any { it in id }
    }
}
