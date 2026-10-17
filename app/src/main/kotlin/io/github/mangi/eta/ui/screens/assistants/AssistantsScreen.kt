package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text

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
    var searchVisible by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }

    // 打开搜索时回到列表顶部并聚焦顶栏搜索框；关闭时清空过滤词。
    // 顶栏标题槽内容随状态切换，聚焦失败时稍候重试。
    LaunchedEffect(searchVisible) {
        if (!searchVisible) {
            searchQuery = ""
            return@LaunchedEffect
        }
        runCatching { listState.scrollToItem(0) }
        repeat(10) {
            if (runCatching { searchFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(32)
        }
    }

    val filtered = remember(profiles, searchQuery) {
        val query = searchQuery.trim()
        profiles.filter { profile ->
            query.isBlank() || profile.name.contains(query, ignoreCase = true)
        }
    }

    // 浏览态进编辑页；选择态（picker）选中助手后返回。
    val openProfile: (AssistantProfile) -> Unit = { profile ->
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
    }

    EtaScaffoldPage(
        title = stringResource(R.string.assistant_list_title),
        onBack = onBack,
        listState = listState,
        // 搜索态：搜索框直接落在标题栏位置（顶栏标题槽），不再占列表首项。
        titleContent = if (searchVisible) {
            {
                AssistantSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.assistant_search),
                    modifier = Modifier.focusRequester(searchFocus),
                )
            }
        } else {
            null
        },
        actions = {
            val onAdd = {
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        AssistantRepository.create()
                    }
                    onNavigate(AppRoute.AssistantEdit(created.id))
                }
            }
            val onToggleSearch = { searchVisible = !searchVisible }
            val searchLabel = stringResource(R.string.assistant_search)
            val closeLabel = stringResource(R.string.action_cancel)
            M3IconButton(onClick = { onAdd() }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.assistant_add),
                )
            }
            M3IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (searchVisible) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = if (searchVisible) closeLabel else searchLabel,
                )
            }
        },
    ) {
        // 所有助手合并为一个 KernelSU 式分段组，行尾三点菜单提供「复制/编辑/删除」。
        filtered.forEachIndexed { index, profile ->
            item(key = profile.id) {
                EtaSegmentedItem(index = index, count = filtered.size) {
                    AssistantRow(
                        profile = profile,
                        selected = profile.id == activeId,
                        onOpen = { openProfile(profile) },
                        onCopy = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    AssistantRepository.duplicate(profile.id)
                                }
                            }
                        },
                        onEdit = { onNavigate(AppRoute.AssistantEdit(profile.id)) },
                        onDelete = { deleteProfile = profile },
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = if (index == filtered.lastIndex) 13.dp else 2.dp,
                        ),
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                M3Text(
                    text = stringResource(R.string.assistant_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
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
 * 助手搜索框：官方 [OutlinedTextField]（只加搜索图标，形状/配色走官方默认）。
 */
@Composable
internal fun AssistantSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { M3Text(text = placeholder) },
        leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
    )
}
