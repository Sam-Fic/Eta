package io.github.mangi.eta.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object Chat : AppRoute

    @Serializable
    data object Browser : AppRoute

    @Serializable
    data object Terminal : AppRoute

    @Serializable
    data object Tools : AppRoute

    @Serializable
    data object AgentTaskPreference : AppRoute

    @Serializable
    // Deserialization compatibility only; renders AgentTaskPreference, with no separate entry in UI.
    data object VirtualDisplayRecovery : AppRoute

    @Serializable
    data object Haptics : AppRoute

    @Serializable
    data object Skills : AppRoute

    @Serializable
    data object Permissions : AppRoute

    @Serializable
    data object SystemEnhance : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object SettingsSearch : AppRoute

    @Serializable
    data object SystemTakeover : AppRoute

    @Serializable
    data object About : AppRoute

    @Serializable
    data object SpeechSettings : AppRoute

    @Serializable
    data object TtsSettings : AppRoute

    @Serializable
    data object VoiceSettings : AppRoute

    @Serializable
    data object AuxiliaryVision : AppRoute

    @Serializable
    data object TitleModel : AppRoute

    @Serializable
    data object ErrorReconnectSettings : AppRoute

    @Serializable
    data object SubAgents : AppRoute

    @Serializable
    data object VoiceModeSettings : AppRoute

    @Serializable
    data object AppearanceSettings : AppRoute

    @Serializable
    data object DataBackup : AppRoute

    @Serializable
    data object Memory : AppRoute

    @Serializable
    data object LinuxEnvironment : AppRoute

    @Serializable
    data object SharedFolders : AppRoute

    @Serializable
    data object Workspace : AppRoute

    @Serializable
    data class LinuxFiles(val distribution: String) : AppRoute

    @Serializable
    data object ModelProviders : AppRoute

    @Serializable
    data object ModelProviderSearch : AppRoute

    @Serializable
    data object McpServers : AppRoute

    @Serializable
    data class McpServerDetail(val serverId: String) : AppRoute

    @Serializable
    data class ModelProviderDetail(val providerId: String) : AppRoute

    @Serializable
    data class ModelProviderAuthMethod(val providerType: NewProviderType) : AppRoute

    @Serializable
    data class ModelProviderNew(
        val providerType: NewProviderType,
        val authMode: String = "api_key",
    ) : AppRoute

    @Serializable
    data object ContextCompression : AppRoute

    @Serializable
    data class Assistants(val picker: Boolean = false) : AppRoute

    @Serializable
    data class AssistantSearch(val picker: Boolean = false) : AppRoute

    @Serializable
    data class AssistantEdit(val assistantId: String) : AppRoute

    @Serializable
    data object UsageStats : AppRoute

    @Serializable
    data object ManageChats : AppRoute

    /** 回收站：已软删除会话的恢复与彻底清理。 */
    @Serializable
    data object ConversationTrash : AppRoute

    /** 回收站站内搜索子页；只匹配回收站会话的标题。 */
    @Serializable
    data object ConversationTrashSearch : AppRoute

    /** [conversationScoped] 为 true 时只检索当前会话（首页顶栏入口），否则跨全部会话。 */
    @Serializable
    data class MessageSearch(val conversationScoped: Boolean = false) : AppRoute

    @Serializable
    data object ConversationTokenUsage : AppRoute

    @Serializable
    data object SubAgentStatus : AppRoute

    /** 守护任务列表页；终端块视图与控制台视图共用。 */
    @Serializable
    data object TerminalDaemonTasks : AppRoute

    /** 本地多模态语义检索页（EmbeddingGemma 2）。 */
    @Serializable
    data object MediaSearch : AppRoute

    /** 媒体语义搜索设置页：模型下载、扫描范围确认与索引构建。 */
    @Serializable
    data object MediaIndexSettings : AppRoute

    /** 端侧设备动作模型设置页：双源下载、令牌、推理后端。 */
    @Serializable
    data object MobileActionsSettings : AppRoute

    /** 端侧设备动作试用页：指令 → 本地函数调用 → 真实执行。 */
    @Serializable
    data object MobileActions : AppRoute
}

@Serializable
enum class NewProviderType { OpenAiCompatible, Anthropic, DoubaoSpeech, CompatibleSpeech }
