package io.github.mangi.eta.ui.screens.terminal

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxFileExplorer
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.app.WorkspaceEntry
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import io.github.mangi.eta.ui.app.guestWorkspacePath
import io.github.mangi.eta.ui.components.ListEmptyState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「工作区与文件」页。
 *
 * - 页面级操作（导入文件、公共目录访问授权）收进顶栏「更多」菜单，
 *   写法与 KernelSU 顶栏 `IconButton + DropdownMenuPopup` 就地弹出一致；
 * - 路径以等宽字体分段行呈现当前所在的 /workspace 位置；
 * - 文件操作收进行尾「更多」按钮的 Material 3 Expressive 菜单（导出 / 删除），
 *   MIUI X 主题下退回长按 + [FileEntryActionsDialog]。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember(appContext) { WorkspaceFileStore(context) }
    val scope = rememberCoroutineScope()
    val distribution by LinuxEnvironmentSettingsRepository.selectedFlow(appContext).collectAsState(
        initial = LinuxEnvironmentSettingsRepository.current(appContext),
    )
    val backendFlow = remember(appContext, distribution) {
        LinuxEnvironmentSettingsRepository.backendFlow(appContext, distribution)
    }
    val backend by backendFlow.collectAsState(
        initial = LinuxEnvironmentSettingsRepository.backend(appContext, distribution),
    )
    val rootfsDir = remember(distribution, backend) {
        LinuxEnvironmentPaths.rootfsDir(appContext, distribution, backend)
    }
    val linuxReady = LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)
    val shellSupervisor = remember { ShellProcessSupervisor() }
    DisposableEffect(Unit) {
        onDispose { shellSupervisor.beginClosing() }
    }
    var path by rememberSaveable { mutableStateOf("") }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingActions by remember { mutableStateOf<FileEntryActions?>(null) }
    var pendingDelete by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var publicAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        publicAccess = Environment.isExternalStorageManager()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            try {
                var succeeded = 0
                uris.forEach { if (store.importFile(it)) succeeded++ }
                message = if (succeeded == uris.size) context.getString(R.string.capability_workspace_imported)
                else context.getString(R.string.capability_workspace_partial_import, succeeded)
                if (succeeded > 0) path = "imports"
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.capability_workspace_failed)
            } finally {
                busy = false
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri != null && source != null) scope.launch {
            busy = true
            try {
                val exported = if (linuxReady) {
                    withContext(Dispatchers.IO) {
                        exportWorkspaceLinuxFile(
                            context = appContext,
                            supervisor = shellSupervisor,
                            rootfsDir = rootfsDir,
                            distribution = distribution,
                            relativePath = source,
                            destination = uri,
                        )
                    }
                } else {
                    false
                }
                if (!exported) store.exportFile(source, uri)
                message = context.getString(R.string.capability_workspace_exported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.capability_workspace_failed)
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(path, revision, linuxReady, distribution, backend) {
        try {
            entries = if (linuxReady) {
                val result = withContext(Dispatchers.IO) {
                    LinuxFileExplorer.list(
                        shellSupervisor,
                        rootfsDir,
                        guestWorkspacePath(path),
                        distribution.terminalEnvironment,
                        SharedFolderMounts.current(),
                    )
                }
                when (result) {
                    is LinuxFileExplorer.ListResult.Success -> result.entries.map { entry ->
                        WorkspaceEntry(
                            path = if (path.isBlank()) entry.name else path.trimEnd('/') + '/' + entry.name,
                            name = entry.name,
                            directory = entry.isDir,
                            size = entry.sizeBytes,
                        )
                    }
                    else -> store.list(path)
                }
            } else {
                store.list(path)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            entries = emptyList()
            message = context.getString(R.string.capability_workspace_failed)
        }
    }

    fun requestExport(sourcePath: String, name: String) {
        pendingExport = sourcePath
        exportLauncher.launch(name)
    }

    fun requestDelete(sourcePath: String, name: String, directory: Boolean) {
        pendingDelete = WorkspaceEntry(
            path = sourcePath,
            name = name,
            directory = directory,
            size = 0L,
        )
    }

    EtaScaffoldPage(
        title = stringResource(R.string.capability_workspace),
        onBack = onBack,
        actions = {
            var menuExpanded by remember { mutableStateOf(false) }
            IconButton(
                onClick = { menuExpanded = true },
                enabled = !busy,
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.action_more),
                )
                // KernelSU 顶栏写法：菜单挂在按钮内容里，由 DropdownMenuPopup 自动锚定到按钮。
                DropdownMenuPopup(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(index = 0, count = 1)) {
                        DropdownMenuItem(
                            onClick = {
                                menuExpanded = false
                                importLauncher.launch(arrayOf("*/*"))
                            },
                            text = { M3Text(text = stringResource(R.string.capability_workspace_import)) },
                            shape = MenuDefaults.itemShape(index = 0, count = 2).shape,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.FileUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                )
                            },
                        )
                        DropdownMenuItem(
                            onClick = {
                                menuExpanded = false
                                try {
                                    accessLauncher.launch(
                                        Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                } catch (_: android.content.ActivityNotFoundException) {
                                    message = context.getString(R.string.capability_workspace_failed)
                                }
                            },
                            text = { M3Text(text = stringResource(R.string.capability_workspace_public)) },
                            shape = MenuDefaults.itemShape(index = 1, count = 2).shape,
                            leadingIcon = {
                                Icon(
                                    // 状态用图标表达：已授权为解锁，未授权为加锁；内容色同步强调已授权。
                                    imageVector = if (publicAccess) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                    tint = if (publicAccess) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        LocalContentColor.current
                                    },
                                )
                            },
                        )
                    }
                }
            }
        },
    ) {
        item(key = "workspace-info") {
            EtaClickableRow(
                title = stringResource(R.string.capability_workspace_private),
                summary = stringResource(R.string.capability_workspace_private_summary),
                onClick = null,
                showChevron = false,
                leading = {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                contentPadding = PaddingValues(horizontal = 12.dp),
            )
        }
        item(key = "path") {
            WorkspacePathBar(path)
        }
        message?.let { text ->
            item(key = "message") { HintText(text) }
        }
        if (path.isNotBlank()) {
            item(key = "parent") {
                EtaFileRow(
                    title = "../",
                    summary = null,
                    leadingIcon = Icons.Rounded.Folder,
                    enabled = !busy,
                    onClick = {
                        pendingActions = null
                        path = path.substringBeforeLast('/', "")
                    },
                )
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.capability_workspace_empty),
                    summary = stringResource(R.string.capability_workspace_empty_summary),
                )
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.path }) { index, entry ->
            EtaSegmentedItem(index = index, count = entries.size) {
                val trailing: (@Composable () -> Unit)? = if (isMaterial3Theme()) {
                    {
                        EtaFileActionsButton(
                            canExport = !entry.directory,
                            canDelete = true,
                            enabled = !busy,
                            onExport = { requestExport(entry.path, entry.name) },
                            onDelete = { requestDelete(entry.path, entry.name, entry.directory) },
                        )
                    }
                } else {
                    null
                }
                EtaFileRow(
                    title = entry.name,
                    summary = if (entry.directory) {
                        stringResource(R.string.capability_workspace_directory)
                    } else {
                        Formatter.formatShortFileSize(context, entry.size)
                    },
                    leadingIcon = if (entry.directory) {
                        Icons.Rounded.Folder
                    } else {
                        Icons.AutoMirrored.Rounded.InsertDriveFile
                    },
                    enabled = !busy,
                    onClick = {
                        if (entry.directory) {
                            pendingActions = null
                            path = entry.path
                        }
                    },
                    // MIUI X 没有行尾「更多」按钮，保留长按 → 对话框。
                    onLongClick = if (isMaterial3Theme()) null else ({
                        pendingActions = FileEntryActions(
                            path = entry.path,
                            name = entry.name,
                            directory = entry.directory,
                            canExport = !entry.directory,
                            canDelete = true,
                        )
                    }),
                    trailingActions = trailing,
                )
            }
        }
    }

    // MIUI X 主题没有行尾「更多」表达性菜单，沿用长按 + 对话框承载同一组操作。
    if (!isMaterial3Theme()) {
        pendingActions?.let { target ->
            FileEntryActionsDialog(
                target = target,
                enabled = !busy,
                onExport = {
                    pendingActions = null
                    requestExport(target.path, target.name)
                },
                onDelete = {
                    pendingActions = null
                    requestDelete(target.path, target.name, target.directory)
                },
                onDismiss = { pendingActions = null },
            )
        }
    }

    pendingDelete?.let { target ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.capability_workspace_delete_title),
            summary = stringResource(
                if (target.directory) R.string.capability_workspace_delete_dir_message
                else R.string.capability_workspace_delete_message,
                target.name,
            ),
            onDismissRequest = { if (!busy) pendingDelete = null },
        ) {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                confirmEnabled = !busy,
                onCancel = { if (!busy) pendingDelete = null },
                onConfirm = {
                    if (busy) return@EtaDialogActions
                    val deleting = target
                    pendingDelete = null
                    scope.launch {
                        busy = true
                        try {
                            val deleted = if (linuxReady) {
                                val result = withContext(Dispatchers.IO) {
                                    LinuxFileExplorer.delete(
                                        shellSupervisor,
                                        rootfsDir,
                                        guestWorkspacePath(deleting.path),
                                        distribution.terminalEnvironment,
                                        SharedFolderMounts.current(),
                                    )
                                }
                                if (result == LinuxFileExplorer.DeleteResult.Success) true
                                else {
                                    store.delete(deleting.path)
                                    true
                                }
                            } else {
                                store.delete(deleting.path)
                                true
                            }
                            message = context.getString(
                                if (deleted) R.string.capability_workspace_deleted
                                else R.string.capability_workspace_failed,
                            )
                            revision++
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            message = context.getString(R.string.capability_workspace_failed)
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

/**
 * 工作区当前位置：等宽字体分段行，显示映射到 Linux 环境的 `/workspace` 路径。
 *
 * M3 走分段行；MIUI X 沿用 miuix 文本，与「Linux 文件」页的路径栏保持同构。
 */
@Composable
private fun WorkspacePathBar(path: String) {
    val display = if (path.isBlank()) "/workspace" else "/workspace/${path.trim('/')}"
    if (isMaterial3Theme()) {
        EtaSegmentedRow(
            modifier = Modifier.padding(horizontal = 12.dp),
            content = {
                M3Text(
                    text = display,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        return
    }
    Text(
        text = display,
        style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp),
    )
}

private fun exportWorkspaceLinuxFile(
    context: android.content.Context,
    supervisor: ShellProcessSupervisor,
    rootfsDir: File,
    distribution: io.github.mangi.eta.agent.terminal.LinuxDistribution,
    relativePath: String,
    destination: Uri,
): Boolean {
    val tmp = File(context.cacheDir, "workspace-export-${System.nanoTime()}")
    return try {
        val copied = LinuxFileExplorer.copyToHostFile(
            supervisor = supervisor,
            rootfsDir = rootfsDir,
            linuxPath = guestWorkspacePath(relativePath),
            environment = distribution.terminalEnvironment,
            destination = tmp,
            sharedMounts = SharedFolderMounts.current(),
        )
        if (copied != LinuxFileExplorer.CopyResult.Success || !tmp.isFile) return false
        tmp.inputStream().use { input ->
            val output = context.contentResolver.openOutputStream(destination, "wt") ?: return false
            output.use { input.copyTo(it) }
        }
        true
    } finally {
        tmp.delete()
    }
}
