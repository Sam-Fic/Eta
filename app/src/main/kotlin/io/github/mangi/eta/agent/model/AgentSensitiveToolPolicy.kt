package io.github.mangi.eta.agent.model

/** 标记原始参数或结果不得进入持久会话的工具。 */
internal object AgentSensitiveToolPolicy {
    fun isSensitive(toolName: String): Boolean =
        toolName.startsWith("mcp_") || toolName in sensitiveTools

    private val sensitiveTools = setOf(
        "delegate_task",
        "manage_agent_workspace",
        "workspace_file",
        "get_task_result",
        "cancel_task",
        "continue_task",
        "supervise_task",
        "get_setting",
        "wifi_credentials",
        "recent_notifications",
        "search_notification_history",
        "recent_app_activity",
        "app_usage_summary",
        "get_current_location",
        "get_device_environment",
        "list_alarms",
        "list_active_timers",
        "search_clipboard_history",
        "get_health_summary",
        "read_sms_code",
        "get_logcat",
        "search_media",
        "search_audio",
        "search_recordings",
        "search_files",
        "search_calendar_events",
        "search_contacts",
        "search_call_history",
        "search_messages",
        "search_downloads",
        "search_coloros_notes",
        "search_coloros_recordings",
        "search_recording_summaries",
        "search_coloros_memories",
        "search_saved_places",
        "search_personal_orders",
        "search_qq_chat_images",
        "search_wechat_chat_images",
        // 结果包含用户本机媒体的文件名与路径；原始结果不进入持久会话。
        "semantic_search_media",
        "read_image",
        "set_setting",
        "memory_get",
        "memory_write",
        // 参数可能携带供应商密钥与端点；原始结果不进入持久会话。
        "eta_manage_models",
        // 参数可能携带 MCP 服务器的端点与 Bearer 密钥；原始结果不进入持久会话。
        "eta_configure_mcp",
        // 参数与结果可能包含用户历史对话的标题与正文；原始结果不进入持久会话。
        "eta_manage_history",
        // 参数是手机上的本地目录，结果含本机 Skill 的文件路径；原始结果不进入持久会话。
        "skills_install_local",
    )
}
