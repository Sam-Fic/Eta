package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.ui.Alignment

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
            shape = MaterialTheme.shapes.extraLarge,
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

/**
 * KernelSU `ExpressiveDialog` 对应物：AlertDialog + `shapes.extraLarge`，
 * 参数与参考实现一致，颜色槽位沿用 `AlertDialogDefaults`。
 */
@Composable
fun EtaExpressiveDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.extraLarge,
    containerColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.containerColor,
    iconContentColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.titleContentColor,
    textContentColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/** KernelSU `ExpressiveConfirmDialog` 对应物：标题 + 可选正文 + 确认/取消文本按钮。 */
@Composable
fun EtaExpressiveConfirmDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    dismissText: String? = null,
) {
    EtaExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = dismissText?.let {
            { TextButton(onClick = onDismiss) { Text(it) } }
        },
    )
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
        // KernelSU（ExpressiveDialog）写法：动作右对齐、一律 TextButton（AlertDialog 的官方
        // 动作形态——没有整宽填充按钮），破坏性确认以 error 色文案区分，不加按钮底色。
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(
                onClick = {
                    TouchHaptics.click(view)
                    onCancel()
                },
                enabled = cancelEnabled,
            ) {
                Text(text = cancelText ?: stringResource(R.string.action_cancel))
            }
            TextButton(
                onClick = {
                    TouchHaptics.click(view)
                    onConfirm()
                },
                enabled = confirmEnabled,
            ) {
                Text(
                    text = confirmText,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
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
    keyboardActions: KeyboardActions = KeyboardActions.Default,
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
            keyboardActions = keyboardActions,
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
