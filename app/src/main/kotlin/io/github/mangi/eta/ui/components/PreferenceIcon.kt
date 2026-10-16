package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme

@Composable
internal fun PreferenceIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // 前导图标用 onSurfaceVariant（官方 ListItem leadingContent 惯例）。
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.padding(end = 8.dp).size(24.dp),
        tint = tint,
    )
}
