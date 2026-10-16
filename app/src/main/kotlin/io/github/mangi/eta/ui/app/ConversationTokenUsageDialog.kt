package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.formatTokenCount
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun ConversationTokenUsageDialog(
    show: Boolean,
    usage: ConversationTokenUsageUi,
    onDismiss: () -> Unit,
) {
    val content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            M3Text(
                text = stringResource(R.string.action_token_usage),
                style = MaterialTheme.typography.titleSmall,
            )
            if (!usage.hasUsage) {
                M3Text(
                    text = stringResource(R.string.token_usage_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                UsageRow(
                    label = stringResource(R.string.token_usage_total),
                    value = formatTokenCount(usage.totalTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_page_fresh_input_tokens),
                    value = formatTokenCount(usage.freshInputTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokenCount(usage.outputTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = formatTokenCount(usage.cachedTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_page_cache_creation_tokens),
                    value = formatTokenCount(usage.cacheCreationTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_model_cache_hit_rate),
                    value = formatCachePercent(usage.cachePercent),
                )
            }
        }
    }
    // 官方菜单容器：内容尺寸交给官方默认，最小宽度 250dp。
    DropdownMenu(
        expanded = show,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 250.dp),
    ) {
        content()
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        M3Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        M3Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatCachePercent(percent: Double?): String {
    if (percent == null) return "—"
    val format = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return format.format(percent) + "%"
}
