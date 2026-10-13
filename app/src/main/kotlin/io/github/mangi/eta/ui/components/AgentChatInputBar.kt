package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenuItem
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.mangi.eta.ui.model.ConversationMention
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.agent.voice.VoiceModePhase
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.config.Prefs
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.ui.screens.assistants.AssistantPickerDialog
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.liveContextUsage
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import io.github.mangi.eta.ui.model.SkillSlashMention
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import kotlin.math.roundToInt
import android.content.Intent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text as M3Text
import androidx.compose.ui.text.style.TextOverflow
import io.github.mangi.eta.data.repository.ComposerQueueStore
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaCircularProgress
import io.github.mangi.eta.ui.adaptive.EtaConnectedGroupRow
import io.github.mangi.eta.ui.adaptive.etaGptSpeedColor
import io.github.mangi.eta.ui.adaptive.EtaGroupPosition
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.etaConnectedShape
import io.github.mangi.eta.ui.adaptive.etaSplitLeadingShape
import io.github.mangi.eta.ui.adaptive.etaSplitTrailingShape
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val SendButtonVisualSize = ChatInputActionIconSize
private val SendIconSize = 16.dp
private val StopIconSize = 10.dp
private val ThinkingIconSize = 21.dp
/** MIUI X 分支的图标弱化透明度（与官方 StandardIconButtonTokens.DisabledOpacity 同值）。 */
private const val DisabledIconAlpha = 0.38f
private val InputContainerShape = RoundedCornerShape(EtaInputCornerRadius)

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AgentChatInputBar(
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    modelPickerState: AgentModelPickerUiState,
    history: List<AgentModelClient.ConversationMessage>,
    billedContextTokens: Int? = null,
    projectedContextTokens: Int? = null,
    billedHistoryTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    measuredContextTokens: Int? = null,
    contextDisplayPolicy: io.github.mangi.eta.ui.model.ContextDisplayPolicy = io.github.mangi.eta.ui.model.ContextDisplayPolicy(),
    billedOverheadTokens: Int? = null,
    activeRunContextWindow: Int? = null,
    autoCompressEnabled: Boolean,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    isCompressingContext: Boolean = false,
    showMorphLoading: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    isEditingMessage: Boolean,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    voiceState: VoiceModeState = VoiceModeState(),
    onStartVoiceMode: (VoiceEntryMode) -> Unit = {},
    onStopVoiceMode: () -> Unit = {},
    editHasLaterTurns: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    isPaused: Boolean = false,
    canContinueDisconnected: Boolean = false,
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit = {},
    /** GPT 速度档位（真值来自 owner 的 uiState，UI 不本地记忆）。 */
    gptSpeedMode: GptSpeedMode = GptSpeedMode.NORMAL,
    /** 长按速度图标时派发给 owner 的循环切换事件。 */
    onCycleGptSpeedMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Commit count only: no snapshot writes, no callbacks retained, no CPU-time claim.
    SideEffect { StreamPerformanceDiagnostics.record("chat.input.compose") }
    val focusRequester = remember { FocusRequester() }
    val textFieldState = draftField ?: rememberTextFieldState(initialText = input)
    var wasEditingMessage by remember { mutableStateOf(isEditingMessage) }
    val draftText = textFieldState.text.toString()
    val skillAppContext = LocalContext.current.applicationContext
    val assistantProfiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val availableSkills = remember(assistantId, skillAppContext, assistantProfiles, repositoryActiveId) {
        val profile = assistantProfiles.firstOrNull { it.id == assistantId }
            ?: assistantProfiles.firstOrNull { it.id == repositoryActiveId }
            ?: AssistantRepository.active()
        val enabledIds = profile.enabledSkillIds.toSet()
        SkillRuntime.createIndexService(skillAppContext)
            .listSkillsForManagement()
            .filter { it.installed && it.id in enabledIds }
            .filter { SkillCompatibilityChecker.evaluate(it).available }
    }
    val supportsVision = modelPickerState.selectedModel?.supportsVision == true
    val supportsVideo = modelPickerState.selectedModel?.supportsVideo == true
    // 只有“实际选中模型”的 modelId 是 GPT 速度模型、端点仍是文本 OpenAI 且非图像/视频生成时，
    // 思考图标才具备长按切速度语义。非 GPT 立即走普通原色分支，不给长按语义，也不做旋转/缩放。
    val gptSpeedSupported = modelPickerState.selectedModel?.gptSpeedSupported == true
    val liveUsage = remember(contextDisplayPolicy, billedContextTokens, modelPickerState.selectedModel,
        activeRunContextWindow) {
        // 圆环只显示真实云端回执：没有实测回执时不投影任何本地估算。
        liveContextUsage(
            billedContextTokens = billedContextTokens,
            selectedModel = modelPickerState.selectedModel,
            contextDisplayPolicy = contextDisplayPolicy,
            activeRunContextWindow = activeRunContextWindow,
        )
    }
    // The budget only serves manual send blocking. Skip all local history work
    // when the existing policy cannot block; the cloud-only ring stays independent.
    val contextSendBlocked = contextSendBlocked(measuredContextTokens, autoCompressEnabled) {
        val historyTokenCount = remember(history) { history.sumOf { io.github.mangi.eta.agent.model.AgentContextBudget.countMessage(it) } }
        val localHistoryTokenCount = remember(history, supportsVision, supportsVideo) {
            io.github.mangi.eta.agent.model.AgentRequestTokenEstimate.history(history, supportsVision, supportsVideo)
        }
        val sendBudget = remember(historyTokenCount, localHistoryTokenCount, draftText, pendingImages, pendingFileReferences,
            conversationMentions.pending, modelPickerState.selectedModel, billedContextTokens,
            billedHistoryTokens, requestOverheadTokens, billedOverheadTokens, projectedContextTokens, activeRunContextWindow) {
            io.github.mangi.eta.ui.model.compressionContextUsage(
                history = emptyList(), currentInput = draftText, pendingImages = pendingImages,
                selectedModel = modelPickerState.selectedModel, historyTokenCount = historyTokenCount,
                localHistoryTokenCount = localHistoryTokenCount,
                pendingFileReferences = pendingFileReferences, pendingConversationMentions = conversationMentions.pending,
                billedContextTokens = billedContextTokens, requestOverheadTokens = requestOverheadTokens,
                billedHistoryTokens = billedHistoryTokens, billedOverheadTokens = billedOverheadTokens,
                projectedContextTokens = projectedContextTokens,
                activeRunContextWindow = activeRunContextWindow,
            )
        }
        sendBudget
    }
    val compressionSendBlocked = isCompressingContext
    val canSend = !modelPickerState.isChanging && modelPickerState.selectedModel != null && !contextSendBlocked && !compressionSendBlocked && (
        textFieldState.text.isNotBlank() ||
            pendingImages.isNotEmpty() ||
            (pendingFileReferences.isNotEmpty() || conversationMentions.pending.isNotEmpty())
        )
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    var inputContainerTopPx by remember { mutableIntStateOf(0) }
    val thinkingPopupMaxHeight = with(density) {
        (inputContainerTopPx - statusBarTopPx).coerceAtLeast(0).toDp()
    }.minus(ChatInputPopupMargin * 2)
        .coerceAtLeast(ListPopupDefaults.MinPopupHeight)

    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val drawerBlocksIme = LocalConversationDrawerBlocksIme.current
    LaunchedEffect(isEditingMessage) {
        // 编辑模式切换由业务状态驱动；日常输入使用会话拥有的 TextFieldState，
        // 不让每个字符触发消息列表和 Markdown 重组。
        if (draftField == null && (isEditingMessage || wasEditingMessage)) {
            textFieldState.setTextAndPlaceCursorAtEnd(input)
        }
        if (isEditingMessage && !wasEditingMessage && !drawerBlocksIme) {
            showChatInputIme(focusRequester, keyboard, view)
        }
        wasEditingMessage = isEditingMessage
    }

    CompositionLocalProvider(LocalChatInputFocusRequester provides focusRequester) {
    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        // Material 3 Expressive：输入器所有进出出场动效统一取官方 motionScheme 规格；
        // miuix 主题族逐字保留原有 tween 时长。
        val m3 = isMaterial3Theme()
        val fadeEnterSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
            if (m3) MaterialTheme.motionScheme.defaultSpatialSpec() else tween(160)
        val fadeExitSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
            if (m3) MaterialTheme.motionScheme.defaultSpatialSpec() else tween(100)
        val growSpec: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntSize> =
            if (m3) MaterialTheme.motionScheme.defaultSpatialSpec() else tween(160)
        val shrinkSpec: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntSize> =
            if (m3) MaterialTheme.motionScheme.defaultSpatialSpec() else tween(140)
        val shrinkTallSpec: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntSize> =
            if (m3) MaterialTheme.motionScheme.defaultSpatialSpec() else tween(160)
        val mentionQuery = ConversationMention.queryAtCursor(draftText, textFieldState.selection.end)
            .takeIf { textFieldState.selection.collapsed }
        val skillQuery = SkillSlashMention.queryAtCursor(draftText, textFieldState.selection.end)
            .takeIf { textFieldState.selection.collapsed }
        SkillSlashMentionPanel(
            skills = availableSkills,
            query = skillQuery?.query,
            selectedIds = SkillSlashMention.selectedIds(draftText),
            onSelect = { skill ->
                val query = skillQuery
                if (query != null) {
                    val end = textFieldState.selection.end
                    textFieldState.edit {
                        replace(query.start, end, SkillSlashMention.token(skill.id))
                        selection = TextRange(query.start + SkillSlashMention.token(skill.id).length)
                    }
                    focusRequester.requestFocus()
                }
            },
        )
        ConversationMentionPanel(
            state = conversationMentions,
            query = mentionQuery?.query,
            onSelect = { id ->
                val query = mentionQuery
                if (query != null && conversationMentions.onAttach(id)) {
                    val end = textFieldState.selection.end
                    textFieldState.edit {
                        replace(query.start, end, "")
                        selection = TextRange(query.start)
                    }
                    focusRequester.requestFocus()
                }
            },
        )
        AnimatedVisibility(
            visible = pendingFileReferences.isNotEmpty(),
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkTallSpec),
        ) {
            PendingFileReferenceStrip(
                references = pendingFileReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkTallSpec),
        ) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = isEditingMessage,
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkSpec),
        ) {
            Text(
                text = if (editHasLaterTurns) {
                    stringResource(R.string.chat_edit_replace_later)
                } else {
                    stringResource(R.string.chat_edit_replace_message)
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = contextSendBlocked,
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkSpec),
        ) {
            Text(
                text = stringResource(R.string.context_window_send_blocked),
                style = MiuixTheme.textStyles.body2,
                color = StatusError,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = compressionSendBlocked,
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkSpec),
        ) {
            Text(
                text = stringResource(R.string.compress_conversation_in_progress),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }

        if (isMaterial3Theme()) {
            // Material 3 Expressive 输入区：附件拆分按钮 + 助手/模型相连按钮组 + 上下文进度环，
            // 下面是胶囊输入框与发送按钮。
            Material3ComposerBody(
                textFieldState = textFieldState,
                focusRequester = focusRequester,
                interactionBlocked = drawerBlocksIme,
                isEditingMessage = isEditingMessage,
                isStreaming = isStreaming,
                isPaused = isPaused,
                isCompressingContext = isCompressingContext,
                canContinueDisconnected = canContinueDisconnected,
                contextSendBlocked = contextSendBlocked,
                canSend = canSend,
                usage = liveUsage,
                popupMaxHeight = thinkingPopupMaxHeight,
                inputContainerTopPx = inputContainerTopPx,
                onPositionedTopPx = { inputContainerTopPx = it },
                conversationId = collaborationConversationId,
                assistantId = assistantId,
                modelPickerState = modelPickerState,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                gptSpeedMode = gptSpeedMode,
                gptSpeedSupported = gptSpeedSupported,
                voiceState = voiceState,
                showMorphLoading = showMorphLoading,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                conversationMentions = conversationMentions,
                onStartVoiceMode = onStartVoiceMode,
                onStopVoiceMode = onStopVoiceMode,
                onCancelMessageEdit = onCancelMessageEdit,
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onEditAssistant = onEditAssistant,
                onAssistantSelected = onAssistantSelected,
                onModelSelected = onModelSelected,
                onReasoningEffortChange = onReasoningEffortChange,
                onCycleGptSpeedMode = onCycleGptSpeedMode,
                onSubmit = onSubmit,
                onStop = onStop,
                onContinue = onContinue,
                onAbortPausedRun = onAbortPausedRun,
            )
        } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    inputContainerTopPx = coordinates.positionInWindow().y.roundToInt()
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InputContainerShape)
                    .background(etaInputContainerColor())
                    .border(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.30f),
                        shape = InputContainerShape,
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                if (voiceState.active || voiceState.error != null) {
                    VoiceModeStatusPanel(
                        state = voiceState,
                        onStop = onStopVoiceMode,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (textFieldState.text.isBlank()) {
                            Text(
                                text = if (isStreaming) stringResource(R.string.chat_eta_working) else stringResource(R.string.chat_input_hint),
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        BasicTextField(
                            state = textFieldState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .focusProperties { canFocus = !drawerBlocksIme },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            textStyle = TextStyle(
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = MiuixTheme.textStyles.body1.fontSize,
                                lineHeight = 22.sp,
                            ),
                            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                            lineLimits = TextFieldLineLimits.MultiLine(
                                minHeightInLines = 1,
                                maxHeightInLines = 6,
                            ),
                        )
                    }
                }

                ChatComposerActionRow(
                    actions = {
                        if (isEditingMessage) {
                            IconButton(
                                onClick = onCancelMessageEdit,
                                minWidth = ChatInputActionSize,
                                minHeight = ChatInputActionSize,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.ui_cancel_edit_c698df),
                                    modifier = Modifier.size(ChatInputActionIconSize),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        } else {
                            AgentAttachmentPickerButton(
                                onAttachImage = onAttachImage,
                                onAttachVideo = onAttachVideo,
                                onAttachFiles = onAttachFiles,
                                onAttachFolder = onAttachFolder,
                                onAttachFilePath = onAttachFilePath,
                            )

                            Spacer(modifier = Modifier.width(2.dp))

                            // GPT 速度模型即使没有思考 options 也保留图标以便长按切速度；
                            // 非 GPT 行为不变：仅在有思考 options 时出现。
                            if (availableReasoningEfforts.isNotEmpty() || gptSpeedSupported) {
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

                            Spacer(modifier = Modifier.width(2.dp))

                            AssistantPickerButton(
                                enabled = !isStreaming || isPaused,
                                selectedAssistantId = assistantId,
                                onEditAssistant = onEditAssistant,
                                onAssistantSelected = onAssistantSelected,
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Keep context details accessible even for an empty draft or an unknown limit.
                        AgentContextUsageButton(
                            // liveUsage is cloud-first; the separate sendBudget must never drive the ring.
                            usage = liveUsage,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            sendBlocked = contextSendBlocked,
                        )
                        Spacer(modifier = Modifier.width(2.dp))

                        AgentModelPickerButton(
                            conversationId = collaborationConversationId,
                            collaborationTaskRunning = isStreaming || isPaused || isCompressingContext,
                            state = modelPickerState,
                            isStreaming = isStreaming,
                            isPaused = isPaused,
                            popupAnchorTopPx = inputContainerTopPx,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            onModelSelected = onModelSelected,
                        )

                        val sendMode = resolveChatComposerSendMode(
                            isStreaming = isStreaming,
                            isPaused = isPaused,
                            canContinueDisconnected = canContinueDisconnected,
                            hasSteerContent = textFieldState.text.isNotBlank() ||
                                pendingImages.isNotEmpty() ||
                                (pendingFileReferences.isNotEmpty() || conversationMentions.pending.isNotEmpty()),
                            canStartNewSend = canSend,
                            isCompressingContext = isCompressingContext,
                        )
                        val sendInteraction = remember { MutableInteractionSource() }
                        val context = LocalContext.current
                        Box(
                            modifier = Modifier
                                .size(ChatInputActionSize)
                                .focusProperties { canFocus = false }
                                .combinedClickable(
                                    enabled = sendMode != "idle",
                                    indication = null,
                                    interactionSource = sendInteraction,
                                    hapticFeedbackEnabled = false,
                                    onClick = {
                                        TouchHaptics.click(view)
                                        when (sendMode) {
                                            "blocked" -> Toast.makeText(
                                                context,
                                                R.string.compress_conversation_in_progress,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            "stop" -> onStop()
                                            "continue" -> onContinue()
                                            "send" -> {
                                                val submittedText = textFieldState.text.toString()
                                                onSubmit(submittedText)
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (sendMode != "stop" && !(sendMode == "continue" && isPaused)) return@combinedClickable
                                        TouchHaptics.longPress(view)
                                        onAbortPausedRun()
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val sendButtonColor by animateColorAsState(
                                targetValue = when (sendMode) {
                                    "stop", "continue", "send" -> MiuixTheme.colorScheme.onSurface
                                    else -> MiuixTheme.colorScheme.surfaceContainerHigh
                                },
                                animationSpec = tween(durationMillis = 160),
                                label = "send_button_color",
                            )
                            Box(
                                modifier = Modifier
                                    .size(SendButtonVisualSize)
                                    .clip(CircleShape)
                                    .background(sendButtonColor),
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedContent(
                                    targetState = sendMode,
                                    transitionSpec = {
                                        (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.72f))
                                            .togetherWith(
                                                fadeOut(tween(90)) +
                                                    scaleOut(tween(110), targetScale = 0.72f)
                                            )
                                    },
                                    label = "send_stop_icon",
                                ) { mode ->
                                    Icon(
                                        imageVector = when (mode) {
                                            "stop" -> Icons.Rounded.Stop
                                            "continue" -> Icons.Rounded.PlayArrow
                                            else -> Icons.Rounded.ArrowUpward
                                        },
                                        contentDescription = when (mode) {
                                            "stop" -> stringResource(R.string.chat_stop)
                                            "continue" -> if (isPaused) {
                                                stringResource(R.string.chat_continue) +
                                                    "，" + stringResource(R.string.chat_continue_abort)
                                            } else {
                                                stringResource(R.string.chat_continue)
                                            }
                                            else -> stringResource(R.string.chat_send)
                                        },
                                        modifier = Modifier.size(
                                            if (mode == "stop") StopIconSize else SendIconSize
                                        ),
                                        tint = when (mode) {
                                            "stop", "continue", "send" -> MiuixTheme.colorScheme.surface
                                            else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                                        },
                                    )
                                }
                            }
                        }
                    },
                    indicator = {
                        if (isEditingMessage) {
                            ChatSpeechIndicator(
                                textFieldState = textFieldState,
                                showGeneration = showMorphLoading,
                                interactionBlocked = drawerBlocksIme,
                                resetKey = isStreaming to isEditingMessage,
                            )
                        } else {
                            VoiceEntryButton(
                                textFieldState = textFieldState,
                                showGeneration = showMorphLoading,
                                interactionBlocked = drawerBlocksIme,
                                resetKey = isStreaming to isEditingMessage,
                                voiceState = voiceState,
                                onStartVoiceMode = onStartVoiceMode,
                                onStopVoiceMode = onStopVoiceMode,
                            )
                        }
                    },
                )
            }
        }
        }
    }
    }

}

/**
 * 思考强度图标：单击打开旧的思考强度弹窗；当实际选中模型是 GPT 速度模型时，
 * 长按在原位循环切换速度档位（NORMAL → FAST → ULTRA_FAST → NORMAL）。
 *
 * 颜色始终由 owner 传入的 [gptSpeedMode] 决定（非 GPT 立即回到普通原色）；
 * 旋转/缩放只是“一次长按正好一圈”的手势反馈，颜色与业务真值都不本地记忆。
 */
@Composable
internal fun ThinkingEffortChip(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    enabled: Boolean,
    gptSpeedMode: GptSpeedMode = GptSpeedMode.NORMAL,
    gptSpeedSupported: Boolean = false,
    onCycleGptSpeedMode: () -> Unit = {},
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showPicker = remember { mutableStateOf(false) }
    val active = effort != ReasoningEffort.OFF
    val pickerEnabled = enabled && options.isNotEmpty()
    LaunchedEffect(pickerEnabled) {
        if (!pickerEnabled) showPicker.value = false
    }

    val normalColor = if (active) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    // 颜色来自 owner 的 gptSpeedMode：非 GPT（gptSpeedSupported=false）直接回落普通原色，
    // 且最终 tint 当帧恢复原色，不保存旧档位。
    // Material 3 Expressive 的变色与旋转动效取官方 motionScheme；miuix 主题族保留原时长。
    val speedFadeSpec: androidx.compose.animation.core.FiniteAnimationSpec<Color> =
        if (isMaterial3Theme()) MaterialTheme.motionScheme.defaultSpatialSpec() else tween(GptSpeedAnimationDurationMillis)
    val speedSpinSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
        if (isMaterial3Theme()) MaterialTheme.motionScheme.defaultSpatialSpec() else tween(
            durationMillis = GptSpeedAnimationDurationMillis,
            easing = LinearEasing,
        )
    val contentColor by animateColorAsState(
        targetValue = if (!gptSpeedSupported) {
            normalColor
        } else if (isMaterial3Theme()) {
            // Material 3：档位色取官方 colorScheme 角色（快速=tertiary、极速=error），不写死品牌色。
            etaGptSpeedColor(gptSpeedMode, normalColor)
        } else {
            gptSpeedChipColor(gptSpeedMode, normalColor)
        },
        animationSpec = speedFadeSpec,
        label = "thinking_content",
    )

    val displayedColor = if (gptSpeedSupported) contentColor else normalColor
    val canLongPressSpeed = gptSpeedSupported && enabled
    val interactive = pickerEnabled || canLongPressSpeed
    // Material 3 Expressive 取官方图标按钮尺寸令牌；MIUI X 沿用既有图标尺寸。
    val m3 = isMaterial3Theme()
    val thinkingIconSize = if (m3) etaOfficialIconButtonIconSize(ChatInputActionSize) else ThinkingIconSize
    val context = LocalContext.current
    val progress = remember { Animatable(0f) }
    val gate = remember { GptSpeedAnimationGate() }
    val scope = rememberCoroutineScope()

    // 用 rememberUpdatedState 固定回调身份：pointerInput 的 key 不随重组改变，
    // 避免长按手势被反复重启，也不让重组触发额外旋转。
    val pickerEnabledState = rememberUpdatedState(pickerEnabled)
    val canLongPressState = rememberUpdatedState(canLongPressSpeed)
    val gptSpeedModeState = rememberUpdatedState(gptSpeedMode)
    val contextState = rememberUpdatedState(context)
    val latestOnCycle by rememberUpdatedState(onCycleGptSpeedMode)

    val openPicker: () -> Unit = remember {
        { if (pickerEnabledState.value) showPicker.value = true }
    }
    val cycleSpeed: () -> Unit = remember {
        {
            // 动画中拒绝新的长按：不排队、不累计圈数，避免截断当前一圈或产生过期切换。
            if (canLongPressState.value) {
                val token = gate.request()
                if (token != 0) {
                    val nextMode = gptSpeedModeState.value.next()
                    latestOnCycle()
                    Toast.makeText(
                        contextState.value,
                        gptSpeedToastMessage(nextMode),
                        Toast.LENGTH_SHORT,
                    ).show()
                    scope.launch {
                        try {
                            progress.snapTo(0f)
                            progress.animateTo(
                                targetValue = 1f,
                                animationSpec = speedSpinSpec,
                            )
                        } finally {
                            // 携带自己的 token：被取消的旧动画不会关掉随后新动画的门禁。
                            gate.finish(token)
                        }
                    }
                }
            }
        }
    }

    // 模型切到非 GPT：立即清动画（取消进行中的一圈并复位），最终 tint 当帧恢复原色。
    LaunchedEffect(gptSpeedSupported) {
        if (!gptSpeedSupported) {
            gate.reset()
            progress.snapTo(0f)
        }
    }

    val contentDescription = if (gptSpeedSupported) {
        gptSpeedChipDescription(
            stringResource(R.string.chat_reasoning_effort, effort.displayName),
            gptSpeedMode,
        )
    } else {
        stringResource(R.string.chat_reasoning_effort, effort.displayName)
    }

    ChatInputNonFocusableIconButton(
        onClick = openPicker,
        contentDescription = contentDescription,
        onLongClick = if (canLongPressSpeed) cycleSpeed else null,
        longClickLabel = gptSpeedLongPressLabel(),
        // 不可用时交给官方按钮的 disabled 状态（容器色、内容色、涟漪一起走官方定义）。
        enabled = interactive,
        modifier = modifier,
    ) {
        // 官方图标替代自绘 ic_atom：AutoAwesome（星形）语义为 AI 生成/思考，与项目"思考深度"图标一致。
        val atomIcon = Icons.Rounded.AutoAwesome
        val atomModifier = Modifier
            .size(thinkingIconSize)
            .then(
                if (gptSpeedSupported) {
                    Modifier.graphicsLayer {
                        rotationZ = gptSpeedRotationDegrees(progress.value)
                        val scale = gptSpeedScale(progress.value)
                        scaleX = scale
                        scaleY = scale
                    }
                } else {
                    Modifier
                },
            )
        if (m3 && !interactive) {
            // 官方禁用内容色即最终色，不再叠加本地透明度。
            Icon(imageVector = atomIcon, contentDescription = null, modifier = atomModifier)
        } else {
            Icon(
                imageVector = atomIcon,
                contentDescription = null,
                modifier = atomModifier,
                tint = if (interactive) {
                    displayedColor
                } else {
                    displayedColor.copy(alpha = DisabledIconAlpha)
                },
            )
        }
    }
    ThinkingEffortPickerDialog(
        show = showPicker.value && pickerEnabled,
        effort = effort,
        options = options,
        onDismiss = { showPicker.value = false },
        onEffortChange = onEffortChange,
    )
}

@Composable
internal fun ThinkingEffortPickerDialog(
    show: Boolean,
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    onDismiss: () -> Unit,
    onEffortChange: (ReasoningEffort) -> Unit,
    description: String? = null,
) {
    if (options.isEmpty()) return
    val view = LocalView.current
    val selectedIndex = options.indexOf(effort).coerceAtLeast(0)
    var sliderValue by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var lastHapticIndex by remember { mutableIntStateOf(selectedIndex) }
    val latestEffort by rememberUpdatedState(effort)
    val latestOptions by rememberUpdatedState(options)
    val latestOnEffortChange by rememberUpdatedState(onEffortChange)
    LaunchedEffect(show, selectedIndex, options) {
        if (show) {
            sliderValue = selectedIndex.toFloat()
            lastHapticIndex = selectedIndex
        }
    }
    val previewIndex = sliderValue.roundToInt().coerceIn(0, options.lastIndex)
    val preview = options[previewIndex]
    val maxIndex = (options.size - 1).toFloat().coerceAtLeast(0f)

    EtaDialog(
        show = show,
        title = stringResource(R.string.reasoning_picker_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (description != null) {
                Text(
                    text = description,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Icon(
                // 官方图标替代自绘 ic_atom：AutoAwesome＝AI 思考/生成中。
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(32.dp),
                tint = if (preview != ReasoningEffort.OFF) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
            Text(
                text = preview.displayName,
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            if (options.size > 1) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = sliderValue.coerceIn(0f, maxIndex),
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        valueRange = 0f..maxIndex,
                        steps = (options.size - 2).coerceAtLeast(0),
                        showKeyPoints = true,
                        keyPoints = options.indices.map { it.toFloat() },
                        magnetThreshold = 0.18f,
                        hapticEffect = SliderDefaults.SliderHapticEffect.None,
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(options.size) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    val width = size.width.toFloat().coerceAtLeast(1f)
                                    var current = discreteSliderIndexForTap(
                                        down.position.x,
                                        width,
                                        latestOptions.size,
                                    )
                                    sliderValue = current.toFloat()
                                    lastHapticIndex = current
                                    TouchHaptics.click(view)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        val index = discreteSliderIndexForTap(
                                            change.position.x,
                                            width,
                                            latestOptions.size,
                                        )
                                        if (index != current) {
                                            current = index
                                            sliderValue = current.toFloat()
                                            if (current != lastHapticIndex) {
                                                lastHapticIndex = current
                                                TouchHaptics.click(view)
                                            }
                                        }
                                        change.consume()
                                        if (!event.changes.any { it.pressed }) break
                                    }
                                    val coerced = current.coerceIn(0, latestOptions.lastIndex)
                                    sliderValue = coerced.toFloat()
                                    lastHapticIndex = coerced
                                    val next = latestOptions[coerced]
                                    if (next != latestEffort) latestOnEffortChange(next)
                                }
                            },
                    )
                }
            }
        }
    }
}

internal fun discreteSliderIndexForTap(x: Float, width: Float, count: Int): Int {
    if (count <= 1 || width <= 0f) return 0
    val fraction = (x / width).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
}


/**
 * 横向跟随 Chip，竖向则避开整个输入面板；默认下拉定位只会避开 Chip 自身。
 */
@Composable
private fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEach { image ->
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            ) {
                rememberDataUrlBitmap(image.dataUrl, fallback = image.uri)?.let { bitmap ->
                    ChatClickableImage(
                        source = image.uri,
                        bitmap = bitmap,
                        contentDescription = stringResource(
                            if (image.isVideo) R.string.chat_video_preview else R.string.chat_image_preview,
                        ),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (image.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = AgentVideoCodec.formatDuration(image.durationMs ?: 0L),
                            style = MiuixTheme.textStyles.body2,
                            color = Color.White,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(20.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.58f))
                        .clickable { onRemoveImage(image.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_image_089db3),
                        modifier = Modifier.size(12.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}


@Composable
internal fun AssistantPickerButton(
    enabled: Boolean,
    selectedAssistantId: String = "",
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    containerShape: androidx.compose.ui.graphics.Shape? = null,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    containerSize: Dp = ChatInputActionSize,
) {
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId.ifBlank { repositoryActiveId }
    var showPicker by remember { mutableStateOf(false) }
    val assistant = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    // Material 3 Expressive 用官方图标尺寸令牌（调用方给 56dp 时即 medium 图标尺寸）。
    val avatarSize = if (isMaterial3Theme()) etaOfficialIconButtonIconSize(containerSize) else ThinkingIconSize
    LaunchedEffect(enabled) {
        if (!enabled) showPicker = false
    }
    ChatInputNonFocusableIconButton(
        onClick = { if (enabled) showPicker = true },
        contentDescription = stringResource(R.string.assistant_selector),
        enabled = enabled,
        modifier = modifier,
        containerShape = containerShape,
        containerColor = containerColor,
        containerSize = containerSize,
    ) {
        Box(modifier = Modifier.size(avatarSize)) {
            AssistantAvatar(
                assistant = assistant,
                size = avatarSize,
                // 头像是图片内容而非容器，官方图标按钮的 disabled 着色不覆盖它，保留弱化。
                modifier = Modifier.graphicsLayer(alpha = if (enabled) 1f else DisabledIconAlpha),
            )
        }
    }
    AssistantPickerDialog(
        show = showPicker && enabled,
        onDismiss = { showPicker = false },
        onSelect = { id ->
            showPicker = false
            onAssistantSelected(id)
        },
        onEdit = { id ->
            showPicker = false
            onEditAssistant(id)
        },
        selectedAssistantId = activeId,
    )
}

internal fun resolveChatComposerSendMode(
    isStreaming: Boolean,
    isPaused: Boolean,
    hasSteerContent: Boolean,
    canStartNewSend: Boolean,
    isCompressingContext: Boolean = false,
    canContinueDisconnected: Boolean = false,
): String = when {
    // 压缩进行中禁止追加/续写：有待发内容时显示灰色发送键，点击只提示；
    // 没有内容时流式仍可停止。
    isCompressingContext && hasSteerContent -> "blocked"
    isCompressingContext && isStreaming -> "stop"
    isCompressingContext -> "idle"
    (isStreaming || isPaused) && hasSteerContent -> "send"
    isPaused -> "continue"
    isStreaming -> "stop"
    // Terminal failure: Continue creates a new logical turn, never resumes the failed one.
    canContinueDisconnected && hasSteerContent -> "send"
    canContinueDisconnected -> "continue"
    canStartNewSend -> "send"
    else -> "idle"
}

@Composable
internal fun VoiceEntryButton(
    textFieldState: androidx.compose.foundation.text.input.TextFieldState,
    showGeneration: Boolean,
    interactionBlocked: Boolean,
    resetKey: Any?,
    voiceState: VoiceModeState,
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
    /** 非空时用该图标渲染一个色调圆形按钮（Material 3 Expressive 外观），行为与语音入口一致。 */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val view = LocalView.current
    val context = LocalContext.current
    var picker by remember { mutableStateOf(false) }
    var lastSelected by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_VOICE_LAST_ENTRY)) }
    fun rememberMode(mode: VoiceEntryMode) {
        lastSelected = mode.wireValue
        Prefs.putString(Prefs.Keys.AGENT_VOICE_LAST_ENTRY, mode.wireValue)
    }
    var pendingMode by remember { mutableStateOf<VoiceEntryMode?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mode = pendingMode
        pendingMode = null
        if (granted && mode != null && io.github.mangi.eta.agent.voice.VoiceEntryPolicy.enabled(
                io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.value, mode)) onStartVoiceMode(mode)
        else if (!granted && mode != null) Toast.makeText(context, R.string.speech_permission_denied, Toast.LENGTH_LONG).show()
    }
    fun startMode(mode: VoiceEntryMode) {
        if (!io.github.mangi.eta.agent.voice.VoiceEntryPolicy.enabled(
                io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.value, mode)) return
        rememberMode(mode)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onStartVoiceMode(mode)
        } else {
            pendingMode = mode
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val config by io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.collectAsState()
    val modes = io.github.mangi.eta.agent.voice.VoiceEntryPolicy.modes(config)
    val canChoose = io.github.mangi.eta.agent.voice.VoiceEntryPolicy.canChoose(config)
    val directMode = io.github.mangi.eta.agent.voice.VoiceEntryPolicy.directMode(config, lastSelected)
    val directLabel = when (directMode) {
        VoiceEntryMode.DICTATION -> R.string.voice_mode_dictation
        VoiceEntryMode.UNIVERSAL -> R.string.voice_mode_universal
        VoiceEntryMode.DOUBAO_DUPLEX -> R.string.voice_mode_doubao
        null -> R.string.voice_mode_choose
    }
    LaunchedEffect(Unit) { io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.load(context) }
    LaunchedEffect(modes) {
        if (!canChoose) picker = false
        if (pendingMode !in modes) pendingMode = null
        if (voiceState.mode != null && voiceState.mode !in modes) onStopVoiceMode()
    }
    LaunchedEffect(resetKey, interactionBlocked) {
        pendingMode = null
        picker = false
    }
    val active = voiceState.active || voiceState.error != null
    val choose: (() -> Unit)? = if (canChoose) ({ picker = true }) else null
    Box {
    if (icon != null) {
        // Material 3 Expressive：官方图标按钮。进行中用 FilledIconButton（官方默认
        // primary/onPrimary），空闲用 FilledTonalIconButton（官方默认
        // secondaryContainer/onSecondaryContainer）；形状取官方 filledShape（整圆），
        // 容器尺寸取官方 smallContainerSize()（与主页 / 侧边栏按钮统一为 Small 档），
        // 内容色交给 LocalContentColor。
        val onVoiceClick: () -> Unit = {
            TouchHaptics.click(view)
            when {
                active -> onStopVoiceMode()
                directMode != null -> startMode(directMode)
                modes.size > 1 -> picker = true
            }
        }
        val voiceIcon: @Composable () -> Unit = {
            Icon(
                imageVector = if (active) Icons.Rounded.CallEnd else icon,
                contentDescription = context.getString(
                    if (active) R.string.voice_mode_stop else directLabel,
                ),
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
        }
        // 只有「可选模式」存在时才挂长按宿主，否则直接透传图标。
        val voiceContent: @Composable () -> Unit = if (choose == null) {
            voiceIcon
        } else {
            {
                ChatInputLongPressSlot(
                    enabled = !interactionBlocked && !active,
                    onLongPress = choose,
                    longClickLabel = context.getString(directLabel),
                    content = voiceIcon,
                )
            }
        }
        val voiceModifier = Modifier.size(IconButtonDefaults.smallContainerSize())
        if (active) {
            FilledIconButton(
                onClick = onVoiceClick,
                modifier = voiceModifier,
                enabled = !interactionBlocked,
                content = voiceContent,
            )
        } else {
            FilledTonalIconButton(
                onClick = onVoiceClick,
                modifier = voiceModifier,
                enabled = !interactionBlocked,
                content = voiceContent,
            )
        }
    } else if (VoiceEntryMode.DICTATION in modes && !active) {
        ChatSpeechIndicator(
            textFieldState = textFieldState,
            showGeneration = showGeneration,
            interactionBlocked = interactionBlocked,
            resetKey = resetKey,
            onLongClick = choose,
            onUnavailableClick = if (canChoose) ({ picker = true }) else null,
            onIdleClick = when (directMode) {
                VoiceEntryMode.DICTATION -> null
                null -> if (canChoose) ({ picker = true }) else null
                else -> ({ startMode(directMode) })
            },
            idleDescription = context.getString(directLabel),
            onStartRequested = { rememberMode(VoiceEntryMode.DICTATION) },
            suspendCapture = picker,
            forceVisible = true,
        )
    } else if (isMaterial3Theme()) {
        // Material 3 Expressive：没有显式图标的语音入口（通话进行中 / 免提模式）改用官方
        // IconButton 承担命中区、官方形状与官方涟漪；长按切换语音模式用官方按钮内容里的
        // ChatInputLongPressSlot 承载（官方图标按钮没有 onLongClick 槽位）。
        val onVoiceClick: () -> Unit = {
            TouchHaptics.click(view)
            when {
                active -> onStopVoiceMode()
                directMode != null -> startMode(directMode)
                modes.size > 1 -> picker = true
            }
        }
        val longPress = if (!active) choose?.let { callback ->
            { TouchHaptics.longPress(view); callback() }
        } else {
            null
        }
        // 官方图标按钮没有 onClickLabel，内容是变形加载指示器也没有图标可播报，
        // 因此点击文案只能在 semantics 里显式给出（只描述，不消费）。
        // 长按语义由 ChatInputLongPressSlot 自己用 onLongClick key 声明（子节点，父为 null
        // 时合并进来）。不要再在按钮 modifier 上补一份：同 key 父非空会赢，会把共享助手的
        // 语义整份遮掉。
        val voiceModifier = Modifier
            .size(IconButtonDefaults.smallContainerSize())
            .then(
                if (modes.isNotEmpty() || active) {
                    Modifier.semantics {
                        contentDescription = context.getString(
                            if (active) R.string.voice_mode_stop else directLabel,
                        )
                    }
                } else {
                    Modifier
                }
            )
        val indicatorSlot: @Composable () -> Unit = {
            ContainedMorphLoadingIndicator(
                indicatorSize = if (active) 40.dp else 24.dp,
                animate = showGeneration || active,
            )
        }
        val content: @Composable () -> Unit = if (longPress != null) {
            {
                ChatInputLongPressSlot(
                    enabled = !interactionBlocked,
                    onLongPress = longPress,
                    longClickLabel = context.getString(directLabel),
                    content = indicatorSlot,
                )
            }
        } else {
            indicatorSlot
        }
        if (active) {
            FilledIconButton(
                onClick = onVoiceClick,
                modifier = voiceModifier,
                enabled = !interactionBlocked,
                content = content,
            )
        } else {
            FilledTonalIconButton(
                onClick = onVoiceClick,
                modifier = voiceModifier,
                enabled = !interactionBlocked,
                content = content,
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .then(if (modes.isNotEmpty() || active) Modifier.semantics {
                    contentDescription = context.getString(if (active) R.string.voice_mode_stop else directLabel)
                }.combinedClickable(
                    enabled = !interactionBlocked,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    hapticFeedbackEnabled = false,
                    onLongClick = if (!active) choose?.let { callback ->
                        {
                            TouchHaptics.longPress(view)
                            callback()
                        }
                    } else null,
                    onClick = {
                        TouchHaptics.click(view)
                        when {
                            active -> onStopVoiceMode()
                            directMode != null -> startMode(directMode)
                            modes.size > 1 -> picker = true
                        }
                    },
                ) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            ContainedMorphLoadingIndicator(
                indicatorSize = if (active) 40.dp else 24.dp,
                animate = showGeneration || active,
            )
        }
    }
    VoiceEntryPickerDialog(
        show = picker && canChoose,
        modes = modes,
        onDismiss = { picker = false },
        onSelect = { mode ->
            picker = false
            pendingMode = null
            if (!interactionBlocked && io.github.mangi.eta.agent.voice.VoiceEntryPolicy.enabled(
                    io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.value, mode)) {
                TouchHaptics.click(view)
                // Choosing changes the default only; a separate tap starts capture or a call.
                rememberMode(mode)
            }
        },
    )
    }
}

@Composable
private fun VoiceEntryPickerDialog(
    show: Boolean,
    modes: List<VoiceEntryMode>,
    onDismiss: () -> Unit,
    onSelect: (VoiceEntryMode) -> Unit,
) {
    // Material 3 Expressive 用官方 DropdownMenu + DropdownMenuItem 的默认样式
    // （surfaceContainer 容器、shapes.extraSmall 圆角、Level2 阴影、48dp 行高、labelLarge）。
    val m3 = isMaterial3Theme()
    val items: @Composable (dismiss: () -> Unit) -> Unit = { dismiss ->
        val entries = listOf(
            Triple(VoiceEntryMode.DICTATION, Icons.Rounded.KeyboardVoice, R.string.voice_mode_dictation),
            Triple(VoiceEntryMode.UNIVERSAL, Icons.Rounded.RecordVoiceOver, R.string.voice_mode_universal),
            Triple(VoiceEntryMode.DOUBAO_DUPLEX, Icons.Rounded.GraphicEq, R.string.voice_mode_doubao),
        ).filter { it.first in modes }
        entries.forEachIndexed { index, (mode, icon, label) ->
            if (m3) {
                // 形状取官方 MenuDefaults：单项用 standalone，多项按位置取 leading/middle/trailing。
                DropdownMenuItem(
                    text = { M3Text(stringResource(label)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    shape = if (entries.size <= 1) {
                        MenuDefaults.standaloneItemShape
                    } else {
                        MenuDefaults.itemShape(index, entries.size).shape
                    },
                    onClick = {
                        dismiss()
                        onSelect(mode)
                    },
                )
            } else {
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    text = { Text(stringResource(label), style = MiuixTheme.textStyles.body2) },
                    leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        dismiss()
                        onSelect(mode)
                    },
                )
            }
        }
    }
    if (m3) {
        // focusable = false：菜单弹出时不抢走输入焦点、不收起输入法（与模型菜单同一套官方窗口属性）。
        DropdownMenu(
            expanded = show,
            onDismissRequest = onDismiss,
        ) {
            items(onDismiss)
        }
    } else {
        EtaDropdownMenu(
            expanded = show,
            onDismissRequest = onDismiss,
            preferAbove = true,
        ) {
            items(onDismiss)
        }
    }
}

@Composable
internal fun VoiceModeStatusPanel(
    state: VoiceModeState,
    onStop: () -> Unit,
) {
    val phase = when {
        state.error != null -> state.error
        state.phase == VoiceModePhase.Connecting -> stringResource(R.string.voice_mode_connecting)
        state.phase == VoiceModePhase.Listening -> stringResource(R.string.voice_mode_listening)
        state.phase == VoiceModePhase.Transcribing -> stringResource(R.string.voice_mode_transcribing)
        state.phase == VoiceModePhase.Thinking -> stringResource(R.string.voice_mode_thinking)
        state.phase == VoiceModePhase.Speaking -> stringResource(R.string.voice_mode_speaking)
        else -> stringResource(R.string.voice_mode_open)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (state.mode == VoiceEntryMode.DOUBAO_DUPLEX) {
                    Icons.Rounded.GraphicEq
                } else {
                    Icons.Rounded.RecordVoiceOver
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (state.error == null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
            )
            Text(
                text = phase,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onStop, minWidth = 36.dp, minHeight = 36.dp) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = stringResource(R.string.voice_mode_stop),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        val detail = listOfNotNull(
            state.transcript.takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.voice_recognized_text, it)
            },
            state.reply.takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.voice_reply_text, it)
            },
        ).joinToString("\n")
        if (detail.isNotBlank()) {
            VoiceTranscript(detail, resetKey = state.mode)
        }
    }
}

@Composable
private fun VoiceTranscript(detail: String, resetKey: Any?) {
    val scroll = rememberScrollState()
    var follow by remember(resetKey) { mutableStateOf(true) }
    var userGesture by remember(resetKey) { mutableStateOf(false) }
    val connection = remember(scroll, resetKey) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userGesture = true
                    follow = false
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && userGesture) follow = !scroll.canScrollForward
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (userGesture) {
                    follow = !scroll.canScrollForward
                    userGesture = false
                }
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(scroll, resetKey) {
        snapshotFlow { Triple(scroll.maxValue, follow, scroll.isScrollInProgress) }.collectLatest { (end, enabled, moving) ->
            if (enabled && !moving && end > 0) scroll.scrollTo(end)
        }
    }
    Text(
        text = detail,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        modifier = Modifier.padding(start = 30.dp, end = 8.dp, bottom = 4.dp)
            .heightIn(max = 160.dp).nestedScroll(connection).verticalScroll(scroll),
    )
}
