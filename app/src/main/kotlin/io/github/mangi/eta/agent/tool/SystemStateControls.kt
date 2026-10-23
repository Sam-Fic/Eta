package io.github.mangi.eta.agent.tool

import android.content.Context
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.os.PowerManager
import android.provider.Settings
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import org.json.JSONObject

/** 只读的系统开关状态；不接受模型提供的设置键或命令。 */
internal class SystemStateControls(
    private val context: Context,
    private val execute: (String) -> BoundedRootCommandExecutor.Result,
) {
    fun read(target: String): Boolean? =
        try {
            when (target) {
                "airplane_mode" ->
                    Settings.Global.getInt(
                        context.contentResolver,
                        Settings.Global.AIRPLANE_MODE_ON,
                        0,
                    ) == 1
                "location" ->
                    context.getSystemService(LocationManager::class.java)?.isLocationEnabled
                "battery_saver" ->
                    context.getSystemService(PowerManager::class.java)?.isPowerSaveMode
                "nfc" -> NfcAdapter.getDefaultAdapter(context)?.isEnabled
                "auto_rotate" -> settingBoolean(Settings.System.ACCELEROMETER_ROTATION)
                "auto_brightness" -> settingBoolean(Settings.System.SCREEN_BRIGHTNESS_MODE)
                "mobile_data" -> mobileDataEnabled()
                "night_light" -> nightLightEnabled()
                "dark_mode" ->
                    execute("cmd uimode night")
                        .takeIf { it.ok && !it.truncated }
                        ?.stdout
                        ?.let {
                            when {
                                Regex("(?i)night mode: yes").containsMatchIn(it) -> true
                                Regex("(?i)night mode: no").containsMatchIn(it) -> false
                                else -> null
                            }
                        }
                else -> null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }

    @Suppress("DEPRECATION")
    private fun mobileDataEnabled(): Boolean? =
        runCatching {
            val subscription = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
            if (!android.telephony.SubscriptionManager.isValidSubscriptionId(subscription)) return null
            context
                .getSystemService(android.telephony.TelephonyManager::class.java)
                .createForSubscriptionId(subscription)
                .isDataEnabled
        }.getOrNull()

    /** 护眼模式的开关状态走隐藏的系统服务；读取不需要特权，不可用时返回 null。 */
    private fun nightLightEnabled(): Boolean? =
        runCatching {
            val type = Class.forName("android.hardware.display.ColorDisplayManager")
            val available =
                type.getMethod("isNightDisplayAvailable", Context::class.java).invoke(null, context) as Boolean
            if (!available) return null
            val manager = context.getSystemService("color_display") ?: return null
            type.getMethod("isNightDisplayActivated").invoke(manager) as Boolean
        }.getOrNull()

    private fun settingBoolean(key: String): Boolean? =
        when (Settings.System.getInt(context.contentResolver, key, -1)) {
            1 -> true
            0 -> false
            else -> null
        }

    companion object {
        val targets =
            listOf(
                "wifi",
                "bluetooth",
                "mobile_data",
                "airplane_mode",
                "location",
                "nfc",
                "battery_saver",
                "auto_rotate",
                "auto_brightness",
                "dark_mode",
                "night_light",
            )
    }
}
