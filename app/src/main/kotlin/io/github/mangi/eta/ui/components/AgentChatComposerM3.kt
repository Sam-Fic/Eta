package io.github.mangi.eta.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ComposerQueueStore
import io.github.mangi.eta.ui.adaptive.EtaConnectedGroupRow
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import java.time.LocalTime
import java.util.Calendar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Material 3 Expressive 输入区。
 *
 * 全部可点区域均为官方 Material 3 组件（`FilledTonalIconButton` / `FilledIconButton` /
 * `OutlinedIconButton` / `IconButton` / `ToggleButton` + `ButtonGroupDefaults`，菜单用官方
 * `DropdownMenu` + `DropdownMenuItem`），不再用 `Box + clip + background + clickable` 手搓。
 * 尺寸一律取官方令牌（`IconButtonDefaults` 的 ContainerSize / IconSize、
 * `ButtonDefaults.MinHeight`、`ProgressIndicatorDefaults.CircularStrokeWidth`），按钮统一为
 * Small 档（40dp），动效曲线取 `MaterialTheme.motionScheme`，颜色只引用 `MaterialTheme.colorScheme` 角色。
 *
 * 结构（自上而下）：
 * 1. 附件连体按钮组（左侧图片图标 + 右侧箭头菜单：定时发送 / 保存草稿 / 分享）、
 *    助手与模型组成的相连按钮组、思考档位、上下文用量进度环。
 * 2. 语音／生成指示 + 胶囊描边输入框 + 发送按钮。
 * 生成中时进度环变为波浪形，发送按钮变为色调停止按钮。
 */
@Composable
internal fun Material3ComposerBody(
    textFieldState: TextFieldState,
    focusRequester: FocusRequester,
    interactionBlocked: Boolean,
    isEditingMessage: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean,
    isCompressingContext: Boolean,
    canContinueDisconnected: Boolean,
    contextSendBlocked: Boolean,
    canSend: Boolean,
    usage: AgentContextUsageUi,
    popupMaxHeight: Dp,
    inputContainerTopPx: Int,
    onPositionedTopPx: (Int) -> Unit,
    conversationId: String?,
    assistantId: String,
    modelPickerState: AgentModelPickerUiState,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    gptSpeedMode: GptSpeedMode,
    gptSpeedSupported: Boolean,
    voiceState: VoiceModeState,
    showMorphLoading: Boolean,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi,
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
    onCancelMessageEdit: () -> Unit,
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCycleGptSpeedMode: () -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onAbortPausedRun: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attachMenuExpanded by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }

    val draftText = textFieldState.text.toString()
    val hasContent = draftText.isNotBlank() ||
        pendingImages.isNotEmpty() ||
        pendingFileReferences.isNotEmpty() ||
        conversationMentions.pending.isNotEmpty()
    val sendMode = resolveChatComposerSendMode(
        isStreaming = isStreaming,
        isPaused = isPaused,
        canContinueDisconnected = canContinueDisconnected,
        hasSteerContent = hasContent,
        canStartNewSend = canSend,
        isCompressingContext = isCompressingContext,
    )
    val conversationKey = conversationId.orEmpty()

    // 重新进入该会话时，如果输入框还是空的，就恢复上次「保存草稿」存下的内容。
    LaunchedEffect(conversationKey) {
        if (conversationKey.isBlank()) return@LaunchedEffect
        if (textFieldState.text.isNotBlank()) return@LaunchedEffect
        val saved = ComposerQueueStore.draftFlow(context, conversationKey).first()
        if (saved.isNotBlank() && textFieldState.text.isBlank()) {
            textFieldState.setTextAndPlaceCursorAtEnd(saved)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onPositionedTopPx(it.positionInWindow().y.toInt()) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (voiceState.active || voiceState.error != null) {
            VoiceModeStatusPanel(state = voiceState, onStop = onStopVoiceMode)
        }

        // ── 第一行：附件拆分按钮 / 助手 + 模型相连按钮组 / 思考 / 上下文进度环 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 附件按钮常驻：编辑态不再被取消按钮替换（取消按钮移到第二行语音位）。
            M3AttachmentSplitButton(
                expanded = attachMenuExpanded,
                onExpandedChange = { attachMenuExpanded = it },
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onScheduleSend = { showScheduleDialog = true },
                onSaveDraft = {
                    scope.launch {
                        ComposerQueueStore.saveDraft(context, conversationKey, draftText)
                        Toast.makeText(
                            context,
                            context.getString(R.string.composer_draft_saved),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onShare = { shareDraft(context, draftText) },
            )

            EtaConnectedGroupRow {
                AssistantPickerButton(
                    enabled = !isStreaming || isPaused,
                    selectedAssistantId = assistantId,
                    onEditAssistant = onEditAssistant,
                    onAssistantSelected = onAssistantSelected,
                    connectedShapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    containerSize = IconButtonDefaults.smallContainerSize().width,
                )
                AgentModelPickerButton(
                    conversationId = conversationId,
                    collaborationTaskRunning = isStreaming || isPaused || isCompressingContext,
                    state = modelPickerState,
                    isStreaming = isStreaming,
                    isPaused = isPaused,
                    popupAnchorTopPx = inputContainerTopPx,
                    popupMaxHeight = popupMaxHeight,
                    onModelSelected = onModelSelected,
                    connectedShapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    containerSize = IconButtonDefaults.smallContainerSize().width,
                )
            }

            if (!isEditingMessage &&
                (availableReasoningEfforts.isNotEmpty() || gptSpeedSupported)
            ) {
                ThinkingEffortChip(
                    effort = reasoningEffort,
                    options = availableReasoningEfforts,
                    enabled = (!isStreaming || isPaused) && !modelPickerState.isChanging,
                    gptSpeedMode = gptSpeedMode,
                    gptSpeedSupported = gptSpeedSupported,
                    onCycleGptSpeedMode = onCycleGptSpeedMode,
                    onEffortChange = onReasoningEffortChange,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 上下文用量：普通为圆形进度，模型回复中改用波浪样式。
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

        // ── 第二行：语音／生成指示 + 胶囊输入框 + 发送按钮 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
                contentAlignment = Alignment.Center,
            ) {
                if (isEditingMessage) {
                    // 编辑态：取消按钮占用原语音按钮的位置（与发送按钮对称，同为 40dp 档），
                    // 第一行的附件按钮恢复常驻。
                    M3ComposerCancelButton(onCancelMessageEdit)
                } else {
                    VoiceEntryButton(
                        textFieldState = textFieldState,
                        showGeneration = showMorphLoading,
                        interactionBlocked = interactionBlocked,
                        resetKey = isStreaming to isEditingMessage,
                        voiceState = voiceState,
                        onStartVoiceMode = onStartVoiceMode,
                        onStopVoiceMode = onStopVoiceMode,
                        // 设计稿：第二行左侧是 call 图标的色调圆形按钮。
                        icon = Icons.Rounded.Call,
                    )
                }
            }

            OutlinedTextField(
                state = textFieldState,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ButtonDefaults.MediumContainerHeight)
                    .focusRequester(focusRequester)
                    .focusProperties { canFocus = !interactionBlocked },
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = {
                    Text(
                        text = if (isStreaming) {
                            stringResource(R.string.chat_eta_working)
                        } else {
                            stringResource(R.string.chat_input_hint)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                lineLimits = TextFieldLineLimits.MultiLine(
                    minHeightInLines = 1,
                    maxHeightInLines = 6,
                ),
                // 官方主题形状令牌：extraLarge（28dp），在 56dp 最小高度下即为胶囊。
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    // M3 官方填充文本框容器色令牌：surfaceContainerHighest，避免透明底。
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )

            // 发送按钮与语音按钮同套一层最小高度容器并在其中居中。输入框最小高是
            // ButtonDefaults.MediumContainerHeight(56dp)，按钮本体是 smallContainerSize(40dp)，
            // 行内 Bottom 对齐会让 40dp 按钮贴行底，圆心比输入框圆心低 8dp（视觉上不居中）。
            // 外包 56dp 居中后单行态与输入框完全同心；输入框长高后仍随 Bottom 贴住行底区域，
            // 与语音按钮行为一致。
            Box(
                modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
                contentAlignment = Alignment.Center,
            ) {
                M3ComposerSendButton(
                    sendMode = sendMode,
                    isPaused = isPaused,
                    onSend = {
                        val text = textFieldState.text.toString()
                        // 发送后草稿失效，清掉本机保存的副本。
                        scope.launch {
                            ComposerQueueStore.saveDraft(context, conversationKey, "")
                        }
                        onSubmit(text)
                    },
                    onStop = onStop,
                    onContinue = onContinue,
                    onAbortPausedRun = onAbortPausedRun,
                    onBlocked = {
                        Toast.makeText(
                            context,
                            context.getString(R.string.context_window_send_blocked),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }

    if (showScheduleDialog) {
        M3ScheduleSendDialog(
            onDismiss = { showScheduleDialog = false },
            onConfirm = { dueAtMillis ->
                showScheduleDialog = false
                ScheduledSendAlarm.schedule(context, conversationKey, draftText, dueAtMillis)
                scope.launch {
                    ComposerQueueStore.scheduleSend(context, conversationKey, draftText, dueAtMillis)
                }
                val label = java.text.DateFormat.getTimeInstance(
                    java.text.DateFormat.SHORT,
                ).format(java.util.Date(dueAtMillis))
                Toast.makeText(
                    context,
                    context.getString(R.string.composer_scheduled_toast, label),
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }
}

/**
 * 编辑态下的“取消编辑”按钮：占据第二行原语音按钮的位置（与发送按钮对称），官方
 * 色调图标按钮（尺寸/图标/水波纹/按压形变均为官方默认）。
 */
@Composable
private fun M3ComposerCancelButton(onClick: () -> Unit) {
    val view = LocalView.current
    FilledTonalIconButton(
        onClick = {
            TouchHaptics.click(view)
            onClick()
        },
        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.ui_cancel_edit_c698df),
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
}

/**
 * 附件连体按钮组：官方 `ToggleButton` + `ButtonGroupDefaults` 连体形状（与助手/模型按钮组同套写法）。
 *
 * 左段点击直接打开系统图片选择器；右段箭头展开菜单：定时发送 / 存草稿 / 分享，
 * 以及视频 / 文件 / 文件夹 / 输入路径等附件项（动作由共享的 `rememberAgentAttachmentActions` 提供）。
 * 段间距（`ButtonGroupDefaults.ConnectedSpaceBetween`）、容器尺寸（官方 Small 图标按钮 40dp）、
 * 按压形变（官方连体形状组）与箭头旋转动效（`MaterialTheme.motionScheme`）全部由官方令牌提供；
 * 菜单用官方 `DropdownMenu` + `DropdownMenuItem`，不覆盖任何形状/容器色/字阶。
 */
@Composable
private fun M3AttachmentSplitButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onScheduleSend: () -> Unit,
    onSaveDraft: () -> Unit,
    onShare: () -> Unit,
) {
    val view = LocalView.current
    val actions = rememberAgentAttachmentActions(
        onAttachImage = onAttachImage,
        onAttachVideo = onAttachVideo,
        onAttachFiles = onAttachFiles,
        onAttachFolder = onAttachFolder,
        onAttachFilePath = onAttachFilePath,
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(),
        label = "m3-composer-split-arrow",
    )
    Box {
        EtaConnectedGroupRow {
            // 左段：点击直接打开系统图片选择器。
            ChatInputNonFocusableIconButton(
                onClick = {
                    TouchHaptics.click(view)
                    actions.pickImage()
                },
                contentDescription = stringResource(R.string.attachment_image),
                connectedShapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                containerSize = IconButtonDefaults.smallContainerSize().width,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = null,
                    modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                )
            }
            // 右段：箭头，展开附件与发送选项菜单。
            ChatInputNonFocusableIconButton(
                onClick = {
                    TouchHaptics.click(view)
                    onExpandedChange(!expanded)
                },
                connectedShapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                containerSize = IconButtonDefaults.smallContainerSize().width,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(IconButtonDefaults.smallIconSize)
                        .graphicsLayer { rotationZ = arrowRotation },
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_schedule_send)) },
                leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                shape = etaOfficialMenuItemShape(0, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
                    TouchHaptics.click(view)
                    onExpandedChange(false)
                    onScheduleSend()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_save_draft)) },
                leadingIcon = { Icon(Icons.Rounded.Save, contentDescription = null) },
                shape = etaOfficialMenuItemShape(1, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
                    TouchHaptics.click(view)
                    onExpandedChange(false)
                    onSaveDraft()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_share)) },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                shape = etaOfficialMenuItemShape(2, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
                    TouchHaptics.click(view)
                    onExpandedChange(false)
                    onShare()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attachment_video)) },
                leadingIcon = { Icon(Icons.Rounded.Videocam, contentDescription = null) },
                shape = etaOfficialMenuItemShape(3, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
                    TouchHaptics.click(view)
                    onExpandedChange(false)
                    actions.pickVideo()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attachment_file)) },
                leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                shape = etaOfficialMenuItemShape(4, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
                    TouchHaptics.click(view)
                    onExpandedChange(false)
                    actions.pickFiles()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attachment_folder)) },
                leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                shape = etaOfficialMenuItemShape(5, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
                    TouchHaptics.click(view)
                    onExpandedChange(false)
                    actions.pickFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attachment_enter_path)) },
                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                shape = etaOfficialMenuItemShape(6, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
                    TouchHaptics.click(view)
                    onExpandedChange(false)
                    actions.inputPath()
                },
            )
        }
    }
}

/** 附件菜单条目数（定时发送 / 存草稿 / 分享 + 视频 / 文件 / 文件夹 / 输入路径）。 */
private const val ATTACHMENT_MENU_ITEM_COUNT = 7

/**
 * 发送 / 停止 / 继续 按钮：按状态直接选用官方图标按钮
 * （停止＝`FilledTonalIconButton`、发送/继续＝`FilledIconButton`、
 * 上下文已满＝`OutlinedIconButton`、空闲＝禁用的 `IconButton`）。
 *
 * 形状、容器色、内容色、水波纹与按压形变全部使用各组件的官方默认值，不传 `shape` / `colors`。
 * 官方图标按钮没有长按槽位（M3 仅 `ListItem` 暴露 `onLongClick`），因此“长按中止暂停中的运行”
 * 通过官方 `Modifier.semantics` 的 `onLongClick` 动作提供。
 */
@Composable
private fun M3ComposerSendButton(
    sendMode: String,
    isPaused: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onAbortPausedRun: () -> Unit,
    onBlocked: () -> Unit,
) {
    val view = LocalView.current
    val description = when (sendMode) {
        "stop" -> stringResource(R.string.chat_stop)
        "continue" -> stringResource(R.string.chat_continue)
        else -> stringResource(R.string.chat_send)
    }
    val content: @Composable () -> Unit = {
        Icon(
            imageVector = when (sendMode) {
                "stop" -> Icons.Rounded.StopCircle
                "continue" -> Icons.Rounded.PlayArrow
                else -> Icons.AutoMirrored.Rounded.Send
            },
            contentDescription = description,
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
    val click: () -> Unit = {
        TouchHaptics.click(view)
        when (sendMode) {
            "blocked" -> onBlocked()
            "stop" -> onStop()
            "continue" -> onContinue()
            "send" -> onSend()
        }
    }
    val abortModifier = if (sendMode == "stop" || (sendMode == "continue" && isPaused)) {
        Modifier.semantics {
            onLongClick(label = description) {
                TouchHaptics.longPress(view)
                onAbortPausedRun()
                true
            }
        }
    } else {
        Modifier
    }
    val modifier = Modifier
        .size(IconButtonDefaults.smallContainerSize())
        .then(abortModifier)

    when (sendMode) {
        "stop" -> FilledTonalIconButton(onClick = click, modifier = modifier, content = content)
        "continue", "send" -> FilledIconButton(onClick = click, modifier = modifier, content = content)
        "blocked" -> OutlinedIconButton(onClick = click, modifier = modifier, content = content)
        else -> IconButton(onClick = click, modifier = modifier, enabled = false, content = content)
    }
}

/** 选择发送时间的对话框（官方 `AlertDialog` + `TextButton` + `TimePicker`）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun M3ScheduleSendDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val now = remember { LocalTime.now() }
    val timeState = rememberTimePickerState(
        initialHour = now.hour,
        initialMinute = now.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.composer_schedule_title)) },
        text = { TimePicker(state = timeState) },
        confirmButton = {
            TextButton(
                onClick = {
                    val calendar = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, timeState.hour)
                        set(Calendar.MINUTE, timeState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) {
                            add(Calendar.DAY_OF_MONTH, 1)
                        }
                    }
                    onConfirm(calendar.timeInMillis)
                },
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 通过系统分享面板分享当前草稿。 */
private fun shareDraft(context: Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, null))
    }
}