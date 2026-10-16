package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxFileExplorer
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.components.ContainedMorphLoadingIndicator
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.haptics.TouchHaptics
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Linux 环境文件浏览：目录列举、预览、导出和删除都在 Linux 会话中完成，
 * 因此能看到 /workspace 等 bind 挂载里的真实文件。
 * 查看文件时进入屏内查看态，页面返回键先退回列表再退出页面。
 *
 * Material 3 Expressive 分支按设置页信息架构重排为分段列表：
 * - 「当前位置」分组承载等宽路径行（查看态时行尾挂文件操作按钮）；
 * - 「文件浏览」分组承载 `../`、目录条目、空态与加载态，条目操作走行尾「更多」菜单。
 *
 * MIUI X 主题族保持原有扁平结构不变：路径等宽文本 → 提示 → 条目，操作走长按 +
 * [FileEntryActionsDialog]。
 */
@Composable
internal fun LinuxFilesScreen(
    context: Context,
    distribution: String,
    onBack: () -> Unit,
) {
    val appContext = context.applicationContext
    val linuxDistribution = remember(distribution) {
        LinuxDistribution.entries.firstOrNull { it.wireName == distribution }
    }
    val rootfsDir = remember(linuxDistribution) {
        linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it) }
    }
    val installed = rootfsDir != null && LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)
    val scope = rememberCoroutineScope()

    val shellSupervisor = remember { ShellProcessSupervisor() }
    DisposableEffect(Unit) {
        onDispose { shellSupervisor.beginClosing() }
    }

    var currentPath by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<LinuxFileExplorer.Entry>?>(null) }
    var listError by remember { mutableStateOf<Int?>(null) }
    var listRevision by remember { mutableIntStateOf(0) }
    var openFilePath by remember { mutableStateOf<String?>(null) }
    var fileResult by remember { mutableStateOf<LinuxFileExplorer.ReadResult?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingActions by remember { mutableStateOf<FileEntryActions?>(null) }
    var pendingDelete by remember { mutableStateOf<LinuxPendingDelete?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        val dir = rootfsDir
        val dist = linuxDistribution
        if (uri == null || source == null || dir == null || dist == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val ok = withContext(Dispatchers.IO) {
                    exportLinuxFile(appContext, shellSupervisor, dir, dist, source, uri)
                }
                notice = appContext.getString(
                    if (ok) R.string.linux_files_exported else R.string.linux_files_export_failed,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = appContext.getString(R.string.linux_files_export_failed)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(currentPath, linuxDistribution, listRevision) {
        val dir = rootfsDir ?: return@LaunchedEffect
        val dist = linuxDistribution ?: return@LaunchedEffect
        if (!installed) return@LaunchedEffect
        entries = null
        listError = null
        val result = withContext(Dispatchers.IO) {
            LinuxFileExplorer.list(
                shellSupervisor,
                dir,
                currentPath,
                dist.terminalEnvironment,
                SharedFolderMounts.current(),
            )
        }
        when (result) {
            is LinuxFileExplorer.ListResult.Success -> entries = result.entries
            LinuxFileExplorer.ListResult.NotDirectory ->
                listError = R.string.linux_files_error_not_directory
            LinuxFileExplorer.ListResult.Unreadable,
            LinuxFileExplorer.ListResult.CommandFailed,
            LinuxFileExplorer.ListResult.NotInstalled ->
                listError = R.string.linux_files_error_unreadable
        }
    }

    LaunchedEffect(openFilePath) {
        val path = openFilePath ?: return@LaunchedEffect
        val dir = rootfsDir ?: return@LaunchedEffect
        val dist = linuxDistribution ?: return@LaunchedEffect
        fileResult = null
        fileResult = withContext(Dispatchers.IO) {
            LinuxFileExplorer.readText(
                shellSupervisor,
                dir,
                path,
                dist.terminalEnvironment,
                SharedFolderMounts.current(),
            )
        }
    }

    fun closeFile() {
        openFilePath = null
        fileResult = null
    }

    fun requestExport(path: String) {
        pendingExport = path
        exportLauncher.launch(path.substringAfterLast('/').ifBlank { "file" })
    }

    fun deleteItem(target: LinuxPendingDelete) {
        val dir = rootfsDir ?: return
        val dist = linuxDistribution ?: return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    LinuxFileExplorer.delete(
                        shellSupervisor,
                        dir,
                        target.path,
                        dist.terminalEnvironment,
                        SharedFolderMounts.current(),
                    )
                }
                notice = appContext.getString(
                    when (result) {
                        LinuxFileExplorer.DeleteResult.Success -> R.string.linux_files_deleted
                        LinuxFileExplorer.DeleteResult.Protected -> R.string.linux_files_delete_protected
                        else -> R.string.linux_files_delete_failed
                    },
                )
                if (result == LinuxFileExplorer.DeleteResult.Success) {
                    if (openFilePath == target.path) closeFile()
                    listRevision++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = appContext.getString(R.string.linux_files_delete_failed)
            } finally {
                busy = false
                pendingDelete = null
            }
        }
    }

    val viewerBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = viewerBackState,
        isBackEnabled = openFilePath != null,
        onBackCompleted = { closeFile() },
    )

    val onExport: (String) -> Unit = { path -> requestExport(path) }
    val onDelete: (String, String, Boolean) -> Unit = { path, name, directory ->
        pendingDelete = LinuxPendingDelete(path = path, name = name, directory = directory)
    }
    val onOpenFile: (String) -> Unit = { path -> openFilePath = path }
    val onOpenDirectory: (String) -> Unit = { path -> currentPath = path }
    val onNavigateUp: () -> Unit = { currentPath = parentLinuxPath(currentPath) }
    // EtaScaffoldPage's content lambda is a LazyListScope receiver, not a composable
    // context, so a @Composable theme read has to be hoisted out of it.
    val material = isMaterial3Theme()

    EtaScaffoldPage(
        title = stringResource(R.string.linux_files_title),
        onBack = { if (openFilePath != null) closeFile() else onBack() },
        actions = {
            // 页面级操作收进顶栏：浏览目录时可直接重新列举当前目录。
            IconButton(
                onClick = { listRevision++ },
                enabled = installed && !busy && openFilePath == null,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.page_refresh_38108e),
                )
            }
        },
    ) {
        if (material) {
            materialLinuxFileItems(
                context = appContext,
                distributionMissing = linuxDistribution == null,
                installed = installed,
                viewingPath = openFilePath,
                currentPath = currentPath,
                entries = entries,
                listError = listError,
                fileResult = fileResult,
                notice = notice,
                busy = busy,
                onExport = onExport,
                onDelete = onDelete,
                onOpenFile = onOpenFile,
                onOpenDirectory = onOpenDirectory,
                onNavigateUp = onNavigateUp,
            )
        } else {
            miuixLinuxFileItems(
                distributionMissing = linuxDistribution == null,
                installed = installed,
                viewingPath = openFilePath,
                currentPath = currentPath,
                entries = entries,
                listError = listError,
                fileResult = fileResult,
                notice = notice,
                busy = busy,
                onExport = onExport,
                onDelete = onDelete,
                onOpenFile = onOpenFile,
                onOpenDirectory = onOpenDirectory,
                onNavigateUp = onNavigateUp,
                onLongPress = { pendingActions = it },
            )
        }
    }

    // MIUI X 主题没有从按下位置弹出的表达性菜单，沿用对话框承载同一组操作。
    if (!isMaterial3Theme()) {
        pendingActions?.let { target ->
            FileEntryActionsDialog(
                target = target,
                enabled = !busy,
                onExport = {
                    pendingActions = null
                    requestExport(target.path)
                },
                onDelete = {
                    pendingActions = null
                    pendingDelete = LinuxPendingDelete(
                        path = target.path,
                        name = target.name,
                        directory = target.directory,
                    )
                },
                onDismiss = { pendingActions = null },
            )
        }
    }

    pendingDelete?.let { target ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.linux_files_delete_title),
            summary = stringResource(
                if (target.directory) R.string.linux_files_delete_dir_message
                else R.string.linux_files_delete_message,
                target.name,
            ),
            onDismissRequest = { if (!busy) pendingDelete = null },
        ) {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                confirmEnabled = !busy,
                onCancel = { if (!busy) pendingDelete = null },
                onConfirm = { if (!busy) deleteItem(target) },
            )
        }
    }
}

/**
 * Material 3 Expressive 内容：分段列表。
 *
 * - 「当前位置」无标题分组：等宽路径行；查看态时行尾挂 [EtaFileActionsButton]。
 * - 「文件浏览」分组：`../`、目录条目、空态 / 加载态统一收进 [EtaSegmentedItemContainer]。
 *
 * 分组统一走 [EtaSegmentedColumn]：16dp 页边距 + 13dp 组间距，与「工作区与文件」页同构。
 */
private fun LazyListScope.materialLinuxFileItems(
    context: Context,
    distributionMissing: Boolean,
    installed: Boolean,
    viewingPath: String?,
    currentPath: String,
    entries: List<LinuxFileExplorer.Entry>?,
    listError: Int?,
    fileResult: LinuxFileExplorer.ReadResult?,
    notice: String?,
    busy: Boolean,
    onExport: (String) -> Unit,
    onDelete: (String, String, Boolean) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val groupModifier = Modifier.padding(
        start = EtaGroupHorizontalPadding,
        end = EtaGroupHorizontalPadding,
        bottom = EtaSegmentedGroupSpacing,
    )

    if (distributionMissing || !installed) {
        item(key = "m3-state") {
            EtaSegmentedColumn(modifier = groupModifier) {
                item {
                    EtaSegmentedItemContainer {
                        MaterialStateMessage(
                            message = if (distributionMissing) {
                                stringResource(R.string.linux_files_invalid_distribution)
                            } else {
                                stringResource(R.string.linux_files_not_installed)
                            },
                        )
                    }
                }
            }
        }
        return
    }

    if (viewingPath != null) {
        val viewing = viewingPath
        item(key = "m3-viewer-path") {
            EtaSegmentedColumn(modifier = groupModifier) {
                item {
                    EtaSegmentedRow(
                        leadingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        content = { PathMonoText(viewing) },
                        trailingContent = {
                            EtaFileActionsButton(
                                canExport = true,
                                canDelete = !LinuxFileExplorer.isProtectedPath(viewing),
                                enabled = !busy,
                                onExport = { onExport(viewing) },
                                onDelete = {
                                    onDelete(
                                        viewing,
                                        viewing.substringAfterLast('/').ifBlank { viewing },
                                        false,
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }
        notice?.let { text ->
            item(key = "m3-viewer-notice") { MaterialGroupHint(text) }
        }
        item(key = "m3-viewer-content") {
            EtaSegmentedColumn(modifier = groupModifier) {
                item {
                    EtaSegmentedItemContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            when (val result = fileResult) {
                                is LinuxFileExplorer.ReadResult.Text -> {
                                    if (result.truncated) {
                                        EtaText(
                                            text = stringResource(R.string.linux_files_truncated_hint),
                                            style = EtaTextStyle.Footnote,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                    }
                                    SelectionContainer {
                                        TerminalMonoText(text = AnnotatedString(result.content))
                                    }
                                }
                                LinuxFileExplorer.ReadResult.Binary -> EtaText(
                                    text = stringResource(R.string.linux_files_binary_hint),
                                    style = EtaTextStyle.Body,
                                    secondary = true,
                                )
                                LinuxFileExplorer.ReadResult.NotFile -> EtaText(
                                    text = stringResource(R.string.linux_files_error_not_file),
                                    style = EtaTextStyle.Body,
                                    secondary = true,
                                )
                                LinuxFileExplorer.ReadResult.Unreadable,
                                LinuxFileExplorer.ReadResult.CommandFailed,
                                LinuxFileExplorer.ReadResult.NotInstalled -> EtaText(
                                    text = stringResource(R.string.linux_files_error_unreadable),
                                    style = EtaTextStyle.Body,
                                    secondary = true,
                                )
                                null -> Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ContainedMorphLoadingIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // 当前位置：等宽路径行。
    item(key = "m3-path") {
        EtaSegmentedColumn(modifier = groupModifier) {
            item {
                EtaSegmentedRow(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    content = { PathMonoText(currentPath) },
                )
            }
        }
    }
    notice?.let { text ->
        item(key = "m3-list-notice") { MaterialGroupHint(text) }
    }

    // 文件浏览：`../` + 目录条目 + 空态 / 加载态。
    item(key = "m3-files") {
        EtaSegmentedColumn(
            modifier = groupModifier,
            title = stringResource(R.string.linux_files_entry_title),
        ) {
            if (currentPath != "/") {
                item(key = "m3-parent") {
                    EtaSegmentedRow(
                        onClick = onNavigateUp,
                        enabled = !busy,
                        leadingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.DriveFileMove,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        content = {
                            M3Text(
                                text = "../",
                                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            )
                        },
                        supportingContent = {
                            M3Text(
                                text = stringResource(R.string.capability_workspace_parent),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
            val currentEntries = entries
            when {
                listError != null -> item(key = "m3-list-error") {
                    EtaSegmentedItemContainer {
                        MaterialStateMessage(stringResource(listError ?: R.string.linux_files_error_unreadable))
                    }
                }
                currentEntries == null -> item(key = "m3-list-loading") {
                    EtaSegmentedItemContainer {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ContainedMorphLoadingIndicator()
                        }
                    }
                }
                currentEntries.isEmpty() -> item(key = "m3-list-empty") {
                    EtaSegmentedItemContainer {
                        ListEmptyState(title = stringResource(R.string.linux_files_empty))
                    }
                }
                else -> currentEntries.forEach { entry ->
                    val target = joinLinuxPath(currentPath, entry.name)
                    item(key = "m3-entry-" + entry.name) {
                        FileRow(
                            name = entry.name,
                            isDir = entry.isDir,
                            summary = if (entry.isDir) {
                                null
                            } else {
                                Formatter.formatShortFileSize(context, entry.sizeBytes)
                            },
                            enabled = !busy,
                            onClick = {
                                if (entry.isDir) onOpenDirectory(target) else onOpenFile(target)
                            },
                            trailingActions = {
                                EtaFileActionsButton(
                                    canExport = !entry.isDir,
                                    canDelete = !LinuxFileExplorer.isProtectedPath(target),
                                    enabled = !busy,
                                    onExport = { onExport(target) },
                                    onDelete = { onDelete(target, entry.name, entry.isDir) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** MIUI X 内容：保持原有扁平信息架构（路径 → 提示 → 条目）不变。 */
private fun LazyListScope.miuixLinuxFileItems(
    distributionMissing: Boolean,
    installed: Boolean,
    viewingPath: String?,
    currentPath: String,
    entries: List<LinuxFileExplorer.Entry>?,
    listError: Int?,
    fileResult: LinuxFileExplorer.ReadResult?,
    notice: String?,
    busy: Boolean,
    onExport: (String) -> Unit,
    onDelete: (String, String, Boolean) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onLongPress: (FileEntryActions) -> Unit,
) {
    when {
        distributionMissing -> {
            item(key = "invalid-distribution") {
                StateMessage(stringResource(R.string.linux_files_invalid_distribution))
            }
        }
        !installed -> {
            item(key = "not-installed") {
                StateMessage(stringResource(R.string.linux_files_not_installed))
            }
        }
        viewingPath != null -> {
            val viewing = viewingPath
            item(key = "viewer-path") {
                PathBar(
                    path = viewing,
                    // MIUI X 没有行尾「更多」按钮，保留长按 → 对话框。
                    onLongClick = if (busy) {
                        null
                    } else {
                        {
                            onLongPress(
                                FileEntryActions(
                                    path = viewing,
                                    name = viewing.substringAfterLast('/').ifBlank { viewing },
                                    directory = false,
                                    canExport = true,
                                    canDelete = !LinuxFileExplorer.isProtectedPath(viewing),
                                ),
                            )
                        }
                    },
                )
            }
            notice?.let { text ->
                item(key = "viewer-notice") { HintText(text) }
            }
            when (val result = fileResult) {
                is LinuxFileExplorer.ReadResult.Text -> {
                    if (result.truncated) {
                        item(key = "viewer-truncated") {
                            HintText(stringResource(R.string.linux_files_truncated_hint))
                        }
                    }
                    item(key = "viewer-content") {
                        // SelectionContainer 是官方 foundation 手势组件，M3/MIUIX 通用。
                        SelectionContainer {
                            TerminalMonoText(
                                text = AnnotatedString(result.content),
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                    }
                }
                LinuxFileExplorer.ReadResult.Binary -> {
                    item(key = "viewer-binary") {
                        StateMessage(stringResource(R.string.linux_files_binary_hint))
                    }
                }
                LinuxFileExplorer.ReadResult.NotFile -> {
                    item(key = "viewer-not-file") {
                        StateMessage(stringResource(R.string.linux_files_error_not_file))
                    }
                }
                LinuxFileExplorer.ReadResult.Unreadable,
                LinuxFileExplorer.ReadResult.CommandFailed,
                LinuxFileExplorer.ReadResult.NotInstalled -> {
                    item(key = "viewer-error") {
                        StateMessage(stringResource(R.string.linux_files_error_unreadable))
                    }
                }
                null -> Unit
            }
        }
        else -> {
            item(key = "path-bar") {
                PathBar(currentPath)
            }
            notice?.let { text ->
                item(key = "list-notice") { HintText(text) }
            }
            if (currentPath != "/") {
                item(key = "..") {
                    FileRow(
                        name = "../",
                        isDir = true,
                        summary = null,
                        onClick = onNavigateUp,
                    )
                }
            }
            val currentEntries = entries
            when {
                listError != null -> {
                    item(key = "list-error") {
                        StateMessage(stringResource(listError ?: R.string.linux_files_error_unreadable))
                    }
                }
                currentEntries != null && currentEntries.isEmpty() -> {
                    item(key = "list-empty") {
                        StateMessage(stringResource(R.string.linux_files_empty))
                    }
                }
                currentEntries != null -> {
                    itemsIndexed(currentEntries, key = { _, entry -> entry.name }) { index, entry ->
                        val target = joinLinuxPath(currentPath, entry.name)
                        EtaSegmentedItem(index = index, count = currentEntries.size) {
                            val canExport = !entry.isDir
                            val canDelete = !LinuxFileExplorer.isProtectedPath(target)
                            FileRow(
                                name = entry.name,
                                isDir = entry.isDir,
                                summary = if (entry.isDir) {
                                    null
                                } else {
                                    Formatter.formatShortFileSize(
                                        androidx.compose.ui.platform.LocalContext.current,
                                        entry.sizeBytes,
                                    )
                                },
                                enabled = !busy,
                                // MIUI X 没有行尾「更多」按钮，保留长按 → 对话框。
                                onLongClick = {
                                    val actions = FileEntryActions(
                                        path = target,
                                        name = entry.name,
                                        directory = entry.isDir,
                                        canExport = canExport,
                                        canDelete = canDelete,
                                    )
                                    if (actions.canExport || actions.canDelete) onLongPress(actions)
                                },
                                onClick = {
                                    if (entry.isDir) {
                                        onOpenDirectory(target)
                                    } else {
                                        onOpenFile(target)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class LinuxPendingDelete(
    val path: String,
    val name: String,
    val directory: Boolean,
)

private fun joinLinuxPath(parent: String, name: String): String =
    if (parent == "/") "/$name" else parent.trimEnd('/') + "/" + name

private fun parentLinuxPath(path: String): String =
    path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }

private fun exportLinuxFile(
    context: Context,
    supervisor: ShellProcessSupervisor,
    rootfsDir: File,
    distribution: LinuxDistribution,
    linuxPath: String,
    destination: Uri,
): Boolean {
    val tmp = File(context.cacheDir, "linux-export-${System.nanoTime()}")
    return try {
        val copied = LinuxFileExplorer.copyToHostFile(
            supervisor = supervisor,
            rootfsDir = rootfsDir,
            linuxPath = linuxPath,
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

/** MIUI X 路径栏：等宽字体行，操作走长按 → 对话框。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PathBar(
    path: String,
    onLongClick: (() -> Unit)? = null,
) {
    Text(
        text = path,
        style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp)
            .then(
                if (onLongClick == null) Modifier
                else Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
            ),
    )
}

/** Material 3 路径等宽文本。 */
@Composable
private fun PathMonoText(path: String) {
    M3Text(
        text = path,
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 分段容器内的说明 / 状态文本（Material 3）。 */
@Composable
private fun MaterialStateMessage(message: String) {
    EtaText(
        text = message,
        style = EtaTextStyle.Body,
        secondary = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    )
}

/** 分组外提示文本（Material 3）：与分组容器同一套 16dp 边距 + 13dp 间距。 */
@Composable
private fun MaterialGroupHint(message: String) {
    EtaText(
        text = message,
        style = EtaTextStyle.Footnote,
        secondary = true,
        modifier = Modifier.padding(
            start = EtaGroupHorizontalPadding,
            end = EtaGroupHorizontalPadding,
            bottom = EtaSegmentedGroupSpacing,
        ),
    )
}

/** MIUI X 状态文本。 */
@Composable
private fun StateMessage(message: String) {
    EtaText(
        text = message,
        style = EtaTextStyle.Body,
        secondary = true,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

/** MIUI X 提示文本。 */
@Composable
internal fun HintText(message: String) {
    EtaText(
        text = message,
        style = EtaTextStyle.Footnote,
        secondary = true,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    name: String,
    isDir: Boolean,
    summary: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    trailingActions: (@Composable () -> Unit)? = null,
) {
    EtaFileRow(
        title = name,
        summary = summary,
        leadingIcon = if (isDir) {
            Icons.Rounded.Folder
        } else {
            Icons.AutoMirrored.Rounded.InsertDriveFile
        },
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
        trailingActions = trailingActions,
    )
}

/**
 * 文件/目录列表行。
 *
 * M3：官方 `SegmentedListItem` —— 涟漪、长按、语义与内边距全部来自官方默认值；
 * 行尾可挂 [trailingActions]（如「更多」按钮），子节点的点击由自身消费，不影响整行点击。
 * MIUI X：沿用 miuix `BasicComponent` + `combinedClickable`，行为不变。
 *
 * @param trailingActions 行尾附加内容（仅 Material 3 生效）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EtaFileRow(
    title: String,
    summary: String?,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailingActions: (@Composable () -> Unit)? = null,
) {
    val view = LocalView.current
    val supporting: (@Composable () -> Unit)? = summary?.let {
        {
            M3Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (isMaterial3Theme()) {
        val leading: @Composable () -> Unit = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // KernelSU 式分段行：形状/容器色取自外层分段组，独立使用时退化为单条目形状。
        EtaSegmentedRow(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            leadingContent = leading,
            trailingContent = trailingActions,
            content = {
                M3Text(text = title, style = MaterialTheme.typography.bodyLarge)
            },
            supportingContent = supporting,
        )
        return
    }
    // MIUI X 分支的整行点击不经过 EtaSegmentedRow，需自行补一次轻触反馈；长按沿用系统内建。
    val hapticClick: (() -> Unit)? = onClick?.let { click -> { TouchHaptics.click(view); click() } }
    BasicComponent(
        modifier = modifier.combinedClickable(
            enabled = enabled,
            onClick = hapticClick ?: {},
            onLongClick = onLongClick,
        ),
        title = title,
        summary = summary,
        enabled = enabled,
        startAction = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
    )
}
