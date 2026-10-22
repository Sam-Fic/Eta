package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentPreset
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.SubAgentDropdownMenu
import io.github.mangi.eta.ui.components.SubAgentPresetDirectoryStatus
import io.github.mangi.eta.ui.components.SubAgentPresetNameDialog
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.components.etaRippleConfiguration
import io.github.mangi.eta.ui.components.rememberSubAgentPresetDirectory
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 子代理预设组列表。
 *
 * 骨架统一走 [EtaScaffoldPage]（大标题折叠顶栏 + surfaceContainer 底），列表统一走
 * KernelSU 式分段列表；新增入口换成右下角 Small Extended FAB，随列表滚动收起为纯图标。
 *
 * 详情是同级的独立导航目标（[io.github.mangi.eta.ui.navigation.AppRoute.SubAgentPresetDetail]），
 * 不在本页内部切换——这样入栈/出栈由 NavHost 统一转场，返回手势也能走预测性返回。
 */
@Composable
internal fun SubAgentSettingsScreen(
    onBack: () -> Unit,
    onOpenPreset: (String) -> Unit,
    repositoryFactory: () -> ConversationSubAgentPreferences = { ConversationSubAgentPreferences() },
) {
    // Deliberately independent of the app's conversation editor (and its running-task lock).
    val repository = remember { repositoryFactory() }
    val directory = rememberSubAgentPresetDirectory(repository)
    var add by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<SubAgentPreset?>(null) }
    var delete by remember { mutableStateOf<SubAgentPreset?>(null) }
    var name by remember { mutableStateOf("") }
    val view = LocalView.current
    val context = LocalContext.current
    val editable = !directory.loading && directory.error == null
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)
    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
        WithoutPressRipple {
            EtaScaffoldPage(
                title = stringResource(R.string.sas_groups_title),
                onBack = onBack,
                listState = listState,
                floatingActionButton = if (editable) {
                    {
                        EtaExtendedFab(
                            text = stringResource(R.string.sas_add_group),
                            icon = Icons.Rounded.Add,
                            expanded = fabExpanded,
                            onClick = { TouchHaptics.click(view); name = ""; add = true },
                        )
                    }
                } else {
                    null
                },
            ) {
                item(key = "hint") {
                    Text(
                        text = stringResource(R.string.sas_group_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            bottom = 6.dp,
                        ),
                    )
                    Box(Modifier.padding(horizontal = EtaGroupHorizontalPadding)) {
                        if (editable && directory.entries.isEmpty()) {
                            // 空态参考 MCP servers 页：分段容器做底板，文案居中，避免裸文本悬空。
                            EtaSegmentedItemContainer {
                                ListEmptyState(title = stringResource(R.string.spc_empty))
                            }
                        } else {
                            SubAgentPresetDirectoryStatus(directory)
                        }
                    }
                }
                item(key = "groups") {
                    EtaSegmentedColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = EtaSegmentedGroupSpacing,
                            ),
                    ) {
                        directory.entries.forEach { preset ->
                            item(key = preset.id) {
                                var groupMenuExpanded by remember(preset.id) { mutableStateOf(false) }
                                LaunchedEffect(editable) { if (!editable) groupMenuExpanded = false }
                                EtaSegmentedRow(
                                    // 保留原卡片行的可访问名，TalkBack 与 UI 测试都依赖它区分同名分组。
                                    modifier = Modifier.semantics {
                                        contentDescription = context.getString(R.string.sas_edit_group_for, preset.name)
                                    },
                                    onClick = {
                                        if (editable && directory.change {
                                                check(repository.presetExists(preset.id)) {
                                                    context.getString(R.string.sas_group_missing)
                                                }
                                            }) {
                                            onOpenPreset(preset.id)
                                        }
                                    },
                                    enabled = editable,
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Rounded.AccountTree,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            text = stringResource(R.string.sas_profiles_count, preset.config.profiles.size) + " · " +
                                                if (preset.config.enabled) stringResource(R.string.sas_auto_delegation_on) else stringResource(R.string.sas_auto_delegation_off),
                                        )
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // 溢出操作走就地弹出菜单，与详情页子代理标题行保持一致，不再拉起底部面板。
                                            Box {
                                                IconButton(
                                                    enabled = editable,
                                                    modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
                                                    onClick = {
                                                        if (editable) { TouchHaptics.click(view); groupMenuExpanded = true }
                                                    },
                                                ) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.sas_group_more_for, preset.name)) }
                                                if (editable) SubAgentDropdownMenu(
                                                    expanded = groupMenuExpanded,
                                                    onDismissRequest = { groupMenuExpanded = false },
                                                    itemCount = 2,
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.spa_rename_group)) },
                                                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                                        shape = EtaMenuItemShape(index = 0, count = 2),
                                                        onClick = {
                                                            groupMenuExpanded = false
                                                            if (editable) { name = preset.name; rename = preset }
                                                        },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.spa_delete_group), color = MaterialTheme.colorScheme.error) },
                                                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                                        shape = EtaMenuItemShape(index = 1, count = 2),
                                                        onClick = {
                                                            groupMenuExpanded = false
                                                            if (editable) delete = preset
                                                        },
                                                    )
                                                }
                                            }
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                ) {
                                    Text(text = preset.name)
                                }
                            }
                        }
                    }
                }
            }
            if (add || rename != null) SubAgentPresetNameDialog(
                title = if (add) stringResource(R.string.sas_add_group) else stringResource(R.string.sas_rename_group),
                name = name,
                onNameChange = { name = it },
                saveEnabled = editable && name.trim().isNotBlank(),
                onDismiss = { add = false; rename = null },
                onSave = {
                    if (directory.change {
                        if (add) repository.addPreset(name.trim())
                        else check(repository.renamePreset(requireNotNull(rename).id, name.trim())) { context.getString(R.string.sas_group_missing) }
                    }) { add = false; rename = null }
                },
            )
            delete?.let { preset ->
                AlertDialog(onDismissRequest = { delete = null }, title = { Text(stringResource(R.string.sas_delete_group_title)) },
                    text = { Text(stringResource(R.string.sas_delete_group_note, preset.name)) },
                    dismissButton = { TextButton(onClick = { delete = null }) { Text(stringResource(R.string.action_cancel)) } },
                    confirmButton = { TextButton(enabled = editable, onClick = {
                        if (directory.change { check(repository.removePreset(preset.id)) { context.getString(R.string.sas_group_missing) } }) delete = null
                    }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } })
            }
        }
    }
}
