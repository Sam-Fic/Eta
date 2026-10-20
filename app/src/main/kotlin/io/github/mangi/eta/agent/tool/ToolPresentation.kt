package io.github.mangi.eta.agent.tool

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.AdsClick
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.WebAsset
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.icons.SubAgents

/**
 * 工具 UI 呈现元数据的单一数据源：一个工具 id 同时拥有图标与显示名，只此一处维护。
 *
 * 与 `agent/model/AgentToolCatalog`（运行时工具 schema 注册表）不是一回事：那张表决定
 * 「有哪些工具、参数是什么」，本对象只决定「工具在界面上长什么样、叫什么」。
 *
 * 合并前 `ui/components/ToolIcons.kt`（iconForTool，约百条）与
 * `agent/overlay/AgentOverlayText.kt`（toolDisplayNameResource，约五十条）是两张分居
 * 两个包的表，新增工具要改两处、且 overlay 包被 chat UI 反向依赖。现在收敛到 agent 层：
 * UI 与浮窗都查这一张表，新增工具只改一处。
 *
 * 未登记的 id：图标回退 FallbackIcon（`mcp_` 前缀走 MCP 扩展图标），显示名回退 id 原文。
 */
internal object ToolPresentation {

    /** 未登记工具 id 的图标回退值（与迁移前 `iconForTool` 行为一致）。 */
    private val FallbackIcon: ImageVector = Icons.Rounded.Build

    /** 工具显示名的字符串资源；未登记返回 null（调用方回退 id 原文）。 */
    @StringRes
    fun nameRes(toolId: String): Int? = when (toolId) {
        "observe_screen" -> R.string.tool_observe_screen
        "tap" -> R.string.tool_tap
        "tap_element" -> R.string.tool_tap_element
        "tap_area" -> R.string.tool_tap_area
        "long_press" -> R.string.tool_long_press
        "long_press_element" -> R.string.tool_long_press_element
        "swipe" -> R.string.tool_swipe
        "scroll" -> R.string.tool_scroll
        "scroll_element" -> R.string.tool_scroll_element
        "paste_text" -> R.string.tool_paste_text
        "get_clipboard" -> R.string.tool_get_clipboard
        "set_clipboard" -> R.string.tool_set_clipboard
        "input_text" -> R.string.tool_input_text
        "replace_text" -> R.string.tool_replace_text
        "clear_text" -> R.string.tool_clear_text
        "wait" -> R.string.tool_wait
        "wait_for_text" -> R.string.tool_wait_for_text
        "wait_for_package" -> R.string.tool_wait_for_package
        "search_apps" -> R.string.tool_search_apps
        "get_current_context" -> R.string.tool_current_context
        "launch_app" -> R.string.tool_launch_app
        "open_uri" -> R.string.tool_open_uri
        "browser_use" -> R.string.tool_browser_use
        "delegate_task" -> R.string.tool_delegate_task
        "get_task_result" -> R.string.tool_get_task_result
        "cancel_task" -> R.string.tool_cancel_task
        "memory_get" -> R.string.tool_memory_get
        "memory_write" -> R.string.tool_memory_write
        "press_key" -> R.string.tool_press_key
        "open_system_panel" -> R.string.tool_open_system_panel
        "set_alarm" -> R.string.tool_set_alarm
        "set_timer" -> R.string.tool_set_timer
        "device_status" -> R.string.tool_device_status
        "set_device_state" -> R.string.tool_set_device_state
        "network_info" -> R.string.tool_network_info
        "wifi_credentials" -> R.string.tool_wifi_credentials
        "media_control" -> R.string.tool_media_control
        "set_volume" -> R.string.tool_set_volume
        "top_memory_apps" -> R.string.tool_top_memory_apps
        "top_storage_apps" -> R.string.tool_top_storage_apps
        "read_sms_code" -> R.string.tool_read_sms_code
        "recent_notifications" -> R.string.tool_recent_notifications
        "get_setting" -> R.string.tool_get_setting
        "set_setting" -> R.string.tool_set_setting
        "app_state_control" -> R.string.tool_app_state_control
        "get_logcat" -> R.string.tool_get_logcat
        "terminal" -> R.string.tool_terminal
        "run_command" -> R.string.tool_run_command
        "read_file" -> R.string.tool_read_file
        "write_file" -> R.string.tool_write_file
        "list_directory" -> R.string.tool_list_directory
        "eta_get_settings" -> R.string.tool_eta_get_settings
        "eta_set_settings" -> R.string.tool_eta_set_settings
        "eta_manage_models" -> R.string.tool_eta_manage_models
        "eta_manage_subagents" -> R.string.tool_eta_manage_subagents
        "eta_manage_history" -> R.string.tool_eta_manage_history
        "semantic_search_media" -> R.string.tool_semantic_search_media
        else -> null
    }

    /** 工具图标；未登记的 id 回退 FallbackIcon。 */
    fun icon(toolId: String): ImageVector = when (toolId) {
        "start_virtual_session", "keep_virtual_result", "finish_virtual_session", "inspect_virtual_backend" -> Icons.Rounded.ScreenshotMonitor
        "observe" -> Icons.Rounded.DocumentScanner
        "observe_screen" -> Icons.Rounded.DocumentScanner
        "click" -> Icons.Rounded.AdsClick
        "tap" -> Icons.Rounded.AdsClick
        "tap_element" -> Icons.Rounded.AdsClick
        "tap_area" -> Icons.Rounded.MyLocation
        "long_press" -> Icons.Rounded.TouchApp
        "long_press_element" -> Icons.Rounded.TouchApp
        "swipe" -> Icons.Rounded.OpenWith
        "scroll" -> Icons.Rounded.SwapVert
        "scroll_element" -> Icons.Rounded.SwapVert
        "clipboard" -> Icons.Rounded.ContentPasteGo
        "paste_text" -> Icons.Rounded.ContentPasteGo
        "get_clipboard" -> Icons.Rounded.ContentPaste
        "set_clipboard" -> Icons.Rounded.ContentPaste
        "input_text" -> Icons.Rounded.Keyboard
        "replace_text" -> Icons.Rounded.FindReplace
        "clear_text" -> Icons.AutoMirrored.Rounded.Backspace
        "wait" -> Icons.Rounded.Schedule
        "wait_text" -> Icons.Rounded.Schedule
        "wait_for_text" -> Icons.Rounded.Schedule
        "wait_for_package" -> Icons.Rounded.Schedule
        "search_apps" -> Icons.Rounded.Search
        "get_current_context" -> Icons.Rounded.Smartphone
        "open_app" -> Icons.Rounded.Apps
        "launch_app" -> Icons.Rounded.Apps
        "open_uri" -> Icons.AutoMirrored.Rounded.OpenInNew
        "browser_use" -> Icons.Rounded.Language
        "网页浏览" -> Icons.Rounded.Language
        "web_search", "web_search_call", "网页搜索" -> Icons.Rounded.TravelExplore
        "browser_read" -> Icons.AutoMirrored.Rounded.MenuBook
        "browser_interact" -> Icons.Rounded.AdsClick
        "browser_screenshot" -> Icons.Rounded.ScreenshotMonitor
        "file_search", "file_search_call", "文件搜索" -> Icons.AutoMirrored.Rounded.ManageSearch
        "code_interpreter", "code_interpreter_call", "代码执行" -> Icons.Rounded.Terminal
        "computer", "computer_call", "计算机操作" -> Icons.Rounded.Computer
        "image_generation", "image_generation_call", "图像生成" -> Icons.Rounded.Image
        "mcp_call", "MCP 工具" -> Icons.Rounded.Extension
        "delegate_task" -> SubAgents
        "get_task_result" -> SubAgents
        "cancel_task" -> SubAgents
        "continue_task", "manage_agent_workspace" -> SubAgents
        "memory_get" -> Icons.Rounded.Psychology
        "memory_write" -> Icons.Rounded.Psychology
        "press_key" -> Icons.Rounded.KeyboardCommandKey
        "open_system_panel" -> Icons.Rounded.WebAsset
        "read_image" -> Icons.Rounded.Image
        "set_alarm" -> Icons.Rounded.Alarm
        "set_timer" -> Icons.Rounded.Alarm
        "list_alarms", "list_active_timers" -> Icons.Rounded.Alarm
        "device_status" -> Icons.Rounded.Smartphone
        "set_device_state" -> Icons.Rounded.Smartphone
        "get_device_environment" -> Icons.Rounded.Smartphone
        "network_info" -> Icons.Rounded.Wifi
        "wifi_credentials" -> Icons.Rounded.Wifi
        "media_control" -> Icons.Rounded.PlayArrow
        "text_to_speech" -> Icons.Rounded.RecordVoiceOver
        "set_volume" -> Icons.AutoMirrored.Rounded.VolumeUp
        "top_memory_apps" -> Icons.Rounded.Layers
        "top_storage_apps" -> Icons.Rounded.Layers
        "read_sms_code" -> Icons.Rounded.Key
        "recent_notifications" -> Icons.Rounded.Notifications
        "search_notification_history" -> Icons.Rounded.Notifications
        "get_setting" -> Icons.Rounded.Settings
        "set_setting" -> Icons.Rounded.Settings
        "app_state_control" -> Icons.Rounded.AdminPanelSettings
        "get_logcat" -> Icons.Rounded.Description
        "get_current_location", "search_saved_places" -> Icons.Rounded.LocationOn
        "get_health_summary" -> Icons.Rounded.MonitorHeart
        "recent_app_activity", "app_usage_summary" -> Icons.Rounded.Insights
        "search_calendar_events" -> Icons.Rounded.CalendarMonth
        "search_contacts" -> Icons.Rounded.Contacts
        "search_call_history" -> Icons.Rounded.Phone
        "ask_user", "search_messages" -> Icons.Rounded.ChatBubble
        "search_media", "search_qq_chat_images", "search_wechat_chat_images" -> Icons.Rounded.Image
        "semantic_search_media" -> Icons.Rounded.TravelExplore
        "search_audio" -> Icons.Rounded.MusicNote
        "search_recordings", "search_coloros_recordings", "search_recording_summaries" -> Icons.Rounded.Mic
        "search_files" -> Icons.Rounded.FolderOpen
        "search_downloads" -> Icons.Rounded.Download
        "search_clipboard_history" -> Icons.Rounded.ContentPaste
        "search_coloros_notes" -> Icons.AutoMirrored.Rounded.StickyNote2
        "search_coloros_memories" -> Icons.Rounded.Psychology
        "search_personal_orders" -> Icons.Rounded.ShoppingBag
        "terminal" -> Icons.Rounded.Terminal
        "terminal_job" -> Icons.Rounded.Terminal
        "run_command" -> Icons.Rounded.Terminal
        "read_file" -> Icons.Rounded.Description
        "write_file" -> Icons.Rounded.EditNote
        "list_directory" -> Icons.Rounded.FolderOpen
        "eta_get_settings" -> Icons.Rounded.Tune
        "eta_set_settings" -> Icons.Rounded.ManageAccounts
        "eta_manage_models" -> Icons.Rounded.SmartToy
        "eta_manage_subagents" -> SubAgents
        "eta_manage_history" -> Icons.Rounded.History
        "skills_list", "skills_list_curated" -> Icons.AutoMirrored.Rounded.MenuBook
        "skills_read", "skills_read_resource" -> Icons.Rounded.Description
        "skills_inspect_github" -> Icons.Rounded.Code
        "skills_install_from_github" -> Icons.Rounded.Download
        else -> if (toolId.startsWith("mcp_")) Icons.Rounded.Extension else FallbackIcon
    }

    /** 显示名：有本地化资源用资源，否则回退工具 id 原文。 */
    fun displayName(resources: Resources, toolId: String): String =
        nameRes(toolId)?.let { resources.getString(it) } ?: toolId
}
