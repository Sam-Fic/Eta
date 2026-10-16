package io.github.mangi.eta.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.PermissionStatusUi
import io.github.mangi.eta.ui.model.RunStatusUi

/**
 * 语义状态色。
 *
 * 全部取官方 `colorScheme` 角色（M3 没有 success/warning 专用角色：
 * 成功＝`primary`、进行中＝`primary`、注意＝`tertiary`、失败＝`error`、空闲＝`onSurfaceVariant`），
 * 不再写死色值。
 */
@Composable
private fun statusSuccessColor(): Color = MaterialTheme.colorScheme.primary

@Composable
private fun statusWarningColor(): Color = MaterialTheme.colorScheme.tertiary

@Composable
private fun statusErrorColor(): Color = MaterialTheme.colorScheme.error

@Composable
private fun statusRunningColor(): Color = MaterialTheme.colorScheme.primary

@Composable
private fun statusIdleColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** 成功态前景色（官方 primary）。 */
internal val StatusSuccess: Color
    @Composable get() = statusSuccessColor()

/** 注意态前景色（官方 tertiary）。 */
internal val StatusWarning: Color
    @Composable get() = statusWarningColor()

/** 失败态前景色（官方 error）。 */
internal val StatusError: Color
    @Composable get() = statusErrorColor()

/** 进行中前景色（官方 primary）。 */
internal val StatusRunning: Color
    @Composable get() = statusRunningColor()

/** 空闲态前景色（官方 onSurfaceVariant）。 */
internal val StatusIdle: Color
    @Composable get() = statusIdleColor()

// ── RunStatusUi 映射 ──────────────────────────────────────────────────

@Composable
fun RunStatusUi.color(): Color = when (this) {
    RunStatusUi.Running -> statusRunningColor()
    RunStatusUi.Success -> statusSuccessColor()
    RunStatusUi.Failed -> statusErrorColor()
    RunStatusUi.Cancelled -> statusIdleColor()
}

@Composable
fun RunStatusUi.label(): String = stringResource(when (this) {
    RunStatusUi.Running -> R.string.tool_status_running
    RunStatusUi.Success -> R.string.tool_status_success
    RunStatusUi.Failed -> R.string.tool_status_failed
    RunStatusUi.Cancelled -> R.string.status_cancelled
})

// ── PermissionStatusUi 映射 ───────────────────────────────────────────

@Composable
fun PermissionStatusUi.color(): Color = when (this) {
    PermissionStatusUi.Available -> statusSuccessColor()
    PermissionStatusUi.Warning -> statusWarningColor()
    PermissionStatusUi.Missing -> statusErrorColor()
    PermissionStatusUi.Disabled -> statusIdleColor()
}

@Composable
fun PermissionStatusUi.label(): String = stringResource(when (this) {
    PermissionStatusUi.Available -> R.string.status_ready
    PermissionStatusUi.Warning -> R.string.status_needs_attention
    PermissionStatusUi.Missing -> R.string.status_unauthorized
    PermissionStatusUi.Disabled -> R.string.status_disabled
})
