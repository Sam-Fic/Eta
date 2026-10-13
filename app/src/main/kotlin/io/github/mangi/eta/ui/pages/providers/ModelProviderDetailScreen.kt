@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.agent.model.oauth.OpenAiCodexOAuth
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthMode
import io.github.mangi.eta.data.model.usesOAuth
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.agent.voice.tts.CloudSpeechSynthesizer
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackFailure
import io.github.mangi.eta.data.model.withId
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixPageBottomSpacer
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text as M3Text

@Composable
internal fun ModelProviderDetailScreen(
    providerId: String? = null,
    newType: NewProviderType? = null,
    authMode: String = ProviderAuthMode.DEFAULT,
    onBack: () -> Unit,
    currentModelId: String? = null,
    onSelectCurrentModel: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    var createdId by remember { mutableStateOf<String?>(null) }
    val effectiveId = providerId ?: createdId
    val provider = remember(providers, effectiveId) {
        effectiveId?.let { id -> providers.firstOrNull { it.id == id } }
    }
    val draft = remember(newType, authMode) {
        when (newType) {
            NewProviderType.OpenAiCompatible -> if (ProviderAuthMode.isOAuth(authMode)) {
                CustomProviderSetting(
                    id = ProviderRepository.newId(),
                    name = OpenAiCodexOAuth.DEFAULT_NAME,
                    baseUrl = OpenAiCodexOAuth.CODEX_BASE_URL,
                    endpointMode = OpenAiCodexOAuth.defaultEndpointMode(),
                    authMode = ProviderAuthMode.OAUTH,
                )
            } else {
                CustomProviderSetting(
                    id = "",
                    name = "",
                    baseUrl = "",
                    endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
                )
            }
            NewProviderType.Anthropic -> AnthropicProviderSetting(
                id = "",
                name = "",
                baseUrl = "https://api.anthropic.com",
            )
            NewProviderType.DoubaoSpeech -> CustomProviderSetting(
                id = "",
                name = "豆包语音",
                baseUrl = "https://openspeech.bytedance.com",
                sourceType = ProviderSourceTypes.DOUBAO_SPEECH,
                models = SpeechSynthesisModels.catalogModels(
                    CustomProviderSetting(
                        id = "draft",
                        name = "豆包语音",
                        baseUrl = "https://openspeech.bytedance.com",
                        sourceType = ProviderSourceTypes.DOUBAO_SPEECH,
                    ),
                ),
            )
            // Restore old navigation state through the ordinary provider form.
            NewProviderType.CompatibleSpeech -> CustomProviderSetting(
                id = "", name = "语音合成", baseUrl = "",
            )
            null -> null
        }
    }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    if (provider == null && draft == null) {
        EtaScaffoldPage(
            title = stringResource(R.string.route_provider_details),
            onBack = onBack,
        ) {
            item(key = "missing_provider") {
                Column(
                    modifier = Modifier.fillParentMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (isMaterial3Theme()) {
                        M3Text(
                            text = stringResource(R.string.ui_provider_does_not_exist_83cee6),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        Text(text = stringResource(R.string.ui_provider_does_not_exist_83cee6))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ProviderPrimaryAction(text = stringResource(R.string.ui_return_11d024), onClick = onBack)
                }
            }
        }
        return
    }

    val initial = provider ?: draft!!
    val isNew = provider == null
    var currentTab by remember { mutableIntStateOf(0) }
    var configDraft by rememberSaveable(
        initial.id,
        stateSaver = ProviderConfigDraftSaver,
    ) {
        mutableStateOf(ProviderConfigDraft.from(initial))
    }
    val title = if (isNew) context.getString(R.string.page_create_new_provider_36cab9) else initial.name

    EtaScaffold(title = title, onBack = onBack) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            if (!isNew) {
                ProviderTabs(
                    tabs = listOf(
                        context.getString(R.string.page_configuration_d7d7ce),
                        context.getString(R.string.page_model_98fd0c),
                    ),
                    selectedIndex = currentTab,
                    onSelect = {
                        TouchHaptics.click(view)
                        currentTab = it
                    },
                    modifier = Modifier.padding(
                        horizontal = sidePadding + 12.dp,
                        vertical = 8.dp,
                    ),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    0 -> ProviderConfigTab(
                        provider = initial,
                        draft = configDraft,
                        onDraftChange = { configDraft = it },
                        scope = scope,
                        isNew = isNew,
                        scrollConnection = scrollBehavior,
                        contentSidePadding = sidePadding,
                        onCreated = { id -> createdId = id },
                        onDeleted = onBack,
                    )
                    1 -> if (!isNew) {
                        ProviderModelsTab(
                            provider = initial,
                            scope = scope,
                            scrollConnection = scrollBehavior,
                            contentSidePadding = sidePadding,
                            currentModelId = currentModelId,
                            onSelectCurrent = onSelectCurrentModel,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 配置 / 模型分段切换。
 *
 * Material 3 走官方单选分段按钮（形状取官方 [SegmentedButtonDefaults]）；
 * MIUI X 保持原有 [TabRow]。
 */
@Composable
private fun ProviderTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isMaterial3Theme()) {
        SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                ) {
                    M3Text(text = label)
                }
            }
        }
    } else {
        TabRow(
            tabs = tabs,
            selectedTabIndex = selectedIndex,
            onTabSelected = onSelect,
            modifier = modifier,
        )
    }
}

@Composable
private fun ProviderConfigTab(
    provider: ProviderSetting,
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    scope: CoroutineScope,
    isNew: Boolean,
    scrollConnection: NestedScrollConnection,
    contentSidePadding: Dp,
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var headersExpanded by rememberSaveable { mutableStateOf(false) }
    var bodiesExpanded by rememberSaveable { mutableStateOf(false) }
    val bodiesResult = remember(draft.bodies) { parseProviderBodies(draft.bodies) }
    var gatewayExpanded by rememberSaveable { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var creationCommitted by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // 低层 Scaffold 只负责把顶栏 Insets 传给调用方，输入法 Insets 由列表自行消费。
            .imePadding()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollConnection),
        contentPadding = PaddingValues(
            start = contentSidePadding,
            end = contentSidePadding,
        ),
        overscrollEffect = null,
    ) {
        item(key = "connection") {
            ProviderSection(title = stringResource(R.string.ui_connection_configuration_7d057b)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ProviderField(
                        value = draft.name,
                        onValueChange = { onDraftChange(draft.copy(name = it)) },
                        label = stringResource(R.string.ui_name_1be7ae),
                    )
                    ProviderField(
                        value = draft.baseUrl,
                        onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
                        label = "Base URL",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (ProviderAuthMode.isOAuth(draft.authMode)) {
                        ProviderOAuthLoginRow(
                            providerId = provider.id,
                            signedIn = draft.apiKey.isNotBlank(),
                            enabled = !isWorking,
                            onToken = { token -> onDraftChange(draft.copy(apiKey = token)) },
                            onBusy = { isWorking = it },
                            onStatus = { status = it },
                        )
                    } else {
                        ProviderField(
                            value = draft.apiKey,
                            onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
                            label = "API Key",
                            modifier = Modifier.padding(top = 12.dp),
                            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                ProviderIconAction(
                                    icon = if (apiKeyVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    contentDescription = if (apiKeyVisible) context.getString(R.string.page_hide_bb0e7e) else context.getString(R.string.page_show_71b677),
                                    onClick = {
                                        TouchHaptics.click(view)
                                        apiKeyVisible = !apiKeyVisible
                                    },
                                )
                            },
                        )
                    }
                    if (provider is AnthropicProviderSetting) {
                        ProviderField(
                            value = draft.anthropicVersion,
                            onValueChange = { onDraftChange(draft.copy(anthropicVersion = it)) },
                            label = "anthropic-version",
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                val speechOnly = SpeechSynthesisModels.isSpeechOnlyProvider(provider)
                if (provider !is AnthropicProviderSetting && !speechOnly) {
                    ProviderDivider()
                    ProviderDropdownRow(
                        title = stringResource(R.string.ui_endpoint_mode_3c8546),
                        summary = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
                            context.getString(R.string.page_using_typed_items_with_semantic_streaming_events_f9c906)
                        } else {
                            context.getString(R.string.page_use_standard_chat_completions_ee4b1a)
                        },
                        items = listOf("Chat Completions API", "Responses API"),
                        selectedIndex = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) 1 else 0,
                        onSelectedIndexChange = { selectedIndex ->
                            onDraftChange(
                                draft.copy(
                                    endpointMode = if (selectedIndex == 1) {
                                        OpenAiEndpointMode.RESPONSES
                                    } else {
                                        OpenAiEndpointMode.CHAT_COMPLETIONS
                                    },
                                ),
                            )
                        },
                    )
                    if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
                        EtaSwitchRow(
                            title = stringResource(R.string.ui_server_side_web_search_ddb8e0),
                            summary = stringResource(R.string.ui_allows_the_model_to_call_web_searches_provided_by_th_2f752f),
                            checked = draft.hostedWebSearchEnabled,
                            onCheckedChange = {
                                onDraftChange(draft.copy(hostedWebSearchEnabled = it))
                            },
                        )
                    }
                }
                ProviderDivider()
                ProviderClickableRow(
                    title = stringResource(R.string.ui_test_connection_10b7d8),
                    summary = testStatus,
                    enabled = !isWorking,
                    onClick = {
                        TouchHaptics.click(view)
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            testStatus = context.getString(R.string.provider_error, validationError)
                            return@ProviderClickableRow
                        }
                        scope.launch {
                            isWorking = true
                            testStatus = context.getString(R.string.page_testing_f43705)
                            try {
                                testStatus = testConnection(
                                    context,
                                    buildUpdatedProvider(
                                        source = provider,
                                        name = draft.name,
                                        baseUrl = draft.baseUrl,
                                        apiKey = draft.apiKey,
                                        authMode = draft.authMode,
                                        isEnabled = draft.isEnabled,
                                        endpointMode = draft.endpointMode,
                                        responsesStripReasoningStatus = draft.responsesStripReasoningStatus,
                                        hostedWebSearchEnabled = draft.hostedWebSearchEnabled,
                                        anthropicVersion = draft.anthropicVersion,
                                        customHeaders = draft.headers.map { it.header },
                                        customBody = bodiesResult.getOrThrow(),
                                        balanceOption = draft.balanceOption,
                                        sessionGatewayJson = draft.encodedSessionGateway(),
                                    )
                                )
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                )
            }
        }

        providerSessionGateway(
            modelPattern = draft.sessionModelPattern,
            pathPattern = draft.sessionPathPattern,
            retention = draft.sessionRetention,
            keySource = draft.sessionKeySource,
            keyField = draft.sessionKeyField,
            expanded = gatewayExpanded,
            onExpandedChange = { gatewayExpanded = it },
            onChange = { model, path, retention, source, field ->
                onDraftChange(draft.copy(
                    sessionModelPattern = model,
                    sessionPathPattern = path,
                    sessionRetention = retention,
                    sessionKeySource = source,
                    sessionKeyField = field,
                ))
            },
        )

        providerHeadersEditor(
            headers = draft.headers,
            expanded = headersExpanded,
            onExpandedChange = { headersExpanded = it },
            onHeadersChange = { onDraftChange(draft.copy(headers = it)) },
        )

        providerBodiesEditor(
            bodies = draft.bodies,
            expanded = bodiesExpanded,
            onExpandedChange = { bodiesExpanded = it },
            onBodiesChange = { onDraftChange(draft.copy(bodies = it)) },
        )

        item(key = "preferences_and_prompt") {
            ProviderSection(title = stringResource(R.string.ui_preferences_and_strategies_2abd3c)) {
                EtaSwitchRow(
                    title = stringResource(R.string.ui_enable_this_provider_683a76),
                    checked = draft.isEnabled,
                    onCheckedChange = { onDraftChange(draft.copy(isEnabled = it)) },
                )
            }
        }

        item(key = "balance_option") {
            ProviderSection(title = stringResource(R.string.ui_balance_section_title)) {
                ProviderBalanceOptionFields(
                    balanceOption = draft.balanceOption,
                    onBalanceOptionChange = { onDraftChange(draft.copy(balanceOption = it)) },
                    provider = buildUpdatedProvider(
                        source = provider,
                        name = draft.name,
                        baseUrl = draft.baseUrl,
                        apiKey = draft.apiKey,
                        authMode = draft.authMode,
                        isEnabled = draft.isEnabled,
                        endpointMode = draft.endpointMode,
                        responsesStripReasoningStatus = draft.responsesStripReasoningStatus,
                        hostedWebSearchEnabled = draft.hostedWebSearchEnabled,
                        anthropicVersion = draft.anthropicVersion,
                        customHeaders = draft.headers.map { it.header },
                        customBody = bodiesResult.getOrElse { provider.customBody },
                        balanceOption = draft.balanceOption,
                        sessionGatewayJson = draft.encodedSessionGateway(),
                    ),
                )
            }
        }

        item(key = "actions") {
            // 操作分层：主按钮实心独占，次要操作降级为文字按钮，与弹窗按钮语言一致
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val onSavePrimary: () -> Unit = {
                    TouchHaptics.click(view)
                    val validationError = validateProviderDraft(context, draft)
                        ?: if (ProviderAuthMode.isOAuth(draft.authMode) && draft.apiKey.isBlank()) {
                            context.getString(R.string.provider_oauth_need_sign_in)
                        } else {
                            null
                        }
                    if (validationError != null) {
                        status = context.getString(R.string.provider_error, validationError)
                    } else {
                        scope.launch {
                        isWorking = true
                        val built = buildUpdatedProvider(
                            source = provider,
                            name = draft.name,
                            baseUrl = draft.baseUrl,
                            apiKey = draft.apiKey,
                            authMode = draft.authMode,
                            isEnabled = draft.isEnabled,
                            endpointMode = draft.endpointMode,
                            responsesStripReasoningStatus = draft.responsesStripReasoningStatus,
                            hostedWebSearchEnabled = draft.hostedWebSearchEnabled,
                            anthropicVersion = draft.anthropicVersion,
                            customHeaders = draft.headers.map { it.header },
                            customBody = bodiesResult.getOrThrow(),
                            balanceOption = draft.balanceOption,
                            sessionGatewayJson = draft.encodedSessionGateway(),
                        )
                        try {
                            if (isNew) {
                                val newId = provider.id.ifBlank { ProviderRepository.newId() }
                                val toSave = built.withId(newId).let { saved ->
                                    when {
                                        saved.usesOAuth && saved.models.isEmpty() ->
                                            saved.withModels(OpenAiCodexOAuth.defaultModels())
                                        SpeechSynthesisModels.isSpeechOnlyProvider(saved) && saved.models.none { SpeechSynthesisModels.matches(it) } ->
                                            saved.withModels(SpeechSynthesisModels.mergeCatalog(saved))
                                        else -> saved
                                    }
                                }
                                val added = ProviderRepository.addProvider(toSave)
                                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                status = context.getString(R.string.capability_provider_created)
                                creationCommitted = true
                                onCreated(added.id)
                            } else {
                                ProviderRepository.updateProvider(built)
                                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                status = context.getString(R.string.capability_provider_saved)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            status = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_save_failed),
                            )
                        } finally {
                            isWorking = false
                        }
                        }
                    }
                }
                ProviderPrimaryAction(
                    text = when {
                        isWorking -> context.getString(R.string.page_saving_d70d42)
                        creationCommitted -> context.getString(R.string.page_created_62cfc5)
                        isNew -> context.getString(R.string.page_create_fcbd09)
                        else -> context.getString(R.string.page_save_configuration_817af1)
                    },
                    onClick = onSavePrimary,
                    enabled = !isWorking && !creationCommitted,
                    modifier = Modifier.fillMaxWidth(),
                )
                status?.let { message ->
                    if (isMaterial3Theme()) {
                        M3Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.startsWith(context.getString(R.string.page_fail_3e3c80))) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        Text(
                            text = message,
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (message.startsWith(context.getString(R.string.page_fail_3e3c80))) StatusError else StatusSuccess,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        if (!isNew) {
            item(key = "danger_zone") {
                val onDangerClick = {
                    TouchHaptics.click(view)
                    if (provider.isBuiltIn) showResetDialog = true else showDeleteDialog = true
                }
                if (isMaterial3Theme()) {
                    // 官方可点击 Card（自带涟漪与按压形变），破坏性文案取官方 error 角色色。
                    M3Card(
                        onClick = onDangerClick,
                        enabled = !isWorking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            M3Text(
                                text = if (provider.isBuiltIn) {
                                    context.getString(R.string.page_reset_built_in_configuration_35b6ec)
                                } else {
                                    context.getString(R.string.page_remove_provider_9f848f)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else {
                    top.yukonga.miuix.kmp.basic.Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp),
                        showIndication = true,
                        onClick = if (isWorking) null else onDangerClick,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (provider.isBuiltIn) {
                                    context.getString(R.string.page_reset_built_in_configuration_35b6ec)
                                } else {
                                    context.getString(R.string.page_remove_provider_9f848f)
                                },
                                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        item(key = "bottom_spacer") {
            MiuixPageBottomSpacer()
        }
    }

    if (showDeleteDialog) {
        val confirmLabel = if (isWorking) {
            context.getString(R.string.page_deleting_6f941d)
        } else {
            context.getString(R.string.page_delete_3755f5)
        }
        val dialogTitle = stringResource(R.string.ui_remove_provider_9f848f)
        val dialogSummary = stringResource(R.string.provider_delete_summary, provider.name)
        val onDismissDelete = { if (!isWorking) showDeleteDialog = false }
        val onCancelDelete = { showDeleteDialog = false }
        val onConfirmDelete: () -> Unit = {
            scope.launch {
                isWorking = true
                try {
                    ProviderRepository.deleteProvider(provider.id)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    showDeleteDialog = false
                    onDeleted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    status = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_delete_failed),
                    )
                    showDeleteDialog = false
                } finally {
                    isWorking = false
                }
            }
        }
        // 自适应弹窗：M3 → 官方 AlertDialog + TextButton；MIUI X → WindowDialog + MiuixDialogActions。
        EtaDialog(
            show = true,
            title = dialogTitle,
            summary = dialogSummary,
            onDismissRequest = onDismissDelete,
        ) {
            EtaDialogActions(
                confirmText = confirmLabel,
                cancelEnabled = !isWorking,
                confirmEnabled = !isWorking,
                destructive = true,
                onCancel = onCancelDelete,
                onConfirm = onConfirmDelete,
            )
        }
    }

    if (showResetDialog) {
        val confirmLabel = if (isWorking) {
            context.getString(R.string.page_resetting_616090)
        } else {
            context.getString(R.string.page_reset_3d8134)
        }
        val dialogTitle = stringResource(R.string.page_reset_built_in_configuration_35b6ec)
        val dialogSummary = stringResource(R.string.provider_reset_summary, provider.name)
        val onDismissReset = { if (!isWorking) showResetDialog = false }
        val onCancelReset = { showResetDialog = false }
        val onConfirmReset: () -> Unit = {
            scope.launch {
                isWorking = true
                try {
                    ProviderRepository.resetBuiltIn(provider.id)
                    ProviderRepository.providerById(provider.id)?.let {
                        onDraftChange(ProviderConfigDraft.from(it))
                    }
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    status = context.getString(R.string.page_reset_a0cc65)
                    showResetDialog = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    status = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_reset_failed),
                    )
                    showResetDialog = false
                } finally {
                    isWorking = false
                }
            }
        }
        // 自适应弹窗：M3 → 官方 AlertDialog + TextButton；MIUI X → WindowDialog + MiuixDialogActions。
        EtaDialog(
            show = true,
            title = dialogTitle,
            summary = dialogSummary,
            onDismissRequest = onDismissReset,
        ) {
            EtaDialogActions(
                confirmText = confirmLabel,
                cancelEnabled = !isWorking,
                confirmEnabled = !isWorking,
                onCancel = onCancelReset,
                onConfirm = onConfirmReset,
            )
        }
    }
}

private suspend fun testConnection(
    context: android.content.Context,
    provider: ProviderSetting,
): String {
    io.github.mangi.eta.data.model.RemovedProviderPolicy.requireSupported(provider)
    if (SpeechSynthesisModels.isSpeechOnlyProvider(provider) &&
        !SpeechSynthesisModels.isCompatibleSpeechProvider(provider)) {
        return runCatching { CloudSpeechSynthesizer().test(provider.apiKey) }.getOrElse { throwable ->
            context.getString(
                R.string.provider_error,
                (throwable as? SpeechPlaybackFailure)?.message ?: "语音接口不可用",
            )
        }
    }
    if (provider.usesOAuth && OpenAiCodexOAuth.isCodexEndpoint(provider.baseUrl)) {
        val token = OpenAiCodexOAuth.validAccessToken(context, provider.id) ?: provider.apiKey
        return context.getString(
            if (token.isBlank()) R.string.provider_oauth_need_sign_in
            else R.string.provider_oauth_signed_in,
        )
    }
    return RemoteModelFetcher.fetch(provider)
        .map { context.resources.getQuantityString(R.plurals.provider_models_fetched, it.size, it.size) }
        .getOrElse { throwable ->
            context.getString(
                R.string.provider_error,
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }
}

@Composable
private fun ProviderOAuthLoginRow(
    providerId: String,
    signedIn: Boolean,
    enabled: Boolean,
    onToken: (String) -> Unit,
    onBusy: (Boolean) -> Unit,
    onStatus: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = providerId
    val onSignIn: () -> Unit = {
        onBusy(true)
        onStatus(null)
        scope.launch {
            try {
                val token = OpenAiCodexOAuth.login(context, id)
                onToken(token)
                onStatus(context.getString(R.string.provider_oauth_sign_in_ok))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                onStatus(
                    context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_oauth_sign_in_failed),
                    ),
                )
            } finally {
                onBusy(false)
            }
        }
    }
    ProviderPrimaryAction(
        text = if (signedIn) {
            context.getString(
                R.string.provider_oauth_signed_in,
            )
        } else {
            context.getString(
                R.string.provider_oauth_sign_in,
            )
        },
        onClick = onSignIn,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    )
    val note = if (signedIn) {
        context.getString(R.string.provider_oauth_signed_in_note)
    } else {
        context.getString(R.string.provider_oauth_sign_in_note)
    }
    if (isMaterial3Theme()) {
        M3Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    } else {
        Text(
            text = note,
            style = MiuixTheme.textStyles.footnote2,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
