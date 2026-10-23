package io.github.mangi.eta.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
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
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaPageSurface
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRadioRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
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

/** 预设分组之间的垂直间距，与兄弟设置页的分段分组节奏一致。 */
private val ProfileDialogGroupSpacing = 13.dp

/**
 * 草稿表单的分段设置行：标题为正文（content），取值为次要行（supportingContent），
 * 尾部前缀箭头。与兄弟页面的 [EtaSegmentedRow] 形态保持一致，行内不再自绘字段描边，
 * 因此窄屏下取值也不会被标题挤成逐字折行。
 *
 * 轻触反馈由 [EtaSegmentedRow] 自己触发，调用方**不要**在 onClick 里再写 TouchHaptics。
 */
@Composable
private fun SubAgentDraftSettingRow(
    label: String,
    value: String,
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    badge: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    EtaSegmentedRow(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        leadingContent = {
            // 不写死 tint：禁用时交给分段行提供的 M3 内容色统一变暗。
            Icon(icon, contentDescription = null)
        },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!badge.isNullOrBlank()) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(
                            text = badge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                )
            }
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 配置表单的二级选择页；都留在同一页面表面内，不再叠对话框。 */
private enum class ProfileDraftSubPage { MODEL, THINKING, RESOLUTION }

private data class DraftChoice<T>(val value: T, val label: String)

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
    var subPage by remember(session) { mutableStateOf<ProfileDraftSubPage?>(null) }
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
    LaunchedEffect(usable) { if (!usable) subPage = null }
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
        !canThink -> media?.let { stringResource(it.labelRes) } ?: stringResource(R.string.sap_endpoint_unsupported)
        draft.isMedia && effective !in efforts -> stringResource(R.string.sap_reselect_tier)
        else -> effective?.let { stringResource(it.labelRes) } ?: stringResource(R.string.sap_not_enabled)
    }
    val verifiedGrok = boundConfig?.let { runtime -> runCatching {
        val body = if (runtime.extraBodyJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(runtime.extraBodyJson)
        io.github.mangi.eta.agent.model.RequestBodyMerge.mergeCustomBody(body, runtime.customBody)
        io.github.mangi.eta.agent.model.GrokImageProfile.applies(runtime.baseUrl, runtime.model, body)
    }.getOrDefault(false) } ?: false
    val ready = usable && !resolutionPending && session.parallelBoundTo(boundConfig) &&
        session.name.trim().isNotBlank() && session.parallelIsValid() &&
        (session.expectedProfile != null || boundConfig != null)
    val nameHint = stringResource(R.string.sap_name)
    val dismiss = { session.dismiss(); latestDismiss() }
    // Back/outside taps cannot abandon an in-flight commit. Saved/owner invalidation still close.
    val userDismiss = { if (!session.submitting) dismiss() }
    val pageTitle = if (session.expectedProfile == null) stringResource(R.string.sap_add_profile)
        else stringResource(R.string.sap_profile_config)
    BackHandler(enabled = subPage != null) { subPage = null }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // 二级选择页会替换主表单的组合；滚动位置提在页面之上，返回时仍停在原来的设置行。
    val formScroll = rememberScrollState()
    // 二级页侧滑与 NavHost 同一套 M3E 规格：位移走 Spatial（轻微过冲），淡化走 Effects。
    // 规格须在组合期读好，transitionSpec 里不能读 CompositionLocal。
    val pageSlideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val pageFadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
        WithoutPressRipple {
            EtaPageSurface(onDismissRequest = userDismiss) {
                AnimatedContent(
                    targetState = subPage,
                    transitionSpec = {
                        val forward = targetState != null
                        // 与 NavHost 同一套几何：二级页从侧边推入，返回时沿原路退出。
                        val sign = if (forward) 1f else -1f
                        val enterSign = if (rtl) -sign else sign
                        val enter = slideInHorizontally(
                            animationSpec = pageSlideSpec,
                        ) { width -> (width * enterSign).toInt() } + fadeIn(
                            animationSpec = pageFadeSpec,
                            initialAlpha = 0.9f,
                        )
                        val exit = slideOutHorizontally(
                            animationSpec = pageSlideSpec,
                        ) { width -> (-width * enterSign / 4f).toInt() } + fadeOut(
                            animationSpec = pageFadeSpec,
                            targetAlpha = 0.9f,
                        )
                        enter togetherWith exit
                    },
                    label = "subagent-profile-page",
                ) { page ->
                    if (page == null) {
                        EtaScaffold(
                            title = pageTitle,
                            onBack = userDismiss,
                            modifier = Modifier.fillMaxSize().imePadding(),
                            bottomBar = {
                                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                                    EtaDialogActions(
                                        modifier = Modifier.navigationBarsPadding().imePadding()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        confirmText = stringResource(R.string.action_confirm),
                                        cancelText = stringResource(R.string.action_cancel),
                                        confirmEnabled = ready && !session.submitting,
                                        cancelEnabled = !session.submitting,
                                        onCancel = { userDismiss() },
                                        onConfirm = {
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
                                        },
                                    )
                                }
                            },
                        ) { padding, scrollConnection, _ ->
                            Column(
                                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                                    .nestedScroll(scrollConnection)
                                    .verticalScroll(formScroll)
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 16.dp),
                            ) {
                                EtaFormTextField(session.name, { if (usable) session.name = it.take(80) }, hint = nameHint,
                                    singleLine = true, enabled = usable,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = ProfileDialogGroupSpacing))
                                // 分段列表：启用开关与各设置行同处一组，首末项自动获得外圆角、行间留缝。
                                EtaSegmentedColumn(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = ProfileDialogGroupSpacing),
                                ) {
                                    item(key = "enable") {
                                        // 官方 Switch 只呈现状态，整行切换由分段行的点击承载。
                                        EtaSegmentedRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = usable,
                                            onClick = { session.edit { it.copy(enabled = !draft.enabled) } },
                                            trailingContent = { Switch(checked = draft.enabled, enabled = usable, onCheckedChange = null) },
                                        ) {
                                            Text(stringResource(R.string.sap_enable))
                                        }
                                    }
                                    item(key = "model") {
                                        SubAgentDraftSettingRow(stringResource(R.string.sap_model),
                                            boundConfig?.let { it.modelDisplayName.ifBlank { it.model } }
                                                ?: if (draft.modelId.isBlank()) stringResource(R.string.sap_select_model) else stringResource(R.string.sap_model_unavailable),
                                            Icons.Rounded.ViewInAr, stringResource(R.string.sap_draft_model), usable,
                                            badge = boundConfig?.providerName,
                                            onClick = { if (usable) subPage = ProfileDraftSubPage.MODEL },
                                            onLongClick = { if (usable && canThink) subPage = ProfileDraftSubPage.THINKING })
                                    }
                                    if (canEditSpeed) item(key = "speed") {
                                        SubAgentDraftSettingRow(stringResource(R.string.sap_gpt_speed), gptSpeedDisplayName(draft.gptSpeedForModel()),
                                            Icons.Rounded.Speed, stringResource(R.string.sap_draft_gpt_speed), usable, onClick = {
                                                session.edit { profile ->
                                                    if (profile.providerId != draft.providerId || profile.modelId != draft.modelId || profile.role != draft.role) profile
                                                    else profile.copy(gptSpeedByModel = profile.gptSpeedByModel +
                                                        (SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId) to profile.gptSpeedForModel().next()))
                                                }
                                            })
                                    }
                                    item(key = "role") {
                                        // 与上下文压缩页「请求接口」行同款：原位弹出锚定菜单，点选即写入草稿。
                                        val roles = listOf(
                                            "implementation" to stringResource(R.string.sap_role_impl),
                                            "review" to stringResource(R.string.sap_role_review),
                                            "image_generation" to stringResource(R.string.sap_role_image),
                                            "video_generation" to stringResource(R.string.sap_role_video),
                                        )
                                        EtaSegmentedDropdownRow(
                                            title = stringResource(R.string.sap_role),
                                            items = roles.map { it.second },
                                            selectedIndex = roles.indexOfFirst { it.first == draft.role },
                                            onItemSelected = { index ->
                                                val value: String? = roles.getOrNull(index)?.first
                                                    ?: return@EtaSegmentedDropdownRow
                                                if (session.canCommit(latestEditor, latestEnabled)) {
                                                    session.edit { profile -> profile.withRole(requireNotNull(value)) }
                                                }
                                            },
                                            icon = Icons.AutoMirrored.Rounded.Assignment,
                                            enabled = usable,
                                            description = stringResource(R.string.sap_draft_role),
                                        )
                                    }
                                    if (draft.supportsTaskTier) item(key = "tier") {
                                        val tiers = listOf<SubAgentTaskTier?>(null) + SubAgentTaskTier.entries
                                        EtaSegmentedDropdownRow(
                                            title = stringResource(R.string.sap_task_tier),
                                            items = tiers.map { tier ->
                                                tier?.let { stringResource(it.labelRes) } ?: stringResource(R.string.sap_tier_unset)
                                            },
                                            selectedIndex = tiers.indexOf(draft.tier),
                                            onItemSelected = { index ->
                                                if (index !in tiers.indices ||
                                                    !session.canCommit(latestEditor, latestEnabled)
                                                ) return@EtaSegmentedDropdownRow
                                                val tier = tiers[index]
                                                session.edit { profile ->
                                                    if (profile.supportsTaskTier) profile.copy(tier = tier) else profile
                                                }
                                            },
                                            icon = Icons.Rounded.AccountTree,
                                            enabled = usable,
                                            description = stringResource(R.string.sap_draft_tier),
                                        )
                                    }
                                    if (draft.role == "image_generation") item(key = "resolution") {
                                        SubAgentDraftSettingRow(stringResource(R.string.sap_default_resolution),
                                            draft.imageResolution?.let(ImageResolutionTier::labelRes)?.let { stringResource(it) } ?: stringResource(R.string.sap_follow_endpoint),
                                            Icons.Rounded.Image, stringResource(R.string.sap_draft_resolution), usable && boundConfig != null,
                                            onClick = { if (usable) subPage = ProfileDraftSubPage.RESOLUTION })
                                    }
                                    item(key = "thinking") {
                                        SubAgentDraftSettingRow(stringResource(R.string.sap_thinking), thinkingLabel,
                                            Icons.Rounded.AutoAwesome, stringResource(R.string.sap_draft_thinking), usable && canThink,
                                            onClick = { if (usable && canThink) subPage = ProfileDraftSubPage.THINKING })
                                    }
                                }
                                EtaFormTextField(session.parallelValue, { if (usable) session.editParallel(it) },
                                    hint = stringResource(R.string.sap_parallel_hint), singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    enabled = usable && boundConfig != null, modifier = Modifier.fillMaxWidth())
                                if (!session.parallelIsValid()) Text(stringResource(R.string.sap_parallel_invalid), color = MaterialTheme.colorScheme.error)
                                Text(stringResource(R.string.sap_parallel_note),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp))
                                session.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                                val editorState = session.editor.state
                                if (editorState is SubAgentEditorState.Error) {
                                    Text(stringResource(R.string.sap_config_error, editorState.reason), color = MaterialTheme.colorScheme.error)
                                    TextButton(enabled = !session.submitting, onClick = onRetry) { Text(stringResource(R.string.sap_retry_restore_draft)) }
                                }
                            }
                        }
                    } else {
                        when (page) {
                            ProfileDraftSubPage.MODEL -> SubAgentDraftModelPage(
                                draft = draft,
                                providers = providers,
                                usable = usable,
                                title = stringResource(R.string.sap_select_model_for, draft.name),
                                onBack = { subPage = null },
                                onCommit = { selection ->
                                    if (session.canCommit(latestEditor, latestEnabled)) {
                                        session.selectModel(selection ?: ModelFeatureSelection(true, "", ""))
                                    }
                                    subPage = null
                                },
                            )
                            ProfileDraftSubPage.THINKING -> SubAgentDraftChoicePage(
                                title = stringResource(R.string.sap_thinking),
                                description = stringResource(R.string.sap_draft_only_note) +
                                    if (draft.isMedia) stringResource(R.string.sap_tier_endpoint_note) else "",
                                choices = efforts.map { effort -> DraftChoice(effort, stringResource(effort.labelRes)) },
                                isSelected = { it == effective },
                                enabled = usable,
                                onBack = { subPage = null },
                                onSelected = { next ->
                                    if (session.canCommit(latestEditor, latestEnabled) && next in efforts) {
                                        session.edit { it.copy(reasoning = next) }
                                    }
                                    subPage = null
                                },
                            )
                            ProfileDraftSubPage.RESOLUTION -> {
                                val choices = (listOf<String?>(null) + ImageResolutionTier.values
                                    .filter { !verifiedGrok || it in setOf("low", "high") })
                                    .map { tier -> DraftChoice(tier, tier?.let(ImageResolutionTier::labelRes)?.let { stringResource(it) } ?: stringResource(R.string.sap_follow_endpoint)) }
                                SubAgentDraftChoicePage(
                                    title = stringResource(R.string.sap_default_resolution),
                                    choices = choices,
                                    isSelected = { draft.imageResolution == it },
                                    enabled = usable && boundConfig != null,
                                    onBack = { subPage = null },
                                    onSelected = { value ->
                                        if (session.canCommit(latestEditor, latestEnabled)) {
                                            session.edit { profile ->
                                                if (profile.role == "image_generation") profile.copy(imageResolution = value) else profile
                                            }
                                        }
                                        subPage = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 草稿模型选择页：搜索 + 按供应商分组单选，确认后才写入草稿。 */
@Composable
private fun SubAgentDraftModelPage(
    draft: SubAgentProfile,
    providers: List<ProviderSetting>,
    usable: Boolean,
    title: String,
    onBack: () -> Unit,
    onCommit: (ModelFeatureSelection?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var pendingProviderId by remember { mutableStateOf(draft.providerId.takeIf { it.isNotBlank() }) }
    var pendingModelId by remember { mutableStateOf(draft.modelId.takeIf { it.isNotBlank() }) }
    val all = remember(providers, draft.providerId, draft.modelId) {
        val projected = AgentModelPickerProjector.project(providers, draft.providerId, draft.modelId)
        projected.copy(providerGroups = projected.providerGroups.map { group ->
            group.copy(models = group.models.filter { draft.acceptsModel(it.supportsImageGeneration, it.supportsVideoGeneration) })
        }.filter { it.models.isNotEmpty() })
    }
    val groups = remember(all, query) {
        val needle = query.trim()
        if (needle.isEmpty()) all.providerGroups else all.providerGroups.mapNotNull { group ->
            val providerMatch = group.providerName.contains(needle, ignoreCase = true)
            val models = if (providerMatch) group.models
                else group.models.filter { it.displayName.contains(needle, ignoreCase = true) || it.id.contains(needle, ignoreCase = true) }
            if (models.isEmpty()) null else group.copy(models = models)
        }
    }
    val hasPending = pendingModelId != null
    EtaScaffoldPage(
        title = title,
        onBack = onBack,
        modifier = Modifier.imePadding(),
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                EtaDialogActions(
                    modifier = Modifier.navigationBarsPadding().imePadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    confirmText = stringResource(R.string.action_confirm),
                    cancelText = stringResource(R.string.action_cancel),
                    confirmEnabled = usable,
                    cancelEnabled = usable,
                    onCancel = onBack,
                    onConfirm = {
                        onCommit(
                            if (hasPending) ModelFeatureSelection(true, pendingProviderId.orEmpty(), pendingModelId.orEmpty())
                            else null,
                        )
                    },
                )
            }
        },
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.ui_search_model_df5586)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
            )
        }
        item(key = "none") {
            EtaSegmentedColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .padding(bottom = EtaSegmentedGroupSpacing),
            ) {
                item(key = "none-row") {
                    EtaSegmentedRadioRow(
                        title = stringResource(R.string.tts_none),
                        selected = !hasPending,
                        onClick = {
                            pendingProviderId = null
                            pendingModelId = null
                        },
                    )
                }
            }
        }
        if (groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.page_no_matching_model_found_ae7e96),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        groups.forEach { group ->
            item(key = "group-${group.providerId}") {
                EtaSegmentedColumn(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .padding(bottom = EtaSegmentedGroupSpacing),
                    title = group.providerName,
                ) {
                    group.models.forEach { model ->
                        item(key = "${model.providerId}/${model.id}") {
                            EtaSegmentedRadioRow(
                                title = model.displayName,
                                selected = pendingProviderId == model.providerId && pendingModelId == model.id,
                                onClick = {
                                    pendingProviderId = model.providerId
                                    pendingModelId = model.id
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 草稿的通用单选页：点选即写入并返回表单。 */
@Composable
private fun <T> SubAgentDraftChoicePage(
    title: String,
    choices: List<DraftChoice<T>>,
    isSelected: (T) -> Boolean,
    enabled: Boolean,
    onBack: () -> Unit,
    onSelected: (T) -> Unit,
    description: String? = null,
) {
    EtaScaffoldPage(
        title = title,
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        description?.takeIf { it.isNotBlank() }?.let { text ->
            item(key = "description") {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item(key = "choices") {
            EtaSegmentedColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .padding(bottom = EtaSegmentedGroupSpacing),
            ) {
                choices.forEach { choice ->
                    item(key = choice.label) {
                        EtaSegmentedRadioRow(
                            title = choice.label,
                            selected = isSelected(choice.value),
                            enabled = enabled,
                            onClick = { onSelected(choice.value) },
                        )
                    }
                }
            }
        }
    }
}
