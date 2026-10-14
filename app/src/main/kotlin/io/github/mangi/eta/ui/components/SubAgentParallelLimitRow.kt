package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.haptics.TouchHaptics

/** Limits belong to this configuration's provider/API model pool, not the global preferences. */
@Composable
internal fun SubAgentParallelLimitRow(profile: SubAgentProfile, config: AgentModelClient.ModelConfig?, enabled: Boolean = true) {
    val editor = LocalConversationSubAgentEditor.current
    val view = LocalView.current
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
        SubAgentSettingRow(stringResource(R.string.sap_parallel_label), when {
            state is SubAgentEditorState.Error -> stringResource(R.string.sap_parallel_read_failed)
            ownerConfig == null -> stringResource(R.string.sap_parallel_reading)
            config == null -> if (profile.modelId.isBlank()) stringResource(R.string.sap_parallel_no_model) else stringResource(R.string.sap_model_unavailable)
            limit == 0 -> stringResource(R.string.sap_parallel_unlimited)
            limit != null -> limit.toString()
            else -> stringResource(R.string.sap_model_unavailable)
        }, Icons.AutoMirrored.Rounded.CallSplit, stringResource(R.string.sap_parallel_set_for, profile.name), enabled = usable,
            onClick = {
                if (currentUsable && config != null && limit != null) {
                    TouchHaptics.click(view)
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
            AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.sap_parallel_dialog_title, profile.name)) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${config.providerName} · ${config.modelDisplayName.ifBlank { config.model }}")
                    OutlinedTextField(value, { if (currentUsable) value = it }, label = { Text(stringResource(R.string.sap_parallel_input_hint)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, isError = number == null, enabled = usable,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text(stringResource(R.string.sap_parallel_shared_note))
                } },
                dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
                confirmButton = { TextButton(enabled = usable && number != null, onClick = {
                    if (currentUsable && number != null &&
                        editor?.saveParallelLimit(profile.id, profile.providerId, profile.modelId, config.model, number) is ConversationSubAgentPreferences.WriteResult.Saved)
                        open = false
                }) { Text(stringResource(R.string.action_save)) } })
        }
    }
}
