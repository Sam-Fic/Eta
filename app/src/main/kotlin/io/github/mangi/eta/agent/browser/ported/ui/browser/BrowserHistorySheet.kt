// Adapted from OpenMinis/OpenMinis @ 4ef29002e88db1e20e462ec2ff46916e8a7dcb45.
// SPDX-License-Identifier: GPL-3.0-only
// Local/private integration; see third_party/openminis-browser/NOTICE.md.
package io.github.mangi.eta.agent.browser.ported.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.ported.browser.BrowserHistoryStore
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史面板（M3 Expressive）：官方 [ModalBottomSheet] + [TopAppBar] 页头，
 * 列表走项目分段列表套件（[EtaSegmentedColumn] / [EtaSegmentedRow]），
 * 按天分组，组标题用官方 `titleSmallEmphasized` + primary。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserHistorySheet(
    historyStore: BrowserHistoryStore,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Expanded,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    val resultsLabel = stringResource(R.string.om_browser_history_results)

    val entries = remember(searchQuery, historyStore.getEntries().size) {
        if (searchQuery.isBlank()) historyStore.groupedByDay()
        else mapOf(resultsLabel to historyStore.search(searchQuery))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BrowserSheetTopBar(
                title = stringResource(R.string.om_browser_history_title),
                onDismiss = onDismiss,
                actions = {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        enabled = historyStore.getEntries().isNotEmpty(),
                    ) {
                        Text(
                            text = stringResource(R.string.om_browser_history_clear),
                            color = if (historyStore.getEntries().isNotEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )

            // 搜索框：官方 OutlinedTextField（前导搜索图标，去掉自绘圆角与字阶覆盖）。
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.om_browser_history_search)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (entries.values.flatten().isEmpty()) {
                BrowserSheetEmptyState(
                    title = stringResource(R.string.om_browser_history_empty_title),
                    summary = stringResource(R.string.om_browser_history_empty_subtitle),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                ) {
                    for ((dayLabel, dayEntries) in entries) {
                        if (dayEntries.isEmpty()) continue
                        item(key = "group_$dayLabel") {
                            EtaSegmentedColumn(
                                title = dayLabel,
                                modifier = Modifier.padding(
                                    start = EtaGroupHorizontalPadding,
                                    end = EtaGroupHorizontalPadding,
                                    bottom = EtaSegmentedGroupSpacing,
                                ),
                            ) {
                                dayEntries.forEach { entry ->
                                    item(key = entry.id) {
                                        EtaSegmentedRow(
                                            onClick = { onNavigate(entry.url) },
                                            modifier = Modifier.fillMaxWidth(),
                                            leadingContent = {
                                                Icon(
                                                    imageVector = Icons.Rounded.History,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                            content = {
                                                Text(
                                                    text = entry.title.ifEmpty { entry.domain },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            supportingContent = {
                                                Text(
                                                    text = entry.url,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            trailingContent = {
                                                Text(
                                                    text = SimpleDateFormat("HH:mm", LocalConfiguration.current.locales[0])
                                                        .format(Date(entry.timestamp)),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item(key = "eta_bottom_spacer") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.om_browser_history_clear_dialog_title),
            summary = stringResource(R.string.om_browser_history_clear_dialog_message),
            onDismissRequest = { showClearConfirm = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.om_browser_history_clear),
                    destructive = true,
                    onCancel = { showClearConfirm = false },
                    onConfirm = {
                        historyStore.clear()
                        showClearConfirm = false
                    },
                )
            },
        ) {
        }
    }
}
