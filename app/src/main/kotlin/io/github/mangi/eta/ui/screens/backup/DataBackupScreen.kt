package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.text.format.Formatter
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.EtaBackupExportOptions
import io.github.mangi.eta.data.repository.EtaBackupRepository
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.EtaModelBackupSummary
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow

@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (OutputStream, EtaBackupExportOptions) -> EtaBackupSummary,
    onImport: suspend (InputStream) -> EtaBackupSummary,
    onExportModels: suspend (OutputStream) -> EtaModelBackupSummary,
    onImportModels: suspend (InputStream) -> EtaModelBackupSummary,
) {
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var exportingModels by remember { mutableStateOf(false) }
    var importingModels by remember { mutableStateOf(false) }
    val busy = exporting || importing || exportingModels || importingModels
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingModelImportUri by remember { mutableStateOf<Uri?>(null) }
    var showModelImportDialog by remember { mutableStateOf(false) }
    var includeLinuxEnvironment by remember { mutableStateOf(false) }
    var linuxBytes by remember { mutableStateOf<Long?>(null) }
    var includeEmbeddings by remember { mutableStateOf(true) }
    var mediaBytes by remember { mutableStateOf<Long?>(null) }
    var includeModels by remember { mutableStateOf(false) }
    var modelSummary by remember { mutableStateOf(EtaModelBackupSummary.EMPTY) }
    LaunchedEffect(Unit) {
        linuxBytes = withContext(Dispatchers.IO) {
            EtaBackupRepository.linuxEnvironmentBytes(context)
        }
        mediaBytes = withContext(Dispatchers.IO) {
            EtaBackupRepository.mediaIndexEstimateBytes(context)
        }
        modelSummary = EtaBackupRepository.downloadedModels(context)
    }
    val linuxSizeLabel = linuxBytes?.takeIf { it > 0L }?.let { Formatter.formatShortFileSize(context, it) }
    val mediaSizeLabel = mediaBytes?.takeIf { it > 0L }?.let { Formatter.formatShortFileSize(context, it) }
    val modelSizeLabel = modelSummary.bytes.takeIf { modelSummary.modelCount > 0 }
        ?.let { Formatter.formatShortFileSize(context, it) }

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
                val summary = output.use {
                    onExport(
                        it,
                        EtaBackupExportOptions(
                            includeLinuxEnvironment = includeLinuxEnvironment,
                            includeEmbeddings = includeEmbeddings,
                            includeModels = includeModels,
                        ),
                    )
                }
                Toast.makeText(
                    context,
                    backupMessage(
                        context,
                        context.getString(
                            R.string.data_backup_exported,
                            summary.conversationCount,
                            summary.providerCount,
                            summary.assistantCount,
                        ),
                        R.string.data_backup_exported_models,
                        summary.downloadedModelCount,
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

    val modelExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exportingModels = true
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                val summary = output.use { onExportModels(it) }
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.backup_models_exported,
                        summary.modelCount,
                        Formatter.formatShortFileSize(context, summary.bytes),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                showFailure(throwable)
            } finally {
                exportingModels = false
            }
        }
    }

    val modelImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingModelImportUri = uri
            showModelImportDialog = true
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.data_backup_title),
        onBack = onBack,
    ) {
        item(key = "warning") {
            // 纯描述性文字不套分段卡片：直接排在页面背景上，
            // 容器底色只留给下方可交互的分组，视觉层级更清楚。
            BackupNoticeRow(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.data_backup_warning_title),
                summary = stringResource(R.string.data_backup_warning_summary),
            )
        }
        item(key = "actions-card") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.data_backup_actions),
            ) {
                item {
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
                }
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.backup_media_include),
                        summary = buildString {
                            append(stringResource(R.string.backup_media_include_summary))
                            mediaSizeLabel?.let { size ->
                                append('\n')
                                append(context.getString(R.string.backup_media_size, size))
                            }
                        },
                        checked = includeEmbeddings,
                        onCheckedChange = { includeEmbeddings = it },
                        enabled = !busy,
                    )
                }
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.backup_models_include),
                        summary = buildString {
                            append(stringResource(R.string.backup_models_include_summary))
                            modelSizeLabel?.let { size ->
                                append('\n')
                                append(context.getString(R.string.backup_models_size, size))
                            }
                        },
                        checked = includeModels,
                        onCheckedChange = { includeModels = it },
                        enabled = !busy && modelSummary.modelCount > 0,
                    )
                }
                item {
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
                            exportLauncher.launch(defaultBackupFileName(context))
                        },
                    )
                }
                item {
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
        item(key = "models-card") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.backup_models_section),
            ) {
                item {
                    BackupActionRow(
                        title = stringResource(R.string.backup_models_export),
                        summary = when {
                            exportingModels -> stringResource(R.string.data_backup_working)
                            modelSummary.modelCount > 0 -> context.getString(
                                R.string.backup_models_downloaded,
                                modelSummary.modelCount,
                                Formatter.formatShortFileSize(context, modelSummary.bytes),
                            )
                            else -> stringResource(R.string.backup_models_none)
                        },
                        icon = Icons.Rounded.FileDownload,
                        loading = exportingModels,
                        enabled = !busy && modelSummary.modelCount > 0,
                        onClick = {
                            modelExportLauncher.launch(defaultModelBackupFileName(context))
                        },
                    )
                }
                item {
                    BackupActionRow(
                        title = stringResource(R.string.backup_models_import),
                        summary = if (importingModels) {
                            stringResource(R.string.data_backup_working)
                        } else {
                            stringResource(R.string.backup_models_import_summary)
                        },
                        icon = Icons.Rounded.FileUpload,
                        loading = importingModels,
                        enabled = !busy,
                        onClick = {
                            modelImportLauncher.launch(arrayOf("application/zip", "*/*"))
                        },
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.data_backup_import_confirm_title),
            summary = stringResource(R.string.data_backup_import_confirm_summary) + "\n" + stringResource(R.string.data_backup_single_conversation_note),
            onDismissRequest = {
                if (!busy) {
                    showImportDialog = false
                    pendingImportUri = null
                }
            },
            actions = {
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
                                    backupMessage(
                                        context,
                                        context.getString(
                                            R.string.data_backup_imported,
                                            summary.conversationCount,
                                            summary.providerCount,
                                            summary.assistantCount,
                                        ),
                                        R.string.data_backup_imported_models,
                                        summary.downloadedModelCount,
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
            },
        ) {
        }
    }

    if (showModelImportDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.backup_models_import_confirm_title),
            summary = stringResource(R.string.backup_models_import_confirm_summary),
            onDismissRequest = {
                if (!busy) {
                    showModelImportDialog = false
                    pendingModelImportUri = null
                }
            },
            actions = {
                EtaDialogActions(
                    confirmText = if (importingModels) {
                        stringResource(R.string.data_backup_working)
                    } else {
                        stringResource(R.string.backup_models_restore_action)
                    },
                    cancelEnabled = !busy,
                    confirmEnabled = !busy,
                    onCancel = {
                        showModelImportDialog = false
                        pendingModelImportUri = null
                    },
                    onConfirm = {
                        val uri = pendingModelImportUri ?: return@EtaDialogActions
                        showModelImportDialog = false
                        scope.launch {
                            importingModels = true
                            try {
                                val input = context.contentResolver.openInputStream(uri)
                                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                                val summary = input.use { onImportModels(it) }
                                modelSummary = EtaBackupRepository.downloadedModels(context)
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.backup_models_imported,
                                        summary.modelCount,
                                        Formatter.formatShortFileSize(context, summary.bytes),
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } catch (throwable: Throwable) {
                                showFailure(throwable)
                            } finally {
                                pendingModelImportUri = null
                                importingModels = false
                            }
                        }
                    },
                )
            },
        ) {
        }
    }
}

/** 纯提示行：直接以文字排在页面背景上（不带容器）。 */
@Composable
private fun BackupNoticeRow(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        M3Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        M3Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 备份/导入入口行。
 *
 * 走官方分段行 [EtaSegmentedRow]（自带涟漪与语义），加载态用官方 M3E
 * [LoadingIndicator]（morph 变形，尺寸收敛进图标槽）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BackupActionRow(
    title: String,
    summary: String,
    icon: ImageVector,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    EtaSegmentedRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        leadingContent = {
            if (loading) {
                LoadingIndicator(modifier = Modifier.size(24.dp))
            } else {
                Icon(imageVector = icon, contentDescription = null)
            }
        },
        content = { M3Text(text = title) },
        supportingContent = {
            M3Text(text = summary)
        },
    )
}

private fun defaultBackupFileName(context: Context): String =
    context.getString(R.string.data_backup_file_name, SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()))

private fun defaultModelBackupFileName(context: Context): String =
    context.getString(R.string.backup_models_file_name, SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()))

private fun backupMessage(context: Context, base: String, modelCountRes: Int, modelCount: Int): String =
    if (modelCount > 0) base + "\n" + context.getString(modelCountRes, modelCount) else base
