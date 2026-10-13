package io.github.mangi.eta.ui.screens.mcp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme


@Composable
internal fun McpServersScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    EtaScaffoldPage(
        title = stringResource(R.string.route_mcp_servers),
        onBack = onBack,
        actions = {
            McpActionIcon(
                icon = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.mcp_add_server),
                onClick = { showAdd = true },
            )
        },
    ) {
        item(key = "servers") {
            EtaSectionTitle(text = stringResource(R.string.mcp_configured_servers, servers.size))
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                if (servers.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_empty_title),
                        summary = stringResource(R.string.mcp_empty_summary),
                        action = {
                            EtaTextButton(
                                text = stringResource(R.string.mcp_add_server),
                                onClick = { showAdd = true },
                            )
                        },
                    )
                } else {
                    servers.forEach { server ->
                        McpClickableRow(
                            title = server.name,
                            summary = stringResource(
                                R.string.mcp_server_row_summary,
                                server.activeTools.size,
                                server.tools.size,
                            ),
                            onClick = { onNavigate(AppRoute.McpServerDetail(server.id)) },
                        )
                    }
                }
            }
        }
    }

    EtaDialog(
        show = showAdd,
        title = stringResource(R.string.mcp_add_server),
        onDismissRequest = { if (!working) showAdd = false },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            McpTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.mcp_server_name),
                enabled = !working,
            )
            McpTextField(
                value = url,
                onValueChange = { url = it },
                label = stringResource(R.string.mcp_server_url),
                enabled = !working,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            McpTextField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(R.string.mcp_bearer_optional),
                enabled = !working,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            error?.let {
                if (isMaterial3Theme()) {
                    M3Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    MiuixText(
                        text = it,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
            EtaDialogActions(
                confirmText = if (working) {
                    stringResource(R.string.mcp_connecting)
                } else {
                    stringResource(R.string.mcp_add_server)
                },
                confirmEnabled = !working,
                onCancel = { showAdd = false },
                onConfirm = {
                    val normalizedName = name.trim()
                    val normalizedUrl = url.trim()
                    error = when {
                        normalizedName.isBlank() -> resources.getString(R.string.mcp_name_required)
                        else -> runCatching { validateMcpEndpoint(normalizedUrl) }
                            .exceptionOrNull()?.message
                    }
                    if (error == null) scope.launch {
                        working = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val draft = McpServerSetting(
                                    id = "",
                                    name = normalizedName,
                                    url = normalizedUrl,
                                    protocolMode = McpProtocolMode.AUTO,
                                    authorizationType = if (token.isBlank()) {
                                        McpAuthorizationType.NONE
                                    } else {
                                        McpAuthorizationType.BEARER
                                    },
                                )
                                val discovered = McpServerManager.discover(draft, token)
                                McpServerRepository.add(discovered, token)
                            }
                        }
                        working = false
                        result.onSuccess {
                            showAdd = false
                            name = ""
                            url = ""
                            token = ""
                            error = null
                            Toast.makeText(
                                context,
                                resources.getString(R.string.mcp_server_added, it.tools.size),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }.onFailure {
                            error = it.message ?: resources.getString(R.string.mcp_connection_failed)
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun McpServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
    var working by remember { mutableStateOf(false) }
    var pendingRiskyTool by remember { mutableStateOf<McpToolDefinition?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }

    EtaScaffoldPage(
        title = server?.name ?: stringResource(R.string.route_mcp_server_detail),
        onBack = onBack,
        actions = {
            McpActionIcon(
                icon = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.mcp_refresh_tools),
                enabled = server != null && !working,
                onClick = {
                    scope.launch {
                        working = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching { McpServerManager.refresh(serverId) }
                        }
                        working = false
                        Toast.makeText(
                            context,
                            result.fold(
                                onSuccess = { resources.getString(R.string.mcp_refreshed, it.tools.size) },
                                onFailure = { it.message ?: resources.getString(R.string.mcp_refresh_failed) },
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        },
    ) {
        if (server == null) {
            item(key = "missing") {
                McpNoticeRow(title = stringResource(R.string.mcp_server_missing))
            }
            return@EtaScaffoldPage
        }
        item(key = "server") {
            EtaSectionTitle(text = stringResource(R.string.mcp_server_settings))
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                EtaSwitchRow(
                    title = stringResource(R.string.mcp_enable_server),
                    summary = server.url,
                    checked = server.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(server.copy(enabled = enabled))
                        }
                    },
                )
                McpClickableRow(
                    title = stringResource(R.string.mcp_update_token),
                    summary = stringResource(
                        if (server.authorizationType == McpAuthorizationType.BEARER) {
                            R.string.mcp_token_configured
                        } else {
                            R.string.mcp_no_authentication
                        }
                    ),
                    onClick = { showToken = true },
                )
            }
        }
        item(key = "tools") {
            EtaSectionTitle(text = stringResource(R.string.mcp_tools_count, server.tools.size))
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                if (server.tools.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_no_tools),
                        summary = stringResource(R.string.mcp_refresh_tools_hint),
                    )
                } else {
                    server.tools.forEach { tool ->
                        val checked = tool.name in server.enabledToolNames
                        EtaSwitchRow(
                            title = tool.title.ifBlank { tool.name },
                            summary = toolSummary(tool),
                            checked = checked,
                            onCheckedChange = { enabled ->
                                if (enabled && tool.readOnlyHint != true) {
                                    pendingRiskyTool = tool
                                } else {
                                    scope.launch(Dispatchers.IO) {
                                        McpServerRepository.setToolEnabled(serverId, tool.name, enabled)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        item(key = "delete") {
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                McpClickableRow(
                    title = stringResource(R.string.mcp_delete_server),
                    summary = stringResource(R.string.mcp_delete_server_summary),
                    onClick = { showDelete = true },
                )
            }
        }
    }

    pendingRiskyTool?.let { tool ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.mcp_enable_risky_tool),
            summary = stringResource(R.string.mcp_enable_risky_tool_summary, tool.name, server?.name.orEmpty()),
            onDismissRequest = { pendingRiskyTool = null },
        ) {
            EtaDialogActions(
                confirmText = stringResource(R.string.mcp_enable_tool),
                destructive = true,
                onCancel = { pendingRiskyTool = null },
                onConfirm = {
                    scope.launch(Dispatchers.IO) {
                        McpServerRepository.setToolEnabled(serverId, tool.name, true)
                    }
                    pendingRiskyTool = null
                },
            )
        }
    }

    EtaDialog(
        show = showToken,
        title = stringResource(R.string.mcp_update_token),
        summary = stringResource(R.string.mcp_update_token_summary),
        onDismissRequest = { showToken = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            McpTextField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(R.string.mcp_bearer_token),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            EtaDialogActions(
                confirmText = stringResource(R.string.action_save),
                onCancel = { showToken = false },
                onConfirm = {
                    server?.let { current ->
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(
                                current.copy(
                                    authorizationType = if (token.isBlank()) {
                                        McpAuthorizationType.NONE
                                    } else {
                                        McpAuthorizationType.BEARER
                                    },
                                ),
                                bearerToken = token,
                            )
                        }
                        token = ""
                        showToken = false
                    }
                },
            )
        }
    }

    EtaDialog(
        show = showDelete,
        title = stringResource(R.string.mcp_delete_server),
        summary = stringResource(R.string.mcp_delete_confirm, server?.name.orEmpty()),
        onDismissRequest = { showDelete = false },
    ) {
        EtaDialogActions(
            confirmText = stringResource(R.string.ui_delete_3755f5),
            destructive = true,
            onCancel = { showDelete = false },
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { McpServerRepository.delete(serverId) }
                    onBack()
                }
            },
        )
    }
}

@Composable
private fun toolSummary(tool: McpToolDefinition): String = when {
    tool.readOnlyHint == true -> tool.description.ifBlank { stringResource(R.string.mcp_read_only_tool) }
    else -> tool.description.ifBlank { stringResource(R.string.mcp_may_modify_data) }
}

/** 顶栏动作：M3 用官方 [M3IconButton]，MIUI X 保持原有图标按钮。 */
@Composable
private fun McpActionIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (isMaterial3Theme()) {
        M3IconButton(onClick = onClick, enabled = enabled) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    } else {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}

/** 可点击设置行：M3 用官方 [ListItem]（onClick 重载自带涟漪与语义），MIUI X 保持原有 [ArrowPreference]。 */
@Composable
private fun McpClickableRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    if (isMaterial3Theme()) {
        ListItem(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            content = { M3Text(text = title, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = {
                M3Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    } else {
        ArrowPreference(
            title = title,
            summary = summary,
            onClick = onClick,
        )
    }
}

/** 纯提示行：M3 用官方 [ListItem]，MIUI X 保持原有 [BasicComponent]。 */
@Composable
private fun McpNoticeRow(title: String) {
    if (isMaterial3Theme()) {
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            content = { M3Text(text = title, style = MaterialTheme.typography.bodyLarge) },
        )
    } else {
        BasicComponent(title = title)
    }
}

/** 输入框：M3 用官方 [OutlinedTextField]，MIUI X 保持原有 [TextField]。 */
@Composable
private fun McpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    if (isMaterial3Theme()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { M3Text(text = label) },
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
        )
    } else {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
        )
    }
}
