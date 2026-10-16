package io.github.mangi.eta.ui.pages.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.typeLabel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.material3.Text as M3Text
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox

/** 分组与页面边缘的统一边距：与 KernelSU 分段列表调用方一致（水平 16dp、组间 13dp）。 */
private val SectionEdgePadding = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp)

/** 分组标题（已含上下留白）+ 分段列表的组合外边距。 */
private val SectionGroupPadding = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 21.dp)

@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    currentProviderId: String? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val storedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val selectedProviderId = currentProviderId?.takeIf { it.isNotBlank() } ?: storedProviderId
    var searchQuery by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedProviderIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    val filteredProviders = remember(providers, searchQuery) {
        val query = searchQuery.trim()
        providers.filter { provider ->
            query.isBlank() ||
                provider.name.contains(query, ignoreCase = true) ||
                provider.baseUrl.contains(query, ignoreCase = true) ||
                provider.typeLabel.contains(query, ignoreCase = true)
        }
    }
    val selectableProviders = remember(filteredProviders) {
        filteredProviders.filterNot(ProviderSetting::isBuiltIn)
    }

    LaunchedEffect(selectionMode, selectedProviderIds, providers) {
        if (!selectionMode) return@LaunchedEffect
        val existingIds = providers.filterNot { it.isBuiltIn }.mapTo(mutableSetOf()) { it.id }
        val pruned = selectedProviderIds.filter { it in existingIds }.toSet()
        if (pruned != selectedProviderIds) selectedProviderIds = pruned
        if (existingIds.isEmpty()) {
            selectionMode = false
            selectedProviderIds = emptySet()
        }
    }

    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = {
            selectionMode = false
            selectedProviderIds = emptySet()
        },
    )

    // LazyListScope 的 content lambda 非组合上下文，主题判定需在外层解析后按值分支。
    val material3 = isMaterial3Theme()

    Box(modifier = Modifier.fillMaxSize()) {
        EtaScaffoldPage(
            title = stringResource(R.string.ui_model_provider_e8c7f5),
            onBack = {
                if (selectionMode) {
                    selectionMode = false
                    selectedProviderIds = emptySet()
                } else {
                    onBack()
                }
            },
        ) {
            if (material3) {
                materialProviderListContent(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    filteredProviders = filteredProviders,
                    selectableProviders = selectableProviders,
                    selectionMode = selectionMode,
                    selectedProviderIds = selectedProviderIds,
                    selectedProviderId = selectedProviderId,
                    onToggleChecked = { id ->
                        if (id in selectedProviderIds) {
                            selectedProviderIds - id
                        } else {
                            selectedProviderIds + id
                        }
                    },
                    onSelectAll = {
                        TouchHaptics.click(view)
                        selectedProviderIds = if (
                            selectableProviders.isNotEmpty() &&
                            selectedProviderIds.containsAll(selectableProviders.map { it.id })
                        ) {
                            emptySet()
                        } else {
                            selectableProviders.mapTo(mutableSetOf()) { it.id }
                        }
                    },
                    onOpen = { id ->
                        TouchHaptics.click(view)
                        onNavigate(AppRoute.ModelProviderDetail(id))
                    },
                    onEnterSelection = { id ->
                        selectionMode = true
                        selectedProviderIds = setOf(id)
                    },
                    onAddProvider = { type ->
                        TouchHaptics.click(view)
                        onNavigate(
                            when (type) {
                                NewProviderType.OpenAiCompatible -> {
                                    AppRoute.ModelProviderAuthMethod(NewProviderType.OpenAiCompatible)
                                }
                                else -> AppRoute.ModelProviderNew(type)
                            },
                        )
                    },
                )
            } else {
                miuixProviderListContent(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    filteredProviders = filteredProviders,
                    selectableProviders = selectableProviders,
                    selectionMode = selectionMode,
                    selectedProviderIds = selectedProviderIds,
                    selectedProviderId = selectedProviderId,
                    onToggleChecked = { id ->
                        if (id in selectedProviderIds) {
                            selectedProviderIds - id
                        } else {
                            selectedProviderIds + id
                        }
                    },
                    onSelectAll = {
                        TouchHaptics.click(view)
                        selectedProviderIds = if (
                            selectableProviders.isNotEmpty() &&
                            selectedProviderIds.containsAll(selectableProviders.map { it.id })
                        ) {
                            emptySet()
                        } else {
                            selectableProviders.mapTo(mutableSetOf()) { it.id }
                        }
                    },
                    onOpen = { id ->
                        TouchHaptics.click(view)
                        onNavigate(AppRoute.ModelProviderDetail(id))
                    },
                    onEnterSelection = { id ->
                        selectionMode = true
                        selectedProviderIds = setOf(id)
                    },
                    onAddProvider = { type ->
                        TouchHaptics.click(view)
                        onNavigate(
                            when (type) {
                                NewProviderType.OpenAiCompatible -> {
                                    AppRoute.ModelProviderAuthMethod(NewProviderType.OpenAiCompatible)
                                }
                                else -> AppRoute.ModelProviderNew(type)
                            },
                        )
                    },
                )
            }

            // 选择模式下预留底部操作条高度；其余场景用骨架自带的底距。
            if (selectionMode) {
                item(key = "selection_spacer") {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                            .navigationBarsPadding(),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            ProviderSelectionBar(
                selectedCount = selectedProviderIds.size,
                enabled = !isDeleting,
                onDelete = {
                    TouchHaptics.click(view)
                    showBatchDeleteDialog = true
                },
                onExit = {
                    TouchHaptics.click(view)
                    selectionMode = false
                    selectedProviderIds = emptySet()
                },
            )
        }
    }

    if (showBatchDeleteDialog) {
        val confirmLabel = if (isDeleting) {
            context.getString(R.string.page_deleting_6f941d)
        } else {
            context.getString(R.string.page_delete_3755f5)
        }
        val dialogTitle = stringResource(R.string.ui_remove_provider_9f848f)
        val dialogSummary = pluralStringResource(
            R.plurals.provider_batch_delete_summary,
            selectedProviderIds.size,
            selectedProviderIds.size,
        )
        val onDismissBatch = { if (!isDeleting) showBatchDeleteDialog = false }
        val onCancelBatch = { showBatchDeleteDialog = false }
        val onConfirmBatch: () -> Unit = {
            scope.launch {
                isDeleting = true
                try {
                    ProviderRepository.deleteProviders(selectedProviderIds)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    showBatchDeleteDialog = false
                    selectionMode = false
                    selectedProviderIds = emptySet()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    isDeleting = false
                }
            }
        }
        // 自适应弹窗：M3 → 官方 AlertDialog + TextButton；MIUI X → WindowDialog + MiuixDialogActions。
        EtaDialog(
            show = true,
            title = dialogTitle,
            summary = dialogSummary,
            onDismissRequest = onDismissBatch,
        ) {
            EtaDialogActions(
                confirmText = confirmLabel,
                cancelEnabled = !isDeleting,
                confirmEnabled = !isDeleting && selectedProviderIds.isNotEmpty(),
                destructive = true,
                onCancel = onCancelBatch,
                onConfirm = onConfirmBatch,
            )
        }
    }
}

/**
 * Material 3 列表内容：KernelSU 式分段列表布局 —— 搜索框、添加组标题+分段列、
 * 已配置标题行（含全选）+ 分段供应商行；空态为无容器的居中文本。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.materialProviderListContent(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filteredProviders: List<ProviderSetting>,
    selectableProviders: List<ProviderSetting>,
    selectionMode: Boolean,
    selectedProviderIds: Set<String>,
    selectedProviderId: String?,
    onToggleChecked: (String) -> Unit,
    onSelectAll: () -> Unit,
    onOpen: (String) -> Unit,
    onEnterSelection: (String) -> Unit,
    onAddProvider: (NewProviderType) -> Unit,
) {
    item(key = "search") {
        ProviderSearchField(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            placeholder = stringResource(R.string.ui_search_provider_74e049),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
    }

    item(key = "create_section") {
        EtaSegmentedColumn(
            modifier = SectionGroupPadding,
            title = stringResource(R.string.ui_add_new_provider_74df54),
        ) {
            item {
                ProviderClickableRow(
                    title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                    summary = stringResource(R.string.ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02),
                    leading = { ProviderBrandIcon(ProviderSourceTypes.OPENAI) },
                    onClick = { onAddProvider(NewProviderType.OpenAiCompatible) },
                )
            }
            item {
                ProviderClickableRow(
                    title = stringResource(R.string.ui_new_anthropic_db6098),
                    summary = stringResource(R.string.ui_support_anthropic_claude_official_or_compatible_api_de3f80),
                    leading = { ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC) },
                    onClick = { onAddProvider(NewProviderType.Anthropic) },
                )
            }
            item {
                ProviderClickableRow(
                    title = stringResource(R.string.ui_new_doubao_speech),
                    summary = stringResource(R.string.ui_new_doubao_speech_summary),
                    leading = { ProviderBrandIcon(ProviderSourceTypes.DOUBAO_SPEECH) },
                    onClick = { onAddProvider(NewProviderType.DoubaoSpeech) },
                )
            }
        }
    }

    item(key = "list_title") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                EtaSectionTitle(
                    text = pluralStringResource(
                        R.plurals.provider_configured_count,
                        filteredProviders.size,
                        filteredProviders.size,
                    ),
                )
            }
            if (selectionMode && selectableProviders.isNotEmpty()) {
                // M3：官方 TextButton。
                M3TextButton(onClick = onSelectAll) {
                    M3Text(text = stringResource(R.string.page_select_all_3e44b2))
                }
            }
        }
    }

    if (filteredProviders.isEmpty()) {
        item(key = "empty") {
            ListEmptyState(
                title = if (searchQuery.isBlank()) {
                    stringResource(R.string.provider_empty)
                } else {
                    stringResource(R.string.provider_no_matches)
                },
            )
        }
    } else {
        items(
            count = filteredProviders.size,
            key = { index -> filteredProviders[index].id },
        ) { index ->
            val provider = filteredProviders[index]
            EtaSegmentedItem(
                index = index,
                count = filteredProviders.size,
            ) {
                // 分段间 2dp 露出背景缝隙（KernelSU 的 spacedBy(2.dp)），
                // 末项补足组间距 13dp。
                val isLast = index == filteredProviders.lastIndex
                Column(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (isLast) 13.dp else 2.dp,
                    ),
                ) {
                    ProviderListItem(
                        provider = provider,
                        isCurrent = provider.id == selectedProviderId,
                        selectionMode = selectionMode,
                        checked = provider.id in selectedProviderIds,
                        onOpen = { onOpen(provider.id) },
                        onToggleChecked = { onToggleChecked(provider.id) },
                        onEnterSelection = { onEnterSelection(provider.id) },
                    )
                }
            }
        }
    }
}

/**
 * MIUI X 列表内容：与 Material 3 分支信息架构一致 —— 搜索框、添加组标题+卡片、
 * 已配置标题行（含全选）+ 卡片供应商行；空态同样为无容器文本。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.miuixProviderListContent(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filteredProviders: List<ProviderSetting>,
    selectableProviders: List<ProviderSetting>,
    selectionMode: Boolean,
    selectedProviderIds: Set<String>,
    selectedProviderId: String?,
    onToggleChecked: (String) -> Unit,
    onSelectAll: () -> Unit,
    onOpen: (String) -> Unit,
    onEnterSelection: (String) -> Unit,
    onAddProvider: (NewProviderType) -> Unit,
) {
    item(key = "search") {
        ProviderSearchField(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            placeholder = stringResource(R.string.ui_search_provider_74e049),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
    }

    item(key = "create_section") {
        Column(modifier = SectionGroupPadding) {
            EtaSectionTitle(text = stringResource(R.string.ui_add_new_provider_74df54))
            EtaCard {
                ProviderClickableRow(
                    title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                    summary = stringResource(R.string.ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02),
                    leading = { ProviderBrandIcon(ProviderSourceTypes.OPENAI) },
                    onClick = { onAddProvider(NewProviderType.OpenAiCompatible) },
                )
                ProviderClickableRow(
                    title = stringResource(R.string.ui_new_anthropic_db6098),
                    summary = stringResource(R.string.ui_support_anthropic_claude_official_or_compatible_api_de3f80),
                    leading = { ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC) },
                    onClick = { onAddProvider(NewProviderType.Anthropic) },
                )
                ProviderClickableRow(
                    title = stringResource(R.string.ui_new_doubao_speech),
                    summary = stringResource(R.string.ui_new_doubao_speech_summary),
                    leading = { ProviderBrandIcon(ProviderSourceTypes.DOUBAO_SPEECH) },
                    onClick = { onAddProvider(NewProviderType.DoubaoSpeech) },
                )
            }
        }
    }

    item(key = "list_title") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                EtaSectionTitle(
                    text = pluralStringResource(
                        R.plurals.provider_configured_count,
                        filteredProviders.size,
                        filteredProviders.size,
                    ),
                )
            }
            if (selectionMode && selectableProviders.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.page_select_all_3e44b2),
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier
                        .clickable(onClick = onSelectAll)
                        .padding(end = 16.dp),
                )
            }
        }
    }

    if (filteredProviders.isEmpty()) {
        item(key = "empty") {
            ListEmptyState(
                title = if (searchQuery.isBlank()) {
                    stringResource(R.string.provider_empty)
                } else {
                    stringResource(R.string.provider_no_matches)
                },
            )
        }
    } else {
        item(key = "list_card") {
            Column(modifier = SectionEdgePadding) {
                EtaCard {
                    filteredProviders.forEach { provider ->
                        ProviderListItem(
                            provider = provider,
                            isCurrent = provider.id == selectedProviderId,
                            selectionMode = selectionMode,
                            checked = provider.id in selectedProviderIds,
                            onOpen = { onOpen(provider.id) },
                            onToggleChecked = { onToggleChecked(provider.id) },
                            onEnterSelection = { onEnterSelection(provider.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderSelectionBar(
    selectedCount: Int,
    enabled: Boolean,
    onDelete: () -> Unit,
    onExit: () -> Unit,
) {
    EtaCard(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderDestructiveAction(
                text = stringResource(R.string.ui_delete_3755f5),
                onClick = onDelete,
                enabled = selectedCount > 0 && enabled,
                modifier = Modifier.weight(1f),
            )
            ProviderPrimaryAction(
                text = stringResource(R.string.action_cancel),
                onClick = onExit,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 供应商列表项。
 *
 * Material 3 走官方 [ListItem]（selected + onClick + onLongClick 一次覆盖点按、长按与
 * 多选勾选框），替掉「Row + combinedClickable + 手搓勾选」；MIUI X 保持原有实现。
 */
@Composable
private fun ProviderListItem(
    provider: ProviderSetting,
    isCurrent: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onOpen: () -> Unit,
    onToggleChecked: () -> Unit,
    onEnterSelection: () -> Unit,
) {
    val selectable = !provider.isBuiltIn
    val opacity = if (provider.isEnabled) 1f else 0.6f
    val metaText = listOfNotNull(
        provider.typeLabel,
        pluralStringResource(R.plurals.provider_models_count, provider.models.size, provider.models.size),
        stringResource(R.string.ui_built_in_09ceea).takeIf { provider.isBuiltIn },
        stringResource(R.string.ui_current_25e74d).takeIf { isCurrent },
    ).joinToString(" · ")
    val showCheckbox = selectionMode && selectable
    val showCurrentMark = !selectionMode && isCurrent

    val trailing: (@Composable () -> Unit)? = if (showCheckbox || showCurrentMark) {
        {
            if (showCheckbox) {
                if (isMaterial3Theme()) {
                    Checkbox(checked = checked, onCheckedChange = { onToggleChecked() })
                } else {
                    MiuixCheckbox(
                        state = if (checked) ToggleableState.On else ToggleableState.Off,
                        onClick = onToggleChecked,
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.ui_current_25e74d),
                    tint = if (isMaterial3Theme()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    } else {
        null
    }

    val meta: @Composable () -> Unit = {
        if (isMaterial3Theme()) {
            M3Text(
                text = metaText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = metaText,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
        if (!provider.isEnabled) {
            if (isMaterial3Theme()) {
                M3Text(
                    text = stringResource(R.string.ui_disabled_0fe5a9),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.ui_disabled_0fe5a9),
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
        }
    }

    if (isMaterial3Theme()) {
        EtaSegmentedRow(
            selected = isCurrent,
            onClick = {
                when {
                    selectionMode && selectable -> onToggleChecked()
                    selectionMode -> Unit
                    else -> onOpen()
                }
            },
            onLongClick = {
                if (selectable) {
                    if (selectionMode) onToggleChecked() else onEnterSelection()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = opacity },
            leadingContent = { ProviderIcon(provider) },
            content = {
                M3Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                // 本行只会在 M3 分支组合，直接用官方样式，无需再判主题。
                // Column 不能省：supportingContent 内多个文本若裸排会叠在同一位置。
                Column {
                    M3Text(
                        text = provider.baseUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    meta()
                }
            },
            trailingContent = trailing,
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        when {
                            selectionMode && selectable -> onToggleChecked()
                            selectionMode -> Unit
                            else -> onOpen()
                        }
                    },
                    onLongClick = {
                        if (selectable) {
                            if (selectionMode) onToggleChecked() else onEnterSelection()
                        }
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .graphicsLayer { alpha = opacity },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderIcon(provider)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.name,
                    style = MiuixTheme.textStyles.headline1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = provider.baseUrl,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                meta()
            }
            trailing?.invoke()
        }
    }
}
