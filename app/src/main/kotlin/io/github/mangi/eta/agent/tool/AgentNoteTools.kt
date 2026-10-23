package io.github.mangi.eta.agent.tool

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import java.util.UUID

/**
 * ColorOS 便签：创建 / 读取 / 移入回收站。
 * 只通过 Root 直达便签 Provider，每次写入都读回校验；结果未确认时不重复创建。
 */
internal class AgentNoteTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? {
        if (name !in TOOL_NAMES) return null
        if (!rootAvailable()) {
            return sensitiveError(
                "ROOT_REQUIRED",
                "直接读写系统便签需要 Root 权限；请到权限页授权后重试",
            )
        }
        return when (name) {
            "create_note" -> create(args)
            "read_note" -> read(args)
            "delete_note" -> delete(args)
            else -> null
        }
    }

    private fun create(args: JSONObject): AgentModelClient.ToolResult {
        val content = args.optString("content")
        if (content.isBlank()) return sensitiveError("INVALID_ARGUMENT", "content 不能为空")
        if (content.length > MAX_CONTENT_CHARS) {
            return sensitiveError("TEXT_TOO_LONG", "便签正文最多 $MAX_CONTENT_CHARS 字")
        }
        val title = args.optString("title").trim()
        if (title.length > MAX_TITLE_CHARS) {
            return sensitiveError("INVALID_ARGUMENT", "标题最多 $MAX_TITLE_CHARS 字")
        }
        // 先确认便签 Provider 可读，避免在未初始化时写入半成品。
        query(
            uri = richNotesUri(),
            projection = listOf("local_id"),
            where = "deleted=0",
            limit = 1,
        ) ?: return sensitiveError("NOTES_NOT_READY", "便签尚未提供可读的数据接口，本次未创建")

        val binds = buildList {
            add(Bind("package_name", 's', context.packageName))
            add(Bind("content", 's', content))
            if (title.isNotEmpty()) add(Bind("title", 's', title))
        }
        val insert = root.execute(
            buildString {
                append("content insert --uri ").append(shellQuote(textNoteUri()))
                binds.forEach { append(" --bind ").append(shellQuote("${it.name}:${it.type}:${it.value}")) }
            },
            timeoutMillis = COMMAND_TIMEOUT_MS,
            maxOutputBytes = MAX_OUTPUT_BYTES,
        )
        if (!insert.ok || PersonalDataContentParser.hasProviderFailure(insert.stdout, insert.stderr)) {
            return sensitiveError("NOTE_CREATE_REJECTED", "便签应用没有接受创建请求；请检查便签应用权限或改用手动创建")
        }
        val noteId = parseInsertedId(insert.stdout)
            ?: latestMatchingNote(content)
        if (noteId == null) {
            return sensitive(
                JSONObject()
                    .put("ok", false)
                    .put("code", "NOTE_CREATE_UNVERIFIED")
                    .put("message", "创建命令已执行，但未校验到新便签；请先用 read_note 或 search_coloros_notes 确认，不要重复创建"),
            )
        }
        val note = readRow(noteId)
        val verified = note != null &&
            note.optString("content").contains(content) &&
            (title.isEmpty() || note.optString("title") == title)
        return sensitive(
            JSONObject()
                .put("ok", verified)
                .put("tool", "create_note")
                .put("note_id", noteId)
                .put("note", note ?: JSONObject.NULL)
                .put("verified", verified)
                .also {
                    if (!verified) {
                        it.put("code", "NOTE_CREATE_UNVERIFIED")
                            .put("message", "便签已写入但内容回读不一致；请先读取确认，不要重复创建")
                    }
                },
        )
    }

    private fun read(args: JSONObject): AgentModelClient.ToolResult {
        val id = noteId(args) ?: return invalidNoteId()
        val note = readRow(id)
            ?: return sensitiveError("NOTE_NOT_FOUND", "未找到该便签；它可能已被删除或移入回收站")
        return sensitive(JSONObject().put("ok", true).put("tool", "read_note").put("note", note))
    }

    private fun delete(args: JSONObject): AgentModelClient.ToolResult {
        val id = noteId(args) ?: return invalidNoteId()
        val before = readRow(id)
            ?: return sensitiveError("NOTE_NOT_FOUND", "未找到该便签；它可能已经被删除")
        val uri = textNoteUri() +
            "?caller_package=" + Uri.encode(context.packageName) + "&local_id=" + Uri.encode(id)
        val result = root.execute(
            "content delete --uri ${shellQuote(uri)}",
            timeoutMillis = COMMAND_TIMEOUT_MS,
            maxOutputBytes = MAX_OUTPUT_BYTES,
        )
        val removed = result.ok && readRow(id) == null
        return sensitive(
            JSONObject()
                .put("ok", removed)
                .put("tool", "delete_note")
                .put("note_id", id)
                .put("mode", "recycle")
                .put("before", before)
                .also {
                    if (!removed) {
                        it.put("code", "NOTE_DELETE_NOT_APPLIED")
                            .put("message", "删除后仍能读到此便签，未生效；请到便签应用里手动移入回收站")
                    }
                },
        )
    }

    private fun noteId(args: JSONObject): String? = runCatching {
        UUID.fromString(args.optString("note_id").trim()).toString()
    }.getOrNull()

    private fun invalidNoteId(): AgentModelClient.ToolResult =
        sensitiveError("INVALID_ARGUMENT", "note_id 必须是便签返回的原始 UUID")

    private fun readRow(id: String): JSONObject? = query(
        uri = richNotesUri(),
        projection = listOf("local_id", "raw_title", "raw_text", "update_time"),
        where = "local_id='${escapeSql(id)}' AND deleted=0 AND recycle_time=0",
        limit = 1,
    )?.firstOrNull()?.let { row ->
        JSONObject()
            .put("note_id", row.optString("local_id"))
            .put("title", row.optString("raw_title"))
            .put("content", row.optString("raw_text").take(MAX_CONTENT_CHARS))
            .put("updated_at", row.optLong("update_time"))
    }

    private fun latestMatchingNote(content: String): String? = query(
        uri = richNotesUri(),
        projection = listOf("local_id", "raw_text"),
        where = "deleted=0 AND recycle_time=0",
        sort = "update_time DESC",
        limit = 5,
    )?.firstOrNull { it.optString("raw_text").contains(content) }
        ?.optString("local_id")
        ?.takeIf { it.isNotBlank() }

    private fun parseInsertedId(stdout: String): String? {
        val candidate = stdout.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("content://") }
            ?: return null
        val uri = Uri.parse(candidate)
        val id = uri.getQueryParameter("localId")
            ?: uri.lastPathSegment?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        return id?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
    }

    private fun query(
        uri: String,
        projection: List<String>,
        where: String?,
        sort: String? = null,
        limit: Int? = null,
    ): List<JSONObject>? {
        val command = buildString {
            append("content query --uri ").append(shellQuote(uri))
            append(" --projection ").append(shellQuote(projection.joinToString(":")))
            where?.let { append(" --where ").append(shellQuote(it)) }
            sort?.let { append(" --sort ").append(shellQuote(it)) }
        }
        val result = root.execute(command, timeoutMillis = COMMAND_TIMEOUT_MS, maxOutputBytes = MAX_OUTPUT_BYTES)
        if (!result.ok || PersonalDataContentParser.hasProviderFailure(result.stdout, result.stderr)) return null
        val rows = PersonalDataContentParser.parseRows(result.stdout, projection)
        return limit?.let { rows.take(it) } ?: rows
    }

    private fun authority(): String = "${DeviceToolContract.appUserId(context)}@com.nearme.note"
    private fun richNotesUri(): String = "content://${authority()}/rich_notes"
    private fun textNoteUri(): String = "content://${authority()}/text_note"

    private fun escapeSql(value: String): String = value.replace("'", "''")
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun sensitive(payload: JSONObject) = AgentModelClient.ToolResult(content = payload.toString(), sensitive = true)
    private fun sensitiveError(code: String, message: String) = sensitive(
        JSONObject().put("ok", false).put("code", code).put("message", message),
    )

    private data class Bind(val name: String, val type: Char, val value: String)

    private companion object {
        val TOOL_NAMES = setOf("create_note", "read_note", "delete_note")
        const val MAX_CONTENT_CHARS = 20_000
        const val MAX_TITLE_CHARS = 200
        const val COMMAND_TIMEOUT_MS = 15_000L
        const val MAX_OUTPUT_BYTES = 128 * 1024
    }
}
