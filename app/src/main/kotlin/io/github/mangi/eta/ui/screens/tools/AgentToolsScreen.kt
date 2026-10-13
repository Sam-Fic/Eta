package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.projectToolGroups
import androidx.compose.material3.Text as M3Text
import top.yukonga.miuix.kmp.basic.TabRow

private object ToolsMetrics {
    val GridHorizontalPadding = 20.dp
    val GridGap = 12.dp
}

@Composable
fun AgentToolsScreen(
    state: AgentToolsUiState,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val capabilities = rememberDeviceCapabilities()
    var showAll by rememberSaveable { mutableStateOf(false) }
    val currentListState = rememberLazyListState()
    val allListState = rememberLazyListState()
    val groups = projectToolGroups(state.groups, showAll, capabilities.root.isGranted, capabilities.tools.colorOs)
    EtaScaffoldPage(
        title = stringResource(R.string.ui_tool_ability_9f0f80),
        onBack = { onAction(AgentToolsAction.NavigateBack) },
        modifier = modifier,
        listState = if (showAll) allListState else currentListState,
    ) {
        item(key = "capability-view") {
            ToolsSegmentTabs(
                tabs = listOf(
                    stringResource(R.string.capability_current_device),
                    stringResource(R.string.capability_all),
                ),
                selectedIndex = if (showAll) 1 else 0,
                onSelect = { showAll = it == 1 },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        groups.forEach { group ->
            item(key = "${group.id}-title") {
                EtaSectionTitle(text = group.title)
            }
            items(
                items = group.tools.chunked(2),
                key = { row -> "${group.id}-${row.joinToString(separator = "-") { it.id }}" },
            ) { row ->
                ToolGridRow(
                    tools = row,
                    rootGranted = capabilities.root.isGranted,
                    capabilities = capabilities.tools,
                    onAction = onAction,
                )
            }
        }
    }
}

/**
 * 工具页顶部分段切换。
 *
 * Material 3 走官方单选分段按钮 [SingleChoiceSegmentedButtonRow] + [SegmentedButton]
 * （形状取官方 `SegmentedButtonDefaults.itemShape`，配色/行高/字阶全部使用官方默认值）；
 * MIUI X 保留原有 [TabRow]。
 */
@Composable
private fun ToolsSegmentTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isMaterial3Theme()) {
        SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, title ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                ) {
                    M3Text(text = title)
                }
            }
        }
    } else {
        TabRow(
            tabs = tabs,
            selectedTabIndex = selectedIndex,
            onTabSelected = onSelect,
            modifier = modifier,
        )
    }
}

@Composable
private fun ToolGridRow(
    tools: List<ToolItemUi>,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToolsMetrics.GridHorizontalPadding)
            .padding(bottom = ToolsMetrics.GridGap),
    ) {
        val useSingleColumn = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f
        if (useSingleColumn) {
            Column(verticalArrangement = Arrangement.spacedBy(ToolsMetrics.GridGap)) {
                tools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        rootGranted = rootGranted,
                        capabilities = capabilities,
                        onAction = onAction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.GridGap),
            ) {
                tools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        rootGranted = rootGranted,
                        capabilities = capabilities,
                        onAction = onAction,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (tools.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
