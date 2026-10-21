package io.github.mangi.eta.agent.mobileactions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import io.github.mangi.eta.R
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 模型输出被解析出的一个设备动作。
 *
 * 这一层是**内部**模型，与工具签名的 snake_case 解耦：模型侧的 snake_case 名字只在
 * [MobileActionsToolSet] 出现，这里保持 Kotlin 惯例。
 */
internal sealed interface MobileActionsAction {
    data object FlashlightOn : MobileActionsAction
    data object FlashlightOff : MobileActionsAction

    data class CreateContact(
        val firstName: String,
        val lastName: String,
        val phoneNumber: String,
        val email: String,
    ) : MobileActionsAction

    data class SendEmail(val to: String, val subject: String, val body: String) : MobileActionsAction

    data class ShowMap(val query: String) : MobileActionsAction

    data object OpenWifiSettings : MobileActionsAction

    data class CreateCalendarEvent(val title: String, val datetime: String) : MobileActionsAction
}

/** 一次动作执行的结果；[message] 已本地化，可直接展示。 */
internal data class MobileActionsOutcome(val ok: Boolean, val message: String)

/**
 * 真正调用 Android API 的动作分发器。
 *
 * 原则：
 * - 联系人/日历/邮件/地图/WiFi 设置都走 **系统 Intent**（预填后交给用户确认），
 *   不写 ContentProvider，因此不需要 WRITE_CONTACTS / WRITE_CALENDAR 等运行时权限；
 * - 手电筒是唯一直接调系统 API 的动作（`setTorchMode`），失败时给出可读原因；
 * - 所有异常都收敛成 [MobileActionsOutcome]，绝不让模型回调线程崩掉推理进程。
 */
internal object MobileActionsDispatcher {
    fun run(context: Context, action: MobileActionsAction): MobileActionsOutcome = when (action) {
        MobileActionsAction.FlashlightOn -> flashlight(context, enabled = true)
        MobileActionsAction.FlashlightOff -> flashlight(context, enabled = false)
        is MobileActionsAction.CreateContact -> createContact(context, action)
        is MobileActionsAction.SendEmail -> sendEmail(context, action)
        is MobileActionsAction.ShowMap -> showMap(context, action.query)
        MobileActionsAction.OpenWifiSettings -> launch(context, Intent(Settings.ACTION_WIFI_SETTINGS))
        is MobileActionsAction.CreateCalendarEvent -> createCalendarEvent(context, action)
    }

    private fun flashlight(context: Context, enabled: Boolean): MobileActionsOutcome {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return failure(context.getString(R.string.mobile_actions_error_no_flash))
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return failure(context.getString(R.string.mobile_actions_error_no_flash))
        return runCatching {
            manager.setTorchMode(cameraId, enabled)
            MobileActionsOutcome(ok = true, message = "")
        }.getOrElse { error ->
            failure(context.getString(R.string.mobile_actions_error_generic, error.javaClass.simpleName))
        }
    }

    private fun createContact(
        context: Context,
        action: MobileActionsAction.CreateContact,
    ): MobileActionsOutcome {
        val name = listOf(action.firstName, action.lastName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            if (name.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.NAME, name)
            if (action.phoneNumber.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.PHONE, action.phoneNumber)
                putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
            }
            if (action.email.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.EMAIL, action.email)
                putExtra(ContactsContract.Intents.Insert.EMAIL_TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
            }
        }
        return launch(context, intent)
    }

    private fun sendEmail(
        context: Context,
        action: MobileActionsAction.SendEmail,
    ): MobileActionsOutcome {
        val query = buildString {
            append("subject=").append(encode(action.subject))
            if (action.body.isNotBlank()) append("&body=").append(encode(action.body))
        }
        val sendTo = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${encode(action.to)}?$query"))
        val direct = launchOrNull(context, sendTo)
        if (direct != null) return direct
        // 没有 SENDTO 处理器时退回通用分享，至少让用户能复制/转发内容。
        return launch(context, Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(action.to))
            putExtra(Intent.EXTRA_SUBJECT, action.subject)
            putExtra(Intent.EXTRA_TEXT, action.body)
        })
    }

    private fun showMap(context: Context, query: String): MobileActionsOutcome =
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${encode(query)}")))

    private fun createCalendarEvent(
        context: Context,
        action: MobileActionsAction.CreateCalendarEvent,
    ): MobileActionsOutcome {
        val begin = runCatching {
            LocalDateTime.parse(action.datetime)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrElse { System.currentTimeMillis() }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, action.title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            // 模型只给开始时间，默认一小时。
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60 * 1000L)
        }
        return launch(context, intent)
    }

    /** 启动 Intent；返回 null 表示当前设备没有处理器（供调用方回退）。 */
    private fun launchOrNull(context: Context, intent: Intent): MobileActionsOutcome? = try {
        context.startActivity(intent.withLaunchFlags(context))
        MobileActionsOutcome(ok = true, message = "")
    } catch (_: ActivityNotFoundException) {
        null
    } catch (error: Exception) {
        failure(context.getString(R.string.mobile_actions_error_generic, error.javaClass.simpleName))
    }

    private fun launch(context: Context, intent: Intent): MobileActionsOutcome = try {
        context.startActivity(intent.withLaunchFlags(context))
        MobileActionsOutcome(ok = true, message = "")
    } catch (_: ActivityNotFoundException) {
        // 异常文案会带出 Intent 内容（含模型生成的查询词），只给出固定提示。
        failure(context.getString(R.string.mobile_actions_error_no_app))
    } catch (error: Exception) {
        failure(context.getString(R.string.mobile_actions_error_generic, error.javaClass.simpleName))
    }

    private fun Intent.withLaunchFlags(context: Context): Intent = apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun failure(message: String) = MobileActionsOutcome(ok = false, message = message)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
