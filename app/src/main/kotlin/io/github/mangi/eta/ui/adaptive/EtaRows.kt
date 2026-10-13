package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.HapticBasicComponent
import io.github.mangi.eta.ui.components.SwitchPreference
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

private val DefaultRowPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
private val DenseRowPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)

/**
 * 带开关的设置行。
 *
 * @param dense 位于可展开项内部时使用更紧凑的内边距。
 * @param contentPadding 覆盖默认内边距（两种主题都会生效）。
 */
@Composable
fun EtaSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    dense: Boolean = false,
    contentPadding: PaddingValues? = null,
) {
    if (isMaterial3Theme()) {
        // 官方可交互 ListItem：自带涟漪、语义、长按与按压形变。
        ListItem(
            onClick = { onCheckedChange(!checked) },
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            content = {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
            },
            supportingContent = summary?.let {
                {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                EtaSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                )
            },
            contentPadding = contentPadding ?: if (dense) DenseRowPadding else DefaultRowPadding,
        )
    } else {
        SwitchPreference(
            title = title,
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            summary = summary,
            enabled = enabled,
            insideMargin = contentPadding
                ?: if (dense) PaddingValues(0.dp) else BasicComponentDefaults.InsideMargin,
        )
    }
}

/**
 * 可点击 / 可展开的设置行。
 *
 * @param showChevron 是否在尾部显示箭头（展开型条目通常自带尾部图标，可置 false）。
 * @param contentPadding 覆盖默认内边距（两种主题都会生效）。
 */
@Composable
fun EtaClickableRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    endActions: (@Composable RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    contentPadding: PaddingValues? = null,
) {
    if (isMaterial3Theme()) {
        val trailingContent: (@Composable () -> Unit)? = if (endActions != null || showChevron) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    endActions?.invoke(this)
                    if (showChevron) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            null
        }
        Column(modifier = modifier.fillMaxWidth()) {
            if (onClick != null) {
                ListItem(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    content = {
                        Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    },
                    supportingContent = summary?.let {
                        {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingContent = trailingContent,
                    contentPadding = contentPadding ?: ListItemDefaults.ContentPadding,
                )
            } else {
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    },
                    supportingContent = summary?.let {
                        {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingContent = trailingContent,
                    contentPadding = contentPadding ?: ListItemDefaults.ContentPadding,
                )
            }
            bottomAction?.invoke()
        }
    } else {
        val insideMargin = contentPadding ?: BasicComponentDefaults.InsideMargin
        if (showChevron) {
            ArrowPreference(
                title = title,
                modifier = modifier,
                summary = summary,
                endActions = { endActions?.invoke(this) },
                bottomAction = bottomAction,
                insideMargin = insideMargin,
                onClick = onClick,
                holdDownState = holdDownState,
                enabled = enabled,
            )
        } else {
            HapticBasicComponent(
                title = title,
                modifier = modifier,
                summary = summary,
                endActions = { endActions?.invoke(this) },
                bottomAction = bottomAction,
                insideMargin = insideMargin,
                onClick = onClick,
                holdDownState = holdDownState,
                enabled = enabled,
            )
        }
    }
}

/**
 * 下拉选择设置行。[summary] 通常传当前选中项的文案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtaDropdownRow(
    title: String,
    summary: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues? = null,
) {
    if (isMaterial3Theme()) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it },
            modifier = modifier.fillMaxWidth(),
        ) {
            ListItem(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
                enabled = enabled,
                content = {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                },
                supportingContent = {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                contentPadding = contentPadding ?: ListItemDefaults.ContentPadding,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                items.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(text = label) },
                        onClick = {
                            expanded = false
                            onSelectedIndexChange(index)
                        },
                    )
                }
            }
        }
    } else {
        OverlayDropdownPreference(
            title = title,
            summary = summary,
            items = items,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            enabled = enabled,
        )
    }
}
