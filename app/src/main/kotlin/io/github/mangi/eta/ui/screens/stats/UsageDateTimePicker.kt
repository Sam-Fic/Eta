package io.github.mangi.eta.ui.screens.stats

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * 统计页的日期选择弹窗。
 *
 * 使用官方 `DatePickerDialog` + `DatePicker` + `rememberDatePickerState`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsageDateTimePickerDialog(
    show: Boolean,
    title: String,
    current: UsageTimeBound,
    endOfBound: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (UsageTimeBound) -> Unit,
    onClear: () -> Unit,
) {
    if (!show) return
    val initial = remember(current, endOfBound) { current.date ?: LocalDate.now() }
    val view = LocalView.current

    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.toEpochDay() * MILLIS_PER_DAY,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onConfirm(UsageTimeBound.from(date))
                    }
                    onDismiss()
                },
                enabled = pickerState.selectedDateMillis != null,
            ) {
                Text(text = stringResource(R.string.stats_model_filter_set))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        TouchHaptics.click(view)
                        onClear()
                    },
                ) {
                    Text(text = stringResource(R.string.action_clear))
                }
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        },
    ) {
        DatePicker(
            state = pickerState,
            // 自定义 title 会覆盖 DatePicker 默认标题，需手动补回默认标题的
            // 内边距（start 24 / end 12 / top 16），否则文字贴住弹窗左上圆角被裁切。
            title = {
                Text(
                    text = title,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}
