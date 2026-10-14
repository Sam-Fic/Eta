package io.github.mangi.eta.ui

import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices
import io.github.mangi.eta.agent.voice.tts.ReadAloudVoiceHistory
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.components.EtaFormTextField
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.mangi.eta.ui.adaptive.EtaScaffold

@Composable
internal fun PersonalVoicesScreen(
    onBack: () -> Unit,
    onOpenSubPage: (VoiceSubPage) -> Unit = {},
) {
    val view = LocalView.current
    BackHandler(onBack = onBack)
    VoiceSettingsSectionPage(title = androidx.compose.ui.res.stringResource(io.github.mangi.eta.R.string.voice_section_personal), onBack = onBack) {
        voiceGroup(key = "personal") {
            item {
                EtaClickableRow(title = stringResource(R.string.pv_doubao_voice),
                    onClick = { TouchHaptics.click(view); onOpenSubPage(VoiceSubPage.DOUBAO_VOICES) })
            }
            item {
                EtaClickableRow(title = stringResource(R.string.pv_mimo_voice),
                    onClick = { TouchHaptics.click(view); onOpenSubPage(VoiceSubPage.MIMO_VOICES) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MimoVoicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val available = providers.filter(MimoPersonalVoices::supports)
    var selectedProvider by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID)) }
    val provider = available.firstOrNull { it.id == selectedProvider } ?: available.singleOrNull()
    var providerMenu by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<MimoPersonalVoices.Voice?>(null) }
    var sample by remember { mutableStateOf(context.getString(R.string.pv_sample_default)) }
    val voices by MimoPersonalVoices.state.collectAsState()
    val playback by SpeechPlayback.state.collectAsState()
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { MimoPersonalVoices.load(context) } }
            .onSuccess { ready = true }.onFailure { notice = context.getString(R.string.pv_list_load_failed) }
    }
    DisposableEffect(Unit) { onDispose { if (SpeechPlayback.state.value.owner?.startsWith("mimo-preview:") == true) SpeechPlayback.stop("preview_dispose") } }
    BackHandler(enabled = !busy, onBack = onBack)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result -> uri = result }
    EtaScaffold(title = stringResource(R.string.pv_mimo_voice), onBack = { TouchHaptics.click(view); onBack() }) { padding, scrollConnection, sidePadding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .padding(horizontal = sidePadding).nestedScroll(scrollConnection)
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pv_save_hint), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body2, MaterialTheme.typography.bodyMedium))
            if (available.isEmpty()) Text(stringResource(R.string.pv_need_provider))
            Box {
                TextButton(enabled = !busy && available.isNotEmpty(), onClick = { TouchHaptics.click(view); providerMenu = true }) {
                    Text(provider?.name ?: stringResource(R.string.pv_select_provider))
                }
                io.github.mangi.eta.ui.components.EtaMaterialDropdownMenu(providerMenu, { providerMenu = false }) {
                    available.forEach { item ->
                        io.github.mangi.eta.ui.components.EtaMaterialDropdownMenuItem(item.name, onClick = {
                            TouchHaptics.click(view); selectedProvider = item.id; providerMenu = false
                        })
                    }
                }
            }
            EtaFormTextField(name, { name = it.take(80) }, hint = stringResource(R.string.dv_voice_name_label), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy, onClick = { TouchHaptics.click(view); picker.launch(arrayOf("audio/mpeg", "audio/wav", "audio/x-wav")) }) {
                Text(if (uri == null) stringResource(R.string.pv_select_audio) else stringResource(R.string.pv_reselect_audio))
            }
            Text(stringResource(R.string.pv_import_note), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.footnote1, MaterialTheme.typography.bodySmall))
            Button(enabled = ready && !busy && uri != null && name.isNotBlank() && provider != null,
                modifier = Modifier.fillMaxWidth(), onClick = {
                    TouchHaptics.click(view)
                    val source = uri ?: return@Button
                    val target = provider ?: return@Button
                    val title = name
                    busy = true; notice = ""
                    scope.launch {
                        try {
                            MimoPersonalVoices.import(context, source, title, target.id)
                            name = ""; uri = null; notice = context.getString(R.string.pv_saved_toast)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            notice = if (e is IllegalArgumentException || e is IllegalStateException) e.message.orEmpty() else context.getString(R.string.pv_import_failed)
                        } finally { busy = false }
                    }
                }) { Text(if (busy) stringResource(R.string.pv_saving) else stringResource(R.string.pv_save_reference)) }
            EtaFormTextField(sample, { sample = it.take(300) }, hint = stringResource(R.string.pv_sample_hint), modifier = Modifier.fillMaxWidth())
            if (notice.isNotBlank()) Text(notice, style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body2, MaterialTheme.typography.bodyMedium))
            playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            voices.forEach { voice ->
                val owner = "mimo-preview:${voice.id}"
                val playing = playback.owner == owner
                val bound = available.firstOrNull { it.id == voice.providerId }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(voice.name, style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body1, MaterialTheme.typography.bodyLarge))
                        Text(stringResource(R.string.pv_voice_meta, bound?.name ?: stringResource(R.string.pv_provider_unavailable), voice.durationMs / 1000.0), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.footnote1, MaterialTheme.typography.bodySmall))
                        if (playing) Text(if (playback.preparing) stringResource(R.string.pv_generating_preview) else stringResource(R.string.dv_previewing), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.footnote1, MaterialTheme.typography.bodySmall))
                        Row {
                            TextButton(enabled = playing || (bound != null && sample.isNotBlank() && !playback.recording), onClick = {
                                TouchHaptics.click(view); SpeechPlayback.previewMimo(context, voice, sample)
                            }) { Text(if (playing) stringResource(R.string.action_stop) else stringResource(R.string.dv_preview)) }
                            TextButton(enabled = bound != null, onClick = {
                                TouchHaptics.click(view)
                                val model = bound?.let { SpeechSynthesisModels.mergeCatalog(it) }?.firstOrNull { it.modelId == "mimo-v2.5-tts" && it.isEnabled }
                                if (model == null) notice = context.getString(R.string.pv_enable_model_first) else {
                                    SpeechPlayback.stop("settings")
                                    ReadAloudVoiceHistory.rememberCurrent(context)
                                    Prefs.putString(Prefs.Keys.AGENT_TTS_MODE, "cloud")
                                    Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID, voice.providerId)
                                    Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_ID, model.id)
                                    Prefs.putString(Prefs.Keys.AGENT_TTS_VOICE, voice.id)
                                    ReadAloudVoiceHistory.remember(context, voice.providerId, model.id, voice.id)
                                    notice = context.getString(R.string.pv_set_as_read_aloud, voice.name)
                                }
                            }) { Text(stringResource(R.string.pv_use_for_read_aloud)) }
                            TextButton(enabled = !busy, onClick = { TouchHaptics.click(view); deleting = voice }) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { voice ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.pv_delete_title, voice.name)) },
            text = { Text(stringResource(R.string.pv_delete_note)) },
            confirmButton = { TextButton(onClick = {
                TouchHaptics.click(view); SpeechPlayback.stop("settings"); deleting = null; busy = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { MimoPersonalVoices.remove(voice.id) }; notice = context.getString(R.string.pv_deleted) }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = context.getString(R.string.pv_delete_failed) }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.action_delete)) } }, dismissButton = { TextButton(onClick = { TouchHaptics.click(view); deleting = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
