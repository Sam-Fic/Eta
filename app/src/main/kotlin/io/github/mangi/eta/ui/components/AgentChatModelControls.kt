package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.data.repository.ProviderBalanceState
import io.github.mangi.eta.ui.pages.providers.ProviderBalanceIndicator
import io.github.mangi.eta.ui.pages.providers.hasBalanceIndicatorContent
import io.github.mangi.eta.ui.pages.providers.activityLifecycleOwnerOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.data.repository.ProviderBalanceStore
import io.github.mangi.eta.ui.pages.providers.ProviderBalanceAmount
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelProviderGroupUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.defaultExpandedModelProviderIds
import io.github.mangi.eta.ui.model.formatContextUsage
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator as M3CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider as M3HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaOnSurface
import io.github.mangi.eta.ui.adaptive.etaPrimary
import io.github.mangi.eta.ui.adaptive.etaSecondaryContainer
import io.github.mangi.eta.ui.adaptive.etaSurfaceContainerHigh
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme

/**
 * 官方菜单窗口属性一律使用 `MenuDefaults.DefaultMenuProperties`
 * （即 `PopupProperties(focusable = true)`），不要覆盖。
 *
 * 原因：`focusable = false` 会给 Popup 窗口加上 `FLAG_NOT_FOCUSABLE`，而
 * `createFlags()` 又**无条件**带上 `FLAG_WATCH_OUTSIDE_TOUCH`；于是「点开菜单的那一次抬手」
 * 会被窗口当成一次外部点击，配合 `dismissOnClickOutside` 立刻回调 `onDismissRequest`，
 * 结果就是菜单刚弹出就被关掉、肉眼看上去「点不出菜单」。
 *
 * 代价（与官方一致）：菜单弹出时会收起输入法。这是官方 `DropdownMenu` 的标准行为。
 */

/**
 * 官方菜单项形状：按位置取 `MenuDefaults` 的 leading / middle / trailing 令牌，
 * 单项时用 `standaloneItemShape`。不自定义圆角。
 */
@Composable
internal fun etaOfficialMenuItemShape(index: Int, count: Int): Shape = when {
    count <= 1 -> MenuDefaults.standaloneItemShape
    index == 0 -> MenuDefaults.leadingItemShape
    index == count - 1 -> MenuDefaults.trailingItemShape
    else -> MenuDefaults.middleItemShape
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentModelPickerButton(
    conversationId: String? = null,
    state: AgentModelPickerUiState,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    collaborationTaskRunning: Boolean = isStreaming,
    // Deferred: invoked when the menu is composed, so the input bar does not read its own
    // window position (and therefore does not recompose while the keyboard animates).
    popupMaxHeight: () -> Dp,
    onModelSelected: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    containerShape: androidx.compose.ui.graphics.Shape? = null,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    containerSize: Dp = ChatInputActionSize,
    connectedShapes: androidx.compose.material3.ToggleButtonShapes? = null,
) {
    // Draft tokens are real owners too: a nullable conversationId cannot fence a popup.
    val owner = LocalConversationSubAgentEditor.current?.owner
    val popup = remember(owner) { OwnerBoundPopup(owner) }
    val currentPopup by androidx.compose.runtime.rememberUpdatedState(popup)
    val menuState = remember(owner) { EtaMenuState() }
    var menuTicket by remember(popup) { mutableStateOf<Any?>(null) }
    var dialogTicket by remember(popup) { mutableStateOf<Any?>(null) }
    val capturedDialogTicket = dialogTicket
    ConversationCollaborationDialog(
        show = popup.isCurrent(capturedDialogTicket, currentPopup),
        enabled = false, // The dialog reads the scoped repository, never this legacy argument.
        taskRunning = collaborationTaskRunning,
        ownerMatches = { popup.isCurrent(capturedDialogTicket, currentPopup) },
        onEnabledChange = {},
        onDismiss = {
            popup.dispatch(capturedDialogTicket, currentPopup) {
                popup.dismiss()
                dialogTicket = null
            }
        },
    )
    var expandedProviderIds by remember(owner) { mutableStateOf(emptySet<String>()) }
    var modelQuery by remember(owner) { mutableStateOf("") }
    val selected = state.selectedModel
    val pickerAvailable = (!isStreaming || isPaused) && state.providerGroups.isNotEmpty()
    LaunchedEffect(pickerAvailable) {
        if (!pickerAvailable) {
            popup.dismiss()
            menuTicket = null
            dialogTicket = null
            menuState.dismiss()
        }
    }
    val currentModel = selected?.displayName ?: stringResource(R.string.model_not_selected)
    val switchModelDescription = stringResource(R.string.model_switch_current, currentModel)
    val capturedMenuTicket = menuTicket
    val menuVisible =
        menuState.expanded && pickerAvailable && popup.isCurrent(capturedMenuTicket, currentPopup)
    val anchorContent: @Composable () -> Unit = {
        ModelBrandMark(
            modelId = selected?.modelId,
            sourceType = selected?.providerSourceType,
            size = if (isMaterial3Theme()) {
                // 图标尺寸走官方 IconButtonDefaults 令牌，由共享助手按容器尺寸取「仍然放得下的
                // 最大档」：XS 32/20、S 40/24、M 56/24、L 96/32、XL 136/40。
                etaOfficialIconButtonIconSize(containerSize)
            } else {
                ChatInputActionIconSize
            },
            modifier = if (isMaterial3Theme()) {
                // M3 下由官方禁用态配色表达不可用，不再手动调透明度。
                Modifier
            } else {
                Modifier.graphicsLayer(alpha = if (pickerAvailable) 1f else 0.38f)
            },
        )
    }
    val onAnchorClick: () -> Unit = {
        // 单击打开模型菜单不需要子代理 editor owner（主会话里 owner 为空），
        // owner 只用于长按打开的协作设置对话框。
        if (pickerAvailable) {
            expandedProviderIds = defaultExpandedModelProviderIds(state.selectedModel)
            modelQuery = ""
            menuState.onAnchorClick()
            menuTicket = if (menuState.expanded) popup.open() else {
                popup.dismiss()
                null
            }
        }
    }
    val onMenuDismiss: () -> Unit = {
        popup.dispatch(capturedMenuTicket, currentPopup) {
            popup.dismiss()
            menuTicket = null
            menuState.dismiss()
        }
    }
    val onProviderExpandedChange: (String, Boolean) -> Unit = { providerId, expanded ->
        popup.dispatch(capturedMenuTicket, currentPopup) {
            expandedProviderIds = if (expanded) setOf(providerId) else emptySet()
        }
    }
    val onPickModel: (String, String) -> Unit = { providerId, modelId ->
        popup.dispatch(capturedMenuTicket, currentPopup) {
            if (!state.isChanging && pickerAvailable) onModelSelected(providerId, modelId)
        }
    }
    Box(modifier = modifier) {
        ChatInputNonFocusableIconButton(
            onClick = onAnchorClick,
            contentDescription = switchModelDescription,
            onLongClick = {
                if (owner != null) {
                    menuState.dismiss()
                    menuTicket = null
                    dialogTicket = popup.open()
                }
            },
            containerShape = containerShape,
            containerColor = containerColor,
            containerSize = containerSize,
            connectedShapes = connectedShapes,
            enabled = !isMaterial3Theme() || pickerAvailable,
            content = anchorContent,
        )
        if (isMaterial3Theme()) {
            // Material 3 Expressive：官方 ModalBottomSheet（布局复刻 KernelSU MultiSelectDialog：
            // 标题行 + 关闭按钮 + 搜索框 + 可滚动列表；仅允许 Hidden↔Expanded 两态，不进半展开）。
            if (menuVisible) {
                ModalBottomSheet(
                    onDismissRequest = onMenuDismiss,
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
                                text = stringResource(R.string.model_picker_title),
                                style = etaTextStyle(EtaType.Title),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onMenuDismiss) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.action_close),
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            ModelPickerMenuContentM3(
                                state = state,
                                query = modelQuery,
                                onQueryChange = { modelQuery = it },
                                expandedProviderIds = expandedProviderIds,
                                onProviderExpandedChange = onProviderExpandedChange,
                                onModelSelected = onPickModel,
                            )
                        }
                    }
                }
            }
        } else {
            EtaDropdownMenu(
                expanded = menuVisible,
                onDismissRequest = onMenuDismiss,
                alignEnd = true,
                preferAbove = true,
                focusable = true,
                minWidth = 220.dp,
                maxWidth = 220.dp,
                maxHeight = popupMaxHeight(),
            ) {
                ModelPickerPopupContent(
                    state = state,
                    query = modelQuery,
                    onQueryChange = { modelQuery = it },
                    expandedProviderIds = expandedProviderIds,
                    onProviderExpandedChange = onProviderExpandedChange,
                    onModelSelected = onPickModel,
                )
            }
        }
    }
}

/**
 * 订阅并按需刷新各服务商的余额快照；两个主题族的菜单共用。
 */
@Composable
private fun rememberProviderBalanceStates(): Map<String, ProviderBalanceState> {
    val states by ProviderBalanceStore.states.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        context.activityLifecycleOwnerOrNull()?.lifecycleScope?.let { scope ->
            ProviderBalanceStore.start(scope)
            ProviderBalanceStore.requestRefresh(scope)
        }
    }
    return states
}

/** 按搜索词过滤服务商分组：命中服务商名时保留全部模型，否则只保留命中的模型。 */
private fun filterModelProviderGroups(
    providerGroups: List<AgentModelProviderGroupUi>,
    needle: String,
): List<AgentModelProviderGroupUi> {
    if (needle.isEmpty()) return providerGroups
    return providerGroups.mapNotNull { group ->
        val providerHit = group.providerName.contains(needle, ignoreCase = true)
        val models = if (providerHit) {
            group.models
        } else {
            group.models.filter { model ->
                model.displayName.contains(needle, ignoreCase = true) ||
                    model.modelId.contains(needle, ignoreCase = true)
            }
        }
        if (models.isEmpty()) null else group.copy(models = models)
    }
}

@Composable
private fun ModelPickerPopupContent(
    state: AgentModelPickerUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    expandedProviderIds: Set<String>,
    onProviderExpandedChange: (String, Boolean) -> Unit,
    onModelSelected: (String, String) -> Unit,
) {
    val balanceStates = rememberProviderBalanceStates()
    ModelPickerSearchField(query = query, onQueryChange = onQueryChange)
    val needle = query.trim()
    val groups = filterModelProviderGroups(state.providerGroups, needle)
    if (groups.isEmpty()) {
        Text(
            text = stringResource(R.string.model_search_empty),
            style = etaTextStyle(EtaType.Caption),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        return
    }
    groups.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
            }
            val expanded = needle.isNotEmpty() || group.providerId in expandedProviderIds
            ModelProviderGroupHeader(
                name = group.providerName,
                expanded = expanded,
                balance = balanceStates[group.providerId],
                onClick = {
                    if (needle.isEmpty()) onProviderExpandedChange(group.providerId, !expanded)
                },
            )
            if (expanded) {
                group.models.forEach { model ->
                    ModelPickerRow(
                        model = model,
                        selected = model.id == state.selectedModel?.id && model.providerId == state.selectedModel?.providerId,
                        onClick = { onModelSelected(model.providerId, model.id) },
                    )
                }
            }
    }
}

// ---------------------------------------------------------------------------
// Material 3 Expressive：模型选择菜单
// ---------------------------------------------------------------------------

/** 菜单行模型：把 MIUI X 的手搓行结构映射成官方 DropdownMenuItem 序列。 */
private sealed interface ModelMenuRow {
    data class Provider(
        val providerId: String,
        val name: String,
        val expanded: Boolean,
        val collapsible: Boolean,
        val balance: ProviderBalanceState?,
    ) : ModelMenuRow

    data class Model(val model: AgentModelOptionUi, val selected: Boolean) : ModelMenuRow

    data object Divider : ModelMenuRow

    data object Empty : ModelMenuRow
}

private fun List<ModelMenuRow>.isMenuItemAt(index: Int): Boolean =
    when (getOrNull(index)) {
        is ModelMenuRow.Provider, is ModelMenuRow.Model -> true
        else -> false
    }

/** 当前连续菜单项段的首项下标。 */
private fun List<ModelMenuRow>.firstItemInRunAt(index: Int): Int {
    var first = index
    while (first - 1 >= 0 && isMenuItemAt(first - 1)) first--
    return first
}

/** 连续菜单项段的长度，供官方 `MenuDefaults.itemShape(index, count)` 使用。 */
private fun List<ModelMenuRow>.itemRunLengthAt(index: Int): Int {
    val first = firstItemInRunAt(index)
    var last = index
    while (last + 1 <= lastIndex && isMenuItemAt(last + 1)) last++
    return last - first + 1
}

/**
 * 官方 `DropdownMenu` 内容：官方 `OutlinedTextField` 搜索框 +
 * 官方 `DropdownMenuItem`（单选模型项用官方 `selected` 重载，语义为 RadioButton）+
 * 官方 `HorizontalDivider`，行形状与选中配色全部来自 `MenuDefaults`。
 *
 * 同时被旧模型菜单与「助手与模型」二合一弹窗的模型页签复用。
 */
@Composable
internal fun ModelPickerMenuContentM3(
    state: AgentModelPickerUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    expandedProviderIds: Set<String>,
    onProviderExpandedChange: (String, Boolean) -> Unit,
    onModelSelected: (String, String) -> Unit,
) {
    val balanceStates = rememberProviderBalanceStates()
    val needle = query.trim()
    val groups = filterModelProviderGroups(state.providerGroups, needle)
    val rows: List<ModelMenuRow> = buildList {
        if (groups.isEmpty()) {
            add(ModelMenuRow.Empty)
        } else {
            groups.forEachIndexed { groupIndex, group ->
                if (groupIndex > 0) add(ModelMenuRow.Divider)
                val expanded = needle.isNotEmpty() || group.providerId in expandedProviderIds
                add(
                    ModelMenuRow.Provider(
                        providerId = group.providerId,
                        name = group.providerName,
                        expanded = expanded,
                        collapsible = needle.isEmpty(),
                        balance = balanceStates[group.providerId],
                    ),
                )
                if (expanded) {
                    group.models.forEach { model ->
                        add(
                            ModelMenuRow.Model(
                                model = model,
                                selected = model.id == state.selectedModel?.id &&
                                    model.providerId == state.selectedModel?.providerId,
                            ),
                        )
                    }
                }
            }
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
        placeholder = { M3Text(text = stringResource(R.string.model_search_hint)) },
    )
    rows.forEachIndexed { index, row ->
        when (row) {
            is ModelMenuRow.Provider -> DropdownMenuItem(
                onClick = { onProviderExpandedChange(row.providerId, !row.expanded) },
                text = {
                    M3Text(text = row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = { ModelProviderHeaderTrailingM3(row = row) },
                enabled = row.collapsible,
                shape = MenuDefaults.itemShape(index, rows.itemRunLengthAt(index)).shape,
            )

            is ModelMenuRow.Model -> SelectableDropdownMenuItem(
                selected = row.selected,
                onClick = { onModelSelected(row.model.providerId, row.model.id) },
                text = {
                    M3Text(
                        text = row.model.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                shapes = MenuDefaults.itemShape(index, rows.itemRunLengthAt(index)),
            )

            ModelMenuRow.Divider -> M3HorizontalDivider(
                modifier = Modifier.padding(MenuDefaults.HorizontalDividerPadding),
            )

            ModelMenuRow.Empty -> DropdownMenuItem(
                onClick = {},
                text = { M3Text(text = stringResource(R.string.model_search_empty)) },
                enabled = false,
                shape = MenuDefaults.standaloneItemShape,
            )
        }
    }
}

/**
 * 服务商行尾部：余额 + 展开箭头。
 * 箭头旋转使用官方 `MaterialTheme.motionScheme` 空间动效规格，不自定义 tween。
 */
@Composable
private fun ModelProviderHeaderTrailingM3(row: ModelMenuRow.Provider) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (row.expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(),
        label = "model_provider_arrow",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MenuDefaults.GroupSpacing),
    ) {
        if (hasBalanceIndicatorContent(row.balance)) {
            ProviderBalanceIndicator(state = row.balance)
        }
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = if (row.expanded) {
                stringResource(R.string.model_collapse_provider, row.name)
            } else {
                stringResource(R.string.model_expand_provider, row.name)
            },
            modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelPickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(etaSurfaceContainerHigh())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Box(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.model_search_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = etaTextStyle(EtaType.Caption),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = etaTextStyle(EtaType.Caption).copy(color = etaOnSurface()),
                cursorBrush = SolidColor(etaPrimary()),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ModelProviderGroupHeader(
    name: String,
    expanded: Boolean,
    balance: ProviderBalanceState? = null,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "model_provider_arrow",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                TouchHaptics.click(view)
                onClick()
            }
            .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = etaTextStyle(EtaType.Caption),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hasBalanceIndicatorContent(balance)) {
            Spacer(modifier = Modifier.width(8.dp))
            ProviderBalanceIndicator(state = balance)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) {
                stringResource(R.string.model_collapse_provider, name)
            } else {
                stringResource(R.string.model_expand_provider, name)
            },
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = arrowRotation },
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}

@Composable
private fun ModelPickerRow(
    model: AgentModelOptionUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .squircleSurface(
                color = if (selected) {
                    etaSurfaceContainerHigh()
                } else {
                    Color.Transparent
                },
                cornerRadius = 12.dp,
            )
            .clickable {
                TouchHaptics.click(view)
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model.displayName,
            style = etaTextStyle(EtaType.Secondary),
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.ui_current_model_a0af8f),
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentContextUsageButton(
    usage: AgentContextUsageUi,
    sendBlocked: Boolean = false,
    popupMaxHeight: () -> Dp = { 360.dp },
    modifier: Modifier = Modifier,
    containerSize: Dp = ChatInputActionSize,
    /**
     * 进度环绘制直径，两个主题族都生效。
     *
     * Material 3 下官方 `CircularProgressIndicator` / `CircularWavyProgressIndicator`
     * 会额外追加官方直径（圆形 40dp、波浪 48dp），但调用方这个 `size` 位于链上最外层，
     * 会先把约束收紧，因此实际渲染以本参数为准（详见进度环分支内的注释）。
     */
    ringSize: Dp = ChatInputActionIconSize,
    ringStrokeWidth: Dp = 2.5.dp,
    /** 波浪样式（Material 3 Expressive）：模型回复中使用。 */
    wavy: Boolean = false,
) {
    val menuState = rememberEtaMenuState()
    val selectorState = rememberEtaMenuState()
    val context = LocalContext.current
    val telemetry = LocalAgentContextTelemetry.current
    val owner = LocalConversationSubAgentEditor.current?.owner
    var localSelectedTaskId by remember(owner) { mutableStateOf<String?>(null) }
    val selectedTaskId = if (telemetry.onTaskSelected != null) telemetry.selectedTaskId else localSelectedTaskId
    fun selectTask(id: String?) {
        if (telemetry.onTaskSelected != null) telemetry.onTaskSelected.invoke(id) else localSelectedTaskId = id
    }
    val child = telemetry.children.firstOrNull { it.taskId == selectedTaskId }
    LaunchedEffect(telemetry.children, selectedTaskId, owner) {
        // Controlled selection belongs to the owner reducer: a refresh effect must not
        // replay an old null selection over a newer explicit user choice.
        if (telemetry.onTaskSelected == null && selectedTaskId != null && child == null) {
            localSelectedTaskId = null
        }
    }
    val displayedUsage = child?.cloudContextUsage() ?: usage
    val selectedLabel = child?.contextLabel(context) ?: telemetry.mainModelName.ifBlank { stringResource(R.string.acm_main_agent) }
    val progress = displayedUsage.progress
    val progressColor = when {
        progress == null -> MiuixTheme.colorScheme.onSurfaceVariantActions
        progress >= 0.95f -> StatusError
        progress >= 0.80f -> StatusWarning
        else -> etaPrimary()
    }
    val locale = LocalConfiguration.current.locales[0]
    val summary = child?.cloudContextSummary(context, locale) ?: formatContextUsage(
        usage = displayedUsage,
        noUsageText = stringResource(R.string.context_no_previous_usage),
        noLimitText = stringResource(R.string.context_no_model_limit),
        unknownText = stringResource(R.string.amp_unknown),
        locale = locale,
    )
    val detail = when {
        sendBlocked && child == null -> "$summary\n${stringResource(R.string.context_window_send_blocked)}"
        child?.role == "video_generation" -> "${child.contextStatusLabel(context)}\n${stringResource(R.string.acm_video_no_context_stats)}"
        child?.role == "image_generation" -> "${child.contextStatusLabel(context)}\n${stringResource(R.string.acm_image_no_context_stats)}"
        child != null -> "$summary\n${child.contextStatusLabel(context)} · ${stringResource(R.string.acm_standalone_context)}"
        else -> summary
    }
    val usageDescription = stringResource(
        R.string.context_usage_description,
        summary.replace('\n', ' '),
    )
    val keepIme = rememberKeepImeWhenOpeningMenu()
    val material3 = isMaterial3Theme()
    Box(modifier = modifier) {
        ChatInputNonFocusableIconButton(
            onClick = {
                keepIme()
                selectorState.dismiss()
                menuState.onAnchorClick()
            },
            longClickLabel = stringResource(R.string.acm_select_context_target),
            onLongClick = {
                keepIme()
                menuState.dismiss()
                selectorState.onAnchorClick()
            },
            containerSize = containerSize,
        ) {
            val ringSemantics = Modifier.semantics {
                contentDescription = "$selectedLabel · $usageDescription"
            }
            if (material3) {
                // 官方 Material 3 进度指示器：圆形 / 波浪，颜色只引用 colorScheme 角色。
                //
                // 官方圆形内部是 `Canvas(modifier.semantics{}.size(40.dp))`、
                // 波浪是 `Spacer(modifier.size(48.dp)…​)`，都会在调用方 modifier 之后追加一个
                // 官方直径。但 `Modifier.size()` 的语义是「把目标尺寸收敛进传入约束」
                // （无条件覆盖的是 `requiredSize()`），而本链上调用方的 size 在最外层：
                //   按钮 40dp 的 Box 会以 min=0/max=40 测量子节点
                //   → 外层 size(ringSize) 把约束收紧成 [ringSize, ringSize]
                //   → 内层官方 size(40) 再被 coerceIn 到 [ringSize, ringSize]
                // 所以 ringSize 在 M3 下**是生效的**，必须保留，否则环会按官方 40/48dp
                // 顶到按钮边缘（波浪还会溢出）。
                val scheme = MaterialTheme.colorScheme
                val indicatorColor = when {
                    progress == null -> scheme.onSurfaceVariant
                    progress >= 0.95f -> scheme.error
                    progress >= 0.80f -> scheme.tertiary
                    else -> scheme.primary
                }
                if (wavy) {
                    val strokePx = with(androidx.compose.ui.platform.LocalDensity.current) {
                        ringStrokeWidth.toPx()
                    }
                    CircularWavyProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = ringSemantics.size(ringSize),
                        color = indicatorColor,
                        trackColor = scheme.secondaryContainer,
                        stroke = Stroke(width = strokePx),
                        trackStroke = Stroke(width = strokePx),
                    )
                } else {
                    M3CircularProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = ringSemantics.size(ringSize),
                        color = indicatorColor,
                        trackColor = scheme.secondaryContainer,
                        strokeWidth = ringStrokeWidth,
                    )
                }
            } else if (wavy) {
                val strokePx = with(androidx.compose.ui.platform.LocalDensity.current) {
                    ringStrokeWidth.toPx()
                }
                CircularWavyProgressIndicator(
                    progress = { progress ?: 0f },
                    modifier = ringSemantics.size(ringSize),
                    color = progressColor,
                    trackColor = etaSecondaryContainer(),
                    stroke = Stroke(width = strokePx),
                    trackStroke = Stroke(width = strokePx),
                )
            } else {
                CircularProgressIndicator(
                    progress = progress ?: 0f,
                    colors = ProgressIndicatorDefaults.progressIndicatorColors(
                        foregroundColor = progressColor,
                        disabledForegroundColor = progressColor,
                        backgroundColor = etaSecondaryContainer(),
                    ),
                    strokeWidth = ringStrokeWidth,
                    size = ringSize,
                    modifier = ringSemantics,
                )
            }
        }
        if (material3) {
            // 官方 DropdownMenu：不抢焦点（保持输入法），菜单本体样式全部为官方默认值。
            DropdownMenu(
                expanded = menuState.expanded,
                onDismissRequest = menuState::dismiss,
            ) {
                // 官方组标题自带 labelLarge 字阶，这里不再覆盖 style。
                MenuDefaults.DropdownMenuGroupLabel {
                    M3Text(text = selectedLabel)
                }
                M3Text(
                    text = detail,
                    style = etaTextStyle(EtaType.Secondary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(MenuDefaults.DropdownMenuSelectableItemContentPadding),
                )
            }
            val targets =
                listOf("${telemetry.mainModelName.ifBlank { stringResource(R.string.acm_main_agent) }}（${stringResource(R.string.acm_main_agent)}）") +
                    telemetry.children.map { "${it.contextLabel(context)} · ${it.contextStatusLabel(context)}" }
            val selectedIndex = if (child == null) 0 else {
                telemetry.children.indexOfFirst { it.taskId == selectedTaskId } + 1
            }
            DropdownMenu(
                expanded = selectorState.expanded,
                onDismissRequest = selectorState::dismiss,
                modifier = Modifier.heightIn(max = popupMaxHeight()),
            ) {
                MenuDefaults.DropdownMenuGroupLabel { M3Text(text = stringResource(R.string.acm_context_stats)) }
                targets.forEachIndexed { index, label ->
                    SelectableDropdownMenuItem(
                        selected = index == selectedIndex,
                    onClick = {
                        selectTask(if (index == 0) null else telemetry.children[index - 1].taskId)
                        selectorState.dismiss()
                    },
                    text = {
                        M3Text(
                            text = label,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    shapes = MenuDefaults.itemShape(index, targets.size),
                )
                }
            }
        } else {
            EtaDropdownMenu(
                expanded = menuState.expanded,
                onDismissRequest = menuState::dismiss,
                alignEnd = true,
                preferAbove = true,
                focusable = false,
                minWidth = 0.dp,
                maxWidth = 220.dp,
            ) {
                Text(
                    text = selectedLabel,
                    style = etaTextStyle(EtaType.Body),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    text = detail,
                    style = etaTextStyle(EtaType.Secondary),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }
            EtaDropdownMenu(
                expanded = selectorState.expanded,
                onDismissRequest = selectorState::dismiss,
                alignEnd = true, preferAbove = true, focusable = false,
                minWidth = 220.dp, maxWidth = 260.dp, maxHeight = popupMaxHeight(),
            ) {
                Text(stringResource(R.string.acm_context_stats), style = etaTextStyle(EtaType.Caption),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                ContextTargetRow("${telemetry.mainModelName.ifBlank { stringResource(R.string.acm_main_agent) }}（${stringResource(R.string.acm_main_agent)}）", child == null) {
                    selectTask(null); selectorState.dismiss()
                }
                telemetry.children.forEach { target ->
                    ContextTargetRow("${target.contextLabel(context)} · ${target.contextStatusLabel(context)}", selectedTaskId == target.taskId) {
                        selectTask(target.taskId); selectorState.dismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelBrandMark(
    modelId: String?,
    sourceType: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val logo = modelOrProviderBrandLogoRes(modelId, sourceType)
    if (logo != null) {
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        val containerColor = if (isMaterial3Theme()) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MiuixTheme.colorScheme.primaryContainer
        }
        val iconTint = if (isMaterial3Theme()) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MiuixTheme.colorScheme.primary
        }
        Box(
            modifier = modifier
                .size(size)
                .background(containerColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Dns,
                contentDescription = null,
                modifier = Modifier.size(size * 0.56f),
                tint = iconTint,
            )
        }
    }
}

@Composable
private fun ContextTargetRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
        .squircleSurface(color = if (selected) etaSurfaceContainerHigh() else Color.Transparent, cornerRadius = 12.dp)
        .clickable { TouchHaptics.click(view); onClick() }
        .padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = etaTextStyle(EtaType.Secondary), maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.acm_current_target), modifier = Modifier.size(18.dp))
    }
}
