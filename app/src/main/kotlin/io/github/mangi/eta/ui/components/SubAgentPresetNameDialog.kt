package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions

/**
 * 子智能体预设命名弹窗。
 *
 * 走自适应 [EtaDialog]：Material 3 使用官方 `AlertDialog`，MIUI X 仍使用窗口级表单宿主。
 * 编辑框保持共用 [EtaFormTextField]，动作行统一由 [EtaDialogActions] 渲染，
 * 不再自行拼装按钮行。
 */
@Composable
internal fun SubAgentPresetNameDialog(
    title: String,
    name: String,
    onNameChange: (String) -> Unit,
    saveEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    fieldHint: String = "组名称",
) {
    EtaDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            EtaFormTextField(
                value = name,
                onValueChange = { onNameChange(it.take(80)) },
                hint = fieldHint,
                singleLine = true,
            )
            EtaDialogActions(
                confirmText = stringResource(R.string.action_save),
                cancelText = stringResource(R.string.action_cancel),
                confirmEnabled = saveEnabled,
                onCancel = onDismiss,
                onConfirm = onSave,
            )
        }
    }
}
