package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 常用设备能力的结构化 schema；按风险组决定是否向模型公开。 */
internal object AgentDeviceToolCatalog {
    fun appendTo(
        tools: JSONArray,
        directTools: Boolean,
        sensitiveReadTools: Boolean,
        sensitiveActionTools: Boolean,
    ) {
        if (directTools) appendDirectTools(tools)
        if (sensitiveReadTools) appendSensitiveReadTools(tools)
        if (sensitiveActionTools) appendSensitiveActionTools(tools)
    }

    private fun appendDirectTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "inspect_app",
                    "读取当前 Android 用户中指定应用的版本、UID、安装来源与路径、启用状态和请求权限，不要求应用具有桌面入口。" +
                        "只支持 Eta 所属用户，不跨工作资料查询。权限列表按 permission_offset/permission_limit 分页。",
                    properties(
                        "package_name" to string("精确 Android 包名", 255),
                        "user_id" to integer("Eta 所属 Android 用户 id，通常无需传入", 0, 2_147_483_647),
                        "permission_offset" to integer("请求权限列表的起始索引，默认 0", 0, 2_147_483_647),
                        "permission_limit" to integer("最多返回权限数量，默认 100", 1, 200),
                    ),
                    "package_name",
                ),
            )
            .put(
                function(
                    "set_alarm",
                    "直接创建系统闹钟，不要用 GUI。涉及相对日期时先用 get_current_context 换算；hour/minute 使用设备本地时间。系统不接受直达操作时可能只打开时钟页面。",
                    properties(
                        "hour" to integer("0 到 23", 0, 23),
                        "minute" to integer("0 到 59", 0, 59),
                        "label" to string("闹钟标签，最多 100 字", 100),
                        "repeat_days" to stringArray(
                            "重复星期；不提供表示仅下一次",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                        "vibrate" to boolean("是否振动，默认 true"),
                    ),
                    "hour", "minute",
                ),
            )
            .put(
                function(
                    "set_timer",
                    "直接创建系统计时器，不要用 GUI。duration_seconds 必须是 1 到 86400 秒。",
                    properties(
                        "duration_seconds" to integer("计时秒数", 1, 86_400),
                        "label" to string("计时器标签，最多 100 字", 100),
                    ),
                    "duration_seconds",
                ),
            )
            .put(emptyFunction("device_status", "读取电池、内存、存储、系统版本与开机时长。"))
            .put(emptyFunction("network_info", "读取当前联网方式、联网验证状态和当前 Wi‑Fi 基本信息，不返回保存的密码。"))
            .put(limitFunction("top_memory_apps", "按当前 RSS 列出内存占用最高的进程。"))
            .put(limitFunction("top_storage_apps", "按应用、数据与缓存合计列出存储占用最高的应用。"))
            .put(
                function(
                    "media_control",
                    "直接控制当前媒体会话，不要操作播放器 GUI。",
                    properties(
                        "action" to enumString(
                            "媒体动作",
                            "play", "pause", "play_pause", "next", "previous", "stop",
                        ),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "set_volume",
                    "直接设置系统音量，不要操作音量 GUI。",
                    properties(
                        "stream" to enumString("音量通道", "media", "alarm", "ring", "notification"),
                        "percent" to integer("0 到 100 的音量百分比", 0, 100),
                    ),
                    "stream", "percent",
                ),
            )
            .put(
                function(
                    "set_ringer_mode",
                    "直接切换铃声模式（响铃/振动/静音），不要操作音量 GUI。无需 Root。" +
                        "静音只让电话和通知不响铃，媒体音量不受影响；需要连媒体一起静音请改用 set_volume。" +
                        "需要拦截所有通知请用 set_do_not_disturb，二者不是一回事。",
                    properties(
                        "mode" to enumString("铃声模式", "normal", "vibrate", "silent"),
                    ),
                    "mode",
                ),
            )
            .put(
                function(
                    "set_sound_mode",
                    "切换铃声模式（normal/vibrate/silent），与 set_ringer_mode 同效，免 Root。" +
                        "只影响电话和通知，不改变媒体音量。",
                    properties("mode" to enumString("铃声模式", "normal", "vibrate", "silent")),
                    "mode",
                ),
            )
    }

    private fun appendSensitiveReadTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "get_setting",
                    "读取一个 Android Settings 值。结果可能包含设备标识等敏感信息，原始结果不会持久化。",
                    properties(
                        "namespace" to enumString("设置命名空间", "system", "secure", "global"),
                        "key" to string("精确设置键", 200),
                    ),
                    "namespace", "key",
                ),
            )
            .put(
                function(
                    "wifi_credentials",
                    "读取手机保存的 Wi‑Fi 名称与密码。原始结果不会持久化。",
                    properties(
                        "ssid" to string("可选的精确 Wi‑Fi 名称", 128),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_notifications",
                    "读取当前通知栏中的通知标题与正文。结果不写入持久会话。",
                    properties(
                        "package_name" to string("可选的精确应用包名过滤", 255),
                        "limit" to integer("最多返回数量，默认 10", 1, 20),
                    ),
                ),
            )
            .put(
                function(
                    "search_notification_history",
                    "检索 Eta 在用户授予通知使用权后记录的最近 7 天通知。原始结果不写入持久会话。",
                    properties(
                        "query" to string("可选标题或正文关键词", 200),
                        "package_name" to string("可选精确包名", 255),
                        "max_age_hours" to integer("回溯小时数，默认 24", 1, 168),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_app_activity",
                    "读取最近打开应用的时间顺序，需要系统使用情况访问权。",
                    properties(
                        "package_name" to string("可选精确包名", 255),
                        "max_age_hours" to integer("回溯小时数，默认 24", 1, 168),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "app_usage_summary",
                    "按前台时长汇总最近应用使用情况，需要系统使用情况访问权。",
                    properties(
                        "max_age_hours" to integer("统计小时数，默认 24", 1, 168),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(emptyFunction("get_current_location", "读取系统已有的最近位置，不持续监听或主动唤醒 GPS。"))
            .put(emptyFunction("get_device_environment", "读取锁屏、勿扰、铃声、音频输出和外接显示器状态。"))
            .put(
                function(
                    "list_alarms",
                    "读取系统时钟应用中的闹钟计划，自动适配本机默认时钟应用。",
                    properties(
                        "enabled_only" to boolean("是否只返回已启用闹钟，默认 true"),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "list_active_timers",
                    "读取系统时钟应用中正在运行或暂停的计时器，自动适配本机默认时钟应用。",
                    properties("limit" to integer("最多返回数量，默认 20", 1, 50)),
                ),
            )
            .put(searchFunction("search_clipboard_history", "检索当前系统输入法保存的剪贴板历史。"))
            .put(
                function(
                    "get_health_summary",
                    "汇总系统健康数据中的步数、睡眠、运动、心率、体重和血氧；不返回原始测量序列。",
                    properties("days" to integer("汇总最近天数，默认 7", 1, 30)),
                ),
            )
            .put(
                function(
                    "read_sms_code",
                    "从最近短信中只提取 4 到 8 位验证码、发送方和时间，不返回完整短信正文。",
                    properties(
                        "max_age_minutes" to integer("只检查多少分钟内的短信，默认 10", 1, 1_440),
                    ),
                ),
            )
            .put(
                function(
                    "get_logcat",
                    "读取最近系统日志。query 只在已读取日志中做文本过滤，不会进入 Shell。",
                    properties(
                        "query" to string("可选过滤文本", 200),
                        "max_lines" to integer("最多日志行数，默认 200", 20, 500),
                    ),
                ),
            )
            .put(searchFunction("search_media", "检索本机相册中的图片，可按文件名或相册路径筛选。返回元数据与可打开的 content URI，不读取图片内容。"))
            .put(searchFunction("search_audio", "检索本机音乐和音频文件，可按标题或文件名筛选。"))
            .put(searchFunction("search_recordings", "检索本机录音文件。结果来自系统媒体库，不读取录音转写或音频内容。"))
            .put(searchFunction("search_files", "检索共享存储中的文档和下载文件，可按文件名筛选。不会遍历其他应用私有目录。"))
            .put(searchFunction("search_calendar_events", "检索系统日历事件，可按标题、地点或说明筛选。"))
            .put(searchFunction("search_contacts", "检索系统通讯录联系人，返回姓名和 lookup URI。"))
            .put(searchFunction("search_call_history", "检索通话记录，可按号码或联系人缓存名筛选。"))
            .put(searchFunction("search_messages", "检索短信，可按发送方或正文关键词筛选。结果属于敏感个人内容。"))
            .put(searchFunction("search_downloads", "检索系统下载记录，可按文件名或说明筛选。"))
            .put(searchFunction("search_coloros_notes", "检索 ColorOS 便签和待办，可按标题或正文筛选。仅在安装并可访问 ColorOS 便签时可用。"))
            .put(
                function(
                    "search_notes",
                    "search_coloros_notes 的别称，检索 ColorOS 便签和待办。" +
                        "原始便签不可读时回退到历史索引；current_only=true 时只查原始便签，不回退。",
                    properties(
                        "query" to string("可选关键词，最多 200 字"),
                        "limit" to integer("最多返回数量，默认 10", 1, 30),
                        "current_only" to boolean("仅使用当前原始便签来源，不回退历史索引"),
                    ),
                ),
            )
            .put(
                function(
                    "read_note",
                    "按 note_id 读取一条 ColorOS 便签的标题、正文和更新时间。需 Root 直达便签 Provider。",
                    properties("note_id" to string("便签返回的原始 UUID", 36)),
                    "note_id",
                ),
            )
            .put(
                function(
                    "list_calendars",
                    "列出当前用户可写入的系统日历（calendar_id、名称、账户、是否只读）。" +
                        "创建日程前不确定写哪个日历时先调用；返回空表示需要先在日历应用中建日历。",
                    properties(),
                ),
            )
            .put(
                function(
                    "read_calendar_event",
                    "按原始 event_id 读取单条系统日历事件的完整字段、提醒与重复规则。" +
                        "event_id 必须来自 search_calendar_events，不要传系统记忆里的文档 ID。",
                    properties("event_id" to integer("日程 id", 1, 2_147_483_647)),
                    "event_id",
                ),
            )
            .put(emptyFunction("get_flashlight", "读取手电筒开关状态。状态来自 CameraManager 托管灯光连接，读取不开灯。"))
            .put(emptyFunction("get_sound_state", "读取铃声模式、勿扰状态和各音频通道当前/最大音量，不修改任何设置。"))
            .put(emptyFunction("get_hotspot", "读取个人热点是否开启；普通应用只读，不改变热点。"))
            .put(
                function(
                    "get_display_state",
                    "读取屏幕亮度百分比、自动亮度、自动旋转与息屏时长。需 Root 才能读取系统当前亮度。",
                    properties(),
                ),
            )
            .put(
                function(
                    "get_device_state",
                    "读取一个系统开关的当前状态，只读、不修改。target 支持 wifi/bluetooth/mobile_data/" +
                        "airplane_mode/location/nfc/battery_saver/auto_rotate/auto_brightness/dark_mode/night_light。" +
                        "返回 enabled=null 表示当前设备无法确认该状态。",
                    properties(
                        "target" to enumString(
                            "设备能力",
                            "wifi", "bluetooth", "mobile_data", "airplane_mode", "location", "nfc",
                            "battery_saver", "auto_rotate", "auto_brightness", "dark_mode", "night_light",
                        ),
                    ),
                    "target",
                ),
            )
            .put(searchFunction("search_coloros_recordings", "检索 ColorOS 录音应用中的普通录音和通话录音，返回名称、时长、类型和文件路径。"))
            .put(searchFunction("search_recording_summaries", "检索 ColorOS 录音关联的转写摘要和便签内容。仅在录音应用生成过摘要时可用。"))
            .put(searchFunction("search_coloros_memories", "检索 ColorOS 系统记忆，可读取已收集的信息、账单、日程、取件码、快递、地点和附件等关联内容。"))
            .put(searchFunction("search_saved_places", "检索系统记忆中保存或识别的地点。"))
            .put(searchFunction("search_personal_orders", "检索系统记忆中识别的外卖、购物、快递、票券和出行订单。"))
            .put(personalSearchFunction("search_bills", "按商户、内容和时间查找账单记录。"))
            .put(personalSearchFunction("search_todos", "查询系统从个人信息中提取的待办线索，未识别时间的记录不能按时间命中。"))
            .put(personalSearchFunction("search_calendar_todos", "查询日历中的待办及其计划时间。"))
            .put(personalSearchFunction("search_memory_collections", "查找已保存的记忆合集，不支持业务时间筛选。"))
            .put(personalSearchFunction("search_daily_events", "查找系统记录或推断的生活事件，不把推断当成确认事实。"))
            .put(personalSearchFunction("search_flights", "按航班、机场和计划起飞时间检索行程。"))
            .put(personalSearchFunction("search_hotels", "按酒店、地址、订单和入住时间检索预订。"))
            .put(personalSearchFunction("search_trains", "按车次、车站和出发时间检索行程。"))
            .put(
                function(
                    "read_personal_item",
                    "使用个人数据查询返回的 ref 读取索引详情；不能用该引用直接修改原始记录。",
                    properties("ref" to string("查询结果返回的完整 ref", 160)),
                    "ref",
                ),
            )
            .put(
                function(
                    "summarize_bills",
                    "精确汇总匹配的账单索引，区分收入、支出、转账和未知类型；最多完整汇总 1000 条，" +
                        "范围过大时失败而不返回部分总额。",
                    properties(
                        "query" to string("可选关键词，最多 200 字"),
                        "start_time" to string("包含的起始时间，带时区偏移的 ISO 8601", 64),
                        "end_time" to string("不包含的结束时间，带时区偏移的 ISO 8601", 64),
                    ),
                ),
            )
            .put(searchFunction("search_qq_chat_images", "检索 QQ 聊天图片缓存，返回最近文件的时间、大小、类型和私有路径。仅在安装 QQ 且缓存仍存在时可用。"))
            .put(searchFunction("search_wechat_chat_images", "检索微信聊天图片缓存，返回最近文件的时间、大小和私有路径。仅在安装微信且缓存仍存在时可用。"))
            .put(
                function(
                    "list_installed_apps",
                    "列出已安装应用（含系统应用与已禁用应用），返回包名、应用名、版本、是否系统应用与启用状态。免 Root。" +
                        "结果属于设备隐私，不会写入持久会话。找到目标后启动请用 launch_app 传 package_name。",
                    properties(
                        "filter" to enumString("过滤范围，默认 all", "all", "user", "system", "disabled"),
                        "limit" to integer("最多返回数量，默认 50", 1, 200),
                    ),
                ),
            )
            .put(
                function(
                    "search_settings",
                    "列出某个 Android Settings 命名空间下的全部键值，支持关键字过滤。需要 Root。" +
                        "用来查找可修改的设置项真实键名（不要凭空猜键名）。找到后用 set_setting 修改。" +
                        "常用场景：确认某项系统开关对应的键、排查 set_setting 失败原因。",
                    properties(
                        "namespace" to enumString("设置命名空间", "system", "secure", "global"),
                        "keyword" to string("按键名或值包含的关键字过滤，可留空", 100),
                        "limit" to integer("最多返回数量，默认 100", 1, 500),
                    ),
                    "namespace",
                ),
            )
            .put(
                function(
                    "dumpsys_diag",
                    "抓取 Android 系统服务（dumpsys）的运行状态，用于诊断。需要 Root。" +
                        "可指定服务名（如 battery、wifi、activity、alarm、job、notification、gfxinfo、cpuinfo、SurfaceFlinger）" +
                        "与 focus 行过滤关键字。输出默认截断到 4000 字符，可用 max_chars 调大。" +
                        "仅用于诊断读取，不要用它修改系统状态。",
                    properties(
                        "service" to string("系统服务名，如 battery/wifi/activity", 40),
                        "focus" to string("只保留包含该关键字的行，可留空", 100),
                        "max_chars" to integer("输出最大字符数，默认 4000", 200, 20_000),
                    ),
                    "service",
                ),
            )
    }

    private fun appendSensitiveActionTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "set_setting",
                    "修改一个 Android Settings 值。免 Root 需要用户已授予 Eta「修改系统设置」权限" +
                        "（未授权时返回 WRITE_SETTINGS_REQUIRED 并指引用户去 设置 → 应用 → Eta → 修改系统设置，" +
                        "此时不要重复调用）；有 Root 时直达。写入后回读校验。" +
                        "常用键：system/screen_off_timeout（灭屏秒数）、system/screen_brightness（0-255）、" +
                        "system/screen_brightness_mode（0 手动 1 自动）、system/accelerometer_rotation（0/1 自动旋转）、" +
                        "system/font_scale（字体倍率）、system/show_touches、secure/ui_night_mode（深色模式）、" +
                        "global/stay_on_while_charging（充电不息屏）。相对日期先用 get_current_context 换算。",
                    properties(
                        "namespace" to enumString("设置命名空间", "system", "secure", "global"),
                        "key" to string("精确设置键", 200),
                        "value" to string("新值", 2_000),
                    ),
                    "namespace", "key", "value",
                ),
            )
            .put(
                function(
                    "set_device_state",
                    "直接启用或关闭 Wi-Fi/蓝牙/飞行模式/移动数据/NFC/热点，不要操作设置 GUI。" +
                        "有 Root 且该开关存在直达命令时直达并回读校验；无 Root、热点开关或 Root 命令失败时，" +
                        "打开对应系统设置面板并返回 ok=false 与 RADIO_PANEL_FALLBACK，" +
                        "此时告诉用户去面板里手动操作，不要重复调用本工具。",
                    properties(
                        "target" to enumString(
                            "设备能力",
                            "wifi", "bluetooth", "airplane_mode", "mobile_data", "nfc", "hotspot",
                        ),
                        "enabled" to boolean("true 启用，false 关闭"),
                    ),
                    "target", "enabled",
                ),
            )
            .put(
                function(
                    "app_state_control",
                    "停止、冻结或解冻一个精确包名，包括系统应用。",
                    properties(
                        "package_name" to string("精确 Android 包名", 255),
                        "action" to enumString("动作", "force_stop", "freeze", "unfreeze"),
                    ),
                    "package_name", "action",
                ),
            )
            .put(
                function(
                    "set_do_not_disturb",
                    "开启或关闭勿扰模式。免 Root 需要用户已授予 Eta 勿扰管理权限" +
                        "（未授权时返回 NOTIFICATION_POLICY_REQUIRED 并指引用户去 设置 → 通知 → 勿扰 授权，" +
                        "此时不要重复调用）；有 Root 时直达。priority_only=true 表示仅优先内容可打扰（免 Root 支持，" +
                        "Root 保底只切换全开/全关并会在结果中说明）。",
                    properties(
                        "enabled" to boolean("true 开启勿扰，false 关闭"),
                        "priority_only" to boolean("是否仅允许优先内容打扰，默认 false"),
                    ),
                    "enabled",
                ),
            )
            .put(
                function(
                    "set_brightness",
                    "设置屏幕亮度或切换自动亮度。免 Root 需要用户已授予 Eta「修改系统设置」权限" +
                        "（未授权时返回 WRITE_SETTINGS_REQUIRED 并指引授权，此时不要重复调用）；有 Root 时直达。" +
                        "auto=true 时忽略 percent。设置后回读校验，亮度允许厂商裁剪的 ±2 误差。",
                    properties(
                        "percent" to integer("亮度百分比，0 到 100；auto=true 时可省略", 0, 100),
                        "auto" to boolean("是否切换为自动亮度，默认 false"),
                    ),
                ),
            )
            .put(
                function(
                    "screen_action",
                    "屏幕电源动作。lock 与 screen_off 同效（息屏即锁屏）。" +
                        "power_menu 免 Root 走无障碍电源对话框；lock/screen_off 与 reboot 需要 Root。" +
                        "reboot 会立即重启设备并中断当前对话，执行前必须先用 ask_user 向用户确认，" +
                        "未获得确认不要调用。",
                    properties(
                        "action" to enumString("电源动作", "lock", "screen_off", "power_menu", "reboot"),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "manage_app",
                    "应用管理：安装 APK、卸载、清除数据、授予或撤销运行时权限。" +
                        "install 的 apk_path 需为设备上的绝对路径（可先用 search_files 搜索 .apk）。" +
                        "免 Root 时 install/uninstall 打开系统安装/卸载界面由用户确认（返回 ok=false，" +
                        "告诉用户去确认，不要重复调用）；clear_data/grant_permission/revoke_permission 需要 Root。" +
                        "权限名需为完整形式，如 android.permission.CAMERA。",
                    properties(
                        "action" to enumString(
                            "管理动作",
                            "install", "uninstall", "clear_data", "grant_permission", "revoke_permission",
                        ),
                        "package_name" to string("目标应用包名；install 时不需要", 255),
                        "apk_path" to string("install 时的 APK 绝对路径", 4_096),
                        "permission" to string("grant/revoke 时的完整权限名，如 android.permission.CAMERA", 200),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "create_calendar_event",
                    "直接在系统日历创建日程（写入日历 Provider，不打开日历界面）。涉及“明天/周四”等相对日期时，" +
                        "先用 get_current_context 取当前时间再换算，不要猜测年份。" +
                        "start 必填，格式 YYYY-MM-DDTHH:MM（设备本地时间，可带秒，也可写作 YYYY-MM-DD HH:MM；" +
                        "只给日期时创建全天日程）。end 省略时默认 start 后 1 小时（全天日程为 1 天）。" +
                        "reminder_minutes 是提前通知分钟数，默认 10，传 0 表示不提醒。" +
                        "repeat 可选 none/daily/weekly/monthly/yearly；repeat=weekly 时可用 repeat_days 指定星期。" +
                        "未获得 Root 时会退化为打开日历预填界面并返回 ok=false，此时告诉用户去日历里确认保存，不要重复调用。",
                    properties(
                        "title" to string("日程标题", 200),
                        "start" to string("开始时间，YYYY-MM-DDTHH:MM 或 YYYY-MM-DD", 32),
                        "end" to string("结束时间，格式同 start；省略按默认时长", 32),
                        "all_day" to boolean("是否全天日程；start 只给日期时自动为 true"),
                        "description" to string("日程说明", 2_000),
                        "location" to string("地点", 500),
                        "reminder_minutes" to integer("提前通知分钟数，默认 10，0 表示不提醒", 0, 20_160),
                        "repeat" to enumString("重复规则", "none", "daily", "weekly", "monthly", "yearly"),
                        "repeat_days" to stringArray(
                            "重复星期，仅 repeat=weekly 时有效",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                    ),
                    "title", "start",
                ),
            )
            .put(
                function(
                    "update_calendar_event",
                    "修改系统日历中已存在的日程，只改提供的字段，未提供的字段保持不变。" +
                        "定位方式二选一：优先用 event_id（search_calendar_events 或创建时返回的 id）；" +
                        "否则用 match_title 按标题查找，可加 match_date(YYYY-MM-DD) 限定那一天。" +
                        "匹配到多个日程时返回候选列表与 EVENT_AMBIGUOUS，此时取候选里的 event_id 重试一次即可，不要重复同样的调用。" +
                        "reminder_minutes 会替换该日程的全部提前通知；repeat 传 none 清除重复规则。" +
                        "时间格式与 create_calendar_event 相同。",
                    properties(
                        "event_id" to integer("日程 id，优先使用", 1, 2_147_483_647),
                        "match_title" to string("按标题定位时的关键词", 200),
                        "match_date" to string("限定日期，YYYY-MM-DD", 10),
                        "title" to string("新标题", 200),
                        "start" to string("新开始时间，格式同 create_calendar_event", 32),
                        "end" to string("新结束时间，格式同 start", 32),
                        "all_day" to boolean("是否改为全天日程"),
                        "description" to string("新说明，传空字符串清除", 2_000),
                        "location" to string("新地点，传空字符串清除", 500),
                        "reminder_minutes" to integer("新的提前通知分钟数，0 表示不提醒", 0, 20_160),
                        "repeat" to enumString("新的重复规则，none 表示清除", "none", "daily", "weekly", "monthly", "yearly"),
                        "repeat_days" to stringArray(
                            "新的重复星期，仅 repeat=weekly 时有效",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                    ),
                ),
            )
            .put(
                function(
                    "delete_calendar_event",
                    "删除系统日历中的日程。定位方式同 update_calendar_event：优先 event_id，" +
                        "否则用 match_title（可加 match_date）。匹配到多个时返回候选列表，取候选里的 event_id 重试一次。" +
                        "重复日程按事件 id 删除会删除整个系列。",
                    properties(
                        "event_id" to integer("日程 id，优先使用", 1, 2_147_483_647),
                        "match_title" to string("按标题定位时的关键词", 200),
                        "match_date" to string("限定日期，YYYY-MM-DD", 10),
                    ),
                ),
            )
            .put(
                function(
                    "delete_alarm",
                    "删除系统时钟里的闹钟（Root 直达写入时钟数据库，不打开时钟界面）。" +
                        "定位方式：优先 alarm_id（list_alarms 返回的 _id）；否则用 match_time(HH:MM) 和 match_label 组合定位。" +
                        "匹配到多个闹钟时返回候选列表与 ALARM_AMBIGUOUS，取候选里的 alarm_id 重试一次；" +
                        "找不到时返回 ALARM_NOT_FOUND，可先用 list_alarms 查看。" +
                        "删除后若时钟界面仍显示旧闹钟，重新打开时钟应用即可刷新，不要重复调用本工具。",
                    properties(
                        "alarm_id" to integer("闹钟 id，优先使用", 1, 2_147_483_647),
                        "match_time" to string("按时间定位，HH:MM", 5),
                        "match_label" to string("按标签关键词定位", 100),
                    ),
                ),
            )
            .put(
                function(
                    "update_alarm",
                    "修改系统时钟里已存在的闹钟，只改提供的字段（enabled/label/vibrate/hour/minute）。" +
                        "定位方式同 delete_alarm：优先 alarm_id，否则 match_time(HH:MM) 与 match_label。" +
                        "修改时间后，一次性闹钟的下次触发时间会自动重算；重复闹钟由时钟应用在下次打开时重算。" +
                        "重复规则（星期）变更请先 delete_alarm 再用 set_alarm 重建，不要反复修改。" +
                        "匹配多个时返回候选列表，取 alarm_id 重试一次。",
                    properties(
                        "alarm_id" to integer("闹钟 id，优先使用", 1, 2_147_483_647),
                        "match_time" to string("按时间定位，HH:MM", 5),
                        "match_label" to string("按标签关键词定位", 100),
                        "enabled" to boolean("是否启用该闹钟"),
                        "hour" to integer("新小时，0 到 23", 0, 23),
                        "minute" to integer("新分钟，0 到 59", 0, 59),
                        "label" to string("新标签，传空字符串清除", 100),
                        "vibrate" to boolean("是否振动"),
                    ),
                ),
            )
            .put(
                function(
                    "create_calendar_events",
                    "在同一个日历里批量创建多条日程（最多 30 条），逐条写入并各自读回校验。" +
                        "每一项的字段与 create_calendar_event 相同；calendar_id 可整体指定，先用 list_calendars 确认。" +
                        "中途失败会停止后续写入并返回 CALENDAR_BATCH_PARTIAL 与已创建列表；" +
                        "此时先 search_calendar_events 确认，只补建失败项，不要重复整批。",
                    properties(
                        "calendar_id" to integer("可选的日历 id", 1, 2_147_483_647),
                        "events" to JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put("maxItems", 30)
                            .put("description", "日程数组，每项必须含 title 与 start")
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        properties(
                                            "title" to string("日程标题，最多 200 字", 200),
                                            "start" to string("开始时间：YYYY-MM-DDTHH:MM 或全天 YYYY-MM-DD", 40),
                                            "end" to string("可选结束时间，格式同 start", 40),
                                            "description" to string("可选备注", 1_000),
                                            "location" to string("可选地点", 200),
                                            "reminder_minutes" to integer("可选提前提醒分钟数", 0, 20_160),
                                        ),
                                    )
                                    .put("required", JSONArray().put("title").put("start")),
                            ),
                    ),
                    "events",
                ),
            )
            .put(
                function(
                    "create_note",
                    "直接创建 ColorOS 便签并读回校验，不打开便签界面。需 Root。" +
                        "标题可为空；正文最多 20000 字。返回 ok=false 且 code=NOTE_CREATE_UNVERIFIED 时，" +
                        "先用 search_coloros_notes 确认，不要重复创建。",
                    properties(
                        "content" to string("便签正文，最多 20000 字", 20_000),
                        "title" to string("可选标题，最多 200 字", 200),
                    ),
                    "content",
                ),
            )
            .put(
                function(
                    "delete_note",
                    "把一条 ColorOS 便签移入回收站并读回确认。需 Root；note_id 必须是 create_note/read_note 返回的原始 UUID。",
                    properties("note_id" to string("便签的原始 UUID", 36)),
                    "note_id",
                ),
            )
            .put(
                function(
                    "set_alarm_enabled",
                    "启用或停用一个系统闹钟，不改时间。需 Root；优先 alarm_id，否则用 match_time/match_label。" +
                        "匹配多个时返回候选，取 alarm_id 重试一次；找不到可先用 list_alarms 查看。",
                    properties(
                        "alarm_id" to integer("闹钟 id，优先使用", 1, 2_147_483_647),
                        "match_time" to string("按时间定位，HH:MM", 5),
                        "match_label" to string("按标签关键词定位", 100),
                        "enabled" to boolean("true 启用，false 停用"),
                    ),
                    "enabled",
                ),
            )
            .put(
                function(
                    "update_alarm_time",
                    "只修改系统闹钟的触发时间（hour/minute），其余字段不变。定位方式同 set_alarm_enabled。" +
                        "一次性闹钟触发时间会自动重算；重复规则变更请先 delete_alarm 再用 set_alarm 重建。",
                    properties(
                        "alarm_id" to integer("闹钟 id，优先使用", 1, 2_147_483_647),
                        "match_time" to string("按原时间定位，HH:MM", 5),
                        "match_label" to string("按标签关键词定位", 100),
                        "hour" to integer("新小时，0 到 23", 0, 23),
                        "minute" to integer("新分钟，0 到 59", 0, 59),
                    ),
                    "hour", "minute",
                ),
            )
            .put(
                function(
                    "set_flashlight",
                    "开启或关闭手电筒并回读状态，不操作相机界面。普通应用即可使用；" +
                        "返回 FLASHLIGHT_BUSY 表示闪光灯被相机占用，不要重复调用。",
                    properties("enabled" to boolean("true 开灯，false 关灯")),
                    "enabled",
                ),
            )
            .put(
                function(
                    "set_screen_timeout",
                    "设置自动息屏时长并回读校验，需 Root。seconds 允许 15 到 1800。",
                    properties("seconds" to integer("息屏秒数", 15, 1_800)),
                    "seconds",
                ),
            )
            .put(
                function(
                    "set_hotspot",
                    "开启或关闭个人热点。普通应用通常没有修改特权，" +
                        "此时会打开无线设置并返回 HOTSPOT_PANEL_FALLBACK/ok=false；" +
                        "告诉用户去面板操作，不要重复调用本工具。",
                    properties("enabled" to boolean("true 开热点，false 关热点")),
                    "enabled",
                ),
            )
            .put(
                function(
                    "manage_file",
                    "复制、移动、删除、重命名设备上的文件或目录，免 Root（应用已持所有文件访问权限）。" +
                        "跨存储或私有路径失败时自动用 Root 保底。写入后回读校验（目标存在、源已消失）。" +
                        "路径必须是设备绝对路径，可用 search_files / list_directory 先定位。" +
                        "操作系统关键目录（/system、/data 顶层等）会被拒绝。",
                    properties(
                        "action" to enumString("动作", "copy", "move", "delete", "rename", "mkdir"),
                        "path" to string("源路径（绝对路径）", 1_024),
                        "dest_path" to string("目标路径；目标是已存在目录时自动落入该目录并保留原名", 1_024),
                        "new_name" to string("rename 的新名称（不含 /）", 255),
                    ),
                    "action", "path",
                ),
            )
            .put(
                function(
                    "system_property",
                    "读取或修改 Android 系统属性（getprop/setprop）。需要 Root。" +
                        "get 支持精确 key 或 prefix 前缀批量匹配（如 persist.sys.）；" +
                        "set 写入后回读校验。只读属性（ro.*）与系统专用属性（ctl.*、init.*）会被拒绝，" +
                        "收到 PROPERTY_READONLY 时不要重复调用，改用对应设置项。",
                    properties(
                        "action" to enumString("动作", "get", "set"),
                        "key" to string("属性名，如 persist.sys.locale", 128),
                        "prefix" to string("get 时的前缀批量匹配，如 persist.sys.", 128),
                        "value" to string("set 的新值", 512),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "display_control",
                    "修改屏幕分辨率、屏幕密度（DPI）或过扫描边距，均可恢复默认。需要 Root（wm 命令）。" +
                        "写入后回读校验。界面显示异常时用 reset_resolution / reset_density / reset_overscan 恢复。" +
                        "调整 DPI 常用于让界面元素变大或变小；分辨率调整会重启界面。",
                    properties(
                        "action" to enumString(
                            "动作",
                            "set_resolution", "reset_resolution",
                            "set_density", "reset_density",
                            "set_overscan", "reset_overscan",
                        ),
                        "resolution" to string("set_resolution：WxH，如 1080x2400", 16),
                        "density" to integer("set_density：DPI 值 100 到 800", 100, 800),
                        "overscan" to string("set_overscan：左,上,右,下，如 0,0,0,-100", 32),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "deep_launch",
                    "直达任意应用的指定界面或服务（am start/startservice），无需在桌面翻找。需要 Root。" +
                        "传 component（包名/类名，类名可省包名以 . 开头）或 action（意图动作）二选一，可附 data_uri。" +
                        "组件不存在时返回 DEEP_LAUNCH_FAILED，此时不要重复同样调用；" +
                        "常规启动请优先用 launch_app 传包名。",
                    properties(
                        "mode" to enumString("启动类型，默认 activity", "activity", "service"),
                        "component" to string("组件名，如 com.android.settings/.Settings", 255),
                        "action" to string("意图动作，如 android.settings.WIFI_SETTINGS", 255),
                        "data_uri" to string("意图数据 URI，可留空", 2_000),
                    ),
                ),
            )
            .put(
                function(
                    "package_control",
                    "包管理（pm 命令）：列出包、启用/停用（含系统应用）、取 APK 路径、" +
                        "卸载系统应用更新恢复出厂版本、为当前用户重装。需要 Root。" +
                        "停用系统核心应用可能被系统拒绝（PACKAGE_STATE_NOT_APPLIED），此时不要重复调用。" +
                        "普通应用的安装/卸载/清数据/权限授予请用 manage_app。",
                    properties(
                        "action" to enumString(
                            "动作",
                            "list", "enable", "disable", "app_path", "uninstall_updates", "reinstall",
                        ),
                        "package_name" to string("目标包名", 255),
                        "filter" to enumString("list 过滤：默认 all", "all", "system", "disabled", "enabled", "third_party"),
                        "keyword" to string("list 按包名包含的关键字过滤，可留空", 100),
                        "limit" to integer("list 最多返回数量，默认 50", 1, 200),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "user_control",
                    "多用户管理：列出用户、查看当前用户、切换用户、创建用户、删除用户。需要 Root。" +
                        "切换是异步的，未确认时用 action=current 复查，不要重复调用。" +
                        "删除用户会清空其全部数据（主用户 id=0 不允许删除）。" +
                        "删除类操作执行前必须先用 ask_user 获得用户明确确认。",
                    properties(
                        "action" to enumString("动作", "list", "current", "switch", "create", "remove"),
                        "user_id" to integer("switch/remove 的目标用户 id", 0, 99),
                        "name" to string("create 的用户名", 64),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "appops_control",
                    "按操作粒度（AppOps）管控应用行为：读取某个应用的全部 AppOps 记录，" +
                        "或将指定操作设为 allow/ignore/deny/default。需要 Root。" +
                        "常用 op：COARSE_LOCATION、FINE_LOCATION、CAMERA、RECORD_AUDIO、READ_CONTACTS、" +
                        "READ_SMS、READ_CLIPBOARD、SYSTEM_ALERT_WINDOW、WAKE_LOCK。" +
                        "set 后回读校验；这是比运行时权限更底层的管控手段。",
                    properties(
                        "action" to enumString("动作", "get", "set"),
                        "package_name" to string("目标包名", 255),
                        "op" to string("操作名，如 CAMERA", 40),
                        "mode" to enumString("设置模式", "allow", "ignore", "deny", "default"),
                    ),
                    "action", "package_name",
                ),
            )
            .put(
                function(
                    "power_control",
                    "电源策略：省电模式开关、充电时保持屏幕唤醒、把应用加入/移出电池优化白名单（Doze）、" +
                        "重启设备。需要 Root。重启会让本次对话随之中断，执行前必须先用 ask_user 获得用户明确确认，" +
                        "且一天内不要重复下发重启。",
                    properties(
                        "action" to enumString("动作", "battery_saver", "stay_awake", "doze_whitelist", "reboot"),
                        "enabled" to boolean("battery_saver/stay_awake 的开关"),
                        "allow" to boolean("doze_whitelist：true 加入白名单 / false 移出"),
                        "package_name" to string("doze_whitelist 的目标包名", 255),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "ime_control",
                    "输入法管理：列出已启用输入法、查看当前默认输入法、切换默认输入法。" +
                        "list/current 免 Root（公开 API）；set 需要 Root。" +
                        "无 Root 时 set 会弹出系统输入法选择器并返回 ok=false，此时告诉用户手动选择，" +
                        "不要重复调用。切换后用 set 结果里的 verified 字段确认。",
                    properties(
                        "action" to enumString("动作", "list", "current", "set"),
                        "id" to string("set 的输入法组件 id，如 com.baidu.input/.ImeService", 255),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "connect_wifi",
                    "Wi-Fi 精细操作：扫描附近热点、连接指定网络（含密码）、断开、忘记已保存网络。" +
                        "需要 Root（cmd wifi）。连接是异步的，工具会等待确认，最长约 8 秒；" +
                        "未确认时返回 WIFI_CONNECT_UNVERIFIED，不要用同样参数重复调用，可先 action=scan 确认热点。" +
                        "无 Root 时打开 Wi-Fi 设置界面并返回 ok=false，请用户手动连接。",
                    properties(
                        "action" to enumString("动作", "scan", "connect", "disconnect", "forget"),
                        "ssid" to string("热点名称", 64),
                        "password" to string("密码（加密网络必填，至少 8 位）", 128),
                        "security" to enumString("加密方式，默认 wpa2", "open", "wep", "wpa", "wpa2", "wpa3"),
                        "keyword" to string("scan 的结果过滤关键字，可留空", 100),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "set_timezone",
                    "设置系统时区（IANA 名称，如 Asia/Shanghai）或自动时间/自动时区开关。" +
                        "时区需要 Root（cmd alarm set-timezone）；自动时间开关免 Root 可写（需修改系统设置权限）。" +
                        "写入后回读校验；系统可能在网络授时后覆盖手动值，不要因此重复调用。",
                    properties(
                        "timezone_id" to string("IANA 时区 ID，如 Asia/Shanghai", 64),
                        "auto_time" to boolean("自动使用网络时间"),
                        "auto_timezone" to boolean("自动使用网络提供的时区"),
                    ),
                ),
            )
    }

    private fun emptyFunction(name: String, description: String): JSONObject =
        function(name, description, properties())

    private fun limitFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            properties("limit" to integer("最多返回数量，默认 10", 1, 30)),
        )

    private fun searchFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            properties(
                "query" to string("可选关键词，最多 200 字"),
                "limit" to integer("最多返回数量，默认 10", 1, 30),
            ),
        )

    /** 个人数据历史索引查询：可按带时区的 ISO 8601 时间筛选并分页。 */
    private fun personalSearchFunction(name: String, description: String): JSONObject =
        function(
            name,
            "$description 结果来自历史索引，可能延迟；返回 ref 可读取详情。",
            properties(
                "query" to string("可选关键词，最多 200 字"),
                "start_time" to string("包含的起始时间，带时区偏移的 ISO 8601", 64),
                "end_time" to string("不包含的结束时间，带时区偏移的 ISO 8601", 64),
                "limit" to integer("最多返回数量，默认 10", 1, 30),
                "offset" to integer("分页起始偏移，默认 0", 0, 10_000),
                "sort" to enumString("排序方向，默认 newest", "newest", "oldest"),
            ),
        )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject =
        AgentToolSchema.function(
            name = name,
            description = description,
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .also { schema ->
                    if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
                },
        )

    private fun properties(vararg entries: Pair<String, JSONObject>): JSONObject =
        JSONObject().also { target -> entries.forEach { (name, schema) -> target.put(name, schema) } }

    private fun string(description: String, maxLength: Int? = null): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .also { schema -> maxLength?.let { schema.put("maxLength", it) } }

    private fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun integer(description: String, minimum: Int, maximum: Int): JSONObject =
        JSONObject()
            .put("type", "integer")
            .put("minimum", minimum)
            .put("maximum", maximum)
            .put("description", description)

    private fun enumString(description: String, vararg values: String): JSONObject =
        string(description).put("enum", JSONArray(values.toList()))

    private fun stringArray(description: String, vararg values: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("items", enumString(description, *values))
            .put("uniqueItems", true)
            .put("maxItems", 7)
            .put("description", description)
}
