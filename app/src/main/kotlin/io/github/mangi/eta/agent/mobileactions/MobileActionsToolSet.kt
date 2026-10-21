package io.github.mangi.eta.agent.mobileactions

import android.content.Context
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * 暴露给 FunctionGemma 的工具集合。
 *
 * ## 函数名/参数名为什么是 snake_case
 *
 * 该 checkpoint（`litert-community/functiongemma-270m-ft-mobile-actions`）是在
 * `google/mobile-actions` 数据集上微调的，**训练时的工具签名就是 snake_case**：
 * `turn_on_flashlight` / `create_contact(first_name, last_name, phone_number, email)` /
 * `send_email(to, subject, body)` / `show_map(query)` / `open_wifi_settings` /
 * `create_calendar_event(title, datetime)`。
 *
 * 函数名与参数名会原样进模型提示词（`<start_function_declaration>`），模型再把名字抄回
 * `call:<name>{...}` 里，因此这里**必须逐字对齐训练分布**，不能照抄 AI Edge Gallery 的
 * camelCase 写法（那套能跑通，但属于分布外）。下面这些名字看着不符合 Kotlin 惯例，
 * 是刻意为之，不要改名。
 *
 * 必填项同样对齐数据集：`create_contact` 只有 first_name/last_name 必填，
 * `send_email` 只有 to/subject 必填——所以可选参数给默认值，由 LiteRT-LM 的
 * `callBy` 在模型省略时补默认值。
 *
 * 模型在 LiteRT-LM 内自动调用这些函数（`automaticToolCalling` 默认开启），
 * 函数体负责真正执行动作并把结果回给模型；结果同时交给 [onOutcome] 供 UI 展示。
 */
internal class MobileActionsToolSet(
    private val context: Context,
    private val onOutcome: (MobileActionsAction, MobileActionsOutcome) -> Unit,
) : ToolSet {
    /** Turns on flashlight. */
    @Tool(description = "Turns the flashlight on.")
    fun turn_on_flashlight(): Map<String, String> = report(MobileActionsAction.FlashlightOn)

    /** Turns off flashlight. */
    @Tool(description = "Turns the flashlight off.")
    fun turn_off_flashlight(): Map<String, String> = report(MobileActionsAction.FlashlightOff)

    /** Creates contact. */
    @Tool(description = "Creates a contact in the phone's contact list.")
    fun create_contact(
        @ToolParam(description = "The first name of the contact.") first_name: String,
        @ToolParam(description = "The last name of the contact.") last_name: String,
        @ToolParam(description = "The phone number of the contact.") phone_number: String? = null,
        @ToolParam(description = "The email address of the contact.") email: String? = null,
    ): Map<String, String> = report(
        action = MobileActionsAction.CreateContact(
            firstName = first_name,
            lastName = last_name,
            phoneNumber = phone_number.orEmpty(),
            email = email.orEmpty(),
        ),
        echoed = mapOf(
            "first_name" to first_name,
            "last_name" to last_name,
            "phone_number" to phone_number.orEmpty(),
            "email" to email.orEmpty(),
        ),
    )

    /** Sends email. */
    @Tool(description = "Sends an email.")
    fun send_email(
        @ToolParam(description = "The email address of the recipient.") to: String,
        @ToolParam(description = "The subject of the email.") subject: String,
        @ToolParam(description = "The body of the email.") body: String? = null,
    ): Map<String, String> = report(
        action = MobileActionsAction.SendEmail(
            to = to,
            subject = subject,
            body = body.orEmpty(),
        ),
        echoed = mapOf("to" to to, "subject" to subject, "body" to body.orEmpty()),
    )

    /** Shows location on map. */
    @Tool(description = "Shows a location on the map.")
    fun show_map(
        @ToolParam(
            description = "The location to search for. May be the name of a place, a business, or an address.",
        )
        query: String,
    ): Map<String, String> = report(
        action = MobileActionsAction.ShowMap(query = query),
        echoed = mapOf("query" to query),
    )

    /** Opens wifi settings. */
    @Tool(description = "Opens the Wi-Fi settings.")
    fun open_wifi_settings(): Map<String, String> = report(MobileActionsAction.OpenWifiSettings)

    /** Creates calendar events. */
    @Tool(description = "Creates a new calendar event.")
    fun create_calendar_event(
        @ToolParam(description = "The title of the event.") title: String,
        @ToolParam(description = "The date and time of the event in the format YYYY-MM-DDTHH:MM:SS.")
        datetime: String,
    ): Map<String, String> = report(
        action = MobileActionsAction.CreateCalendarEvent(title = title, datetime = datetime),
        echoed = mapOf("title" to title, "datetime" to datetime),
    )

    /**
     * 执行动作并把结果回给模型。
     *
     * 成功时回 `result=success` 并回显参数（官方形状）；失败时回 `result=error` 与原因，
     * 让模型如实告诉用户失败，而不是声称已完成。
     */
    private fun report(
        action: MobileActionsAction,
        echoed: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val outcome = MobileActionsDispatcher.run(context, action)
        onOutcome(action, outcome)
        return if (outcome.ok) {
            mapOf("result" to "success") + echoed
        } else {
            mapOf("result" to "error", "message" to outcome.message)
        }
    }
}
