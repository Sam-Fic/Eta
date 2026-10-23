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

/** 读取系统私有数据库；表、字段与查询均不接受模型输入。时钟库位置运行期自动发现，其余为真机验证的固定路径。 */
internal class AgentPrivateDatabaseTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? = when (name) {
        "list_alarms" -> sensitive(readClockDatabase { listAlarms(it, args) })
        "list_active_timers" -> sensitive(readClockDatabase { listTimers(it, args) })
        "delete_alarm" -> sensitive(deleteAlarm(args))
        "update_alarm" -> sensitive(updateAlarm(args))
        "search_clipboard_history" -> sensitive(readDatabase(CLIPBOARD_DATABASE, "CLIPBOARD_HISTORY_UNAVAILABLE") { searchClipboard(it, args) })
        "get_health_summary" -> sensitive(readDatabase(HEALTH_DATABASE, "HEALTH_DATA_UNAVAILABLE") { healthSummary(it, args) })
        else -> null
    }

    private fun listAlarms(database: SQLiteDatabase, args: JSONObject): String {
        if (!database.hasColumns("alarms", setOf("_id", "hour", "minutes", "enabled"))) {
            return error("CLOCK_SCHEMA_UNSUPPORTED", "当前时钟应用的数据库结构暂不受支持")
        }
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val order = database.alarmOrder()
        val items = database.rows(
            table = "alarms",
            columns = listOf(
                "_id", "hour", "minutes", "daysofweek", "days_of_week", "alarmtime", "enabled",
                "message", "vibrate", "deleteAfterUse", "workdaySwitch", "holidaySwitch", "snoozeTime",
            ),
            selection = if (args.optBoolean("enabled_only", true)) "enabled=1" else null,
            order = order,
            limit = limit,
        )
        return ok("list_alarms", items, limit)
    }

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

    private val clockAppLocator = ClockAppLocator(context)

    /** 已探测到的时钟数据库：(userId, path)；探测成功后缓存，避免每轮重复 Root 探测。 */
    @Volatile
    private var discoveredClockDatabase: Pair<Int, String>? = null

    private fun readDatabase(source: DatabaseSource, unavailableCode: String, block: (SQLiteDatabase) -> String): String =
        synchronized(snapshotLock) {
            withDatabase(source, block) ?: error(unavailableCode, source.unavailableMessage)
        }

    /** 时钟数据读取：先发现系统时钟应用的数据库，再快照只读访问。 */
    private fun readClockDatabase(block: (SQLiteDatabase) -> String): String =
        synchronized(snapshotLock) {
            val path = clockDatabasePath()
                ?: return@synchronized error(
                    "CLOCK_DATA_UNAVAILABLE",
                    "无法定位系统时钟应用的数据库；读取闹钟与计时器需要 Root，且时钟应用需使用标准 alarms.db 结构",
                )
            withSnapshot(path, CLOCK_DB_MAX_BYTES, block)
                ?: error("CLOCK_DATA_UNAVAILABLE", "系统时钟数据暂时不可访问（需要 Root）")
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

    // ---------------------------------------------------------------- 闹钟查询与修改（发现系统时钟应用后直达其数据库）

    private sealed interface ClockRead {
        data class Ok(val rows: List<JSONObject>) : ClockRead
        data object SchemaUnsupported : ClockRead
        data object Unavailable : ClockRead
    }

    private sealed interface ClockPrecondition {
        data class Ready(val sqlite: String) : ClockPrecondition
        data class Failed(val error: String) : ClockPrecondition
    }

    private data class AlarmTarget(val id: Long, val row: JSONObject, val repeatColumn: String?)

    private sealed interface AlarmResolution {
        data class Found(val target: AlarmTarget) : AlarmResolution
        data class Failed(val error: String) : AlarmResolution
    }

    private fun deleteAlarm(args: JSONObject): String {
        val sqlite = when (val precondition = clockWritePreconditions()) {
            is ClockPrecondition.Failed -> return precondition.error
            is ClockPrecondition.Ready -> precondition.sqlite
        }
        return when (val resolution = resolveAlarm(args)) {
            is AlarmResolution.Failed -> resolution.error
            is AlarmResolution.Found -> {
                val target = resolution.target
                val databasePath = clockDatabasePath()
                    ?: return error("CLOCK_DATA_UNAVAILABLE", "无法定位系统时钟数据库，无法删除闹钟")
                val sql = "PRAGMA busy_timeout=3000; DELETE FROM alarms WHERE _id=${target.id};"
                val result = root.execute(
                    "${shellQuote(sqlite)} ${shellQuote(databasePath)} ${shellQuote(sql)}",
                    timeoutMillis = 15_000L,
                    maxOutputBytes = 8 * 1024,
                )
                if (!result.ok) return error("CLOCK_WRITE_FAILED", "删除命令执行失败（exit=${result.exitCode}）")
                when (val after = readAlarms("_id=?", arrayOf(target.id.toString()))) {
                    is ClockRead.Ok ->
                        if (after.rows.isEmpty()) {
                            ok("delete_alarm")
                                .put("alarm_id", target.id)
                                .put("hour", target.row.optLongValue("hour"))
                                .put("minute", target.row.optLongValue("minutes"))
                                .put("label", target.row.optString("message"))
                                .put(
                                    "summary",
                                    "已删除闹钟 ${clockTime(target.row)}${labelSuffix(target.row)}",
                                )
                                .put("note", "若时钟界面仍显示该闹钟，重新打开时钟应用即可刷新；不要重复调用本工具")
                                .toString()
                        } else {
                            error("ALARM_DELETE_NOT_APPLIED", "删除后仍能读到该闹钟，未生效；请稍后重试或到时钟应用里手动删除")
                        }
                    is ClockRead.SchemaUnsupported -> error("CLOCK_SCHEMA_UNSUPPORTED", "当前时钟数据库结构暂不受支持")
                    is ClockRead.Unavailable -> error("CLOCK_DATA_UNAVAILABLE", "删除已执行，但读取时钟数据校验失败")
                }
            }
        }
    }

    private fun updateAlarm(args: JSONObject): String {
        val sqlite = when (val precondition = clockWritePreconditions()) {
            is ClockPrecondition.Failed -> return precondition.error
            is ClockPrecondition.Ready -> precondition.sqlite
        }
        return when (val resolution = resolveAlarm(args)) {
            is AlarmResolution.Failed -> resolution.error
            is AlarmResolution.Found -> {
                val target = resolution.target
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
                val databasePath = clockDatabasePath()
                    ?: return error("CLOCK_DATA_UNAVAILABLE", "无法定位系统时钟数据库，无法修改闹钟")
                val sql = "PRAGMA busy_timeout=3000; UPDATE alarms SET ${sets.joinToString(", ")} WHERE _id=${target.id};"
                val result = root.execute(
                    "${shellQuote(sqlite)} ${shellQuote(databasePath)} ${shellQuote(sql)}",
                    timeoutMillis = 15_000L,
                    maxOutputBytes = 8 * 1024,
                )
                if (!result.ok) return error("CLOCK_WRITE_FAILED", "修改命令执行失败（exit=${result.exitCode}）")
                when (val after = readAlarms("_id=?", arrayOf(target.id.toString()))) {
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
                        return ok("update_alarm")
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
                    is ClockRead.SchemaUnsupported -> error("CLOCK_SCHEMA_UNSUPPORTED", "当前时钟数据库结构暂不受支持")
                    is ClockRead.Unavailable -> error("CLOCK_DATA_UNAVAILABLE", "修改已执行，但读取时钟数据校验失败")
                }
            }
        }
    }

    /** Root + sqlite3 两项前置检查；不满足时返回可展示给模型的错误 JSON。 */
    private fun clockWritePreconditions(): ClockPrecondition {
        if (!rootAvailable()) {
            return ClockPrecondition.Failed(
                error("ROOT_REQUIRED", "直接修改系统闹钟需要 Root 权限；可用 set_alarm 创建新闹钟"),
            )
        }
        val sqlite = sqliteBinary()
            ?: return ClockPrecondition.Failed(
                error("CLOCK_SQLITE_UNAVAILABLE", "系统缺少 sqlite3，无法直接改写时钟数据库；可用 set_alarm 创建新闹钟"),
            )
        return ClockPrecondition.Ready(sqlite)
    }

    /**
     * 定位系统时钟数据库：先取缓存的探测结果，未命中时用一条 Root 命令探测全部候选路径。
     * 候选顺序由 ClockAppLocator 决定（本机默认时钟应用优先，AOSP/常见厂商包名兜底）。
     */
    private fun clockDatabasePath(): String? {
        val userId = context.dataDir.parentFile?.name?.toIntOrNull() ?: return null
        discoveredClockDatabase?.takeIf { it.first == userId }?.let { return it.second }
        val candidates = clockAppLocator.databaseCandidates(userId)
        if (candidates.isEmpty()) return null
        val command = buildString {
            append("for p in")
            candidates.forEach { append(' ').append(shellQuote(it)) }
            append("; do [ -f \"\$p\" ] && { echo \"\$p\"; exit 0; }; done; exit 9")
        }
        val result = root.execute(command, timeoutMillis = 10_000L, maxOutputBytes = 4 * 1024)
        if (!result.ok) return null
        val found = result.stdout.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty) ?: return null
        discoveredClockDatabase = userId to found
        return found
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
        val conditions = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        if (alarmId != null) conditions += "_id=$alarmId"
        if (matchTime.isNotEmpty()) {
            val (hour, minute) = matchTime.split(":")
            conditions += "hour=${hour.toInt()} AND minutes=${minute.toInt()}"
        }
        if (matchLabel.isNotEmpty()) {
            conditions += "message LIKE ? ESCAPE '\\' COLLATE NOCASE"
            selectionArgs += "%${CalendarEventFormats.escapeLike(matchLabel)}%"
        }
        return when (val read = readAlarms(conditions.joinToString(" AND "), selectionArgs.toTypedArray())) {
            is ClockRead.Unavailable -> AlarmResolution.Failed(
                error("CLOCK_DATA_UNAVAILABLE", "系统时钟数据暂时不可访问（需要 Root）"),
            )
            is ClockRead.SchemaUnsupported -> AlarmResolution.Failed(
                error("CLOCK_SCHEMA_UNSUPPORTED", "当前时钟应用的数据库结构暂不受支持"),
            )
            is ClockRead.Ok -> when (read.rows.size) {
                0 -> AlarmResolution.Failed(
                    error(
                        "ALARM_NOT_FOUND",
                        "没有找到匹配的闹钟；可先用 list_alarms 查看已有闹钟，再用 alarm_id 精确定位",
                    ),
                )
                1 -> {
                    val target = read.rows[0]
                    AlarmResolution.Found(
                        AlarmTarget(
                            id = target.optLongValue("_id") ?: 0L,
                            row = target,
                            repeatColumn = target.optString("__repeat_column__").takeIf(String::isNotBlank),
                        ),
                    )
                }
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

    private fun readAlarms(selection: String?, selectionArgs: Array<String>?): ClockRead =
        withClockSnapshot { database ->
            if (!database.hasColumns("alarms", setOf("_id", "hour", "minutes", "enabled"))) {
                ClockRead.SchemaUnsupported
            } else {
                val repeatColumn = database.alarmRepeatColumn()
                val rows = database.rows(
                    table = "alarms",
                    columns = ALARM_COLUMNS + listOf("days_of_week").filter { it != repeatColumn },
                    selection = selection,
                    selectionArgs = selectionArgs,
                    order = database.alarmOrder(),
                    limit = 10,
                )
                ClockRead.Ok((0 until rows.length()).mapNotNull { row ->
                    rows.optJSONObject(row)?.also { it.put("__repeat_column__", repeatColumn ?: "") }
                })
            }
        } ?: ClockRead.Unavailable

    private fun <T> withClockSnapshot(block: (SQLiteDatabase) -> T): T? {
        val path = clockDatabasePath() ?: return null
        return withSnapshot(path, CLOCK_DB_MAX_BYTES, block)
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
        val ALARM_COLUMNS = listOf(
            "_id", "hour", "minutes", "daysofweek", "alarmtime", "enabled", "message", "vibrate",
        )
        val CLOCK_TIME = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")
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
