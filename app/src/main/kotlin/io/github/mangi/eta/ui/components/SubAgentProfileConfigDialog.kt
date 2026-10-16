package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ImageResolutionTier
import io.github.mangi.eta.agent.model.MediaReasoningSettings
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.supportsGptSpeedBinding
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.TtsModelPickerDialog
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One opening owns one editor, application, expected profile and parallel-pool snapshot.
 * Nothing here writes preferences until commitProfileDraft; abandoning a session is zero-write. */
internal class SubAgentProfileDraftSession private constructor(
    val editor: ConversationSubAgentEditor,
    val expectedProfile: SubAgentProfile?,
    val expectedApplicationToken: String?,
    private val openedConfig: ConversationSubAgentConfig,
    initialDraft: SubAgentProfile,
) {
    val owner = editor.owner
    val openToken = Any()
    private var active = true
    var draft by mutableStateOf(initialDraft)
        private set
    var name by mutableStateOf(initialDraft.name)
    var parallelValue by mutableStateOf("")
        private set
    private var parallelEdited by mutableStateOf(false)
    private var bindingChanged = false
    private var parallelModel by mutableStateOf<SubAgentParallelModel?>(null)
    var submitting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun dismiss() { active = false }
    fun isCurrent(currentEditor: ConversationSubAgentEditor?, allowed: Boolean): Boolean {
        if (!active || !allowed || editor.isDisposed || currentEditor !== editor || currentEditor?.owner != owner) return false
        // A durability failure is recoverable in this form; never discard its draft just for Error.
        val current = (editor.state as? SubAgentEditorState.Loaded)?.config ?: return true
        return editor.canEdit() && current.presetApplicationToken == expectedApplicationToken
    }
    fun canCommit(currentEditor: ConversationSubAgentEditor?, allowed: Boolean, ticket: Any = openToken): Boolean =
        ticket === openToken && isCurrent(currentEditor, allowed) && editor.enabled

    fun edit(change: (SubAgentProfile) -> SubAgentProfile) {
        val old = draft
        draft = change(old)
        if (old.providerId != draft.providerId || old.modelId != draft.modelId) {
            bindingChanged = true
            resetParallel()
        }
    }
    fun selectModel(selection: ModelFeatureSelection) {
        // This read-only API restores LAST CONFIRMED complete settings. Never cache local A/B edits.
        edit { editor.changeProfileModel(it, selection) }
    }
    private fun resetParallel() {
        parallelEdited = false
        parallelModel = null
        parallelValue = editor.rememberedParallelLimit(draft)?.toString().orEmpty()
    }
    fun bindParallel(config: AgentModelClient.ModelConfig?) {
        val next = config?.takeIf { it.providerId.isNotBlank() && it.model.isNotBlank() }
            ?.let { SubAgentParallelModel(it.providerId, it.model) }
        if (next != parallelModel) {
            parallelModel = next
            val originalBinding = expectedProfile != null && !bindingChanged &&
                draft.providerId == expectedProfile.providerId && draft.modelId == expectedProfile.modelId
            // Existing unchanged bindings show the actual owner pool. New/rebound bindings restore
            // confirmed memory as a pending pool write; null change must mean truly untouched.
            val restored = if (next != null && !originalBinding) editor.rememberedParallelLimit(draft) else null
            parallelEdited = restored != null
            parallelValue = if (next == null) "" else
                (restored ?: openedConfig.parallelLimit(next)).toString()
        }
    }
    fun editParallel(value: String) { parallelValue = value; parallelEdited = true }
    fun parallelChange(): SubAgentParallelLimitChange? {
        if (!parallelEdited) return null
        val model = parallelModel ?: return null
        val number = parallelValue.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return SubAgentParallelLimitChange(model, number, openedConfig.parallelLimit(model))
    }
    fun parallelBoundTo(config: AgentModelClient.ModelConfig?): Boolean = config == null ||
        parallelModel == SubAgentParallelModel(config.providerId, config.model)
    fun parallelIsValid(): Boolean = !parallelEdited ||
        (parallelModel != null && parallelValue.trim().toIntOrNull()?.let { it >= 0 } == true)

    companion object {
        fun open(editor: ConversationSubAgentEditor, expectedProfile: SubAgentProfile? = null): SubAgentProfileDraftSession? {
            if (!editor.enabled) return null
            val config = (editor.state as? SubAgentEditorState.Loaded)?.config ?: return null
            if (expectedProfile != null && config.profiles.singleOrNull { it.id == expectedProfile.id } != expectedProfile) return null
            return SubAgentProfileDraftSession(editor, expectedProfile, config.presetApplicationToken,
                config.detached(), expectedProfile ?: editor.newProfileDraft())
        }
    }
}

/** Read-only resolution outside any editor transaction. No unsupported role gets a replacement model. */
internal fun resolveSubAgentProfileConfig(profile: SubAgentProfile, providers: List<ProviderSetting>): AgentModelClient.ModelConfig? {
    val provider = providers.singleOrNull { it.id == profile.providerId && it.isEnabled } ?: return null
    val model = provider.models.singleOrNull { it.id == profile.modelId && it.isEnabled } ?: return null
    if (model.supportsSpeechSynthesis || !profile.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration)) return null
    return runCatching { RuntimeConfigRepository.buildRuntimeConfig(provider, model) }.getOrNull()
}

private class SubAgentResolvedDraftBinding(val providerId: String, val modelId: String, val role: String,
    val config: AgentModelClient.ModelConfig?)

private fun Modifier.profileDialogScrollableBody(): Modifier = layout { measurable, constraints ->
    // Reserve the title/actions, including in landscape; only the body scrolls.
    val maximum = if (constraints.hasBoundedHeight) (constraints.maxHeight - 144.dp.roundToPx())
        .coerceAtLeast(1).coerceAtMost(520.dp.roundToPx()) else 520.dp.roundToPx()
    val placeable = measurable.measure(constraints.copy(minHeight = constraints.minHeight.coerceAtMost(maximum), maxHeight = maximum))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentProfileConfigDialog(
    session: SubAgentProfileDraftSession,
    currentEditor: ConversationSubAgentEditor?,
    providers: List<ProviderSetting>,
    enabled: Boolean = true,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = { session.editor.retry() },
) {
    val latestEditor by rememberUpdatedState(currentEditor)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val current = session.isCurrent(currentEditor, enabled)
    DisposableEffect(session) { onDispose { session.dismiss() } }
    LaunchedEffect(session, current) {
        if (!current) { session.dismiss(); latestDismiss() }
    }
    if (!current) return
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val draft = session.draft
    val usable = session.canCommit(currentEditor, enabled) && !session.submitting
    var modelPicker by remember(session) { mutableStateOf(false) }
    var thinkingPicker by remember(session) { mutableStateOf(false) }
    var choices by remember(session) { mutableStateOf<String?>(null) }
    // Resolve outside the write lock. A produceState result must carry its original selection:
    // API-model aliases must not make a stale selection look like the current draft binding.
    val resolved by produceState<SubAgentResolvedDraftBinding?>(null, draft.providerId, draft.modelId, draft.role, providers) {
        value = SubAgentResolvedDraftBinding(draft.providerId, draft.modelId, draft.role,
            resolveSubAgentProfileConfig(draft, providers))
    }
    val boundConfig = resolved?.takeIf { it.providerId == draft.providerId &&
        it.modelId == draft.modelId && it.role == draft.role }?.config
    val resolutionPending = draft.providerId.isNotBlank() && draft.modelId.isNotBlank() &&
        (resolved == null || resolved?.providerId != draft.providerId || resolved?.modelId != draft.modelId || resolved?.role != draft.role)
    LaunchedEffect(session, draft.providerId, draft.modelId, draft.role, resolutionPending, boundConfig?.providerId, boundConfig?.model) {
        // Do not discard a manual/pending pool edit during a transient lookup of the same binding.
        if (!resolutionPending) session.bindParallel(boundConfig)
    }
    LaunchedEffect(usable) { if (!usable) { modelPicker = false; thinkingPicker = false; choices = null } }
    // Require the resolved role-compatible binding AND the selected record's actual GPT capability.
    // The preference key is the selection ID, never boundConfig.model (an API alias).
    val speedProvider = providers.singleOrNull { it.id == draft.providerId && it.isEnabled }
    val speedModel = speedProvider?.models?.singleOrNull { it.id == draft.modelId && it.isEnabled }
    val canEditSpeed = boundConfig != null && supportsGptSpeedBinding(speedProvider, speedModel)
    val media = if (draft.isMedia && boundConfig != null) MediaReasoningSettings.resolve(boundConfig, draft.role) else null
    val canThink = boundConfig != null && (!draft.isMedia || media?.status == MediaReasoningSettings.Status.SUPPORTED)
    val effective = boundConfig?.let { SubAgentPreferences.applyReasoning(draft, it).effectiveReasoningEffort }
    val efforts = if (draft.isMedia) media?.efforts.orEmpty() else boundConfig?.reasoningCapabilities?.selectableEfforts.orEmpty()
    val thinkingLabel = when {
        boundConfig == null -> stringResource(R.string.sap_no_valid_model)
        !canThink -> media?.label ?: stringResource(R.string.sap_endpoint_unsupported)
        draft.isMedia && effective !in efforts -> stringResource(R.string.sap_reselect_tier)
        else -> effective?.displayName ?: stringResource(R.string.sap_not_enabled)
    }
    val verifiedGrok = boundConfig?.let { runtime -> runCatching {
        val body = if (runtime.extraBodyJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(runtime.extraBodyJson)
        io.github.mangi.eta.agent.model.RequestBodyMerge.mergeCustomBody(body, runtime.customBody)
        io.github.mangi.eta.agent.model.GrokImageProfile.applies(runtime.baseUrl, runtime.model, body)
    }.getOrDefault(false) } ?: false
    val ready = usable && !resolutionPending && session.parallelBoundTo(boundConfig) &&
        session.name.trim().isNotBlank() && session.parallelIsValid() &&
        (session.expectedProfile != null || boundConfig != null)
    val interact: (() -> Unit) -> Unit = { action ->
        if (session.canCommit(latestEditor, latestEnabled) && !session.submitting) { TouchHaptics.click(view); action() }
    }
    val dismiss = { session.dismiss(); latestDismiss() }
    // Back/outside taps cannot abandon an in-flight commit. Saved/owner invalidation still close.
    val userDismiss = { if (!session.submitting) dismiss() }
    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
        WithoutPressRipple {
            // This form is also opened from a Compose Dialog. OverlayDialog registers
            // content in a Scaffold host, which can be absent or behind that parent window.
            // EtaDialog 在自己的模态窗口里呈现同一份表单。
            EtaDialog(show = true, title = if (session.expectedProfile == null) stringResource(R.string.sap_add_profile) else stringResource(R.string.sap_profile_config),
                onDismissRequest = userDismiss) {
                Column(Modifier.fillMaxWidth().profileDialogScrollableBody().verticalScroll(rememberScrollState())) {
                    EtaFormTextField(session.name, { if (usable) session.name = it.take(80) }, hint = stringResource(R.string.sap_name),
                        singleLine = true, enabled = usable, modifier = Modifier.fillMaxWidth())
                    // 官方 ListItem 行承载整行点按与开关语义；官方 Switch 只呈现状态，点击统一由行处理。
                    ListItem(
                        trailingContent = { Switch(checked = draft.enabled, enabled = usable, onCheckedChange = null) },
                        modifier = Modifier.toggleable(
                            value = draft.enabled,
                            enabled = usable,
                            role = Role.Switch,
                            onValueChange = { active -> interact { session.edit { it.copy(enabled = active) } } },
                        ),
                    ) {
                        Text(stringResource(R.string.sap_enable))
                    }
                    SubAgentSettingRow(stringResource(R.string.sap_model), boundConfig?.let { it.modelDisplayName.ifBlank { it.model } }
                        ?: if (draft.modelId.isBlank()) stringResource(R.string.sap_select_model) else stringResource(R.string.sap_model_unavailable), Icons.Rounded.ViewInAr,
                        stringResource(R.string.sap_draft_model), usable, badge = boundConfig?.providerName,
                        onClick = { interact { modelPicker = true } },
                        onLongClick = { if (usable && canThink) { thinkingPicker = true } })
                    if (canEditSpeed) SubAgentSettingRow(stringResource(R.string.sap_gpt_speed), gptSpeedDisplayName(draft.gptSpeedForModel()),
                        Icons.Rounded.Speed, stringResource(R.string.sap_draft_gpt_speed), usable, onClick = { interact {
                            session.edit { profile ->
                                if (profile.providerId != draft.providerId || profile.modelId != draft.modelId || profile.role != draft.role) profile
                                else profile.copy(gptSpeedByModel = profile.gptSpeedByModel +
                                    (SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId) to profile.gptSpeedForModel().next()))
                            }
                        } })
                    SubAgentSettingRow(stringResource(R.string.sap_role), draft.roleLabel, Icons.AutoMirrored.Rounded.Assignment, stringResource(R.string.sap_draft_role), usable,
                        onClick = { interact { choices = "role" } })
                    if (draft.supportsTaskTier) SubAgentSettingRow(stringResource(R.string.sap_task_tier), draft.tier?.label ?: stringResource(R.string.sap_tier_unset),
                        Icons.Rounded.AccountTree, stringResource(R.string.sap_draft_tier), usable, onClick = { interact { choices = "tier" } })
                    SubAgentSettingRow(stringResource(R.string.sap_thinking), thinkingLabel, Icons.Rounded.AutoAwesome, stringResource(R.string.sap_draft_thinking),
                        usable && canThink, onClick = { interact { thinkingPicker = true } })
                    EtaFormTextField(session.parallelValue, { if (usable) session.editParallel(it) },
                        hint = stringResource(R.string.sap_parallel_hint), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = usable && boundConfig != null, modifier = Modifier.fillMaxWidth())
                    if (!session.parallelIsValid()) Text(stringResource(R.string.sap_parallel_invalid), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.sap_parallel_note),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp))
                    if (draft.role == "image_generation") SubAgentSettingRow(stringResource(R.string.sap_default_resolution),
                        draft.imageResolution?.let(ImageResolutionTier::label) ?: stringResource(R.string.sap_follow_endpoint), Icons.Rounded.Image,
                        stringResource(R.string.sap_draft_resolution), usable && boundConfig != null, onClick = { interact { choices = "resolution" } })
                    session.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    val editorState = session.editor.state
                    if (editorState is SubAgentEditorState.Error) {
                        Text(stringResource(R.string.sap_config_error, editorState.reason), color = MaterialTheme.colorScheme.error)
                        TextButton(enabled = !session.submitting, onClick = onRetry) { Text(stringResource(R.string.sap_retry_restore_draft)) }
                    }
                }
                SubAgentDraftDialogActions(ready, !session.submitting, userDismiss, onConfirm = {
                    if (ready && !session.submitting && session.canCommit(latestEditor, latestEnabled)) {
                        // Snapshot every argument before suspension; callbacks never retarget another opening.
                        val capturedDraft = session.draft.copy(name = session.name.trim())
                        val capturedTicket = session.openToken
                        val capturedExpected = session.expectedProfile
                        val capturedApplication = session.expectedApplicationToken
                        val capturedParallel = session.parallelChange()
                        session.submitting = true
                        session.error = null
                        scope.launch {
                            try {
                                val result = session.editor.commitProfileDraft(capturedDraft, capturedExpected,
                                    capturedApplication, capturedParallel, canCommit = {
                                        session.canCommit(latestEditor, latestEnabled, capturedTicket)
                                    })
                                if (result is ConversationSubAgentPreferences.WriteResult.Saved &&
                                    session.isCurrent(latestEditor, latestEnabled)) {
                                    TouchHaptics.click(view)
                                    dismiss()
                                } else if (session.isCurrent(latestEditor, latestEnabled)) {
                                    session.error = context.getString(R.string.sap_not_saved)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                              catch (_: Exception) { session.error = context.getString(R.string.sap_save_failed) }
                            finally { session.submitting = false }
                        }
                    }
                })
            }
            if (usable && modelPicker) {
                val all = AgentModelPickerProjector.project(providers, draft.providerId, draft.modelId)
                val models = all.copy(providerGroups = all.providerGroups.map { group ->
                    group.copy(models = group.models.filter { draft.acceptsModel(it.supportsImageGeneration, it.supportsVideoGeneration) })
                }.filter { it.models.isNotEmpty() })
                TtsModelPickerDialog(models, true, { modelPicker = false }, { provider, model ->
                    if (session.canCommit(latestEditor, latestEnabled)) session.selectModel(ModelFeatureSelection(true, provider, model))
                    modelPicker = false
                }, stringResource(R.string.sap_select_model_for, draft.name), onClearSelection = {
                    if (session.canCommit(latestEditor, latestEnabled)) session.selectModel(ModelFeatureSelection(true, "", ""))
                    modelPicker = false
                }, highlightSelection = true)
            }
            if (usable && thinkingPicker && canThink && effective != null) {
                val binding = draft
                ThinkingEffortPickerDialog(true, effective, efforts.ifEmpty { listOf(effective) }, { thinkingPicker = false }, { next ->
                    if (session.canCommit(latestEditor, latestEnabled) && next in efforts &&
                        session.draft.providerId == binding.providerId && session.draft.modelId == binding.modelId && session.draft.role == binding.role)
                        session.edit { it.copy(reasoning = next) }
                }, description = stringResource(R.string.sap_draft_only_note) + if (draft.isMedia) stringResource(R.string.sap_tier_endpoint_note) else "")
            }
            if (usable) choices?.let { field ->
                val options: List<Pair<String?, String>> = when (field) {
                    "role" -> listOf("implementation" to stringResource(R.string.sap_role_impl), "review" to stringResource(R.string.sap_role_review), "image_generation" to stringResource(R.string.sap_role_image), "video_generation" to stringResource(R.string.sap_role_video))
                    "tier" -> listOf(null to stringResource(R.string.sap_tier_unset)) + SubAgentTaskTier.entries.map { it.wireValue to it.label }
                    else -> listOf(null to stringResource(R.string.sap_follow_endpoint)) + ImageResolutionTier.values.filter { !verifiedGrok || it in setOf("low", "high") }.map { it to ImageResolutionTier.label(it) }
                }
                EtaDialog(show = true, title = when (field) { "role" -> stringResource(R.string.sap_role); "tier" -> stringResource(R.string.sap_task_tier); else -> stringResource(R.string.sap_default_resolution) },
                    onDismissRequest = { choices = null }) {
                    Column(Modifier.fillMaxWidth().profileDialogScrollableBody().verticalScroll(rememberScrollState())) {
                        options.forEach { (value, label) ->
                            // 官方单选列表项：选中态与无障碍单选语义都由 ListItem 提供。
                            ListItem(
                                selected = when (field) {
                                    "role" -> draft.role == value
                                    "tier" -> draft.tier == SubAgentTaskTier.fromWireValue(value.orEmpty())
                                    else -> draft.imageResolution == value
                                },
                                onClick = { interact {
                                    session.edit { profile -> when (field) {
                                        "role" -> profile.withRole(requireNotNull(value))
                                        "tier" -> if (profile.supportsTaskTier) profile.copy(tier = SubAgentTaskTier.fromWireValue(value.orEmpty())) else profile
                                        else -> if (profile.role == "image_generation") profile.copy(imageResolution = value) else profile
                                    } }
                                    choices = null
                                } },
                                // 可交互/单选重载的正文参数名是 content（不是 headlineContent）。
                                content = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 对话框操作区：等宽、8dp 间距、左轻（TextButton）右实（Button）。
 * 与全局助手不同，被拒绝或异步失效的提交绝不触发轻触反馈。 */
@Composable
private fun SubAgentDraftDialogActions(confirmEnabled: Boolean, cancelEnabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 官方 TextButton（左，轻）+ FilledButton（右，实心），与参考的对话框操作区一致。
        TextButton(
            onClick = { TouchHaptics.click(view); onCancel() },
            modifier = Modifier.weight(1f),
            enabled = cancelEnabled,
        ) { Text(stringResource(R.string.action_cancel)) }
        Button(
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            enabled = confirmEnabled,
        ) { Text(stringResource(R.string.action_confirm)) }
    }
}
