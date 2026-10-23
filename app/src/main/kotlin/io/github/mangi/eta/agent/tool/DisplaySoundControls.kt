package io.github.mangi.eta.agent.tool

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import org.json.JSONObject

/** 只读的显示与声音状态，以及需要 Root 的息屏时间设置。 */
internal class DisplaySoundControls(
    private val context: Context,
    private val execute: (String) -> BoundedRootCommandExecutor.Result,
) {
    fun sound(): JSONObject {
        val audio = context.getSystemService(AudioManager::class.java)
        return JSONObject()
            .put("ok", audio != null)
            .put("tool", "get_sound_state")
            .put(
                "ringer_mode",
                when (audio?.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    AudioManager.RINGER_MODE_NORMAL -> "normal"
                    else -> JSONObject.NULL
                },
            )
            .put(
                "do_not_disturb",
                context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
                    ?: JSONObject.NULL,
            )
            .put(
                "streams",
                JSONObject().also { streams ->
                    mapOf(
                            "media" to AudioManager.STREAM_MUSIC,
                            "ring" to AudioManager.STREAM_RING,
                            "notification" to AudioManager.STREAM_NOTIFICATION,
                            "alarm" to AudioManager.STREAM_ALARM,
                            "voice_call" to AudioManager.STREAM_VOICE_CALL,
                            "system" to AudioManager.STREAM_SYSTEM,
                        )
                        .forEach { (name, stream) ->
                            if (audio != null)
                                streams.put(
                                    name,
                                    JSONObject()
                                        .put("level", audio.getStreamVolume(stream))
                                        .put("max_level", audio.getStreamMaxVolume(stream)),
                                )
                        }
                },
            )
    }

    fun display(): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("tool", "get_display_state")
            .put("brightness_percent", brightness() ?: JSONObject.NULL)
            .put(
                "auto_brightness",
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    0,
                ) == 1,
            )
            .put(
                "auto_rotate",
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    0,
                ) == 1,
            )
            .put(
                "screen_timeout_seconds",
                Settings.System.getLong(
                    context.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    0,
                ) / 1000,
            )

    fun timeout(args: JSONObject): JSONObject {
        val seconds = args.optInt("seconds", 30).coerceIn(15, 1800)
        val key = Settings.System.SCREEN_OFF_TIMEOUT
        val before = Settings.System.getLong(context.contentResolver, key, -1)
        val result =
            execute(
                "settings --user ${DeviceToolContract.appUserId(context)} put system $key ${seconds * 1000}"
            )
        val after = Settings.System.getLong(context.contentResolver, key, -1)
        return DeviceMutationResult.observed(
            "set_screen_timeout",
            DeviceToolContract.appUserId(context),
            "app_user",
            before,
            after,
            seconds * 1000L,
            result.ok,
        )
    }

    private fun brightness(): Double? {
        val result = execute("cmd display get-brightness --unit percentage")
        return if (result.ok && !result.truncated)
            result.stdout.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
        else null
    }
}
