package io.github.mangi.eta.data.model

import org.junit.Assert.*
import org.junit.Test

class SpeechSynthesisModelsTest {
    @Test fun readAloudExcludesCreationModelsButKeepsAudioNamedTtsModels() {
        listOf("seed-audio-1.0", "vendor/SEED-AUDIO-1.0", "mimo-v2.5-tts-voiceclone", "mimo-v2.5-tts-voicedesign", "qwen-tts-voice-design").forEach { id ->
            val model = Model(id = id, modelId = id, displayName = id)
            assertTrue(id, model.supportsSpeechSynthesis)
            assertFalse(id, SpeechSynthesisModels.isReadAloudModel(model))
        }
        listOf("seed-tts-2.0", "mimo-v2.5-tts", "stepaudio-2.5-tts", "qwen-audio-3.0-tts-flash", "tts-1").forEach { id ->
            assertTrue(id, SpeechSynthesisModels.isReadAloudModel(Model(id = id, modelId = id, displayName = id)))
        }
    }

    @Test fun realtimeRequiresEnabledCredentialedOpenspeechProvider() {
        val p = OpenAiCompatibleProviderSetting("d", "d", "https://openspeech.bytedance.com", apiKey = "test")
        assertTrue(SpeechSynthesisModels.isRealtimeVoiceProvider(p))
        assertFalse(SpeechSynthesisModels.isRealtimeVoiceProvider(p.copy(apiKey = "")))
        assertFalse(SpeechSynthesisModels.isRealtimeVoiceProvider(p.copy(isEnabled = false)))
        assertFalse(SpeechSynthesisModels.isRealtimeVoiceProvider(p.copy(baseUrl = "https://api.openai.com/v1")))
    }

    @Test fun dedicatedModelsAreNotChatModels() {
        listOf("tts-1", "gpt-4o-mini-tts", "cosyvoice-v2", "speech-01-hd").forEach {
            assertTrue(it, SpeechSynthesisModels.matches(it))
        }
    }
    @Test fun audioOutputAloneDoesNotImplySpeechEndpoint() {
        assertFalse(SpeechSynthesisModels.matches("gpt-4o-audio", listOf("audio")))
        assertFalse(SpeechSynthesisModels.matches("custom", listOf("audio")))
        assertFalse(SpeechSynthesisModels.matches("whisper-tts"))
        assertFalse(SpeechSynthesisModels.matches("gpt-5"))
    }
    @Test fun oauthAndAnthropicAreNotSpeechProviders() {
        assertFalse(SpeechSynthesisModels.allowsSpeechEndpoint(AnthropicProviderSetting("a", "a", "https://example.com")))
        assertFalse(SpeechSynthesisModels.allowsSpeechEndpoint(OpenAiCompatibleProviderSetting("a", "a", "https://example.com", authMode = ProviderAuthModes.OPENAI_CHATGPT)))
        assertTrue(SpeechSynthesisModels.allowsSpeechEndpoint(CustomProviderSetting("a", "a", "https://example.com/v1")))
    }

    @Test fun openspeechIsSpeechOnly() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "d", name = "豆包语音", baseUrl = "https://openspeech.bytedance.com", apiKey = "k",
        )
        assertTrue(SpeechSynthesisModels.isSpeechOnlyProvider(provider))
        assertFalse(
            SpeechSynthesisModels.isSpeechOnlyProvider(
                provider.copy(baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3"),
            ),
        )
    }
}
