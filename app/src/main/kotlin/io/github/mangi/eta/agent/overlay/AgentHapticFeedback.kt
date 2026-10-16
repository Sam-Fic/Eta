package io.github.mangi.eta.agent.overlay

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import io.github.mangi.eta.config.Prefs

/**
 * Agent 前台操作、前台服务与本地工具回调（无 `View` 可用）的触感语义。
 *
 * 与 [io.github.mangi.eta.ui.haptics.TouchHaptics] 保持同一策略：只放系统预定义 effect，
 * 不再叠加自定义振幅，交给系统按设备线性马达能力渲染；并显式标注 `USAGE_TOUCH`，
 * 让系统按「触控反馈」用途分类（免打扰 / 系统总开关），避免落到 `USAGE_UNKNOWN`。
 */
internal object AgentHapticFeedback {
    enum class Type(val effectId: Int) {
        TAP(VibrationEffect.EFFECT_TICK),
        LONG_PRESS(VibrationEffect.EFFECT_HEAVY_CLICK),
        SWIPE(VibrationEffect.EFFECT_TICK),
        RUN_STARTED(VibrationEffect.EFFECT_CLICK),
    }

    fun perform(context: Context, type: Type) {
        if (!Prefs.isEnabled(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK)) return
        if (!isSystemHapticEnabled(context)) return
        val vibrator = context.getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?: return
        if (!vibrator.hasVibrator()) return

        runCatching {
            vibrator.vibrate(
                VibrationEffect.createPredefined(type.effectId),
                VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build(),
            )
        }
    }

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
