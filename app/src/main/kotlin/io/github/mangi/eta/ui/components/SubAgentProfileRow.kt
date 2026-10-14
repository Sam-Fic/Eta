package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.agent.model.MediaReasoningSettings
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.TtsModelPickerDialog
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerProjector

/** Pickers capture both editor identity and profile binding; a changed owner/run dismisses them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubAgentProfileRow(profile: SubAgentProfile, providers: List<ProviderSetting>, enabled: Boolean = true,
    settings: Boolean = false, draftAllowed: Boolean = enabled, draftRetry: (() -> Unit)? = null) {
    val editor = LocalConversationSubAgentEditor.current
    val usable = enabled && editor?.enabled == true
    val view = LocalView.current
    val currentUsable by rememberUpdatedState(usable)
    var modelPicker by remember(editor, profile.id) { mutableStateOf(false) }
    var thinkingPicker by remember(editor, profile.id) { mutableStateOf(false) }
    var rolePicker by remember(editor, profile.id) { mutableStateOf(false) }
    var tierPicker by remember(editor, profile.id, profile.role) { mutableStateOf(false) }
    var profileDraft by remember(editor, profile.id) { mutableStateOf<SubAgentProfileDraftSession?>(null) }
    var resolutionPicker by remember(editor, profile.id, profile.providerId, profile.modelId, profile.role) { mutableStateOf(false) }
    LaunchedEffect(usable, editor) {
        if (!usable) { modelPicker = false; thinkingPicker = false; rolePicker = false; tierPicker = false; resolutionPicker = false }
    }
    LaunchedEffect(profile.role) { rolePicker = false; tierPicker = false; thinkingPicker = false; modelPicker = false; resolutionPicker = false }
    val config = remember(profile.providerId, profile.modelId, profile.role, providers) {
        val provider = providers.firstOrNull { it.id == profile.providerId && it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == profile.modelId && it.isEnabled }
        if (provider == null || model == null || model.supportsSpeechSynthesis ||
            !profile.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration)) null
        else runCatching { RuntimeConfigRepository.buildRuntimeConfig(provider, model) }.getOrNull()
    }
    val media = if (profile.isMedia && config != null) MediaReasoningSettings.resolve(config, profile.role) else null
    val canThink = config != null && (!profile.isMedia || media?.status == MediaReasoningSettings.Status.SUPPORTED)
    val effective = config?.let { SubAgentPreferences.applyReasoning(profile, it).effectiveReasoningEffort }
    val efforts = if (profile.isMedia) media?.efforts.orEmpty() else config?.reasoningCapabilities?.selectableEfforts.orEmpty()
    val thinkingLabel = when {
        config == null -> stringResource(R.string.sap_select_model)
        profile.isMedia && !canThink -> media?.label ?: stringResource(R.string.sap_endpoint_unsupported)
        profile.isMedia && effective !in efforts -> stringResource(R.string.sap_reselect_tier)
        else -> effective?.displayName ?: stringResource(R.string.sap_not_enabled)
    }
    LaunchedEffect(profile.providerId, profile.modelId) { thinkingPicker = false }
    Column(verticalArrangement = Arrangement.spacedBy(if (settings) 0.dp else 12.dp)) {
        if (settings) {
            SubAgentSettingRow(stringResource(R.string.sap_model), config?.let { it.modelDisplayName.ifBlank { it.model } }
                ?: if (profile.modelId.isBlank()) stringResource(R.string.sap_select_model) else stringResource(R.string.sap_model_unavailable),
                Icons.Rounded.ViewInAr, stringResource(R.string.spr_model_for, profile.name), enabled = usable, badge = config?.providerName,
                onClick = { if (currentUsable) { TouchHaptics.click(view); modelPicker = true } },
                onLongClick = { if (currentUsable && canThink) { TouchHaptics.longPress(view); thinkingPicker = true } },
                trailing = { SubAgentGptSpeedButton(profile, providers, usable) })
            Box(Modifier.fillMaxWidth()) {
                SubAgentSettingRow(stringResource(R.string.sap_role), profile.roleLabel, Icons.AutoMirrored.Rounded.Assignment,
                    stringResource(R.string.spr_select_role_for, profile.name), enabled = usable, dropdown = true,
                    onClick = { if (currentUsable) { TouchHaptics.click(view); rolePicker = !rolePicker } })
                Box(Modifier.align(Alignment.CenterEnd).size(40.dp)) {
                    if (usable) SubAgentDropdownMenu(rolePicker, { rolePicker = false }, Modifier.selectableGroup()) {
                        listOf("implementation" to stringResource(R.string.sap_role_impl), "review" to stringResource(R.string.sap_role_review), "image_generation" to stringResource(R.string.sap_role_image), "video_generation" to stringResource(R.string.sap_role_video)).forEach { (role, label) ->
                            SubAgentSelectionItem(label, profile.role == role) {
                                if (currentUsable) editor?.updateProfile(profile.id) { it.withRole(role) }
                                rolePicker = false
                            }
                        }
                    }
                }
            }
            if (profile.supportsTaskTier) {
                Box(Modifier.fillMaxWidth()) {
                    SubAgentSettingRow(stringResource(R.string.sap_task_tier), profile.tier?.label ?: stringResource(R.string.sap_tier_unset), Icons.Rounded.AccountTree,
                        stringResource(R.string.spr_set_tier_for, profile.name), enabled = usable, dropdown = true,
                        onClick = { if (currentUsable) { TouchHaptics.click(view); tierPicker = !tierPicker } })
                    Box(Modifier.align(Alignment.CenterEnd).size(40.dp)) {
                        if (usable) SubAgentDropdownMenu(tierPicker, { tierPicker = false }, Modifier.selectableGroup()) {
                            (listOf<SubAgentTaskTier?>(null) + SubAgentTaskTier.entries).forEach { tier ->
                                SubAgentSelectionItem(tier?.label ?: stringResource(R.string.sap_tier_unset), profile.tier == tier) {
                                    if (currentUsable) editor?.updateProfile(profile.id) { latest ->
                                        if (latest.supportsTaskTier) latest.copy(tier = tier) else latest
                                    }
                                    tierPicker = false
                                }
                            }
                        }
                    }
                }
            }
            if (profile.role == "image_generation") {
                SubAgentSettingRow(stringResource(R.string.sap_default_resolution), profile.imageResolution?.let(io.github.mangi.eta.agent.model.ImageResolutionTier::label) ?: stringResource(R.string.sap_follow_endpoint),
                    Icons.Rounded.ViewInAr, stringResource(R.string.spr_set_resolution_for, profile.name), enabled = usable && config != null,
                    onClick = { if (currentUsable && config != null) { TouchHaptics.click(view); resolutionPicker = true } })
            }
            SubAgentSettingRow(stringResource(R.string.sap_thinking), thinkingLabel,
                Icons.Rounded.AutoAwesome, stringResource(R.string.spr_adjust_thinking_for, profile.name), enabled = usable && canThink,
                onClick = { if (currentUsable && canThink) { TouchHaptics.click(view); thinkingPicker = true } })
            SubAgentParallelLimitRow(profile, config, usable)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 官方可交互 ListItem：点击、长按、涟漪、Role.Button 语义、按压形变、禁用态与内边距
                // 全部由组件提供（onLongClick 是 ListItem 独有的官方槽位，IconButton/Card/Surface 都没有）。
                // 只把这块文本包成 ListItem：GPT 速度 Chip 与「配置」IconButton 是外层 Row 的兄弟节点，
                // 天然落在点击区之外，不会被吞进同一次点按，所以不需要 combinedClickable 兜手势。
                ListItem(
                    onClick = { if (currentUsable) { TouchHaptics.click(view); modelPicker = true } },
                    onLongClick = { if (currentUsable && canThink) { TouchHaptics.longPress(view); thinkingPicker = true } },
                    onLongClickLabel = stringResource(R.string.spr_adjust_thinking_for, profile.name),
                    // 不再手写 semantics contentDescription：ListItem 内部已是
                    // semantics(mergeDescendants = true)，headline(代理名) + supportingContent(模型/接口·思考)
                    // 会自动合并成该可点节点的播报文本；再叠 contentDescription 只会造成重复朗读。
                    modifier = Modifier.weight(1f),
                    enabled = usable,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    content = {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Column {
                            Text(config?.let { it.modelDisplayName.ifBlank { it.model } } ?: if (profile.modelId.isBlank()) stringResource(R.string.sap_none) else stringResource(R.string.sap_model_unavailable),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (config != null) Text(config.providerName + stringResource(R.string.spr_thinking_suffix, thinkingLabel),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                )
                SubAgentGptSpeedButton(profile, providers, usable)
                // 官方 IconButton：容器尺寸取 IconButtonDefaults 默认值（内部已 minimumInteractiveComponentSize()），
                // 图标尺寸取 Icon 默认值，不再手写 48.dp / 20.dp。
                IconButton(enabled = usable, onClick = {
                    if (currentUsable && editor != null) {
                        SubAgentProfileDraftSession.open(editor, profile)?.let {
                            TouchHaptics.click(view)
                            profileDraft = it
                        }
                    }
                }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.spr_config_for, profile.name),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    profileDraft?.let { session ->
        SubAgentProfileConfigDialog(session, editor, providers, enabled = draftAllowed, onDismiss = {
            session.dismiss()
            if (profileDraft === session) profileDraft = null
        }, onRetry = { if (draftRetry != null) draftRetry() else session.editor.retry() })
    }
    if (usable && resolutionPicker && config != null) {
        val verifiedGrok = runCatching {
            val body = if (config.extraBodyJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(config.extraBodyJson)
            io.github.mangi.eta.agent.model.RequestBodyMerge.mergeCustomBody(body, config.customBody)
            io.github.mangi.eta.agent.model.GrokImageProfile.applies(config.baseUrl, config.model, body)
        }.getOrDefault(false)
        AlertDialog(onDismissRequest = { resolutionPicker = false }, title = { Text(stringResource(R.string.sap_default_resolution)) },
            text = { Column {
                Text(stringResource(R.string.spr_resolution_note) +
                    if (verifiedGrok) stringResource(R.string.spr_resolution_grok) else stringResource(R.string.spr_resolution_generic))
                (listOf<String?>(null) + io.github.mangi.eta.agent.model.ImageResolutionTier.values).forEach { tier ->
                    val allowed = !verifiedGrok || tier == null || tier in setOf("low", "high")
                    // 官方单选列表项：选中态、无障碍单选语义与按压反馈全部由 ListItem 提供。
                    ListItem(
                        selected = profile.imageResolution == tier,
                        enabled = usable && allowed,
                        onClick = {
                            if (currentUsable) editor?.updateProfile(profile.id) { latest ->
                                if (latest.providerId == profile.providerId && latest.modelId == profile.modelId && latest.role == "image_generation") latest.copy(imageResolution = tier) else latest
                            }
                            resolutionPicker = false
                        },
                        // 可交互/单选重载的正文参数名是 content（不是 headlineContent）。
                        content = {
                            Text(tier?.let(io.github.mangi.eta.agent.model.ImageResolutionTier::label) ?: stringResource(R.string.sap_follow_endpoint))
                        },
                    )
                }
            } }, confirmButton = { TextButton(onClick = { resolutionPicker = false }) { Text(stringResource(R.string.action_close)) } })
    }
    if (usable && modelPicker) {
        val all = AgentModelPickerProjector.project(providers, profile.providerId, profile.modelId)
        val models = all.copy(providerGroups = all.providerGroups.map { group ->
            group.copy(models = group.models.filter { profile.acceptsModel(it.supportsImageGeneration, it.supportsVideoGeneration) })
        }.filter { it.models.isNotEmpty() })
        TtsModelPickerDialog(models, true, { modelPicker = false }, { provider, model ->
            if (currentUsable) editor?.saveModel(profile.id, ModelFeatureSelection(true, provider, model), expected = profile)
            modelPicker = false
        }, stringResource(R.string.sap_select_model_for, profile.name), onClearSelection = {
            if (currentUsable) editor?.saveModel(profile.id, ModelFeatureSelection(true, "", ""), expected = profile)
            modelPicker = false
        }, highlightSelection = true)
    }
    if (usable && thinkingPicker && config != null && canThink) {
        val effort = requireNotNull(effective)
        val options = efforts
        ThinkingEffortPickerDialog(true, effort, options.ifEmpty { listOf(effort) }, { thinkingPicker = false }, { next ->
            if (currentUsable && next in options) editor?.updateProfile(profile.id) { latest ->
                if (latest.role == profile.role && latest.providerId == profile.providerId && latest.modelId == profile.modelId)
                    latest.copy(reasoning = next) else latest
            }
        }, description = stringResource(R.string.spr_thinking_desc, profile.name) +
            if (profile.isMedia) stringResource(R.string.spr_thinking_endpoint) else "")
    }
}
