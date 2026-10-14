package io.github.mangi.eta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentPreset
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.ConversationSubAgentEditor
import io.github.mangi.eta.ui.components.LocalConversationSubAgentEditor
import io.github.mangi.eta.ui.components.SubAgentDropdownMenu
import io.github.mangi.eta.ui.components.SubAgentEditorState
import io.github.mangi.eta.ui.components.SubAgentPresetActionsSheet
import io.github.mangi.eta.ui.components.SubAgentPresetDirectoryStatus
import io.github.mangi.eta.ui.components.SubAgentPresetNameDialog
import io.github.mangi.eta.ui.components.SubAgentProfileConfigDialog
import io.github.mangi.eta.ui.components.SubAgentProfileDraftSession
import io.github.mangi.eta.ui.components.SubAgentProfileRow
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.components.etaRippleConfiguration
import io.github.mangi.eta.ui.components.rememberSubAgentPresetDirectory
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 子代理预设组列表。
 *
 * 骨架统一走 [EtaScaffoldPage]（大标题折叠顶栏 + surfaceContainer 底），列表统一走
 * KernelSU 式分段列表；新增入口换成右下角 Small Extended FAB，随列表滚动收起为纯图标。
 */
@Composable
internal fun SubAgentSettingsScreen(
    onBack: () -> Unit,
    repositoryFactory: () -> ConversationSubAgentPreferences = { ConversationSubAgentPreferences() },
    isCurrentRoute: Boolean = true,
) {
    // Deliberately independent of the app's conversation editor (and its running-task lock).
    val repository = remember { repositoryFactory() }
    val directory = rememberSubAgentPresetDirectory(repository)
    var selectedId by remember { mutableStateOf<String?>(null) }
    val group = directory.entries.firstOrNull { it.id == selectedId }
    LaunchedEffect(directory.entries, directory.loading, directory.error) {
        if (!directory.loading && directory.error == null && selectedId != null && group == null) selectedId = null
    }
    if (group != null) {
        key(group.id) {
            val owner = remember(group.id) { SubAgentConfigKey.Preset(group.id) }
            val editor = remember(owner) {
                ConversationSubAgentEditor(owner, repository) {
                    selectedId == owner.value && try { repository.presetExists(owner.value) } catch (_: Exception) { false }
                }
            }
            DisposableEffect(editor) { onDispose { editor.dispose() } }
            val detailBack = { editor.dispose(); selectedId = null }
            BackHandler(enabled = isCurrentRoute, onBack = detailBack)
            CompositionLocalProvider(LocalConversationSubAgentEditor provides editor) {
                SubAgentPresetDetail(editor, group.name, detailBack, isCurrentRoute)
            }
        }
        return
    }
    var add by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<SubAgentPreset?>(null) }
    var delete by remember { mutableStateOf<SubAgentPreset?>(null) }
    var actionsPreset by remember { mutableStateOf<SubAgentPreset?>(null) }
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
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    )
                    Box(Modifier.padding(horizontal = 16.dp)) { SubAgentPresetDirectoryStatus(directory) }
                }
                item(key = "groups") {
                    EtaSegmentedColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    ) {
                        directory.entries.forEach { preset ->
                            item(key = preset.id) {
                                EtaSegmentedRow(
                                    // 保留原卡片行的可访问名，TalkBack 与 UI 测试都依赖它区分同名分组。
                                    modifier = Modifier.semantics {
                                        contentDescription = context.getString(R.string.sas_edit_group_for, preset.name)
                                    },
                                    onClick = {
                                        if (editable) directory.change {
                                            check(repository.presetExists(preset.id)) { context.getString(R.string.sas_group_missing) }
                                            selectedId = preset.id
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
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                enabled = editable,
                                                modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
                                                onClick = {
                                                    if (editable) { TouchHaptics.click(view); actionsPreset = preset }
                                                },
                                            ) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.sas_group_more_for, preset.name)) }
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                ) {
                                    Text(text = preset.name, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
            actionsPreset?.takeIf { editable }?.let { preset ->
                SubAgentPresetActionsSheet(preset, onDismiss = { actionsPreset = null },
                    onRename = {
                        if (editable) { name = preset.name; rename = preset }
                        actionsPreset = null
                    }, onDelete = {
                        if (editable) delete = preset
                        actionsPreset = null
                    })
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

/**
 * 预设组详情：组内子代理列表。
 *
 * 每个子代理是一组分段条目（标题行 + 设置块），新增入口同样是右下角 Small Extended FAB。
 */
@Composable
private fun SubAgentPresetDetail(editor: ConversationSubAgentEditor, groupName: String, onBack: () -> Unit, isCurrentRoute: Boolean) {
    val state = editor.observe()
    val lastLoadedConfig = remember(editor) { arrayOfNulls<ConversationSubAgentConfig>(1) }
    val loadedConfig = (state as? SubAgentEditorState.Loaded)?.config
    if (loadedConfig != null) lastLoadedConfig[0] = loadedConfig
    val config = loadedConfig ?: lastLoadedConfig[0]
    val profiles = config?.profiles.orEmpty()
    val editable = editor.enabled
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    var addedDraft by remember(editor) { mutableStateOf<SubAgentProfileDraftSession?>(null) }
    var rename by remember(editor) { mutableStateOf<SubAgentProfile?>(null) }
    var delete by remember(editor) { mutableStateOf<SubAgentProfile?>(null) }
    var name by remember(editor) { mutableStateOf("") }
    val view = LocalView.current
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)
    LaunchedEffect(editor, editable) { if (!editable) { rename = null; delete = null } }
    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
        WithoutPressRipple {
            EtaScaffoldPage(
                title = groupName,
                onBack = onBack,
                listState = listState,
                floatingActionButton = if (editable) {
                    {
                        EtaExtendedFab(
                            text = stringResource(R.string.sap_add_profile),
                            icon = Icons.Rounded.Add,
                            expanded = fabExpanded,
                            onClick = {
                                if (editor.enabled) SubAgentProfileDraftSession.open(editor)?.let {
                                    TouchHaptics.click(view)
                                    addedDraft = it
                                }
                            },
                        )
                    }
                } else {
                    null
                },
            ) {
                item(key = "hint") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    ) {
                        Text(
                            text = when (state) {
                                is SubAgentEditorState.Error -> stringResource(R.string.sas_read_error, state.reason)
                                SubAgentEditorState.Loading -> stringResource(R.string.sas_loading)
                                else -> if (!editable) stringResource(R.string.sas_readonly)
                                else stringResource(R.string.sas_config_note)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state is SubAgentEditorState.Error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (state is SubAgentEditorState.Error) TextButton(onClick = { editor.retry() }) { Text(stringResource(R.string.sas_retry_restore)) }
                    }
                }
                if (config != null) {
                    item(key = "profiles") {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            profiles.forEach { profile ->
                                key(profile.id) {
                                    EtaSegmentedColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp),
                                    ) {
                                        item(key = "${profile.id}-header") {
                                            SubAgentProfileHeaderRow(
                                                profile = profile,
                                                editable = editable,
                                                onEnabledChange = { active ->
                                                    if (editor.enabled) {
                                                        TouchHaptics.click(view)
                                                        editor.updateProfile(profile.id) { it.copy(enabled = active) }
                                                    }
                                                },
                                                onConfigure = {
                                                    if (editor.enabled) SubAgentProfileDraftSession.open(editor, profile)?.let {
                                                        TouchHaptics.click(view)
                                                        addedDraft = it
                                                    }
                                                },
                                                onRename = { name = profile.name; rename = profile },
                                                onDelete = { delete = profile },
                                            )
                                        }
                                        item(key = "${profile.id}-settings") {
                                            // 设置块与标题行同组：容器提供 surfaceBright 底色与分段形状，
                                            // 内部行不再自绘底色（见 SubAgentSettingRow）。
                                            EtaSegmentedItemContainer {
                                                SubAgentProfileRow(
                                                    profile = profile,
                                                    providers = providers,
                                                    enabled = editable,
                                                    settings = true,
                                                    draftAllowed = true,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            addedDraft?.let { session ->
                SubAgentProfileConfigDialog(session, editor, providers, enabled = isCurrentRoute, onDismiss = {
                    session.dismiss()
                    if (addedDraft === session) addedDraft = null
                })
            }
            rename?.let { profile ->
                if (editable) SubAgentPresetNameDialog(
                    title = stringResource(R.string.sas_rename_agent),
                    fieldHint = stringResource(R.string.sap_name),
                    name = name,
                    onNameChange = { if (editor.enabled) name = it },
                    saveEnabled = name.trim().isNotBlank(),
                    onDismiss = { rename = null },
                    onSave = {
                        if (editor.enabled && editor.updateProfile(profile.id) { it.copy(name = name.trim()) } is ConversationSubAgentPreferences.WriteResult.Saved)
                            rename = null
                    },
                )
            }
            delete?.let { profile ->
                if (editable) AlertDialog(onDismissRequest = { delete = null }, title = { Text(stringResource(R.string.sas_delete_agent_title)) },
                    text = { Text(stringResource(R.string.sas_delete_agent_note, profile.name)) },
                    dismissButton = { TextButton(onClick = { delete = null }) { Text(stringResource(R.string.action_cancel)) } },
                    confirmButton = { TextButton(enabled = editable, onClick = {
                        if (editor.enabled && editor.remove(profile.id) is ConversationSubAgentPreferences.WriteResult.Saved)
                            delete = null
                    }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } })
            }
        }
    }
}

/**
 * 单个子代理的标题行：角色图标 + 名称 + 启用开关 + 更多操作。
 *
 * 形状与容器色取自外层分段组（KernelSU 式 `SegmentedListItem`）；开关与溢出菜单是行内独立可点目标。
 */
@Composable
private fun SubAgentProfileHeaderRow(
    profile: SubAgentProfile,
    editable: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onConfigure: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    var menuExpanded by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(editable) { if (!editable) menuExpanded = false }
    EtaSegmentedRow(
        modifier = Modifier.fillMaxWidth(),
        enabled = editable,
        leadingContent = {
            Icon(
                imageVector = when (profile.role) {
                    "review" -> Icons.AutoMirrored.Rounded.FactCheck
                    "image_generation" -> Icons.Rounded.Image
                    "video_generation" -> Icons.Rounded.Videocam
                    else -> Icons.Rounded.AccountTree
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 尺寸由外层约束（保持行内开关的紧凑观感）；
                // 名称挂在开关自身的语义节点上：ListItem 的命中测试不会把外层包裹盒的点击
                // 透传给内部开关，因此可访问名必须落在真正可切换的节点上。
                Box(
                    modifier = Modifier.requiredSize(36.dp, 22.dp).wrapContentSize(unbounded = true),
                    contentAlignment = Alignment.Center,
                ) {
                    EtaSwitch(
                        checked = profile.enabled,
                        enabled = editable,
                        modifier = Modifier.semantics { contentDescription = context.getString(R.string.sas_enable_for, profile.name) },
                        onCheckedChange = { active -> if (editable) onEnabledChange(active) },
                    )
                }
                Box {
                    IconButton(enabled = editable, onClick = {
                        if (editable) { TouchHaptics.click(view); menuExpanded = true }
                    }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.sas_more_for, profile.name)) }
                    if (editable) SubAgentDropdownMenu(menuExpanded, { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.sas_configure)) }, leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                            onClick = { menuExpanded = false; onConfigure() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menuExpanded = false; onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() })
                    }
                }
            }
        },
    ) {
        Text(
            text = profile.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
