package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig

internal object VoiceEntryPolicy {
    /**
     * 听写已并入「语音对话」：输入区只暴露一个会边说边发、自动朗读回复的对话模式。
     * [VoiceEntryMode.DICTATION] 仍保留在枚举里用于读取旧偏好，但不再作为入口出现。
     */
    fun modes(config: DoubaoVoiceConfig.Config): List<VoiceEntryMode> = buildList {
        if (config.conversationEnabled) add(VoiceEntryMode.UNIVERSAL)
        if (config.duplexEnabled) add(VoiceEntryMode.DOUBAO_DUPLEX)
    }
    fun enabled(config: DoubaoVoiceConfig.Config, mode: VoiceEntryMode): Boolean = mode in modes(config)
    fun directMode(config: DoubaoVoiceConfig.Config, lastSelected: String = ""): VoiceEntryMode? {
        val available = modes(config)
        // 旧版记住的「语音输入」现在就是合并后的语音对话，不因历史值退化成“每次都要选”。
        val remembered = if (lastSelected == VoiceEntryMode.DICTATION.wireValue) {
            VoiceEntryMode.UNIVERSAL.wireValue
        } else {
            lastSelected
        }
        // Unknown/disabled history must not silently enable a mode or guess dictation.
        return available.firstOrNull { it.wireValue == remembered } ?: available.singleOrNull()
    }
    fun canChoose(config: DoubaoVoiceConfig.Config): Boolean = modes(config).size > 1
}
