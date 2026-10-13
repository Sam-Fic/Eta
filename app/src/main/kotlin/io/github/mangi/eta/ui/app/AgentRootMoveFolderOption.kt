package io.github.mangi.eta.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text as M3Text
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MoveFolderOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (isMaterial3Theme()) {
        // Material 3 Expressive：官方单选 ListItem 承担容器、形状、选中配色、涟漪与
        // 单选语义（Role.RadioButton），行高与内容内边距全部走 ListItemDefaults.ContentPadding，
        // 不再是「Row + clip + 硬编码圆角 + clickable」的手搓可点区域。
        ListItem(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            leadingContent = {
                Icon(imageVector = Icons.Rounded.Folder, contentDescription = null)
            },
        ) {
            M3Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    // MIUI X 主题族：保持原有绘制与命中区不变。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            tint = if (selected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
        )
        Text(
            text = label,
            color = if (selected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}