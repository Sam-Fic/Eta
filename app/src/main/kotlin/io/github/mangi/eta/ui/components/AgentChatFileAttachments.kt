package io.github.mangi.eta.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme

internal val ChatInputPopupMargin = 8.dp
/** 主页 / 侧边栏按钮统一档：官方 Small 图标按钮容器（40dp），尺寸只引用 IconButtonDefaults 令牌。 */
internal val ChatInputActionSize = IconButtonDefaults.smallContainerSize().height
/** MIUI X 分支既有图标尺寸；Material 3 分支一律走 `etaOfficialIconButtonIconSize` 官方令牌。 */
internal val ChatInputActionIconSize = 24.dp

/** 附件动作集：系统选择器与绝对路径输入的统一入口，由 [rememberAgentAttachmentActions] 创建。 */
internal class AgentAttachmentActions(
    val pickImage: () -> Unit,
    val pickVideo: () -> Unit,
    val pickFiles: () -> Unit,
    val pickFolder: () -> Unit,
    val inputPath: () -> Unit,
)

/**
 * 创建附件动作集并托管「输入绝对路径」弹窗（按主题族选用官方 `AlertDialog` / `EtaDialog`）。
 * 图片 / 视频选择成功后先持久化读取权限再回调；文件 / 文件夹 / 路径直接回调。
 */
@Composable
internal fun rememberAgentAttachmentActions(
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
): AgentAttachmentActions {
    val context = LocalContext.current
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAttachImage(uri.toString())
        }
    }
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAttachVideo(uri.toString())
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onAttachFiles(uris.map { it.toString() })
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) onAttachFolder(uri.toString())
    }

    val material3 = isMaterial3Theme()
    if (material3) {
        if (showPathDialog) {
            AlertDialog(
                onDismissRequest = { showPathDialog = false },
                title = { M3Text(text = stringResource(R.string.ui_input_file_path_36d474)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        M3Text(
                            text = stringResource(
                                R.string.ui_supports_files_and_folders_under_internal_storage_or_520786
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = pathInput,
                            onValueChange = { pathInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { M3Text(text = stringResource(R.string.ui_absolute_path_9ac6fc)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val path = pathInput.trim()
                            showPathDialog = false
                            onAttachFilePath(path)
                        },
                        enabled = pathInput.trim().startsWith('/'),
                    ) {
                        M3Text(text = stringResource(R.string.attachment_add))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showPathDialog = false }) {
                        M3Text(text = stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    } else {
        EtaDialog(
            show = showPathDialog,
            title = stringResource(R.string.ui_input_file_path_36d474),
            summary = stringResource(
                R.string.ui_supports_files_and_folders_under_internal_storage_or_520786
            ),
            onDismissRequest = { showPathDialog = false },
        ) {
            Column {
                TextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = stringResource(R.string.ui_absolute_path_9ac6fc),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaDialogActions(
                    confirmText = stringResource(R.string.attachment_add),
                    confirmEnabled = pathInput.trim().startsWith('/'),
                    onCancel = { showPathDialog = false },
                    onConfirm = {
                        val path = pathInput.trim()
                        showPathDialog = false
                        onAttachFilePath(path)
                    },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }

    return AgentAttachmentActions(
        pickImage = {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        pickVideo = {
            videoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        },
        pickFiles = { filePicker.launch(arrayOf("*/*")) },
        pickFolder = { folderPicker.launch(null) },
        inputPath = {
            pathInput = ""
            showPathDialog = true
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentAttachmentPickerButton(
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.Add,
    containerShape: androidx.compose.ui.graphics.Shape? = null,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    containerSize: androidx.compose.ui.unit.Dp = ChatInputActionSize,
) {
    val view = LocalView.current
    val menuState = rememberEtaMenuState()
    val keepIme = rememberKeepImeWhenOpeningMenu()
    val actions = rememberAgentAttachmentActions(
        onAttachImage = onAttachImage,
        onAttachVideo = onAttachVideo,
        onAttachFiles = onAttachFiles,
        onAttachFolder = onAttachFolder,
        onAttachFilePath = onAttachFilePath,
    )
    val material3 = isMaterial3Theme()
    val options = listOf(
        Triple(stringResource(R.string.attachment_image), Icons.Rounded.Image, 0),
        Triple(stringResource(R.string.attachment_video), Icons.Rounded.Videocam, 1),
        Triple(stringResource(R.string.attachment_file), Icons.Rounded.Description, 2),
        Triple(stringResource(R.string.attachment_folder), Icons.Rounded.FolderOpen, 3),
        Triple(stringResource(R.string.attachment_enter_path), Icons.Rounded.Link, 4),
    )
    fun runOption(index: Int) {
        TouchHaptics.click(view)
        menuState.dismiss()
        when (index) {
            0 -> actions.pickImage()
            1 -> actions.pickVideo()
            2 -> actions.pickFiles()
            3 -> actions.pickFolder()
            4 -> actions.inputPath()
        }
    }
    Box(modifier = modifier) {
        ChatInputNonFocusableIconButton(
            onClick = {
                keepIme()
                menuState.onAnchorClick()
            },
            contentDescription = stringResource(R.string.ui_add_attachment_dba9e8),
            containerShape = containerShape,
            containerColor = containerColor,
            containerSize = containerSize,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(
                    if (material3) {
                        // 官方图标按钮图标尺寸令牌。
                        etaOfficialIconButtonIconSize(containerSize)
                    } else {
                        ChatInputActionIconSize
                    },
                ),
                tint = if (material3) {
                    // 官方图标按钮已通过 LocalContentColor 下发内容色。
                    LocalContentColor.current
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
        }
        if (material3) {
            // Material 3 Expressive：官方 DropdownMenu + 官方 DropdownMenuItem，
            // 行高 / 内边距 / 前置图标尺寸 / 行形状全部取官方默认值。
            DropdownMenu(
                expanded = menuState.expanded,
                onDismissRequest = menuState::dismiss,
            ) {
                options.forEachIndexed { index, (label, optionIcon, action) ->
                    DropdownMenuItem(
                        text = { M3Text(text = label) },
                        leadingIcon = { Icon(imageVector = optionIcon, contentDescription = null) },
                        shape = etaOfficialMenuItemShape(index, options.size),
                        onClick = { runOption(action) },
                    )
                }
            }
        } else {
            EtaDropdownMenu(
                expanded = menuState.expanded,
                onDismissRequest = menuState::dismiss,
                preferAbove = true,
                minWidth = 0.dp,
                focusable = false,
            ) {
                options.forEach { (option, icon, index) ->
                    DropdownMenuItem(
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        text = { M3Text(option) },
                        leadingIcon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = { runOption(index) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PendingFileReferenceStrip(
    references: List<PendingFileReferenceUi>,
    onRemoveReference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        references.forEach { pending ->
            val reference = pending.reference
            Row(
                modifier = Modifier
                    .height(42.dp)
                    .widthIn(max = 250.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh,
                        cornerRadius = 12.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        cornerRadius = 12.dp,
                    )
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onRemoveReference(pending.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_file_reference_04bbfc),
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SentFileReferenceFlow(
    references: List<AgentFileReference>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        references.forEach { reference ->
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .widthIn(max = 280.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surface,
                        cornerRadius = 12.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.45f),
                        cornerRadius = 12.dp,
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal class InputPopupPositionProvider(
    private val inputContainerTopPx: Int,
    private val windowHorizontalInsetPx: Int? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
    ): IntOffset {
        val alignToEnd = when (alignment) {
            PopupPositionProvider.Align.End,
            PopupPositionProvider.Align.TopEnd,
            PopupPositionProvider.Align.BottomEnd,
            -> true

            else -> false
        }
        val physicalEnd = if (layoutDirection == LayoutDirection.Ltr) alignToEnd else !alignToEnd
        val requestedX = when {
            windowHorizontalInsetPx != null && physicalEnd ->
                windowBounds.right - popupContentSize.width - popupMargin.right - windowHorizontalInsetPx

            windowHorizontalInsetPx != null ->
                windowBounds.left + popupMargin.left + windowHorizontalInsetPx

            physicalEnd -> anchorBounds.right - popupContentSize.width - popupMargin.right
            else -> anchorBounds.left + popupMargin.left
        }
        val maxX = (windowBounds.right - popupContentSize.width - popupMargin.right)
            .coerceAtLeast(windowBounds.left)
        val requestedY = inputContainerTopPx - popupContentSize.height - popupMargin.bottom
        val maxY = windowBounds.bottom - popupContentSize.height - popupMargin.bottom
        val minY = (windowBounds.top + popupMargin.top).coerceAtMost(maxY)
        return IntOffset(
            x = requestedX.coerceIn(windowBounds.left, maxX),
            y = requestedY.coerceIn(minY, maxY),
        )
    }

    override fun getMargins(): PaddingValues = PaddingValues(vertical = ChatInputPopupMargin)
}
