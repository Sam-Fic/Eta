package io.github.mangi.eta.agent.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CancelPresentation
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.ui.components.AgentConversationMessages
import io.github.mangi.eta.ui.components.ChatInputPopupMargin
import io.github.mangi.eta.ui.components.Material3ComposerBody
import io.github.mangi.eta.ui.components.PendingFileReferenceStrip
import io.github.mangi.eta.ui.components.rememberDataUrlBitmap
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class EtaVoicePhase {
    READY,
    PROCESSING,
    ERROR,
}

internal data class EtaVoiceUiState(
    val messages: List<AgentChatMessageUi> = emptyList(),
    val phase: EtaVoicePhase = EtaVoicePhase.READY,
    val status: EtaVoiceStatus = EtaVoiceStatus.InputRequest,
    val screenContext: EtaScreenContextUiState = EtaScreenContextUiState(),
)

internal sealed interface EtaVoiceStatus {
    data object InputRequest : EtaVoiceStatus
    data object WaitingForAnswer : EtaVoiceStatus
    data object Reasoning : EtaVoiceStatus
    data object Completed : EtaVoiceStatus
    data class RunningTool(val name: String) : EtaVoiceStatus
    data class Failed(val detail: String?) : EtaVoiceStatus
    data object Stopped : EtaVoiceStatus
}

internal enum class EtaScreenContextPhase {
    CAPTURING,
    AVAILABLE,
    UNAVAILABLE,
    CONSUMED,
}

internal data class EtaScreenContextUiState(
    val phase: EtaScreenContextPhase = EtaScreenContextPhase.CONSUMED,
    val previewDataUrl: String? = null,
    val selected: Boolean = false,
)

internal object EtaScreenContextStateReducer {
    fun select(
        state: EtaScreenContextUiState,
        enabled: Boolean,
        hasAttachment: Boolean,
    ): EtaScreenContextUiState =
        if (enabled && hasAttachment && state.phase == EtaScreenContextPhase.AVAILABLE) {
            state.copy(selected = true)
        } else {
            state
        }

    fun remove(
        state: EtaScreenContextUiState,
        enabled: Boolean,
    ): EtaScreenContextUiState =
        if (enabled && state.selected) state.copy(selected = false) else state

    fun consume(): EtaScreenContextUiState = EtaScreenContextUiState(
        phase = EtaScreenContextPhase.CONSUMED,
    )
}

private data class EtaVoicePanelColors(
    val content: Color,
    val input: Color,
    val inputPrimary: Color,
    val inputSecondary: Color,
    val inputTertiary: Color,
    val tertiary: Color,
    val scrim: Color,
    /**
     * 输入区上方功能件（屏幕上下文卡片等）的**全不透明**底色。
     *
     * [input] 自身带 alpha，再叠 0.92 后铺在浮窗上会把下层内容（用户当前界面、底部渐变）
     * 透出来。功能件一律读这个值：它按同一外观合成到了不透明的面板底色上。
     */
    val inputSolid: Color,
)

@Composable
private fun rememberEtaVoicePanelColors(): EtaVoicePanelColors {
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        if (dark) {
            // 去掉 alpha 后的面板实色，用于把半透明功能件底色合成为不透明等价色。
            val sheetSolid = Color(0xFF2B2C2F)
            EtaVoicePanelColors(
                content = Color(0xF52B2C2F),
                input = Color(0xF2404040),
                inputPrimary = Color(0xE6FFFFFF),
                inputSecondary = Color(0x8AFFFFFF),
                inputTertiary = Color(0x4DFFFFFF),
                tertiary = Color(0x66FFFFFF),
                scrim = Color(0x52000000),
                inputSolid = Color(0xF2404040).copy(alpha = 0.92f).compositeOver(sheetSolid),
            )
        } else {
            val sheetSolid = Color(0xFFF7F7F9)
            EtaVoicePanelColors(
                content = Color(0xFAF7F7F9),
                input = Color(0xF2FFFFFF),
                inputPrimary = Color(0xE6000000),
                inputSecondary = Color(0x8A000000),
                inputTertiary = Color(0x42000000),
                tertiary = Color(0x52000000),
                scrim = Color(0x30000000),
                inputSolid = Color(0xF2FFFFFF).copy(alpha = 0.92f).compositeOver(sheetSolid),
            )
        }
    }
}

/**
 * 入口浮层底部渐变压暗的**最小**高度。实际高度改为跟随输入区实测高度
 * （见 [AssistantInputBackdropExtraHeight]）：输入区早已不是单行，而是
 * 「屏幕上下文 chip + M3E 浮动工具栏（+ 附件预览条 / 语音聆听条）」，
 * 再固定 104dp 会让渐变的透明端跑到输入框上方、深色端压在输入框底下，
 * 视觉上就退化成一段等透明度的暗条。
 */
private val AssistantInputBackdropHeight = 104.dp

/** 渐变压暗在输入区实测高度之上再多出一小截，让顶端透明段落在输入框上方可见。 */
private val AssistantInputBackdropExtraHeight = 16.dp

/**
 * 入口弹出时的上移距离：内容面板与底部渐变压暗都从下方这个距离平移到位。
 * 面板走 M3E 弹簧、渐变压暗走普通补间，两者同时上移。
 */
private val AssistantEntryTranslation = 56.dp

/**
 * 退场时在整窗高度之上额外多推出去的余量：保证动画结束时连边角都不残留，
 * 而不是滑到一半等窗口被移除时「闪现消失」。
 */
private val AssistantExitOvershoot = 48.dp

/**
 * 退场动画时长：与 [EtaAssistantOverlayService] 里移除浮窗前等待的时长对应，
 * 反向平移回去、用普通非线性曲线（不用弹簧）。
 */
private const val AssistantExitDurationMillis = 220

@Composable
internal fun EtaVoicePanel(
    state: EtaVoiceUiState,
    textFieldState: TextFieldState,
    composer: EtaAssistantComposerState,
    voiceState: VoiceModeState,
    inputFocusRequestKey: Int,
    canOpenConversation: Boolean,
    exitRequested: Boolean,
    composerConversationId: String?,
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
    onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit,
    onSubmitQuestionAnswer: (String, String) -> Unit,
) {
    val colors = rememberEtaVoicePanelColors()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val panelEntry = remember { Animatable(0f) }
    val backdropEntry = remember { Animatable(0f) }
    val exitProgress = remember { Animatable(0f) }
    // 整窗高度（px）：退场要按它把内容完全推出屏幕，动画结束时任何内容都不再可见。
    var windowHeightPx by remember { mutableIntStateOf(0) }

    // 弹出：内容面板走 M3E 弹簧（纯平移入场），底部渐变压暗同期走普通补间平移，
    // 两者一起自下而上平移到位，只是曲线不同；全程不掺透明度变化。弹簧规格须在组合期读取。
    val panelEntrySpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        launch {
            panelEntry.animateTo(
                targetValue = 1f,
                animationSpec = panelEntrySpec,
            )
        }
        launch {
            backdropEntry.animateTo(
                targetValue = 1f,
                animationSpec = tween(260, easing = FastOutSlowInEasing),
            )
        }
    }

    // 退场：点空白处 / 返回键 / 交接时，面板连同底部渐变压暗**整体向下平移出屏**。
    // 与入场相反，这里用普通非线性曲线而不用弹簧；位移量按整窗高度 + 余量计算，
    // 保证动画结束时所有内容都已经滑出屏幕，而不是走一半等窗口被移除时「闪现消失」。
    // 全程只有位移、不做淡出。
    LaunchedEffect(exitRequested) {
        if (!exitRequested) return@LaunchedEffect
        exitProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = AssistantExitDurationMillis,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    LaunchedEffect(inputFocusRequestKey) {
        if (inputFocusRequestKey >= 0 && state.phase != EtaVoicePhase.PROCESSING) {
            delay(120)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { windowHeightPx = it.height },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // 入场 / 退场只做位移，不做透明度渐变：移动过程中输入框始终是不透明的，
                // 不会在位移途中把下层用户界面透出来。
                .graphicsLayer {
                    translationY = if (exitRequested) {
                        // 退场：一次性把内容推过窗口下缘（再加一段余量），完全离开屏幕。
                        exitProgress.value * (windowHeightPx + with(density) {
                            AssistantExitOvershoot.toPx()
                        })
                    } else {
                        // 入场：M3E 弹簧从下方一小段距离平移到位。
                        (1f - panelEntry.value) * with(density) { AssistantEntryTranslation.toPx() }
                    }
                },
        ) {
            val imeBottom = WindowInsets.ime.getBottom(density)
            val navigationBottom = WindowInsets.navigationBars.getBottom(density)
            val statusTop = WindowInsets.statusBars.getTop(density)
            val bottomInset = max(imeBottom, navigationBottom)
            val imeOverlap = (imeBottom - navigationBottom).coerceAtLeast(0)
            val maxContentHeightPx = with(density) {
                (maxHeight - 88.dp).toPx() - statusTop - navigationBottom
            }.coerceAtLeast(with(density) { 220.dp.toPx() })
            AssistantPanel(
                state = state,
                textFieldState = textFieldState,
                composer = composer,
                voiceState = voiceState,
                colors = colors,
                focusRequester = focusRequester,
                canOpenConversation = canOpenConversation,
                baseContentHeightPx = assistantBaseHeightPx(
                    messages = state.messages,
                    maxHeightPx = maxContentHeightPx,
                    density = density.density,
                ),
                maxContentHeightPx = maxContentHeightPx,
                bottomInsetPx = bottomInset,
                imeOverlapPx = imeOverlap,
                imeInsetPx = imeBottom,
                panelEntry = panelEntry,
                backdropEntry = backdropEntry,
                composerConversationId = composerConversationId,
                onStartVoiceMode = onStartVoiceMode,
                onStopVoiceMode = onStopVoiceMode,
                onScreenContextSelect = onScreenContextSelect,
                onScreenContextRemove = onScreenContextRemove,
                onSubmit = {
                    keyboard?.hide()
                    onSubmit(it)
                },
                onStop = onStop,
                onClose = onClose,
                onOpenConversation = onOpenConversation,
                onQuestionDraftChanged = onQuestionDraftChanged,
                onSubmitQuestionAnswer = onSubmitQuestionAnswer,
            )
        }
    }
}

@Composable
private fun BoxScope.AssistantPanel(
    state: EtaVoiceUiState,
    textFieldState: TextFieldState,
    composer: EtaAssistantComposerState,
    voiceState: VoiceModeState,
    colors: EtaVoicePanelColors,
    focusRequester: FocusRequester,
    canOpenConversation: Boolean,
    baseContentHeightPx: Float,
    maxContentHeightPx: Float,
    bottomInsetPx: Int,
    imeOverlapPx: Int,
    imeInsetPx: Int,
    panelEntry: Animatable<Float, AnimationVector1D>,
    backdropEntry: Animatable<Float, AnimationVector1D>,
    composerConversationId: String?,
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
    onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit,
    onSubmitQuestionAnswer: (String, String) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var settledHeightPx by remember { mutableFloatStateOf(baseContentHeightPx) }
    var draggedHeightPx by remember { mutableStateOf<Float?>(null) }
    var dismissPullPx by remember { mutableFloatStateOf(0f) }
    var handoffPullPx by remember { mutableFloatStateOf(0f) }
    var directHandoffPullPx by remember { mutableFloatStateOf(0f) }
    var thresholdHapticSent by remember { mutableStateOf(false) }
    var handoffRunning by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }
    // 输入区实测高度（含它自己的内边距）：底部渐变压暗按它取材，输入区长高/缩短时
    // 渐变同步变高，不会退化成等透明度的暗条。绘制阶段读取，只触发重绘不触发重组。
    var composerHeightPx by remember { mutableFloatStateOf(0f) }
    val handoffThresholdPx = with(density) { 72.dp.toPx() }
    val directHandoffThresholdPx = with(density) { 48.dp.toPx() }
    val dismissThresholdPx = with(density) { 92.dp.toPx() }
    val handoffVelocityPx = with(density) { 900.dp.toPx() }
    val hasMessages = state.messages.isNotEmpty()
    val targetHeightPx = if (hasMessages) {
        settledHeightPx.coerceIn(baseContentHeightPx, maxContentHeightPx)
    } else {
        0f
    }
    val animatedHeightPx by animateFloatAsState(
        targetValue = targetHeightPx,
        animationSpec = folmeSpring(damping = 0.9f, response = 0.38f),
        label = "assistant_content_height",
    )
    val sheetBackgroundAlpha = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing),
        label = "assistant_sheet_background",
    )
    val messageRevealProgress = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = if (hasMessages) {
            tween(durationMillis = 180, delayMillis = 50, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 100, easing = FastOutSlowInEasing)
        },
        label = "assistant_message_reveal",
    )
    val currentAnimatedHeight = rememberUpdatedState(animatedHeightPx)
    val sheetHeightPx = draggedHeightPx ?: animatedHeightPx
    val visibleSheetHeightPx = sheetHeightPx.coerceAtMost(
        (maxContentHeightPx - imeOverlapPx).coerceAtLeast(0f),
    )
    val nearFullscreen = sheetHeightPx >= maxContentHeightPx * 0.88f
    val handoffReady = canOpenConversation && nearFullscreen &&
        (handoffPullPx >= handoffThresholdPx ||
            directHandoffPullPx >= directHandoffThresholdPx)
    val sheetTranslationPx = dismissPullPx * 0.28f - handoffPullPx.coerceAtMost(
        with(density) { 28.dp.toPx() },
    ) * 0.12f

    LaunchedEffect(hasMessages, baseContentHeightPx, maxContentHeightPx) {
        settledHeightPx = if (hasMessages) {
            settledHeightPx.coerceIn(baseContentHeightPx, maxContentHeightPx)
        } else {
            0f
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) keepBottomAnchored = true
    }
    LaunchedEffect(handoffReady) {
        if (handoffReady && !thresholdHapticSent) {
            thresholdHapticSent = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (!handoffReady) {
            thresholdHapticSent = false
        }
    }

    fun triggerHandoff() {
        if (handoffRunning || !canOpenConversation) return
        handoffRunning = true
        settledHeightPx = maxContentHeightPx
        draggedHeightPx = null
        scope.launch {
            delay(120)
            onOpenConversation()
        }
    }

    fun dragBy(deltaY: Float): Float {
        if (handoffRunning || state.messages.isEmpty()) return 0f
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        val requested = current - deltaY
        return when {
            requested > maxContentHeightPx -> {
                draggedHeightPx = maxContentHeightPx
                if (deltaY < 0f) handoffPullPx += -deltaY
                deltaY
            }
            requested < baseContentHeightPx -> {
                draggedHeightPx = baseContentHeightPx
                if (deltaY > 0f) dismissPullPx += deltaY
                deltaY
            }
            else -> {
                draggedHeightPx = requested
                dismissPullPx = 0f
                handoffPullPx = 0f
                deltaY
            }
        }
    }

    fun finishDrag(velocityY: Float = 0f) {
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        when {
            dismissPullPx >= dismissThresholdPx -> onClose()
            canOpenConversation && current >= maxContentHeightPx * 0.88f &&
                (handoffReady || velocityY <= -handoffVelocityPx) -> triggerHandoff()
            else -> {
                val medium = baseContentHeightPx +
                    (maxContentHeightPx - baseContentHeightPx) * 0.58f
                val anchors = floatArrayOf(baseContentHeightPx, medium, maxContentHeightPx)
                settledHeightPx = anchors.minBy { kotlin.math.abs(it - current) }
                draggedHeightPx = null
                dismissPullPx = 0f
                handoffPullPx = 0f
            }
        }
    }

    val dragByState = rememberUpdatedState<(Float) -> Float>(::dragBy)
    val finishDragState = rememberUpdatedState<(Float) -> Unit>(::finishDrag)
    val nestedScrollConnection = remember(
        baseContentHeightPx,
        maxContentHeightPx,
        canOpenConversation,
        listState,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                if (
                    available.y < 0f &&
                    canOpenConversation &&
                    current >= maxContentHeightPx * 0.88f
                ) {
                    directHandoffPullPx += -available.y
                    if (directHandoffPullPx >= directHandoffThresholdPx) {
                        triggerHandoff()
                    }
                    // 第二段上滑由父容器在 pre-scroll 阶段完整消费，避免列表或
                    // overscroll 先截走事件后，接管手势永远达不到阈值。
                    return Offset(0f, available.y)
                }
                val shouldResize = (available.y < 0f && current < maxContentHeightPx) ||
                    (available.y > 0f && current > baseContentHeightPx && !listState.canScrollBackward)
                return if (shouldResize) {
                    Offset(0f, dragByState.value(available.y))
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y == 0f) return Offset.Zero
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                val atUpperEdge = available.y < 0f && current >= maxContentHeightPx * 0.88f
                val atLowerEdge = available.y > 0f && current <= baseContentHeightPx
                return if (atUpperEdge || atLowerEdge) {
                    Offset(0f, dragByState.value(available.y))
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                finishDragState.value(available.y)
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                finishDragState.value(available.y)
                return Velocity.Zero
            }
        }
    }

    val bottomInset = with(density) { bottomInsetPx.toDp() }
    val messageRevealOffsetPx = with(density) { 12.dp.toPx() }
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    // 会话面板（发出消息后弹出的这块 bottomsheet）底色：用 surfaceContainer，并且强制
    // 全不透明。原先用带 alpha 的 colors.content，铺在用户界面上会把下层内容透出来。
    val sheetSurface = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f)
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .offset(y = with(density) { sheetTranslationPx.toDp() })
            .clip(sheetShape)
            .drawBehind {
                drawRect(
                    color = sheetSurface.copy(alpha = sheetBackgroundAlpha.value),
                )
                // 输入区下方的渐变压暗：画在面板底色之上、输入框之下。
                // 面板本体走 M3E 弹簧上移，这里抵消掉面板的弹簧位移、再叠加渐变自己的
                // 普通补间位移，让渐变单独按普通曲线平移上来。
                // 和输入框一样：入场 / 退场只位移，不跟着改透明度。
                val translationPx = AssistantEntryTranslation.toPx()
                val panelOffsetPx = (1f - panelEntry.value) * translationPx
                val backdropOffsetPx = (1f - backdropEntry.value) * translationPx
                // 渐变高度跟随输入区实测高度再加一小截，并保留最小值兜底（首帧未测量时）。
                val scrimHeightPx = maxOf(
                    AssistantInputBackdropHeight.toPx(),
                    composerHeightPx + AssistantInputBackdropExtraHeight.toPx(),
                )
                // 底部一直垫到物理屏幕底（键盘弹起时抬到键盘上沿），
                // 因此手势条区域也被一并压暗，不会空出一道。
                val scrimBottomPx = size.height - imeInsetPx
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            colors.scrim,
                        ),
                    ),
                    topLeft = Offset(
                        x = 0f,
                        y = scrimBottomPx - scrimHeightPx + (backdropOffsetPx - panelOffsetPx),
                    ),
                    size = Size(size.width, scrimHeightPx),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { visibleSheetHeightPx.toDp() })
                .nestedScroll(nestedScrollConnection),
        ) {
            DragHandle(
                colors = colors,
                modifier = Modifier.pointerInput(baseContentHeightPx, maxContentHeightPx) {
                    detectVerticalDragGestures(
                        onDragStart = { draggedHeightPx = currentAnimatedHeight.value },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragBy(dragAmount)
                        },
                        onDragEnd = { finishDrag() },
                        onDragCancel = { finishDrag() },
                    )
                },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        alpha = messageRevealProgress.value
                        translationY = (1f - messageRevealProgress.value) * messageRevealOffsetPx
                    },
            ) {
                if (hasMessages) {
                    AgentConversationMessages(
                        visibleMessages = state.messages,
                        scrollState = listState,
                        isStreaming = state.phase == EtaVoicePhase.PROCESSING,
                        bottomInset = 8.dp,
                        keepBottomAnchored = keepBottomAnchored,
                        onBottomAnchorChanged = { keepBottomAnchored = it },
                        onQuestionDraftChanged = onQuestionDraftChanged,
                        onSubmitQuestionAnswer = onSubmitQuestionAnswer,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        AssistantComposer(
            state = state,
            textFieldState = textFieldState,
            composer = composer,
            voiceState = voiceState,
            colors = colors,
            focusRequester = focusRequester,
            composerConversationId = composerConversationId,
            onStartVoiceMode = onStartVoiceMode,
            onStopVoiceMode = onStopVoiceMode,
            onScreenContextSelect = onScreenContextSelect,
            onScreenContextRemove = onScreenContextRemove,
            onSubmit = onSubmit,
            onStop = onStop,
            modifier = Modifier
                .onSizeChanged { composerHeightPx = it.height.toFloat() }
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = bottomInset + 10.dp,
                ),
        )
    }
}

@Composable
private fun AssistantComposer(
    state: EtaVoiceUiState,
    textFieldState: TextFieldState,
    composer: EtaAssistantComposerState,
    voiceState: VoiceModeState,
    colors: EtaVoicePanelColors,
    focusRequester: FocusRequester,
    composerConversationId: String?,
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val processing = state.phase == EtaVoicePhase.PROCESSING
    // 尾部按钮槽只在一个条件下会腾出语音入口：sendMode == "idle" 且无内容。sendMode 由
    // canSend 推导，所以 canSend 必须和应用内一样把「草稿有可发送内容」算进去，
    // 否则空草稿也判定为可发送，发送按钮会一直占住尾部，语音入口永远不出现。
    val hasDraftContent = textFieldState.text.isNotBlank() ||
        composer.pendingImages.isNotEmpty() ||
        composer.pendingFileReferences.isNotEmpty()
    val density = LocalDensity.current
    // Not snapshot state: the panel reports its position on every layout, and the IME moves
    // it during the keyboard animation. Observing that here would recompose the panel per
    // keyboard frame; the value is read on demand when the popup is composed instead.
    val inputContainerTopPx = remember { intArrayOf(0) }
    // 「助手与模型」选择弹窗向上展开的最大高度：到状态栏为止，与应用内输入区同一套算法
    // （浮窗没有抽屉，直接用窗口内容顶到状态栏的距离）。
    // 状态栏顶部同样在组合期读取一次：`WindowInsets.statusBars` 是 @Composable getter，
    // 不能放进延迟到弹窗组合时才执行的 lambda 里。
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    val thinkingPopupMaxHeight: () -> Dp = remember(density, statusBarTopPx) {
        {
            with(density) {
                (inputContainerTopPx[0] - statusBarTopPx).coerceAtLeast(0).toDp()
            }.minus(ChatInputPopupMargin * 2)
                .coerceAtLeast(ListPopupDefaults.MinPopupHeight)
        }
    }

    Column(
        modifier = modifier.animateContentSize(
            animationSpec = folmeSpring(damping = 0.92f, response = 0.34f),
        ),
    ) {
        ScreenContextAttachment(
            state = state.screenContext,
            enabled = !processing,
            colors = colors,
            onSelect = onScreenContextSelect,
            onRemove = onScreenContextRemove,
        )
        if (state.screenContext.phase != EtaScreenContextPhase.CONSUMED) {
            Spacer(Modifier.height(8.dp))
        }
        // 附件预览条：与应用内输入区一致，文件引用在上、图片 / 视频在下，都渲染在输入框之上。
        // （`Material3ComposerBody` 自身不渲染预览条，需由调用方提供。）
        if (composer.pendingFileReferences.isNotEmpty()) {
            PendingFileReferenceStrip(
                references = composer.pendingFileReferences,
                onRemoveReference = composer::removePendingFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (composer.pendingImages.isNotEmpty()) {
            OverlayPendingImageStrip(
                images = composer.pendingImages,
                onRemoveImage = composer::removePendingImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Material3ComposerBody(
            textFieldState = textFieldState,
            focusRequester = focusRequester,
            // 生成中禁止聚焦输入框（与应用内旧浮层行为一致）；发送 / 停止按钮不受影响。
            interactionBlocked = processing,
            // 入口浮层没有「编辑消息」与「暂停运行」，这些槽位按不适用处理。
            isEditingMessage = false,
            isStreaming = processing,
            isPaused = false,
            isCompressingContext = false,
            canContinueDisconnected = false,
            contextSendBlocked = false,
            canSend = composer.canSend(hasDraftContent),
            usage = composer.usage,
            popupMaxHeight = thinkingPopupMaxHeight,
            onPositionedTopPx = { inputContainerTopPx[0] = it },
            // 传入浮窗会话 key，避免空 key 触发「保存草稿 / 定时发送」的额外副作用。
            conversationId = composerConversationId,
            assistantId = composer.assistantId,
            modelPickerState = composer.modelPickerState,
            reasoningEffort = composer.reasoningEffort,
            availableReasoningEfforts = composer.availableReasoningEfforts,
            gptSpeedMode = composer.gptSpeedMode,
            gptSpeedSupported = composer.gptSpeedSupported,
            // 入口浮层不放「助手与模型」按钮：这里没有模型切换的上下文，发送走当前全局默认选择。
            showModelPicker = false,
            voiceState = voiceState,
            showMorphLoading = processing,
            pendingImages = composer.pendingImages,
            pendingFileReferences = composer.pendingFileReferences,
            conversationMentions = ConversationMentionInputUi(),
            onStartVoiceMode = onStartVoiceMode,
            onStopVoiceMode = onStopVoiceMode,
            onCancelMessageEdit = {},
            onAttachImage = composer::attachImage,
            onAttachVideo = composer::attachVideo,
            onAttachFiles = composer::attachFiles,
            onAttachFolder = composer::attachFolder,
            onAttachFilePath = composer::attachFilePath,
            // 浮层没有助手编辑器入口，也不承载协作设置。
            onEditAssistant = {},
            onAssistantSelected = composer::selectAssistant,
            onModelSelected = composer::selectModel,
            onReasoningEffortChange = composer::updateReasoningEffort,
            onCycleGptSpeedMode = composer::cycleGptSpeedMode,
            onSubmit = onSubmit,
            onStop = onStop,
            // 入口浮层不支持「继续 / 中止暂停运行」。
            onContinue = {},
            onAbortPausedRun = {},
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScreenContextAttachment(
    state: EtaScreenContextUiState,
    enabled: Boolean,
    colors: EtaVoicePanelColors,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (fadeIn(tween(180, easing = LinearOutSlowInEasing)) +
                slideInVertically(tween(180, easing = LinearOutSlowInEasing)) { it / 5 })
                .togetherWith(
                    fadeOut(tween(120, easing = FastOutSlowInEasing)) +
                        slideOutVertically(tween(120, easing = FastOutSlowInEasing)) { -it / 6 },
                )
        },
        contentKey = { it.phase to it.selected },
        label = "assistant_screen_context",
    ) { screenContext ->
        when {
            screenContext.phase == EtaScreenContextPhase.CONSUMED -> Unit
            screenContext.phase == EtaScreenContextPhase.AVAILABLE && screenContext.selected -> {
                SelectedScreenContext(
                    previewDataUrl = screenContext.previewDataUrl,
                    enabled = enabled,
                    colors = colors,
                    onRemove = onRemove,
                )
            }
            else -> {
                val available = screenContext.phase == EtaScreenContextPhase.AVAILABLE && enabled
                // 与应用内「新建对话 / 管理聊天」同一套 M3E 按钮：官方 FilledTonalButton，
                // 形状取 ButtonDefaults.shapes()，可用态容器 tertiaryContainer / onTertiaryContainer。
                //
                // 禁用态（采集中 / 当前屏幕不可用 / 生成中）一律靠**颜色**区分，不允许半透明：
                // 官方默认 disabledContainerColor 是 `onTertiaryContainer.copy(alpha = 0.12)`
                // 这样的裸 alpha 色（Material3 Button.kt 直接选它做容器填充，不再合成底色），
                // 铺在透明浮窗上就会把用户界面透出来。这里禁用态改用 secondaryContainer 实色底，
                // 前景配 onSecondaryContainer（再合成一次，兜住自定义配色里可能残留的 alpha）。
                val scheme = MaterialTheme.colorScheme
                val chipContainer = scheme.tertiaryContainer
                val chipOnContainer = scheme.onTertiaryContainer
                val chipDisabledContainer = scheme.secondaryContainer
                val chipDisabledContent = scheme.onSecondaryContainer
                    .compositeOver(chipDisabledContainer)
                FilledTonalButton(
                    onClick = onSelect,
                    enabled = available,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = chipContainer,
                        contentColor = chipOnContainer,
                        disabledContainerColor = chipDisabledContainer,
                        disabledContentColor = chipDisabledContent,
                    ),
                ) {
                    when (screenContext.phase) {
                        EtaScreenContextPhase.CAPTURING -> CircularProgressIndicator(
                            size = ButtonDefaults.IconSize,
                            strokeWidth = 2.dp,
                        )
                        EtaScreenContextPhase.AVAILABLE -> Icon(
                            imageVector = Icons.Rounded.DesktopWindows,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        EtaScreenContextPhase.UNAVAILABLE -> Icon(
                            imageVector = Icons.Rounded.CancelPresentation,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        EtaScreenContextPhase.CONSUMED -> Unit
                    }
                    M3Text(
                        text = when (screenContext.phase) {
                            EtaScreenContextPhase.CAPTURING -> stringResource(R.string.voice_screen_preparing)
                            EtaScreenContextPhase.AVAILABLE -> stringResource(R.string.voice_screen_add)
                            EtaScreenContextPhase.UNAVAILABLE -> stringResource(R.string.voice_screen_unavailable)
                            EtaScreenContextPhase.CONSUMED -> ""
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedScreenContext(
    previewDataUrl: String?,
    enabled: Boolean,
    colors: EtaVoicePanelColors,
    onRemove: () -> Unit,
) {
    val previewBitmap = previewDataUrl?.let { rememberDataUrlBitmap(it) }
    // 卡片底色与前景墨色全部取**全不透明**值：功能件不允许半透明；禁用态（生成中）的移除钮
    // 靠合成后的实色深浅区分，而不是靠降低透明度。
    val titleColor = colors.inputPrimary.compositeOver(colors.inputSolid)
    val summaryColor = colors.inputSecondary.compositeOver(colors.inputSolid)
    val removeColor = (if (enabled) colors.inputSecondary else colors.inputTertiary)
        .compositeOver(colors.inputSolid)
    Row(
        modifier = Modifier
            .height(60.dp)
            .squircleBackground(colors.inputSolid, 15.dp)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        previewBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.voice_screen_preview),
                modifier = Modifier
                    .size(50.dp)
                    .squircleClip(11.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.voice_screen_title),
                color = titleColor,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.voice_screen_context_summary),
                color = summaryColor,
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                maxLines = 1,
            )
        }
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            minWidth = 30.dp,
            minHeight = 30.dp,
            cornerRadius = 16.dp,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.voice_screen_remove),
                modifier = Modifier.size(16.dp),
                tint = removeColor,
            )
        }
    }
}

@Composable
private fun DragHandle(colors: EtaVoicePanelColors, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(colors.tertiary),
        )
    }
}

/**
 * 浮层输入区的待发图片 / 视频预览条。
 *
 * 应用内的同名条是 `private`，浮层无法复用，因此这里按同一套外观（60dp 见方、12dp 圆角、
 * 右上角移除圆钮、视频叠时长与播放图标）重写一份；图片解码复用内部 `rememberDataUrlBitmap`。
 */
@Composable
private fun OverlayPendingImageStrip(
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
            // 角标 / 移除钮压在缩略图上：取 M3 inverseSurface 作**全不透明**底板（再合成一层
            // 容器色，兜住自定义配色里可能残留的 alpha），不使用半透明黑。
            val badgeSurface = MaterialTheme.colorScheme.inverseSurface
                .compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
            val badgeOnSurface = MaterialTheme.colorScheme.inverseOnSurface
                .compositeOver(badgeSurface)
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                rememberDataUrlBitmap(image.dataUrl, fallback = image.uri)?.let { bitmap ->
                    Image(
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
                            .background(badgeSurface)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        M3Text(
                            text = AgentVideoCodec.formatDuration(image.durationMs ?: 0L),
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeOnSurface,
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
                        .background(badgeSurface)
                        .clickable { onRemoveImage(image.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_image_089db3),
                        modifier = Modifier.size(12.dp),
                        tint = badgeOnSurface,
                    )
                }
            }
        }
    }
}

private fun assistantBaseHeightPx(
    messages: List<AgentChatMessageUi>,
    maxHeightPx: Float,
    density: Float,
): Float {
    if (messages.isEmpty()) return 0f
    val estimatedLines = messages.sumOf { message ->
        when (message) {
            is UserMessageUi -> ceil(message.content.length / 22f).toInt().coerceAtLeast(1)
            is AgentMessageUi -> ceil(message.content.length / 24f).toInt().coerceAtLeast(1)
            is ThinkingMessageUi -> 2
            is io.github.mangi.eta.ui.model.AgentQuestionMessageUi -> 7 + message.request.options.size * 2
            is ToolActivityMessageUi -> 2
            else -> 1
        }
    }
    val estimatedDp = 92f + estimatedLines * 23f + messages.size * 12f
    return max(230f, estimatedDp)
        .times(density)
        .coerceAtMost(maxHeightPx * 0.68f)
}
