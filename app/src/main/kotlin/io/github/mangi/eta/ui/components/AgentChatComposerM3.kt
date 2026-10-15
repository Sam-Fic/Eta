package io.github.mangi.eta.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceModePhase
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ComposerQueueStore
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
 * Material 3 Expressive 输入区：官方浮动工具栏（Floating Toolbar）里的单行布局。
 *
 * 结构（自左向右）：
 * 1. 「+」主操作（官方 `FilledIconButton`，primary／onPrimary）→ 附件与发送选项菜单
 *    （定时发送 / 保存草稿 / 分享、视频 / 文件 / 文件夹 / 输入路径）。
 * 2. 图库（官方 Standard 图标按钮，`Icons.Rounded.PhotoLibrary`）→ 直接打开系统图片选择器。
 * 3. 「助手与模型」二合一入口（官方 Standard 图标按钮）→ 合并选择弹窗
 *    （助手 / 模型两个页签，底部为思考档位与上下文用量）。
 * 4. 胶囊输入框（官方 Filled 文本框，`surfaceContainer` 容器、固定 `extraLarge` 28dp 圆角），
 *    单行时 56dp 高即胶囊；多行时是圆角矩形（圆角不变），语音 / 发送按钮固定在右下角，
 *    与内层圆角保持同心（端距 8dp = 28 − 20）。有输入内容（文字 / 图片 / 文件 / 引用）时
 *    左侧动作组按官方 Expressive 弹簧规格收起、输入框展开占满，发送 / 停止按钮替代语音按钮的位置。
 *
 * 同心圆角：内层半径 = 外层半径 − 间距。外层固定 36dp（28 + 8，单行 72dp 高时即胶囊），
 * 内层固定 28dp，尾部 40dp 圆钮端距 8dp，三者两两满足规则；不用官方 CornerFull，
 * 否则输入框长高后外层会被拉成巨型胶囊、内外圆角失去同心关系。
 *
 * 整个工具栏容器、容器色（官方 vibrant 变体：`primaryContainer` / `onPrimaryContainer`）、
 * 高度、内边距与进出场弹簧动效全部取官方 `HorizontalFloatingToolbar` 默认令牌；可点区域一律为官方 Material 3 组件
 * （`FilledIconButton` / `IconButton` / `FilledTonalIconButton` / `OutlinedIconButton`），
 * 菜单用官方 `DropdownMenu` + `DropdownMenuItem`，尺寸取 `IconButtonDefaults` 令牌，
 * 进出场动效一律取 `MaterialTheme.motionScheme` 的 Expressive 弹簧规格。
 *
 * 编辑态：左侧取消按钮替代整组入口，尾部仍为发送按钮。
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
    var showPickerSheet by remember { mutableStateOf(false) }
    val actions = rememberAgentAttachmentActions(
        onAttachImage = onAttachImage,
        onAttachVideo = onAttachVideo,
        onAttachFiles = onAttachFiles,
        onAttachFolder = onAttachFolder,
        onAttachFilePath = onAttachFilePath,
    )

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
    // 生成 / 压缩中不再允许改助手与模型（与旧按钮组相同的可用性规则）。
    val pickerEnabled = (!isStreaming || isPaused) && !isCompressingContext

    // 重新进入该会话时，如果输入框还是空的，就恢复上次「保存草稿」存下的内容。
    LaunchedEffect(conversationKey) {
        if (conversationKey.isBlank()) return@LaunchedEffect
        if (textFieldState.text.isNotBlank()) return@LaunchedEffect
        val saved = ComposerQueueStore.draftFlow(context, conversationKey).first()
        if (saved.isNotBlank() && textFieldState.text.isBlank()) {
            textFieldState.setTextAndPlaceCursorAtEnd(saved)
        }
    }
    // 生成开始或进入编辑态时收起选择弹窗，避免弹窗内容与当前状态脱节。
    LaunchedEffect(pickerEnabled, isEditingMessage) {
        if (!pickerEnabled || isEditingMessage) showPickerSheet = false
    }
    // 输入栏进出场动效统一取官方 Expressive 弹簧规格（与输入区其它动效同一来源）。
    val enterSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val exitSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val growSpec: FiniteAnimationSpec<IntSize> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val shrinkSpec: FiniteAnimationSpec<IntSize> = MaterialTheme.motionScheme.defaultSpatialSpec()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onPositionedTopPx(it.positionInWindow().y.toInt()) },
    ) {
        val voiceActive = voiceState.active || voiceState.error != null
        HorizontalFloatingToolbar(
            expanded = true,
            // 官方 vibrant 变体：容器 primaryContainer、内容 onPrimaryContainer
            // （与参考稿“淡色底 + 深色图标”的关系一致）。
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            // 同心圆角：外层固定半径 = 内层 28dp（extraLarge）+ 8dp 间距 = 36dp。
            // 不能用官方 CornerFull：输入框多行长高后 CornerFull 会把外壳拉成巨型胶囊，
            // 内外圆角就不再满足“半径差 = 间距”。单行（72dp 高）时 36dp 恰是胶囊，外观不变。
            shape = RoundedCornerShape(ComposerOuterCornerRadius),
            // 关键：官方 minimumInteractiveBalancedPadding 只在“两侧都没有 leading/trailing
            // 内容”时才生效；它会读取内容里 MinimumInteractive*AlignmentLine（含位置偏移的大值）
            // 当补偿内边距，把整条工具栏撑高——空输入时被撑到 ~350dp、输入中无动作组时更夸张到整屏。
            // 官方本意是给 IconButton 组成的内容补平衡留白，本输入区布局完全显式（间距 + weight），
            // 因此显式传一个空的 leadingContent，让这组补偿归零（官方参数语义，非 hack）。
            leadingContent = {},
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 左侧动作组：语音进行中或已有内容时整组收起（官方弹簧收起，胶囊随之变宽）；
            // 编辑态改由取消按钮占据该位置（语音与编辑互斥，此时取消按钮直接让位）。
            if (!voiceActive) {
                if (isEditingMessage) {
                    // 编辑态：取消按钮占据左侧入口位置（与发送按钮对称），尾部仍为发送按钮。
                    Spacer(modifier = Modifier.width(ComposerConcentricGap))
                    M3ComposerCancelButton(
                        onClick = onCancelMessageEdit,
                        // 官方工具栏的内容 Row 是顶对齐（Row 默认 Alignment.Top），
                        // 40dp 按钮相对 56dp 输入框会高出 8dp，必须显式竖直居中。
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    Spacer(modifier = Modifier.width(ComposerToolbarGap))
                } else {
                    // 有输入内容（文字 / 图片 / 文件 / 引用）时左侧动作组收起，输入框展开占满，
                    // 尾部语音按钮同步让位给发送按钮；清空后按官方弹簧规格展开回来。
                    AnimatedVisibility(
                        visible = !hasContent,
                        // 同上：与输入框竖直居中，避免整组按钮偏高。
                        modifier = Modifier.align(Alignment.CenterVertically),
                        enter = fadeIn(enterSpec) +
                            expandHorizontally(growSpec, expandFrom = Alignment.Start),
                        exit = fadeOut(exitSpec) +
                            shrinkHorizontally(shrinkSpec, shrinkTowards = Alignment.Start),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 组首间距：第一个 40dp 圆形按钮距工具栏左端 = 官方内容内边距 8dp
                            // + 本间距 8dp = 16dp，与圆钮到上下的间距（(72 − 40)/2 = 16dp）相等，
                            // 即 36dp 外层半径 − 20dp 圆钮半径 = 16dp 的同心间距——端盖才是同心的；
                            // 只有官方内边距时圆钮会贴左端 8dp，与上下 16dp 不同心。
                            Spacer(modifier = Modifier.width(ComposerConcentricGap))
                            M3ComposerAddButton(
                                actions = actions,
                                expanded = attachMenuExpanded,
                                onExpandedChange = { attachMenuExpanded = it },
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
                            Spacer(modifier = Modifier.width(ComposerToolbarGap))
                            M3ComposerPickImageButton(actions = actions)
                            Spacer(modifier = Modifier.width(ComposerToolbarGap))
                            M3ComposerPickerButton(
                                enabled = pickerEnabled,
                                collaborationTaskRunning = isStreaming || isPaused || isCompressingContext,
                                onClick = { showPickerSheet = true },
                            )
                            // 组尾间距放进动画组：收起后输入框左右各贴工具栏 8dp 内边距
                            // （与上下间距一致），两个胶囊端盖保持同心。
                            Spacer(modifier = Modifier.width(ComposerToolbarGap))
                        }
                    }
                }
            }

            // 胶囊内容：正常输入框 ⇄ 聆听条（语音进行中）。过渡与“输入内容时动作组收起”同款：
            // 横向展开 / 收起 + 淡入淡出（不用缩放交叉淡化）——动作组向左收起时，聆听条以右端为锚
            // 向左展开填满；结束语音时反向，与动作组从左侧展开形成连续的“胶囊变宽”。
            AnimatedContent(
                targetState = voiceActive,
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    (fadeIn(enterSpec) + expandHorizontally(growSpec, expandFrom = Alignment.End))
                        .togetherWith(
                            fadeOut(exitSpec) +
                                shrinkHorizontally(shrinkSpec, shrinkTowards = Alignment.End),
                        )
                },
                label = "composer_pill",
            ) { listening ->
                if (listening) {
                    // 聆听条：麦克风状态圆钮 + 实时转写与阶段文案 + 官方色调停止圆钮（参考稿第二图）。
                    M3VoiceBar(state = voiceState, onStop = onStopVoiceMode)
                } else {
                    M3ComposerTextField(
                        textFieldState = textFieldState,
                        focusRequester = focusRequester,
                        interactionBlocked = interactionBlocked,
                        isStreaming = isStreaming,
                        isPaused = isPaused,
                        isEditingMessage = isEditingMessage,
                        showMorphLoading = showMorphLoading,
                        voiceState = voiceState,
                        onStartVoiceMode = onStartVoiceMode,
                        onStopVoiceMode = onStopVoiceMode,
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
                        sendMode = sendMode,
                        hasContent = hasContent,
                    )
                }
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

    M3ComposerPickerSheet(
        show = showPickerSheet,
        onDismiss = { showPickerSheet = false },
        assistantId = assistantId,
        onEditAssistant = onEditAssistant,
        onAssistantSelected = onAssistantSelected,
        modelPickerState = modelPickerState,
        isStreaming = isStreaming,
        isPaused = isPaused,
        onModelSelected = onModelSelected,
        usage = usage,
        contextSendBlocked = contextSendBlocked,
        popupMaxHeight = popupMaxHeight,
        reasoningEffort = reasoningEffort,
        availableReasoningEfforts = availableReasoningEfforts,
        gptSpeedMode = gptSpeedMode,
        gptSpeedSupported = gptSpeedSupported,
        onReasoningEffortChange = onReasoningEffortChange,
        onCycleGptSpeedMode = onCycleGptSpeedMode,
    )
}

/** 工具栏内图标按钮之间的间距（官方浮动工具栏的容器间隔令牌值，4dp）。 */
private val ComposerToolbarGap = 4.dp

/**
 * 外层工具栏胶囊与内层输入框胶囊的同心间距（= 官方浮动工具栏内容内边距 8dp）。
 *
 * 同心规则：内层圆角半径 = 外层圆角半径 − 间距。外层取固定 36dp（见 [ComposerOuterCornerRadius]）、
 * 内层取官方 `extraLarge` 28dp，单行时外层 72dp 高 ⇒ 36dp 恰为胶囊；多行长高后两者半径不变，
 * 依旧满足 36 − 28 = 8dp；尾部 40dp 圆形按钮同理需要 8dp 端距。
 */
private val ComposerConcentricGap = 8.dp

/** 外层浮动工具栏的固定圆角半径：内层 28dp（官方 extraLarge）+ 同心间距 8dp。 */
private val ComposerOuterCornerRadius = 36.dp

/**
 * 胶囊输入框：官方 Filled 文本框（无 label，仅 placeholder，容器 `surfaceContainer`，
 * 指示线透明，圆角固定 28dp＝官方 `extraLarge`，不改大小），起始侧内嵌生成中的形变加载指示器；
 * 语音／发送按钮叠放在输入框右下角。
 *
 * 按钮位置：单行（56dp）时右下角与竖直居中重合，端距都是 (56 − 40)/2 = 8dp；
 * 多行长高后按钮固定在右下角，与内层 28dp 圆角保持同心（40dp 圆形半径 20dp，
 * 28 − 20 = 8dp 即为端距），文本仍由官方 trailingIcon 槽预留横向空间。
 */
@Composable
private fun M3ComposerTextField(
    textFieldState: TextFieldState,
    focusRequester: FocusRequester,
    interactionBlocked: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean,
    isEditingMessage: Boolean,
    showMorphLoading: Boolean,
    voiceState: VoiceModeState,
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onAbortPausedRun: () -> Unit,
    onBlocked: () -> Unit,
    sendMode: String,
    hasContent: Boolean,
) {
    // 有内容时尾部让位给发送按钮；没有内容但生成 / 压缩中时，尾部必须保留停止按钮。
    val trailingShowsSend = hasContent || sendMode != "idle"
    // 麦克风 ↔ 发送按钮的切换动效取官方 Expressive 弹簧规格。
    val fadeSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val scaleSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()
    Box(modifier = Modifier.fillMaxWidth()) {
        TextField(
            state = textFieldState,
            modifier = Modifier
                .fillMaxWidth()
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
            // 圆角固定为官方 extraLarge（28dp），单行 56dp 高即胶囊；多行时是圆角矩形，
            // 与外层固定 36dp 仍满足“半径差 = 间距”（36 − 28 = 8dp）。
            shape = MaterialTheme.shapes.extraLarge,
            // 生成中的形变加载指示器（“生成时显示加载指示”设置项）：放在胶囊内部起始侧，
            // 不占用尾部的语音 / 发送按钮位置。
            leadingIcon = if (showMorphLoading) {
                { ContainedMorphLoadingIndicator(indicatorSize = IconButtonDefaults.smallIconSize) }
            } else {
                null
            },
            // 尾部槽仅预留与按钮同宽的横向空间（48dp 触控区由官方槽自身保证），
            // 按钮本体在下方以右下角对齐叠放，多行长高时贴住右下角。
            trailingIcon = {
                Spacer(modifier = Modifier.size(IconButtonDefaults.smallContainerSize()))
            },
            colors = TextFieldDefaults.colors(
                // 内层胶囊取官方 surfaceContainer：与外层 primaryContainer 工具栏形成两层表面。
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                // 参考稿没有描边，指示线取官方透明值。
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // 同心端距：40dp 圆钮半径 20dp 与内层 28dp 圆角相差 8dp。
                .padding(end = ComposerConcentricGap, bottom = ComposerConcentricGap),
        ) {
            AnimatedContent(
                targetState = trailingShowsSend,
                transitionSpec = {
                    // 轻微缩放 + 淡入淡出的弹簧过渡（官方 Expressive Motion Scheme）。
                    (fadeIn(fadeSpec) + scaleIn(scaleSpec, initialScale = 0.8f))
                        .togetherWith(fadeOut(fadeSpec) + scaleOut(scaleSpec, targetScale = 0.8f))
                },
                label = "composer_trailing",
            ) { showsSend ->
                if (showsSend) {
                    M3ComposerSendButton(
                        sendMode = sendMode,
                        isPaused = isPaused,
                        onSend = onSend,
                        onStop = onStop,
                        onContinue = onContinue,
                        onAbortPausedRun = onAbortPausedRun,
                        onBlocked = onBlocked,
                    )
                } else {
                    VoiceEntryButton(
                        textFieldState = textFieldState,
                        showGeneration = showMorphLoading,
                        interactionBlocked = interactionBlocked,
                        resetKey = isStreaming to isEditingMessage,
                        voiceState = voiceState,
                        onStartVoiceMode = onStartVoiceMode,
                        onStopVoiceMode = onStopVoiceMode,
                        // 设计稿：尾部是 call 图标的填充圆形按钮，属于主要动作。
                        icon = Icons.Rounded.Call,
                        idleFilled = true,
                    )
                }
            }
        }
    }
}

/**
 * 语音进行中的“聆听条”（参考稿第二图）：vibrant 外胶囊里的单枚内胶囊——
 * 起始侧麦克风状态圆钮（指示，非按钮）、中间实时转写与阶段文案、末端官方色调停止圆钮。
 *
 * 几何与正常态一致：内胶囊固定 28dp 圆角、56dp 最小高；麦克风圆钮（半径 20dp）距端盖
 * 8dp（28 − 20 的同心间距）。听写时中间显示实时转写，无转写时显示阶段文案；
 * 通话模式图标为 GraphicEq，其余为 RecordVoiceOver。结束后由调用方切回正常输入区。
 */
@Composable
private fun M3VoiceBar(state: VoiceModeState, onStop: () -> Unit) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TextFieldDefaults.MinHeight)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = ComposerConcentricGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (state.mode == VoiceEntryMode.DOUBAO_DUPLEX) {
                        Icons.Rounded.GraphicEq
                    } else {
                        Icons.Rounded.RecordVoiceOver
                    },
                    contentDescription = null,
                    modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                    tint = if (state.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                )
            }
        }
        Text(
            text = state.transcript.takeIf { it.isNotBlank() } ?: voicePhaseText(state),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FilledTonalIconButton(
            onClick = {
                TouchHaptics.click(view)
                onStop()
            },
            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
            // 参考稿的粉色圆钮取官方 tertiaryContainer / onTertiaryContainer 角色。
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Rounded.Stop,
                contentDescription = stringResource(R.string.voice_mode_stop),
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
        }
    }
}

/** 语音阶段文案（自 VoiceModeStatusPanel 提取，供聆听条复用同一套措辞）。 */
@Composable
private fun voicePhaseText(state: VoiceModeState): String = when {
    state.error != null -> state.error
    state.phase == VoiceModePhase.Connecting -> stringResource(R.string.voice_mode_connecting)
    state.phase == VoiceModePhase.Listening -> stringResource(R.string.voice_mode_listening)
    state.phase == VoiceModePhase.Transcribing -> stringResource(R.string.voice_mode_transcribing)
    state.phase == VoiceModePhase.Thinking -> stringResource(R.string.voice_mode_thinking)
    state.phase == VoiceModePhase.Speaking -> stringResource(R.string.voice_mode_speaking)
    else -> stringResource(R.string.voice_mode_open)
}

/**
 * 编辑态下的“取消编辑”按钮：占据工具栏左侧入口位置（与发送按钮对称），官方
 * 色调图标按钮（尺寸/图标/水波纹/按压形变均为官方默认）。
 *
 * [modifier] 供调用方传入 `Modifier.align(Alignment.CenterVertically)`：
 * 官方工具栏内容 Row 是顶对齐，不显式居中会相对输入框偏高。
 */
@Composable
private fun M3ComposerCancelButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    FilledTonalIconButton(
        onClick = {
            TouchHaptics.click(view)
            onClick()
        },
        modifier = modifier.size(IconButtonDefaults.smallContainerSize()),
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.ui_cancel_edit_c698df),
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
}

/**
 * 「+」主操作按钮：官方 `FilledIconButton`（primary／onPrimary，官方默认色），
 * 点击展开附件与发送选项菜单：定时发送 / 存草稿 / 分享，以及视频 / 文件 / 文件夹 /
 * 输入路径等附件项（动作由共享的 `rememberAgentAttachmentActions` 提供）。
 * 菜单用官方 `DropdownMenu` + `DropdownMenuItem`，行形状取官方 `MenuDefaults` 令牌。
 */
@Composable
private fun M3ComposerAddButton(
    actions: AgentAttachmentActions,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onScheduleSend: () -> Unit,
    onSaveDraft: () -> Unit,
    onShare: () -> Unit,
) {
    Box {
        ChatInputNonFocusableIconButton(
            onClick = { onExpandedChange(!expanded) },
            contentDescription = stringResource(R.string.ui_add_attachment_dba9e8),
            containerColor = MaterialTheme.colorScheme.primary,
            containerSize = IconButtonDefaults.smallContainerSize().width,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
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
                    onExpandedChange(false)
                    onScheduleSend()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_save_draft)) },
                leadingIcon = { Icon(Icons.Rounded.Save, contentDescription = null) },
                shape = etaOfficialMenuItemShape(1, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
                    onExpandedChange(false)
                    onSaveDraft()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_share)) },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                shape = etaOfficialMenuItemShape(2, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
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
                    onExpandedChange(false)
                    actions.pickVideo()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attachment_file)) },
                leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                shape = etaOfficialMenuItemShape(4, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
                    onExpandedChange(false)
                    actions.pickFiles()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attachment_folder)) },
                leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                shape = etaOfficialMenuItemShape(5, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
                    onExpandedChange(false)
                    actions.pickFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attachment_enter_path)) },
                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                shape = etaOfficialMenuItemShape(6, ATTACHMENT_MENU_ITEM_COUNT),
                onClick = {
                    onExpandedChange(false)
                    actions.inputPath()
                },
            )
        }
    }
}

/**
 * 图库按钮：官方 Standard 图标按钮，点击直接打开系统图片选择器（插入图片）。
 *
 * 图标用 `Icons.Rounded.PhotoLibrary`（图库语义）：入口是从图库选图，不是拍照。
 */
@Composable
private fun M3ComposerPickImageButton(actions: AgentAttachmentActions) {
    ChatInputNonFocusableIconButton(
        onClick = { actions.pickImage() },
        contentDescription = stringResource(R.string.attachment_image),
        containerSize = IconButtonDefaults.smallContainerSize().width,
    ) {
        Icon(
            imageVector = Icons.Rounded.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
}

/**
 * 「助手与模型」二合一入口：官方 Standard 图标按钮，点击打开合并选择弹窗
 * （助手 / 模型两个页签，底部为思考档位与上下文用量）。
 *
 * 子代理编辑器里长按沿用原模型按钮的行为：打开「协作设置」对话框。
 */
@Composable
private fun M3ComposerPickerButton(
    enabled: Boolean,
    collaborationTaskRunning: Boolean,
    onClick: () -> Unit,
) {
    val owner = LocalConversationSubAgentEditor.current?.owner
    val popup = remember(owner) { OwnerBoundPopup(owner) }
    var dialogTicket by remember(popup) { mutableStateOf<Any?>(null) }
    val currentPopup by rememberUpdatedState(popup)
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
    val openCollaborationDialog: (() -> Unit)? = if (owner != null) {
        { dialogTicket = popup.open() }
    } else {
        null
    }
    ChatInputNonFocusableIconButton(
        onClick = onClick,
        contentDescription = stringResource(R.string.composer_picker_title),
        onLongClick = openCollaborationDialog,
        enabled = enabled,
        containerSize = IconButtonDefaults.smallContainerSize().width,
    ) {
        Icon(
            imageVector = Icons.Rounded.Layers,
            contentDescription = null,
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
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
