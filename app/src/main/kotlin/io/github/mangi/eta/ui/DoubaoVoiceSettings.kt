package io.github.mangi.eta.ui

import androidx.compose.ui.text.TextStyle
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.agent.voice.doubao.PersonalVoicePreview
import io.github.mangi.eta.agent.voice.doubao.PersonalVoices
import io.github.mangi.eta.agent.voice.doubao.VoiceCatalogPreferences
import io.github.mangi.eta.agent.voice.doubao.VoiceCatalogSecretStore
import io.github.mangi.eta.agent.voice.doubao.VoiceProjectCatalog
import io.github.mangi.eta.ui.components.EtaMaterialDropdownMenu
import io.github.mangi.eta.ui.components.EtaFormTextField
import io.github.mangi.eta.ui.components.EtaMaterialDropdownMenuItem
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DoubaoVoiceSettings(onBack: () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleteVoice by remember { mutableStateOf<PersonalVoices.Voice?>(null) }
    var showSync by remember { mutableStateOf(false) }
    var manualId by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var audio by remember { mutableStateOf<android.net.Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var showAccount by remember { mutableStateOf(false) }
    var settingsMenu by remember { mutableStateOf(false) }
    val back: () -> Unit = {
        when (voiceSettingsBackTarget(showSync, showAccount, mode, step)) {
            VoiceSettingsBackTarget.SYNC_PARENT -> showSync = false
            VoiceSettingsBackTarget.ACCOUNT_PARENT -> showAccount = false
            VoiceSettingsBackTarget.PREVIOUS_STEP -> step--
            VoiceSettingsBackTarget.HOME -> mode = null
            VoiceSettingsBackTarget.EXIT -> onBack()
        }
    }
    val config by DoubaoVoiceConfig.state.collectAsState()
    val voices by PersonalVoices.state.collectAsState()
    var cloneKey by remember { mutableStateOf("") }
    var postpaid by remember { mutableStateOf(false) }
    var slotId by remember { mutableStateOf("") }
    var importBusy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { if (!busy && !importBusy) back() }
    val preview = remember { PersonalVoicePreview() }
    val previewState by preview.state.collectAsState()
    LaunchedEffect(previewState.error) {
        previewState.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(Unit) {
        DoubaoVoiceConfig.load(context); PersonalVoices.load(context)
        cloneKey = DoubaoVoiceConfig.state.value.cloneKey
    }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audio = uri; fileName = context.getString(R.string.dv_recording_selected); consent = false
            scope.launch {
                val display = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                }
                if (audio == uri) fileName = display ?: context.getString(R.string.dv_recording_selected)
            }
        }
    }
    deleteVoice?.let { voice ->
        AlertDialog(onDismissRequest = { deleteVoice = null }, title = { Text(stringResource(R.string.dv_remove_title, voice.name)) },
            text = { Text(stringResource(R.string.dv_delete_local_note)) },
            confirmButton = { TextButton(onClick = {
                TouchHaptics.click(view)
                try { PersonalVoices.removeLocal(voice); if (previewState.matches(voice.account, voice.id)) preview.stop(5); notice = context.getString(R.string.dv_local_removed) }
                catch (_: Exception) { notice = context.getString(R.string.dv_local_remove_failed) }
                deleteVoice = null
            }) { Text(stringResource(R.string.dv_remove_record), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { TouchHaptics.click(view); deleteVoice = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    fun beginMode(next: String) {
        mode = next; name = ""; step = 0; slotId = ""; manualId = false
        showSync = false; postpaid = false; advanced = false; audio = null
        fileName = ""; consent = false; notice = ""
    }
    val home = mode == null && !showAccount && !showSync
    LaunchedEffect(home) { if (!home) preview.stop() }
    EtaScaffold(
        title = when {
            showSync -> stringResource(R.string.dv_sync_title)
            showAccount -> stringResource(R.string.dv_account_title)
            mode == "create" -> stringResource(R.string.dv_mode_create)
            mode == "import" -> stringResource(R.string.dv_mode_import)
            else -> stringResource(R.string.dv_my_voices)
        },
        onBack = { TouchHaptics.click(view); back() },
        actions = {
            if (home) Box {
                IconButton(onClick = { TouchHaptics.click(view); settingsMenu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.dv_settings_cd)) }
                EtaMaterialDropdownMenu(expanded = settingsMenu, onDismissRequest = { settingsMenu = false }) {
                    EtaMaterialDropdownMenuItem(text = stringResource(R.string.dv_account_title), onClick = { TouchHaptics.click(view); settingsMenu = false; showAccount = true; notice = "" })
                    EtaMaterialDropdownMenuItem(text = stringResource(R.string.dv_sync_title), enabled = config.cloneKey.isNotBlank(), onClick = { TouchHaptics.click(view); settingsMenu = false; showSync = true; notice = "" })
                }
            }
        },
    ) { padding, scrollConnection, sidePadding ->
        key(mode, step, showAccount, showSync) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .padding(horizontal = sidePadding).nestedScroll(scrollConnection).imePadding(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (showAccount) {
                            VoiceTextField(cloneKey, { cloneKey = it }, label = stringResource(R.string.dv_api_key_label), visualTransformation = PasswordVisualTransformation())
                            VoiceAction(text = stringResource(R.string.dv_save_account), primary = true, enabled = cloneKey.isNotBlank(), onClick = {
                                DoubaoVoiceConfig.save(context, config.copy(cloneKey = cloneKey)); showAccount = false
                            })
                            VoiceConsoleHelp()
                        } else if (showSync) {
                            VoiceSlotSync(config.cloneKey, onBusy = { importBusy = it }, onResult = { notice = it })
                        } else {
                            if (mode == "create") {
                                Text("${step + 1} / 3 · " + listOf(stringResource(R.string.dv_step_slot), stringResource(R.string.dv_step_recording), stringResource(R.string.dv_step_confirm))[step], style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body2, MaterialTheme.typography.bodyMedium), color = MaterialTheme.colorScheme.primary)
                                LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
                            }
                            if (mode != null && step == 0) {
                                Text(if (mode == "import") stringResource(R.string.dv_import_hint) else stringResource(R.string.dv_slot_hint), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body2, MaterialTheme.typography.bodyMedium), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                VoiceTextField(name, { name = it.take(80) }, enabled = !busy, label = if (mode == "import") stringResource(R.string.dv_note_name_label) else stringResource(R.string.dv_voice_name_label), singleLine = true, modifier = Modifier.fillMaxWidth())
                                if (mode == "create") {
                                    VoiceAction(text = stringResource(R.string.dv_advanced_postpaid), onClick = { advanced = !advanced })
                                    if (advanced || postpaid) Column(Modifier.selectableGroup()) {
                                        listOf(false to stringResource(R.string.dv_opt_existing_slot), true to stringResource(R.string.dv_opt_new_postpaid)).forEach { (value, label) ->
                                            ListItem(
                                                modifier = Modifier.selectable(selected = postpaid == value, role = Role.RadioButton, onClick = { TouchHaptics.click(view); postpaid = value; consent = false }),
                                                content = { Text(label) },
                                                leadingContent = { RadioButton(selected = postpaid == value, onClick = null) },
                                            )
                                        }
                                    }
                                }
                                if (!postpaid || mode == "import") {
                                    val slots = voices.filter { it.account == PersonalVoices.account(config.cloneKey) && it.id.startsWith("S_") && (mode == "create" || it.showInMyVoices) }
                                        .sortedWith(compareByDescending<PersonalVoices.Voice> { it.unused }.thenBy { it.name })
                                    Text(if (mode == "create") stringResource(R.string.dv_pick_slot) else stringResource(R.string.dv_pick_voice), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.subtitle, MaterialTheme.typography.titleMedium))
                                    if (slots.isEmpty()) {
                                        Text(stringResource(R.string.dv_no_slots))
                                        Text(stringResource(R.string.dv_no_slots_hint))
                                    }
                                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                                        slots.forEachIndexed { index, slot ->
                                            VoiceSlotRow(
                                                slot = slot,
                                                title = if (slot.name == slot.id) stringResource(R.string.dv_slot_index, index + 1) else slot.name,
                                                selected = slotId == slot.id,
                                                enabled = !busy && !importBusy && (mode == "import" || slot.canTrain),
                                                onClick = { slotId = slot.id; consent = false; manualId = false },
                                            )
                                        }
                                    }
                                    VoiceAction(text = stringResource(R.string.dv_find_slots), enabled = !busy && !importBusy, onClick = { showSync = true; notice = "" })
                                    VoiceAction(text = if (manualId) stringResource(R.string.dv_collapse_manual) else stringResource(R.string.dv_manual_alternative), enabled = !busy, onClick = { manualId = !manualId; slotId = ""; consent = false })
                                    if (manualId) {
                                        Text(stringResource(R.string.dv_console_hint_slots))
                                        VoiceConsoleHelp(showKeyHelp = false)
                                        VoiceTextField(slotId, { slotId = it.trim(); consent = false }, enabled = !busy, label = stringResource(R.string.dv_paste_voice_id), singleLine = true, modifier = Modifier.fillMaxWidth())
                                    }
                                    if (slotId.isNotBlank()) Text(if (mode == "import") stringResource(R.string.dv_selected_voice_hint) else stringResource(R.string.dv_selected_slot_hint), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body2, MaterialTheme.typography.bodyMedium))
                                } else Text(stringResource(R.string.dv_postpaid_note))
                                if (mode == "import") {
                                    VoiceAction(text = if (busy) stringResource(R.string.dv_querying) else stringResource(R.string.dv_mode_import), primary = true, enabled = !busy && !importBusy && slotId.matches(Regex("S_[A-Za-z0-9_-]+")), onClick = {
                                        busy = true
                                        scope.launch {
                                            try { PersonalVoices.importExisting(config.cloneKey, slotId, name); mode = null; notice = context.getString(R.string.dv_import_success) }
                                            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = e.message ?: context.getString(R.string.dv_import_failed) }
                                            finally { busy = false }
                                        }
                                    })
                                } else VoiceAction(text = stringResource(R.string.dv_next_recording), primary = true, enabled = !importBusy && name.isNotBlank() && (postpaid || (slotId.matches(Regex("S_[A-Za-z0-9_-]+")) && (PersonalVoices.find(slotId, config.cloneKey)?.canTrain != false))), onClick = { step = 1; notice = "" })
                                if (slotId.isBlank() && !postpaid) Text(stringResource(R.string.dv_pick_slot_first), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body2, MaterialTheme.typography.bodyMedium))
                            }
                            if (mode == "create" && step == 1) {
                                Text(stringResource(R.string.dv_recording_tips))
                                VoiceAction(text = if (audio == null) stringResource(R.string.dv_choose_recording) else stringResource(R.string.dv_choose_recording_again), onClick = { picker.launch(arrayOf("audio/*")) })
                                if (fileName.isNotBlank()) Text(fileName)
                                Text(stringResource(R.string.dv_file_not_uploaded))
                                VoiceAction(text = stringResource(R.string.dv_next_confirm), primary = true, enabled = audio != null, onClick = { step = 2; consent = false })
                            }
                            if (mode == "create" && step == 2) {
                                Text(stringResource(R.string.dv_name_prefix, name))
                                Text(if (postpaid) stringResource(R.string.dv_opt_new_postpaid) else stringResource(R.string.dv_using_voice, PersonalVoices.find(slotId, config.cloneKey)?.name ?: slotId))
                                Text(if (postpaid) stringResource(R.string.dv_postpaid_fee_note) else stringResource(R.string.dv_training_note))
                                Row(
                                    modifier = Modifier.fillMaxWidth().toggleable(value = consent, enabled = !busy, role = Role.Checkbox, onValueChange = { TouchHaptics.click(view); consent = it }).padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = consent, onCheckedChange = null, enabled = !busy)
                                    Text(stringResource(R.string.dv_consent), Modifier.weight(1f).padding(start = 12.dp))
                                }
                                VoiceAction(text = if (busy) stringResource(R.string.dv_submitting) else stringResource(R.string.dv_upload_create), primary = true, enabled = consent && !busy, onClick = {
                                    busy = true
                                    val trainingKey = config.cloneKey
                                    scope.launch {
                                        try { PersonalVoices.create(context.applicationContext, requireNotNull(audio), name, trainingKey, if (postpaid) null else slotId.trim()); mode = null; step = 0; notice = context.getString(R.string.dv_submit_success) }
                                        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = e.message ?: context.getString(R.string.dv_submit_failed) }
                                        finally { busy = false }
                                    }
                                })
                            }
                            if (mode == null) {
                                VoiceGroup {
                                    item {
                                        EtaClickableRow(title = stringResource(R.string.dv_entry_create), showChevron = false,
                                            onClick = { TouchHaptics.click(view); if (config.cloneKey.isBlank()) showAccount = true else beginMode("create") })
                                    }
                                    item {
                                        EtaClickableRow(title = stringResource(R.string.dv_entry_import), showChevron = false,
                                            onClick = { TouchHaptics.click(view); if (config.cloneKey.isBlank()) showAccount = true else beginMode("import") })
                                    }
                                }
                                val myVoices = voices.filter { it.account == PersonalVoices.account(config.cloneKey) && it.showInMyVoices }
                                if (myVoices.isEmpty()) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(stringResource(R.string.dv_empty_title), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.headline2, MaterialTheme.typography.titleMedium))
                                        Text(if (config.cloneKey.isBlank()) stringResource(R.string.dv_empty_no_account) else stringResource(R.string.dv_empty_has_account), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body2, MaterialTheme.typography.bodyMedium), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (config.cloneKey.isBlank()) VoiceAction(text = stringResource(R.string.dv_config_account), onClick = { showAccount = true })
                                    }
                                }
                                myVoices.forEach { voice ->
                                    key(voice.account, voice.id) {
                                        PersonalVoiceRow(
                                            voice = voice,
                                            enabled = !importBusy,
                                            previewActive = previewState.matches(voice.account, voice.id),
                                            previewLoading = previewState.matches(voice.account, voice.id) && previewState.loading,
                                            onRefresh = { PersonalVoices.refresh(voice, config.cloneKey) },
                                            onDelete = { deleteVoice = voice },
                                            onAccept = { PersonalVoices.accept(voice) },
                                            onPreview = {
                                                preview.toggle(voice.account, voice.id, voice.demo)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (notice.isNotBlank()) Text(notice, style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body2, MaterialTheme.typography.bodyMedium))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceConsoleHelp(showKeyHelp: Boolean = true) {
    val context = LocalContext.current
    VoiceAction(text = stringResource(R.string.dv_open_console), onClick = {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.volcengine.com/speech/new/overview?projectName=default")))
    })
    if (showKeyHelp) Text(stringResource(R.string.dv_key_help), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body2, MaterialTheme.typography.bodyMedium))
}

@Composable
private fun VoiceSlotSync(apiKey: String, onBusy: (Boolean) -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ak by remember { mutableStateOf(VoiceCatalogPreferences.keyId(context)) }
    val secretStore = remember(context) { VoiceCatalogSecretStore(context) }
    var sk by remember { mutableStateOf(secretStore.read(ak).orEmpty()) }
    var storageError by remember { mutableStateOf("") }
    var project by remember { mutableStateOf(VoiceCatalogPreferences.project(context)) }
    var resultText by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var projectOptions by remember { mutableStateOf(false) }
    var projectPicker by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf<List<VoiceProjectCatalog.Project>>(emptyList()) }
    var projectsLoaded by remember { mutableStateOf(false) }
    var projectLoading by remember { mutableStateOf(false) }
    var projectError by remember { mutableStateOf("") }
    val view = LocalView.current
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    val working = busy || projectLoading
    Text(stringResource(R.string.dv_sync_intro), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body2, MaterialTheme.typography.bodyMedium), color = MaterialTheme.colorScheme.onSurfaceVariant)
    VoiceAction(text = stringResource(R.string.dv_open_keymanage), onClick = {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.volcengine.com/iam/keymanage/")))
    })
    Text(stringResource(R.string.dv_keymanage_hint), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.footnote1, MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
    VoiceTextField(ak, {
        if (it.trim() != ak.trim()) {
            runCatching { secretStore.clear() }.onFailure { storageError = context.getString(R.string.dv_old_sk_clear_failed) }
            sk = ""
            projects = emptyList(); projectsLoaded = false; projectError = ""
        }
        ak = it; VoiceCatalogPreferences.save(context, it, project)
    }, label = "Access Key ID（AK）", enabled = !working,
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    VoiceTextField(sk, {
        if (sk != it) { projects = emptyList(); projectsLoaded = false; projectError = "" }
        sk = it
        storageError = runCatching { secretStore.save(ak, it) }.fold({ "" }, { context.getString(R.string.dv_sk_save_failed) })
    }, label = "Secret Access Key（SK）", enabled = !working,
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.dv_current_project, project.ifBlank { stringResource(R.string.dv_not_selected) }), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body1, MaterialTheme.typography.bodyLarge))
    VoiceAction(text = if (projectLoading) stringResource(R.string.dv_loading_projects) else if (projectsLoaded) stringResource(R.string.dv_refresh_projects) else stringResource(R.string.dv_load_projects),
        enabled = !working && ak.isNotBlank() && sk.isNotBlank(), onClick = {
            projectLoading = true; onBusy(true); projectError = ""
            val accessKey = ak.trim(); val secretKey = sk.trim()
            scope.launch {
                try {
                    projects = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        VoiceProjectCatalog.list(accessKey, secretKey)
                    }
                    projectsLoaded = true
                    projectPicker = projects.isNotEmpty()
                    if (projects.isEmpty()) projectError = context.getString(R.string.dv_no_projects)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    projects = emptyList(); projectsLoaded = false
                    projectError = when (e) {
                        is java.io.IOException -> context.getString(R.string.dv_projects_io_error)
                        is org.json.JSONException -> context.getString(R.string.dv_projects_json_error)
                        else -> e.message ?: context.getString(R.string.dv_projects_failed)
                    }
                } finally { projectLoading = false; onBusy(false) }
            }
        })
    if (projects.isNotEmpty()) VoiceAction(text = stringResource(R.string.dv_pick_project), enabled = !working, onClick = { projectPicker = true })
    if (projectError.isNotBlank()) Text(projectError, color = MaterialTheme.colorScheme.error)
    Text(stringResource(R.string.dv_project_hint), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.footnote1, MaterialTheme.typography.bodySmall),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    VoiceAction(text = if (projectOptions) stringResource(R.string.dv_collapse_manual) else stringResource(R.string.dv_manual_project), enabled = !working,
        onClick = { projectOptions = !projectOptions })
    if (projectOptions) {
        VoiceTextField(project, { project = it; resultText = ""; failed = false; onResult(""); VoiceCatalogPreferences.save(context, ak, it) }, label = stringResource(R.string.dv_project_name), enabled = !working, singleLine = true)
    }
    if (projectPicker) AlertDialog(
        onDismissRequest = { projectPicker = false },
        title = { Text(stringResource(R.string.dv_pick_project)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                projects.forEach { item ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected = project == item.name, enabled = item.allowed && !working, role = Role.RadioButton, onClick = {
                            TouchHaptics.click(view)
                            project = item.name
                            resultText = ""; failed = false; onResult("")
                            VoiceCatalogPreferences.save(context, ak, item.name)
                            projectPicker = false; projectOptions = false
                        }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = project == item.name, enabled = item.allowed && !working, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(item.displayName, style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body1, MaterialTheme.typography.bodyLarge),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.allowed) 1f else 0.38f))
                            if (item.displayName != item.name) Text(item.name, style = etaAdaptiveTextStyle(MiuixTheme.textStyles.footnote1, MaterialTheme.typography.bodySmall))
                            if (!item.allowed) Text(stringResource(R.string.dv_no_project_access), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.footnote1, MaterialTheme.typography.bodySmall),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { TouchHaptics.click(view); projectPicker = false }) { Text(stringResource(R.string.action_close)) } },
    )
    VoiceAction(text = if (busy) stringResource(R.string.dv_searching_slots) else stringResource(R.string.dv_read_my_slots), primary = true, enabled = !working && apiKey.isNotBlank() && ak.isNotBlank() && sk.isNotBlank() && project.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
        busy = true; onBusy(true); resultText = ""; failed = false; onResult("")
        val savedKey = apiKey; val accessKey = ak.trim(); val secretKey = sk.trim(); val selectedProject = project.trim()
        scope.launch {
            try {
                val count = PersonalVoices.importPurchased(savedKey, accessKey, secretKey, selectedProject)
                resultText = if (count == 0) context.getString(R.string.dv_no_slots_found) else context.getString(R.string.dv_synced_count, count)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = true
                resultText = e.message ?: context.getString(R.string.dv_sync_failed)
            } finally { busy = false; onBusy(false) }
        }
    })
    if (resultText.isNotBlank()) Text(resultText, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if (storageError.isNotBlank()) Text(storageError, color = MaterialTheme.colorScheme.error)
    Text(stringResource(R.string.dv_sk_storage_note), style = etaAdaptiveTextStyle(MiuixTheme.textStyles.footnote1, MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
    VoiceAction(text = stringResource(R.string.dv_clear_remembered), enabled = !working, onClick = {
        runCatching { secretStore.clear() }.onSuccess {
            VoiceCatalogPreferences.clear(context); ak = ""; project = "default"; sk = ""; storageError = ""
            projects = emptyList(); projectsLoaded = false; projectError = ""; projectPicker = false
            resultText = context.getString(R.string.dv_cleared); failed = false
        }.onFailure { storageError = context.getString(R.string.dv_clear_failed) }
    })
}

@Composable
private fun PersonalVoiceRow(
    voice: PersonalVoices.Voice,
    enabled: Boolean,
    previewActive: Boolean,
    previewLoading: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onAccept: () -> Unit,
    onPreview: () -> Unit,
) {
    val view = LocalView.current
    var menu by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    Column {
        ListItem(
            content = { Text(voice.name, style = etaAdaptiveTextStyle(MiuixTheme.textStyles.body1, MaterialTheme.typography.bodyLarge)) },
            supportingContent = { Text(if (previewLoading) stringResource(R.string.dv_preview_loading) else if (previewActive) stringResource(R.string.dv_previewing) else when (voice.status) {
                -2 -> stringResource(R.string.dv_status_rejected); 0 -> stringResource(R.string.dv_status_notfound); 1 -> stringResource(R.string.dv_status_training); 2 -> stringResource(R.string.dv_status_train_ok); 3 -> stringResource(R.string.dv_status_train_fail); 4 -> stringResource(R.string.dv_status_accepted); else -> stringResource(R.string.dv_status_pending)
            }) },
            leadingContent = { Icon(Icons.Rounded.Mic, contentDescription = null) },
            trailingContent = {
                Row {
                    IconButton(enabled = previewActive || voice.demo.startsWith("https://"), onClick = { TouchHaptics.click(view); onPreview() }) {
                        Icon(if (previewActive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = if (previewActive) stringResource(R.string.dv_preview_stop) else stringResource(R.string.dv_preview))
                    }
                    Box {
                        IconButton(onClick = { TouchHaptics.click(view); menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.dv_voice_actions)) }
                        EtaMaterialDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            EtaMaterialDropdownMenuItem(text = stringResource(R.string.dv_refresh_status), onClick = { TouchHaptics.click(view); menu = false; onRefresh() })
                            if (voice.error.isNotBlank()) EtaMaterialDropdownMenuItem(text = stringResource(R.string.dv_error_details), onClick = { TouchHaptics.click(view); menu = false; showError = true })
                            EtaMaterialDropdownMenuItem(text = stringResource(R.string.dv_remove_local), enabled = enabled, onClick = { TouchHaptics.click(view); menu = false; onDelete() })
                        }
                    }
                }
            },
        )
        if (voice.tts && !voice.accepted) {
            VoiceAction(text = stringResource(R.string.dv_accept_voice), onClick = onAccept)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (showError) AlertDialog(
        onDismissRequest = { showError = false }, title = { Text(stringResource(R.string.dv_error_details)) },
        text = { Text(voice.error, modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { TouchHaptics.click(view); showError = false }) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun VoiceSlotRow(
    slot: PersonalVoices.Voice,
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val status = when {
        slot.status == 1 || slot.catalogState == "Training" -> stringResource(R.string.dv_slot_training)
        slot.status == 4 || slot.catalogState == "Active" -> stringResource(R.string.dv_slot_locked)
        slot.remaining == 0 -> stringResource(R.string.dv_slot_exhausted)
        slot.unused -> stringResource(R.string.dv_slot_unused)
        slot.ready -> stringResource(R.string.dv_slot_ready)
        else -> stringResource(R.string.dv_slot_unknown)
    }
    val quota = if (slot.remaining >= 0) stringResource(R.string.dv_slot_remaining, slot.remaining) else stringResource(R.string.dv_slot_quota_unknown)
    ListItem(
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { TouchHaptics.click(view); onClick() }),
        content = { Text(title) },
        supportingContent = { Text("$status · $quota") },
        leadingContent = { RadioButton(selected = selected, enabled = enabled, onClick = null) },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
        ),
    )
}

@Composable
private fun VoiceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    EtaFormTextField(value = value, onValueChange = onValueChange, hint = label,
        modifier = modifier, enabled = enabled, singleLine = singleLine, visualTransformation = visualTransformation)
}

@Composable
private fun VoiceAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val view = LocalView.current
    val click = { TouchHaptics.click(view); onClick() }
    if (primary) Button(onClick = click, enabled = enabled, modifier = modifier.fillMaxWidth()) { Text(text) }
    else TextButton(onClick = click, enabled = enabled, modifier = modifier) { Text(text) }
}

/**
 * 语音设置页遗留的 miuix 字阶令牌在 M3 主题下的自适应取值：
 * Material 3 用官方字阶，MIUI X 保持原 miuix 字阶。
 */
@Composable
internal fun etaAdaptiveTextStyle(miuix: TextStyle, m3: TextStyle): TextStyle =
    if (isMaterial3Theme()) m3 else miuix
