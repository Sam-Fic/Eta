package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Transparent chat icon action.
 *
 * Uses the official IconButton: the container comes from
 * IconButtonDefaults.extraSmallContainerSize() (32dp) and shape, colors, ripple and the minimum
 * interactive size are all supplied by the official component. minWidth / minHeight are kept so
 * caller signatures stay untouched.
 */
@Composable
internal fun ChatIconAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minWidth: Dp = 30.dp,
    minHeight: Dp = 30.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(IconButtonDefaults.extraSmallContainerSize()),
        enabled = enabled,
    ) {
        Box(contentAlignment = Alignment.Center, content = content)
    }
}
