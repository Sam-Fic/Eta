package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AssistantsScreen(
    picker: Boolean,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    selectedAssistantId: String? = null,
    onSelectAssistant: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId ?: repositoryActiveId
    var searchQuery by remember { mutableStateOf("") }
    var actionProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }

    val filtered = remember(profiles, searchQuery) {
        val query = searchQuery.trim()
        profiles.filter { profile ->
            query.isBlank() || profile.name.contains(query, ignoreCase = true)
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.assistant_list_title),
        onBack = onBack,
        actions = {
            val onAdd = {
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        AssistantRepository.create()
                    }
                    onNavigate(AppRoute.AssistantEdit(created.id))
                }
            }
            if (isMaterial3Theme()) {
                M3IconButton(onClick = { onAdd() }) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.assistant_add),
                    )
                }
            } else {
                IconButton(onClick = { onAdd() }) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.assistant_add),
                    )
                }
            }
        },
    ) {
        item(key = "search") {
            AssistantSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.assistant_search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 8.dp),
            )
        }
        filtered.forEach { profile ->
            item(key = profile.id) {
                AssistantProfileCard(
                    profile = profile,
                    selected = profile.id == activeId,
                    onClick = {
                        if (picker) {
                            if (onSelectAssistant != null) {
                                onSelectAssistant(profile.id)
                                onBack()
                            } else {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        AssistantRepository.select(profile.id)
                                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                    }
                                    onBack()
                                }
                            }
                        } else {
                            onNavigate(AppRoute.AssistantEdit(profile.id))
                        }
                    },
                    onLongClick = { actionProfile = profile },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                if (isMaterial3Theme()) {
                    M3Text(
                        text = stringResource(R.string.assistant_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.assistant_empty),
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
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
                onNavigate(AppRoute.AssistantEdit(profile.id))
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
 * 助手搜索框：M3 用官方 [OutlinedTextField]（只加搜索图标，形状/配色走官方默认），
 * MIUI X 保持原有 [InputField]。
 */
@Composable
internal fun AssistantSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    if (isMaterial3Theme()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { M3Text(text = placeholder) },
            leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
        )
    } else {
        InputField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            label = placeholder,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
