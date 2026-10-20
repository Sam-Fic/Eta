package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.data.repository.ProviderBalanceState
import io.github.mangi.eta.ui.pages.providers.ProviderBalanceIndicator
import io.github.mangi.eta.ui.pages.providers.hasBalanceIndicatorContent
import io.github.mangi.eta.ui.pages.providers.activityLifecycleOwnerOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.ProviderBalanceStore
import io.github.mangi.eta.ui.pages.providers.ProviderBalanceAmount
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelProviderGroupUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.formatContextUsage
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle

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
            // 图标尺寸走官方 IconButtonDefaults 令牌，由共享助手按容器尺寸取「仍然放得下的
            // 最大档」：XS 32/20、S 40/24、M 56/24、L 96/32、XL 136/40。
            size = etaOfficialIconButtonIconSize(containerSize),
            // 由官方禁用态配色表达不可用，不再手动调透明度。
            modifier = Modifier,
        )
    }
    val onAnchorClick: () -> Unit = {
        // 单击打开模型菜单不需要子代理 editor owner（主会话里 owner 为空），
        // owner 只用于长按打开的协作设置对话框。
        if (pickerAvailable) {
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
            enabled = pickerAvailable,
            content = anchorContent,
        )
        // Material 3 Expressive：官方 ModalBottomSheet（布局复刻 KernelSU MultiSelectDialog：
        // 标题行 + 搜索框 + 可滚动列表；仅允许 Hidden↔Expanded 两态，不进半展开）。
        // 关闭走面板下拉 / 点遮罩 / 返回键，不放右上角关闭按钮。
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
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        M3Text(
                            text = stringResource(R.string.model_picker_title),
                            style = etaTextStyle(EtaType.Title),
                        )
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
                            onModelSelected = onPickModel,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 订阅并按需刷新各服务商的余额快照；模型选择菜单共用。
 */
@Composable
internal fun rememberProviderBalanceStates(): Map<String, ProviderBalanceState> {
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
internal fun filterModelProviderGroups(
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

// ---------------------------------------------------------------------------
// Material 3 Expressive：模型选择菜单
// ---------------------------------------------------------------------------

/** 菜单行模型：服务商分类标题 + 官方 DropdownMenuItem 序列。 */
private sealed interface ModelMenuRow {
    data class Provider(
        val name: String,
        val balance: ProviderBalanceState?,
    ) : ModelMenuRow

    data class Model(val model: AgentModelOptionUi, val selected: Boolean) : ModelMenuRow

    data object Empty : ModelMenuRow
}

/** 只有模型行是菜单项；服务商标题把菜单项切成若干形状段。 */
private fun List<ModelMenuRow>.isMenuItemAt(index: Int): Boolean =
    getOrNull(index) is ModelMenuRow.Model

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

/** 模型搜索框：旧模型菜单与「助手与模型」弹窗共用（官方 `OutlinedTextField`）。 */
@Composable
internal fun ModelSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
        placeholder = { M3Text(text = stringResource(R.string.model_search_hint)) },
    )
}

/**
 * 官方 `DropdownMenu` 内容：官方搜索框 + 官方 `DropdownMenuGroupLabel` 服务商分类标题 +
 * 官方 `SelectableDropdownMenuItem`（单选模型项用官方 `selected` 重载，语义为 RadioButton），
 * 行形状与选中配色全部来自 `MenuDefaults`。
 *
 * 模型全部平铺，不再按服务商折叠；服务商只作为分类文字出现。
 */
@Composable
internal fun ModelPickerMenuContentM3(
    state: AgentModelPickerUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onModelSelected: (String, String) -> Unit,
) {
    val balanceStates = rememberProviderBalanceStates()
    val groups = filterModelProviderGroups(state.providerGroups, query.trim())
    val selected = state.selectedModel
    val rows: List<ModelMenuRow> = buildList {
        if (groups.isEmpty()) {
            add(ModelMenuRow.Empty)
        } else {
            groups.forEach { group ->
                add(
                    ModelMenuRow.Provider(
                        name = group.providerName,
                        balance = balanceStates[group.providerId],
                    ),
                )
                group.models.forEach { model ->
                    add(
                        ModelMenuRow.Model(
                            model = model,
                            selected = selected != null &&
                                model.id == selected.id &&
                                model.providerId == selected.providerId,
                        ),
                    )
                }
            }
        }
    }
    ModelSearchField(query = query, onQueryChange = onQueryChange)
    rows.forEachIndexed { index, row ->
        when (row) {
            is ModelMenuRow.Provider -> MenuDefaults.DropdownMenuGroupLabel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MenuDefaults.GroupSpacing),
                ) {
                    M3Text(
                        text = row.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasBalanceIndicatorContent(row.balance)) {
                        ProviderBalanceIndicator(state = row.balance)
                    }
                }
            }

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
                // 形状段以「服务商标题」为界，首/末项各自拿到官方 leading/trailing 形状。
                shapes = MenuDefaults.itemShape(
                    index = index - rows.firstItemInRunAt(index),
                    count = rows.itemRunLengthAt(index),
                ),
                // 选中项带 Check 前导图标，与「请求接口」那个下拉同款。
                selectedLeadingIcon = {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                    )
                },
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
 * 上下文统计的页内直显内容。
 *
 * 选中子任务上下文时展示该子任务的用量，否则展示主代理的。
 */
@Immutable
internal data class ContextUsageDisplay(
    /** 统计对象的模型名（子任务上下文时是子代理的模型与角色）。 */
    val label: String,
    /** 用量摘要，如「0k / 262.14K tokens」。 */
    val summary: String,
    /** 上下文已满、发送被拦截。 */
    val blocked: Boolean,
)

/**
 * 上下文统计内容：与旧弹窗同源（主代理或当前选中的子任务上下文），但直接写在页面上。
 */
@Composable
internal fun contextUsageDisplay(
    usage: AgentContextUsageUi,
    sendBlocked: Boolean,
): ContextUsageDisplay {
    val telemetry = LocalAgentContextTelemetry.current
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val child = telemetry.children.firstOrNull { it.taskId == telemetry.selectedTaskId }
    val displayedUsage = child?.cloudContextUsage() ?: usage
    val summary = child?.cloudContextSummary(context, locale) ?: formatContextUsage(
        usage = displayedUsage,
        noUsageText = stringResource(R.string.context_no_previous_usage),
        noLimitText = stringResource(R.string.context_no_model_limit),
        unknownText = stringResource(R.string.amp_unknown),
        locale = locale,
    )
    return ContextUsageDisplay(
        label = child?.contextLabel(context)
            ?: telemetry.mainModelName.ifBlank { stringResource(R.string.acm_main_agent) },
        summary = summary.replace('\n', ' '),
        blocked = sendBlocked && child == null,
    )
}

/**
 * 上下文目标选择菜单：主代理 + 各子任务上下文。
 *
 * 由「上下文统计」文字长按触发，与旧进度环的长按语义一致。
 */
@Composable
internal fun ContextTargetSelectorMenu(
    telemetry: AgentContextTelemetry,
    expanded: Boolean,
    onDismiss: () -> Unit,
    popupMaxHeight: () -> Dp,
) {
    val context = LocalContext.current
    val targets =
        listOf("${telemetry.mainModelName.ifBlank { stringResource(R.string.acm_main_agent) }}（${stringResource(R.string.acm_main_agent)}）") +
            telemetry.children.map { "${it.contextLabel(context)} · ${it.contextStatusLabel(context)}" }
    val selectedIndex = if (telemetry.selectedTaskId == null) 0 else {
        telemetry.children.indexOfFirst { it.taskId == telemetry.selectedTaskId } + 1
    }
    EtaMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = popupMaxHeight()),
    ) {
        // 组内容可滚动：弹层高度受限，条目多时在组内滚动而不是把弹层撑高。
        EtaMenuGroup(
            index = 0,
            count = 1,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            MenuDefaults.DropdownMenuGroupLabel { M3Text(text = stringResource(R.string.acm_context_stats)) }
            targets.forEachIndexed { index, label ->
                SelectableDropdownMenuItem(
                    selected = index == selectedIndex,
                    onClick = {
                        telemetry.onTaskSelected?.invoke(if (index == 0) null else telemetry.children[index - 1].taskId)
                        onDismiss()
                    },
                    text = {
                        M3Text(
                            text = label,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    shapes = MenuDefaults.itemShape(index, targets.size),
                    // 选中项带 Check 前导图标，与「请求接口」那个下拉同款。
                    selectedLeadingIcon = {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                )
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
        val containerColor = MaterialTheme.colorScheme.primaryContainer
        val iconTint = MaterialTheme.colorScheme.onPrimaryContainer
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
