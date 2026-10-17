package io.github.mangi.eta.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechSession
import io.github.mangi.eta.agent.voice.offline.SpeechInputPolicy
import io.github.mangi.eta.agent.voice.offline.SpeechDraft
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Existing generation animation doubles as opt-in dictation; never auto-starts the microphone. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatSpeechIndicator(
    textFieldState: TextFieldState,
    showGeneration: Boolean,
    interactionBlocked: Boolean,
    resetKey: Any?,
    onLongClick: (() -> Unit)? = null,
    onUnavailableClick: (() -> Unit)? = null,
    forceVisible: Boolean = false,
    suspendCapture: Boolean = false,
    startRequest: Int = 0,
    onIdleClick: (() -> Unit)? = null,
    idleDescription: String? = null,
    onStartRequested: () -> Unit = {},
    /**
     * 采集是否进行中。听写由本组件独占采集协程与麦克风，调用方无法自行判断；
     * 输入区需要据此在转写期间保留本入口（否则写入第一个转写分片后，输入框一有内容，
     * 尾部槽就换成发送按钮，本组件被移出组合 → `onDispose` 收停采集）。
     */
    onCaptureActiveChange: (Boolean) -> Unit = {},
    /**
     * [ContainedMorphLoadingIndicator] 的配色覆盖。空表示沿用主题默认；输入区把本入口放进
     * 浮动工具栏时需要显式指定（工具栏容器与默认的 primaryContainer 同色，圆底会看不见）。
     */
    indicatorContainerColor: Color? = null,
    indicatorTint: Color? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val pack by OfflineSpeechPack.state.collectAsState()
    val cloudConfig by io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.collectAsState()
    val speechEnabled = cloudConfig.inputEnabled
    var job by remember { mutableStateOf<Job?>(null) }
    var active by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<SpeechDraft?>(null) }
    val allowed by rememberUpdatedState(speechEnabled && (if (cloudConfig.cloudAsr) cloudConfig.asrKey.isNotBlank() else pack.ready) && !interactionBlocked && !suspendCapture)
    // 采集进行中只在转写期间为真：请求权限、待机都不算，避免输入区误以为听写已开始。
    val latestActiveChange by rememberUpdatedState(onCaptureActiveChange)
    LaunchedEffect(active) { latestActiveChange(active) }
    DisposableEffect(Unit) { onDispose { latestActiveChange(false) } }

    fun stop() {
        pendingPermission = false
        active = false
        listening = false
        draft = null
        job?.cancel()
    }
    fun start() {
        if (!allowed || job?.isCompleted == false || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val target = SpeechDraft(textFieldState.text.toString(), textFieldState.selection.start, textFieldState.selection.end)
        draft = target
        active = true
        listening = false
        job = scope.launch {
            var speechToken: Long? = null
            try {
                speechToken = io.github.mangi.eta.agent.voice.tts.SpeechPlayback.beginInput()
                val heard = io.github.mangi.eta.agent.voice.SpeechInputSession.recognize(context.applicationContext,
                    onListening = { listening = true },
                    onText = { text ->
                        val next = target.accept(textFieldState.text.toString(), text)
                            ?: throw CancellationException("Draft changed")
                        textFieldState.edit {
                            replace(0, length, next)
                            selection = TextRange(target.cursor)
                        }
                    },
                )
                if (!heard) Toast.makeText(context, R.string.speech_no_voice, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Toast.makeText(context, if (cloudConfig.cloudAsr) failure.message ?: context.getString(R.string.csi_doubao_asr_failed) else context.getString(R.string.speech_failed), Toast.LENGTH_LONG).show()
            } catch (_: LinkageError) {
                Toast.makeText(context, R.string.speech_failed, Toast.LENGTH_LONG).show()
            } finally {
                speechToken?.let { io.github.mangi.eta.agent.voice.tts.SpeechPlayback.endInput(it) }
                active = false
                listening = false
                draft = null
            }
        }
    }

    val latestStart by rememberUpdatedState({ start() })
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = pendingPermission
        pendingPermission = false
        if (granted && requested) latestStart()
        else if (!granted && requested) Toast.makeText(context, R.string.speech_permission_denied, Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) { io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.load(context); OfflineSpeechPack.initialize(context) }
    LaunchedEffect(allowed, resetKey) {
        stop() // Mode changes, drawer, edit, send/stream transition: invalidate permission and capture.
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { current ->
            if (active && draft?.expectedText != current) stop()
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }

    fun requestStart() {
        if (!allowed) return
        onStartRequested()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else { pendingPermission = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    var handledStartRequest by remember { mutableIntStateOf(startRequest) }
    LaunchedEffect(startRequest) {
        if (startRequest != handledStartRequest) {
            handledStartRequest = startRequest
            requestStart()
        }
    }

    // M3 Expressive：直径变化走官方 motionScheme 空间动效。
    // 触摸区仍是固定 48dp（等于 Compose 官方最小可点区域），避免生长时挤动输入栏其它按钮。
    val diameter by animateDpAsState(
        targetValue = if (active) 40.dp else 24.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>(),
        label = "speechDiameter",
    )
    val label = stringResource(if (active) R.string.speech_stop else R.string.speech_start)
    val status = stringResource(if (listening) R.string.speech_listening else R.string.speech_preparing)
    // Parent layout data (for example BoxScope.align) must reach the outer node.
    AnimatedVisibility(
        modifier = modifier,
        visible = forceVisible || SpeechInputPolicy.visible(showGeneration, speechEnabled),
    ) {
        val clickAction: () -> Unit = {
            TouchHaptics.click(view)
            if (active || pendingPermission) stop()
            else if (onIdleClick != null) onIdleClick()
            else if (!allowed) onUnavailableClick?.invoke()
            else requestStart()
        }
        val longPressAction: (() -> Unit)? = onLongClick?.let { callback ->
            { TouchHaptics.longPress(view); callback() }
        }
        val indicator: @Composable () -> Unit = {
            ContainedMorphLoadingIndicator(
                indicatorSize = diameter,
                animate = showGeneration || active,
                containerColor = indicatorContainerColor,
                indicatorColor = indicatorTint,
            )
        }
        // 触摸区仍是固定 48dp（等于 Compose 官方最小可点区域），避免生长时挤动输入栏其它按钮。
        val accessibility = Modifier.semantics {
            if (speechEnabled) {
                contentDescription = if (!active && onIdleClick != null) idleDescription ?: context.getString(R.string.voice_mode_choose) else label
                if (active) stateDescription = status
            }
        }
        // M3 Expressive：官方 IconButton 承担 48dp 命中区、官方形状、官方涟漪与 Role；
        // 按钮内容是官方 ContainedLoadingIndicator（见 ContainedMorphLoadingIndicator）。
        // 长按切换语音模式：官方图标按钮没有 onLongClick 槽位（material3 1.5.0-alpha29
        // 里只有 ListItem 提供，而 ListItem 无法表达圆形录音按钮），因此长按检测放进按钮
        // 内容，只观察指针不消费，点击、禁用与涟漪仍全部由官方按钮处理。
        // 长按语义由 ChatInputLongPressSlot 自己用 onLongClick key 声明（子节点，父为 null
        // 时合并进来）。不要再在按钮 modifier 上补一份：同 key 父非空会赢，会把共享助手的
        // 语义整份遮掉。
        IconButton(
            onClick = clickAction,
            // modifier 必须包住整个按钮（含官方 minimumInteractiveComponentSize 的 48dp
            // 命中区）：输入区把本入口放进只留 40dp 的尾部槽，不在此收口，命中区会把
            // 槽位撑宽，让胶囊与发送按钮位置错开。
            modifier = modifier.then(accessibility),
            enabled = (speechEnabled || forceVisible) &&
                (!interactionBlocked &&
                    (allowed || active || onLongClick != null ||
                        onUnavailableClick != null || onIdleClick != null)),
        ) {
            if (longPressAction != null) {
                ChatInputLongPressSlot(
                    enabled = !interactionBlocked,
                    onLongPress = longPressAction,
                    longClickLabel = label,
                    content = indicator,
                )
            } else {
                indicator()
            }
        }
    }
}
