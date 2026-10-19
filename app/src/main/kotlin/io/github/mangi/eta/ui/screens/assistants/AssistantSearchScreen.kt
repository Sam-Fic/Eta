package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaSearchHistory
import io.github.mangi.eta.ui.adaptive.EtaSearchPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 查询历史分域键：助手搜索历史独立存储。 */
private const val HISTORY_KEY = "assistants"

/**
 * 独立搜索页：从助手主页右上角的单搜索图标跳入，整页即搜索。
 *
 * 顶栏是「返回钮 + 透明底搜索框 + 清除按钮」（官方 [EtaSearchPage]），进入即自动聚焦
 * 弹键盘；列表与主页共用 [assistantItems]，按搜索词实时过滤。交互语义与主页一致：
 * 点行即选中该助手；编辑只收在行尾三点菜单里。
 */
@Composable
internal fun AssistantSearchScreen(
    picker: Boolean,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    selectedAssistantId: String? = null,
    onSelectAssistant: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId ?: repositoryActiveId
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }

    // 与主页 openProfile 同一套语义：点行即选中助手；编辑只收在行尾三点菜单里。
    val openProfile: (AssistantProfile) -> Unit = { profile ->
        if (onSelectAssistant != null) {
            onSelectAssistant(profile.id)
        } else {
            scope.launch {
                withContext(Dispatchers.IO) {
                    AssistantRepository.select(profile.id)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                }
            }
        }
        if (picker) onBack()
    }

    EtaSearchPage(
        searchPlaceholder = stringResource(R.string.assistant_search),
        onBack = onBack,
        historyKey = HISTORY_KEY,
    ) { query ->
        assistantItems(
            profiles = profiles,
            activeId = activeId,
            query = query,
            onOpen = { profile ->
                EtaSearchHistory.add(context, HISTORY_KEY, query)
                openProfile(profile)
            },
            onCopy = { profile ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AssistantRepository.duplicate(profile.id)
                    }
                }
            },
            onEdit = { profile -> onNavigate(AppRoute.AssistantEdit(profile.id)) },
            onDelete = { profile -> deleteProfile = profile },
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
