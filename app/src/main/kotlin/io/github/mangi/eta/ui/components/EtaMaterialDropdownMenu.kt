package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Material 3 溢出菜单。
 *
 * 形状、容器色、阴影层级与菜单项字阶/高度全部沿用官方 `MenuDefaults`：
 * `surfaceContainer` 容器 + `shapes.extraSmall`（4dp）圆角 + Level2 阴影，
 * 菜单项 48dp、`labelLarge`。这里只额外限制宽度，避免超长文本撑破屏幕。
 */
@Composable
internal fun EtaMaterialDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 112.dp, max = 280.dp),
        content = content,
    )
}

/** 官方菜单项：48dp 行高、`labelLarge` 字阶、内容色由 `MenuDefaults` 决定。 */
@Composable
internal fun EtaMaterialDropdownMenuItem(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text = text) },
        enabled = enabled,
        onClick = onClick,
    )
}