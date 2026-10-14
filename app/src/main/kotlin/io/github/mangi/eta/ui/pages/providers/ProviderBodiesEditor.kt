package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text

internal fun LazyListScope.providerBodiesEditor(
    bodies: List<ProviderBodyDraft>,
    onBodiesChange: (List<ProviderBodyDraft>) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    item(key = "custom_bodies") {
        // KernelSU 式分段列：展开行独立成段（首段），请求体编辑体、示例提示与「添加」行
        // 落在同一个隐藏段里（visible = expanded，折叠时以 spring 动画收起）。
        EtaSegmentedColumn(
            modifier = EditorSectionPadding,
            title = stringResource(R.string.pbe_custom_bodies),
        ) {
            item {
                ProviderExpandableRow(
                    title = if (bodies.isEmpty()) stringResource(R.string.prov_not_set) else stringResource(R.string.prov_set_count, bodies.size),
                    summary = stringResource(R.string.pbe_bodies_summary),
                    expanded = expanded,
                    onExpandedChange = onExpandedChange,
                )
            }
            item(visible = expanded) {
                EtaSegmentedItemContainer {
                    Column {
                        bodies.forEach { row ->
                            key(row.id) {
                                ProviderBodyRow(
                                    row = row,
                                    onKeyChange = { value ->
                                        onBodiesChange(bodies.map { if (it.id == row.id) it.copy(key = value) else it })
                                    },
                                    onValueChange = { value ->
                                        onBodiesChange(bodies.map { if (it.id == row.id) it.copy(valueJson = value) else it })
                                    },
                                    onRemove = { onBodiesChange(bodies.filterNot { it.id == row.id }) },
                                )
                            }
                        }
                        val error = parseProviderBodies(bodies).exceptionOrNull()?.message
                        val hint = error ?: stringResource(R.string.pbe_hint_example)
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            if (isMaterial3Theme()) {
                                M3Text(
                                    text = hint,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = hint,
                                    style = MiuixTheme.textStyles.body2,
                                )
                            }
                        }
                        ProviderDivider()
                        ProviderAddRow(text = stringResource(R.string.pbe_add_body_field)) {
                            onBodiesChange(bodies + ProviderBodyDraft())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderBodyRow(
    row: ProviderBodyDraft,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderField(
                value = row.key,
                onValueChange = onKeyChange,
                label = stringResource(R.string.pbe_field_name),
            )
            ProviderField(
                value = row.valueJson,
                onValueChange = onValueChange,
                label = stringResource(R.string.pbe_value_json),
                singleLine = false,
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
