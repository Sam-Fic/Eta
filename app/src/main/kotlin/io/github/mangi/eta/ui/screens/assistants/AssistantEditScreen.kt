package io.github.mangi.eta.ui.screens.assistants

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.components.AssistantAvatar
import io.github.mangi.eta.ui.components.rememberAssistantAvatarBitmap
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.screens.skills.SkillSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.material3.Text as M3Text

@Composable
internal fun AssistantEditScreen(
    assistantId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember(assistantId) { AssistantRepository.profile(assistantId) }
    if (original == null) {
        LaunchedEffect(assistantId) { onBack() }
        return
    }
    var name by remember(assistantId) { mutableStateOf(original.name) }
    var prompt by remember(assistantId) { mutableStateOf(original.prompt) }
    var avatarFileName by remember(assistantId) { mutableStateOf(original.avatarFileName) }
    var memoryEnabled by remember(assistantId) { mutableStateOf(original.memoryEnabled) }
    var persistedMemoryEnabled by remember(assistantId) { mutableStateOf(original.memoryEnabled) }
    var enabledSkillIds by remember(assistantId) { mutableStateOf(original.enabledSkillIds.toSet()) }
    var persistedSkillIds by remember(assistantId) { mutableStateOf(original.enabledSkillIds.toSet()) }
    var memoryDraft by remember(assistantId) { mutableStateOf("") }
    var memoryRevision by remember(assistantId) { mutableStateOf("") }
    var memorySaved by remember(assistantId) { mutableStateOf("") }
    var installedSkills by remember(assistantId) { mutableStateOf<List<SkillIndexEntry>>(emptyList()) }
    var cropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    val avatarBitmap = rememberAssistantAvatarBitmap(assistantId, avatarFileName)
    val memoryBytes = remember(memoryDraft) { memoryDraft.toByteArray(Charsets.UTF_8).size }
    val memoryOverLimit = memoryBytes > AgentMemoryStore.MAX_FILE_BYTES
    val dirty = name.trim() != original.name ||
        prompt != original.prompt ||
        avatarFileName != original.avatarFileName ||
        memoryEnabled != persistedMemoryEnabled ||
        enabledSkillIds != persistedSkillIds ||
        memoryDraft != memorySaved

    LaunchedEffect(assistantId) {
        val loaded = withContext(Dispatchers.IO) {
            val snapshot = runCatching { AgentMemoryRepository.snapshot(assistantId) }.getOrNull()
            val skills = runCatching {
                SkillRuntime.createIndexService(context.applicationContext)
                    .listSkillsForManagement()
                    .filter { it.installed }
            }.getOrDefault(emptyList())
            snapshot to skills
        }
        memoryDraft = loaded.first?.content.orEmpty()
        memorySaved = loaded.first?.content.orEmpty()
        memoryRevision = loaded.first?.revision.orEmpty()
        installedSkills = loaded.second
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        cropBitmap = decodeAvatarBitmap(context, uri)
    }
    val pickAvatar = {
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    fun persistAssistantToggles(nextMemory: Boolean = memoryEnabled, nextSkills: Set<String> = enabledSkillIds) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val current = requireNotNull(AssistantRepository.currentProfile(assistantId)) { context.getString(R.string.aes_assistant_missing) }
                AssistantRepository.update(current.copy(memoryEnabled = nextMemory, enabledSkillIds = nextSkills.toList()))
            } }
            if (result.isSuccess) {
                persistedMemoryEnabled = nextMemory
                persistedSkillIds = nextSkills
            } else {
                android.widget.Toast.makeText(context, result.exceptionOrNull()?.message ?: context.getString(R.string.aes_skill_catalog_publish_failed), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun save() {
        if (!dirty || saving || memoryOverLimit || memoryRevision.isBlank()) return
        val targetMemory = memoryDraft
        val baseRevision = memoryRevision
        val targetProfile = original.copy(name = name, prompt = prompt, avatarFileName = avatarFileName,
            memoryEnabled = memoryEnabled, enabledSkillIds = enabledSkillIds.toList())
        val memoryChanged = targetMemory != memorySaved
        saving = true
        scope.launch {
            var savedSnapshot: io.github.mangi.eta.data.repository.AgentMemorySnapshot? = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (memoryChanged) savedSnapshot = AgentMemoryRepository.replaceAll(targetMemory, assistantId, baseRevision)
                    AssistantRepository.update(targetProfile)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                }
            }
            savedSnapshot?.let { snapshot ->
                memoryRevision = snapshot.revision
                memorySaved = snapshot.content
            }
            saving = false
            if (result.isSuccess) onBack()
            else android.widget.Toast.makeText(context, result.exceptionOrNull()?.message ?: context.getString(R.string.aes_save_failed_draft_kept), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val saveEnabled = dirty && !saving && !memoryOverLimit
    EtaScaffoldPage(
        title = stringResource(R.string.assistant_edit_title),
        onBack = onBack,
        actions = {
            // M3 下用官方 TextButton（自带涟漪与禁用态），替掉手搓的「文本 + clickable」。
            if (isMaterial3Theme()) {
                M3TextButton(onClick = ::save, enabled = saveEnabled) {
                    M3Text(text = stringResource(R.string.action_save))
                }
            } else {
                Text(
                    text = stringResource(R.string.action_save),
                    color = if (saveEnabled) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clickable(enabled = saveEnabled, onClick = ::save),
                )
            }
        },
    ) {
        item(key = "preview") {
            if (isMaterial3Theme()) {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    title = stringResource(R.string.assistant_preview),
                ) {
                    item {
                        EtaSegmentedItemContainer {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // 官方可点击 Surface，自带涟漪与按压语义。
                                Surface(
                                    onClick = pickAvatar,
                                    shape = CircleShape,
                                    modifier = Modifier.size(56.dp),
                                ) {
                                    AssistantAvatar(bitmap = avatarBitmap, size = 56.dp)
                                }
                                M3Text(
                                    text = name.trim().ifBlank { stringResource(R.string.assistant_unnamed) },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            } else {
                // MIUI X：还原改动前的「标题 item + 卡片 item」结构（同一 lazy item 内顺序堆叠，视觉等价）。
                Column {
                    EtaSectionTitle(text = stringResource(R.string.assistant_preview))
                    EtaCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AssistantAvatar(
                                bitmap = avatarBitmap,
                                size = 56.dp,
                                modifier = Modifier.clickable(onClick = pickAvatar),
                            )
                            Text(
                                text = name.trim().ifBlank { stringResource(R.string.assistant_unnamed) },
                                style = MiuixTheme.textStyles.title3,
                            )
                        }
                    }
                }
            }
        }
        item(key = "identity") {
            if (isMaterial3Theme()) {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    title = stringResource(R.string.assistant_identity),
                ) {
                    item {
                        // M3：KernelSU 式无边框输入行，直接作为分段行（与其它设置二级页一致）。
                        AssistantField(
                            value = name,
                            onValueChange = { name = it },
                            label = stringResource(R.string.assistant_name),
                        )
                    }
                }
            } else {
                // MIUI X：还原改动前的「标题 + 卡片」结构。
                Column {
                    EtaSectionTitle(text = stringResource(R.string.assistant_identity))
                    EtaCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AssistantField(
                                value = name,
                                onValueChange = { name = it },
                                label = stringResource(R.string.assistant_name),
                            )
                        }
                    }
                }
            }
        }
        item(key = "prompt") {
            if (isMaterial3Theme()) {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    title = stringResource(R.string.assistant_prompt_title),
                ) {
                    item {
                        AssistantField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            label = "",
                            singleLine = false,
                            minLines = 8,
                            maxLines = 14,
                        )
                    }
                }
            } else {
                // MIUI X：还原改动前的「标题 + 卡片」结构。
                Column {
                    EtaSectionTitle(text = stringResource(R.string.assistant_prompt_title))
                    EtaCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        AssistantField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            label = "",
                            modifier = Modifier
                                .padding(16.dp)
                                .height(240.dp),
                            singleLine = false,
                        )
                    }
                }
            }
        }
        item(key = "memory") {
            if (isMaterial3Theme()) {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    title = stringResource(R.string.ui_memory_b55ff5),
                ) {
                    item {
                        EtaSwitchRow(
                            title = stringResource(R.string.ui_enable_memory_4b69b7),
                            summary = stringResource(R.string.assistant_memory_summary),
                            checked = memoryEnabled,
                            onCheckedChange = {
                                memoryEnabled = it
                                persistAssistantToggles(nextMemory = it)
                            },
                            enabled = !saving,
                        )
                    }
                    item {
                        // MEMORY.md 超限时用支持文案（supportingContent）承载错误提示。
                        val memorySupporting: (@Composable () -> Unit)? = if (memoryOverLimit) {
                            {
                                M3Text(
                                    text = stringResource(R.string.memory_over_limit),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            null
                        }
                        AssistantField(
                            value = memoryDraft,
                            onValueChange = { memoryDraft = it },
                            label = "MEMORY.md",
                            useLabelAsPlaceholder = true,
                            singleLine = false,
                            minLines = 6,
                            maxLines = 12,
                            monospace = true,
                            isError = memoryOverLimit,
                            supportingContent = memorySupporting,
                        )
                    }
                }
            } else {
                // MIUI X：还原改动前的「标题 + 卡片」结构（开关行 + MEMORY.md 表单同卡片）。
                Column {
                    EtaSectionTitle(text = stringResource(R.string.ui_memory_b55ff5))
                    EtaCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        EtaSwitchRow(
                            title = stringResource(R.string.ui_enable_memory_4b69b7),
                            summary = stringResource(R.string.assistant_memory_summary),
                            checked = memoryEnabled,
                            onCheckedChange = {
                                memoryEnabled = it
                                persistAssistantToggles(nextMemory = it)
                            },
                            enabled = !saving,
                        )
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            AssistantField(
                                value = memoryDraft,
                                onValueChange = { memoryDraft = it },
                                label = "MEMORY.md",
                                useLabelAsPlaceholder = true,
                                singleLine = false,
                                minLines = 6,
                                maxLines = 12,
                                monospace = true,
                            )
                            if (memoryOverLimit) {
                                Text(
                                    text = stringResource(R.string.memory_over_limit),
                                    color = MiuixTheme.colorScheme.error,
                                    style = MiuixTheme.textStyles.footnote1,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "skills") {
            if (isMaterial3Theme()) {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    title = stringResource(R.string.route_skills),
                ) {
                    item {
                        EtaSegmentedItemContainer {
                            M3Text(
                                text = stringResource(R.string.assistant_skills_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                            if (installedSkills.isEmpty()) {
                                M3Text(
                                    text = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                    if (installedSkills.isNotEmpty()) {
                        installedSkills.forEach { skill ->
                            item {
                                SkillSwitchRow(
                                    skill = skill.toSwitcherUi(enabledSkillIds.contains(skill.id)),
                                    enabled = !saving,
                                    showMenu = false,
                                    onToggle = { enabled ->
                                        val next = if (enabled) {
                                            enabledSkillIds + skill.id
                                        } else {
                                            enabledSkillIds - skill.id
                                        }
                                        enabledSkillIds = next
                                        persistAssistantToggles(nextSkills = next)
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                // MIUI X：还原改动前的结构——说明文案 / 无技能文案 / 全部技能行同处一个 EtaCard。
                Column {
                    EtaSectionTitle(text = stringResource(R.string.route_skills))
                    EtaCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.assistant_skills_summary),
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        if (installedSkills.isEmpty()) {
                            Text(
                                text = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                                style = MiuixTheme.textStyles.body2,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        } else {
                            installedSkills.forEach { skill ->
                                SkillSwitchRow(
                                    skill = skill.toSwitcherUi(enabledSkillIds.contains(skill.id)),
                                    enabled = !saving,
                                    showMenu = false,
                                    onToggle = { enabled ->
                                        val next = if (enabled) {
                                            enabledSkillIds + skill.id
                                        } else {
                                            enabledSkillIds - skill.id
                                        }
                                        enabledSkillIds = next
                                        persistAssistantToggles(nextSkills = next)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    cropBitmap?.let { bitmap ->
        AvatarCropDialog(
            bitmap = bitmap,
            onCancel = { cropBitmap = null },
            onConfirm = { cropped ->
                cropBitmap = null
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        AssistantRepository.saveAvatar(assistantId, cropped)
                    }
                    avatarFileName = updated.avatarFileName
                    if (cropped !== bitmap) cropped.recycle()
                    bitmap.recycle()
                }
            },
        )
    }
}

/**
 * 助手表单输入框。
 *
 * Material 3 走项目内 KernelSU 式 [EtaSegmentedTextField]（无边框输入框嵌进分段行）；
 * MIUI X 保持原有 [TextField]。
 */
@Composable
private fun AssistantField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    useLabelAsPlaceholder: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    monospace: Boolean = false,
    isError: Boolean = false,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    if (isMaterial3Theme()) {
        EtaSegmentedTextField(
            modifier = modifier.fillMaxWidth(),
            label = if (useLabelAsPlaceholder) "" else label,
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = if (monospace) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyLarge
            },
            placeholder = if (useLabelAsPlaceholder) {
                { M3Text(text = label) }
            } else {
                null
            },
            isError = isError,
            supportingContent = supportingContent,
        )
    } else {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = modifier.fillMaxWidth(),
            singleLine = singleLine,
            useLabelAsPlaceholder = useLabelAsPlaceholder,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = if (monospace) {
                MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace)
            } else {
                MiuixTheme.textStyles.body1
            },
        )
    }
}

private fun SkillIndexEntry.toSwitcherUi(enabled: Boolean): SkillItemUi = SkillItemUi(
    id = id,
    name = name,
    description = description,
    source = source,
    enabled = enabled,
    installed = installed,
    capabilities = buildList {
        if (hasScripts) add("scripts")
        if (hasReferences) add("references")
        if (hasAssets) add("assets")
        if (hasEvals) add("evals")
    },
)
