package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
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
import io.github.mangi.eta.ui.components.AssistantAvatar
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
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
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }

    LaunchedEffect(show) {
        if (!show) {
            searchQuery = ""
            deleteProfile = null
        }
    }

    val filtered = remember(profiles, searchQuery) {
        val query = searchQuery.trim()
        profiles.filter { profile ->
            query.isBlank() || profile.name.contains(query, ignoreCase = true)
        }
    }

    // Material 3：官方 ModalBottomSheet（布局复刻 KernelSU MultiSelectDialog：
    // 标题行 + 关闭按钮、搜索框、可滚动分段列表；仅 Hidden↔Expanded 两态，不进半展开）。
    if (show) {
        ModalBottomSheet(
            onDismissRequest = {
                if (deleteProfile == null) onDismiss()
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
                    // 助手合并为一个 KernelSU 式分段组，行尾三点菜单提供「复制/编辑/删除」。
                    EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                        filtered.forEach { profile ->
                            item(key = profile.id) {
                                AssistantRow(
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
 * 助手条目：[Card] + `combinedClickable`（长按弹操作对话框）。
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
            M3Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
            )
            if (selected) {
                M3Text(
                    text = stringResource(R.string.assistant_current),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 助手行：KernelSU 式分段行 + 行尾官方三点菜单（与 Skills 页的技能行同一套交互）。
 *
 * 「复制 / 编辑 / 删除」收进 [DropdownMenu]，行本身只负责打开助手；删除仍需二次确认。
 */
@Composable
internal fun AssistantRow(
    profile: AssistantProfile,
    selected: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    EtaSegmentedRow(
        selected = selected,
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    M3Text(
                        text = stringResource(R.string.assistant_current),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreHoriz,
                            contentDescription = stringResource(R.string.assistant_more_named, profile.name),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { M3Text(text = stringResource(R.string.ui_copy_4edd1d)) },
                            onClick = {
                                menuExpanded = false
                                onCopy()
                            },
                        )
                        DropdownMenuItem(
                            text = { M3Text(text = stringResource(R.string.ui_edit_a7f814)) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { M3Text(text = stringResource(R.string.action_delete)) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
    )
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
