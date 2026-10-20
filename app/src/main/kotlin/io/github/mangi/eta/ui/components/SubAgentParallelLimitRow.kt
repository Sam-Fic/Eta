package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaSegmentedScope

/** Limits belong to this configuration's provider/API model pool, not the global preferences.
 *  [EtaSegmentedScope] 版注册：行、读取失败提示与编辑弹窗同属一个分段条目（普通函数，
 *  可在分段列的 DSL 内调用；直接组合会落在非 @Composable 上下文）。 */
internal fun EtaSegmentedScope.addParallelLimitItem(profile: SubAgentProfile, config: AgentModelClient.ModelConfig?, enabled: Boolean) {
    item(key = "parallel") { SubAgentParallelLimitContent(profile, config, enabled) }
}

/** 独立渲染（无外部分段列）：行直接铺出、形状退化为单条目。 */
@Composable
internal fun SubAgentParallelLimitRow(profile: SubAgentProfile, config: AgentModelClient.ModelConfig?, enabled: Boolean = true) {
    SubAgentParallelLimitContent(profile, config, enabled)
}

@Composable
private fun SubAgentParallelLimitContent(profile: SubAgentProfile, config: AgentModelClient.ModelConfig?, enabled: Boolean) {
    val editor = LocalConversationSubAgentEditor.current
    val state = editor?.observe()
    val ownerConfig = (state as? SubAgentEditorState.Loaded)?.config
    val usable = enabled && editor?.enabled == true && ownerConfig != null && config != null && config.providerId.isNotBlank() && config.model.isNotBlank()
    val currentUsable by rememberUpdatedState(usable)
    key(editor, profile.id, profile.providerId, profile.modelId, profile.role, config?.providerId, config?.model, usable) {
        var open by remember { mutableStateOf(false) }
        var value by remember { mutableStateOf("") }
        val limit = if (config != null && ownerConfig != null && config.providerId.isNotBlank() && config.model.isNotBlank()) {
            ownerConfig.parallelLimit(SubAgentParallelModel(config.providerId, config.model))
        } else null
        SubAgentSettingsRow(stringResource(R.string.sap_parallel_label), when {
            state is SubAgentEditorState.Error -> stringResource(R.string.sap_parallel_read_failed)
            ownerConfig == null -> stringResource(R.string.sap_parallel_reading)
            config == null -> if (profile.modelId.isBlank()) stringResource(R.string.sap_parallel_no_model) else stringResource(R.string.sap_model_unavailable)
            limit == 0 -> stringResource(R.string.sap_parallel_unlimited)
            limit != null -> limit.toString()
            else -> stringResource(R.string.sap_model_unavailable)
        }, Icons.AutoMirrored.Rounded.CallSplit, stringResource(R.string.sap_parallel_set_for, profile.name), enabled = usable,
            onClick = {
                if (currentUsable && config != null && limit != null) {
                    value = limit.toString()
                    open = true
                }
            })
        if (state is SubAgentEditorState.Error) {
            Text(stringResource(R.string.sap_parallel_config_error, state.reason))
            TextButton(onClick = { editor?.retry() }) { Text(stringResource(R.string.sap_parallel_retry_restore)) }
        }
        if (open && usable && config != null) {
            val number = value.trim().toIntOrNull()?.takeIf { it >= 0 }
            // 走兄弟弹窗统一的 EtaDialog：动作区交 EtaDialogActions（右对齐文字按钮，自带轻触反馈），
            // 输入框沿用共用 EtaFormTextField，不再自拼 AlertDialog 按钮行与 OutlinedTextField。
            EtaDialog(show = true, title = stringResource(R.string.sap_parallel_dialog_title, profile.name),
                onDismissRequest = { open = false },
                actions = {
                    EtaDialogActions(
                        confirmText = stringResource(R.string.action_save),
                        cancelText = stringResource(R.string.action_cancel),
                        confirmEnabled = usable && number != null,
                        onCancel = { open = false },
                        onConfirm = {
                            if (currentUsable && number != null &&
                                editor?.saveParallelLimit(profile.id, profile.providerId, profile.modelId, config.model, number) is ConversationSubAgentPreferences.WriteResult.Saved)
                                open = false
                        },
                    )
                }) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${config.providerName} · ${config.modelDisplayName.ifBlank { config.model }}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    EtaFormTextField(value, { if (currentUsable) value = it },
                        hint = stringResource(R.string.sap_parallel_input_hint), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = usable, modifier = Modifier.fillMaxWidth())
                    if (number == null) Text(stringResource(R.string.sap_parallel_invalid), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.sap_parallel_shared_note),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
