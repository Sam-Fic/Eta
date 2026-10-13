package io.github.mangi.eta.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.agent.pet.WhaleMaidController
import io.github.mangi.eta.agent.pet.WhaleMaidStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.etaOnSurfaceVariant
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import androidx.compose.material3.Icon

@Composable
internal fun WhaleMaidSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val initial = remember { WhaleMaidStore.snapshot(context) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var expanded by remember { mutableStateOf(false) }
    var workSpeech by remember { mutableStateOf(initial.workSpeechEnabled) }
    var globalVisible by remember { mutableStateOf(initial.globalVisible) }
    var selection by remember { mutableStateOf(WhaleMaidStore.modelSelection(context)) }
    var picker by remember { mutableStateOf(false) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val models = remember(providers, selection) {
        val all = AgentModelPickerProjector.project(providers, selection.providerId, selection.modelId)
        all.copy(
            providerGroups = all.providerGroups.map { group ->
                group.copy(models = group.models.filter { !it.supportsImageGeneration && !it.supportsVideoGeneration })
            }.filter { it.models.isNotEmpty() },
            selectedModel = all.selectedModel?.takeIf { !it.supportsImageGeneration && !it.supportsVideoGeneration },
        )
    }
    EtaScaffoldPage(title = stringResource(R.string.whale_maid_title), onBack = onBack) {
        item(key = "whale_maid") {
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                EtaClickableRow(
                    title = stringResource(R.string.whale_maid_title),
                    showChevron = false,
                    onClick = {
                        TouchHaptics.click(view)
                        expanded = !expanded
                    },
                    holdDownState = expanded,
                    endActions = {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .padding(end = 8.dp)
                                .size(16.dp),
                            tint = etaOnSurfaceVariant(),
                        )
                        EtaSwitch(
                            checked = enabled,
                            onCheckedChange = { value ->
                                if (value && !Settings.canDrawOverlays(context)) {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                    return@EtaSwitch
                                }
                                enabled = value
                                WhaleMaidController.setEnabled(context, value)
                            },
                        )
                    },
                )
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column {
                        EtaSwitchRow(
                            title = stringResource(R.string.whale_maid_work_speech),
                            checked = workSpeech,
                            onCheckedChange = { value ->
                                workSpeech = value
                                WhaleMaidController.setWorkSpeechEnabled(context, value)
                            },
                        )
                        EtaSwitchRow(
                            title = stringResource(R.string.whale_maid_global),
                            checked = globalVisible,
                            onCheckedChange = { value ->
                                globalVisible = value
                                WhaleMaidController.setGlobalVisible(context, value)
                            },
                        )
                        EtaSwitchRow(
                            title = stringResource(R.string.whale_maid_custom_model),
                            checked = selection.custom,
                            onCheckedChange = { value ->
                                selection = selection.copy(custom = value)
                                WhaleMaidController.setModelSelection(
                                    context,
                                    selection.custom,
                                    selection.providerId,
                                    selection.modelId,
                                )
                            },
                        )
                        if (selection.custom) {
                            EtaClickableRow(
                                title = stringResource(R.string.whale_maid_select_model),
                                summary = models.selectedModel?.let { "${it.providerName} / ${it.displayName}" },
                                onClick = { picker = true },
                            )
                        }
                    }
                }
            }
        }
    }
    TtsModelPickerDialog(
        state = models,
        show = picker,
        onDismiss = { picker = false },
        onModelSelected = { providerId, modelId ->
            selection = ModelFeatureSelection(true, providerId, modelId)
            WhaleMaidController.setModelSelection(context, true, providerId, modelId)
            picker = false
        },
        title = stringResource(R.string.whale_maid_select_model),
    )
}
