package io.github.mangi.eta.ui

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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
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
import io.github.mangi.eta.agent.voice.doubao.DoubaoAsrProtocol
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DoubaoVoiceSettings(page: String, onBack: () -> Unit) {
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
    var addMenu by remember { mutableStateOf(false) }
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
    var asrKey by remember { mutableStateOf("") }
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
        asrKey = DoubaoVoiceConfig.state.value.asrKey; cloneKey = DoubaoVoiceConfig.state.value.cloneKey
    }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audio = uri; fileName = "已选择录音"; consent = false
            scope.launch {
                val display = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                }
                if (audio == uri) fileName = display ?: "已选择录音"
            }
        }
    }
    deleteVoice?.let { voice ->
        AlertDialog(onDismissRequest = { deleteVoice = null }, title = { Text("移除“${voice.name}”？") },
            text = { Text("仅删除本机记录，不删除云端音色，也不取消云端任务。若朗读正在使用它，移除后需重新选择声音。") },
            confirmButton = { TextButton(onClick = {
                TouchHaptics.click(view)
                try { PersonalVoices.removeLocal(voice); if (previewState.matches(voice.account, voice.id)) preview.stop(5); notice = "已移除本机记录" }
                catch (_: Exception) { notice = "本机记录删除失败，请重试" }
                deleteVoice = null
            }) { Text("移除记录", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { TouchHaptics.click(view); deleteVoice = null }) { Text("取消") } },
        )
    }
    fun beginMode(next: String) {
        mode = next; name = ""; step = 0; slotId = ""; manualId = false
        showSync = false; postpaid = false; advanced = false; audio = null
        fileName = ""; consent = false; notice = ""; addMenu = false
    }
    val home = page == "voices" && mode == null && !showAccount && !showSync
    LaunchedEffect(home) { if (!home) preview.stop() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(when {
                    showSync -> "同步音色名额"
                    showAccount -> "声音复刻账户"
                    mode == "create" -> "制作声音"
                    mode == "import" -> "导入声音"
                    page == "asr" -> "识别方式"
                    else -> "我的声音"
                }) },
                navigationIcon = {
                    IconButton(enabled = !busy && !importBusy, onClick = { TouchHaptics.click(view); back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (home) Box {
                        IconButton(onClick = { TouchHaptics.click(view); settingsMenu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "声音设置") }
                        EtaMaterialDropdownMenu(expanded = settingsMenu, onDismissRequest = { settingsMenu = false }) {
                            EtaMaterialDropdownMenuItem(text = "声音复刻账户", onClick = { TouchHaptics.click(view); settingsMenu = false; showAccount = true; notice = "" })
                            EtaMaterialDropdownMenuItem(text = "同步音色名额", enabled = config.cloneKey.isNotBlank(), onClick = { TouchHaptics.click(view); settingsMenu = false; showSync = true; notice = "" })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (home) Box {
                FloatingActionButton(onClick = { TouchHaptics.click(view); if (config.cloneKey.isBlank()) showAccount = true else addMenu = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加声音")
                }
                EtaMaterialDropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    EtaMaterialDropdownMenuItem(text = "用录音制作声音", onClick = { TouchHaptics.click(view); beginMode("create") })
                    EtaMaterialDropdownMenuItem(text = "导入已有声音", onClick = { TouchHaptics.click(view); beginMode("import") })
                }
            }
        },
    ) { padding ->
        key(mode, step, showAccount, showSync) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (showAccount) {
                            VoiceTextField(cloneKey, { cloneKey = it }, label = "声音复刻 API Key", visualTransformation = PasswordVisualTransformation())
                            VoiceAction(text = "保存账户", primary = true, enabled = cloneKey.isNotBlank(), onClick = {
                                DoubaoVoiceConfig.save(context, config.copy(cloneKey = cloneKey)); showAccount = false
                            })
                            VoiceConsoleHelp()
                        } else if (showSync) {
                            VoiceSlotSync(config.cloneKey, onBusy = { importBusy = it }, onResult = { notice = it })
                        } else {
                            if (page == "asr") {
                                Row {
                                    RadioButton(!config.cloudAsr, { TouchHaptics.click(view); DoubaoVoiceConfig.save(context, config.copy(cloudAsr = false)) })
                                    Text("本机识别", Modifier.padding(top = 12.dp))
                                    RadioButton(config.cloudAsr, { TouchHaptics.click(view); DoubaoVoiceConfig.save(context, config.copy(cloudAsr = true)) })
                                    Text("豆包识别", Modifier.padding(top = 12.dp))
                                }
                                if (!config.cloudAsr) Text("无需账户。上一页下载语音包后，即可离线识别。")
                                if (config.cloudAsr) {
                                    Text("连接豆包账户", style = MiuixTheme.textStyles.subtitle)
                                    Text("识别音频会发送到豆包；与朗读、实时对话分别配置。")
                                    VoiceTextField(asrKey, { asrKey = it }, label = "ASR API Key", visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                    VoiceAction(text = if (advanced) "收起高级设置" else "高级：更换识别服务", onClick = { advanced = !advanced })
                                    if (advanced) {
                                        Text("仅在控制台开通了不同服务时更改。")
                                        DoubaoAsrProtocol.resources.forEach { resource ->
                                            Row {
                                                RadioButton(selected = resource == config.resource, onClick = { TouchHaptics.click(view); DoubaoVoiceConfig.save(context, config.copy(resource = resource)) })
                                                Text(when (resource) {
                                                    "volc.seedasr.sauc.duration" -> "识别 2.0 · 按时长"
                                                    "volc.seedasr.sauc.concurrent" -> "识别 2.0 · 按并发"
                                                    "volc.bigasr.sauc.duration" -> "识别 1.0 · 按时长"
                                                    else -> "识别 1.0 · 按并发"
                                                }, modifier = Modifier.padding(top = 12.dp), style = MiuixTheme.textStyles.body2)
                                            }
                                        }
                                    }
                                    VoiceAction(text = "保存连接设置", primary = true, enabled = asrKey.isNotBlank(), onClick = { DoubaoVoiceConfig.save(context, config.copy(asrKey = asrKey)); notice = "已保存。返回聊天页说一句话，文字出现即识别成功。" })
                                    VoiceConsoleHelp(showKeyHelp = false)
                                }
                            }
                            if (page == "voices") {
                                if (mode == "create") {
                                    Text("${step + 1} / 3 · " + listOf("选择名额", "选择录音", "确认制作")[step], style = MiuixTheme.textStyles.body2, color = MaterialTheme.colorScheme.primary)
                                    LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
                                }
                                if (mode != null && step == 0) {
                                    Text(if (mode == "import") "选择已有声音或填写音色 ID，仅查询导入，不会重新训练。" else "选择一个已有名额，免费赠送的也可以使用。", style = MiuixTheme.textStyles.body2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    VoiceTextField(name, { name = it.take(80) }, enabled = !busy, label = if (mode == "import") "备注名称（可不填）" else "声音名称", singleLine = true, modifier = Modifier.fillMaxWidth())
                                    if (mode == "create") {
                                        VoiceAction(text = "高级：新建后付费音色", onClick = { advanced = !advanced })
                                        if (advanced || postpaid) Column(Modifier.selectableGroup()) {
                                            listOf(false to "已有/免费名额", true to "新建后付费音色").forEach { (value, label) ->
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
                                        Text(if (mode == "create") "选择一个音色名额" else "选择已制作的声音", style = MiuixTheme.textStyles.subtitle)
                                        if (slots.isEmpty()) {
                                            Text("还没有同步名额，不代表你的免费名额用完了。")
                                            Text("可以连接火山账户读取名额，也可以从控制台复制一次音色 ID。")
                                        }
                                        Column(Modifier.fillMaxWidth().selectableGroup()) {
                                            slots.forEachIndexed { index, slot ->
                                                VoiceSlotRow(
                                                    slot = slot,
                                                    title = if (slot.name == slot.id) "音色名额 ${index + 1}" else slot.name,
                                                    selected = slotId == slot.id,
                                                    enabled = !busy && !importBusy && (mode == "import" || slot.canTrain),
                                                    onClick = { slotId = slot.id; consent = false; manualId = false },
                                                )
                                            }
                                        }
                                        VoiceAction(text = "查找我的音色名额", enabled = !busy && !importBusy, onClick = { showSync = true; notice = "" })
                                        VoiceAction(text = if (manualId) "收起手动填写" else "备用方式：从控制台复制 ID", enabled = !busy, onClick = { manualId = !manualId; slotId = ""; consent = false })
                                        if (manualId) {
                                            Text("控制台 → 音色库 → 我的音色 → 预付费音色。把“已复刻”筛选改为“全部”或未复刻选项，找未使用名额；复制 S_ 开头的 ID。无需先在网页上传录音。")
                                            VoiceConsoleHelp(showKeyHelp = false)
                                            VoiceTextField(slotId, { slotId = it.trim(); consent = false }, enabled = !busy, label = "粘贴复制的音色 ID", singleLine = true, modifier = Modifier.fillMaxWidth())
                                        }
                                        if (slotId.isNotBlank()) Text(if (mode == "import") "已选择声音，点击导入即可。" else "已选择音色名额。下一步选择录音，不会立即上传。", style = MiuixTheme.textStyles.body2)
                                    } else Text("需额外开通同项目的后付费音色服务；仅开通声音复刻 2.0 不足以创建自定义 ID。首次正式合成可能收取音色费用；未正式使用的音色 7 天后可能删除。")
                                    if (mode == "import") {
                                        VoiceAction(text = if (busy) "正在查询…" else "导入声音", primary = true, enabled = !busy && !importBusy && slotId.matches(Regex("S_[A-Za-z0-9_-]+")), onClick = {
                                            busy = true
                                            scope.launch {
                                                try { PersonalVoices.importExisting(config.cloneKey, slotId, name); mode = null; notice = "已导入，可在列表中试听或查看状态。" }
                                                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = e.message ?: "导入失败" }
                                                finally { busy = false }
                                            }
                                        })
                                    } else VoiceAction(text = "下一步：选择录音", primary = true, enabled = !importBusy && name.isNotBlank() && (postpaid || (slotId.matches(Regex("S_[A-Za-z0-9_-]+")) && (PersonalVoices.find(slotId, config.cloneKey)?.canTrain != false))), onClick = { step = 1; notice = "" })
                                    if (slotId.isBlank() && !postpaid) Text("先选择上面的名额；没有列表时，点“查找我的音色名额”。", style = MiuixTheme.textStyles.body2)
                                }
                                if (mode == "create" && step == 1) {
                                    Text("选择清晰的单人录音，尽量没有音乐和噪声。支持 WAV、MP3、OGG、M4A、AAC，不超过 10 MiB。")
                                    VoiceAction(text = if (audio == null) "选择录音" else "重新选择录音", onClick = { picker.launch(arrayOf("audio/*")) })
                                    if (fileName.isNotBlank()) Text(fileName)
                                    Text("选择文件不会立即上传，下一步确认后才制作。")
                                    VoiceAction(text = "下一步：确认制作", primary = true, enabled = audio != null, onClick = { step = 2; consent = false })
                                }
                                if (mode == "create" && step == 2) {
                                    Text("声音名称：$name")
                                    Text(if (postpaid) "新建后付费音色" else "使用音色：${PersonalVoices.find(slotId, config.cloneKey)?.name ?: slotId}")
                                    Text(if (postpaid) "需单独开通后付费音色服务；试听按账户规则计费，首次正式合成可能收取音色费。" else "录音会上传豆包，消耗此音色的训练次数，并可能覆盖原来的声音。试听按账户额度或计费规则结算。")
                                    Row(
                                        modifier = Modifier.fillMaxWidth().toggleable(value = consent, enabled = !busy, role = Role.Checkbox, onValueChange = { TouchHaptics.click(view); consent = it }).padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = consent, onCheckedChange = null, enabled = !busy)
                                        Text("我有权使用该录音，并确认以上操作", Modifier.weight(1f).padding(start = 12.dp))
                                    }
                                    VoiceAction(text = if (busy) "正在提交…" else "确认上传并制作", primary = true, enabled = consent && !busy, onClick = {
                                        busy = true
                                        val trainingKey = config.cloneKey
                                        scope.launch {
                                            try { PersonalVoices.create(context.applicationContext, requireNotNull(audio), name, trainingKey, if (postpaid) null else slotId.trim()); mode = null; step = 0; notice = "已提交制作任务，在下方刷新状态。请先试听，再允许正式使用。" }
                                            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = e.message ?: "提交失败" }
                                            finally { busy = false }
                                        }
                                    })
                                }
                                if (mode == null) {
                                    val myVoices = voices.filter { it.account == PersonalVoices.account(config.cloneKey) && it.showInMyVoices }
                                    if (myVoices.isEmpty()) {
                                        Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("还没有声音", style = MiuixTheme.textStyles.headline2)
                                            Text(if (config.cloneKey.isBlank()) "先配置账户，再添加自己的声音。" else "点击右下角 +，制作或导入声音。", style = MiuixTheme.textStyles.body2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            if (config.cloneKey.isBlank()) VoiceAction(text = "配置账户", onClick = { showAccount = true })
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
                        }
                        if (notice.isNotBlank()) Text(notice, style = MiuixTheme.textStyles.body2)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceConsoleHelp(showKeyHelp: Boolean = true) {
    val context = LocalContext.current
    VoiceAction(text = "打开豆包控制台", onClick = {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.volcengine.com/speech/new/overview?projectName=default")))
    })
    if (showKeyHelp) Text("获取 Key：控制台 → API Key。获取音色 ID：控制台 → 音色库。二者需属于同一项目。", style = MiuixTheme.textStyles.body2)
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
    Text("使用火山访问密钥读取音色名额，不会购买或训练。AK / SK 与豆包 API Key 不同。", style = MiuixTheme.textStyles.body2, color = MaterialTheme.colorScheme.onSurfaceVariant)
    VoiceAction(text = "打开火山访问密钥管理", onClick = {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.volcengine.com/iam/keymanage/")))
    })
    Text("在密钥管理中复制 AK 和 SK，分别填写。", style = MiuixTheme.textStyles.footnote1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    VoiceTextField(ak, {
        if (it.trim() != ak.trim()) {
            runCatching { secretStore.clear() }.onFailure { storageError = "旧 SK 清除失败，请重试" }
            sk = ""
            projects = emptyList(); projectsLoaded = false; projectError = ""
        }
        ak = it; VoiceCatalogPreferences.save(context, it, project)
    }, label = "Access Key ID（AK）", enabled = !working,
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    VoiceTextField(sk, {
        if (sk != it) { projects = emptyList(); projectsLoaded = false; projectError = "" }
        sk = it
        storageError = runCatching { secretStore.save(ak, it) }.fold({ "" }, { "SK 加密保存失败；本次仍可读取名额，离开页面后需重新填写。" })
    }, label = "Secret Access Key（SK）", enabled = !working,
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("当前项目：${project.ifBlank { "未选择" }}", style = MiuixTheme.textStyles.body1)
    VoiceAction(text = if (projectLoading) "正在获取项目…" else if (projectsLoaded) "刷新项目列表" else "获取项目列表",
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
                    if (projects.isEmpty()) projectError = "未返回项目，可重试或手动填写。"
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    projects = emptyList(); projectsLoaded = false
                    projectError = when (e) {
                        is java.io.IOException -> "项目列表连接失败，请检查网络后重试；也可手动填写。"
                        is org.json.JSONException -> "项目列表响应格式异常，请重试或手动填写。"
                        else -> e.message ?: "获取项目失败，请核对访问密钥及 IAM 查询权限。"
                    }
                } finally { projectLoading = false; onBusy(false) }
            }
        })
    if (projects.isNotEmpty()) VoiceAction(text = "选择项目", enabled = !working, onClick = { projectPicker = true })
    if (projectError.isNotBlank()) Text(projectError, color = MaterialTheme.colorScheme.error)
    Text("请选择与声音复刻 API Key 对应的项目；项目列表不能判断 Key 属于哪个项目。", style = MiuixTheme.textStyles.footnote1,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    VoiceAction(text = if (projectOptions) "收起手动填写" else "手动填写项目（备用）", enabled = !working,
        onClick = { projectOptions = !projectOptions })
    if (projectOptions) {
        VoiceTextField(project, { project = it; resultText = ""; failed = false; onResult(""); VoiceCatalogPreferences.save(context, ak, it) }, label = "项目名称", enabled = !working, singleLine = true)
    }
    if (projectPicker) AlertDialog(
        onDismissRequest = { projectPicker = false },
        title = { Text("选择项目") },
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
                            Text(item.displayName, style = MiuixTheme.textStyles.body1,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.allowed) 1f else 0.38f))
                            if (item.displayName != item.name) Text(item.name, style = MiuixTheme.textStyles.footnote1)
                            if (!item.allowed) Text("无此项目访问权限", style = MiuixTheme.textStyles.footnote1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { TouchHaptics.click(view); projectPicker = false }) { Text("关闭") } },
    )
    VoiceAction(text = if (busy) "正在查找名额…" else "读取我的名额", primary = true, enabled = !working && apiKey.isNotBlank() && ak.isNotBlank() && sk.isNotBlank() && project.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
        busy = true; onBusy(true); resultText = ""; failed = false; onResult("")
        val savedKey = apiKey; val accessKey = ak.trim(); val secretKey = sk.trim(); val selectedProject = project.trim()
        scope.launch {
            try {
                val count = PersonalVoices.importPurchased(savedKey, accessKey, secretKey, selectedProject)
                resultText = if (count == 0) "此项目未查到音色名额。请核对控制台左上角项目；免费字数额度不代表一定有音色名额。" else "已同步 $count 个名额。空名额仅在制作声音时显示；已制作声音可在“我的声音”查看。"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = true
                resultText = e.message ?: "同步失败，请检查访问密钥及项目权限"
            } finally { busy = false; onBusy(false) }
        }
    })
    if (resultText.isNotBlank()) Text(resultText, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if (storageError.isNotBlank()) Text(storageError, color = MaterialTheme.colorScheme.error)
    Text("SK 在本机加密保存。更换 AK 会清除旧 SK，请使用配对的密钥。", style = MiuixTheme.textStyles.footnote1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    VoiceAction(text = "清除已记住的信息", enabled = !working, onClick = {
        runCatching { secretStore.clear() }.onSuccess {
            VoiceCatalogPreferences.clear(context); ak = ""; project = "default"; sk = ""; storageError = ""
            projects = emptyList(); projectsLoaded = false; projectError = ""; projectPicker = false
            resultText = "已清除 AK、SK 和项目名"; failed = false
        }.onFailure { storageError = "保存的 SK 清除失败，请重试" }
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
            content = { Text(voice.name, style = MiuixTheme.textStyles.body1) },
            supportingContent = { Text(if (previewLoading) "正在加载试听…" else if (previewActive) "正在试听" else when (voice.status) {
                -2 -> "请求被拒绝"; 0 -> "服务端未找到"; 1 -> "训练中"; 2 -> "训练成功"; 3 -> "训练失败"; 4 -> "已正式使用"; else -> "请求待确认"
            }) },
            leadingContent = { Icon(Icons.Rounded.Mic, contentDescription = null) },
            trailingContent = {
                Row {
                    IconButton(enabled = previewActive || voice.demo.startsWith("https://"), onClick = { TouchHaptics.click(view); onPreview() }) {
                        Icon(if (previewActive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = if (previewActive) "停止试听" else "试听")
                    }
                    Box {
                        IconButton(onClick = { TouchHaptics.click(view); menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "声音操作") }
                        EtaMaterialDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            EtaMaterialDropdownMenuItem(text = "查询状态", onClick = { TouchHaptics.click(view); menu = false; onRefresh() })
                            if (voice.error.isNotBlank()) EtaMaterialDropdownMenuItem(text = "错误详情", onClick = { TouchHaptics.click(view); menu = false; showError = true })
                            EtaMaterialDropdownMenuItem(text = "移除本机记录", enabled = enabled, onClick = { TouchHaptics.click(view); menu = false; onDelete() })
                        }
                    }
                }
            },
        )
        if (voice.tts && !voice.accepted) {
            VoiceAction(text = "确认音色，允许正式合成（可能产生音色费用）", onClick = onAccept)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (showError) AlertDialog(
        onDismissRequest = { showError = false }, title = { Text("错误详情") },
        text = { Text(voice.error, modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { TouchHaptics.click(view); showError = false }) { Text("关闭") } },
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
        slot.status == 1 || slot.catalogState == "Training" -> "制作中"
        slot.status == 4 || slot.catalogState == "Active" -> "已锁定"
        slot.remaining == 0 -> "次数已用完"
        slot.unused -> "未使用"
        slot.ready -> "已有声音，再次制作可能覆盖"
        else -> "状态待确认"
    }
    val quota = if (slot.remaining >= 0) "剩余 ${slot.remaining} 次" else "次数待查询"
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
