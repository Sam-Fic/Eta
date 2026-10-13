package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.text.format.Formatter
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.EtaBackupExportOptions
import io.github.mangi.eta.data.repository.EtaBackupRepository
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import io.github.mangi.eta.ui.components.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text

@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (OutputStream, EtaBackupExportOptions) -> EtaBackupSummary,
    onImport: suspend (InputStream) -> EtaBackupSummary,
) {
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val busy = exporting || importing
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var includeLinuxEnvironment by remember { mutableStateOf(false) }
    var linuxBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        linuxBytes = withContext(Dispatchers.IO) {
            EtaBackupRepository.linuxEnvironmentBytes(context)
        }
    }
    val linuxSizeLabel = linuxBytes?.takeIf { it > 0L }?.let { Formatter.formatShortFileSize(context, it) }

    fun showFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        Toast.makeText(
            context,
            throwable.message ?: context.getString(R.string.data_backup_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exporting = true
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                val summary = output.use { onExport(it, EtaBackupExportOptions(includeLinuxEnvironment = includeLinuxEnvironment)) }
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.data_backup_exported,
                        summary.conversationCount,
                        summary.providerCount,
                        summary.assistantCount,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                showFailure(throwable)
            } finally {
                exporting = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.data_backup_title),
        onBack = onBack,
    ) {
        item(key = "warning") {
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                BackupNoticeRow(
                    title = stringResource(R.string.data_backup_warning_title),
                    summary = stringResource(R.string.data_backup_warning_summary),
                )
            }
        }
        item(key = "actions-title") {
            EtaSectionTitle(text = stringResource(R.string.data_backup_actions))
        }
        item(key = "actions-card") {
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                EtaSwitchRow(
                    title = stringResource(R.string.data_backup_include_linux),
                    summary = buildString {
                        append(stringResource(R.string.data_backup_include_linux_summary))
                        linuxSizeLabel?.let { size ->
                            append('\n')
                            append(context.getString(R.string.data_backup_linux_size, size))
                        }
                    },
                    checked = includeLinuxEnvironment,
                    onCheckedChange = { includeLinuxEnvironment = it },
                    enabled = !busy,
                )
                BackupActionRow(
                    title = stringResource(R.string.data_backup_export),
                    summary = if (exporting) {
                        stringResource(R.string.data_backup_working)
                    } else {
                        stringResource(R.string.data_backup_export_summary)
                    },
                    icon = Icons.Rounded.Download,
                    loading = exporting,
                    enabled = !busy,
                    onClick = {
                        exportLauncher.launch(defaultBackupFileName())
                    },
                )
                BackupActionRow(
                    title = stringResource(R.string.data_backup_import),
                    summary = if (importing) {
                        stringResource(R.string.data_backup_working)
                    } else {
                        stringResource(R.string.data_backup_import_summary)
                    },
                    icon = Icons.Rounded.Description,
                    loading = importing,
                    enabled = !busy,
                    onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/json", "text/plain", "*/*"))
                    },
                )
            }
        }
    }

    if (showImportDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.data_backup_import_confirm_title),
            summary = stringResource(R.string.data_backup_import_confirm_summary) + "\n如果选择单会话归档，则导入为独立新会话，不覆盖已有会话或模型配置。",
            onDismissRequest = {
                if (!busy) {
                    showImportDialog = false
                    pendingImportUri = null
                }
            },
        ) {
            EtaDialogActions(
                confirmText = if (busy) {
                    stringResource(R.string.data_backup_working)
                } else {
                    stringResource(R.string.action_import)
                },
                destructive = true,
                cancelEnabled = !busy,
                confirmEnabled = !busy,
                onCancel = {
                    showImportDialog = false
                    pendingImportUri = null
                },
                onConfirm = {
                    val uri = pendingImportUri ?: return@EtaDialogActions
                    showImportDialog = false
                    scope.launch {
                        importing = true
                        try {
                            val input = context.contentResolver.openInputStream(uri)
                                ?: error(context.getString(R.string.data_backup_file_open_failed))
                            val summary = input.use { onImport(it) }
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.data_backup_imported,
                                    summary.conversationCount,
                                    summary.providerCount,
                                    summary.assistantCount,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (throwable: Throwable) {
                            showFailure(throwable)
                        } finally {
                            pendingImportUri = null
                            importing = false
                        }
                    }
                },
            )
        }
    }
}

/** 纯提示行：M3 用官方 [ListItem]，MIUI X 保持原有 [BasicComponent]。 */
@Composable
private fun BackupNoticeRow(title: String, summary: String) {
    if (isMaterial3Theme()) {
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            content = { M3Text(text = title, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = {
                M3Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    } else {
        BasicComponent(
            title = title,
            summary = summary,
        )
    }
}

/**
 * 备份/导入入口行。
 *
 * Material 3 走官方 [ListItem]（onClick 重载自带涟漪与语义），加载态用官方
 * [CircularProgressIndicator]；MIUI X 保持原有 [ArrowPreference] + [InfiniteProgressIndicator]。
 */
@Composable
private fun BackupActionRow(
    title: String,
    summary: String,
    icon: ImageVector,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (isMaterial3Theme()) {
        ListItem(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            leadingContent = {
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    Icon(imageVector = icon, contentDescription = null)
                }
            },
            content = { M3Text(text = title, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = {
                M3Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    } else {
        ArrowPreference(
            title = title,
            summary = summary,
            enabled = enabled,
            startAction = {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        InfiniteProgressIndicator(size = 20.dp)
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                }
            },
            onClick = onClick,
        )
    }
}

private fun defaultBackupFileName(): String =
    "代鱼-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}-备份.zip"
