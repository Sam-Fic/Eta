package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject

internal enum class RootRequirement { NONE, PARTIAL, REQUIRED }
internal enum class LsposedRequirement { NONE, OPTIONAL, REQUIRED }

internal enum class ToolSystemAccess { NONE, NOTIFICATIONS, USAGE, LOCATION }

internal data class LocalToolRequirement(
    val rootRequirement: RootRequirement,
    val lsposedRequirement: LsposedRequirement = LsposedRequirement.NONE,
    val accessibility: Boolean = false,
    val systemAccess: ToolSystemAccess = ToolSystemAccess.NONE,
    val colorOs: Boolean = false,
)

/** 展示、模型目录与执行边界共同使用的本地工具能力合同。未登记的工具不能发布。 */
internal object AgentToolRequirements {
    private val definitions = buildMap {
        fun register(root: RootRequirement, vararg names: String) {
            names.forEach { name ->
                check(put(name, LocalToolRequirement(root)) == null) { "Duplicate tool: $name" }
            }
        }
        register(
            RootRequirement.NONE,
            "ask_user",
            "get_current_context", "search_apps", "launch_app", "keep_virtual_result", "open_uri", "browser_use", "text_to_speech",
            "web_search", "fetch_url",
            "observe_screen", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "set_clipboard", "get_clipboard", "paste_text",
            "wait", "wait_for_text", "wait_for_package", "open_system_panel",
            "set_alarm", "set_timer", "device_status", "media_control", "set_volume",
            "set_ringer_mode", "list_installed_apps",
            "search_notification_history", "recent_app_activity", "app_usage_summary",
            "get_current_location", "get_device_environment", "memory_get", "memory_write",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "skills_inspect_github", "skills_install_from_github", "skills_install_local",
            "eta_get_settings", "eta_set_settings", "eta_manage_models", "eta_manage_subagents", "eta_manage_history", "eta_configure_mcp",
            // 自进化：自写配方本身不需要 Root（配方内含需 Root 的步骤时按配方单独判定）。
            "eta_author_tool", "eta_list_recipes",
            "semantic_search_media",
        )
        register(
            RootRequirement.PARTIAL,
            "press_key", "network_info", "get_setting", "recent_notifications",
            "search_personal_orders", "terminal", "run_command", "read_file",
            "write_file", "list_directory", "read_image",
            // 免 Root 走公开 API（Settings / 通知 Policy / 无障碍），Root 作为保底直达。
            "set_setting", "set_device_state", "set_do_not_disturb", "set_brightness",
            "screen_action", "manage_app",
            // 文件管理免 Root 已可全盘操作（MANAGE_EXTERNAL_STORAGE），Root 仅保底私有路径。
            "manage_file",
            // 输入法列表/当前值、Wi-Fi 面板降级、自动时间开关有免 Root 路径。
            "ime_control", "connect_wifi", "set_timezone",
        )
        register(
            RootRequirement.REQUIRED,
            "start_virtual_session", "finish_virtual_session", "inspect_virtual_backend", "top_memory_apps", "top_storage_apps", "wifi_credentials", "read_sms_code",
            "get_logcat", "app_state_control",
            "list_alarms", "list_active_timers", "get_health_summary", "search_clipboard_history",
            "search_media", "search_audio", "search_recordings", "search_files",
            "search_calendar_events", "search_contacts", "search_call_history", "search_messages",
            "search_downloads", "search_coloros_notes", "search_coloros_recordings",
            "search_recording_summaries", "search_coloros_memories", "search_saved_places",
            "search_qq_chat_images", "search_wechat_chat_images",
            // 日程与闹钟的修改/删除必须 Root 直达 Provider / 时钟数据库；无 Root 时对模型隐藏，避免必然失败的调用。
            "update_calendar_event", "delete_calendar_event", "delete_alarm", "update_alarm",
            // Root 深度控制：全部依赖系统命令直达，无 Root 时对模型隐藏，避免必然失败的调用。
            "search_settings", "dumpsys_diag", "system_property", "display_control",
            "deep_launch", "package_control", "user_control", "appops_control", "power_control",
        )
        // 创建日程在无 Root 时退化为打开日历预填界面，因此按 PARTIAL 注册。
        register(RootRequirement.PARTIAL, "create_calendar_event")
        listOf(
            "observe_screen", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
            "wait_for_text", "wait_for_package",
        ).forEach { name -> put(name, getValue(name).copy(accessibility = true)) }
        mapOf(
            "recent_notifications" to ToolSystemAccess.NOTIFICATIONS,
            "search_notification_history" to ToolSystemAccess.NOTIFICATIONS,
            "search_personal_orders" to ToolSystemAccess.NOTIFICATIONS,
            "recent_app_activity" to ToolSystemAccess.USAGE,
            "app_usage_summary" to ToolSystemAccess.USAGE,
            "get_current_location" to ToolSystemAccess.LOCATION,
        ).forEach { (name, access) -> put(name, getValue(name).copy(systemAccess = access)) }
        listOf(
            "search_coloros_notes", "search_coloros_recordings", "search_recording_summaries",
            "search_coloros_memories", "search_saved_places",
        ).forEach { name -> put(name, getValue(name).copy(colorOs = true)) }
        // 闹钟直写只对 ColorOS 时钟库成立；非 ColorOS 设备直接隐藏，避免模型调用必然失败的工具。
        listOf("delete_alarm", "update_alarm").forEach { name ->
            put(name, getValue(name).copy(colorOs = true))
        }
        // 系统记忆优先使用 Hook 桥接，框架失联时仍有独立的 Root 快照来源。
        listOf("search_coloros_memories", "search_saved_places", "search_personal_orders").forEach { name ->
            put(name, getValue(name).copy(lsposedRequirement = LsposedRequirement.OPTIONAL))
        }
    }

    val toolNames: Set<String> get() = definitions.keys

    fun find(name: String): LocalToolRequirement? = definitions[name]

    fun rootRequirement(name: String): RootRequirement =
        requireNotNull(find(name)) { "Missing tool requirements: $name" }.rootRequirement

    fun requiresAccessibility(name: String): Boolean = find(name)?.accessibility == true

    fun rootDenied(name: String, arguments: JSONObject, rootAvailable: Boolean): Boolean {
        if (rootAvailable) return false
        if (rootRequirement(name) == RootRequirement.REQUIRED) return true
        return when (name) {
            "terminal" -> arguments.optString("identity").equals("root", ignoreCase = true)
            "press_key" -> arguments.optString("button").equals("PASTE", ignoreCase = true)
            else -> false
        }
    }

    /** 复制后收窄，不能修改下一轮或另一个 run 共用的原始 Schema。 */
    fun project(tools: JSONArray, rootAvailable: Boolean): JSONArray = JSONArray().also { result ->
        for (index in 0 until tools.length()) {
            val original = tools.getJSONObject(index)
            val name = original.getJSONObject("function").getString("name")
            val requirement = rootRequirement(name)
            if (!rootAvailable && requirement == RootRequirement.REQUIRED) continue
            val tool = JSONObject(original.toString())
            if (!rootAvailable) projectUnprivileged(tool.getJSONObject("function"))
            result.put(tool)
        }
    }

    private fun projectUnprivileged(function: JSONObject) {
        val properties = function.getJSONObject("parameters").optJSONObject("properties")
        when (function.getString("name")) {
            "terminal" -> {
                function.put("description", "在当前设备管理普通 Android Shell 或用户选择的 Linux 环境。" +
                    "以 App UID 执行，支持会话、异步任务和后台服务；Linux 内的模拟身份不提供 Android 系统特权。" +
                    "使用 open_and_exec 执行单次命令，open/exec 复用会话，daemon_start/list/logs/stop 管理后台服务。")
                properties?.getJSONObject("identity")
                    ?.put("enum", JSONArray().put("user"))
                    ?.put("description", "宿主执行身份；当前仅支持 user，默认 user。")
                properties?.getJSONObject("environment")?.put("description",
                    "android 使用普通 Android Shell；linux 使用用户选择的发行版和免 Root 后端。默认 android。")
                properties?.getJSONObject("cwd")?.put("description",
                    "工作目录。Android 默认使用 Eta 私有工作区，Linux 默认 /workspace。")
            }
            "run_command" -> {
                function.put("description",
                    "通过普通 Android Shell 执行单次非交互命令，以 App UID 运行；只能访问当前应用有权访问的资源。" +
                        "不会进入 Linux 环境，Linux 命令使用 terminal(environment=linux)。")
                properties?.getJSONObject("cwd")?.put("description", "工作目录，默认使用 Eta 私有工作区。")
            }
            "list_directory" -> {
                function.put("description", "列出当前应用有权访问的目录，默认使用 Eta 私有工作区。")
                properties?.optJSONObject("path")?.apply {
                    put("description", "目录路径；未提供时使用 Eta 私有工作区。")
                    remove("default")
                }
            }
            "read_image" -> properties?.getJSONObject("path")?.put("description",
                "绝对图片或视频路径、file URI、已授权的 content URI；聊天附件优先用用户消息里给出的绝对路径，别名 /home/workdir/attachments/image.jpg 只解析当前会话的附件。视频会抽取封面帧。")
            "press_key" -> properties?.getJSONObject("button")?.let { button ->
                val values = button.getJSONArray("enum")
                button.put("enum", JSONArray().also { allowed ->
                    for (i in 0 until values.length()) {
                        val value = values.getString(i)
                        if (!value.equals("PASTE", ignoreCase = true)) allowed.put(value)
                    }
                })
                button.put("description", "无障碍支持的系统按键；粘贴文本请使用 paste_text。")
            }
            "search_personal_orders" -> function.put("description",
                "从用户已授权保存的通知历史检索外卖、购物、快递、票券和出行订单。")
        }
    }
}
