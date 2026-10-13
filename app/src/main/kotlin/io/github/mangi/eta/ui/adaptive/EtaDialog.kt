package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 自适应弹窗。内容由调用方提供（通常是输入框 + [EtaDialogActions]）。
 */
@Composable
fun EtaDialog(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable () -> Unit,
) {
    if (isMaterial3Theme()) {
        if (!show) return
        AlertDialog(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            icon = icon?.let { image ->
                {
                    // 尺寸与配色沿用官方 AlertDialog 的 icon 槽位默认令牌。
                    androidx.compose.material3.Icon(
                        imageVector = image,
                        contentDescription = null,
                    )
                }
            },
            title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    summary?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    content()
                }
            },
            confirmButton = {},
        )
    } else {
        WindowDialog(
            show = show,
            title = title,
            summary = summary,
            onDismissRequest = onDismissRequest,
        ) {
            content()
        }
    }
}

/** 弹窗底部按钮行（取消 / 确认）。 */
@Composable
fun EtaDialogActions(
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String? = null,
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
) {
    if (isMaterial3Theme()) {
        val view = LocalView.current
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 官方对话框动作：取消用 TextButton（dismiss action 的官方默认形态）。
            TextButton(
                onClick = {
                    TouchHaptics.click(view)
                    onCancel()
                },
                modifier = Modifier.weight(1f),
                enabled = cancelEnabled,
            ) {
                Text(text = cancelText ?: stringResource(R.string.action_cancel))
            }
            Button(
                onClick = {
                    TouchHaptics.click(view)
                    onConfirm()
                },
                modifier = Modifier.weight(1f),
                enabled = confirmEnabled,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(text = confirmText)
            }
        }
    } else {
        MiuixDialogActions(
            confirmText = confirmText,
            onCancel = onCancel,
            onConfirm = onConfirm,
            modifier = modifier,
            cancelText = cancelText,
            cancelEnabled = cancelEnabled,
            confirmEnabled = confirmEnabled,
            destructive = destructive,
        )
    }
}

/** 自适应单行输入框。 */
@Composable
fun EtaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
) {
    if (isMaterial3Theme()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            label = { Text(text = label) },
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
        )
    } else {
        MiuixTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            label = label,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
        )
    }
}
