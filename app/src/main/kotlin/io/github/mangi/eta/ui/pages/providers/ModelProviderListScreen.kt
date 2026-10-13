package io.github.mangi.eta.ui.pages.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.material3.Text as M3Text
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox

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

    EtaScaffold(
        title = stringResource(R.string.ui_model_provider_e8c7f5),
        onBack = {
            if (selectionMode) {
                selectionMode = false
                selectedProviderIds = emptySet()
            } else {
                onBack()
            }
        },
    ) { paddingValues, scrollBehavior, sidePadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    end = sidePadding,
                ),
                overscrollEffect = null,
            ) {
                item(key = "search") {
                    ProviderSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(R.string.ui_search_provider_74e049),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp, bottom = 8.dp),
                    )
                }

                item(key = "create_section") {
                    ProviderSection(title = stringResource(R.string.ui_add_new_provider_74df54)) {
                        ProviderClickableRow(
                            title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                            summary = stringResource(R.string.ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02),
                            leading = { ProviderBrandIcon(ProviderSourceTypes.OPENAI) },
                            onClick = {
                                TouchHaptics.click(view)
                                onNavigate(AppRoute.ModelProviderAuthMethod(NewProviderType.OpenAiCompatible))
                            },
                        )
                        ProviderClickableRow(
                            title = stringResource(R.string.ui_new_anthropic_db6098),
                            summary = stringResource(R.string.ui_support_anthropic_claude_official_or_compatible_api_de3f80),
                            leading = { ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC) },
                            onClick = {
                                TouchHaptics.click(view)
                                onNavigate(AppRoute.ModelProviderNew(NewProviderType.Anthropic))
                            },
                        )
                        ProviderClickableRow(
                            title = stringResource(R.string.ui_new_doubao_speech),
                            summary = stringResource(R.string.ui_new_doubao_speech_summary),
                            leading = { ProviderBrandIcon(ProviderSourceTypes.DOUBAO_SPEECH) },
                            onClick = {
                                TouchHaptics.click(view)
                                onNavigate(AppRoute.ModelProviderNew(NewProviderType.DoubaoSpeech))
                            },
                        )
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
                            val onSelectAll = {
                                TouchHaptics.click(view)
                                selectedProviderIds = if (
                                    selectableProviders.isNotEmpty() &&
                                    selectedProviderIds.containsAll(selectableProviders.map { it.id })
                                ) {
                                    emptySet()
                                } else {
                                    selectableProviders.mapTo(mutableSetOf()) { it.id }
                                }
                            }
                            // M3：官方 TextButton，替掉「Text + clickable」。
                            if (isMaterial3Theme()) {
                                M3TextButton(onClick = onSelectAll) {
                                    M3Text(text = stringResource(R.string.page_select_all_3e44b2))
                                }
                            } else {
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
                }

                item(key = "list_section") {
                    ProviderSection(title = null) {
                        if (filteredProviders.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                M3Text(
                                    text = if (searchQuery.isBlank()) {
                                        stringResource(R.string.provider_empty)
                                    } else {
                                        stringResource(R.string.provider_no_matches)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            filteredProviders.forEach { provider ->
                                ProviderListItem(
                                    provider = provider,
                                    isCurrent = provider.id == selectedProviderId,
                                    selectionMode = selectionMode,
                                    checked = provider.id in selectedProviderIds,
                                    onOpen = {
                                        TouchHaptics.click(view)
                                        onNavigate(AppRoute.ModelProviderDetail(provider.id))
                                    },
                                    onToggleChecked = {
                                        TouchHaptics.click(view)
                                        if (!provider.isBuiltIn) {
                                            selectedProviderIds = if (provider.id in selectedProviderIds) {
                                                selectedProviderIds - provider.id
                                            } else {
                                                selectedProviderIds + provider.id
                                            }
                                        }
                                    },
                                    onEnterSelection = {
                                        TouchHaptics.longPress(view)
                                        if (!provider.isBuiltIn) {
                                            selectionMode = true
                                            selectedProviderIds = setOf(provider.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(
                        modifier = Modifier
                            .height(if (selectionMode) 88.dp else 24.dp)
                            .navigationBarsPadding(),
                    )
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
        ListItem(
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
                if (isMaterial3Theme()) {
                    M3Text(
                        text = provider.baseUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = provider.baseUrl,
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                meta()
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
