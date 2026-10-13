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
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.etaOnSurfaceVariant
import io.github.mangi.eta.ui.adaptive.etaPrimary
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
            EtaSectionTitle(stringResource(R.string.error_reconnect_duration))
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
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

/** A single global policy: no provider/model selector or per-model configuration. */
@Composable
internal fun ErrorReconnectPolicyOptions(
    selectedPolicy: ErrorReconnectPolicy,
    onSelect: (ErrorReconnectPolicy) -> Unit,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    Column(modifier = Modifier.selectableGroup()) {
        ErrorReconnectPolicy.entries.forEach { policy ->
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
                EtaText(
                    text = stringResource(errorReconnectPolicyLabel(policy)),
                    modifier = Modifier.weight(1f),
                )
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
}

@StringRes
internal fun errorReconnectPolicyLabel(policy: ErrorReconnectPolicy): Int = when (policy) {
    ErrorReconnectPolicy.NONE -> R.string.error_reconnect_none
    ErrorReconnectPolicy.WINDOW_30S -> R.string.error_reconnect_window_30s
    ErrorReconnectPolicy.WINDOW_1M -> R.string.error_reconnect_window_1m
    ErrorReconnectPolicy.WINDOW_5M -> R.string.error_reconnect_window_5m
    ErrorReconnectPolicy.CONTINUOUS -> R.string.error_reconnect_continuous
}
