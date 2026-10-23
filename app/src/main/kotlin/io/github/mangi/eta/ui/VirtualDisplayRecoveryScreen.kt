package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.VirtualDisplayPreviewLifecycle
import io.github.mangi.eta.agent.device.VirtualDisplaySession
import io.github.mangi.eta.agent.device.VirtualDisplayWebPreview
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Inline controls on the Agent task preference page. Reading status never changes the display.
 * 网页授权独立于副屏是否存在；离开设置页不会撤销已配对浏览器。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun VirtualDisplayRecoveryControls(
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current,
    readStatus: (Context) -> JSONObject = VirtualDisplaySession::recoveryStatus,
    recover: (Context) -> JSONObject = VirtualDisplaySession::recoverAndFinishManually,
    onWorkingChanged: (Boolean) -> Unit = {},
) {
    val installed = rememberTaskBackendInstalled()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    var working by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var webPaired by remember { mutableStateOf(false) }

    fun setWorking(value: Boolean) {
        working = value
        onWorkingChanged(value)
    }

    LaunchedEffect(installed, working) {
        if (installed == false && !working) {
            try {
                withContext(NonCancellable + Dispatchers.IO) { VirtualDisplayWebPreview.revoke(context) }
                webPaired = false
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                result = JSONObject().put("ok", false).put("error", "WEB_PREVIEW_REVOKE_FAILED")
            }
        }
    }

    fun refresh() {
        if (working) return
        setWorking(true)
        scope.launch {
            try {
                webPaired = withContext(Dispatchers.IO) { VirtualDisplayWebPreview.hasPairing(context) }
                state = withContext(Dispatchers.IO) { readStatus(context.applicationContext) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                state = JSONObject().put("ok", false).put("error", "RECOVERY_STATE_UNREADABLE")
            } finally { setWorking(false) }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Navigation is not revocation. The explicit revoke action owns the pairing lifetime.
    DisposableEffect(Unit) {
        onDispose {
            onWorkingChanged(false)
        }
    }

    val snapshot = state
    BackHandler(enabled = working) { /* Do not abandon an in-flight recovery action. */ }

    val readable = snapshot?.optBoolean("ok") == true
    val present = snapshot?.optBoolean("present") == true
    val busy = snapshot?.optBoolean("busy") == true
    val phase = snapshot?.optString("phase", RECOVERY_PHASE_PENDING) ?: RECOVERY_PHASE_PENDING
    // 没有副屏也能首次授权网页；已有配对时始终可撤销。
    // 首次读取期间 working=true 会吞掉返回键，此时也显示卡片（带进度），避免“无响应”。
    val showRecovery = result != null || working || (snapshot != null && (!readable || present))
    val showWeb = installed == true || webPaired
    if (!showRecovery && !showWeb) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.agent_task_preference_recovery_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (working) {
                        // M3E LoadingIndicator：行内 16dp 槽，按 loading 选型表统一。
                        LoadingIndicator(modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = stringResource(recoveryStatusRes(readable, busy, present, phase)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (present) {
                    Text(
                        text = stringResource(
                            R.string.agent_task_preference_recovery_display_id,
                            snapshot?.optInt("displayId", -1) ?: -1,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (readable && present && !busy && phase != RECOVERY_PHASE_PENDING) {
                    Text(
                        text = stringResource(R.string.agent_task_preference_recovery_raw_phase, phase),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val lastError = snapshot?.optString("lastError").orEmpty()
                if (lastError.isNotBlank()) {
                    Text(
                        text = lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val detail = snapshot?.optString("lastDetail").orEmpty()
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                result?.let { receipt ->
                    if (receipt.optBoolean("ok") && receipt.optBoolean("released")) {
                        Text(
                            text = stringResource(R.string.vd_recovery_success),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text(
                            text = stringResource(
                                R.string.vd_recovery_failed,
                                receipt.optString("error", "UNKNOWN"),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        val message = receipt.optString("message")
                        if (message.isNotBlank()) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = installed == true && !working,
                        onClick = {
                            if (!working) {
                                TouchHaptics.click(view)
                                setWorking(true)
                                scope.launch {
                                    try {
                                        val uri = withContext(Dispatchers.IO) { VirtualDisplayWebPreview.openWithManualClose(context) }
                                        val stillInstalled = withContext(Dispatchers.IO) { AgentTaskSurface.moduleInstalled() }
                                        if (!stillInstalled) {
                                            withContext(NonCancellable + Dispatchers.IO) { VirtualDisplayWebPreview.revoke(context) }
                                            webPaired = false
                                            error("Backend module removed")
                                        }
                                        webPaired = true
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (ex: CancellationException) {
                                        throw ex
                                    } catch (ex: Exception) {
                                        // A partial disk commit with failed cleanup is not an ordinary offline error.
                                        webPaired = true // Always leave an explicit revoke action available after failure.
                                        val error = if (ex is VirtualDisplayPreviewLifecycle.PersistenceCleanupException)
                                            "WEB_PREVIEW_REVOKE_FAILED" else "WEB_PREVIEW_OPEN_FAILED"
                                        result = JSONObject().put("ok", false).put("error", error)
                                    } finally { setWorking(false) }
                                }
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.vd_preview_open),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        enabled = webPaired && !working,
                        onClick = {
                            if (!working) {
                                TouchHaptics.click(view)
                                setWorking(true)
                                scope.launch {
                                    try {
                                        withContext(NonCancellable + Dispatchers.IO) { VirtualDisplayWebPreview.revoke(context) }
                                        webPaired = false
                                        result = null
                                    } catch (ex: CancellationException) {
                                        throw ex
                                    } catch (_: Exception) {
                                        result = JSONObject().put("ok", false).put("error", "WEB_PREVIEW_REVOKE_FAILED")
                                    } finally { setWorking(false) }
                                }
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.vd_preview_revoke),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        enabled = installed == true && !working,
                        onClick = { TouchHaptics.click(view); refresh() },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.vd_recovery_refresh),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        enabled = installed == true && !working && snapshot != null &&
                            snapshot.optBoolean("ok") && snapshot.optBoolean("present") &&
                            snapshot.optBoolean("recoverable"),
                        onClick = {
                            if (!working) {
                                TouchHaptics.click(view)
                                setWorking(true)
                                result = null
                                scope.launch {
                                    try {
                                        val receipt = withContext(Dispatchers.IO) { recover(context.applicationContext) }
                                        result = receipt
                                        state = withContext(Dispatchers.IO) { readStatus(context.applicationContext) }
                                    } catch (ex: CancellationException) {
                                        throw ex
                                    } catch (_: Exception) {
                                        result = JSONObject().put("ok", false).put("error", "RECOVERY_OPERATION_FAILED")
                                    } finally { setWorking(false) }
                                }
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(if (working) R.string.vd_recovery_working else R.string.vd_recovery_action),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private const val RECOVERY_PHASE_PENDING = "recovery_pending"

/** 只有"等待恢复"是已确认可读的状态码；其余码显示为状态未知，原始码留在次级文字里。 */
private fun recoveryStatusRes(readable: Boolean, busy: Boolean, present: Boolean, phase: String): Int = when {
    !readable -> R.string.vd_recovery_unknown
    busy -> R.string.vd_recovery_busy
    // 恢复完成后记录已清空：phase 缺省值不是“等待恢复”。
    !present -> R.string.vd_recovery_empty
    phase == RECOVERY_PHASE_PENDING -> R.string.agent_task_preference_recovery_phase_waiting
    else -> R.string.agent_task_preference_recovery_phase_unknown
}
