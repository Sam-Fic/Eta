package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ToolChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (isMaterial3Theme()) {
        // M3 Expressive：只读标签没有对应的官方 Chip（AssistChip / SuggestionChip 都要求 onClick），
        // 因此用官方 Surface 承载容器，形状取官方 shapes.small、内边距与高度取官方
        // AssistChipDefaults.ContentPadding / Height，颜色只用 surfaceContainerHigh 与
        // onSurfaceVariant 两个官方角色，不写死任何数值。
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = text,
                modifier = Modifier
                    .defaultMinSize(minHeight = AssistChipDefaults.Height)
                    .padding(AssistChipDefaults.ContentPadding),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
