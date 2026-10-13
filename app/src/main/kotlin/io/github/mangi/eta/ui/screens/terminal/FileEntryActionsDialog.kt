package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton

@Immutable
internal data class FileEntryActions(
    val path: String,
    val name: String,
    val directory: Boolean,
    val canExport: Boolean,
    val canDelete: Boolean,
)

@Composable
internal fun FileEntryActionsDialog(
    target: FileEntryActions,
    enabled: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    EtaDialog(
        show = true,
        title = target.name,
        onDismissRequest = { if (enabled) onDismiss() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isMaterial3Theme()) {
                // 官方按钮：形状、行高与配色全部使用官方默认值。
                if (target.canExport) {
                    OutlinedButton(
                        onClick = onExport,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        M3Text(text = stringResource(R.string.linux_files_export))
                    }
                }
                if (target.canDelete) {
                    Button(
                        onClick = onDelete,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        colors = M3ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        M3Text(text = stringResource(R.string.action_delete))
                    }
                }
                M3TextButton(
                    onClick = onDismiss,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    M3Text(text = stringResource(R.string.action_cancel))
                }
            } else {
                if (target.canExport) {
                    TextButton(
                        text = stringResource(R.string.linux_files_export),
                        onClick = onExport,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                if (target.canDelete) {
                    TextButton(
                        text = stringResource(R.string.action_delete),
                        onClick = onDelete,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(
                            color = MiuixTheme.colorScheme.error,
                            textColor = MiuixTheme.colorScheme.onError,
                        ),
                    )
                }
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
