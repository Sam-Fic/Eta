package io.github.mangi.eta.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.pet.WhaleMaidController
import io.github.mangi.eta.agent.pet.WhaleMaidSnapshot
import io.github.mangi.eta.agent.pet.WhaleMaidStore
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.components.ListEmptyState

/**
 * 发言手账二级页：女仆历次发言的只读列表（最近 20 条，`WhaleMaidStore` 侧截断）。
 *
 * 原实现只在点击宠物的悬浮橱柜里能看到手账，这里把它提到设置侧，
 * 列表末尾是清空入口（破坏性操作走确认对话框）。
 */
@Composable
internal fun WhaleMaidMemoriesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(WhaleMaidStore.snapshot(context)) }
    var confirmingClear by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener: (WhaleMaidSnapshot) -> Unit = { snapshot = it }
        WhaleMaidStore.addListener(listener)
        onDispose { WhaleMaidStore.removeListener(listener) }
    }

    EtaScaffoldPage(title = stringResource(R.string.whale_maid_memories), onBack = onBack) {
        if (snapshot.memories.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.whale_maid_empty),
                    modifier = Modifier.padding(bottom = EtaSegmentedGroupSpacing),
                )
            }
        } else {
            item(key = "memories") {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                ) {
                    snapshot.memories.forEachIndexed { index, memory ->
                        item(key = index) {
                            EtaClickableRow(
                                title = memory.speech,
                                // 相对时间由系统本地化，避免再引入一套时间格式串。
                                summary = DateUtils.getRelativeTimeSpanString(memory.atMillis).toString(),
                                onClick = null,
                                showChevron = false,
                                leading = { Icon(Icons.Rounded.FormatQuote, null) },
                            )
                        }
                    }
                    item(key = "clear") {
                        EtaClickableRow(
                            title = stringResource(R.string.whale_maid_clear),
                            onClick = { confirmingClear = true },
                            showChevron = false,
                            leading = { Icon(Icons.Rounded.DeleteOutline, null) },
                        )
                    }
                }
            }
        }
    }

    EtaDialog(
        show = confirmingClear,
        title = stringResource(R.string.whale_maid_clear),
        summary = stringResource(R.string.whale_maid_clear_summary),
        icon = Icons.Rounded.DeleteOutline,
        onDismissRequest = { confirmingClear = false },
    ) {
        EtaDialogActions(
            confirmText = stringResource(R.string.whale_maid_clear),
            cancelText = stringResource(R.string.action_cancel),
            destructive = true,
            onCancel = { confirmingClear = false },
            onConfirm = {
                WhaleMaidController.clearMemories(context)
                confirmingClear = false
            },
        )
    }
}
