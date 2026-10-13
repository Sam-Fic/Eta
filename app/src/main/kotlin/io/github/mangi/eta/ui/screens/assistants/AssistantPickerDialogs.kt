package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text as M3Text

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
 * 助手条目。
 *
 * Material 3 走官方 [ListItem]（selected 重载自带选中容器色与按压形变，同时支持
 * `onLongClick`）；MIUI X 保持原有 [Card] + `combinedClickable`。
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
    if (isMaterial3Theme()) {
        ListItem(
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
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
    } else {
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
