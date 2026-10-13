package io.github.mangi.eta.ui.screens.terminal
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.app.DaemonTaskUi
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 守护任务列表面板；终端块视图与控制台视图共用。 */
@Composable
internal fun DaemonTasksDialog(
    tasks: List<DaemonTaskUi>,
    onDismiss: () -> Unit,
    onStop: (String) -> Unit,
    onLoadLogs: suspend (String) -> String,
) {
    EtaDialog(
        show = true,
        title = stringResource(R.string.terminal_daemon_tasks),
        onDismissRequest = onDismiss,
    ) {
        if (tasks.isEmpty()) {
            TerminalBodyText(
                text = stringResource(R.string.terminal_daemon_empty),
                color = terminalMutedForeground(),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(items = tasks, key = { it.id }) { task ->
                    DaemonTaskRow(task = task, onStop = onStop, onLoadLogs = onLoadLogs)
                }
            }
        }
    }
}

@Composable
private fun DaemonTaskRow(
    task: DaemonTaskUi,
    onStop: (String) -> Unit,
    onLoadLogs: suspend (String) -> String,
) {
    val view = LocalView.current
    var logsExpanded by remember(task.id) { mutableStateOf(false) }
    var logs by remember(task.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun toggleLogs() {
        TouchHaptics.click(view)
        logsExpanded = !logsExpanded
        if (logsExpanded && logs == null) {
            scope.launch { logs = onLoadLogs(task.id) }
        }
    }

    if (isMaterial3Theme()) {
        // 官方 ListItem：点按行展开日志，自带涟漪、语义与按压形变；停止动作用官方图标按钮。
        ListItem(
            onClick = ::toggleLogs,
            modifier = Modifier.fillMaxWidth(),
            content = {
                M3Text(
                    text = task.command,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                M3Text(
                    text = daemonMeta(task),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (task.running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            trailingContent = {
                TerminalIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.terminal_stop),
                    onClick = { onStop(task.id) },
                )
            },
        )
        DaemonLogsPanel(logsExpanded = logsExpanded, logs = logs)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = task.command,
            style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = daemonMeta(task),
                style = MiuixTheme.textStyles.footnote2,
                color = if (task.running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(
                    if (logsExpanded) R.string.terminal_daemon_hide_logs else R.string.terminal_daemon_view_logs
                ),
                minHeight = 28.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                textStyle = MiuixTheme.textStyles.body2,
                onClick = ::toggleLogs,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            TextButton(
                text = stringResource(R.string.terminal_stop),
                onClick = {
                    TouchHaptics.click(view)
                    onStop(task.id)
                },
                minHeight = 28.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                textStyle = MiuixTheme.textStyles.body2,
                colors = ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error,
                    textColor = MiuixTheme.colorScheme.onError,
                ),
            )
        }
        DaemonLogsPanel(logsExpanded = logsExpanded, logs = logs)
    }
}

/**
 * 日志面板。M3 走官方 `Card`（容器 `surfaceContainerHighest`、形状 `shapes.medium` 均取默认值），
 * MIUI X 保持原有 surfaceVariant 底色。
 */
@Composable
private fun DaemonLogsPanel(logsExpanded: Boolean, logs: String?) {
    if (!logsExpanded) return
    val content: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            if (logs == null) {
                TerminalBusyIndicator(size = 14.dp)
            } else {
                TerminalMonoText(text = AnnotatedString(logs))
            }
        }
    }
    if (isMaterial3Theme()) {
        Card(modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surfaceVariant),
        ) {
            content()
        }
    }
}

@Composable
private fun daemonMeta(task: DaemonTaskUi): String {
    val environmentLabel = when (task.environment) {
        TerminalEnvironment.ANDROID -> "Android"
        TerminalEnvironment.ALPINE -> "Alpine"
        TerminalEnvironment.DEBIAN -> "Debian"
    }
    val stateLabel = stringResource(
        if (task.running) R.string.terminal_daemon_running else R.string.terminal_daemon_exited
    )
    return "$environmentLabel · ${task.identity} · $stateLabel"
}
