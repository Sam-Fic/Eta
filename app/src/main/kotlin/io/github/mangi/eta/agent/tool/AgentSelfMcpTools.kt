package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置 Eta 自己的 MCP（Model Context Protocol）服务：新增、修改、删除远程工具服务器，
 * 并开关单个 MCP 工具。
 *
 * 服务器与工具定义在 Room，Bearer 密钥在 Android Keystore 支撑的密钥库；
 * 运行期只由 `McpRunSnapshot.load()` 读取启用的服务器，所以任何改动都从下一次回复生效。
 */
internal class AgentSelfMcpTools(private val context: Context) {

    fun execute(args: JSONObject): String {
        val action = args.optString("action").trim()
        return io {
            when (action) {
                "list" -> list()
                "add" -> add(args)
                "update" -> update(args)
                "delete" -> delete(args)
                "refresh" -> refresh(args)
                "set_tool" -> setTool(args)
                else -> error(
                    "INVALID_ARGUMENT",
                    "action 必须是 list / add / update / delete / refresh / set_tool",
                )
            }
        }
    }

    private suspend fun list(): String {
        val servers = McpServerRepository.servers()
        val items = JSONArray()
        servers.forEach { server ->
            items.put(serverDetail(server, includeTools = true))
        }
        return JSONObject()
            .put("ok", true)
            .put("action", "list")
            .put("count", servers.size)
            .put("servers", items)
            .toString()
    }

    private suspend fun add(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isBlank()) return error("INVALID_ARGUMENT", "add 需要提供 name")
        val url = args.optString("url").trim()
        if (url.isBlank()) return error("INVALID_ARGUMENT", "add 需要提供 url")
        validateEndpoint(url)?.let { return it }
        val protocol = protocolMode(args) ?: return error("INVALID_ARGUMENT", "protocol 只接受 auto / latest / legacy")
        val token = args.optString("token")
        val authorization = authorizationType(args, tokenPresent = args.has("token"), token = token, current = null)
            ?: return error("INVALID_ARGUMENT", "authorization 只接受 none / bearer")
        if (authorization == McpAuthorizationType.BEARER && token.isBlank()) {
            return error("MCP_TOKEN_REQUIRED", "authorization=bearer 时需要在 token 中提供密钥")
        }
        val enabled = if (args.has("is_enabled")) args.optBoolean("is_enabled") else true
        val draft = McpServerSetting(
            id = "",
            name = name,
            url = url,
            enabled = enabled,
            protocolMode = protocol,
            authorizationType = authorization,
        )
        val discovered = discoverOrError(draft, token.takeIf { authorization == McpAuthorizationType.BEARER })
            ?: return lastDiscoveryError
        val selection = applyToolSelection(discovered, args)
        if (selection is ToolSelection.Invalid) return error("INVALID_ARGUMENT", selection.message)
        val prepared = (selection as? ToolSelection.Applied)?.server ?: discovered
        val added = McpServerRepository.add(prepared, token)
        return saveResult("add", added)
    }

    private suspend fun update(args: JSONObject): String {
        val input = args.optString("server").trim()
        if (input.isBlank()) return error("INVALID_ARGUMENT", "update 需要提供 server（id、名称或地址）")
        val current = findServer(input) ?: return error("SERVER_NOT_FOUND", "找不到 MCP 服务器：$input")
        val name = args.optString("name").trim().takeIf { it.isNotBlank() } ?: current.name
        val url = args.optString("url").trim().takeIf { it.isNotBlank() } ?: current.url
        validateEndpoint(url)?.let { return it }
        val protocol = if (args.has("protocol")) {
            protocolMode(args) ?: return error("INVALID_ARGUMENT", "protocol 只接受 auto / latest / legacy")
        } else {
            current.protocolMode
        }
        val tokenPresent = args.has("token")
        val token = args.optString("token")
        val authorization = authorizationType(args, tokenPresent, token, current.authorizationType)
            ?: return error("INVALID_ARGUMENT", "authorization 只接受 none / bearer")
        if (authorization == McpAuthorizationType.BEARER && tokenPresent && token.isBlank()) {
            return error("MCP_TOKEN_REQUIRED", "authorization=bearer 时需要用非空的 token；要取消认证请传 authorization=none")
        }
        val enabled = if (args.has("is_enabled")) args.optBoolean("is_enabled") else current.enabled
        val effectiveToken = if (tokenPresent) token else McpServerRepository.bearerToken(current.id).orEmpty()
        var updated = current.copy(
            name = name,
            url = url,
            enabled = enabled,
            protocolMode = protocol,
            authorizationType = authorization,
        )
        val endpointChanged = url != current.url ||
            protocol != current.protocolMode ||
            tokenPresent ||
            current.lastProtocolVersion == null
        if (endpointChanged) {
            updated = discoverOrError(updated, effectiveToken.takeIf { authorization == McpAuthorizationType.BEARER })
                ?: return lastDiscoveryError
        }
        val selection = applyToolSelection(updated, args)
        if (selection is ToolSelection.Invalid) return error("INVALID_ARGUMENT", selection.message)
        updated = (selection as? ToolSelection.Applied)?.server ?: updated
        McpServerRepository.update(updated, if (tokenPresent) token else null)
        return saveResult("update", McpServerRepository.serverById(updated.id) ?: updated)
    }

    private suspend fun delete(args: JSONObject): String {
        val input = args.optString("server").trim()
        if (input.isBlank()) return error("INVALID_ARGUMENT", "delete 需要提供 server（id、名称或地址）")
        val server = findServer(input) ?: return error("SERVER_NOT_FOUND", "找不到 MCP 服务器：$input")
        McpServerRepository.delete(server.id)
        return JSONObject()
            .put("ok", true)
            .put("action", "delete")
            .put("server", serverSummary(server))
            .put("effect", "next_reply")
            .toString()
    }

    private suspend fun refresh(args: JSONObject): String {
        val input = args.optString("server").trim()
        if (input.isBlank()) return error("INVALID_ARGUMENT", "refresh 需要提供 server（id、名称或地址）")
        val server = findServer(input) ?: return error("SERVER_NOT_FOUND", "找不到 MCP 服务器：$input")
        val refreshed = runCatching { McpServerManager.refresh(server.id) }
            .getOrElse { throwable -> return error("MCP_CONNECT_FAILED", throwable.message ?: "重新读取 MCP 工具失败") }
        return saveResult("refresh", refreshed)
    }

    private suspend fun setTool(args: JSONObject): String {
        val input = args.optString("server").trim()
        if (input.isBlank()) return error("INVALID_ARGUMENT", "set_tool 需要提供 server（id、名称或地址）")
        val server = findServer(input) ?: return error("SERVER_NOT_FOUND", "找不到 MCP 服务器：$input")
        val toolName = args.optString("tool").trim()
        if (toolName.isBlank()) return error("INVALID_ARGUMENT", "set_tool 需要提供 tool")
        val tool = findTool(server, toolName)
            ?: return error("TOOL_NOT_FOUND", "服务器 ${server.name} 上没有工具：$toolName；先 refresh 再看 list")
        val enabled = if (args.has("enabled")) args.optBoolean("enabled") else true
        McpServerRepository.setToolEnabled(server.id, tool.name, enabled)
        val saved = McpServerRepository.serverById(server.id) ?: server
        return JSONObject()
            .put("ok", true)
            .put("action", "set_tool")
            .put("server", serverSummary(saved))
            .put("tool", toolDetail(tool, tool.name in saved.enabledToolNames))
            .put("enabled_tool_count", saved.activeTools.size)
            .put("effect", "next_reply")
            .toString()
    }

    /** 连接服务器并读取工具列表；失败时不抛异常，而是记下原因供调用方回显。 */
    private fun discoverOrError(server: McpServerSetting, bearerToken: String?): McpServerSetting? =
        runCatching { McpServerManager.discover(server, bearerToken) }
            .onFailure { lastDiscoveryError = error("MCP_CONNECT_FAILED", it.message ?: "连接 MCP 服务器失败") }
            .getOrNull()

    private var lastDiscoveryError: String = error("MCP_CONNECT_FAILED", "连接 MCP 服务器失败")

    private sealed interface ToolSelection {
        data class Applied(val server: McpServerSetting) : ToolSelection
        data class Invalid(val message: String) : ToolSelection
        data object None : ToolSelection
    }

    /** enable_all / tools 决定新服务器上哪些工具立即可用；不传则维持 discover 的结果（新服务器为空）。 */
    private fun applyToolSelection(server: McpServerSetting, args: JSONObject): ToolSelection {
        if (!args.has("enable_all") && !args.has("tools")) return ToolSelection.None
        val available = server.tools.mapTo(mutableSetOf()) { it.name }
        val requested = mutableSetOf<String>()
        if (args.optBoolean("enable_all")) requested += available
        args.optJSONArray("tools")?.let { array ->
            for (index in 0 until array.length()) {
                val name = array.optString(index).trim()
                if (name.isNotBlank()) requested += name
            }
        }
        val unknown = requested - available
        if (unknown.isNotEmpty()) {
            return ToolSelection.Invalid("服务器上没有这些工具：${unknown.joinToString(", ")}")
        }
        return ToolSelection.Applied(server.copy(enabledToolNames = requested))
    }

    private fun validateEndpoint(raw: String): String? =
        runCatching { validateMcpEndpoint(raw) }
            .exceptionOrNull()
            ?.let { error("INVALID_ARGUMENT", it.message ?: "MCP 地址无效：$raw") }

    private fun protocolMode(args: JSONObject): String? {
        val raw = args.optString("protocol").trim()
        if (raw.isBlank()) return McpProtocolMode.AUTO
        return when (raw.lowercase()) {
            "auto" -> McpProtocolMode.AUTO
            "latest" -> McpProtocolMode.LATEST
            "legacy" -> McpProtocolMode.LEGACY
            else -> null
        }
    }

    private fun authorizationType(
        args: JSONObject,
        tokenPresent: Boolean,
        token: String,
        current: String?,
    ): String? {
        val raw = args.optString("authorization").trim()
        if (raw.isNotBlank()) {
            return when (raw.lowercase()) {
                "none" -> McpAuthorizationType.NONE
                "bearer" -> McpAuthorizationType.BEARER
                else -> null
            }
        }
        if (tokenPresent) {
            return if (token.isBlank()) McpAuthorizationType.NONE else McpAuthorizationType.BEARER
        }
        return current ?: McpAuthorizationType.NONE
    }

    private suspend fun findServer(input: String): McpServerSetting? {
        val servers = McpServerRepository.servers()
        return servers.firstOrNull { it.id.equals(input, ignoreCase = true) }
            ?: servers.firstOrNull { it.name.equals(input, ignoreCase = true) }
            ?: servers.firstOrNull { it.name.contains(input, ignoreCase = true) }
            ?: servers.firstOrNull { it.url.trimEnd('/').equals(input.trimEnd('/'), ignoreCase = true) }
    }

    private fun findTool(server: McpServerSetting, input: String): McpToolDefinition? =
        server.tools.firstOrNull { it.name.equals(input, ignoreCase = true) }
            ?: server.tools.firstOrNull { it.title.equals(input, ignoreCase = true) }
            ?: server.tools.firstOrNull { it.name.contains(input, ignoreCase = true) }

    private fun saveResult(action: String, server: McpServerSetting): String =
        JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("server", serverDetail(server, includeTools = true))
            .put("effect", "next_reply")
            .toString()

    private fun serverSummary(server: McpServerSetting): JSONObject =
        JSONObject()
            .put("id", server.id)
            .put("name", server.name)

    private fun serverDetail(server: McpServerSetting, includeTools: Boolean): JSONObject {
        val detail = JSONObject()
            .put("id", server.id)
            .put("name", server.name)
            .put("url", server.url)
            .put("enabled", server.enabled)
            .put("protocol", protocolLabel(server.protocolMode))
            .put("authorization", server.authorizationType)
            .put(
                "has_token",
                server.authorizationType == McpAuthorizationType.BEARER &&
                    !McpServerRepository.bearerToken(server.id).isNullOrBlank(),
            )
            .put("tool_count", server.tools.size)
            .put("enabled_tool_count", server.activeTools.size)
            .put("last_refreshed_at", server.lastRefreshedAt ?: 0L)
        if (includeTools) {
            val tools = JSONArray()
            server.tools.take(MAX_LISTED_TOOLS).forEach { tool ->
                tools.put(toolDetail(tool, tool.name in server.enabledToolNames))
            }
            detail
                .put("truncated", server.tools.size > MAX_LISTED_TOOLS)
                .put("tools", tools)
        }
        return detail
    }

    private fun toolDetail(tool: McpToolDefinition, enabled: Boolean): JSONObject =
        JSONObject()
            .put("name", tool.name)
            .put("title", tool.title)
            .put("description", tool.description)
            .put("read_only", tool.readOnlyHint == true)
            .put("destructive", tool.destructiveHint == true)
            .put("enabled", enabled)

    private fun protocolLabel(mode: String): String = when (mode) {
        McpProtocolMode.LATEST -> "latest"
        McpProtocolMode.LEGACY -> "legacy"
        else -> "auto"
    }

    private fun io(block: suspend () -> String): String = runBlocking(Dispatchers.IO) { block() }

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private companion object {
        const val MAX_LISTED_TOOLS = 60
    }
}