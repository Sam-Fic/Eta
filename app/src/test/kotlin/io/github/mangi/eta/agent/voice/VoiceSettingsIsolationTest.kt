package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.voice.doubao.DoubaoAsrProtocol
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.agent.voice.tts.ReadAloudVoiceHistory
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackProfile
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class VoiceSettingsIsolationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        Prefs.initLocal(context)
        for (profile in SpeechPlaybackProfile.entries) {
            listOf(profile.modeKey, profile.providerKey, profile.modelKey, profile.voiceKey)
                .forEach { Prefs.putString(it, "") }
            context.getSharedPreferences(profile.historyFile, Context.MODE_PRIVATE).edit().clear().commit()
        }
        context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("offline_speech", Context.MODE_PRIVATE).edit().clear().commit()
        DoubaoVoiceConfig.load(context)
    }

    @Test fun conversationNeverInheritsReadAloudSelection() {
        val read = SpeechPlaybackProfile.READ_ALOUD
        Prefs.putString(read.modeKey, "cloud")
        Prefs.putString(read.providerKey, "read-provider")
        Prefs.putString(read.modelKey, "read-model")
        Prefs.putString(read.voiceKey, "read-voice")
        val conversation = SpeechPlaybackProfile.CONVERSATION.snapshot()
        assertFalse(conversation.cloud)
        assertEquals("", conversation.providerId)
        assertEquals("", conversation.modelId)
        assertEquals("", conversation.voiceId)
    }

    @Test fun conversationChangesDoNotChangeReadAloudOrCapturedCall() {
        val profile = SpeechPlaybackProfile.CONVERSATION
        Prefs.putString(profile.modeKey, "cloud")
        Prefs.putString(profile.providerKey, "first-provider")
        Prefs.putString(profile.modelKey, "first-model")
        Prefs.putString(profile.voiceKey, "first-voice")
        val call = profile.snapshot()
        Prefs.putString(profile.voiceKey, "next-call")
        assertEquals("first-voice", call.voiceId)
        assertEquals("next-call", profile.snapshot().voiceId)
        assertEquals("", SpeechPlaybackProfile.READ_ALOUD.snapshot().voiceId)
    }

    @Test fun voiceHistoryIsFeatureScopedEvenForSameProviderAndModel() {
        ReadAloudVoiceHistory.remember(context, "p", "m", "read")
        assertEquals("", ReadAloudVoiceHistory.restore(context, "p", "m", SpeechPlaybackProfile.CONVERSATION))
        ReadAloudVoiceHistory.remember(context, "p", "m", "conversation", SpeechPlaybackProfile.CONVERSATION)
        assertEquals("read", ReadAloudVoiceHistory.restore(context, "p", "m"))
        assertEquals("conversation", ReadAloudVoiceHistory.restore(context, "p", "m", SpeechPlaybackProfile.CONVERSATION))
    }

    @Test fun recognitionCredentialsAndResourcePersistIndependently() {
        val config = DoubaoVoiceConfig.Config(
            cloudAsr = true, asrKey = "dictation-key", resource = DoubaoAsrProtocol.resources.first(),
            inputEnabled = true, conversationCloudAsr = true, conversationAsrKey = "conversation-key",
            conversationResource = DoubaoAsrProtocol.resources.last(),
        )
        DoubaoVoiceConfig.save(context, config)
        DoubaoVoiceConfig.load(context)
        val dictation = SpeechInputSession.configFor(VoiceEntryMode.DICTATION)
        val conversation = SpeechInputSession.configFor(VoiceEntryMode.UNIVERSAL)
        assertEquals("dictation-key", dictation.asrKey)
        assertEquals("conversation-key", conversation.asrKey)
        assertEquals(config.resource, dictation.resource)
        assertEquals(config.conversationResource, conversation.resource)
        DoubaoVoiceConfig.save(context, DoubaoVoiceConfig.state.value.copy(cloudAsr = false, asrKey = "changed"))
        assertTrue(SpeechInputSession.configFor(VoiceEntryMode.UNIVERSAL).cloudAsr)
        assertEquals("conversation-key", SpeechInputSession.configFor(VoiceEntryMode.UNIVERSAL).asrKey)
        assertEquals("dictation-key", dictation.asrKey) // captured settings stay immutable
    }

    @Test fun missingConversationCredentialCannotBorrowLegacyDictationCredential() {
        DoubaoVoiceConfig.save(context, DoubaoVoiceConfig.Config(
            cloudAsr = true, asrKey = "dictation-key", inputEnabled = true,
            conversationCloudAsr = true, conversationAsrKey = "",
        ))
        // 听写入口已并入语音对话，旧密钥不再单独可用，也不能被语音对话借用。
        assertFalse(SpeechInputSession.ready(VoiceEntryMode.DICTATION))
        assertFalse(SpeechInputSession.ready(VoiceEntryMode.UNIVERSAL))
        assertFalse(SpeechInputSession.ready(VoiceEntryMode.DOUBAO_DUPLEX))
    }

    @Test fun legacyDictationConfigurationMigratesIntoConversationOnlyOnce() {
        context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE).edit()
            .putBoolean("asr", true).putString("asr_key", "legacy-dictation").commit()
        DoubaoVoiceConfig.load(context)
        val migrated = DoubaoVoiceConfig.state.value
        assertFalse(migrated.inputEnabled)
        assertTrue(migrated.conversationEnabled)
        assertTrue(migrated.conversationCloudAsr)
        assertEquals("legacy-dictation", migrated.conversationAsrKey)

        // 迁移只执行一次：之后用户在语音对话里改掉的配置不会被旧听写设置覆盖。
        DoubaoVoiceConfig.save(context, migrated.copy(conversationCloudAsr = false, conversationAsrKey = "conversation-key"))
        DoubaoVoiceConfig.load(context)
        val reloaded = DoubaoVoiceConfig.state.value
        assertFalse(reloaded.conversationCloudAsr)
        assertEquals("conversation-key", reloaded.conversationAsrKey)
        assertEquals("legacy-dictation", reloaded.asrKey)
        assertFalse(reloaded.inputEnabled)
    }
}
