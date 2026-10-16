package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMount
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.runOneShotShell
import io.github.mangi.eta.agent.terminal.shellQuote
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaNoticeText
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow

/**
 * 共享文件夹管理：把 Android 目录配置为 Linux 环境 /workspace/mounts/ 下的挂载点。
 * 配置即全部状态；挂载在每个 Linux 会话建立时按当前配置生效，页面只负责增删与源目录可用性提示。
 */
@Composable
internal fun SharedFoldersScreen(
    context: Context,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val shellSupervisor = remember { ShellProcessSupervisor() }
    DisposableEffect(Unit) {
        onDispose { shellSupervisor.beginClosing() }
    }
    val rootfsPaths = remember(context.applicationContext) {
        listOf(
            AlpineEnvironmentPaths.rootfsDir(context.applicationContext).absolutePath,
            LinuxEnvironmentPaths.rootfsDir(
                context.applicationContext,
                LinuxDistribution.DEBIAN,
            ).absolutePath,
        )
    }

    var mounts by remember { mutableStateOf(SharedFolderMounts.current()) }
    var sourceExists by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var showPicker by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<SharedFolderMount?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val publicAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Environment.isExternalStorageManager()) showPicker = true
        else notice = context.getString(R.string.capability_workspace_public_denied)
    }

    fun refreshSources(list: List<SharedFolderMount>) {
        if (list.isEmpty()) {
            sourceExists = emptyMap()
            return
        }
        coroutineScope.launch {
            sourceExists = withContext(Dispatchers.IO) { probeSources(shellSupervisor, list) }
        }
    }

    LaunchedEffect(Unit) { refreshSources(mounts) }

    EtaScaffoldPage(
        title = stringResource(R.string.shared_folders_title),
        onBack = onBack,
    ) {
        item(key = "mounts-card") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            ) {
                item(visible = mounts.isEmpty()) {
                    EtaClickableRow(
                        title = stringResource(R.string.shared_folders_empty),
                        summary = stringResource(R.string.shared_folders_entry_summary),
                        onClick = null,
                        showChevron = false,
                    )
                }
                mounts.forEach { mount ->
                    val missing = sourceExists[mount.sourcePath] == false
                    item {
                        EtaClickableRow(
                            title = mount.name,
                            summary = buildString {
                                append(mount.sourcePath)
                                append("\n")
                                append(
                                    context.getString(
                                        R.string.shared_folders_mount_point,
                                        "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}",
                                    )
                                )
                                if (missing) {
                                    append(" · ")
                                    append(context.getString(R.string.shared_folders_source_missing))
                                }
                            },
                            onClick = null,
                            showChevron = false,
                            endActions = {
                                EtaTextButton(
                                    text = stringResource(R.string.action_delete),
                                    onClick = { removeTarget = mount },
                                )
                            },
                        )
                    }
                }
                item {
                    EtaClickableRow(
                        title = stringResource(R.string.shared_folders_add),
                        onClick = {
                            if (TerminalRuntime.rootAvailable || Environment.isExternalStorageManager()) {
                                showPicker = true
                            } else {
                                try {
                                    publicAccessLauncher.launch(Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                    ))
                                } catch (_: android.content.ActivityNotFoundException) {
                                    notice = context.getString(R.string.capability_workspace_public_denied)
                                }
                            }
                        },
                    )
                }
            }
        }
        item(key = "footer-note") {
            EtaText(
                text = stringResource(R.string.shared_folders_footer),
                style = EtaTextStyle.Footnote,
                secondary = true,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        notice?.let { message ->
            item(key = "notice-card") {
                // 纯提示文字不带容器，直接排在页面背景上。
                EtaNoticeText(
                    title = message,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                )
            }
        }
    }

    if (showPicker) {
        SharedFolderPickerDialog(
            context = context,
            supervisor = shellSupervisor,
            existing = mounts,
            extraForbiddenRoots = rootfsPaths,
            onDismiss = { showPicker = false },
            onConfirm = { source, name ->
                val updated = mounts + SharedFolderMount(name = name, sourcePath = source)
                if (SharedFolderMounts.save(updated)) {
                    mounts = updated
                    refreshSources(updated)
                    showPicker = false
                } else {
                    notice = context.getString(R.string.shared_folders_error_save)
                }
            },
        )
    }

    removeTarget?.let { target ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.shared_folders_remove_title),
            summary = stringResource(
                R.string.shared_folders_remove_message,
                target.name,
                target.sourcePath,
            ),
            onDismissRequest = { removeTarget = null },
        ) {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { removeTarget = null },
                onConfirm = {
                    val updated = mounts.filterNot { it.name == target.name }
                    if (SharedFolderMounts.save(updated)) {
                        mounts = updated
                        removeTarget = null
                        refreshSources(updated)
                        coroutineScope.launch(Dispatchers.IO) {
                            // 清理空的挂载点目录；仍有会话占用时 rmdir 失败，无副作用。
                            runOneShotShell(
                                processSupervisor = shellSupervisor,
                                identity = TerminalRuntime.defaultIdentity(TerminalEnvironment.ANDROID),
                                command = "rmdir " +
                                    shellQuote("${SharedFolderMounts.ANDROID_MOUNTS_ROOT}/${target.name}") +
                                    " 2>/dev/null",
                                timeoutSeconds = 10,
                            )
                        }
                    } else {
                        notice = context.getString(R.string.shared_folders_error_save)
                        removeTarget = null
                    }
                },
            )
        }
    }
}

/** 批量探测源目录是否存在；name 只含安全字符，可直接拼进单引号。 */
private fun probeSources(
    supervisor: ShellProcessSupervisor,
    mounts: List<SharedFolderMount>,
): Map<String, Boolean> {
    val script = mounts.joinToString("\n") { mount ->
        "if [ -d ${shellQuote(mount.sourcePath)} ]; then echo '${mount.name} 1'; else echo '${mount.name} 0'; fi"
    }
    val result = runOneShotShell(
        processSupervisor = supervisor,
        identity = TerminalRuntime.defaultIdentity(TerminalEnvironment.ANDROID),
        command = script,
        timeoutSeconds = 15,
    )
    if (result.exitCode != 0) return emptyMap()
    return result.output.decodeToString().lineSequence().mapNotNull { line ->
        val parts = line.trim().split(" ")
        if (parts.size == 2) parts[0] to (parts[1] == "1") else null
    }.toMap()
}

/**
 * 目录选择弹层使用当前可用身份枚举目录；普通模式受 App 文件权限限制，
 * Root 模式可以访问系统目录。公共存储授权在进入弹层前按需申请。
 * 确认时挂载的是当前已列出内容的目录（path）；路径输入框只用于跳转。
 */
@Composable
private fun SharedFolderPickerDialog(
    context: Context,
    supervisor: ShellProcessSupervisor,
    existing: List<SharedFolderMount>,
    extraForbiddenRoots: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (sourcePath: String, name: String) -> Unit,
) {
    var path by remember { mutableStateOf("/sdcard") }
    var pathInput by remember { mutableStateOf("/sdcard") }
    var entries by remember { mutableStateOf<List<String>?>(null) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("sdcard") }
    var nameTouched by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    fun navigateTo(target: String) {
        val normalized = SharedFolderMounts.normalizeSourcePath(target) ?: "/"
        path = normalized
        pathInput = normalized
        browseError = null
        if (!nameTouched) nameInput = SharedFolderMounts.defaultName(normalized)
    }

    LaunchedEffect(path) {
        entries = null
        val result = withContext(Dispatchers.IO) {
            runOneShotShell(
                processSupervisor = supervisor,
                identity = TerminalRuntime.defaultIdentity(TerminalEnvironment.ANDROID),
                command = "cd ${shellQuote(path)} && find . -mindepth 1 -maxdepth 1 -type d",
                timeoutSeconds = 15,
            )
        }
        if (result.exitCode != 0) {
            browseError = context.getString(R.string.shared_folders_browse_unavailable)
            entries = emptyList()
        } else {
            browseError = null
            entries = result.output.decodeToString().lineSequence()
                .map { it.removePrefix("./") }
                .filter { it.isNotBlank() && !it.startsWith(".") }
                .sorted()
                .toList()
        }
    }

    EtaDialog(
        show = true,
        title = stringResource(R.string.shared_folders_picker_title),
        onDismissRequest = onDismiss,
    ) {
        Column {
            val pathLabel = stringResource(R.string.shared_folders_path_label)
            // 官方 OutlinedTextField + keyboardActions(onGo)。
            androidx.compose.material3.OutlinedTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { M3Text(pathLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigateTo(pathInput) }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
            browseError?.let { error ->
                EtaText(
                    text = error,
                    style = EtaTextStyle.Footnote,
                    color = terminalErrorColor(),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .padding(top = 8.dp),
            ) {
                if (path != "/") {
                    item(key = "..") {
                        PickerRow(
                            label = "../",
                            onClick = {
                                navigateTo(path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" })
                            },
                        )
                    }
                }
                items(entries.orEmpty(), key = { it }) { entry ->
                    PickerRow(
                        label = entry,
                        onClick = { navigateTo(path.trimEnd('/') + "/" + entry) },
                    )
                }
            }
            TerminalMonoText(
                text = androidx.compose.ui.text.AnnotatedString(
                    stringResource(R.string.shared_folders_selected_source, path),
                ),
                modifier = Modifier.padding(top = 8.dp),
            )
            val nameLabel = stringResource(R.string.shared_folders_name_label)
            androidx.compose.material3.OutlinedTextField(
                value = nameInput,
                onValueChange = {
                    nameInput = it
                    nameTouched = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                label = { M3Text(nameLabel) },
                singleLine = true,
            )
            formError?.let { error ->
                EtaText(
                    text = error,
                    style = EtaTextStyle.Footnote,
                    color = terminalErrorColor(),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            EtaDialogActions(
                confirmText = stringResource(R.string.shared_folders_add),
                onCancel = onDismiss,
                onConfirm = {
                    val sourceError = SharedFolderMounts.validateSource(path, existing, extraForbiddenRoots)
                    val errorText = when (sourceError) {
                        SharedFolderMounts.SourceError.INVALID_PATH ->
                            context.getString(R.string.shared_folders_error_path_invalid)
                        SharedFolderMounts.SourceError.FORBIDDEN_ROOT ->
                            context.getString(R.string.shared_folders_error_path_forbidden)
                        SharedFolderMounts.SourceError.DUPLICATE ->
                            context.getString(R.string.shared_folders_error_path_duplicate)
                        null -> when (SharedFolderMounts.validateName(nameInput, existing)) {
                            SharedFolderMounts.NameError.INVALID ->
                                context.getString(R.string.shared_folders_error_name_invalid)
                            SharedFolderMounts.NameError.DUPLICATE ->
                                context.getString(R.string.shared_folders_error_name_duplicate)
                            null -> if (existing.size >= SharedFolderMounts.MAX_MOUNTS) {
                                context.getString(
                                    R.string.shared_folders_error_limit,
                                    SharedFolderMounts.MAX_MOUNTS,
                                )
                            } else {
                                null
                            }
                        }
                    }
                    if (errorText != null) {
                        formError = errorText
                    } else {
                        onConfirm(path, nameInput.trim())
                    }
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** 目录选择行：KernelSU 式分段行。 */
@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    EtaSegmentedRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        content = {
            M3Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            )
        },
    )
}
