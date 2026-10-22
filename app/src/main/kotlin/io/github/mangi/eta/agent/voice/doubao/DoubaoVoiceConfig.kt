package io.github.mangi.eta.agent.voice.doubao

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Independent ASR/clone credentials, in application-private preferences. Never logged. */
internal object DoubaoVoiceConfig {
    data class Config(
        val cloudAsr: Boolean = false,
        val asrKey: String = "",
        val resource: String = DoubaoAsrProtocol.resources.first(),
        val cloneKey: String = "",
        /** 旧听写入口开关；听写已并入语音对话，仅用于一次性迁移。 */
        val inputEnabled: Boolean = false,
        val conversationEnabled: Boolean = true,
        val duplexEnabled: Boolean = true,
        val conversationCloudAsr: Boolean = false,
        val conversationAsrKey: String = "",
        val conversationResource: String = DoubaoAsrProtocol.resources.first(),
    )
    private val mutable = MutableStateFlow(Config())
    val state = mutable.asStateFlow()
    fun load(context: Context) {
        val p = context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE)
        // Migrate once from either previously enabled engine; explicit OFF survives reloads.
        if (!p.contains("input_enabled")) p.edit().putBoolean("input_enabled",
            p.getBoolean("asr", false) || context.getSharedPreferences("offline_speech", Context.MODE_PRIVATE).getBoolean("enabled", false)).apply()
        migrateDictationIntoConversation(p)
        mutable.value = Config(p.getBoolean("asr", false), p.getString("asr_key", "").orEmpty(),
            p.getString("resource", null)?.takeIf { it in DoubaoAsrProtocol.resources } ?: DoubaoAsrProtocol.resources.first(), p.getString("clone_key", "").orEmpty(), p.getBoolean("input_enabled", false), p.getBoolean("conversation_enabled", true), p.getBoolean("duplex_enabled", true),
            p.getBoolean("voice_conversation_asr_cloud", false),
            p.getString("voice_conversation_asr_key", "").orEmpty(),
            p.getString("voice_conversation_asr_resource", null)?.takeIf { it in DoubaoAsrProtocol.resources }
                ?: DoubaoAsrProtocol.resources.first())
    }
    /**
     * 听写入口并入语音对话的一次性迁移。
     *
     * 以前只开听写的用户合并后仍应看到语音入口，因此把总开关带到语音对话；语音对话
     * 从未保存过识别配置时沿用听写那套云端凭据 / 资源，避免合并后突然要求重新配置。
     * 旧听写开关置为关闭，后续保存不再重复迁移。
     */
    private fun migrateDictationIntoConversation(p: SharedPreferences) {
        if (p.getBoolean("modes_merged_v1", false)) return
        val inputEnabled = p.getBoolean("input_enabled", false)
        val editor = p.edit().putBoolean("modes_merged_v1", true)
        if (inputEnabled) {
            editor.putBoolean("conversation_enabled", true)
            if (!p.contains("voice_conversation_asr_cloud")) {
                editor.putBoolean("voice_conversation_asr_cloud", p.getBoolean("asr", false))
                editor.putString("voice_conversation_asr_key", p.getString("asr_key", "").orEmpty())
                editor.putString(
                    "voice_conversation_asr_resource",
                    p.getString("resource", null)?.takeIf { it in DoubaoAsrProtocol.resources }
                        ?: DoubaoAsrProtocol.resources.first(),
                )
            }
            editor.putBoolean("input_enabled", false)
        }
        editor.apply()
    }

    fun save(context: Context, value: Config) {
        context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE).edit().putBoolean("asr", value.cloudAsr).putBoolean("input_enabled", value.inputEnabled)
            .putBoolean("conversation_enabled", value.conversationEnabled).putBoolean("duplex_enabled", value.duplexEnabled)
            .putBoolean("voice_conversation_asr_cloud", value.conversationCloudAsr)
            .putString("voice_conversation_asr_key", value.conversationAsrKey.trim())
            .putString("voice_conversation_asr_resource", value.conversationResource)
            .putString("asr_key", value.asrKey.trim()).putString("resource", value.resource).putString("clone_key", value.cloneKey.trim()).apply()
        mutable.value = value.copy(asrKey = value.asrKey.trim(), cloneKey = value.cloneKey.trim(), conversationAsrKey = value.conversationAsrKey.trim())
    }
}
