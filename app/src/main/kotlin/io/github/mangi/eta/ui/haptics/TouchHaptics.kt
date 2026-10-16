package io.github.mangi.eta.ui.haptics

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import io.github.mangi.eta.config.Prefs

/**
 * 工作过程里「正在运行」的工具步骤只震动一次，避免 LazyColumn 回收或状态刷新重复触发。
 */
internal class LiveToolHapticTracker(
    private val maxIds: Int = 256,
) {
    private val ids = LinkedHashSet<String>()

    fun markIfNew(id: String): Boolean {
        if (id.isBlank()) return false
        if (!ids.add(id)) return false
        while (ids.size > maxIds) {
            val iterator = ids.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        return true
    }
}

/**
 * 生成过程里一次性轻触的三种质感。各走各的效果，不要合成一个音：
 * 打字、工具步骤、推理完成在手上应该一听就能分开。
 */
internal enum class GenerationTickKind { TYPING, TOOL, REASONING }

/**
 * App 内触控反馈。
 *
 * 交互类语义（点按 / 长按 / 开关 / 成功 / 失败 / 按键 / 手势阈值）一律走系统
 * [View.performHapticFeedback]（[HapticFeedbackConstants]），由 HyperOS / ColorOS 与线性马达
 * 按系统主题渲染各自的「官方触感」，并自动跟随系统「振动强度」；不提供自定义振幅档位——
 * 用户只要系统的「触控反馈」总开关，app 侧只有开 / 关两个状态。
 *
 * 语义只在此处声明，调用点不再各写 `HapticFeedbackConstants`：
 * [click] 普通点按、[longPress] 长按、[toggle] 开关翻转、[confirm] 成功、[reject] 失败、
 * [tick] / [gestureThreshold] 流式与手势阈值。
 *
 * 每类语义对应「震动」设置页里的一个细分开关（[isTapEnabled]、[isLongPressEnabled]、
 * [isKeyboardEnabled]、[isTypingEnabled]、[isToolStepsEnabled]、[isReasoningDoneEnabled]），
 * 它们都先受「触控反馈」或「消息生成触控反馈」总开关约束。
 *
 * 只有工具步骤与推理完成这两种一次性事件会按 [HapticCapability] 探测到的档位往上够，
 * 换成各自的原语 / 预定义效果，好让宽频马达显出差别。高频的打字 tick 不换：
 * [HapticFeedbackConstants.SEGMENT_FREQUENT_TICK] 本就是为一秒几十次设计的，相邻 tick
 * 不会互相打断，换成预定义效果反而会糊成一坨。
 */
internal object TouchHaptics {
    private val liveToolTracker = LiveToolHapticTracker()
    private const val GENERATION_TICK_INTERVAL_MS = 32L
    private const val MAX_QUEUED_TICKS = 8
    private var lastGenerationTickAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingTicks = ArrayDeque<GenerationTickKind>()
    private var tickScheduled = false
    private var tickView: View? = null
    private var preparedTicks: Map<GenerationTickKind, VibrationEffect?>? = null
    private var systemTickEffect: VibrationEffect? = null
    private var cachedVibrator: Vibrator? = null
    private var cachedTouchAttributes: VibrationAttributes? = null
    private val tickRunnable = object : Runnable {
        override fun run() {
            tickScheduled = false
            val kind = pendingTicks.removeFirstOrNull() ?: return
            playGenerationTick(tickView, kind)
            if (pendingTicks.isNotEmpty()) scheduleTick()
        }
    }

    fun isTouchEnabled(): Boolean = Prefs.isEnabled(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK)

    /** 点按与控件反馈：普通点击、开关翻转、成功 / 失败、手势阈值。 */
    fun isTapEnabled(): Boolean =
        isTouchEnabled() && Prefs.isEnabled(Prefs.Keys.HAPTIC_TAP)

    /** 长按反馈。含 M3 分段行自带的长按触感，见 [ProvideEtaHapticFeedback]。 */
    fun isLongPressEnabled(): Boolean =
        isTouchEnabled() && Prefs.isEnabled(Prefs.Keys.HAPTIC_LONG_PRESS)

    /** 按键反馈：终端虚拟键盘等 app 自绘的按键。 */
    fun isKeyboardEnabled(): Boolean =
        isTouchEnabled() && Prefs.isEnabled(Prefs.Keys.HAPTIC_KEYBOARD)

    fun isMessageGenerationEnabled(): Boolean =
        isTouchEnabled() && Prefs.isEnabled(Prefs.Keys.HAPTIC_MESSAGE_GENERATION)

    /** 模型正文逐字流出时的轻触。 */
    fun isTypingEnabled(): Boolean =
        isMessageGenerationEnabled() && Prefs.isEnabled(Prefs.Keys.HAPTIC_TYPING)

    /** 工具步骤标签首次出现时的轻触。 */
    fun isToolStepsEnabled(): Boolean =
        isMessageGenerationEnabled() && Prefs.isEnabled(Prefs.Keys.HAPTIC_TOOL_STEPS)

    /** 推理块从「正在推理」变成「推理已完成」时的轻触。 */
    fun isReasoningDoneEnabled(): Boolean =
        isMessageGenerationEnabled() && Prefs.isEnabled(Prefs.Keys.HAPTIC_REASONING_DONE)

    /** 应用退到后台后是否继续震。依赖消息生成震动开着。 */
    fun isBackgroundEnabled(): Boolean =
        isMessageGenerationEnabled() && Prefs.isEnabled(Prefs.Keys.HAPTIC_BACKGROUND)

    /** 后台时推理正文是否也跟着震；关掉后只剩工具标签和推理完成那一下。 */
    fun isBackgroundReasoningEnabled(): Boolean =
        isBackgroundEnabled() && Prefs.isEnabled(Prefs.Keys.HAPTIC_BACKGROUND_REASONING)

    /**
     * 流式打字的轻触。间隔过短时马达会吞掉后续 tick，输出越快越像没在跟。
     * 32ms 大约一帧半，快流也能连成一串，又不会把 HyperOS 的 tick 挤掉。
     */
    fun generationTick(view: View?) {
        if (!isTypingEnabled()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastGenerationTickAt < GENERATION_TICK_INTERVAL_MS) return
        lastGenerationTickAt = now
        playGenerationTick(view, GenerationTickKind.TYPING)
    }

    private fun playGenerationTick(view: View?, kind: GenerationTickKind) {
        val context = view?.context
        // 工具步骤与推理完成是一次性事件，才有换质感的空间：按这台机器的档位往上够。
        if (kind != GenerationTickKind.TYPING && context != null) {
            val effect = expressiveTickEffect(context, kind)
            if (effect != null && vibrateEffect(context, effect)) return
        }
        // 打字维持系统语义：SEGMENT_FREQUENT_TICK 是一秒几十次也不会互相打断的那种。
        if (view != null && tick(view)) return
        // A stopped window reports the system tick as ignored. The run is still in the
        // foreground service, so play the same tick on the vibrator.
        context?.let(::vibrateSystemTick)
    }

    /** 语义各自的质感效果，按 [HapticCapability] 的档位现成一份；探测只做一次，效果也复用。 */
    private fun expressiveTickEffect(context: Context, kind: GenerationTickKind): VibrationEffect? {
        val table = preparedTicks ?: effectsFor(HapticCapability.of(context)).also { preparedTicks = it }
        return table[kind]
    }

    private fun effectsFor(snapshot: HapticCapability.Snapshot): Map<GenerationTickKind, VibrationEffect?> =
        GenerationTickKind.entries.associateWith { kind ->
            when (snapshot.tier) {
                HapticCapability.Tier.PRIMITIVE -> kind.compose(snapshot)
                HapticCapability.Tier.EFFECT -> kind.predefined(snapshot)
                HapticCapability.Tier.CONSTANT -> null
            }
        }

    private fun GenerationTickKind.compose(snapshot: HapticCapability.Snapshot): VibrationEffect? {
        val primitive = when (this) {
            GenerationTickKind.TYPING -> VibrationEffect.Composition.PRIMITIVE_TICK
            GenerationTickKind.TOOL -> VibrationEffect.Composition.PRIMITIVE_CLICK
            GenerationTickKind.REASONING -> VibrationEffect.Composition.PRIMITIVE_THUD
        }
        if (!snapshot.supportsPrimitive(primitive)) return null
        // 不给 scale：1.0 就是厂商调校好的那一下，系统「振动强度」还会再乘一层。
        return runCatching { VibrationEffect.startComposition().addPrimitive(primitive).compose() }.getOrNull()
    }

    private fun GenerationTickKind.predefined(snapshot: HapticCapability.Snapshot): VibrationEffect? {
        val effectId = when (this) {
            GenerationTickKind.TYPING -> VibrationEffect.EFFECT_TICK
            GenerationTickKind.TOOL -> VibrationEffect.EFFECT_CLICK
            GenerationTickKind.REASONING -> VibrationEffect.EFFECT_DOUBLE_CLICK
        }
        if (!snapshot.supportsEffect(effectId)) return null
        return runCatching { VibrationEffect.createPredefined(effectId) }.getOrNull()
    }

    /**
     * 终端、读图、网页搜索等工具标签首次出现时轻触一次（推理完成走 [onReasoningCompleted]）。
     * 打字 tick 可以因为 32ms 间隔被丢掉；工具标签不行，错过这一次就不会再出现。
     * 间隔还没到就排进队列，等当前 tick 结束再补上。
     */
    fun onLiveToolActivity(view: View?, toolId: String) {
        enqueueOneShotTick(view, toolId, GenerationTickKind.TOOL, isToolStepsEnabled())
    }

    /** 推理块完成那一下。与工具标签共用去重与排队，但各自走自己的开关和质感。 */
    fun onReasoningCompleted(view: View?, toolId: String) {
        enqueueOneShotTick(view, toolId, GenerationTickKind.REASONING, isReasoningDoneEnabled())
    }

    private fun enqueueOneShotTick(
        view: View?,
        toolId: String,
        kind: GenerationTickKind,
        enabled: Boolean,
    ) {
        // 先登记再判开关：这次没震到就是错过了，重新打开开关也不会把历史标签一次性涌出来。
        if (!liveToolTracker.markIfNew(toolId)) return
        if (!enabled) return
        if (pendingTicks.size >= MAX_QUEUED_TICKS) pendingTicks.removeFirst()
        pendingTicks.addLast(kind)
        if (view != null) tickView = view
        scheduleTick()
    }

    private fun scheduleTick() {
        if (tickScheduled) return
        val wait = (GENERATION_TICK_INTERVAL_MS - (SystemClock.uptimeMillis() - lastGenerationTickAt))
            .coerceAtLeast(0L)
        tickScheduled = true
        mainHandler.postDelayed(tickRunnable, wait)
    }

    fun click(view: View?) {
        if (!isTapEnabled()) return
        perform(view, HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun keyboardTap(view: View?) {
        if (!isKeyboardEnabled()) return
        perform(view, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun longPress(view: View?) {
        if (!isLongPressEnabled()) return
        perform(view, HapticFeedbackConstants.LONG_PRESS)
    }

    /** 开关 / 勾选状态翻转（`on` 为翻转后的状态）。 */
    fun toggle(view: View?, on: Boolean) {
        if (!isTapEnabled()) return
        perform(
            view,
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF,
        )
    }

    /** 一次动作最终成功（保存成功、重连成功等）。 */
    fun confirm(view: View?) {
        if (!isTapEnabled()) return
        perform(view, HapticFeedbackConstants.CONFIRM)
    }

    /** 一次动作最终失败或不可用。 */
    fun reject(view: View?) {
        if (!isTapEnabled()) return
        perform(view, HapticFeedbackConstants.REJECT)
    }

    fun tick(view: View?): Boolean = perform(view, HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)

    fun gestureThreshold(view: View?) {
        if (!isTapEnabled()) return
        perform(view, HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
    }

    private fun perform(
        view: View?,
        constant: Int,
        ignoreAppSwitch: Boolean = false,
    ): Boolean {
        if (view == null) return false
        if (!ignoreAppSwitch && !isTouchEnabled()) return false
        // ColorOS / HyperOS 单参数入口会走线性马达主题；带 flags=0 的双参数
        // 会再检查 View.isHapticFeedbackEnabled，升级后这个标志经常是关的。
        return if (ignoreAppSwitch) {
            view.performHapticFeedback(
                constant,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        } else {
            view.performHapticFeedback(constant)
        }
    }

    /**
     * 窗口停止（Activity 退到后台）时系统 tick 会被判为 ignored，但前台服务仍在跑，
     * 所以补一发系统预定义 tick。
     */
    private fun vibrateSystemTick(context: Context) {
        val tick = systemTickEffect
            ?: runCatching { VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) }
                .getOrNull()
                ?.also { systemTickEffect = it }
            ?: return
        vibrateEffect(context, tick)
    }

    /**
     * 直接开给马达。显式标注 [VibrationAttributes.USAGE_TOUCH]：一是让系统按触控反馈归类，
     * 二是只有走这个 usage，系统才会把用户在「声音与振动」里选的强度乘上去
     * （实测 HyperOS 把 TOUCH 判为 MEDIUM，缩放档位 0.71 / 1.0 / 1.4 / 1.96）。
     */
    private fun vibrateEffect(context: Context, effect: VibrationEffect): Boolean {
        if (!isSystemHapticEnabled(context)) return false
        val vibrator = defaultVibrator(context) ?: return false
        return runCatching {
            vibrator.vibrate(effect, touchAttributes())
            true
        }.getOrDefault(false)
    }

    private fun defaultVibrator(context: Context): Vibrator? {
        cachedVibrator?.let { return it }
        return runCatching {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?.takeIf { it.hasVibrator() }
        }.getOrNull()?.also { cachedVibrator = it }
    }

    private fun touchAttributes(): VibrationAttributes =
        cachedTouchAttributes
            ?: VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
                .also { cachedTouchAttributes = it }

    @Suppress("DEPRECATION")
    private fun isSystemHapticEnabled(context: Context): Boolean =
        runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1,
            ) != 0
        }.getOrDefault(true)
}

@Composable
internal fun ApplyTouchHapticFeedbackEnabled() {
    val view = LocalView.current
    val prefs = remember { Prefs.localAgentPreferences() }
    var enabled by remember {
        mutableStateOf(TouchHaptics.isTouchEnabled())
    }
    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.HAPTIC_TOUCH_FEEDBACK) {
                enabled = TouchHaptics.isTouchEnabled()
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        enabled = TouchHaptics.isTouchEnabled()
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SideEffect {
        if (view.isHapticFeedbackEnabled != enabled) {
            view.isHapticFeedbackEnabled = enabled
        }
    }
}

/**
 * 让官方组件自带的触感也受「震动」设置页的细分开关控制，并且修掉一批根本播不出来的震动。
 *
 * `Modifier.combinedClickable` 之类的组件不经过 [TouchHaptics]，它们直接调
 * [LocalHapticFeedback]。这里在主题外层接管两件事：
 *
 * 1. 长按接到 [TouchHaptics.longPress]，「长按反馈」开关才真的管得住 M3 分段行的内建长按。
 * 2. Compose 的默认实现会把非长按的类型统统压成 [HapticFeedbackConstants.TEXT_HANDLE_MOVE]。
 *    实测这台 HyperOS 对 constant=9 一律回 `ignored_unsupported`（`dumpsys vibrator_manager`
 *    里 Eta 自己就有 15 条），也就是说 `VirtualKey` / `ContextClick` 这些按下是彻底没声音的。
 *    翻译成 [TouchHaptics.click]（实测 constant=6 播得出来）之后才有手感，
 *    并且归进「点按与控件反馈」这个开关。
 *
 * 其余类型原样交给上层实现，选区手柄之类不在这页的开关范围内，行为保持不变。
 */
@Composable
internal fun ProvideEtaHapticFeedback(content: @Composable () -> Unit) {
    val view = LocalView.current
    val parent = LocalHapticFeedback.current
    val haptic = remember(view, parent) { EtaHapticFeedback(parent, view) }
    CompositionLocalProvider(LocalHapticFeedback provides haptic, content = content)
}

private class EtaHapticFeedback(
    private val parent: HapticFeedback,
    private val view: View,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        when (hapticFeedbackType) {
            HapticFeedbackType.LongPress -> TouchHaptics.longPress(view)
            HapticFeedbackType.VirtualKey,
            HapticFeedbackType.ContextClick,
            -> TouchHaptics.click(view)
            else -> parent.performHapticFeedback(hapticFeedbackType)
        }
    }
}
