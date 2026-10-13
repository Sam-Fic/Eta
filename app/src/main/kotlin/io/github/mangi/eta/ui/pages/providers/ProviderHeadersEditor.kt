package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.SessionGatewayRule
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.haptics.TouchHaptics

internal fun LazyListScope.providerSessionGateway(
    modelPattern: String,
    pathPattern: String,
    retention: String,
    keySource: String,
    keyField: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onChange: (model: String, path: String, retention: String, source: String, field: String) -> Unit,
) {
    item(key = "session_gateway") {
        ProviderSection(title = "会话 ID") {
            ProviderExpandableRow(
                title = "网关",
                summary = "${modelPattern.ifBlank { SessionGatewayRule.DEFAULT_MODEL }} · ${keyField.ifBlank { SessionGatewayRule.DEFAULT_FIELD }}",
                expanded = expanded,
                onExpandedChange = onExpandedChange,
            )
            if (expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProviderField(
                        value = modelPattern,
                        onValueChange = { onChange(it, pathPattern, retention, keySource, keyField) },
                        label = "模型正则（每一行一个）",
                    )
                    ProviderField(
                        value = pathPattern,
                        onValueChange = { onChange(modelPattern, it, retention, keySource, keyField) },
                        label = "路径正则（每一行一个）",
                    )
                    ProviderField(
                        value = retention,
                        onValueChange = { onChange(modelPattern, pathPattern, it, keySource, keyField) },
                        label = "会话保持",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProviderField(
                            value = keySource,
                            onValueChange = { onChange(modelPattern, pathPattern, retention, it, keyField) },
                            label = "Key 来源",
                            modifier = Modifier.weight(1f),
                        )
                        ProviderField(
                            value = keyField,
                            onValueChange = { onChange(modelPattern, pathPattern, retention, keySource, it) },
                            label = "字段",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

internal fun LazyListScope.providerHeadersEditor(
    headers: List<ProviderHeaderDraft>,
    onHeadersChange: (List<ProviderHeaderDraft>) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    // 请求头数量很少且必须收进同一张卡片，折叠/展开态整组重排，不拆成独立 Lazy 条目。
    item(key = "custom_headers") {
        ProviderSection(title = "自定义请求头") {
            ProviderExpandableRow(
                title = if (headers.isEmpty()) "未设置" else "已设置 ${headers.size} 项",
                summary = "可覆盖 User-Agent；认证与传输请求头由系统管理。",
                expanded = expanded,
                onExpandedChange = onExpandedChange,
            )
            if (expanded) {
                headers.forEach { row ->
                    ProviderDivider()
                    ProviderHeaderRow(
                        row = row,
                        onNameChange = { value ->
                            onHeadersChange(headers.map {
                                if (it.id == row.id) it.copy(header = it.header.copy(name = value)) else it
                            })
                        },
                        onValueChange = { value ->
                            onHeadersChange(headers.map {
                                if (it.id == row.id) it.copy(header = it.header.copy(value = value)) else it
                            })
                        },
                        onRemove = { onHeadersChange(headers.filterNot { it.id == row.id }) },
                    )
                }
                ProviderDivider()
                ProviderAddRow(text = "添加请求头") {
                    onHeadersChange(headers + ProviderHeaderDraft())
                }
            }
        }
    }
}

@Composable
private fun ProviderHeaderRow(
    row: ProviderHeaderDraft,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var visible by remember(row.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderField(
                value = row.header.name,
                onValueChange = onNameChange,
                label = "名称",
            )
            ProviderField(
                value = row.header.value,
                onValueChange = onValueChange,
                label = "值",
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    ProviderIconAction(
                        icon = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = if (visible) {
                            context.getString(R.string.page_hide_bb0e7e)
                        } else {
                            context.getString(R.string.page_show_71b677)
                        },
                        onClick = { visible = !visible },
                    )
                },
            )
        }
        ProviderIconAction(
            icon = Icons.Rounded.Delete,
            contentDescription = context.getString(R.string.ui_delete_3755f5),
            onClick = {
                TouchHaptics.click(view)
                onRemove()
            },
        )
    }
}
