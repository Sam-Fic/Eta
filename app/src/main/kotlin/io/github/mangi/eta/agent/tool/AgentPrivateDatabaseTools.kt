package io.github.mangi.eta.agent.tool

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * 读取系统私有数据库；表、字段与查询均不接受模型输入。
 *
 * 闹钟走「系统标准实现」三级策略：
 * 1. ContentProvider（content://<时钟包名>/alarm，Root content 命令）——端到端生效，
 *    provider 的 change notification 会让时钟应用自行注册 AlarmManager；
 * 2. 数据库快照（alarms.db，自动发现）——provider 不存在时的兜底；
 * 3. 调用方（set_alarm）在 1/2 都不可用时回退标准 AlarmClock intent。
 */
internal class AgentPrivateDatabaseTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? = when (name) {
        "list_alarms" -> sensitive(listAlarms(args))
        "list_active_timers" -> sensitive(readClockDatabase { listTimers(it, args) })
        "delete_alarm" -> sensitive(deleteAlarm(args))
        "update_alarm" -> sensitive(updateAlarm(args))
        "search_clipboard_history" -> sensitive(readDatabase(CLIPBOARD_DATABASE, "CLIPBOARD_HISTORY_UNAVAILABLE") { searchClipboard(it, args) })
        "get_health_summary" -> sensitive(readDatabase(HEALTH_DATABASE, "HEALTH_DATA_UNAVAILABLE") { healthSummary(it, args) })
        else -> null
    }

    // ---------------------------------------------------------------- 闹钟（provider 优先，数据库快照兜底）

    private val clockAppLocator = ClockAppLocator(context)

    /** 已探测到的时钟 ContentProvider URI；探测成功后缓存。 */
    @Volatile
    private var discoveredProviderUri: String? = null

    /** provider/数据库探测失败的时间戳；短 TTL 内不再重复探测，避免每轮拖慢回合。 */
    @Volatile
    private var providerProbeFailedAt: Long = 0L

    /** 已探测到的时钟数据库路径列表：(userId, paths)，探测成功后缓存。 */
    @Volatile
    private var discoveredClockDatabase: Pair<Int, List<String>>? = null

    @Volatile
    private var databaseProbeFailedAt: Long = 0L

    private sealed interface ClockRead {
        data class Ok(val rows: List<JSONObject>, val repeatColumn: String?) : ClockRead
        data object Unavailable : ClockRead
    }

    private data class AlarmTarget(val id: Long, val row: JSONObject, val repeatColumn: String?)

    private data class AlarmMatch(
        val id: Long? = null,
        val hour: Int? = null,
        val minute: Int? = null,
        val label: String? = null,
    )

    private sealed interface AlarmResolution {
        data class Found(val target: AlarmTarget) : AlarmResolution
        data class Failed(val error: String) : AlarmResolution
    }

    private class AlarmSnapshot(val repeatColumn: String?, val rows: List<JSONObject>)

    private fun listAlarms(args: JSONObject): String {
        if (!rootAvailable()) {
            return error("CLOCK_DATA_UNAVAILABLE", "读取系统闹钟需要 Root 权限")
        }
        val enabledOnly = args.optBoolean("enabled_only", true)
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        return when (val read = readAlarms(AlarmMatch())) {
            is ClockRead.Ok -> {
                var rows = read.rows
                if (enabledOnly) {
                    rows = rows.filter { (it.optLongValue("enabled") ?: 0L) == 1L }
                }
                rows = rows.sortedWith(
                    compareByDescending<JSONObject> { it.optLongValue("enabled") ?: 0L }
                        .thenBy { (it.optLongValue("hour") ?: 0L) * 60 + (it.optLongValue("minutes") ?: 0L) },
                )
                JSONObject()
                    .put("ok", true)
                    .put("tool", "list_alarms")
                    .put("source", if (discoveredProviderUri != null) "provider" else "database")
                    .put("items", JSONArray(rows.take(limit)))
                    .put("count", minOf(rows.size, limit))
                    .put("truncated", rows.size > limit)
                    .toString()
            }
            ClockRead.Unavailable -> error(
                "CLOCK_DATA_UNAVAILABLE",
                "无法访问系统时钟数据；需要 Root，且时钟应用需支持标准 alarm provider 或 alarms.db 结构",
            )
        }
    }

    /**
     * 定位闹钟：alarm_id 优先；否则按 match_time(HH:MM) 与 match_label 组合定位。
     * 匹配多个时返回候选列表，让模型带 alarm_id 重试一次，而不是替它猜。
     */
    private fun resolveAlarm(args: JSONObject): AlarmResolution {
        val alarmId = args.optLongValue("alarm_id")
        val matchTime = args.optString("match_time").trim()
        val matchLabel = args.optString("match_label").trim()
        if (alarmId == null && matchTime.isEmpty() && matchLabel.isEmpty()) {
            return AlarmResolution.Failed(
                error(
                    "INVALID_ARGUMENT",
                    "请提供 alarm_id，或提供 match_time(HH:MM) / match_label 定位闹钟；可先用 list_alarms 查看已有闹钟",
                ),
            )
        }
        if (matchTime.isNotEmpty() && !CLOCK_TIME.matches(matchTime)) {
            return AlarmResolution.Failed(error("INVALID_ARGUMENT", "match_time 格式应为 HH:MM"))
        }
        val match = AlarmMatch(
            id = alarmId,
            hour = matchTime.substringBefore(":").toIntOrNull(),
            minute = matchTime.substringAfter(":", "").toIntOrNull(),
            label = matchLabel.takeIf(String::isNotEmpty),
        )
        return when (val read = readAlarms(match)) {
            ClockRead.Unavailable -> AlarmResolution.Failed(
                error("CLOCK_DATA_UNAVAILABLE", "系统时钟数据暂时不可访问（需要 Root）"),
            )
            is ClockRead.Ok -> when (read.rows.size) {
                0 -> AlarmResolution.Failed(
                    error(
                        "ALARM_NOT_FOUND",
                        "没有找到匹配的闹钟；可先用 list_alarms 查看已有闹钟，再用 alarm_id 精确定位",
                    ),
                )
                1 -> AlarmResolution.Found(
                    AlarmTarget(read.rows[0].optLongValue("_id") ?: 0L, read.rows[0], read.repeatColumn),
                )
                else -> AlarmResolution.Failed(
                    JSONObject()
                        .put("ok", false)
                        .put("code", "ALARM_AMBIGUOUS")
                        .put(
                            "message",
                            "找到 ${read.rows.size} 个匹配的闹钟；请从候选里取 alarm_id 后重试一次，不要重复同样的调用",
                        )
                        .put(
                            "candidates",
                            JSONArray().also { array ->
                                read.rows.take(10).forEach { row ->
                                    array.put(
                                        JSONObject()
                                            .put("alarm_id", row.optLongValue("_id"))
                                            .put("time", clockTime(row))
                                            .put("label", row.optString("message"))
                                            .put("enabled", (row.optLongValue("enabled") ?: 0L) == 1L),
                                    )
                                }
                            },
                        )
                        .toString(),
                )
            }
        }
    }

    /** 读闹钟：provider 全量拉取 + 内存过滤；provider 不可用时逐个尝试数据库快照。 */
    private fun readAlarms(match: AlarmMatch): ClockRead {
        val uri = clockProviderUri()
        if (uri != null) {
            queryProviderAlarms(uri)?.let { rows ->
                return ClockRead.Ok(rows.filter { rowMatches(it, match) }, "daysofweek")
            }
        }
        for (path in clockDatabasePaths()) {
            val found = withSnapshot(path, CLOCK_DB_MAX_BYTES) { database ->
                if (!database.hasColumns("alarms", setOf("_id", "hour", "minutes", "enabled"))) {
                    null
                } else {
                    val rows = database.rows(
                        table = "alarms",
                        columns = ALARM_COLUMNS + listOf("days_of_week"),
                        selection = null,
                        order = database.alarmOrder(),
                        limit = 100,
                    )
                    AlarmSnapshot(
                        database.alarmRepeatColumn(),
                        (0 until rows.length()).mapNotNull(rows::optJSONObject),
                    )
                }
            } ?: continue
            return ClockRead.Ok(found.rows.filter { rowMatches(it, match) }, found.repeatColumn)
        }
        return ClockRead.Unavailable
    }

    private fun rowMatches(row: JSONObject, match: AlarmMatch): Boolean {
        if (match.id != null && row.optLongValue("_id") != match.id) return false
        if (match.hour != null && row.optLongValue("hour") != match.hour.toLong()) return false
        if (match.minute != null && row.optLongValue("minutes") != match.minute.toLong()) return false
        if (match.label != null && !row.optString("message").contains(match.label, ignoreCase = true)) return false
        return true
    }

    // ---------------------------------------------------------------- 闹钟创建/删除/修改

    /**
     * 供 set_alarm 使用：通过系统时钟 ContentProvider 直接创建闹钟。
     * 返回 null 表示 provider 不可用或写入命令失败，调用方应回退 intent 路径；
     * 写入命令成功后不再回退（避免重复创建），回读不确认时以 note 提示核对。
     */
    fun insertAlarmViaProvider(hour: Int, minute: Int, label: String?, daysMask: Long, vibrate: Boolean): JSONObject? {
        if (!rootAvailable()) return null
        val uri = clockProviderUri() ?: return null
        val result = root.execute(
            insertAlarmCommand(uri, hour, minute, daysMask, 1, if (vibrate) 1 else 0, label ?: ""),
            timeoutMillis = 15_000L,
            maxOutputBytes = 8 * 1024,
        )
        if (!result.ok) return null
        val payload = JSONObject().put("ok", true).put("tool", "set_alarm").put("mode", "provider")
        return when (val read = readAlarms(AlarmMatch(hour = hour, minute = minute, label = label?.takeIf(String::isNotEmpty)))) {
            is ClockRead.Ok -> {
                val created = read.rows.firstOrNull()
                if (created != null) {
                    payload
                        .put("alarm_id", created.optLongValue("_id"))
                        .put("summary", "已创建闹钟 ${"%02d:%02d".format(hour, minute)}，时钟应用已注册")
                } else {
                    payload.put("summary", "闹钟已写入系统时钟，但未能回读确认；可用 list_alarms 核对，不要重复创建")
                }
                payload
            }
            ClockRead.Unavailable -> payload.put("summary", "闹钟已写入系统时钟；可用 list_alarms 核对，不要重复创建")
        }
    }

    private fun deleteAlarm(args: JSONObject): String {
        if (!rootAvailable()) {
            return error("ROOT_REQUIRED", "读取或修改系统闹钟需要 Root 权限")
        }
        return when (val resolution = resolveAlarm(args)) {
            is AlarmResolution.Failed -> resolution.error
            is AlarmResolution.Found -> {
                val target = resolution.target
                val providerUri = clockProviderUri()
                if (providerUri != null) {
                    val result = root.execute(
                        "content delete --uri ${shellQuote(providerUri)} --where ${shellQuote("_id = ${target.id}")}",
                        timeoutMillis = 15_000L,
                        maxOutputBytes = 8 * 1024,
                    )
                    if (!result.ok) return error("CLOCK_WRITE_FAILED", "删除命令执行失败（exit=${result.exitCode}）")
                } else {
                    val sqlite = sqliteBinary()
                        ?: return error("CLOCK_SQLITE_UNAVAILABLE", "系统缺少 sqlite3，无法直接改写时钟数据库")
                    val databasePath = clockDatabasePaths().firstOrNull()
                        ?: return error("CLOCK_DATA_UNAVAILABLE", "无法定位系统时钟数据库，无法删除闹钟")
                    val sql = "PRAGMA busy_timeout=3000; DELETE FROM alarms WHERE _id=${target.id};"
                    val result = root.execute(
                        "${shellQuote(sqlite)} ${shellQuote(databasePath)} ${shellQuote(sql)}",
                        timeoutMillis = 15_000L,
                        maxOutputBytes = 8 * 1024,
                    )
                    if (!result.ok) return error("CLOCK_WRITE_FAILED", "删除命令执行失败（exit=${result.exitCode}）")
                }
                when (val after = readAlarms(AlarmMatch(id = target.id))) {
                    is ClockRead.Ok ->
                        if (after.rows.isEmpty()) {
                            ok("delete_alarm")
                                .put("alarm_id", target.id)
                                .put("hour", target.row.optLongValue("hour"))
                                .put("minute", target.row.optLongValue("minutes"))
                                .put("label", target.row.optString("message"))
                                .put("summary", "已删除闹钟 ${clockTime(target.row)}${labelSuffix(target.row)}")
                                .put("note", "若时钟界面仍显示该闹钟，重新打开时钟应用即可刷新；不要重复调用本工具")
                                .toString()
                        } else {
                            error("ALARM_DELETE_NOT_APPLIED", "删除后仍能读到该闹钟，未生效；请稍后重试或到时钟应用里手动删除")
                        }
                    ClockRead.Unavailable -> error("CLOCK_DATA_UNAVAILABLE", "删除已执行，但读取时钟数据校验失败")
                }
            }
        }
    }

    private fun updateAlarm(args: JSONObject): String {
        if (!rootAvailable()) {
            return error("ROOT_REQUIRED", "直接修改系统闹钟需要 Root 权限；可用 set_alarm 创建新闹钟")
        }
        return when (val resolution = resolveAlarm(args)) {
            is AlarmResolution.Failed -> resolution.error
            is AlarmResolution.Found -> {
                val providerUri = clockProviderUri()
                if (providerUri != null) {
                    updateAlarmViaProvider(providerUri, resolution.target, args)
                } else {
                    updateAlarmViaDatabase(resolution.target, args)
                }
            }
        }
    }

    /**
     * content update 的 --where 在部分 ROM（实测 MIUI）会忽略条件写全表，绝对不可用；
     * provider 模式下修改 = 读取原行 → 删除 → 按新值重建 → 回读校验。重建失败按原值恢复。
     */
    private fun updateAlarmViaProvider(uri: String, target: AlarmTarget, args: JSONObject): String {
        val current = target.row
        val hour = args.optLongValue("hour")?.toInt() ?: (current.optLongValue("hour") ?: 0L).toInt()
        val minute = args.optLongValue("minute")?.toInt() ?: (current.optLongValue("minutes") ?: 0L).toInt()
        if (hour !in 0..23) return error("INVALID_ARGUMENT", "hour 必须在 0 到 23")
        if (minute !in 0..59) return error("INVALID_ARGUMENT", "minute 必须在 0 到 59")
        if (args.has("label") && args.optString("label").trim().length > 100) {
            return error("INVALID_ARGUMENT", "label 过长（最多 100 字）")
        }
        val enabled = if (args.has("enabled")) {
            if (args.optBoolean("enabled")) 1 else 0
        } else {
            (current.optLongValue("enabled") ?: 1L).toInt()
        }
        val vibrate = if (args.has("vibrate")) {
            if (args.optBoolean("vibrate")) 1 else 0
        } else {
            (current.optLongValue("vibrate") ?: 1L).toInt()
        }
        val label = if (args.has("label")) args.optString("label").trim() else current.optString("message")
        // 重复掩码保持原值；重复规则的变更走 delete_alarm + set_alarm 重建
        val daysMask = target.repeatColumn?.let { current.optLongValue(it) } ?: 0L
        val changed = buildList {
            if (args.has("enabled")) add("enabled")
            if (args.has("vibrate")) add("vibrate")
            if (args.has("label")) add("label")
            if (args.has("hour") || args.has("minute")) add("time")
        }
        if (changed.isEmpty()) {
            return ok("update_alarm")
                .put("alarm_id", target.id)
                .put("changed", JSONArray())
                .put("summary", "没有提供要修改的字段，闹钟保持不变")
                .toString()
        }
        val delete = root.execute(
            "content delete --uri ${shellQuote(uri)} --where ${shellQuote("_id = ${target.id}")}",
            timeoutMillis = 15_000L,
            maxOutputBytes = 8 * 1024,
        )
        if (!delete.ok) return error("CLOCK_WRITE_FAILED", "删除原闹钟失败（exit=${delete.exitCode}）；未做任何修改")
        val insert = root.execute(
            insertAlarmCommand(uri, hour, minute, daysMask, enabled, vibrate, label),
            timeoutMillis = 15_000L,
            maxOutputBytes = 8 * 1024,
        )
        if (!insert.ok) {
            // 重建失败按原值恢复，避免用户闹钟丢失
            root.execute(
                insertAlarmCommand(
                    uri,
                    (current.optLongValue("hour") ?: 0L).toInt(),
                    (current.optLongValue("minutes") ?: 0L).toInt(),
                    daysMask,
                    (current.optLongValue("enabled") ?: 1L).toInt(),
                    (current.optLongValue("vibrate") ?: 1L).toInt(),
                    current.optString("message"),
                ),
                timeoutMillis = 15_000L,
                maxOutputBytes = 8 * 1024,
            )
            return error("CLOCK_WRITE_FAILED", "重建闹钟失败，已按原值恢复；请用 list_alarms 核对后再试")
        }
        return when (val after = readAlarms(AlarmMatch(hour = hour, minute = minute, label = label.takeIf(String::isNotEmpty)))) {
            is ClockRead.Ok -> {
                val row = after.rows.firstOrNull { it.optLongValue("_id") != target.id }
                    ?: return error("ALARM_UPDATE_NOT_APPLIED", "重建后读不到该闹钟，未生效")
                ok("update_alarm")
                    .put("alarm_id", row.optLongValue("_id"))
                    .put("previous_alarm_id", target.id)
                    .put("changed", JSONArray(changed))
                    .put("hour", row.optLongValue("hour"))
                    .put("minute", row.optLongValue("minutes"))
                    .put("label", row.optString("message"))
                    .put("enabled", (row.optLongValue("enabled") ?: 0L) == 1L)
                    .put("vibrate", (row.optLongValue("vibrate") ?: 0L) == 1L)
                    .put("summary", "已修改闹钟 ${clockTime(row)}${labelSuffix(row)}：" + changed.joinToString("、") + "（alarm_id 有变化）")
                    .toString()
            }
            ClockRead.Unavailable -> error("CLOCK_DATA_UNAVAILABLE", "修改已执行，但读取时钟数据校验失败；可用 list_alarms 核对")
        }
    }

    private fun updateAlarmViaDatabase(target: AlarmTarget, args: JSONObject): String {
        val sqlite = sqliteBinary()
            ?: return error("CLOCK_SQLITE_UNAVAILABLE", "系统缺少 sqlite3，无法直接改写时钟数据库；可用 set_alarm 创建新闹钟")
        val current = target.row
        val sets = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val expected = mutableMapOf<String, String>()
        val dayOfWeek = target.repeatColumn?.let { current.optLongValue(it) } ?: 0L
        if (args.has("enabled")) {
            val enabled = if (args.optBoolean("enabled")) 1 else 0
            sets += "enabled=$enabled"
            changed += "enabled"
            expected["enabled"] = enabled.toString()
        }
        if (args.has("vibrate")) {
            val vibrate = if (args.optBoolean("vibrate")) 1 else 0
            sets += "vibrate=$vibrate"
            changed += "vibrate"
            expected["vibrate"] = vibrate.toString()
        }
        if (args.has("label")) {
            val label = args.optString("label").trim()
            sets += "message='${CalendarEventFormats.escapeSqlString(label)}'"
            changed += "label"
            expected["message"] = label
        }
        val hour = args.optLongValue("hour")?.toInt()
        val minute = args.optLongValue("minute")?.toInt()
        if (hour != null && hour !in 0..23) return error("INVALID_ARGUMENT", "hour 必须在 0 到 23")
        if (minute != null && minute !in 0..59) return error("INVALID_ARGUMENT", "minute 必须在 0 到 59")
        if (hour != null || minute != null) {
            val newHour = hour ?: (current.optLongValue("hour")?.toInt() ?: 0)
            val newMinute = minute ?: (current.optLongValue("minutes")?.toInt() ?: 0)
            sets += "hour=$newHour"
            sets += "minutes=$newMinute"
            changed += "time"
            expected["hour"] = newHour.toString()
            expected["minutes"] = newMinute.toString()
            // 一次性闹钟（重复列为空）直接重算下次触发时间；重复闹钟交给时钟应用重算。
            if (dayOfWeek == 0L) {
                sets += "alarmtime=${nextOneShotTriggerMillis(newHour, newMinute)}"
            }
        }
        if (sets.isEmpty()) {
            return ok("update_alarm")
                .put("alarm_id", target.id)
                .put("changed", JSONArray())
                .put("summary", "没有提供要修改的字段，闹钟保持不变")
                .toString()
        }
        val databasePath = clockDatabasePaths().firstOrNull()
            ?: return error("CLOCK_DATA_UNAVAILABLE", "无法定位系统时钟数据库，无法修改闹钟")
        val sql = "PRAGMA busy_timeout=3000; UPDATE alarms SET ${sets.joinToString(", ")} WHERE _id=${target.id};"
        val result = root.execute(
            "${shellQuote(sqlite)} ${shellQuote(databasePath)} ${shellQuote(sql)}",
            timeoutMillis = 15_000L,
            maxOutputBytes = 8 * 1024,
        )
        if (!result.ok) return error("CLOCK_WRITE_FAILED", "修改命令执行失败（exit=${result.exitCode}）")
        return when (val after = readAlarms(AlarmMatch(id = target.id))) {
            is ClockRead.Ok -> {
                val row = after.rows.firstOrNull()
                    ?: return error("ALARM_UPDATE_NOT_APPLIED", "更新后读不到该闹钟，未生效")
                val mismatch = expected.entries.firstOrNull { (column, value) ->
                    row.fieldString(column) != value
                }
                if (mismatch != null) {
                    return error(
                        "ALARM_UPDATE_NOT_APPLIED",
                        "字段 ${mismatch.key} 期望为 ${mismatch.value}，实际为 ${row.fieldString(mismatch.key) ?: "空"}；未生效",
                    )
                }
                val repeatNote = if ("time" in changed && dayOfWeek != 0L) {
                    "重复闹钟的下次触发时间由时钟应用在下次打开时重算"
                } else {
                    null
                }
                ok("update_alarm")
                    .put("alarm_id", target.id)
                    .put("changed", JSONArray(changed))
                    .put("hour", row.optLongValue("hour"))
                    .put("minute", row.optLongValue("minutes"))
                    .put("label", row.optString("message"))
                    .put("enabled", (row.optLongValue("enabled") ?: 0L) == 1L)
                    .put("vibrate", (row.optLongValue("vibrate") ?: 0L) == 1L)
                    .put("summary", "已修改闹钟 ${clockTime(row)}${labelSuffix(row)}：" + changed.joinToString("、"))
                    .also { payload -> repeatNote?.let { payload.put("note", it) } }
                    .toString()
            }
            ClockRead.Unavailable -> error("CLOCK_DATA_UNAVAILABLE", "修改已执行，但读取时钟数据校验失败")
        }
    }

    // ---------------------------------------------------------------- 时钟 provider 与数据库发现

    /** 定位本机时钟应用的 ContentProvider（content://<时钟包名>/alarm）。 */
    private fun clockProviderUri(): String? {
        discoveredProviderUri?.let { return it }
        if (System.currentTimeMillis() - providerProbeFailedAt < PROBE_RETRY_MS) return null
        for (uri in clockAppLocator.providerUris()) {
            val result = root.execute(
                "content query --uri ${shellQuote(uri)} --projection _id",
                timeoutMillis = 8_000L,
                maxOutputBytes = 4 * 1024,
            )
            if (result.ok) {
                discoveredProviderUri = uri
                return uri
            }
        }
        providerProbeFailedAt = System.currentTimeMillis()
        return null
    }

    /**
     * 发现候选时钟数据库：显式 alarms.db 候选 + 各时钟应用 databases 目录通配。
     * 通配兜住 alarm.db 等厂商命名变体；返回顺序即优先级。
     */
    private fun clockDatabasePaths(): List<String> {
        val userId = context.dataDir.parentFile?.name?.toIntOrNull() ?: return emptyList()
        discoveredClockDatabase?.takeIf { it.first == userId }?.let { return it.second }
        if (System.currentTimeMillis() - databaseProbeFailedAt < PROBE_RETRY_MS) return emptyList()
        val command = buildString {
            append("for p in")
            clockAppLocator.databaseCandidates(userId).forEach { append(' ').append(shellQuote(it)) }
            append("; do [ -f \"\$p\" ] && echo \"\$p\"; done")
            clockAppLocator.preferredPackages().forEach { pkg ->
                listOf("/data/user_de/$userId/$pkg", "/data/user/$userId/$pkg", "/data/data/$pkg").forEach { dir ->
                    append("; ls ").append(shellQuote("$dir/databases")).append("/*.db 2>/dev/null")
                }
            }
        }
        val result = root.execute(command, timeoutMillis = 12_000L, maxOutputBytes = 16 * 1024)
        if (!result.ok) {
            databaseProbeFailedAt = System.currentTimeMillis()
            return emptyList()
        }
        val paths = result.stdout.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("/") }
            .distinct()
            .toList()
        if (paths.isEmpty()) {
            databaseProbeFailedAt = System.currentTimeMillis()
            return emptyList()
        }
        discoveredClockDatabase = userId to paths
        return paths
    }

    /** content query 读全表；projection 从全列到最小列逐级尝试，兼容不同 ROM。 */
    private fun queryProviderAlarms(uri: String): List<JSONObject>? {
        for (projection in PROVIDER_PROJECTIONS) {
            val result = root.execute(
                "content query --uri ${shellQuote(uri)} --projection ${projection.joinToString(",")}",
                timeoutMillis = 12_000L,
                maxOutputBytes = 64 * 1024,
            )
            if (result.ok) return parseContentRows(result.stdout)
        }
        return null
    }

    /**
     * 组装 content insert 命令。--bind 的值不能含冒号（会被当作类型分隔符，报
     * Binding not well formed），标签中的半角冒号统一替换为全角；alert 铃声字段
     * 不主动绑定，由时钟应用填默认值。
     */
    private fun insertAlarmCommand(uri: String, hour: Int, minute: Int, daysMask: Long, enabled: Int, vibrate: Int, label: String): String =
        buildString {
            append("content insert --uri ").append(shellQuote(uri))
            append(" --bind hour:i:").append(hour)
            append(" --bind minutes:i:").append(minute)
            append(" --bind daysofweek:i:").append(daysMask)
            append(" --bind enabled:i:").append(enabled)
            append(" --bind vibrate:i:").append(vibrate)
            if (label.isNotEmpty()) append(" --bind message:s:").append(shellQuote(label.replace(':', '：')))
        }

    /** 解析 content query 输出：每行 "Row: 0 k=v, k='v'"；引号内的逗号不切分。 */
    private fun parseContentRows(stdout: String): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        for (line in stdout.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("Row:")) continue
            val body = trimmed.removePrefix("Row:").trim().substringAfter(' ', "")
            if (body.isEmpty()) continue
            val row = JSONObject()
            for (field in splitContentFields(body)) {
                val equals = field.indexOf('=')
                if (equals <= 0) continue
                row.put(field.substring(0, equals).trim(), parseContentValue(field.substring(equals + 1).trim()))
            }
            rows += row
        }
        return rows
    }

    private fun parseContentValue(raw: String): Any = when {
        raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'' ->
            raw.substring(1, raw.length - 1).replace("''", "'")
        raw.matches(INTEGER_VALUE) -> raw.toLongOrNull() ?: raw
        else -> raw
    }

    private fun splitContentFields(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        for (ch in body) {
            when {
                ch == '\'' -> { inQuote = !inQuote; current.append(ch) }
                ch == ',' && !inQuote -> { parts += current.toString().trim(); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return parts
    }

    private fun sqliteBinary(): String? {
        val result = root.execute(
            "command -v sqlite3 2>/dev/null; ls /system/bin/sqlite3 /system/xbin/sqlite3 2>/dev/null",
            timeoutMillis = 8_000L,
            maxOutputBytes = 4 * 1024,
        )
        if (!result.ok) return null
        return result.stdout.lineSequence()
            .map(String::trim)
            .firstOrNull { it == "sqlite3" || it.startsWith("/") }
    }

    // ---------------------------------------------------------------- 计时器（仅数据库可读）

    private fun listTimers(database: SQLiteDatabase, args: JSONObject): String {
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        // ColorOS 系时钟用 timer_schedule；AOSP 系变体通常叫 timers。按实际结构取表。
        val timerTables = listOf(
            "timer_schedule" to setOf("_id", "state"),
            "timers" to setOf("_id", "state"),
        )
        for ((table, required) in timerTables) {
            val columns = database.tableColumns(table)
            if (!columns.containsAll(required)) continue
            val order = listOf("alert_time", "start_time", "_id").firstOrNull(columns::contains) ?: "_id"
            val items = database.rows(
                table = table,
                columns = listOf(
                    "_id", "description", "duration", "state", "first_start_time", "start_time",
                    "remain_time", "pause_remain_time", "alert_time", "length", "time_remaining",
                ),
                selection = "state<>0",
                selectionArgs = null,
                order = "$order ASC",
                limit = limit,
            )
            return ok("list_active_timers", items, limit)
        }
        return error("CLOCK_SCHEMA_UNSUPPORTED", "当前时钟应用的计时器数据结构暂不受支持")
    }

    /** 计时器等仅数据库可读的数据：逐个候选快照，首个能打开且块内不抛异常的生效。 */
    private fun readClockDatabase(block: (SQLiteDatabase) -> String): String =
        synchronized(snapshotLock) {
            for (path in clockDatabasePaths()) {
                withSnapshot(path, CLOCK_DB_MAX_BYTES, block)?.let { return@synchronized it }
            }
            error("CLOCK_DATA_UNAVAILABLE", "系统时钟数据暂时不可访问（需要 Root）")
        }

    // ---------------------------------------------------------------- 剪贴板与健康数据（固定路径）

    private fun searchClipboard(database: SQLiteDatabase, args: JSONObject): String {
        if (!database.hasColumns("CLIPBOARD_ITEM", setOf("TIME", "CONTENT"))) {
            return error("CLIPBOARD_SCHEMA_UNSUPPORTED", "当前剪贴板历史结构暂不受支持")
        }
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val query = args.optString("query").trim()
        val items = database.rows(
            table = "CLIPBOARD_ITEM",
            columns = listOf("TIME", "CONTENT"),
            selection = query.takeIf(String::isNotBlank)?.let { "CONTENT LIKE ? ESCAPE '\\' COLLATE NOCASE" },
            selectionArgs = query.takeIf(String::isNotBlank)?.let { arrayOf("%${it.escapeLike()}%") },
            order = "TIME DESC",
            limit = limit,
        )
        return ok("search_clipboard_history", items, limit)
    }

    private fun healthSummary(database: SQLiteDatabase, args: JSONObject): String {
        val days = args.optInt("days", 7).coerceIn(1, 30)
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        val summary = JSONObject()
        database.aggregate(
            "steps_record_table",
            "SELECT COUNT(*), COALESCE(SUM(count),0) FROM steps_record_table WHERE end_time>=?",
            cutoff,
        )?.let { summary.put("steps", JSONObject().put("records", it[0]).put("count", it[1])) }
        database.aggregate(
            "sleep_session_record_table",
            "SELECT COUNT(*), COALESCE(SUM(end_time-start_time),0) FROM sleep_session_record_table WHERE end_time>=?",
            cutoff,
        )?.let { summary.put("sleep", JSONObject().put("sessions", it[0]).put("duration_ms", it[1])) }
        database.aggregate(
            "exercise_session_record_table",
            "SELECT COUNT(*), COALESCE(SUM(end_time-start_time),0) FROM exercise_session_record_table WHERE end_time>=?",
            cutoff,
        )?.let { summary.put("exercise", JSONObject().put("sessions", it[0]).put("duration_ms", it[1])) }
        if (
            database.hasColumns("heart_rate_record_table", setOf("row_id", "end_time")) &&
            database.hasColumns("heart_rate_record_series_table", setOf("parent_key", "beats_per_minute"))
        ) {
            database.rawQuery(
                "SELECT COUNT(*), MIN(beats_per_minute), MAX(beats_per_minute), AVG(beats_per_minute) " +
                    "FROM heart_rate_record_series_table WHERE parent_key IN " +
                    "(SELECT row_id FROM heart_rate_record_table WHERE end_time>=?)",
                arrayOf(cutoff.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst() && cursor.getLong(0) > 0) {
                    summary.put(
                        "heart_rate",
                        JSONObject()
                            .put("samples", cursor.getLong(0))
                            .put("min_bpm", cursor.getLong(1))
                            .put("max_bpm", cursor.getLong(2))
                            .put("avg_bpm", cursor.getDouble(3)),
                    )
                }
            }
        }
        database.latestMeasurement("weight_record_table", "time", "weight", cutoff)
            ?.let { summary.put("latest_weight_kg", it / 1_000.0) }
        database.latestMeasurement("oxygen_saturation_record_table", "time", "percentage", cutoff)
            ?.let { summary.put("latest_oxygen_saturation", it) }
        return JSONObject()
            .put("ok", true)
            .put("tool", "get_health_summary")
            .put("window_days", days)
            .put("summary", summary)
            .toString()
    }

    private fun readDatabase(source: DatabaseSource, unavailableCode: String, block: (SQLiteDatabase) -> String): String =
        synchronized(snapshotLock) {
            withDatabase(source, block) ?: error(unavailableCode, source.unavailableMessage)
        }

    /** 快照 + 只读打开 + 用后删除；块内抛异常同样按不可用处理。 */
    private fun <T> withDatabase(source: DatabaseSource, block: (SQLiteDatabase) -> T): T? =
        withSnapshot(
            source.path.replace("{user}", context.dataDir.parentFile?.name?.toIntOrNull()?.toString() ?: return null),
            source.maxBytes,
            block,
        )

    private fun <T> withSnapshot(sourcePath: String, maxBytes: Long, block: (SQLiteDatabase) -> T): T? {
        val snapshot = createSnapshot(sourcePath, maxBytes) ?: return null
        return try {
            runCatching {
                SQLiteDatabase.openDatabase(
                    snapshot.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ).use(block)
            }.getOrNull()
        } finally {
            deleteSnapshot(snapshot)
        }
    }

    // ---------------------------------------------------------------- 工具函数

    private fun clockTime(row: JSONObject): String {
        val hour = (row.optLongValue("hour") ?: 0L).toInt()
        val minute = (row.optLongValue("minutes") ?: 0L).toInt()
        return "%02d:%02d".format(hour, minute)
    }

    private fun labelSuffix(row: JSONObject): String {
        val label = row.optString("message").trim()
        return if (label.isEmpty()) "" else "「$label」"
    }

    /** 一次性闹钟的下次触发时间：今天已过就顺延到明天。 */
    private fun nextOneShotTriggerMillis(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= now) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis
    }

    private fun JSONObject.optLongValue(name: String): Long? = when (val value = opt(name)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun JSONObject.fieldString(name: String): String? = when (val value = opt(name)) {
        null -> null
        is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    private fun createSnapshot(source: String, maxBytes: Long): File? {
        cleanupStaleSnapshots()
        val snapshot = runCatching { File.createTempFile(SNAPSHOT_PREFIX, ".db", context.cacheDir) }.getOrNull()
            ?: return null
        val command = buildString {
            append("[ -f ").append(shellQuote(source)).append(" ] || exit 21; ")
            append("[ ! -L ").append(shellQuote(source)).append(" ] || exit 22; ")
            append("[ \"\$(stat -c %s ").append(shellQuote(source)).append(")\" -le ").append(maxBytes).append(" ] || exit 23; ")
            append("cp ").append(shellQuote(source)).append(' ').append(shellQuote(snapshot.absolutePath)).append(" || exit 24; ")
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val extraSource = source + suffix
                val extraTarget = snapshot.absolutePath + suffix
                append("if [ -f ").append(shellQuote(extraSource)).append(" ]; then ")
                append("[ ! -L ").append(shellQuote(extraSource)).append(" ] || exit 25; ")
                append("[ \"\$(stat -c %s ").append(shellQuote(extraSource)).append(")\" -le ").append(maxBytes).append(" ] || exit 26; ")
                append("cp ").append(shellQuote(extraSource)).append(' ').append(shellQuote(extraTarget)).append(" || exit 25; fi; ")
            }
        }
        val result = root.execute(command, timeoutMillis = 15_000L, maxOutputBytes = 8 * 1024)
        if (result.ok) return snapshot
        deleteSnapshot(snapshot)
        return null
    }

    private fun SQLiteDatabase.rows(
        table: String,
        columns: List<String>,
        selection: String?,
        selectionArgs: Array<String>? = null,
        order: String,
        limit: Int,
    ): JSONArray {
        val available = tableColumns(table)
        val projection = columns.filter(available::contains)
        return JSONArray().also { rows ->
            query(table, projection.toTypedArray(), selection, selectionArgs, null, null, order, limit.toString()).use { cursor ->
                while (cursor.moveToNext()) rows.put(cursor.toJson())
            }
        }
    }

    private fun SQLiteDatabase.aggregate(table: String, query: String, cutoff: Long): LongArray? {
        if (tableColumns(table).isEmpty()) return null
        return runCatching {
            rawQuery(query, arrayOf(cutoff.toString())).use { cursor ->
                if (!cursor.moveToFirst()) null else longArrayOf(cursor.getLong(0), cursor.getLong(1))
            }
        }.getOrNull()
    }

    private fun SQLiteDatabase.latestMeasurement(table: String, timeColumn: String, valueColumn: String, cutoff: Long): Double? {
        if (!hasColumns(table, setOf(timeColumn, valueColumn))) return null
        return rawQuery(
            "SELECT $valueColumn FROM $table WHERE $timeColumn>=? ORDER BY $timeColumn DESC LIMIT 1",
            arrayOf(cutoff.toString()),
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null }
    }

    private fun SQLiteDatabase.hasColumns(table: String, required: Set<String>): Boolean =
        tableColumns(table).containsAll(required)

    private fun SQLiteDatabase.tableColumns(table: String): Set<String> = runCatching {
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val name = cursor.getColumnIndex("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }
    }.getOrDefault(emptySet())

    private fun Cursor.toJson(): JSONObject = JSONObject().also { row ->
        for (index in 0 until columnCount) {
            if (isNull(index)) continue
            val value: Any = when (getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_STRING -> getString(index).take(MAX_FIELD_CHARS)
                else -> continue
            }
            row.put(getColumnName(index), value)
        }
    }

    /** 重复闹钟的星期列：ColorOS/AOSP 老结构叫 daysofweek，新结构叫 days_of_week。 */
    private fun SQLiteDatabase.alarmRepeatColumn(): String? =
        listOf("daysofweek", "days_of_week").firstOrNull { hasColumns("alarms", setOf(it)) }

    private fun SQLiteDatabase.alarmOrder(): String {
        val columns = tableColumns("alarms")
        return buildList {
            if ("enabled" in columns) add("enabled DESC")
            if ("alarmtime" in columns) add("alarmtime ASC")
        }.ifEmpty { listOf("_id ASC") }.joinToString(", ")
    }

    private fun ok(tool: String, items: JSONArray, limit: Int): String = JSONObject()
        .put("ok", true)
        .put("tool", tool)
        .put("items", items)
        .put("count", items.length())
        .put("truncated", items.length() == limit)
        .toString()

    private fun ok(tool: String): JSONObject = JSONObject().put("ok", true).put("tool", tool)

    private fun cleanupStaleSnapshots() {
        context.cacheDir.listFiles()?.filter { it.name.startsWith(SNAPSHOT_PREFIX) }?.forEach(File::delete)
    }

    private fun deleteSnapshot(snapshot: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(snapshot.absolutePath + it).delete() }
    }

    private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun error(code: String, message: String): String = JSONObject().put("ok", false).put("code", code).put("message", message).toString()
    private fun sensitive(content: String) = AgentModelClient.ToolResult(content = content, sensitive = true)

    private data class DatabaseSource(val path: String, val maxBytes: Long, val unavailableMessage: String)

    private companion object {
        val snapshotLock = Any()
        const val SNAPSHOT_PREFIX = "eta-private-data-"
        const val MAX_FIELD_CHARS = 4_000
        const val DAY_MS = 24L * 60 * 60 * 1_000
        const val CLOCK_DB_MAX_BYTES = 32L * 1024 * 1024
        const val PROBE_RETRY_MS = 60_000L
        val INTEGER_VALUE = Regex("-?\\d+")
        val CLOCK_TIME = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")
        // provider projection：全列优先（真机验证含 alert/type），失败退最小列
        val PROVIDER_PROJECTIONS = listOf(
            listOf("_id", "hour", "minutes", "daysofweek", "alarmtime", "enabled", "vibrate", "message", "alert", "type"),
            listOf("_id", "hour", "minutes", "daysofweek", "alarmtime", "enabled", "vibrate", "message"),
        )
        val ALARM_COLUMNS = listOf(
            "_id", "hour", "minutes", "daysofweek", "alarmtime", "enabled", "message", "vibrate",
        )
        val CLIPBOARD_DATABASE = DatabaseSource(
            "/data/user/{user}/com.sohu.inputmethod.sogouoem/databases/clipboard_db",
            32L * 1024 * 1024,
            "当前输入法没有可访问的剪贴板历史",
        )
        val HEALTH_DATABASE = DatabaseSource(
            "/data/system_ce/{user}/healthconnect/healthconnect.db",
            256L * 1024 * 1024,
            "系统健康数据暂时不可访问",
        )
    }
}
