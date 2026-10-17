package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.haptics.TouchHaptics

/** A popup entry belongs to one real configuration owner (including the unique draft key).
 * Reopening invalidates previously captured callbacks even when the owner is the same. */
internal class OwnerBoundPopup<Owner>(val owner: Owner) {
    private var active: Any? = null
    fun open(): Any = Any().also { active = it }
    fun dismiss() { active = null }
    fun isCurrent(ticket: Any?, current: OwnerBoundPopup<Owner>): Boolean =
        ticket != null && current === this && active === ticket
    fun dispatch(ticket: Any?, current: OwnerBoundPopup<Owner>, action: () -> Unit): Boolean {
        if (!isCurrent(ticket, current)) return false
        action()
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationCollaborationDialog(
    show: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    taskRunning: Boolean = false,
    ownerMatches: () -> Boolean = { true },
) {
    if (!show || !ownerMatches()) return
    val context = LocalContext.current
    // Never rebind an already opened dialog to a different editor during asynchronous loading.
    val editor = LocalConversationSubAgentEditor.current?.takeIf { ownerMatches() }
    val state = editor?.observe()
    var lastLoadedConfig by remember(editor) { mutableStateOf<io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig?>(null) }
    val loadedConfig = (state as? SubAgentEditorState.Loaded)?.config
    SideEffect { if (loadedConfig != null) lastLoadedConfig = loadedConfig }
    // A failed save must not unmount a configuration draft; explicit retry preserves it.
    val current = loadedConfig ?: lastLoadedConfig
    val canChange = editor?.enabled == true && !taskRunning && ownerMatches()
    // Opening the chooser never writes: legacy conversations keep their original configuration.
    var chooserOverride by remember(editor) { mutableStateOf<Boolean?>(null) }
    val choosing = chooserOverride ?: (current?.appliedPresetId == null)
    var applicationError by remember(editor) { mutableStateOf<String?>(null) }
    val latestOwnerMatches by rememberUpdatedState(ownerMatches)
    val latestTaskRunning by rememberUpdatedState(taskRunning)
    val latestChoosing by rememberUpdatedState(choosing)
    val latestCanChange by rememberUpdatedState(canChange)
    val directory = editor?.let { rememberSubAgentPresetDirectory(it.repository) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val view = LocalView.current
    // Share the same lifetime-bound chooser/panel with the non-scrolling footer.
    val chooserPopup = if (choosing && directory != null && editor != null) {
        val popup = remember(editor) { OwnerBoundPopup(editor.owner) }
        val ticket = remember(popup) { popup.open() }
        DisposableEffect(popup) { onDispose { popup.dismiss() } }
        popup to ticket
    } else null
    val panelEditor = if (!choosing && current != null && editor != null) {
        key(editor, current.presetApplicationToken) {
            // Retained footer callbacks must reject even a reapplication with identical profile IDs.
            val scoped = remember(editor, current.presetApplicationToken) {
                editor.scoped(current.presetApplicationToken) {
                    latestOwnerMatches() && !latestTaskRunning && !latestChoosing
                }
            }
            DisposableEffect(scoped) { onDispose { scoped.dispose() } }
            scoped
        }
    } else null
    val panelState = panelEditor?.observe()
    val panelConfig = (panelState as? SubAgentEditorState.Loaded)?.config
    val panelEnabled = canChange && panelEditor?.enabled == true && panelConfig != null
    // 弹窗观感：外壳、头部、空态与动作区都按官方 Expressive 形态呈现。
    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
        WithoutPressRipple {
            // 走官方 [ModalBottomSheet]：拖拽把手、遮罩、圆角与底部安全区都由组件提供。
            CollaborationDialogShell(
                title = stringResource(R.string.ccd_title),
                onDismissRequest = onDismiss,
            ) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("subagent-collaboration-body")
                    .verticalScroll(key(editor, choosing, current?.presetApplicationToken) { rememberScrollState() })
                    .padding(bottom = 8.dp).alpha(if (canChange) 1f else 0.38f)) {
                    when (state) {
                        is SubAgentEditorState.Error -> {
                            Text(stringResource(R.string.ccd_config_failed, state.reason))
                            TextButton(onClick = { editor?.retry() }) { Text(stringResource(R.string.sap_parallel_retry_restore)) }
                        }
                        SubAgentEditorState.Loading -> Text(stringResource(R.string.ccd_loading))
                        else -> if (editor == null) Text(stringResource(R.string.ccd_select_conversation_first))
                            else if (!canChange) Text(stringResource(R.string.ccd_agent_not_stopped))
                    }
                    if (choosing && directory != null && editor != null && chooserPopup != null) {
                        val (popup, ticket) = chooserPopup
                        // M3E：模式名是分组标题，不能比对话框标题还大。
                        Text(stringResource(R.string.ccd_choose_group),
                            style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.ccd_apply_note),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
                        ExpressivePresetDirectoryStatus(directory)
                        directory.entries.forEach { preset ->
                            key(editor, preset.id) {
                                SubAgentPresetCard(preset, canChange && directory.error == null && !directory.loading,
                                    stringResource(R.string.ccd_apply_group_for, preset.name), selected = current?.appliedPresetId == preset.id,
                                    onClick = {
                                        if (popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && latestCanChange && latestChoosing) {
                                            val result = editor.applyPreset(preset.id) {
                                                popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && latestChoosing
                                            }
                                            if (result is ConversationSubAgentPreferences.WriteResult.Saved) {
                                                applicationError = null
                                                chooserOverride = false
                                            } else applicationError = context.getString(R.string.ccd_apply_failed)
                                        }
                                    })
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                        applicationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    } else if (current != null && editor != null && panelEditor != null) {
                        key(panelEditor) {
                            var addedDraft by remember(panelEditor) { mutableStateOf<SubAgentProfileDraftSession?>(null) }
                            CompositionLocalProvider(LocalConversationSubAgentEditor provides panelEditor) {
                                when (panelState) {
                                    is SubAgentEditorState.Error -> {
                                        Text(stringResource(R.string.ccd_panel_config_failed, panelState.reason), color = MaterialTheme.colorScheme.error)
                                        TextButton(enabled = latestOwnerMatches() && !latestTaskRunning, onClick = {
                                            if (latestOwnerMatches() && !latestTaskRunning && !latestChoosing) {
                                                TouchHaptics.click(view)
                                                // Both subscriptions can be fenced by the same failed commit.
                                                editor.retry()
                                                panelEditor.retry()
                                            }
                                        }) { Text(stringResource(R.string.ccd_retry_panel_config)) }
                                    }
                                    SubAgentEditorState.Loading -> Text(stringResource(R.string.ccd_loading))
                                    else -> Unit
                                }
                                (panelConfig ?: current).appliedPresetName?.let {
                                    Text(stringResource(R.string.ccd_applied_prefix, it), style = MaterialTheme.typography.bodyMedium)
                                }
                                // 官方 ListItem 承载整行点击与无障碍开关语义，官方 Switch 只负责呈现状态。
                                ListItem(
                                    supportingContent = { Text(stringResource(R.string.ccd_auto_assign)) },
                                    trailingContent = {
                                        Switch(checked = panelConfig?.enabled ?: current.enabled, enabled = panelEnabled, onCheckedChange = null)
                                    },
                                    modifier = Modifier.toggleable(
                                        value = panelConfig?.enabled ?: current.enabled, enabled = panelEnabled, role = Role.Switch,
                                        onValueChange = {
                                            if (panelEnabled && panelEditor.enabled) { TouchHaptics.click(view); panelEditor.setEnabled(it) }
                                        },
                                    ),
                                ) {
                                    Text(stringResource(R.string.ccd_auto_delegation))
                                }
                                HorizontalDivider(Modifier.padding(top = 12.dp))
                                (panelConfig ?: current).profiles.forEach { profile ->
                                    key(panelEditor, profile.id) {
                                        SubAgentProfileRow(profile, providers, enabled = panelEnabled,
                                            draftAllowed = latestOwnerMatches() && !latestTaskRunning && !latestChoosing,
                                            draftRetry = { editor.retry(); panelEditor.retry() })
                                        HorizontalDivider()
                                    }
                                }
                                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.ccd_tap_hint), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    IconButton(modifier = Modifier.size(48.dp), enabled = panelEnabled, onClick = {
                                        if (panelEnabled && panelEditor.enabled) {
                                            SubAgentProfileDraftSession.open(panelEditor)?.let {
                                                TouchHaptics.click(view)
                                                addedDraft = it
                                            }
                                        }
                                    }) { Icon(Icons.Rounded.Add, stringResource(R.string.sap_add_profile)) }
                                }
                                addedDraft?.let { session ->
                                    SubAgentProfileConfigDialog(session, panelEditor, providers,
                                        enabled = latestOwnerMatches() && !latestTaskRunning && !latestChoosing,
                                        onDismiss = {
                                            session.dismiss()
                                            if (addedDraft === session) addedDraft = null
                                        }, onRetry = { editor.retry(); panelEditor.retry() })
                                }
                            }
                        }
                    }
                }
                // Fixed footer: changing the chooser state never requires scrolling past the cards.
                Row(Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 48.dp).testTag("subagent-collaboration-footer"),
                    verticalAlignment = Alignment.CenterVertically,
                    // 动作区右对齐（官方对话框动作形态）。
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (choosing && current != null && editor != null && chooserPopup != null) {
                        val (popup, ticket) = chooserPopup
                        TextButton(enabled = canChange, modifier = Modifier.semantics { onClick {
                            if (popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning &&
                                latestCanChange && latestChoosing && editor.enabled) {
                                TouchHaptics.click(view)
                                chooserOverride = false
                            }
                            true
                        } }, onClick = {
                            if (popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning &&
                                latestCanChange && latestChoosing && editor.enabled) {
                                TouchHaptics.click(view)
                                chooserOverride = false
                            }
                        }) { Text(stringResource(R.string.ccd_use_current_config)) }
                    } else if (!choosing && panelEditor != null) {
                        TextButton(enabled = panelEnabled, modifier = Modifier.semantics { onClick {
                            if (panelEnabled && panelEditor.enabled && latestOwnerMatches() && !latestTaskRunning && !latestChoosing) {
                                TouchHaptics.click(view)
                                chooserOverride = true
                            }
                            true
                        } }, onClick = {
                            if (panelEnabled && panelEditor.enabled && latestOwnerMatches() && !latestTaskRunning && !latestChoosing) {
                                TouchHaptics.click(view)
                                chooserOverride = true
                            }
                        }) { Text(stringResource(R.string.ccd_switch_group)) }
                    }
                    TextButton(enabled = !taskRunning, onClick = {
                        if (latestOwnerMatches() && !latestTaskRunning) { TouchHaptics.click(view); onDismiss() }
                    }) { Text(stringResource(R.string.action_done)) }
                }
            }
        }
    }
}

/**
 * 弹窗外壳：官方 [ModalBottomSheet]，拖拽把手、遮罩、圆角与底部安全区都由组件提供，
 * 高度随内容增长、超出屏幕再由正文自己滚动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollaborationDialogShell(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        // 与「助手与模型」选择器一致：只保留 Hidden↔Expanded 两态，不进半展开。
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        ) {
            ExpressiveCollaborationHeader(title)
            content()
        }
    }
}

/**
 * M3E 头部：色调圆底图标 + 标题，与官方对话框的 icon/title 层级一致。
 */
@Composable
private fun ExpressiveCollaborationHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * M3E 目录状态：读取中与空目录都收成居中的次要提示，配一枚图标；
 * 读取失败仍保留可点的重试，不把失败伪装成空目录。
 */
@Composable
private fun ExpressivePresetDirectoryStatus(directory: SubAgentPresetDirectory) {
    val view = LocalView.current
    val error = directory.error
    when {
        error != null -> {
            Text(stringResource(R.string.spc_dir_error, error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { TouchHaptics.click(view); directory.retry() }) { Text(stringResource(R.string.spc_retry_read)) }
        }
        directory.loading -> Text(stringResource(R.string.spc_reading),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        directory.entries.isEmpty() -> ExpressivePresetEmptyHint(R.string.spc_empty)
    }
}

@Composable
private fun ExpressivePresetEmptyHint(textRes: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = Icons.Rounded.AccountTree, contentDescription = null,
            modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
