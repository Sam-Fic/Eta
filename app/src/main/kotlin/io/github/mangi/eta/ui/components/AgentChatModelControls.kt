package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup

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
    /** 子任务统计目标；主代理不重复写模型名，为 null。 */
    val targetLabel: String?,
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
        targetLabel = child?.contextTargetLabel(context),
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
            // 条目带 Check 的单选弹层：保持官方紧凑纵向边距。
            selectableMenu = true,
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
