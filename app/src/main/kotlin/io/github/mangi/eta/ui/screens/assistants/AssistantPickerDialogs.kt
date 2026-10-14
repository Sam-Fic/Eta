package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.LocalEtaSegmentedShapes
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.components.AssistantAvatar
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text as M3Text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantPickerDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    selectedAssistantId: String? = null,
) {
    val scope = rememberCoroutineScope()
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId ?: repositoryActiveId
    var searchQuery by remember { mutableStateOf("") }
    var actionProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    val dialogBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
        .coerceAtLeast(280.dp)

    LaunchedEffect(show) {
        if (!show) {
            searchQuery = ""
            actionProfile = null
            deleteProfile = null
        }
    }

    val filtered = remember(profiles, searchQuery) {
        val query = searchQuery.trim()
        profiles.filter { profile ->
            query.isBlank() || profile.name.contains(query, ignoreCase = true)
        }
    }

    if (isMaterial3Theme()) {
        // Material 3：官方 ModalBottomSheet（布局复刻 KernelSU MultiSelectDialog：
        // 标题行 + 关闭按钮、搜索框、可滚动分段列表；仅 Hidden↔Expanded 两态，不进半展开）。
        if (show) {
            ModalBottomSheet(
                onDismissRequest = {
                    if (actionProfile == null && deleteProfile == null) onDismiss()
                },
                sheetState = rememberBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        M3Text(
                            text = stringResource(R.string.assistant_list_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_close),
                            )
                        }
                    }
                    AssistantSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(R.string.assistant_search),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // 助手合并为一个 KernelSU 式分段组，行左滑露出「复制/编辑/删除」。
                        EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                            filtered.forEach { profile ->
                                item(key = profile.id) {
                                    AssistantSwipeRow(
                                        profile = profile,
                                        selected = profile.id == activeId,
                                        onOpen = {
                                            onSelect(profile.id)
                                            onDismiss()
                                        },
                                        onCopy = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    AssistantRepository.duplicate(profile.id)
                                                }
                                            }
                                        },
                                        onEdit = {
                                            onDismiss()
                                            onEdit(profile.id)
                                        },
                                        onDelete = { deleteProfile = profile },
                                    )
                                }
                            }
                        }
                        if (filtered.isEmpty()) {
                            M3Text(
                                text = stringResource(R.string.assistant_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    } else {
        EtaDialog(
            show = show,
            title = stringResource(R.string.assistant_list_title),
            onDismissRequest = {
                if (actionProfile == null && deleteProfile == null) onDismiss()
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dialogBodyHeight),
            ) {
                AssistantSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.assistant_search),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 12.dp)
                        .verticalScroll(rememberScrollState()),
                    // MIUI X 仍是 8dp 卡片间距。
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filtered.forEach { profile ->
                        AssistantProfileCard(
                            profile = profile,
                            selected = profile.id == activeId,
                            onClick = {
                                onSelect(profile.id)
                                onDismiss()
                            },
                            onLongClick = { actionProfile = profile },
                        )
                    }
                    if (filtered.isEmpty()) {
                        M3Text(
                            text = stringResource(R.string.assistant_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }

    actionProfile?.let { profile ->
        AssistantActionsDialog(
            profile = profile,
            canDelete = profiles.size > 1,
            onCopy = {
                actionProfile = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AssistantRepository.duplicate(profile.id)
                    }
                }
            },
            onEdit = {
                actionProfile = null
                onDismiss()
                onEdit(profile.id)
            },
            onDelete = {
                actionProfile = null
                deleteProfile = profile
            },
            onDismiss = { actionProfile = null },
        )
    }

    deleteProfile?.let { profile ->
        AssistantDeleteDialog(
            profile = profile,
            canDelete = profiles.size > 1,
            onDismiss = { deleteProfile = null },
            onConfirm = {
                val target = profile
                deleteProfile = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AssistantRepository.delete(target.id)
                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    }
                }
            },
        )
    }
}

/**
 * MIUI X 助手条目：[Card] + `combinedClickable`（长按弹操作对话框）。
 *
 * Material 3 不再走这里，改用 KernelSU 式分段行 + 左滑操作条的 [AssistantSwipeRow]。
 */
@Composable
internal fun AssistantProfileCard(
    profile: AssistantProfile,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    hapticFeedbackEnabled = false,
                    onClick = {
                        TouchHaptics.click(view)
                        onClick()
                    },
                    onLongClick = {
                        TouchHaptics.longPress(view)
                        onLongClick()
                    },
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantAvatar(assistant = profile, size = 32.dp)
            Text(
                text = profile.name,
                style = MiuixTheme.textStyles.title3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
            )
            if (selected) {
                Text(
                    text = stringResource(R.string.assistant_current),
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Material 3 助手行：KernelSU 式分段行 + 官方 [SwipeToDismissBox]。
 *
 * 左滑露出「复制 / 编辑 / 删除」三个操作按钮：滑动过程露出，越过阈值（总距离的 50%）后
 * 停留在露出状态；点击露出区域的空白处或执行复制 / 编辑后自动收起。危险操作（删除）用
 * `errorContainer` 底色区分，中性操作（复制 / 编辑）沿用分段行同级的 `surfaceContainerHighest`。
 *
 * 只在 Material 3 分支调用；MIUI X 继续使用长按弹窗的 [AssistantProfileCard]。
 */
@Composable
internal fun AssistantSwipeRow(
    profile: AssistantProfile,
    selected: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.5f },
    )
    SwipeToDismissBox(
        state = dismissState,
        // 只允许向左滑动露出操作；右滑（或点击空白）用于收起。
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            // 背景圆角与分段行一致，避免未滑动时四角露出底色。
            val shape = LocalEtaSegmentedShapes.current?.shape ?: MaterialTheme.shapes.large
            Surface(
                onClick = { scope.launch { dismissState.reset() } },
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = shape,
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        onCopy()
                        scope.launch { dismissState.reset() }
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.ui_copy_4edd1d),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        onEdit()
                        scope.launch { dismissState.reset() }
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.ui_edit_a7f814),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 危险操作（删除）用 errorContainer 底色与 onErrorContainer 图标区分。
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.errorContainer),
                    ) {
                        IconButton(onClick = { onDelete() }) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier,
    ) {
        EtaSegmentedRow(
            selected = selected,
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            leadingContent = { AssistantAvatar(assistant = profile, size = 32.dp) },
            content = {
                M3Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                if (selected) {
                    M3Text(
                        text = stringResource(R.string.assistant_current),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            },
        )
    }
    // 越过阈值停住时给一次触觉反馈（与 ManageChatsScreen 同款）。
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            TouchHaptics.gestureThreshold(view)
        }
    }
}

@Composable
internal fun AssistantActionsDialog(
    profile: AssistantProfile,
    canDelete: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    EtaDialog(
        show = true,
        title = profile.name,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isMaterial3Theme()) {
                // 官方按钮：形状、行高、配色全部使用官方默认值。
                OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    M3Text(text = stringResource(R.string.ui_copy_4edd1d))
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    M3Text(text = stringResource(R.string.ui_edit_a7f814))
                }
                Button(
                    onClick = onDelete,
                    enabled = canDelete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = M3ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    M3Text(text = stringResource(R.string.action_delete))
                }
            } else {
                TextButton(
                    text = stringResource(R.string.ui_copy_4edd1d),
                    onClick = onCopy,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = stringResource(R.string.ui_edit_a7f814),
                    onClick = onEdit,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(R.string.action_delete),
                    onClick = onDelete,
                    enabled = canDelete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(
                        color = MiuixTheme.colorScheme.error,
                        textColor = MiuixTheme.colorScheme.onError,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun AssistantDeleteDialog(
    profile: AssistantProfile,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    EtaDialog(
        show = true,
        title = stringResource(R.string.assistant_delete_title),
        summary = stringResource(R.string.assistant_delete_body, profile.name),
        onDismissRequest = onDismiss,
    ) {
        EtaDialogActions(
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            confirmEnabled = canDelete,
            onCancel = onDismiss,
            onConfirm = onConfirm,
        )
    }
}
