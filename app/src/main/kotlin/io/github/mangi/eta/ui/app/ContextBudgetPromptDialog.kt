package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults as M3CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.Text as M3Text

@Composable
internal fun ContextBudgetPromptDialog(
    reason: String,
    onDismiss: () -> Unit,
    onCompactThisRun: () -> Unit,
    onStop: () -> Unit,
) {
    val view = LocalView.current
    // M3：正文说明走官方 AlertDialog 的 summary 槽；MIUI X：仍由 WindowDialog 渲染 summary。
    EtaDialog(
        show = true,
        title = stringResource(R.string.context_budget_title),
        summary = stringResource(R.string.context_budget_body),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (reason.isNotBlank()) {
                ContextBudgetReasonCard(reason = reason.trim())
            }
            if (isMaterial3Theme()) {
                M3Text(
                    text = stringResource(R.string.context_budget_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.context_budget_hint),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            ContextBudgetChoices(
                onCompact = {
                    TouchHaptics.click(view)
                    onCompactThisRun()
                },
                onPause = {
                    TouchHaptics.click(view)
                    onDismiss()
                },
                onStop = {
                    TouchHaptics.click(view)
                    onStop()
                },
            )
        }
    }
}

/** 三个处置选项；颜色只取当前主题族的角色色。 */
@Composable
private fun ContextBudgetChoices(
    onCompact: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isMaterial3Theme()) {
            ContextBudgetChoice(
                title = stringResource(R.string.context_budget_compact),
                summary = stringResource(R.string.context_budget_compact_summary),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = onCompact,
            )
            ContextBudgetChoice(
                title = stringResource(R.string.context_budget_pause),
                summary = stringResource(R.string.context_budget_pause_summary),
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = MaterialTheme.colorScheme.onSurface,
                onClick = onPause,
            )
            ContextBudgetChoice(
                title = stringResource(R.string.context_budget_stop),
                summary = stringResource(R.string.context_budget_stop_summary),
                container = MaterialTheme.colorScheme.error,
                content = MaterialTheme.colorScheme.onError,
                onClick = onStop,
            )
        } else {
            ContextBudgetChoice(
                title = stringResource(R.string.context_budget_compact),
                summary = stringResource(R.string.context_budget_compact_summary),
                container = MiuixTheme.colorScheme.primary,
                content = MiuixTheme.colorScheme.onPrimary,
                onClick = onCompact,
            )
            ContextBudgetChoice(
                title = stringResource(R.string.context_budget_pause),
                summary = stringResource(R.string.context_budget_pause_summary),
                container = MiuixTheme.colorScheme.surfaceContainerHigh,
                content = MiuixTheme.colorScheme.onSurface,
                onClick = onPause,
            )
            ContextBudgetChoice(
                title = stringResource(R.string.context_budget_stop),
                summary = stringResource(R.string.context_budget_stop_summary),
                container = MiuixTheme.colorScheme.error,
                content = MiuixTheme.colorScheme.onError,
                onClick = onStop,
            )
        }
    }
}

/** 触发原因的高层级容器：M3 用官方 [M3Card]，MIUI X 保持原有填充卡片。 */
@Composable
private fun ContextBudgetReasonCard(reason: String) {
    if (isMaterial3Theme()) {
        M3Card(modifier = Modifier.fillMaxWidth()) {
            M3Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        return
    }
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.defaultColors(
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurfaceVariantSummary,
        ),
    ) {
        Text(
            text = reason,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

/** 可点选项：M3 用官方 [M3Card] 的 onClick 重载（自带涟漪与按压形变），MIUI X 保持原有卡片。 */
@Composable
private fun ContextBudgetChoice(
    title: String,
    summary: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    if (isMaterial3Theme()) {
        M3Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = M3CardDefaults.cardColors(
                containerColor = container,
                contentColor = content,
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                M3Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = content,
                )
                M3Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.78f),
                )
            }
        }
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.defaultColors(
            color = container,
            contentColor = content,
        ),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = content,
            )
            Text(
                text = summary,
                style = MiuixTheme.textStyles.footnote2,
                color = content.copy(alpha = 0.78f),
            )
        }
    }
}
