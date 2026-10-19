package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSearchHistory
import io.github.mangi.eta.ui.adaptive.EtaSearchPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.icons.SubAgents
import io.github.mangi.eta.ui.navigation.AppRoute
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding

/** 查询历史分域键：设置搜索历史独立存储。 */
private const val HISTORY_KEY = "settings"

/** 设置搜索动作：子页面路由，或系统设置意图（悬浮窗 / 无障碍）。 */
private sealed interface SettingsSearchAction {
    data class Route(val route: AppRoute) : SettingsSearchAction
    data object OverlayPermission : SettingsSearchAction
    data object AccessibilitySettings : SettingsSearchAction
}

/**
 * 设置搜索索引项：一个可跳转的设置入口。
 *
 * 只收录「点了会走」的行（子页面路由 + 系统设置意图）；行内开关 / 选择器 / 一次性动作
 * （导出日志等）没有可跳转目标，不收录。[group] 参与匹配，让「权限」「工具」等分组词也能搜。
 */
private data class SettingsSearchEntry(
    val title: String,
    val summary: String?,
    val icon: ImageVector,
    val group: String,
    val action: SettingsSearchAction,
)

/**
 * 独立搜索页：从设置主页右上角的单搜索图标跳入，整页即搜索。
 *
 * 顶栏是「返回钮 + 透明底搜索框 + 清除按钮」（官方 [EtaSearchPage]），进入即自动聚焦弹键盘；
 * 未输入时有搜索记录列表、无记录才显示官方 Empty state，不复制主页内容。
 * 结果按标题 / 摘要 / 所属分组实时过滤，点按即跳转到对应设置页（或打开系统设置意图），
 * 与主页该行的行为一致，并把查询词记入搜索历史。
 */
@Composable
internal fun SettingsSearchScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    // 与主页同一判定：任务后端未安装时不收录「任务界面」入口。
    val taskBackendInstalled = rememberTaskBackendInstalled()
    val entries = settingsSearchEntries(taskBackendInstalled = taskBackendInstalled)

    EtaSearchPage(
        searchPlaceholder = stringResource(R.string.settings_search_hint),
        onBack = onBack,
        historyKey = HISTORY_KEY,
    ) { query ->
        // LazyListScope 不是 Composable 作用域，过滤直接内联计算。
        val needle = query.trim()
        val matched = entries.filter { entry ->
            needle.isBlank() ||
                entry.title.contains(needle, ignoreCase = true) ||
                entry.summary?.contains(needle, ignoreCase = true) == true ||
                entry.group.contains(needle, ignoreCase = true)
        }
        if (matched.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(title = stringResource(R.string.settings_search_no_matches))
            }
        } else {
            matched.forEachIndexed { index, entry ->
                item(key = entry.title) {
                    EtaSegmentedItem(index = index, count = matched.size) {
                        EtaClickableRow(
                            title = entry.title,
                            // 无自有摘要时用所属分组名兜底，保留「这在哪个分区」的上下文。
                            summary = entry.summary ?: entry.group,
                            leading = { PreferenceIcon(icon = entry.icon) },
                            onClick = {
                                EtaSearchHistory.add(context, HISTORY_KEY, query)
                                when (val action = entry.action) {
                                    is SettingsSearchAction.Route -> onNavigate(action.route)
                                    SettingsSearchAction.OverlayPermission ->
                                        openOverlayPermissionSettings(context)
                                    SettingsSearchAction.AccessibilitySettings ->
                                        openAccessibilitySettings(context)
                                }
                            },
                            modifier = Modifier.padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = if (index == matched.lastIndex) {
                                    EtaSegmentedGroupSpacing
                                } else {
                                    EtaSegmentedGap
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 设置搜索索引：与主页分组顺序、图标、跳转目标一一对应。 */
@Composable
private fun settingsSearchEntries(taskBackendInstalled: Boolean?): List<SettingsSearchEntry> {
    val groupLlmProviders = stringResource(R.string.settings_llm_providers)
    val groupModelFeatures = stringResource(R.string.settings_model_features)
    val groupContextExtensions = stringResource(R.string.settings_context_extensions)
    val groupGeneral = stringResource(R.string.settings_general)
    val groupToolCapabilities = stringResource(R.string.settings_tool_capabilities)
    val groupRuntimeEnvironment = stringResource(R.string.settings_runtime_environment)
    val groupSystemTakeover = stringResource(R.string.settings_system_takeover)
    val groupPermissions = stringResource(R.string.ui_permissions_560165)
    return buildList {
        // ── LLM 提供商 ──────────────────────────────────────────
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.ui_model_provider_e8c7f5),
                summary = null,
                icon = Icons.Rounded.Memory,
                group = groupLlmProviders,
                action = SettingsSearchAction.Route(AppRoute.ModelProviders),
            ),
        )

        // ── 模型功能 ────────────────────────────────────────────
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.route_context_compression),
                summary = null,
                icon = Icons.Rounded.Compress,
                group = groupModelFeatures,
                action = SettingsSearchAction.Route(AppRoute.ContextCompression),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.auxiliary_vision_title),
                summary = null,
                icon = Icons.Rounded.RemoveRedEye,
                group = groupModelFeatures,
                action = SettingsSearchAction.Route(AppRoute.AuxiliaryVision),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.title_model_title),
                summary = null,
                icon = Icons.Rounded.AutoAwesome,
                group = groupModelFeatures,
                action = SettingsSearchAction.Route(AppRoute.TitleModel),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.error_reconnect_title),
                summary = null,
                icon = Icons.Rounded.Refresh,
                group = groupModelFeatures,
                action = SettingsSearchAction.Route(AppRoute.ErrorReconnectSettings),
            ),
        )

        // ── 扩展 ────────────────────────────────────────────────
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.settings_assistants),
                summary = null,
                icon = Icons.Rounded.SmartToy,
                group = groupContextExtensions,
                action = SettingsSearchAction.Route(AppRoute.Assistants()),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.route_skills),
                summary = null,
                icon = Icons.Rounded.Extension,
                group = groupContextExtensions,
                action = SettingsSearchAction.Route(AppRoute.Skills),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.route_mcp_servers),
                summary = null,
                icon = Icons.Rounded.AccountTree,
                group = groupContextExtensions,
                action = SettingsSearchAction.Route(AppRoute.McpServers),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.settings_subagent_title),
                summary = null,
                icon = SubAgents,
                group = groupContextExtensions,
                action = SettingsSearchAction.Route(AppRoute.SubAgents),
            ),
        )

        // ── 通用 ────────────────────────────────────────────────
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.voice_title),
                summary = null,
                icon = Icons.Rounded.RecordVoiceOver,
                group = groupGeneral,
                action = SettingsSearchAction.Route(AppRoute.VoiceSettings),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.appearance_title),
                summary = null,
                icon = Icons.Rounded.Palette,
                group = groupGeneral,
                action = SettingsSearchAction.Route(AppRoute.AppearanceSettings),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.stats_page_title),
                summary = null,
                icon = Icons.Rounded.BarChart,
                group = groupGeneral,
                action = SettingsSearchAction.Route(AppRoute.UsageStats),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.data_backup_title),
                summary = null,
                icon = Icons.Rounded.Description,
                group = groupGeneral,
                action = SettingsSearchAction.Route(AppRoute.DataBackup),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.haptics_list),
                summary = stringResource(R.string.haptics_entry_summary),
                icon = Icons.Rounded.Vibration,
                group = groupGeneral,
                action = SettingsSearchAction.Route(AppRoute.Haptics),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.ui_about_bed172),
                summary = null,
                icon = Icons.Rounded.Info,
                group = groupGeneral,
                action = SettingsSearchAction.Route(AppRoute.About),
            ),
        )

        // ── Agent 工具能力 ──────────────────────────────────────
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.settings_tools_list),
                summary = null,
                icon = Icons.Rounded.Dashboard,
                group = groupToolCapabilities,
                action = SettingsSearchAction.Route(AppRoute.Tools),
            ),
        )
        if (taskBackendInstalled == true) {
            add(
                SettingsSearchEntry(
                    title = stringResource(R.string.agent_task_surface_title),
                    summary = null,
                    icon = Icons.Rounded.Layers,
                    group = groupToolCapabilities,
                    action = SettingsSearchAction.Route(AppRoute.AgentTaskPreference),
                ),
            )
        }

        // ── 运行环境 ────────────────────────────────────────────
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.ui_linux_tool_environment_314d22),
                summary = null,
                icon = Icons.Rounded.Inventory2,
                group = groupRuntimeEnvironment,
                action = SettingsSearchAction.Route(AppRoute.LinuxEnvironment),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.capability_workspace),
                summary = stringResource(R.string.capability_workspace_summary),
                icon = Icons.Rounded.Folder,
                group = groupRuntimeEnvironment,
                action = SettingsSearchAction.Route(AppRoute.Workspace),
            ),
        )

        // ── 系统助手接管 ────────────────────────────────────────
        // 主页只剩子页入口：摘要里带上原四个分组的关键词，Gemini / 厂商兼容 / 一圈即搜 仍可搜到。
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.ui_system_assistant_takes_over_f46043),
                summary = stringResource(R.string.settings_system_takeover_summary),
                icon = Icons.Rounded.PowerSettingsNew,
                group = groupSystemTakeover,
                action = SettingsSearchAction.Route(AppRoute.SystemTakeover),
            ),
        )

        // ── 权限 ────────────────────────────────────────────────
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.ui_permission_health_3048bb),
                summary = stringResource(R.string.ui_permissions_and_status_35f368),
                icon = Icons.Rounded.HealthAndSafety,
                group = groupPermissions,
                action = SettingsSearchAction.Route(AppRoute.Permissions),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.capability_enhancements),
                summary = stringResource(R.string.capability_enhancements_summary),
                icon = Icons.Rounded.Security,
                group = groupPermissions,
                action = SettingsSearchAction.Route(AppRoute.SystemEnhance),
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.ui_floating_window_permissions_076b77),
                summary = null,
                icon = Icons.Rounded.Layers,
                group = groupPermissions,
                action = SettingsSearchAction.OverlayPermission,
            ),
        )
        add(
            SettingsSearchEntry(
                title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                summary = null,
                icon = Icons.Rounded.AccessibilityNew,
                group = groupPermissions,
                action = SettingsSearchAction.AccessibilitySettings,
            ),
        )
    }
}

// ── 系统设置意图：与主页对应行的行为一致 ─────────────────────────────────────────

private fun openOverlayPermissionSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
