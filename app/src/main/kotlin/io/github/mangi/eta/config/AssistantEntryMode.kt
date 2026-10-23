package io.github.mangi.eta.config

/**
 * 系统助手入口（电源键长按 / 系统助理 API）唤起后的落点。
 *
 * 默认保持历史行为：直接聚焦输入框、弹起键盘。语音需要用户主动在系统接管页选择，
 * 且只由 App 进程的入口浮窗消费，因此配置存本地，不依赖 LSPosed。
 */
internal enum class AssistantEntryMode(
    val persistedValue: String,
) {
    KEYBOARD("keyboard"),
    VOICE("voice"),
    ;

    companion object {
        fun resolve(persistedValue: String?): AssistantEntryMode =
            entries.firstOrNull { it.persistedValue == persistedValue } ?: KEYBOARD
    }
}
