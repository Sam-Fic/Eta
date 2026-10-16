package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.model.MessageSearchHit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text

@Composable
internal fun SearchHistoryDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onSearch: suspend (String) -> List<MessageSearchHit>,
    onOpenHit: (MessageSearchHit) -> Unit,
    showConversationTitle: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MessageSearchHit>>(emptyList()) }

    LaunchedEffect(show) {
        if (!show) {
            query = ""
            results = emptyList()
        }
    }

    LaunchedEffect(show, query) {
        if (!show) return@LaunchedEffect
        results = emptyList()
        if (query.isBlank()) return@LaunchedEffect
        // The suspend callback captures UI state before doing cancellable background work.
        results = onSearch(query)
    }

    EtaDialog(
        show = show,
        title = stringResource(R.string.action_search_history),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { M3Text(text = stringResource(R.string.search_history_hint)) },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            when {
                query.isBlank() -> {
                    EtaText(
                        text = stringResource(R.string.search_history_empty),
                        style = EtaTextStyle.Secondary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                results.isEmpty() -> {
                    EtaText(
                        text = stringResource(R.string.search_history_no_results),
                        style = EtaTextStyle.Secondary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(
                            items = results,
                            key = { "${it.conversationId}:${it.messageId}" },
                        ) { hit ->
                            SearchHitRow(
                                hit = hit,
                                showConversationTitle = showConversationTitle,
                                onClick = {
                                    onOpenHit(hit)
                                    onDismiss()
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** 搜索结果行：官方 [ListItem]（onClick 重载自带涟漪与语义）。 */
@Composable
private fun SearchHitRow(
    hit: MessageSearchHit,
    showConversationTitle: Boolean,
    onClick: () -> Unit,
) {
    val detail = "${hit.roleLabel} · ${hit.snippet}"
    ListItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        content = {
            M3Text(
                text = if (showConversationTitle) hit.conversationTitle else detail,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (showConversationTitle) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (showConversationTitle) {
            {
                M3Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
    )
}
