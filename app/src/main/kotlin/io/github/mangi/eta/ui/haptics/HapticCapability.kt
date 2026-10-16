package io.github.mangi.eta.ui.haptics

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * 宽频线性马达到底给了多少表现力，只能运行时探测，不能按品牌猜。
 *
 * AOSP 的表现力接口分两级，两级都是厂商可选实现：
 * - [VibrationEffect.Composition] 的原语（API 31 起，`LOW_TICK` / `QUICK_RISE` 等 API 33 起）；
 * - 振幅与频率包络 `VibrationEffect.WaveformEnvelopeBuilder`（API 36 起）。
 *
 * 实测一台 HyperOS（2304FPN6DC，`dumpsys vibrator_manager`）：
 * `supportedPrimitives = []`、`compositionSizeMax = 0`、`mMaxEnvelopeEffectSize = 0`、
 * `q-factor = NaN`、`frequencyProfile` 的 min/max/resonant 全是 NaN ——
 * 也就是原语和包络两级都不给，只剩 `supportedEffects` 里 AOSP 的
 * CLICK / DOUBLE_CLICK / TICK / HEAVY_CLICK 加上一长串厂商私有 id。
 * 所以这里会判成 [Tier.EFFECT]；换到把原语报上来的机器才会走 [Tier.PRIMITIVE]。
 *
 * 厂商私有 id（实测被播出来的 6 / 8 / 9 / 166 / 169）不在考虑范围内：没有文档、
 * 随机型重新编号，而且绕开 HyperOS 自己那张 constant → 效果 的调校表。
 *
 * 包络那一级目前也不接：要用它就得自己描振幅曲线，等于把「一律用系统调校好的触感」
 * 这条决定推翻。等想清楚要什么质感再说，探测口径已经在这里了。
 */
internal object HapticCapability {
    enum class Tier {
        /** 厂商实现了 Composition 原语，用语义各自的原语拼质感。 */
        PRIMITIVE,

        /** 没有原语，但 AOSP 预定义效果能播。 */
        EFFECT,

        /** 两级都不给，只能退回 `performHapticFeedback` 的系统语义。 */
        CONSTANT,
    }

    /** 探测结果。[primitives] 与 [effects] 存的是这台机器支持的 AOSP id。 */
    class Snapshot(
        val tier: Tier,
        private val primitives: Set<Int>,
        private val effects: Set<Int>,
    ) {
        fun supportsPrimitive(id: Int): Boolean = id in primitives

        fun supportsEffect(id: Int): Boolean = id in effects
    }

    /** 本项目真正会用的三种原语，缺一就不走 PRIMITIVE 档。 */
    private val RequiredPrimitives = intArrayOf(
        VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_THUD,
    )

    private val AllPrimitives = RequiredPrimitives + intArrayOf(
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
        VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
        VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
        VibrationEffect.Composition.PRIMITIVE_SPIN,
    )

    private val PredefinedEffects = intArrayOf(
        VibrationEffect.EFFECT_TICK,
        VibrationEffect.EFFECT_CLICK,
        VibrationEffect.EFFECT_DOUBLE_CLICK,
        VibrationEffect.EFFECT_HEAVY_CLICK,
    )

    @Volatile
    private var cached: Snapshot? = null

    /** 马达能力在进程生命周期内不变，探测一次存着。外接马达热插拔不管。 */
    fun of(context: Context): Snapshot {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: read(context.applicationContext).also { cached = it }
        }
    }

    private fun read(context: Context): Snapshot {
        val vibrator = runCatching {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        }.getOrNull()
        if (vibrator == null || !runCatching { vibrator.hasVibrator() }.getOrDefault(false)) {
            return Snapshot(Tier.CONSTANT, emptySet(), emptySet())
        }
        // 两个探测接口返回约定不一样：原语回 boolean[]，预定义效果回 int[]
        // （不支持的那一项填 -1），所以分开写，不套成同一个泛型入口。
        val primitives = runCatching { vibrator.arePrimitivesSupported(*AllPrimitives) }
            .getOrNull()
            ?.mapIndexed { index, ok -> if (ok) AllPrimitives[index] else -1 }
            ?.filter { it >= 0 }
            ?.toSet()
            ?: emptySet()
        val effects = runCatching { vibrator.areEffectsSupported(*PredefinedEffects) }
            .getOrNull()
            ?.mapIndexed { index, supported -> if (supported >= 0) PredefinedEffects[index] else -1 }
            ?.filter { it >= 0 }
            ?.toSet()
            ?: emptySet()
        val tier = when {
            RequiredPrimitives.all { it in primitives } -> Tier.PRIMITIVE
            effects.isNotEmpty() -> Tier.EFFECT
            else -> Tier.CONSTANT
        }
        return Snapshot(tier, primitives, effects)
    }
}
