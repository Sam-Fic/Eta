package io.github.mangi.eta.agent.tool

import android.app.Application
import android.content.Context
import io.github.mangi.eta.ui.app.AgentAppState
import io.github.mangi.eta.ui.app.AgentSessionHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 管理 Eta 自己的会话历史：文件夹（分类）与会话的增删改查。
 *
 * 会话与文件夹的唯一真相源是界面持有的内存快照（AgentAppState），这里通过进程内单例
 * AgentSessionHost 取到同一个实例；所有写入都复用界面已有的门面方法，因此工具改动与
 * 手工操作完全等价，改动立刻反映在历史列表上。
 *
 * 删除走回收站：delete_conversation 只做软删除（可 restore_conversation 恢复），
 * 真正清理资源需要先删除、再对回收站里的会话调用 delete_conversation_forever。
 */
internal class AgentSelfHistoryTools(
    context: Context,
    /** 当前 run 所属的会话 id；用于把 conversation 参数里的特殊值 current 解析成真实会话。 */
    private val conversationId: String = "",
) {
    private val application: Application? =
        (context.applicationContext as? Application) ?: (context as? Application)

    fun execute(args: JSONObject): String = io {
        // 参数不合法或状态不可用时抛出异常；工具门禁本身不捕获，所以在这里转成结构化错误回报给模型。
        try {
            when (val action = args.optString("action").trim()) {
                "list_folders" -> listFolders()
                "list_conversations" -> listConversations(args)
                "search_conversations" -> searchConversations(args)
                "create_folder" -> createFolder(args)
                "rename_folder" -> renameFolder(args)
                "delete_folder" -> deleteFolder(args)
                "rename_conversation" -> renameConversation(args)
                "move_conversation" -> moveConversation(args)
                "set_pinned" -> setPinned(args)
                "delete_conversation" -> deleteConversation(args)
                "restore_conversation" -> restoreConversation(args)
                "delete_conversation_forever" -> deleteConversationForever(args)
                "empty_trash" -> emptyTrash()
                else -> error("INVALID_ARGUMENT", "action 取值无效：$action")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            error("INTERNAL_ERROR", failure.message ?: "历史管理操作失败")
        }
    }

    private fun state(): AgentAppState? = application?.let { AgentSessionHost.get(it).state }

    private fun unavailable(): String = error("HISTORY_UNAVAILABLE", "当前进程没有可用的会话状态")

    private suspend fun listFolders(): String {
        val state = state() ?: return unavailable()
        val folders = state.agentHistoryFolders()
        val array = JSONArray()
        folders.forEach { array.put(folderJson(it)) }
        return JSONObject()
            .put("ok", true)
            .put("action", "list_folders")
            .put("count", folders.size)
            .put("folders", array)
            .toString()
    }

    private suspend fun listConversations(args: JSONObject): String {
        val state = state() ?: return unavailable()
        // trashed=true 列回收站（按删除时间由新到旧），默认列正常历史。
        val conversations = state.agentHistoryConversations(trashed = args.optBoolean("trashed"))
        val limited = conversations.take(MAX_LIST)
        val array = JSONArray()
        limited.forEach { array.put(conversationJson(it)) }
        return JSONObject()
            .put("ok", true)
            .put("action", "list_conversations")
            .put("total", conversations.size)
            .put("truncated", conversations.size > limited.size)
            .put("conversations", array)
            .toString()
    }

    private suspend fun searchConversations(args: JSONObject): String {
        val state = state() ?: return unavailable()
        val query = args.optString("query").trim()
        if (query.isEmpty()) return error("INVALID_ARGUMENT", "search_conversations 需要提供 query")
        val hits = state.agentHistorySearch(query)
        val limited = hits.take(MAX_SEARCH_HITS)
        val array = JSONArray()
        limited.forEach { hit ->
            array.put(
                JSONObject()
                    .put("conversation_id", hit.conversationId)
                    .put("conversation_title", hit.conversationTitle)
                    .put("message_id", hit.messageId)
                    .put("match", if (hit.isTitleMatch) "title" else "message")
                    .put("role", hit.roleLabel)
                    .put("snippet", hit.snippet),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("action", "search_conversations")
            .put("query", query)
            .put("count", hits.size)
            .put("truncated", hits.size > limited.size)
            .put("hits", array)
            .toString()
    }

    private suspend fun createFolder(args: JSONObject): String {
        val state = state() ?: return unavailable()
        return mutationJson("create_folder", state.agentHistoryCreateFolder(args.optString("name")))
    }

    private suspend fun renameFolder(args: JSONObject): String {
        val state = state() ?: return unavailable()
        val raw = args.optString("folder").trim()
        if (raw.isEmpty() || raw.equals("none", ignoreCase = true)) {
            return error("INVALID_ARGUMENT", "rename_folder 需要提供 folder")
        }
        val resolution = lookupFolder(state.agentHistoryFolders(), raw)
        val target = resolution.id ?: return resolutionError(resolution)
        return mutationJson("rename_folder", state.agentHistoryRenameFolder(target, args.optString("name")))
    }

    private suspend fun deleteFolder(args: JSONObject): String {
        val state = state() ?: return unavailable()
        val raw = args.optString("folder").trim()
        if (raw.isEmpty() || raw.equals("none", ignoreCase = true)) {
            return error("INVALID_ARGUMENT", "delete_folder 需要提供 folder")
        }
        val resolution = lookupFolder(state.agentHistoryFolders(), raw)
        val target = resolution.id ?: return resolutionError(resolution)
        return mutationJson("delete_folder", state.agentHistoryDeleteFolder(target))
    }

    private suspend fun renameConversation(args: JSONObject): String {
        val state = state() ?: return unavailable()
        val resolution = lookupConversation(state.agentHistoryConversations(), args.optString("conversation"))
        val target = resolution.id ?: return resolutionError(resolution)
        return mutationJson("rename_conversation", state.agentHistoryRenameConversation(target, args.optString("name")))
    }

    private suspend fun moveConversation(args: JSONObject): String {
        val state = state() ?: return unavailable()
        val resolution = lookupConversation(state.agentHistoryConversations(), args.optString("conversation"))
        val conversationTarget = resolution.id ?: return resolutionError(resolution)
        val rawFolder = args.optString("folder").trim()
        if (rawFolder.isEmpty() || rawFolder.equals("none", ignoreCase = true)) {
            return mutationJson("move_conversation", state.agentHistoryMoveConversation(conversationTarget, null))
        }
        val folder = lookupFolder(state.agentHistoryFolders(), rawFolder)
        val folderTarget = folder.id ?: return resolutionError(folder)
        return mutationJson("move_conversation", state.agentHistoryMoveConversation(conversationTarget, folderTarget))
    }

    private suspend fun setPinned(args: JSONObject): String {
        if (!args.has("pinned")) return error("INVALID_ARGUMENT", "set_pinned 需要提供 pinned")
        val state = state() ?: return unavailable()
        val resolution = lookupConversation(state.agentHistoryConversations(), args.optString("conversation"))
        val target = resolution.id ?: return resolutionError(resolution)
        return mutationJson("set_pinned", state.agentHistorySetConversationPinned(target, args.optBoolean("pinned")))
    }

    /** 软删除：会话进入回收站，可用 restore_conversation 撤销；重复删除返回现状而非报错。 */
    private suspend fun deleteConversation(args: JSONObject): String {
        val state = state() ?: return unavailable()
        val resolution = lookupConversation(state.agentHistoryConversations(), args.optString("conversation"))
        val target = resolution.id ?: return resolutionError(resolution)
        return mutationJson("delete_conversation", state.agentHistoryDeleteConversation(target))
    }

    private suspend fun restoreConversation(args: JSONObject): String {
        val state = state() ?: return unavailable()
        // 恢复只对回收站成员有意义，所以在回收站列表里解析目标。
        val resolution = lookupConversation(state.agentHistoryConversations(trashed = true), args.optString("conversation"))
        val target = resolution.id ?: return resolutionError(resolution)
        return mutationJson("restore_conversation", state.agentHistoryRestoreConversation(target))
    }

    private suspend fun deleteConversationForever(args: JSONObject): String {
        val state = state() ?: return unavailable()
        val resolution = lookupConversation(state.agentHistoryConversations(trashed = true), args.optString("conversation"))
        val target = resolution.id ?: return resolutionError(resolution)
        return mutationJson("delete_conversation_forever", state.agentHistoryDeleteConversationForever(target))
    }

    private suspend fun emptyTrash(): String {
        val state = state() ?: return unavailable()
        return mutationJson("empty_trash", state.agentHistoryEmptyTrash())
    }

    /** conversation 接受 id / 标题，以及特殊值 current；多义时不猜测，返回候选列表。 */
    private fun lookupConversation(
        conversations: List<AgentAppState.HistoryConversation>,
        raw: String,
    ): Resolution {
        val input = raw.trim()
        if (input.isEmpty()) return Resolution(code = "INVALID_ARGUMENT", message = "该 action 需要提供 conversation")
        if (input.equals("current", ignoreCase = true)) {
            if (conversationId.isBlank()) {
                return Resolution(code = "CONVERSATION_NOT_FOUND", message = "当前没有正在进行的对话")
            }
            val current = conversations.firstOrNull { it.id == conversationId }
                ?: return Resolution(code = "CONVERSATION_NOT_FOUND", message = "当前对话还没有保存到历史")
            return Resolution(id = current.id)
        }
        conversations.firstOrNull { it.id.equals(input, ignoreCase = true) }
            ?.let { return Resolution(id = it.id) }
        val exact = conversations.filter { it.title.equals(input, ignoreCase = true) }
        if (exact.size == 1) return Resolution(id = exact.single().id)
        if (exact.size > 1) {
            return Resolution(
                code = "AMBIGUOUS",
                message = "有多个对话同名：$input，请用 id 指定",
                candidates = exact.map(::describeConversation),
            )
        }
        val partial = conversations.filter { it.title.contains(input, ignoreCase = true) }
        if (partial.size == 1) return Resolution(id = partial.single().id)
        if (partial.size > 1) {
            return Resolution(
                code = "AMBIGUOUS",
                message = "标题包含「$input」的对话有多个，请用 id 指定",
                candidates = partial.map(::describeConversation),
            )
        }
        return Resolution(code = "CONVERSATION_NOT_FOUND", message = "找不到对话：$input")
    }

    /** folder 接受 id / 名称；多义时不猜测，返回候选列表。 */
    private fun lookupFolder(folders: List<AgentAppState.HistoryFolder>, raw: String): Resolution {
        val input = raw.trim()
        folders.firstOrNull { it.id.equals(input, ignoreCase = true) }
            ?.let { return Resolution(id = it.id) }
        val exact = folders.filter { it.name.equals(input, ignoreCase = true) }
        if (exact.size == 1) return Resolution(id = exact.single().id)
        if (exact.size > 1) {
            return Resolution(
                code = "AMBIGUOUS",
                message = "有多个同名文件夹：$input，请用 id 指定",
                candidates = exact.map(::describeFolder),
            )
        }
        val partial = folders.filter { it.name.contains(input, ignoreCase = true) }
        if (partial.size == 1) return Resolution(id = partial.single().id)
        if (partial.size > 1) {
            return Resolution(
                code = "AMBIGUOUS",
                message = "名称包含「$input」的文件夹有多个，请用 id 指定",
                candidates = partial.map(::describeFolder),
            )
        }
        return Resolution(code = "FOLDER_NOT_FOUND", message = "找不到文件夹：$input")
    }

    private fun mutationJson(action: String, result: AgentAppState.HistoryMutation): String {
        if (!result.ok) return error(result.code, result.message)
        val json = JSONObject()
            .put("ok", true)
            .put("action", action)
        result.folder?.let { json.put("folder", folderJson(it)) }
        result.conversation?.let { json.put("conversation", conversationJson(it)) }
        if (result.movedConversations > 0) json.put("moved_conversations", result.movedConversations)
        if (result.deletedConversations > 0) json.put("deleted", result.deletedConversations)
        return json.put("effect", "history_updated").toString()
    }

    private fun resolutionError(resolution: Resolution): String =
        if (resolution.candidates.isEmpty()) {
            error(resolution.code, resolution.message)
        } else {
            JSONObject()
                .put("ok", false)
                .put("code", "AMBIGUOUS")
                .put("message", resolution.message)
                .put("candidates", JSONArray(resolution.candidates))
                .toString()
        }

    private fun folderJson(folder: AgentAppState.HistoryFolder): JSONObject =
        JSONObject()
            .put("id", folder.id)
            .put("name", folder.name)
            .put("sort_index", folder.sortIndex)
            .put("conversation_count", folder.conversationCount)

    private fun conversationJson(conversation: AgentAppState.HistoryConversation): JSONObject =
        JSONObject()
            .put("id", conversation.id)
            .put("title", conversation.title)
            .put("updated_at", conversation.updatedAt)
            .put("created_at", conversation.createdAt)
            .put("folder_id", conversation.folderId ?: JSONObject.NULL)
            .put("folder_name", conversation.folderName ?: JSONObject.NULL)
            .put("pinned", conversation.pinned)
            .put("deleted_at", conversation.deletedAt ?: JSONObject.NULL)

    private fun describeConversation(conversation: AgentAppState.HistoryConversation): String =
        "${conversation.title}（id=${conversation.id}）"

    private fun describeFolder(folder: AgentAppState.HistoryFolder): String =
        "${folder.name}（id=${folder.id}）"

    /** 参数解析结果：命中唯一目标时 [id] 非空，否则 [code]/[message] 或 [candidates] 描述失败原因。 */
    private class Resolution(
        val id: String? = null,
        val code: String = "",
        val message: String = "",
        val candidates: List<String> = emptyList(),
    )

    private fun io(block: suspend () -> String): String = runBlocking(Dispatchers.IO) { block() }

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private companion object {
        const val MAX_LIST = 200
        const val MAX_SEARCH_HITS = 40
    }
}
