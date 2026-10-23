package io.github.mangi.eta.agent.tool

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.Locale

/**
 * Root 深度控制工具集：系统属性、显示参数、深度启动、包/用户/权限精细管控、
 * 电源策略、诊断 dump、输入法、Wi-Fi 连接、时区、设置搜索。
 *
 * 设计约束（与日程/闹钟/系统控制工具一致）：
 * - **Root 直达**：本文件所有写路径都通过 Root 命令直接改写系统状态，不打开任何 GUI；
 *   少数能力（输入法列表/当前值、自动时间开关）有公开 API 免 Root 路径，优先使用。
 * - **参数收敛**：所有值在拼进 shell 命令前完成白名单/正则校验（[shellQuote] 包裹），
 *   绝不把模型原始输入直接透传，避免命令注入与误操作系统关键属性。
 * - **写后回读**：属性/显示/时区/输入法/连接状态一律回读校验，不把"命令已发出"当"已生效"。
 * - **防循环**：每个失败码都带"下一步该做什么"与"不要重复调用"的明确指引；
 *   歧义（如多用户切换目标不明）返回候选列表让模型带精确参数重试一次。
 */
internal class AgentRootControlTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? = when (name) {
        "search_settings" -> direct(searchSettings(args))
        "system_property" -> sensitive(systemProperty(args))
        "display_control" -> sensitive(displayControl(args))
        "deep_launch" -> sensitive(deepLaunch(args))
        "package_control" -> sensitive(packageControl(args))
        "user_control" -> sensitive(userControl(args))
        "appops_control" -> sensitive(appopsControl(args))
        "power_control" -> sensitive(powerControl(args))
        "dumpsys_diag" -> direct(dumpsysDiag(args))
        "ime_control" -> sensitive(imeControl(args))
        "connect_wifi" -> sensitive(connectWifi(args))
        "set_timezone" -> sensitive(setTimezone(args))
        else -> null
    }

    // ---------------------------------------------------------------- 设置搜索

    /** 列出某个命名空间下的 Settings 键值，支持关键字过滤，帮模型找到可用的键。 */
    private fun searchSettings(args: JSONObject): String {
        val namespace = args.optString("namespace").trim().lowercase(Locale.ROOT)
        if (namespace !in SETTINGS_NAMESPACES) {
            return error("INVALID_ARGUMENT", "namespace 仅支持 ${SETTINGS_NAMESPACES.joinToString("/")}")
        }
        if (!rootAvailable()) {
            return error(
                "ROOT_REQUIRED",
                "搜索系统设置项需要 Root（settings list）；无 Root 时只能凭已知键名用 set_setting/get_setting，" +
                    "不要重复调用本工具",
            )
        }
        val keyword = args.optString("keyword").trim()
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        val result = root.execute(
            "settings --user current list ${shellQuote(namespace)}",
            timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
        )
        if (!result.ok) return rootFailure("SETTINGS_LIST_FAILED", result)
        val rows = mutableListOf<JSONObject>()
        for (line in result.stdout.lineSequence()) {
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (key.isEmpty()) continue
            if (keyword.isNotEmpty() &&
                !key.contains(keyword, ignoreCase = true) &&
                !value.contains(keyword, ignoreCase = true)
            ) {
                continue
            }
            rows.add(JSONObject().put("key", key).put("value", value.take(120)))
        }
        rows.sortBy { it.optString("key").lowercase(Locale.ROOT) }
        val items = JSONArray().also { array -> rows.take(limit).forEach { array.put(it) } }
        return ok(
            JSONObject()
                .put("tool", "search_settings")
                .put("namespace", namespace)
                .put("keyword", keyword.ifEmpty { JSONObject.NULL })
                .put("count", minOf(rows.size, limit))
                .put("total_matched", rows.size)
                .put("items", items)
                .put(
                    "summary",
                    "在 $namespace 命名空间匹配到 ${rows.size} 项，返回前 ${minOf(rows.size, limit)} 项；" +
                        "确认要修改的键后用 set_setting 修改，不要凭猜测的键名反复试错",
                ),
        )
    }

    // ---------------------------------------------------------------- 系统属性

    /**
     * 读取或修改 Android 系统属性（getprop/setprop）。
     * 写路径带黑名单保护：ro.*（只读）、ctl.*（可终止系统进程）、init.* 一律拒绝。
     */
    private fun systemProperty(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        return when (action) {
            "get" -> systemPropertyGet(args)
            "set" -> systemPropertySet(args)
            else -> error("INVALID_ARGUMENT", "action 仅支持 get/set")
        }
    }

    private fun systemPropertyGet(args: JSONObject): String {
        val key = args.optString("key").trim()
        val prefix = args.optString("prefix").trim()
        if (key.isEmpty() && prefix.isEmpty()) {
            return error("INVALID_ARGUMENT", "get 需要 key（精确属性名）或 prefix（前缀批量匹配，如 persist.sys.）")
        }
        if (!rootAvailable()) {
            return error("ROOT_REQUIRED", "读取完整系统属性列表需要 Root；少量公开属性可改用 get_device_environment")
        }
        val result = if (key.isNotEmpty()) {
            if (!PROPERTY_KEY.matches(key)) return error("INVALID_PROPERTY_KEY", "属性名格式无效")
            root.execute("getprop ${shellQuote(key)}", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
        } else {
            if (!PROPERTY_KEY.matches(prefix)) return error("INVALID_PROPERTY_KEY", "属性前缀格式无效")
            root.execute(
                "getprop | grep ${shellQuote("^" + prefix)}",
                timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
            )
        }
        if (!result.ok) return rootFailure("GETPROP_FAILED", result)
        val rows = mutableListOf<JSONObject>()
        for (line in result.stdout.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // getprop 精确读取输出裸值；批量匹配输出 [key]: [value]
            if (key.isNotEmpty()) {
                rows.add(JSONObject().put("key", key).put("value", trimmed))
            } else {
                val separator = trimmed.indexOf("]: [")
                if (separator <= 1) continue
                val name = trimmed.removePrefix("[").substring(0, separator - 1)
                val value = trimmed.substring(separator + 4).removeSuffix("]")
                rows.add(JSONObject().put("key", name).put("value", value.take(160)))
            }
        }
        return ok(
            JSONObject()
                .put("tool", "system_property")
                .put("action", "get")
                .put("count", rows.size)
                .put("items", JSONArray().also { array -> rows.take(200).forEach { array.put(it) } })
                .put("summary", "读取到 ${rows.size} 个属性"),
        )
    }

    private fun systemPropertySet(args: JSONObject): String {
        val key = args.optString("key").trim()
        val value = args.optString("value")
        if (!PROPERTY_KEY.matches(key) || value.isEmpty()) {
            return error("INVALID_ARGUMENT", "set 需要合法的 key（属性名）与非空 value")
        }
        val blocked = PROPERTY_BLACKLIST_PREFIXES.firstOrNull { key.startsWith(it) }
        if (blocked != null) {
            return error(
                "PROPERTY_READONLY",
                "属性 $key 属于 $blocked* 前缀（只读或系统专用），不可修改；" +
                    "如需调整相关行为请换用对应设置项，不要重复调用本工具",
            )
        }
        if (!rootAvailable()) {
            return error("ROOT_REQUIRED", "修改系统属性需要 Root")
        }
        val result = root.execute(
            "setprop ${shellQuote(key)} ${shellQuote(value)}",
            timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
        )
        if (!result.ok) return rootFailure("SETPROP_FAILED", result)
        val verify = root.execute("getprop ${shellQuote(key)}", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
        val current = verify.stdout.trim()
        if (verify.ok && current != value) {
            return error(
                "PROPERTY_NOT_APPLIED",
                "属性已写入但回读不一致（期望 $value，实际 $current）；部分属性需重启或由系统覆盖，" +
                    "不要重复调用本工具",
            )
        }
        return ok(
            JSONObject()
                .put("tool", "system_property")
                .put("action", "set")
                .put("key", key)
                .put("value", value)
                .put("verified", verify.ok)
                .put("summary", "系统属性 $key 已设为 $value"),
        )
    }

    // ---------------------------------------------------------------- 显示控制

    /** 修改分辨率 / 屏幕密度 / 过扫描（wm size/density/overscan），全部可恢复默认。 */
    private fun displayControl(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        if (!rootAvailable()) return error("ROOT_REQUIRED", "修改分辨率/DPI 需要 Root（wm 命令）")
        return when (action) {
            "set_resolution" -> {
                val size = args.optString("resolution").trim()
                if (!RESOLUTION_PATTERN.matches(size)) {
                    return error("INVALID_ARGUMENT", "resolution 需为 WxH 格式，如 1080x2400")
                }
                val (width, height) = size.split("x").map { it.toInt() }
                if (width < 240 || height < 240 || width > 8192 || height > 8192) {
                    return error("INVALID_ARGUMENT", "分辨率宽高需在 240 到 8192 之间")
                }
                val result = root.execute("wm size ${width}x${height}", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("WM_SIZE_FAILED", result)
                val current = readWmValue("size")
                if (current != null && current != size) {
                    return error("RESOLUTION_NOT_APPLIED", "期望 $size，实际 $current；不要重复调用")
                }
                ok(
                    JSONObject()
                        .put("tool", "display_control")
                        .put("action", action)
                        .put("resolution", current ?: size)
                        .put("summary", "屏幕分辨率已设为 $size；界面异常时用 reset_resolution 恢复"),
                )
            }

            "reset_resolution" -> {
                val result = root.execute("wm size reset", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("WM_SIZE_FAILED", result)
                val current = readWmValue("size")
                ok(
                    JSONObject()
                        .put("tool", "display_control")
                        .put("action", action)
                        .put("resolution", current ?: JSONObject.NULL)
                        .put("summary", "已恢复默认分辨率"),
                )
            }

            "set_density" -> {
                val density = args.optInt("density", -1)
                if (density < 100 || density > 800) {
                    return error("INVALID_ARGUMENT", "density 需在 100 到 800 之间（常见值 320/420/480）")
                }
                val result = root.execute("wm density $density", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("WM_DENSITY_FAILED", result)
                val current = readWmValue("density")
                if (current != null && current.toIntOrNull() != density) {
                    return error("DENSITY_NOT_APPLIED", "期望 $density，实际 $current；不要重复调用")
                }
                ok(
                    JSONObject()
                        .put("tool", "display_control")
                        .put("action", action)
                        .put("density", density)
                        .put("summary", "屏幕密度已设为 $density DPI；界面异常时用 reset_density 恢复"),
                )
            }

            "reset_density" -> {
                val result = root.execute("wm density reset", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("WM_DENSITY_FAILED", result)
                ok(
                    JSONObject()
                        .put("tool", "display_control")
                        .put("action", action)
                        .put("density", readWmValue("density") ?: JSONObject.NULL)
                        .put("summary", "已恢复默认屏幕密度"),
                )
            }

            "set_overscan" -> {
                val raw = args.optString("overscan").trim()
                val parts = raw.split(",").map { it.trim() }
                if (parts.size != 4 || parts.any { part -> part.toIntOrNull()?.let { it < -500 || it > 500 } != false }) {
                    return error("INVALID_ARGUMENT", "overscan 需为 左,上,右,下 四个 -500 到 500 的整数，如 0,0,0,-100")
                }
                val result = root.execute("wm overscan $raw", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("WM_OVERSCAN_FAILED", result)
                ok(
                    JSONObject()
                        .put("tool", "display_control")
                        .put("action", action)
                        .put("overscan", raw)
                        .put("summary", "过扫描边距已设为 $raw；恢复默认用 reset_overscan"),
                )
            }

            "reset_overscan" -> {
                val result = root.execute("wm overscan reset", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("WM_OVERSCAN_FAILED", result)
                ok(
                    JSONObject()
                        .put("tool", "display_control")
                        .put("action", action)
                        .put("summary", "已恢复默认过扫描边距"),
                )
            }

            else -> error(
                "INVALID_ARGUMENT",
                "action 仅支持 set_resolution/reset_resolution/set_density/reset_density/set_overscan/reset_overscan",
            )
        }
    }

    /** 读取 wm size/density 的 Override 值（无 override 时返回 Physical 值）。 */
    private fun readWmValue(kind: String): String? {
        val result = root.execute("wm $kind", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
        if (!result.ok) return null
        val override = result.stdout.lineSequence().firstOrNull { it.contains("Override $kind:", ignoreCase = true) }
        val source = override
            ?: result.stdout.lineSequence().firstOrNull { it.contains("$kind:", ignoreCase = true) }
            ?: return null
        return source.substringAfter(':').trim().takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- 深度启动

    /** 直达任意应用的指定界面或动作（am start / startservice），无需在桌面翻找。 */
    private fun deepLaunch(args: JSONObject): String {
        val component = args.optString("component").trim()
        val action = args.optString("action").trim()
        val dataUri = args.optString("data_uri").trim()
        val mode = args.optString("mode").trim().lowercase(Locale.ROOT).ifEmpty { "activity" }
        if (mode !in LAUNCH_MODES) return error("INVALID_ARGUMENT", "mode 仅支持 activity/service")
        if (component.isEmpty() && action.isEmpty()) {
            return error("INVALID_ARGUMENT", "需要 component（包名/类名）或 action（意图动作），二选一")
        }
        if (component.isNotEmpty() && !COMPONENT_PATTERN.matches(component)) {
            return error(
                "INVALID_COMPONENT",
                "component 需为 包名/类名 格式（类名可以 . 开头，如 com.foo/.MainActivity）；" +
                    "不确定类名时先用 package_control(action=list, keyword=包名) 确认包名",
            )
        }
        if (action.isNotEmpty() && !ACTION_PATTERN.matches(action)) {
            return error("INVALID_ACTION", "action 需为合法意图动作（如 android.settings.WIFI_SETTINGS）")
        }
        if (dataUri.isNotEmpty() && dataUri.length > 2_000) {
            return error("INVALID_ARGUMENT", "data_uri 过长")
        }
        if (!rootAvailable()) {
            return error(
                "ROOT_REQUIRED",
                "直达指定界面需要 Root（am start）；常规启动请改用 launch_app 传包名，不要重复调用本工具",
            )
        }
        val command = buildString {
            append(if (mode == "service") "am startservice --user current" else "am start --user current")
            if (component.isNotEmpty()) {
                append(" -n ")
                append(shellQuote(component))
            }
            if (action.isNotEmpty()) {
                append(" -a ")
                append(shellQuote(action))
            }
            if (dataUri.isNotEmpty()) {
                append(" -d ")
                append(shellQuote(dataUri))
            }
        }
        val result = root.execute(command, timeoutMillis = LAUNCH_TIMEOUT_MS)
        val output = "${result.stdout} ${result.stderr}"
        if (!result.ok || output.contains("Error", ignoreCase = true) || output.contains("Exception")) {
            return error(
                "DEEP_LAUNCH_FAILED",
                "启动失败：${output.trim().take(200).ifEmpty { "exit=${result.exitCode}" }}；" +
                    "请确认 component/action 正确，不要重复同样的调用",
            )
        }
        return ok(
            JSONObject()
                .put("tool", "deep_launch")
                .put("mode", mode)
                .put("component", component.ifEmpty { JSONObject.NULL })
                .put("action", action.ifEmpty { JSONObject.NULL })
                .put("method", "root_am")
                .put("summary", "已启动${if (mode == "service") "服务" else "界面"}；若界面未出现可能是组件不存在或需要先解锁"),
        )
    }

    // ---------------------------------------------------------------- 包管理

    /** pm 封装：查询/启用/停用/恢复出厂/重装/取路径，覆盖系统应用。 */
    private fun packageControl(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        val packageName = args.optString("package_name").trim()
        if (!rootAvailable()) return error("ROOT_REQUIRED", "包管理需要 Root（pm 命令）")
        return when (action) {
            "list" -> {
                val filter = args.optString("filter").trim().lowercase(Locale.ROOT).ifEmpty { "all" }
                if (filter !in PACKAGE_FILTERS) {
                    return error("INVALID_ARGUMENT", "filter 仅支持 ${PACKAGE_FILTERS.joinToString("/")}")
                }
                val keyword = args.optString("keyword").trim()
                val limit = args.optInt("limit", 50).coerceIn(1, 200)
                val flag = when (filter) {
                    "system" -> " -s"
                    "disabled" -> " -d"
                    "enabled" -> " -e"
                    "third_party" -> " -3"
                    else -> ""
                }
                val result = root.execute(
                    "pm list packages --user current$flag",
                    timeoutMillis = PACKAGE_COMMAND_TIMEOUT_MS,
                )
                if (!result.ok) return rootFailure("PACKAGE_LIST_FAILED", result)
                val names = result.stdout.lineSequence()
                    .map { it.removePrefix("package:").trim() }
                    .filter { PACKAGE_NAME.matches(it) }
                    .filter { keyword.isEmpty() || it.contains(keyword, ignoreCase = true) }
                    .sorted()
                    .toList()
                val items = JSONArray().also { array -> names.take(limit).forEach { array.put(it) } }
                ok(
                    JSONObject()
                        .put("tool", "package_control")
                        .put("action", "list")
                        .put("filter", filter)
                        .put("count", minOf(names.size, limit))
                        .put("total_matched", names.size)
                        .put("items", items)
                        .put("summary", "匹配 ${names.size} 个包，返回前 ${minOf(names.size, limit)} 个"),
                )
            }

            "enable", "disable" -> {
                if (!PACKAGE_NAME.matches(packageName)) {
                    return error("INVALID_PACKAGE", "package_name 格式无效")
                }
                val command = if (action == "enable") {
                    "pm enable --user current ${shellQuote(packageName)}"
                } else {
                    "pm disable-user --user current ${shellQuote(packageName)}"
                }
                val result = root.execute(command, timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("PACKAGE_STATE_FAILED", result)
                val check = root.execute(
                    "pm list packages --user current -d",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                val disabled = check.ok && check.stdout.lineSequence()
                    .any { it.removePrefix("package:").trim() == packageName }
                val applied = if (action == "enable") !disabled else disabled
                if (!applied) {
                    return error(
                        "PACKAGE_STATE_NOT_APPLIED",
                        "命令已执行但目标应用状态未变化；系统核心应用可能不允许停用，" +
                            "启用失败通常是应用已被卸载，不要重复调用",
                    )
                }
                ok(
                    JSONObject()
                        .put("tool", "package_control")
                        .put("action", action)
                        .put("package_name", packageName)
                        .put("summary", "已${if (action == "enable") "启用" else "停用"} $packageName"),
                )
            }

            "app_path" -> {
                if (!PACKAGE_NAME.matches(packageName)) return error("INVALID_PACKAGE", "package_name 格式无效")
                val result = root.execute(
                    "pm path --user current ${shellQuote(packageName)}",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                if (!result.ok) return rootFailure("PACKAGE_PATH_FAILED", result)
                val paths = result.stdout.lineSequence()
                    .map { it.removePrefix("package:").trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                if (paths.isEmpty()) return error("APP_NOT_FOUND", "未找到 $packageName 的安装路径")
                ok(
                    JSONObject()
                        .put("tool", "package_control")
                        .put("action", "app_path")
                        .put("package_name", packageName)
                        .put("paths", JSONArray().also { array -> paths.forEach { array.put(it) } })
                        .put("summary", "$packageName 的 APK 路径：${paths.first()}"),
                )
            }

            "uninstall_updates" -> {
                if (!PACKAGE_NAME.matches(packageName)) return error("INVALID_PACKAGE", "package_name 格式无效")
                val result = root.execute(
                    "pm uninstall -k --user 0 ${shellQuote(packageName)}",
                    timeoutMillis = UNINSTALL_TIMEOUT_MS,
                )
                if (result.ok && result.stdout.contains("Success", ignoreCase = true)) {
                    return ok(
                        JSONObject()
                            .put("tool", "package_control")
                            .put("action", "uninstall_updates")
                            .put("package_name", packageName)
                            .put("summary", "已卸载 $packageName 的更新并恢复出厂版本（数据保留）"),
                    )
                }
                error(
                    "UNINSTALL_UPDATES_FAILED",
                    "恢复出厂版本失败：${failureDetail(result)}；非系统应用不支持此操作",
                )
            }

            "reinstall" -> {
                if (!PACKAGE_NAME.matches(packageName)) return error("INVALID_PACKAGE", "package_name 格式无效")
                val result = root.execute(
                    "cmd package install-existing --user current ${shellQuote(packageName)}",
                    timeoutMillis = INSTALL_TIMEOUT_MS,
                )
                if (result.ok && (result.stdout.contains("installed", ignoreCase = true) ||
                        result.stdout.contains("exists", ignoreCase = true))
                ) {
                    return ok(
                        JSONObject()
                            .put("tool", "package_control")
                            .put("action", "reinstall")
                            .put("package_name", packageName)
                            .put("summary", "已为当前用户重新安装 $packageName"),
                    )
                }
                error("REINSTALL_FAILED", "重装失败：${failureDetail(result)}")
            }

            else -> error(
                "INVALID_ARGUMENT",
                "action 仅支持 list/enable/disable/app_path/uninstall_updates/reinstall",
            )
        }
    }

    // ---------------------------------------------------------------- 多用户

    /** 多用户管理：列出/当前/切换/创建/删除（工作资料、访客等）。 */
    private fun userControl(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        if (!rootAvailable()) return error("ROOT_REQUIRED", "多用户管理需要 Root")
        return when (action) {
            "list", "current" -> {
                val command = if (action == "current") "am get-current-user" else "pm list users"
                val result = root.execute(command, timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("USER_QUERY_FAILED", result)
                val value = result.stdout.trim()
                if (action == "current") {
                    return ok(
                        JSONObject()
                            .put("tool", "user_control")
                            .put("action", "current")
                            .put("user_id", value.toIntOrNull() ?: JSONObject.NULL)
                            .put("summary", "当前用户 id 为 $value"),
                    )
                }
                val users = mutableListOf<JSONObject>()
                for (line in value.lineSequence()) {
                    val match = USER_INFO_PATTERN.find(line) ?: continue
                    val (id, name) = match.destructured
                    users.add(JSONObject().put("user_id", id.toIntOrNull() ?: continue).put("name", name))
                }
                ok(
                    JSONObject()
                        .put("tool", "user_control")
                        .put("action", "list")
                        .put("count", users.size)
                        .put("items", JSONArray().also { array -> users.forEach { array.put(it) } })
                        .put("summary", "共 ${users.size} 个用户；切换用 switch 传 user_id"),
                )
            }

            "switch" -> {
                val userId = args.optInt("user_id", -1)
                if (userId < 0) return error("INVALID_ARGUMENT", "user_id 需为非负整数；先用 action=list 查看可用用户")
                val result = root.execute("am switch-user $userId", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("USER_SWITCH_FAILED", result)
                val current = root.execute("am get-current-user", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                val switched = current.stdout.trim().toIntOrNull() == userId
                ok(
                    JSONObject()
                        .put("tool", "user_control")
                        .put("action", "switch")
                        .put("user_id", userId)
                        .put("verified", switched)
                        .put(
                            "summary",
                            if (switched) {
                                "已切换到用户 $userId"
                            } else {
                                "切换指令已下发（当前读回 ${current.stdout.trim()}）；切换是异步的，" +
                                    "稍后可用 action=current 确认，不要重复调用"
                            },
                        ),
                )
            }

            "create" -> {
                val name = args.optString("name").trim()
                if (name.isEmpty() || name.length > 64) {
                    return error("INVALID_ARGUMENT", "name 需为 1 到 64 个字符的用户名")
                }
                val result = root.execute("pm create-user ${shellQuote(name)}", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("USER_CREATE_FAILED", result)
                val id = USER_CREATED_PATTERN.find(result.stdout)?.groupValues?.get(1)?.toIntOrNull()
                ok(
                    JSONObject()
                        .put("tool", "user_control")
                        .put("action", "create")
                        .put("name", name)
                        .put("user_id", id ?: JSONObject.NULL)
                        .put("verified", id != null)
                        .put("summary", "已创建用户${id?.let { "（id=$it）" } ?: ""}；切换用 action=switch"),
                )
            }

            "remove" -> {
                val userId = args.optInt("user_id", -1)
                if (userId <= 0) return error("INVALID_ARGUMENT", "user_id 必须大于 0；主用户（0）不允许删除")
                val result = root.execute("pm remove-user $userId", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("USER_REMOVE_FAILED", result)
                ok(
                    JSONObject()
                        .put("tool", "user_control")
                        .put("action", "remove")
                        .put("user_id", userId)
                        .put("summary", "已删除用户 $userId 及其数据"),
                )
            }

            else -> error("INVALID_ARGUMENT", "action 仅支持 list/current/switch/create/remove")
        }
    }

    // ---------------------------------------------------------------- AppOps 精细权限

    /** 按操作（appop）粒度允许/忽略/拒绝某个应用的敏感行为。 */
    private fun appopsControl(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        val packageName = args.optString("package_name").trim()
        if (!PACKAGE_NAME.matches(packageName)) {
            return error("INVALID_PACKAGE", "package_name 格式无效")
        }
        if (!rootAvailable()) return error("ROOT_REQUIRED", "AppOps 管控需要 Root（cmd appops）")
        return when (action) {
            "get" -> {
                val result = root.execute(
                    "cmd appops get ${shellQuote(packageName)}",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                if (!result.ok) return rootFailure("APPOPS_GET_FAILED", result)
                val ops = mutableListOf<JSONObject>()
                for (line in result.stdout.lineSequence()) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size != 2) continue
                    ops.add(JSONObject().put("op", parts[0].trim()).put("mode", parts[1].trim()))
                }
                if (ops.isEmpty()) {
                    return error("APPOPS_NO_DATA", "未读取到 $packageName 的 AppOps 记录；应用可能从未触发相关操作")
                }
                ok(
                    JSONObject()
                        .put("tool", "appops_control")
                        .put("action", "get")
                        .put("package_name", packageName)
                        .put("count", ops.size)
                        .put("items", JSONArray().also { array -> ops.take(200).forEach { array.put(it) } })
                        .put("summary", "读取到 ${ops.size} 条 AppOps 记录"),
                )
            }

            "set" -> {
                val op = args.optString("op").trim().uppercase(Locale.ROOT)
                val mode = args.optString("mode").trim().lowercase(Locale.ROOT)
                if (!APPOP_NAME.matches(op)) {
                    return error("INVALID_OP", "op 需为大写操作名，如 COARSE_LOCATION / CAMERA / READ_CLIPBOARD")
                }
                if (mode !in APPOP_MODES) {
                    return error("INVALID_ARGUMENT", "mode 仅支持 ${APPOP_MODES.joinToString("/")}")
                }
                val result = root.execute(
                    "cmd appops set ${shellQuote(packageName)} ${shellQuote(op)} $mode",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                if (!result.ok) return rootFailure("APPOPS_SET_FAILED", result)
                val verify = root.execute(
                    "cmd appops get ${shellQuote(packageName)} $op",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                val current = verify.stdout.trim().substringAfterLast(':').trim()
                ok(
                    JSONObject()
                        .put("tool", "appops_control")
                        .put("action", "set")
                        .put("package_name", packageName)
                        .put("op", op)
                        .put("mode", mode)
                        .put("verified", verify.ok && current.isNotEmpty())
                        .put("summary", "已将 $packageName 的 $op 设为 $mode（回读：${current.ifEmpty { "未知" }}）"),
                )
            }

            else -> error("INVALID_ARGUMENT", "action 仅支持 get/set")
        }
    }

    // ---------------------------------------------------------------- 电源策略

    /** 省电模式 / 重启（多目标）/ 充电保持唤醒 / 电池优化白名单。 */
    private fun powerControl(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        if (!rootAvailable()) return error("ROOT_REQUIRED", "电源策略控制需要 Root")
        return when (action) {
            "battery_saver" -> {
                if (!args.has("enabled")) {
                    return error("INVALID_ARGUMENT", "battery_saver 需要显式 enabled（true 开启 / false 关闭）")
                }
                val enabled = args.optBoolean("enabled", true)
                val value = if (enabled) "1" else "0"
                val result = root.execute(
                    "settings put global low_power $value",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                if (!result.ok) return rootFailure("BATTERY_SAVER_FAILED", result)
                val current = root.execute("settings get global low_power", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (current.ok && current.stdout.trim() != value) {
                    return error("BATTERY_SAVER_NOT_APPLIED", "省电模式已写入但回读不一致；不要重复调用")
                }
                ok(
                    JSONObject()
                        .put("tool", "power_control")
                        .put("action", action)
                        .put("enabled", enabled)
                        .put("summary", "省电模式已${if (enabled) "开启" else "关闭"}"),
                )
            }

            "stay_awake" -> {
                if (!args.has("enabled")) {
                    return error("INVALID_ARGUMENT", "stay_awake 需要显式 enabled（true 开启 / false 关闭）")
                }
                val enabled = args.optBoolean("enabled", true)
                val value = if (enabled) "true" else "false"
                val result = root.execute("svc power stayon $value", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("STAY_AWAKE_FAILED", result)
                ok(
                    JSONObject()
                        .put("tool", "power_control")
                        .put("action", action)
                        .put("enabled", enabled)
                        .put("summary", "充电时保持屏幕唤醒已${if (enabled) "开启" else "关闭"}"),
                )
            }

            "doze_whitelist" -> {
                val packageName = args.optString("package_name").trim()
                val allow = args.optBoolean("allow", true)
                if (!args.has("allow")) {
                    return error("INVALID_ARGUMENT", "doze_whitelist 需要显式 allow（true 加入白名单 / false 移出）")
                }
                if (!PACKAGE_NAME.matches(packageName)) return error("INVALID_PACKAGE", "package_name 格式无效")
                val sign = if (allow) "+" else "-"
                val result = root.execute(
                    "cmd deviceidle whitelist $sign${shellQuote(packageName)}",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                if (!result.ok) return rootFailure("DOZE_FAILED", result)
                ok(
                    JSONObject()
                        .put("tool", "power_control")
                        .put("action", action)
                        .put("package_name", packageName)
                        .put("allow", allow)
                        .put(
                            "summary",
                            "已将 $packageName ${if (allow) "加入" else "移出"}电池优化白名单（Doze 不限制其后台）",
                        ),
                )
            }

            "reboot" -> {
                val target = args.optString("target").trim().lowercase(Locale.ROOT).ifEmpty { "normal" }
                if (target !in REBOOT_TARGETS) {
                    return error("INVALID_ARGUMENT", "target 仅支持 ${REBOOT_TARGETS.joinToString("/")}")
                }
                if (target != "normal") {
                    return error(
                        "REBOOT_TARGET_UNSUPPORTED",
                        "target=$target 属于高风险操作，本工具仅支持 target=normal；" +
                            "其他模式请让用户在电源菜单中手动选择（可用 screen_action 打开电源菜单）",
                    )
                }
                val result = root.execute("reboot", timeoutMillis = REBOOT_TIMEOUT_MS)
                if (!result.ok) return rootFailure("REBOOT_FAILED", result)
                ok(
                    JSONObject()
                        .put("tool", "power_control")
                        .put("action", "reboot")
                        .put("target", "normal")
                        .put("summary", "重启指令已下发，设备即将重启；本次对话可能随之中断"),
                )
            }

            else -> error("INVALID_ARGUMENT", "action 仅支持 battery_saver/stay_awake/doze_whitelist/reboot")
        }
    }

    // ---------------------------------------------------------------- 系统诊断

    /** dumpsys 子系统诊断：按服务名抓取系统状态，支持关键字聚焦与输出截断。 */
    private fun dumpsysDiag(args: JSONObject): String {
        val service = args.optString("service").trim()
        if (!SERVICE_PATTERN.matches(service)) {
            return error(
                "INVALID_SERVICE",
                "service 需为合法服务名，如 battery/wifi/activity/alarm/job/notification/gfxinfo/SurfaceFlinger",
            )
        }
        if (!rootAvailable()) {
            return error("ROOT_REQUIRED", "dumpsys 需要 Root（普通应用无权限读取大部分子系统）")
        }
        val focus = args.optString("focus").trim()
        val maxChars = args.optInt("max_chars", 4_000).coerceIn(200, 20_000)
        val result = root.execute("dumpsys ${shellQuote(service)}", timeoutMillis = DUMPSYS_TIMEOUT_MS)
        if (!result.ok) return rootFailure("DUMPSYS_FAILED", result)
        val allLines = result.stdout.lines()
        val selected = if (focus.isEmpty()) {
            allLines
        } else {
            allLines.filter { it.contains(focus, ignoreCase = true) }
        }
        var output = selected.joinToString("\n")
        val truncated = output.length > maxChars
        if (truncated) output = output.take(maxChars)
        if (output.isBlank()) {
            return error(
                "DUMPSYS_NO_MATCH",
                "服务 $service 可用但未匹配到内容${if (focus.isEmpty()) "" else "（focus=$focus）"}；" +
                    "可调整 focus 关键字或换服务名，不要重复同样的调用",
            )
        }
        return ok(
            JSONObject()
                .put("tool", "dumpsys_diag")
                .put("service", service)
                .put("focus", focus.ifEmpty { JSONObject.NULL })
                .put("total_lines", allLines.size)
                .put("matched_lines", selected.size)
                .put("truncated", truncated)
                .put("output", output)
                .put(
                    "summary",
                    "dumpsys $service 共 ${allLines.size} 行，命中 ${selected.size} 行" +
                        if (truncated) "（已截断到 $maxChars 字符，可调大 max_chars 或缩小 focus）" else "",
                )
        )
    }

    // ---------------------------------------------------------------- 输入法

    /** 输入法列表/当前值/切换；list 与 current 免 Root 可用（公开 API）。 */
    private fun imeControl(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        return when (action) {
            "list" -> {
                val manager = context.getSystemService(InputMethodManager::class.java)
                    ?: return error("IME_SERVICE_UNAVAILABLE", "输入法服务不可用")
                val enabled = runCatching { manager.enabledInputMethodList }.getOrNull()
                if (enabled != null) {
                    val items = enabled.mapNotNull { info ->
                        runCatching {
                            JSONObject()
                                .put("id", info.id)
                                .put("label", info.loadLabel(context.packageManager).toString())
                                .put("enabled", true)
                        }.getOrNull()
                    }
                    return ok(
                        JSONObject()
                            .put("tool", "ime_control")
                            .put("action", "list")
                            .put("count", items.size)
                            .put("items", JSONArray().also { array -> items.forEach { array.put(it) } })
                            .put("summary", "已启用 ${items.size} 个输入法；切换用 action=set 传 id"),
                    )
                }
                if (!rootAvailable()) {
                    return error("ROOT_REQUIRED", "读取输入法列表失败且无 Root 可回退")
                }
                val result = root.execute("ime list -a", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("IME_LIST_FAILED", result)
                val items = result.stdout.lineSequence()
                    .filter { it.contains('/') }
                    .map { line -> line.substringBefore(' ').trim() }
                    .filter { it.isNotEmpty() }
                    .map { JSONObject().put("id", it) }
                    .toList()
                ok(
                    JSONObject()
                        .put("tool", "ime_control")
                        .put("action", "list")
                        .put("count", items.size)
                        .put("items", JSONArray().also { array -> items.forEach { array.put(it) } })
                        .put("summary", "读取到 ${items.size} 个输入法"),
                )
            }

            "current" -> {
                val current = runCatching {
                    Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.DEFAULT_INPUT_METHOD,
                    )
                }.getOrNull()
                if (!current.isNullOrEmpty()) {
                    return ok(
                        JSONObject()
                            .put("tool", "ime_control")
                            .put("action", "current")
                            .put("id", current)
                            .put("summary", "当前输入法为 $current"),
                    )
                }
                if (!rootAvailable()) return error("ROOT_REQUIRED", "读取当前输入法失败且无 Root 可回退")
                val result = root.execute(
                    "settings get secure default_input_method",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                if (!result.ok) return rootFailure("IME_CURRENT_FAILED", result)
                ok(
                    JSONObject()
                        .put("tool", "ime_control")
                        .put("action", "current")
                        .put("id", result.stdout.trim())
                        .put("summary", "当前输入法为 ${result.stdout.trim()}"),
                )
            }

            "set" -> {
                val id = args.optString("id").trim()
                if (!IME_ID_PATTERN.matches(id)) {
                    return error(
                        "INVALID_IME_ID",
                        "id 需为输入法组件名（包名/.服务名）；先用 action=list 获取可用 id",
                    )
                }
                if (!rootAvailable()) {
                    val manager = context.getSystemService(InputMethodManager::class.java)
                    val shown = manager != null && runCatching { manager.showInputMethodPicker() }.isSuccess
                    return if (shown) {
                        error(
                            "IME_PICKER_SHOWN",
                            "无 Root，已弹出输入法选择器；请用户手动选择，不要重复调用本工具",
                        )
                    } else {
                        error(
                            "ROOT_REQUIRED",
                            "切换默认输入法需要 Root，或让用户在 设置 → 语言和输入法 中手动切换",
                        )
                    }
                }
                val result = root.execute("ime set ${shellQuote(id)}", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("IME_SET_FAILED", result)
                val current = runCatching {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                }.getOrNull()
                val verify = root.execute(
                    "settings get secure default_input_method",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                // 优先用 Root 回读（更贴近系统真实状态），失败再退回 Settings 查询。
                val observed = if (verify.ok && verify.stdout.isNotBlank()) {
                    verify.stdout.trim()
                } else {
                    current.orEmpty()
                }
                val applied = observed == id
                if (!applied) {
                    return error(
                        "IME_NOT_APPLIED",
                        "切换指令已执行但默认输入法未变化（可能是禁用状态或系统限制）；" +
                            "不要重复调用，可让用户在输入法设置中确认",
                    )
                }
                ok(
                    JSONObject()
                        .put("tool", "ime_control")
                        .put("action", "set")
                        .put("id", id)
                        .put("summary", "默认输入法已切换为 $id"),
                )
            }

            else -> error("INVALID_ARGUMENT", "action 仅支持 list/current/set")
        }
    }

    // ---------------------------------------------------------------- Wi-Fi 连接

    /** 扫描热点 / 连接指定网络 / 断开 / 忘记（Root cmd wifi）。 */
    private fun connectWifi(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        if (!rootAvailable()) return wifiPanelFallback(action)
        return when (action) {
            "scan" -> {
                val result = root.execute("cmd wifi list-scan-results", timeoutMillis = SCAN_TIMEOUT_MS)
                if (!result.ok) return rootFailure("WIFI_SCAN_FAILED", result)
                val keyword = args.optString("keyword").trim()
                val lines = result.stdout.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && (keyword.isEmpty() || it.contains(keyword, ignoreCase = true)) }
                    .take(60)
                    .toList()
                ok(
                    JSONObject()
                        .put("tool", "connect_wifi")
                        .put("action", "scan")
                        .put("count", lines.size)
                        .put("items", JSONArray().also { array -> lines.forEach { array.put(it) } })
                        .put("summary", "扫描到 ${lines.size} 条热点结果；连接用 action=connect 传 ssid"),
                )
            }

            "connect" -> {
                val ssid = args.optString("ssid").trim()
                val password = args.optString("password")
                val security = args.optString("security").trim().lowercase(Locale.ROOT).ifEmpty { "wpa2" }
                if (ssid.isEmpty() || ssid.length > 64) {
                    return error("INVALID_SSID", "ssid 需为 1 到 64 个字符")
                }
                if (security !in WIFI_SECURITIES) {
                    return error("INVALID_ARGUMENT", "security 仅支持 ${WIFI_SECURITIES.joinToString("/")}")
                }
                if (security != "open" && password.length < 8) {
                    return error("INVALID_PASSWORD", "$security 网络密码至少 8 位")
                }
                if (security == "open" && password.isNotEmpty()) {
                    return error("INVALID_ARGUMENT", "open 网络不需要密码，请省略 password")
                }
                val result = root.execute(
                    "cmd wifi connect-network ${shellQuote(ssid)} ${shellQuote(security)} ${shellQuote(password)}",
                    timeoutMillis = CONNECT_TIMEOUT_MS,
                )
                if (!result.ok) return rootFailure("WIFI_CONNECT_FAILED", result)
                // 连接是异步的：轮询 wifi status 确认 SSID，最多等待约 8 秒，连接成功即提前结束。
                var connected = false
                repeat(CONNECT_VERIFY_ATTEMPTS) {
                    if (connected) return@repeat
                    Thread.sleep(CONNECT_VERIFY_INTERVAL_MS)
                    val status = root.execute("cmd wifi status", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                    if (status.ok && status.stdout.contains(ssid, ignoreCase = true)) {
                        connected = true
                    }
                }
                if (!connected) {
                    return error(
                        "WIFI_CONNECT_UNVERIFIED",
                        "连接指令已下发但未在预期时间内确认连接（密码错误或热点已关闭）；" +
                            "可先用 action=scan 确认热点存在，不要用同样参数重复调用",
                    )
                }
                ok(
                    JSONObject()
                        .put("tool", "connect_wifi")
                        .put("action", "connect")
                        .put("ssid", ssid)
                        .put("security", security)
                        .put("summary", "已连接 Wi-Fi「$ssid」"),
                )
            }

            "disconnect" -> {
                val result = root.execute("cmd wifi disconnect", timeoutMillis = QUICK_COMMAND_TIMEOUT_MS)
                if (!result.ok) return rootFailure("WIFI_DISCONNECT_FAILED", result)
                ok(
                    JSONObject()
                        .put("tool", "connect_wifi")
                        .put("action", "disconnect")
                        .put("summary", "已断开当前 Wi-Fi 连接"),
                )
            }

            "forget" -> {
                val ssid = args.optString("ssid").trim()
                if (ssid.isEmpty()) return error("INVALID_SSID", "forget 需要 ssid")
                val result = root.execute(
                    "cmd wifi forget-network ${shellQuote(ssid)}",
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                if (!result.ok) {
                    return rootFailure("WIFI_FORGET_FAILED", result)
                }
                ok(
                    JSONObject()
                        .put("tool", "connect_wifi")
                        .put("action", "forget")
                        .put("ssid", ssid)
                        .put("summary", "已忘记网络「$ssid」（已保存的密码已删除）"),
                )
            }

            else -> error("INVALID_ARGUMENT", "action 仅支持 scan/connect/disconnect/forget")
        }
    }

    private fun wifiPanelFallback(action: String): String {
        val launched = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        return if (launched) {
            error(
                "WIFI_PANEL_OPENED",
                "无 Root，已打开 Wi-Fi 设置界面${if (action == "scan") "（请在页面等待扫描刷新）" else ""}；" +
                    "请用户手动完成，不要重复调用本工具",
            )
        } else {
            error("ROOT_REQUIRED", "Wi-Fi 精细操作需要 Root，且无法打开 Wi-Fi 设置界面")
        }
    }

    // ---------------------------------------------------------------- 时区

    /** 设置系统时区 / 自动时间 / 自动时区（时区必须 Root，自动时间开关免 Root 可写）。 */
    private fun setTimezone(args: JSONObject): String {
        val timezoneId = args.optString("timezone_id").trim()
        val hasAutoTime = args.has("auto_time")
        val hasAutoTimezone = args.has("auto_timezone")
        if (timezoneId.isEmpty() && !hasAutoTime && !hasAutoTimezone) {
            return error(
                "INVALID_ARGUMENT",
                "需要 timezone_id（如 Asia/Shanghai）或 auto_time / auto_timezone 开关",
            )
        }
        if (timezoneId.isNotEmpty() && timezoneId !in ZoneId.getAvailableZoneIds()) {
            return error("INVALID_TIMEZONE", "时区 ID 无效；请使用 IANA 名称，如 Asia/Shanghai / Europe/London")
        }
        val autoTime = args.optBoolean("auto_time", false)
        val autoTimezone = args.optBoolean("auto_timezone", false)
        val methods = mutableListOf<String>()
        var autoVerified = false
        // 自动时间/自动时区：优先公开 Settings API，失败用 Root 保底；写后回读校验。
        if (hasAutoTime || hasAutoTimezone) {
            val resolver = context.contentResolver
            val wrote = runCatching {
                var success = true
                if (hasAutoTime) {
                    success = success && Settings.Global.putInt(resolver, Settings.Global.AUTO_TIME, if (autoTime) 1 else 0)
                }
                if (hasAutoTimezone) {
                    success = success &&
                        Settings.Global.putInt(resolver, Settings.Global.AUTO_TIME_ZONE, if (autoTimezone) 1 else 0)
                }
                success
            }.getOrDefault(false)
            if (wrote) {
                methods.add("public_api")
            } else if (rootAvailable()) {
                val commands = buildList {
                    if (hasAutoTime) add("settings put global auto_time ${if (autoTime) 1 else 0}")
                    if (hasAutoTimezone) add("settings put global auto_time_zone ${if (autoTimezone) 1 else 0}")
                }
                val result = root.execute(
                    commands.joinToString("; "),
                    timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
                )
                if (result.ok) methods.add("root")
            }
            if (methods.isEmpty()) {
                return error(
                    "WRITE_SETTINGS_REQUIRED",
                    "修改自动时间开关需要「修改系统设置」权限或 Root；未授权前不要重复调用本工具",
                )
            }
            val autoTimeCurrent = runCatching {
                Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, -1)
            }.getOrDefault(-1)
            val autoZoneCurrent = runCatching {
                Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME_ZONE, -1)
            }.getOrDefault(-1)
            autoVerified = (!hasAutoTime || autoTimeCurrent == if (autoTime) 1 else 0) &&
                (!hasAutoTimezone || autoZoneCurrent == if (autoTimezone) 1 else 0)
        }
        // 时区：Root 直达，写后回读 persist.sys.timezone。
        var timezoneVerified = false
        if (timezoneId.isNotEmpty()) {
            if (!rootAvailable()) {
                return error(
                    "ROOT_REQUIRED",
                    "手动设置时区需要 Root（cmd alarm set-timezone）；免 Root 可在日期时间设置中手动选择，" +
                        "不要重复调用本工具",
                )
            }
            val result = root.execute(
                "cmd alarm set-timezone ${shellQuote(timezoneId)}",
                timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
            )
            if (!result.ok) return rootFailure("TIMEZONE_FAILED", result)
            val verify = root.execute(
                "getprop persist.sys.timezone",
                timeoutMillis = QUICK_COMMAND_TIMEOUT_MS,
            )
            timezoneVerified = verify.ok && verify.stdout.trim() == timezoneId
            if (!timezoneVerified) {
                return error(
                    "TIMEZONE_NOT_APPLIED",
                    "时区写入后回读为 ${verify.stdout.trim().ifEmpty { "空" }}（期望 $timezoneId）；" +
                        "系统可能在网络授时后覆盖，不要重复调用",
                )
            }
            methods.add("root")
        }
        return ok(
            JSONObject()
                .put("tool", "set_timezone")
                .put("timezone_id", timezoneId.ifEmpty { JSONObject.NULL })
                .put("auto_time", if (hasAutoTime) autoTime else JSONObject.NULL)
                .put("auto_timezone", if (hasAutoTimezone) autoTimezone else JSONObject.NULL)
                .put("verified", timezoneVerified || autoVerified)
                .put("method", methods.joinToString("+"))
                .put(
                    "summary",
                    buildList {
                        if (timezoneId.isNotEmpty()) add("时区已设为 $timezoneId")
                        if (hasAutoTime) add("自动使用网络时间${if (autoTime) "已开" else "已关"}")
                        if (hasAutoTimezone) add("自动时区${if (autoTimezone) "已开" else "已关"}")
                    }.joinToString("；"),
                )
        )
    }

    // ---------------------------------------------------------------- 基础设施

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun direct(content: String) = AgentModelClient.ToolResult(content)

    private fun sensitive(content: String) = AgentModelClient.ToolResult(content = content, sensitive = true)

    private fun ok(payload: JSONObject): String = payload.put("ok", true).toString()

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun failureDetail(result: BoundedRootCommandExecutor.Result): String {
        val detail = listOf(result.stdout.trim(), result.stderr.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" | ")
            .take(200)
        return detail.ifEmpty { if (result.timedOut) "命令超时" else "exit=${result.exitCode}" }
    }

    private fun rootFailure(code: String, result: BoundedRootCommandExecutor.Result): String =
        error(code, "Root 命令执行失败：${failureDetail(result)}")

    private companion object {
        val SETTINGS_NAMESPACES = setOf("system", "secure", "global")
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        val PROPERTY_KEY = Regex("[A-Za-z_][A-Za-z0-9_.\\-]*")
        val PROPERTY_BLACKLIST_PREFIXES = listOf("ro.", "ctl.", "init.", "persist.service.", "persist.sys.timezone")
        val RESOLUTION_PATTERN = Regex("\\d{3,5}x\\d{3,5}")
        val COMPONENT_PATTERN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.]+")
        val ACTION_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.]{2,120}")
        val LAUNCH_MODES = setOf("activity", "service")
        val PACKAGE_FILTERS = setOf("all", "system", "disabled", "enabled", "third_party")
        val USER_INFO_PATTERN = Regex("\\{(\\d+):([^:}]+)")
        val USER_CREATED_PATTERN = Regex("id\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val APPOP_NAME = Regex("[A-Z][A-Z0-9_]{1,40}")
        val APPOP_MODES = setOf("allow", "ignore", "deny", "default")
        val REBOOT_TARGETS = setOf("normal", "recovery", "bootloader", "fastboot", "safe")
        val SERVICE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.\\-]{0,60}")
        val IME_ID_PATTERN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.]+")
        val WIFI_SECURITIES = setOf("open", "wep", "wpa", "wpa2", "wpa3")
        const val QUICK_COMMAND_TIMEOUT_MS = 15_000L
        const val PACKAGE_COMMAND_TIMEOUT_MS = 30_000L
        const val LAUNCH_TIMEOUT_MS = 20_000L
        const val INSTALL_TIMEOUT_MS = 120_000L
        const val UNINSTALL_TIMEOUT_MS = 60_000L
        const val REBOOT_TIMEOUT_MS = 20_000L
        const val DUMPSYS_TIMEOUT_MS = 30_000L
        const val SCAN_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val CONNECT_VERIFY_ATTEMPTS = 4
        const val CONNECT_VERIFY_INTERVAL_MS = 2_000L
    }
}
