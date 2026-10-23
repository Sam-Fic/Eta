package io.github.mangi.eta.agent.tool

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 系统控制工具：铃声模式、勿扰、亮度、屏幕电源动作、应用清单与应用管理。
 *
 * 统一设计约束（与日程/闹钟工具一致）：
 * - **免 Root 优先**：能用公开 API 解决的绝不要求 Root（铃声模式走 AudioManager、勿扰走
 *   NotificationManager policy、亮度走 WRITE_SETTINGS、应用清单走 PackageManager、
 *   安装/卸载走系统安装器意图）；Root 只作为保底直达路径。
 * - **每步都有明确失败码**：缺权限时返回可操作的错误码与授权指引，模型据此告知用户或
 *   引导授权，不反复重试同一个必然失败的调用。
 * - **写后回读**：能回读的状态（铃声模式、勿扰过滤器、亮度、安装结果）一律回读校验；
 *   只能交给系统界面确认的动作（安装/卸载意图）明确返回 ok=false 并说明不要重复调用。
 */
internal class AgentSystemControlTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? = when (name) {
        "set_ringer_mode" -> direct(setRingerMode(args))
        "set_do_not_disturb" -> sensitive(setDoNotDisturb(args))
        "set_brightness" -> sensitive(setBrightness(args))
        "screen_action" -> sensitive(screenAction(args))
        "list_installed_apps" -> sensitive(listInstalledApps(args))
        "manage_app" -> sensitive(manageApp(args))
        "manage_file" -> sensitive(manageFile(args))
        else -> null
    }

    // ---------------------------------------------------------------- 铃声模式

    private fun setRingerMode(args: JSONObject): String {
        val mode = args.optString("mode").trim().lowercase(Locale.ROOT)
        val target = when (mode) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return error("INVALID_ARGUMENT", "mode 仅支持 normal/vibrate/silent")
        }
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return error("AUDIO_SERVICE_UNAVAILABLE", "系统音频服务不可用")
        val previous = audio.ringerMode
        return try {
            audio.ringerMode = target
            if (audio.ringerMode != target) {
                error("RINGER_MODE_NOT_APPLIED", "铃声模式设置后未生效；请勿重复调用，可让用户用音量键手动切换")
            } else {
                ok(
                    JSONObject()
                        .put("tool", "set_ringer_mode")
                        .put("previous_mode", ringerModeName(previous))
                        .put("current_mode", ringerModeName(target))
                        .put(
                            "summary",
                            "铃声模式已切换为${ringerModeName(target)}" +
                                if (mode == "silent") "（静音只影响电话铃声，媒体音量不受影响）" else "",
                        ),
                )
            }
        } catch (_: SecurityException) {
            error("RINGER_MODE_DENIED", "系统拒绝修改铃声模式")
        }
    }

    private fun ringerModeName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_SILENT -> "silent"
        else -> "unknown"
    }

    // ---------------------------------------------------------------- 勿扰模式

    private fun setDoNotDisturb(args: JSONObject): String {
        val enabled = args.getBoolean("enabled")
        val priorityOnly = args.optBoolean("priority_only", false)
        val notification = context.getSystemService(NotificationManager::class.java)
            ?: return error("NOTIFICATION_SERVICE_UNAVAILABLE", "系统通知服务不可用")
        val target = when {
            !enabled -> NotificationManager.INTERRUPTION_FILTER_ALL
            priorityOnly -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else -> NotificationManager.INTERRUPTION_FILTER_NONE
        }
        val label = when (target) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "仅优先内容可打扰"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "完全静音"
            else -> "全部允许"
        }
        if (notification.isNotificationPolicyAccessGranted) {
            val applied = try {
                notification.setInterruptionFilter(target)
                notification.currentInterruptionFilter == target
            } catch (_: SecurityException) {
                false
            } catch (_: RuntimeException) {
                false
            }
            return if (applied) {
                ok(
                    JSONObject()
                        .put("tool", "set_do_not_disturb")
                        .put("filter", label)
                        .put("method", "public_api")
                        .put("summary", "勿扰模式已${if (enabled) "开启" else "关闭"}（$label）"),
                )
            } else {
                error("DND_NOT_APPLIED", "勿扰设置后未生效；请勿重复调用，可让用户在系统勿扰设置中确认")
            }
        }
        if (rootAvailable()) {
            val result = root.execute("cmd notification set_dnd ${if (enabled) "on" else "off"}")
            if (result.ok) {
                val current = notification.currentInterruptionFilter
                val accepted = if (enabled) {
                    current == NotificationManager.INTERRUPTION_FILTER_NONE ||
                        current == NotificationManager.INTERRUPTION_FILTER_PRIORITY
                } else {
                    current == NotificationManager.INTERRUPTION_FILTER_ALL
                }
                if (accepted) {
                    return ok(
                        JSONObject()
                            .put("tool", "set_do_not_disturb")
                            .put("method", "root")
                            .put(
                                "summary",
                                "勿扰模式已${if (enabled) "开启" else "关闭"}" +
                                    if (enabled && priorityOnly) {
                                        "（Root 保底只支持全开/全关，未能设为仅优先内容）"
                                    } else {
                                        ""
                                    },
                            ),
                    )
                }
                return error("DND_NOT_APPLIED", "Root 命令已执行但勿扰状态未变化；请勿重复调用")
            }
        }
        return error(
            "NOTIFICATION_POLICY_REQUIRED",
            "免 Root 设置勿扰需要用户授予 Eta 勿扰管理权限：请在 设置 → 通知 → 勿扰 中允许 Eta 管理勿扰，" +
                "或提供 Root 后重试。未授权前不要重复调用本工具。",
        )
    }

    // ---------------------------------------------------------------- 屏幕亮度

    private fun setBrightness(args: JSONObject): String {
        val auto = args.optBoolean("auto", false)
        val percent = if (auto) 0 else args.optInt("percent", -1).coerceIn(0, 100)
        if (!auto && !args.has("percent")) {
            return error("INVALID_ARGUMENT", "请提供 percent（0 到 100）或 auto=true")
        }
        val raw = if (auto) 0 else Math.round(percent * 255f / 100f)
        val resolver = context.contentResolver
        val wrote = if (Settings.System.canWrite(context)) {
            try {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (auto) 1 else 0) &&
                    (auto || Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, raw))
            } catch (_: SecurityException) {
                false
            } catch (_: RuntimeException) {
                false
            }
        } else {
            false
        }
        if (!wrote && rootAvailable()) {
            val modeCommand = "settings put system screen_brightness_mode ${if (auto) 1 else 0}"
            val brightnessCommand = if (auto) null else "settings put system screen_brightness $raw"
            root.execute(
                listOfNotNull(modeCommand, brightnessCommand).joinToString("; "),
                timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
            )
        }
        if (!wrote && !rootAvailable()) {
            return error(
                "WRITE_SETTINGS_REQUIRED",
                "修改亮度需要「修改系统设置」权限：请在 设置 → 应用 → Eta → 修改系统设置 中授予，" +
                    "或提供 Root 后重试。未授权前不要重复调用本工具。",
            )
        }
        // 回读校验：亮度允许厂商裁剪，±2 容差；模式必须精确一致。
        val currentMode = readSettingInt(Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        val currentBrightness = readSettingInt(Settings.System.SCREEN_BRIGHTNESS, -1)
        val modeOk = currentMode == if (auto) 1 else 0
        val brightnessOk = auto || (currentBrightness >= 0 && Math.abs(currentBrightness - raw) <= 2)
        if (!modeOk || !brightnessOk) {
            return error(
                "BRIGHTNESS_NOT_APPLIED",
                "亮度设置后回读不一致（期望 ${if (auto) "自动" else "$percent%"}，" +
                    "实际模式=$currentMode 亮度=$currentBrightness）；请勿重复调用",
            )
        }
        return ok(
            JSONObject()
                .put("tool", "set_brightness")
                .put("auto", auto)
                .put("percent", if (auto) JSONObject.NULL else percent)
                .put("method", if (wrote) "public_api" else "root")
                .put("summary", if (auto) "已切换为自动亮度" else "屏幕亮度已设为 $percent%"),
        )
    }

    private fun readSettingInt(key: String, fallback: Int): Int = try {
        Settings.System.getInt(context.contentResolver, key, fallback)
    } catch (_: Exception) {
        fallback
    }

    // ---------------------------------------------------------------- 屏幕电源动作

    private fun screenAction(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        return when (action) {
            "power_menu" -> {
                val service = AgentAccessibilityService.current()
                val global = service?.globalActionResult("POWER_DIALOG")
                if (global != null && global.ok) {
                    return ok(
                        JSONObject()
                            .put("tool", "screen_action")
                            .put("action", "power_menu")
                            .put("method", "accessibility")
                            .put("summary", "已打开电源菜单（无障碍全局动作）"),
                    )
                }
                if (global != null && global.code == "ACTION_OUTCOME_UNKNOWN") {
                    return ok(
                        JSONObject()
                            .put("tool", "screen_action")
                            .put("action", "power_menu")
                            .put("method", "accessibility")
                            .put("verified", false)
                            .put("summary", "已触发电源菜单，但无法确认结果"),
                    )
                }
                if (rootAvailable()) {
                    val result = root.execute("input keyevent --longpress 26", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                    if (result.ok) {
                        return ok(
                            JSONObject()
                                .put("tool", "screen_action")
                                .put("action", "power_menu")
                                .put("method", "root_longpress_power")
                                .put("verified", false)
                                .put("summary", "已通过长按电源键唤起电源菜单，但无法确认结果"),
                        )
                    }
                }
                error(
                    "POWER_MENU_UNAVAILABLE",
                    "电源菜单需要无障碍服务或 Root；请先在权限健康页开启 Eta 无障碍服务",
                )
            }

            "lock", "screen_off" -> {
                val power = context.getSystemService(PowerManager::class.java)
                if (power != null && !power.isInteractive) {
                    return ok(
                        JSONObject()
                            .put("tool", "screen_action")
                            .put("action", action)
                            .put("summary", "屏幕已经关闭，无需操作"),
                    )
                }
                if (!rootAvailable()) {
                    return error(
                        "ROOT_REQUIRED",
                        "锁屏/息屏需要 Root（直达电源键）或无障碍电源菜单；无 Root 时可用 screen_action(power_menu) " +
                            "让用户在电源菜单中点锁屏，不要重复调用本工具",
                    )
                }
                val result = root.execute("input keyevent 26", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("SCREEN_OFF_FAILED", result)
                ok(
                    JSONObject()
                        .put("tool", "screen_action")
                        .put("action", action)
                        .put("method", "root_power_key")
                        .put("verified", false)
                        .put("summary", "已发送电源键指令，屏幕应已关闭；若仍亮起请再调用一次"),
                )
            }

            "reboot" -> {
                if (!rootAvailable()) {
                    return error(
                        "ROOT_REQUIRED",
                        "重启设备需要 Root；无 Root 时可用 screen_action(power_menu) 让用户在电源菜单中手动重启",
                    )
                }
                val result = root.execute("reboot", timeoutMillis = REBOOT_TIMEOUT_MS)
                if (!result.ok) return rootFailure("REBOOT_FAILED", result)
                ok(
                    JSONObject()
                        .put("tool", "screen_action")
                        .put("action", "reboot")
                        .put("method", "root_reboot")
                        .put("summary", "重启指令已下发，设备即将重启；本次对话可能随之中断"),
                )
            }

            else -> error("INVALID_ARGUMENT", "action 仅支持 lock/screen_off/power_menu/reboot")
        }
    }

    // ---------------------------------------------------------------- 已安装应用列表

    private fun listInstalledApps(args: JSONObject): String {
        val filter = args.optString("filter").trim().lowercase(Locale.ROOT).ifEmpty { "all" }
        if (filter !in APP_FILTERS) return error("INVALID_ARGUMENT", "filter 仅支持 all/user/system/disabled")
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val packageManager = context.packageManager
        val installed = runCatching {
            packageManager.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0L))
        }.getOrNull() ?: return error("PACKAGE_QUERY_FAILED", "系统未返回已安装应用列表")
        val rows = mutableListOf<JSONObject>()
        for (info in installed) {
            val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val keep = when (filter) {
                "user" -> !isSystem
                "system" -> isSystem
                "disabled" -> !info.enabled
                else -> true
            }
            if (!keep) continue
            rows.add(
                runCatching {
                    val version = runCatching {
                        packageManager.getPackageInfo(
                            info.packageName,
                            android.content.pm.PackageManager.PackageInfoFlags.of(0L),
                        ).versionName
                    }.getOrNull()
                    JSONObject()
                        .put("package_name", info.packageName)
                        .put("app_name", info.loadLabel(packageManager).toString())
                        .put("version_name", version ?: JSONObject.NULL)
                        .put("is_system", isSystem)
                        .put("enabled", info.enabled)
                        .put("target_sdk", info.targetSdkVersion)
                }.getOrNull() ?: continue,
            )
        }
        rows.sortBy { it.optString("app_name").lowercase(Locale.ROOT) }
        val items = JSONArray().also { array -> rows.take(limit).forEach { array.put(it) } }
        return ok(
            JSONObject()
                .put("tool", "list_installed_apps")
                .put("filter", filter)
                .put("count", minOf(rows.size, limit))
                .put("total_matched", rows.size)
                .put("items", items)
                .put(
                    "summary",
                    "共匹配 ${rows.size} 个应用，返回前 ${minOf(rows.size, limit)} 个；" +
                        "启动应用请用 launch_app 传 package_name",
                ),
        )
    }

    // ---------------------------------------------------------------- 应用管理

    private fun manageApp(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        val packageName = args.optString("package_name").trim()
        return when (action) {
            "install" -> installApp(args.optString("apk_path").trim())
            "uninstall" -> {
                if (packageName.isEmpty() || !PACKAGE_NAME.matches(packageName)) {
                    return error("INVALID_PACKAGE", "包名格式无效；uninstall 需要 package_name")
                }
                uninstallApp(packageName)
            }

            "clear_data" -> {
                if (packageName.isEmpty() || !PACKAGE_NAME.matches(packageName)) {
                    return error("INVALID_PACKAGE", "包名格式无效；clear_data 需要 package_name")
                }
                if (!appInstalled(packageName)) return error("APP_NOT_FOUND", "未找到应用 $packageName")
                if (!rootAvailable()) {
                    return error(
                        "ROOT_REQUIRED",
                        "清除应用数据需要 Root；免 Root 只能在 设置 → 应用 中手动清除存储空间",
                    )
                }
                val result = root.execute(
                    "pm clear --user current ${shellQuote(packageName)}",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                if (result.ok && result.stdout.contains("Success", ignoreCase = true)) {
                    return ok(
                        JSONObject()
                            .put("tool", "manage_app")
                            .put("action", "clear_data")
                            .put("package_name", packageName)
                            .put("method", "root")
                            .put("summary", "已清除 $packageName 的数据"),
                    )
                }
                error(
                    "CLEAR_DATA_FAILED",
                    "清除数据失败：${failureDetail(result)}；该应用可能不允许清除数据",
                )
            }

            "grant_permission", "revoke_permission" -> {
                if (packageName.isEmpty() || !PACKAGE_NAME.matches(packageName)) {
                    return error("INVALID_PACKAGE", "包名格式无效")
                }
                val permission = args.optString("permission").trim()
                if (!PERMISSION_NAME.matches(permission)) {
                    return error("INVALID_PERMISSION", "permission 需为完整权限名，如 android.permission.CAMERA")
                }
                if (!appInstalled(packageName)) return error("APP_NOT_FOUND", "未找到应用 $packageName")
                if (!rootAvailable()) {
                    return error(
                        "ROOT_REQUIRED",
                        "授予或撤销运行时权限需要 Root；免 Root 只能在 设置 → 应用 → 权限 中手动调整",
                    )
                }
                val verb = if (action == "grant_permission") "grant" else "revoke"
                val result = root.execute(
                    "pm $verb --user current ${shellQuote(packageName)} ${shellQuote(permission)}",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                if (!result.ok) {
                    return error(
                        "PERMISSION_CHANGE_FAILED",
                        "权限${if (action == "grant_permission") "授予" else "撤销"}失败：${failureDetail(result)}",
                    )
                }
                // 回读校验；老版本没有 check-permission 子命令时退化为“命令已接受”。
                val check = root.execute(
                    "cmd package check-permission ${shellQuote(packageName)} ${shellQuote(permission)}",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                val verdict = check.stdout.trim().lowercase(Locale.ROOT)
                val expected = if (action == "grant_permission") "granted" else "denied"
                val verified = verdict == expected || verdict == "default_deny"
                ok(
                    JSONObject()
                        .put("tool", "manage_app")
                        .put("action", action)
                        .put("package_name", packageName)
                        .put("permission", permission)
                        .put("method", "root")
                        .put("verified", verified)
                        .put(
                            "summary",
                            "已${if (action == "grant_permission") "授予" else "撤销"} $packageName 的 $permission" +
                                if (verified) "" else "（未能回读确认，以应用内实际行为为准）",
                        ),
                )
            }

            else -> error(
                "INVALID_ARGUMENT",
                "action 仅支持 install/uninstall/clear_data/grant_permission/revoke_permission",
            )
        }
    }

    private fun installApp(apkPath: String): String {
        if (apkPath.isEmpty()) {
            return error(
                "INVALID_ARGUMENT",
                "install 需要 apk_path（设备上的 APK 绝对路径；可先用 search_files 搜索 .apk）",
            )
        }
        val normalized = apkPath.removePrefix("file://")
        val file = File(normalized)
        if (!file.isFile) {
            return error("APK_NOT_FOUND", "找不到 APK 文件：$normalized；可用 search_files 重新定位")
        }
        if (file.length() > MAX_APK_BYTES) {
            return error("APK_TOO_LARGE", "APK 超过 ${MAX_APK_BYTES / (1024 * 1024)} MB，拒绝处理")
        }
        if (rootAvailable()) {
            val result = root.execute(
                "pm install -r ${shellQuote(file.absolutePath)}",
                timeoutMillis = INSTALL_TIMEOUT_MS,
            )
            if (result.ok && result.stdout.contains("Success", ignoreCase = true)) {
                return ok(
                    JSONObject()
                        .put("tool", "manage_app")
                        .put("action", "install")
                        .put("apk_path", file.absolutePath)
                        .put("method", "root")
                        .put("summary", "已通过 Root 覆盖安装 ${file.name}"),
                )
            }
            return error(
                "INSTALL_FAILED",
                "Root 安装失败：${failureDetail(result)}；签名冲突或解析失败的 APK 换用系统安装器也会失败",
            )
        }
        // 免 Root：复制到应用缓存并通过 FileProvider 交给系统安装器，由用户确认。
        val staged = runCatching { stageApkForSharing(file) }.getOrNull()
            ?: return error("APK_STAGE_FAILED", "无法准备安装文件（缓存不可写或复制失败）")
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
        }.getOrNull() ?: return error("APK_STAGE_FAILED", "无法生成安装文件的内容 URI")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        return if (launched) {
            error(
                "INSTALL_INTENT_LAUNCHED",
                "已打开系统安装界面并载入 ${file.name}；请用户在安装器中确认安装。" +
                    "不要重复调用本工具，安装完成后可用 list_installed_apps 核实。",
            )
        } else {
            error("INSTALL_INTENT_FAILED", "无法打开系统安装界面；请用户手动找到该 APK 安装")
        }
    }

    private fun stageApkForSharing(source: File): File {
        val directory = File(context.cacheDir, "apk-share").apply { mkdirs() }
        val target = File(directory, source.name)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun uninstallApp(packageName: String): String {
        if (!appInstalled(packageName)) return error("APP_NOT_FOUND", "未找到应用 $packageName")
        if (rootAvailable()) {
            val result = root.execute(
                "pm uninstall --user current ${shellQuote(packageName)}",
                timeoutMillis = UNINSTALL_TIMEOUT_MS,
            )
            if (result.ok && result.stdout.contains("Success", ignoreCase = true)) {
                return ok(
                    JSONObject()
                        .put("tool", "manage_app")
                        .put("action", "uninstall")
                        .put("package_name", packageName)
                        .put("method", "root")
                        .put("summary", "已通过 Root 卸载 $packageName"),
                )
            }
            return error("UNINSTALL_FAILED", "Root 卸载失败：${failureDetail(result)}")
        }
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        return if (launched) {
            error(
                "UNINSTALL_INTENT_LAUNCHED",
                "已打开系统卸载界面；请用户确认卸载 $packageName。不要重复调用本工具，" +
                    "完成后可用 list_installed_apps 核实。",
            )
        } else {
            error("UNINSTALL_INTENT_FAILED", "无法打开系统卸载界面；请用户在 设置 → 应用 中手动卸载")
        }
    }

    private fun appInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(
            packageName,
            android.content.pm.PackageManager.ApplicationInfoFlags.of(0L),
        )
    }.isSuccess

    // ---------------------------------------------------------------- 文件管理

    /**
     * 文件复制 / 移动 / 删除 / 重命名 / 建目录。
     * 免 Root：应用已持 MANAGE_EXTERNAL_STORAGE，外部存储全盘可操作；内部路径需要 Root 保底。
     * 安全约束：路径规范化后拒绝 `..` 越界，拒绝操作文件系统关键根（/system、/data 顶层等）。
     */
    private fun manageFile(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        if (action !in FILE_ACTIONS) {
            return error("INVALID_ARGUMENT", "action 仅支持 ${FILE_ACTIONS.joinToString("/")}")
        }
        val path = args.optString("path").trim().removePrefix("file://")
        if (path.isEmpty()) return error("INVALID_ARGUMENT", "path 不能为空")
        val normalized = normalizePath(path) ?: return error(
            "INVALID_PATH",
            "路径非法或越界：$path；请使用设备上的绝对路径（可用 search_files 或 list_directory 定位）",
        )
        if (normalized in DANGEROUS_ROOTS) {
            return error(
                "PATH_PROTECTED",
                "$normalized 是系统关键目录，不允许直接操作；请指定其下的具体文件或子目录",
            )
        }
        val source = File(normalized)
        return when (action) {
            "mkdir" -> {
                if (source.exists()) return error("ALREADY_EXISTS", "$normalized 已存在")
                val created = source.mkdirs() || (rootAvailable() && root.execute(
                    "mkdir -p ${shellQuote(normalized)}",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                ).ok)
                if (!created) return error("MKDIR_FAILED", "无法创建目录 $normalized；请检查上层目录权限")
                ok(
                    JSONObject()
                        .put("tool", "manage_file")
                        .put("action", "mkdir")
                        .put("path", normalized)
                        .put("summary", "已创建目录 $normalized"),
                )
            }

            "delete" -> {
                if (!source.exists()) return error("FILE_NOT_FOUND", "$normalized 不存在；可用 list_directory 确认")
                val succeeded = source.deleteRecursively() || (rootAvailable() && root.execute(
                    "rm -rf ${shellQuote(normalized)}",
                    timeoutMillis = DELETE_TIMEOUT_MS,
                ).ok && !source.exists())
                if (!succeeded || source.exists()) {
                    return error(
                        "DELETE_FAILED",
                        "删除失败：$normalized 仍存在；可能是权限不足或文件被占用，不要重复调用",
                    )
                }
                ok(
                    JSONObject()
                        .put("tool", "manage_file")
                        .put("action", "delete")
                        .put("path", normalized)
                        .put("summary", "已删除 $normalized"),
                )
            }

            "rename" -> {
                val newName = args.optString("new_name").trim()
                if (newName.isEmpty() || newName.contains('/') || newName == "." || newName == "..") {
                    return error("INVALID_ARGUMENT", "new_name 需为不含 / 的合法名称")
                }
                if (!source.exists()) return error("FILE_NOT_FOUND", "$normalized 不存在")
                val target = File(source.parentFile, newName)
                if (target.exists()) return error("ALREADY_EXISTS", "${target.path} 已存在，换个名称")
                val succeeded = source.renameTo(target) || (rootAvailable() && root.execute(
                    "mv ${shellQuote(normalized)} ${shellQuote(target.path)}",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                ).ok && target.exists() && !source.exists())
                if (!succeeded) return error("RENAME_FAILED", "重命名失败：$normalized → ${target.path}")
                ok(
                    JSONObject()
                        .put("tool", "manage_file")
                        .put("action", "rename")
                        .put("path", normalized)
                        .put("new_path", target.path)
                        .put("summary", "已重命名为 ${target.path}"),
                )
            }

            "copy", "move" -> {
                val rawDestination = args.optString("dest_path").trim().removePrefix("file://")
                if (rawDestination.isEmpty()) {
                    return error("INVALID_ARGUMENT", "${action} 需要 dest_path（目标路径）")
                }
                val normalizedDestination = normalizePath(rawDestination) ?: return error(
                    "INVALID_PATH",
                    "目标路径非法或越界：$rawDestination",
                )
                if (normalizedDestination in DANGEROUS_ROOTS) {
                    return error("PATH_PROTECTED", "$normalizedDestination 是系统关键目录，不能作为目标")
                }
                if (!source.exists()) return error("FILE_NOT_FOUND", "$normalized 不存在")
                val sourceIsDirectory = source.isDirectory
                val destination = File(normalizedDestination)
                // dest_path 是已存在目录时，自动落到该目录下保留原名。
                val target = if (destination.isDirectory) File(destination, source.name) else destination
                if (target.path == source.path) {
                    return error("SAME_PATH", "源与目标相同，无需操作")
                }
                target.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) {
                        return error("DEST_PARENT_MISSING", "目标上层目录不存在：${parent.path}")
                    }
                }
                if (target.exists() && target.isDirectory) {
                    return error("DEST_IS_DIRECTORY", "目标已存在且是目录：${target.path}")
                }
                val succeeded = if (action == "copy") {
                    copyPath(source, target) || (rootAvailable() && root.execute(
                        "cp -a ${shellQuote(normalized)} ${shellQuote(target.path)}",
                        timeoutMillis = COPY_TIMEOUT_MS,
                    ).ok && target.exists())
                } else {
                    // 同一挂载点优先 rename（原子、快）；跨设备 rename 会失败，退回复制 + 删除源。
                    source.renameTo(target) || run {
                        val copied = copyPath(source, target) || (rootAvailable() && root.execute(
                            "cp -a ${shellQuote(normalized)} ${shellQuote(target.path)}",
                            timeoutMillis = COPY_TIMEOUT_MS,
                        ).ok && target.exists())
                        copied && (source.deleteRecursively() || (rootAvailable() && root.execute(
                            "rm -rf ${shellQuote(normalized)}",
                            timeoutMillis = DELETE_TIMEOUT_MS,
                        ).ok && !source.exists()))
                    }
                }
                if (!succeeded) {
                    val partiallyCopied = action == "move" && target.exists() && source.exists()
                    return error(
                        if (action == "copy") "COPY_FAILED" else "MOVE_FAILED",
                        "${if (action == "copy") "复制" else "移动"}失败：$normalized → ${target.path}；" +
                            (if (partiallyCopied) "目标已复制但源未能删除，请先确认源路径再重试；" else "") +
                            "可能是权限不足、空间不够或目标已占用，不要重复调用",
                    )
                }
                val size = if (sourceIsDirectory) null else target.length()
                ok(
                    JSONObject()
                        .put("tool", "manage_file")
                        .put("action", action)
                        .put("path", normalized)
                        .put("dest_path", target.path)
                        .put("is_directory", sourceIsDirectory)
                        .put("size_bytes", size ?: JSONObject.NULL)
                        .put("summary", "已${if (action == "copy") "复制" else "移动"}到 ${target.path}"),
                )
            }

            else -> error("INVALID_ARGUMENT", "未支持的 action")
        }
    }

    /** 规范化路径：解析 . 与 ..；越界或为空时返回 null。 */
    private fun normalizePath(raw: String): String? {
        if (!raw.startsWith("/")) return null
        val canonical = runCatching { File(raw).canonicalPath }.getOrNull()
        val resolved = canonical ?: run {
            val segments = mutableListOf<String>()
            for (segment in raw.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> {
                        if (segments.isEmpty()) return null
                        segments.removeAt(segments.lastIndex)
                    }
                    else -> segments.add(segment)
                }
            }
            "/" + segments.joinToString("/")
        }
        return resolved.takeIf { it.length > 1 }
    }

    private fun copyPath(source: File, target: File): Boolean = runCatching {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) return@runCatching false
            source.listFiles()?.forEach { child ->
                if (!copyPath(child, File(target, child.name))) return@runCatching false
            }
            true
        } else {
            target.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.length() == source.length()
        }
    }.getOrDefault(false)

    private fun failureDetail(result: BoundedRootCommandExecutor.Result): String {
        val detail = listOf(result.stdout.trim(), result.stderr.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" | ")
            .take(200)
        return detail.ifEmpty { if (result.timedOut) "命令超时" else "exit=${result.exitCode}" }
    }

    private fun rootFailure(code: String, result: BoundedRootCommandExecutor.Result): String =
        error(code, "Root 命令执行失败：${failureDetail(result)}")

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun direct(content: String) = AgentModelClient.ToolResult(content)

    private fun sensitive(content: String) = AgentModelClient.ToolResult(content = content, sensitive = true)

    private fun ok(payload: JSONObject): String = payload.put("ok", true).toString()

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        val PERMISSION_NAME = Regex("android\\.permission\\.[A-Za-z0-9_.]+")
        val APP_FILTERS = setOf("all", "user", "system", "disabled")
        val FILE_ACTIONS = setOf("copy", "move", "delete", "rename", "mkdir")
        // 文件系统关键根：不允许直接删除/移动/覆盖/重命名这些路径本身（其子目录不受限）。
        val DANGEROUS_ROOTS = setOf(
            "/",
            "/system",
            "/data",
            "/storage",
            "/storage/emulated",
            "/sdcard",
            "/apex",
            "/vendor",
            "/product",
            "/system_ext",
            "/dev",
            "/proc",
            "/sys",
            "/etc",
            "/config",
        )
        const val QUICK_COMMAND_TIMEOUT_MS = 15_000L
        const val COPY_TIMEOUT_MS = 120_000L
        const val DELETE_TIMEOUT_MS = 60_000L
        const val INSTALL_TIMEOUT_MS = 120_000L
        const val UNINSTALL_TIMEOUT_MS = 60_000L
        const val REBOOT_TIMEOUT_MS = 20_000L
        const val MAX_APK_BYTES = 1_073_741_824L
    }
}
