package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.defaultExpandedModelProviderIds
import io.github.mangi.eta.ui.screens.assistants.AssistantDeleteDialog
import io.github.mangi.eta.ui.screens.assistants.AssistantSearchField
import io.github.mangi.eta.ui.screens.assistants.AssistantSwipeRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「助手与模型」二合一选择弹窗（官方 `ModalBottomSheet`，仅 Hidden↔Expanded 两态）。
 *
 * 结构：
 * 1. 标题。（关闭走 ModalBottomSheet 的下拉 / 点遮罩，不放关闭按钮。）
 * 2. 官方单选分段按钮的页签：助手 / 模型。
 *    - 助手页签：官方搜索框 + KernelSU 式分段列表（行左滑露出复制 / 编辑 / 删除）。
 *    - 模型页签：官方搜索框 + 服务商分组 + 官方 `SelectableDropdownMenuItem`（选中态为 RadioButton）。
 * 3. 底部固定两行「思考档位」「上下文用量」，复用输入区原有的两个控件（含各自弹窗 /
 *    长按语义），替代旧布局里的两个独立按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun M3ComposerPickerSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    assistantId: String,
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit,
    modelPickerState: AgentModelPickerUiState,
    isStreaming: Boolean,
    isPaused: Boolean,
    onModelSelected: (String, String) -> Unit,
    usage: AgentContextUsageUi,
    contextSendBlocked: Boolean,
    popupMaxHeight: () -> Dp,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    gptSpeedMode: GptSpeedMode,
    gptSpeedSupported: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCycleGptSpeedMode: () -> Unit,
) {
    if (!show) return
    var tabIndex by remember { mutableIntStateOf(0) }
    var expandedProviderIds by remember { mutableStateOf(emptySet<String>()) }
    // 打开弹窗 / 切到模型页签时，展开当前模型所在服务商（与旧模型菜单一致）。
    LaunchedEffect(show, tabIndex) {
        if (show && tabIndex == 1) {
            expandedProviderIds = defaultExpandedModelProviderIds(modelPickerState.selectedModel)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Text(
                text = stringResource(R.string.composer_picker_title),
                style = etaTextStyle(EtaType.Title),
            )
            val tabs = listOf(
                stringResource(R.string.assistant_list_title),
                stringResource(R.string.conversation_dock_models),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, title ->
                    SegmentedButton(
                        selected = index == tabIndex,
                        onClick = { tabIndex = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                    ) {
                        Text(text = title)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (tabIndex == 0) {
                    M3AssistantPickerTab(
                        assistantId = assistantId,
                        onOpen = { id ->
                            onDismiss()
                            onAssistantSelected(id)
                        },
                        onEdit = { id ->
                            onDismiss()
                            onEditAssistant(id)
                        },
                    )
                } else {
                    M3ModelPickerTab(
                        state = modelPickerState,
                        isStreaming = isStreaming,
                        isPaused = isPaused,
                        expandedProviderIds = expandedProviderIds,
                        onProviderExpandedChange = { providerId, expanded ->
                            expandedProviderIds = if (expanded) setOf(providerId) else emptySet()
                        },
                        onModelSelected = onModelSelected,
                    )
                }
            }
            HorizontalDivider()
            // 思考档位：沿用输入区原来的芯片（长按切 GPT 速度档位的语义保持不变）。
            if (availableReasoningEfforts.isNotEmpty() || gptSpeedSupported) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.composer_thinking_effort_label),
                        style = etaTextStyle(EtaType.Body),
                        modifier = Modifier.weight(1f),
                    )
                    ThinkingEffortChip(
                        effort = reasoningEffort,
                        options = availableReasoningEfforts,
                        // 模型切换（isChanging）期间 owner 会 no-op，禁用以免出现“假动画”。
                        enabled = (!isStreaming || isPaused) && !modelPickerState.isChanging,
                        gptSpeedMode = gptSpeedMode,
                        gptSpeedSupported = gptSpeedSupported,
                        onCycleGptSpeedMode = onCycleGptSpeedMode,
                        onEffortChange = onReasoningEffortChange,
                    )
                }
            }
            // 上下文用量：沿用输入区原来的进度环（单击详情 / 长按切换上下文目标）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.acm_context_stats),
                    style = etaTextStyle(EtaType.Body),
                    modifier = Modifier.weight(1f),
                )
                AgentContextUsageButton(
                    usage = usage,
                    popupMaxHeight = popupMaxHeight,
                    sendBlocked = contextSendBlocked,
                    containerSize = IconButtonDefaults.smallContainerSize().width,
                    ringSize = IconButtonDefaults.largeIconSize,
                    ringStrokeWidth = ProgressIndicatorDefaults.CircularStrokeWidth,
                    wavy = isStreaming && !isPaused,
                )
            }
        }
    }
}

/**
 * 助手页签：官方搜索框 + KernelSU 式分段列表；行左滑露出复制 / 编辑 / 删除，
 * 与独立助手选择弹窗的 Material 3 分支保持同一套交互。
 */
@Composable
private fun M3AssistantPickerTab(
    assistantId: String,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = assistantId.ifBlank { repositoryActiveId }
    var searchQuery by remember { mutableStateOf("") }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    val scope = rememberCoroutineScope()
    val filtered = remember(profiles, searchQuery) {
        val query = searchQuery.trim()
        profiles.filter { profile ->
            query.isBlank() || profile.name.contains(query, ignoreCase = true)
        }
    }
    AssistantSearchField(
        query = searchQuery,
        onQueryChange = { searchQuery = it },
        placeholder = stringResource(R.string.assistant_search),
    )
    Spacer(modifier = Modifier.height(12.dp))
    EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
        filtered.forEach { profile ->
            item(key = profile.id) {
                AssistantSwipeRow(
                    profile = profile,
                    selected = profile.id == activeId,
                    onOpen = { onOpen(profile.id) },
                    onCopy = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AssistantRepository.duplicate(profile.id)
                            }
                        }
                    },
                    onEdit = { onEdit(profile.id) },
                    onDelete = { deleteProfile = profile },
                )
            }
        }
    }
    if (filtered.isEmpty()) {
        Text(
            text = stringResource(R.string.assistant_empty),
            style = etaTextStyle(EtaType.Secondary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
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
 * 模型页签：复用旧模型菜单的官方内容（搜索框 + 服务商分组 + 单选模型项），
 * 选中模型时的可用性判断与旧入口一致（切换中或列表为空时不派发）。
 */
@Composable
private fun M3ModelPickerTab(
    state: AgentModelPickerUiState,
    isStreaming: Boolean,
    isPaused: Boolean,
    expandedProviderIds: Set<String>,
    onProviderExpandedChange: (String, Boolean) -> Unit,
    onModelSelected: (String, String) -> Unit,
) {
    var modelQuery by remember { mutableStateOf("") }
    val pickerAvailable = (!isStreaming || isPaused) && state.providerGroups.isNotEmpty()
    ModelPickerMenuContentM3(
        state = state,
        query = modelQuery,
        onQueryChange = { modelQuery = it },
        expandedProviderIds = expandedProviderIds,
        onProviderExpandedChange = onProviderExpandedChange,
        onModelSelected = { providerId, modelId ->
            if (!state.isChanging && pickerAvailable) onModelSelected(providerId, modelId)
        },
    )
}
