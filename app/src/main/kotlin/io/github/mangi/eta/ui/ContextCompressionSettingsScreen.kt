package io.github.mangi.eta.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentCompressionEndpoint
import io.github.mangi.eta.config.AutoCompressPreference
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.components.rememberAutoCompressEnabled
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextCompressionSettingsScreen(context: Context, onBack: () -> Unit) {
    val prefs = Prefs.localAgentPreferences()
    val autoCompressPreference = remember(prefs) { AutoCompressPreference(prefs) }
    val enabled by rememberAutoCompressEnabled(autoCompressPreference)
    var endpointMode by remember {
        mutableStateOf(
            AgentCompressionEndpoint.parse(prefs?.getString(Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE, null)),
        )
    }

    val scope = rememberCoroutineScope()
    var selectedCompressModel by remember { mutableStateOf<AgentModelOptionUi?>(null) }
    var customModelEnabled by remember { mutableStateOf(Prefs.isCustomCompressModelEnabled(prefs)) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showMissingWindowDialog by remember { mutableStateOf(false) }
    var modelPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var isLoadingModels by remember { mutableStateOf(prefs != null) }
    val view = LocalView.current

    LaunchedEffect(prefs) {
        prefs?.let { currentPrefs ->
            endpointMode = AgentCompressionEndpoint.parse(
                currentPrefs.getString(Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE, null),
            )
            isLoadingModels = true
            selectedCompressModel = withContext(Dispatchers.IO) { readCompressModelSelection(currentPrefs) }
            modelPickerState = withContext(Dispatchers.IO) { buildCompressModelPickerState(currentPrefs) }
            customModelEnabled = Prefs.isCustomCompressModelEnabled(currentPrefs)
            isLoadingModels = false
        }
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE -> endpointMode = AgentCompressionEndpoint.parse(prefs?.getString(key, null))
                Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID,
                Prefs.Keys.AGENT_COMPRESS_MODEL_ID -> {
                    prefs?.let { currentPrefs ->
                        scope.launch {
                            selectedCompressModel = withContext(Dispatchers.IO) { readCompressModelSelection(currentPrefs) }
                            modelPickerState = withContext(Dispatchers.IO) { buildCompressModelPickerState(currentPrefs) }
                        }
                    }
                }
                Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED -> {
                    customModelEnabled = Prefs.isCustomCompressModelEnabled(prefs)
                }
            }
        }
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs?.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    EtaScaffoldPage(title = stringResource(R.string.ui_context_compression_settings_title), onBack = onBack) {
        item(key = "auto_compress") {
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        EtaText(
                            text = stringResource(R.string.ui_auto_compress_context_title),
                            style = EtaTextStyle.Body,
                        )
                        EtaText(
                            text = stringResource(R.string.ui_auto_compress_context_summary),
                            style = EtaTextStyle.Footnote,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    EtaSwitch(
                        checked = enabled,
                        enabled = autoCompressPreference.isWritable,
                        onCheckedChange = { value ->
                            TouchHaptics.click(view)
                            autoCompressPreference.setEnabled(value)
                        },
                    )
                }
            }
        }

        item(key = "compress_endpoint") {
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                CompressionEndpointPreference(
                    selected = endpointMode,
                    enabled = prefs != null,
                    onSelect = { option ->
                        endpointMode = option
                        prefs?.edit()?.putString(Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE, option)?.apply()
                    },
                )
            }
        }

        item(key = "compress_model") {
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                EtaSwitchRow(
                    title = stringResource(R.string.ui_custom_compress_model_title),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    summary = stringResource(R.string.ui_custom_compress_model_summary),
                    checked = customModelEnabled,
                    onCheckedChange = { value ->
                        TouchHaptics.click(view)
                        prefs?.edit()?.putBoolean(
                            Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED,
                            value,
                        )?.apply()
                        customModelEnabled = value
                        if (value && selectedCompressModel != null &&
                            !selectedCompressModel.hasCompressContextWindow()
                        ) {
                            showMissingWindowDialog = true
                        }
                    },
                )
                if (customModelEnabled) {
                    EtaClickableRow(
                        title = stringResource(R.string.ui_compress_model_title),
                        summary = selectedCompressModel?.displayName
                            ?: stringResource(R.string.model_not_selected),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        onClick = {
                            TouchHaptics.click(view)
                            showModelDialog = true
                        },
                    )
                }
            }
        }

        item(key = "hint") {
            EtaSectionTitle("")
        }
    }

    CompressModelPickerDialog(
        state = modelPickerState,
        show = showModelDialog,
        isLoading = isLoadingModels,
        onDismiss = { showModelDialog = false },
        onModelSelected = { providerId, modelId ->
            prefs?.edit()?.apply {
                putString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, providerId)
                putString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID, modelId)
            }?.apply()
            if (!modelPickerState.findCompressModel(providerId, modelId).hasCompressContextWindow()) {
                showMissingWindowDialog = true
            }
            scope.launch {
                prefs?.let { currentPrefs ->
                    selectedCompressModel = withContext(Dispatchers.IO) { readCompressModelSelection(currentPrefs) }
                    modelPickerState = withContext(Dispatchers.IO) { buildCompressModelPickerState(currentPrefs) }
                }
            }
            showModelDialog = false
        },
    )

    CompressModelMissingWindowDialog(
        show = showMissingWindowDialog,
        onDismiss = { showMissingWindowDialog = false },
    )
}
