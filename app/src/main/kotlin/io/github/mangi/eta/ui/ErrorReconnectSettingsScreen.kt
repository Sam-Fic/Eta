package io.github.mangi.eta.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRadioRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedScope
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.etaOnSurfaceVariant
import io.github.mangi.eta.ui.adaptive.etaPrimary
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

@Composable
internal fun ErrorReconnectSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val policyFlow = remember { SettingsDataStore.errorReconnectPolicyFlow().map { it as ErrorReconnectPolicy? } }
    val loadedPolicy by policyFlow.collectAsState(initial = null)
    val policy = loadedPolicy ?: ErrorReconnectPolicy.DEFAULT
    var saving by remember { mutableStateOf(false) }

    EtaScaffoldPage(
        title = stringResource(R.string.error_reconnect_title),
        onBack = onBack,
    ) {
        item(key = "error_reconnect_policy") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.error_reconnect_duration),
            ) {
                ErrorReconnectPolicyOptions(
                    selectedPolicy = policy,
                    enabled = loadedPolicy != null && !saving,
                    onSelect = { selected ->
                        if (loadedPolicy != null && !saving && selected != loadedPolicy) {
                            saving = true
                            scope.launch {
                                try {
                                    SettingsDataStore.setErrorReconnectPolicy(selected)
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    Toast.makeText(
                                        context,
                                        R.string.error_reconnect_save_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * 重连策略选项组：注册进分段列，每个选项独立一条目，自行拿分段形状
 * （KernelSU `SegmentedRadioItem` 式的单选列表）。
 *
 * Material 3：前导 RadioButton + 标题 + 摘要说明，整行可选；
 * MIUI X：保留原有的「整行 selectable + 尾部 RadioButton」结构。
 *
 * A single global policy: no provider/model selector or per-model configuration.
 */
internal fun EtaSegmentedScope.ErrorReconnectPolicyOptions(
    selectedPolicy: ErrorReconnectPolicy,
    onSelect: (ErrorReconnectPolicy) -> Unit,
    enabled: Boolean = true,
) {
    ErrorReconnectPolicy.entries.forEach { policy ->
        item(key = policy.name) {
            if (isMaterial3Theme()) {
                val view = LocalView.current
                EtaSegmentedRadioRow(
                    title = stringResource(errorReconnectPolicyLabel(policy)),
                    summary = stringResource(errorReconnectPolicyHint(policy)),
                    selected = selectedPolicy == policy,
                    enabled = enabled,
                    onClick = {
                        TouchHaptics.click(view)
                        onSelect(policy)
                    },
                )
            } else {
                ErrorReconnectPolicyMiuixRow(
                    policy = policy,
                    selectedPolicy = selectedPolicy,
                    enabled = enabled,
                    onSelect = onSelect,
                )
            }
        }
    }
}

/** MIUI X 主题族的单选行：整行 selectable，尾部 RadioButton，与原版观感一致。 */
@Composable
private fun ErrorReconnectPolicyMiuixRow(
    policy: ErrorReconnectPolicy,
    selectedPolicy: ErrorReconnectPolicy,
    enabled: Boolean,
    onSelect: (ErrorReconnectPolicy) -> Unit,
) {
    val view = LocalView.current
    Column(modifier = Modifier.selectableGroup()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(
                    selected = selectedPolicy == policy,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = {
                        TouchHaptics.click(view)
                        onSelect(policy)
                    },
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                EtaText(text = stringResource(errorReconnectPolicyLabel(policy)))
                EtaText(
                    text = stringResource(errorReconnectPolicyHint(policy)),
                    style = EtaTextStyle.Footnote,
                )
            }
            RadioButton(
                selected = selectedPolicy == policy,
                enabled = enabled,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = etaPrimary(),
                    unselectedColor = etaOnSurfaceVariant(),
                ),
            )
        }
    }
}

@StringRes
internal fun errorReconnectPolicyLabel(policy: ErrorReconnectPolicy): Int = when (policy) {
    ErrorReconnectPolicy.NONE -> R.string.error_reconnect_none
    ErrorReconnectPolicy.WINDOW_30S -> R.string.error_reconnect_window_30s
    ErrorReconnectPolicy.WINDOW_1M -> R.string.error_reconnect_window_1m
    ErrorReconnectPolicy.WINDOW_5M -> R.string.error_reconnect_window_5m
    ErrorReconnectPolicy.CONTINUOUS -> R.string.error_reconnect_continuous
}

@StringRes
private fun errorReconnectPolicyHint(policy: ErrorReconnectPolicy): Int = when (policy) {
    ErrorReconnectPolicy.NONE -> R.string.error_reconnect_hint_none
    ErrorReconnectPolicy.WINDOW_30S -> R.string.error_reconnect_hint_30s
    ErrorReconnectPolicy.WINDOW_1M -> R.string.error_reconnect_hint_1m
    ErrorReconnectPolicy.WINDOW_5M -> R.string.error_reconnect_hint_5m
    ErrorReconnectPolicy.CONTINUOUS -> R.string.error_reconnect_hint_continuous
}
