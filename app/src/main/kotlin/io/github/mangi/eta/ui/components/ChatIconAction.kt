package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme

/**
 * Transparent chat icon action.
 *
 * miuix branch keeps the original layout: miuix builds an offscreen squircle mask for every
 * IconButton even when the background is transparent, and that mask only ever carved pixels which
 * were never visible while each node still paid an extra offscreen composite per frame.
 *
 * Material 3 Expressive branch uses the official IconButton: the container comes from
 * IconButtonDefaults.extraSmallContainerSize() (32dp) and shape, colors, ripple and the minimum
 * interactive size are all supplied by the official component. minWidth / minHeight only apply to
 * the miuix branch; they are kept so caller signatures stay untouched.
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
    if (isMaterial3Theme()) {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(IconButtonDefaults.extraSmallContainerSize()),
            enabled = enabled,
        ) {
            Box(contentAlignment = Alignment.Center, content = content)
        }
        return
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
