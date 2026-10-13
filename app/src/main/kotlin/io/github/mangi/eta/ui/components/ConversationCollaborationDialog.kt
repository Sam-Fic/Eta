package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val maximumHeight = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160).dp
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
    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
        WithoutPressRipple {
            Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Card(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
                    .heightIn(max = maximumHeight).testTag("subagent-collaboration-card")) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                        Text("本会话协作", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("subagent-collaboration-body")
                            .verticalScroll(key(editor, choosing, current?.presetApplicationToken) { rememberScrollState() })
                            .padding(bottom = 8.dp).alpha(if (canChange) 1f else 0.38f)) {
                            when (state) {
                                is SubAgentEditorState.Error -> {
                                    Text("子代理配置读取或保存失败：${state.reason}")
                                    TextButton(onClick = { editor?.retry() }) { Text("重试恢复配置") }
                                }
                                SubAgentEditorState.Loading -> Text("正在读取本会话子代理配置…")
                                else -> if (editor == null) Text("请先选择会话；未选择会话时不可编辑。")
                                    else if (!canChange) Text("主代理尚未停止，暂不可编辑本会话配置。")
                            }
                            if (choosing && directory != null && editor != null && chooserPopup != null) {
                                val (popup, ticket) = chooserPopup
                                Text("选择子代理组", style = MaterialTheme.typography.headlineSmall)
                                Text("应用时复制到本会话；本会话修改不会反写预设。切换会替换当前会话配置。",
                                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
                                SubAgentPresetDirectoryStatus(directory)
                                directory.entries.forEach { preset ->
                                    key(editor, preset.id) {
                                        SubAgentPresetCard(preset, canChange && directory.error == null && !directory.loading,
                                            "应用子代理组${preset.name}", selected = current?.appliedPresetId == preset.id,
                                            onClick = {
                                                if (popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && latestCanChange && latestChoosing) {
                                                    val result = editor.applyPreset(preset.id) {
                                                        popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && latestChoosing
                                                    }
                                                    if (result is ConversationSubAgentPreferences.WriteResult.Saved) {
                                                        applicationError = null
                                                        chooserOverride = false
                                                    } else applicationError = "子代理组未应用，当前会话配置已保留；请重试。"
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
                                                Text("本会话配置保存或读取失败：${panelState.reason}", color = MaterialTheme.colorScheme.error)
                                                TextButton(enabled = latestOwnerMatches() && !latestTaskRunning, onClick = {
                                                    if (latestOwnerMatches() && !latestTaskRunning && !latestChoosing) {
                                                        TouchHaptics.click(view)
                                                        // Both subscriptions can be fenced by the same failed commit.
                                                        editor.retry()
                                                        panelEditor.retry()
                                                    }
                                                }) { Text("重试本会话配置") }
                                            }
                                            SubAgentEditorState.Loading -> Text("正在读取本会话子代理配置…")
                                            else -> Unit
                                        }
                                        (panelConfig ?: current).appliedPresetName?.let {
                                            Text("已应用：$it", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        // 官方 ListItem 承载整行点击与无障碍开关语义，官方 Switch 只负责呈现状态。
                                        ListItem(
                                            supportingContent = { Text("按职责自动分配任务") },
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
                                            Text("自动委派")
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
                                            Text("点按模型切换 · 长按调整思考", style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                            IconButton(modifier = Modifier.size(48.dp), enabled = panelEnabled, onClick = {
                                                if (panelEnabled && panelEditor.enabled) {
                                                    SubAgentProfileDraftSession.open(panelEditor)?.let {
                                                        TouchHaptics.click(view)
                                                        addedDraft = it
                                                    }
                                                }
                                            }) { Icon(Icons.Rounded.Add, "添加子代理") }
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
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                }) { Text("使用当前配置") }
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
                                }) { Text("切换子代理组") }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(enabled = !taskRunning, onClick = {
                                if (latestOwnerMatches() && !latestTaskRunning) { TouchHaptics.click(view); onDismiss() }
                            }) { Text("完成") }
                        }
                    }
                }
            }
        }
    }
}
