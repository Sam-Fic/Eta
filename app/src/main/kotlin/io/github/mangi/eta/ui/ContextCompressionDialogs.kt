package io.github.mangi.eta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ListItem
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.ViewTreeObserver
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentCompressionEndpoint
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.defaultExpandedModelProviderIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import androidx.compose.material3.CircularProgressIndicator as M3CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton as M3TextButton


private val CompressDialogChrome = 200.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun rememberActivityImeBottomDp(): Dp {
    val density = LocalDensity.current
    val context = LocalContext.current
    val composeImePx = WindowInsets.ime.getBottom(density)
    var viewImePx by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val target = context.findActivity()?.window?.decorView
        if (target == null) {
            return@DisposableEffect onDispose { }
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val insets = ViewCompat.getRootWindowInsets(target) ?: return@OnGlobalLayoutListener
            viewImePx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
        target.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            target.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    return with(density) { maxOf(composeImePx, viewImePx).toDp() }
}


private fun storedManualCompressionEndpoint(): String =
    AgentCompressionEndpoint.parse(Prefs.getString(Prefs.Keys.AGENT_MANUAL_COMPRESS_ENDPOINT_MODE, ""))


@Composable
internal fun CompressionEndpointPreference(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    compact: Boolean = false,
) {
    if (compact) {
        CompactCompressionEndpointPreference(selected, enabled, onSelect)
        return
    }
    val index = if (selected == OpenAiEndpointMode.RESPONSES) 1 else 0
    // 与兄弟界面（外观设置等）一致的下拉交互：点击行弹出锚定菜单二选一，选中项带 Check。
    EtaSegmentedDropdownRow(
        title = stringResource(R.string.ui_compress_request_api),
        summary = stringResource(R.string.ui_compress_endpoint_description),
        icon = Icons.Rounded.Api,
        items = listOf("Chat Completions API", "Responses API"),
        selectedIndex = index,
        onItemSelected = { selectedIndex ->
            onSelect(
                if (selectedIndex == 1) OpenAiEndpointMode.RESPONSES
                else OpenAiEndpointMode.CHAT_COMPLETIONS,
            )
        },
        enabled = enabled,
    )
}

/** Dialog layout intentionally avoids a setting-row spinner: long API names get a full row. */
@Composable
private fun CompactCompressionEndpointPreference(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    val detailsState = stringResource(if (detailsExpanded) R.string.work_collapse else R.string.work_expand)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = stringResource(R.string.ui_compress_request_api),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.ui_compress_endpoint_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        // 官方单选列表：选中态、涟漪与无障碍单选语义均由 ListItem 提供。
        Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            listOf(
                OpenAiEndpointMode.CHAT_COMPLETIONS to "Chat Completions API",
                OpenAiEndpointMode.RESPONSES to "Responses API",
            ).forEach { (mode, label) ->
                ListItem(
                    selected = selected == mode,
                    enabled = enabled,
                    onClick = { if (selected != mode) { TouchHaptics.click(view); onSelect(mode) } },
                    // 可交互/单选重载的正文参数名是 content（不是 headlineContent）。
                    content = { Text(label) },
                    trailingContent = { RadioButton(selected = selected == mode, onClick = null, enabled = enabled) },
                )
            }
        }
        ListItem(
            modifier = Modifier.semantics { stateDescription = detailsState },
            onClick = {
                TouchHaptics.click(view)
                detailsExpanded = !detailsExpanded
            },
            // 可交互重载的正文参数名是 content（不是 headlineContent）。
            content = {
                Text(
                    text = stringResource(R.string.ui_compress_endpoint_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = if (detailsExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                    contentDescription = null,
                )
            },
        )
        AnimatedVisibility(visible = detailsExpanded) {
            Text(
                text = stringResource(R.string.ui_compress_endpoint_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompressConversationDialog(
    show: Boolean,
    isCompressing: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (
        providerId: String?,
        modelId: String?,
        onFinished: (Boolean) -> Unit,
    ) -> Unit,
) {
    val prefs = remember { Prefs.localAgentPreferences() }
    val scope = rememberCoroutineScope()
    var endpointMode by remember { mutableStateOf(OpenAiEndpointMode.CHAT_COMPLETIONS) }
    var selectedModel by remember { mutableStateOf<AgentModelOptionUi?>(null) }
    var customModelEnabled by remember { mutableStateOf(false) }
    var modelPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showMissingWindowDialog by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(false) }
    val imeBottom = rememberActivityImeBottomDp()
    val configuration = LocalConfiguration.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val dialogImeOffset = -(imeBottom / 2)
    val maxBodyHeight = (configuration.screenHeightDp.dp - imeBottom - CompressDialogChrome)
        .coerceIn(140.dp, 360.dp)

    LaunchedEffect(show, prefs) {
        if (!show) {
            showModelDialog = false
            return@LaunchedEffect
        }
        endpointMode = storedManualCompressionEndpoint()
        isLoadingModels = true
        customModelEnabled = Prefs.isCustomCompressModelEnabled(prefs)
        val pickerState = withContext(Dispatchers.IO) {
            buildManualCompressModelPickerState()
        }
        modelPickerState = pickerState
        selectedModel = pickerState.selectedModel.takeIf { customModelEnabled }
        isLoadingModels = false
    }

    EtaDialog(
        show = show,
        title = stringResource(R.string.action_compress_conversation),
        modifier = Modifier.offset(y = dialogImeOffset),
        onDismissRequest = onDismiss,
    ) {
        val scrollState = rememberScrollState()
        val dialogFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            dialogFocus.requestFocus()
            keyboard?.hide()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(dialogFocus)
                .focusable(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight)
                    .verticalScroll(scrollState)
                    .alpha(if (isCompressing) 0.42f else 1f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.ui_custom_compress_model_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    val customModelLabel = stringResource(R.string.ui_custom_compress_model_title)
                    // 官方 expressive 勾选：选中时在拇指内显示对勾，尺寸取官方 SwitchDefaults.IconSize。
                    Switch(
                        checked = customModelEnabled,
                        modifier = Modifier.semantics { contentDescription = customModelLabel },
                        thumbContent = {
                            if (customModelEnabled) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        },
                        enabled = !isCompressing && !isLoadingModels,
                        onCheckedChange = { value ->
                            TouchHaptics.click(view)
                            customModelEnabled = value
                            Prefs.putBoolean(Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED, value)
                            if (value && selectedModel == null) {
                                selectedModel = modelPickerState.selectedModel
                            }
                            if (value && !selectedModel.hasCompressContextWindow() && selectedModel != null) {
                                showMissingWindowDialog = true
                            }
                        },
                    )
                }
                if (customModelEnabled) {
                    ListItem(
                        enabled = !isCompressing && !isLoadingModels,
                        onClick = {
                            TouchHaptics.click(view)
                            showModelDialog = true
                        },
                        content = {
                            Text(
                                text = if (isLoadingModels) stringResource(R.string.ui_compress_models_loading)
                                else selectedModel?.displayName ?: stringResource(R.string.model_not_selected),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = { Icon(imageVector = Icons.Rounded.ChevronRight, contentDescription = null) },
                    )
                } else {
                    Text(
                        text = stringResource(R.string.ui_custom_compress_model_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                CompressionEndpointPreference(
                    selected = endpointMode,
                    enabled = !isCompressing,
                    compact = true,
                    onSelect = { option ->
                        endpointMode = option
                        Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_ENDPOINT_MODE, option)
                    },
                )

            }
            if (isCompressing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isMaterial3Theme()) {
                        // 官方不确定进度指示器：尺寸/线宽/配色全部使用官方默认值。
                        M3CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                    }
                    Text(
                        text = stringResource(R.string.compress_conversation_in_progress),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            EtaDialogActions(
                confirmText = stringResource(R.string.compress_conversation_confirm),
                cancelText = stringResource(R.string.action_cancel),
                confirmEnabled = !isCompressing && !isLoadingModels,
                cancelEnabled = !isCompressing,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .alpha(if (isCompressing) 0.42f else 1f),
                onCancel = onDismiss,
                onConfirm = {
                    if (isCompressing) return@EtaDialogActions
                    focusManager.clearFocus()
                    keyboard?.hide()
                    Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_ENDPOINT_MODE, endpointMode)
                    onConfirm(
                        selectedModel?.providerId.takeIf { customModelEnabled },
                        selectedModel?.id.takeIf { customModelEnabled },
                    ) { ok ->
                        if (ok) onDismiss()
                    }
                },
            )
        }
    }

    CompressModelPickerDialog(
        state = modelPickerState,
        show = show && showModelDialog,
        isLoading = isLoadingModels,
        onDismiss = { showModelDialog = false },
        onModelSelected = { providerId, modelId ->
            showModelDialog = false
            Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_PROVIDER_ID, providerId)
            Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_ID, modelId)
            val picked = modelPickerState.findCompressModel(providerId, modelId)
            if (!picked.hasCompressContextWindow()) {
                showMissingWindowDialog = true
            }
            scope.launch {
                val pickerState = withContext(Dispatchers.IO) {
                    buildCompressModelPickerState(providerId, modelId)
                }
                modelPickerState = pickerState
                selectedModel = pickerState.selectedModel
            }
        },
    )

    CompressModelMissingWindowDialog(
        show = show && showMissingWindowDialog,
        onDismiss = { showMissingWindowDialog = false },
    )
}


internal fun AgentModelOptionUi?.hasCompressContextWindow(): Boolean =
    this?.contextWindow?.let { it > 0 } == true

internal fun AgentModelPickerUiState.findCompressModel(providerId: String, modelId: String): AgentModelOptionUi? =
    providerGroups.firstOrNull { it.providerId == providerId }?.models?.firstOrNull { it.id == modelId }

@Composable
internal fun CompressModelMissingWindowDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    if (!show) return
    EtaDialog(
        show = true,
        title = stringResource(R.string.compress_model_missing_window_title),
        summary = stringResource(R.string.compress_model_missing_window_summary),
        onDismissRequest = onDismiss,
    ) {
        if (isMaterial3Theme()) {
            // 官方文字按钮：形状、行高与配色均取官方默认值。
            M3TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.ui_knew_cb63c6))
            }
        } else {
            TextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
internal fun CompressModelPickerDialog(
    state: AgentModelPickerUiState,
    show: Boolean,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit,
    title: String? = null,
) {
    val view = LocalView.current
    var expandedProviderIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(show, state.selectedModel?.providerId, state.providerGroups) {
        if (!show) return@LaunchedEffect
        expandedProviderIds = defaultExpandedModelProviderIds(state.selectedModel).ifEmpty {
            state.providerGroups.singleOrNull()?.providerId?.let(::setOf).orEmpty()
        }
    }
    EtaDialog(
        show = show,
        title = title ?: stringResource(R.string.ui_compress_model_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (isLoading && state.providerGroups.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (isMaterial3Theme()) {
                        M3CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    } else {
                        CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp)
                    }
                }
            } else if (state.providerGroups.isEmpty()) {
                if (isMaterial3Theme()) {
                    Text(
                        text = stringResource(R.string.provider_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                } else {
                    MiuixText(
                        text = stringResource(R.string.provider_empty),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            } else if (isMaterial3Theme()) {
                // M3：KernelSU 式分段选择列表——每个提供商一组，提供商行带展开/收起指示；
                // 模型行选中时 primary 高亮 + Check 尾标，条目形状由分段列统一接管。
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.providerGroups.forEach { group ->
                        val expanded = group.providerId in expandedProviderIds
                        EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                            item {
                                EtaSegmentedRow(
                                    onClick = {
                                        TouchHaptics.click(view)
                                        expandedProviderIds = if (expanded) {
                                            emptySet()
                                        } else {
                                            setOf(group.providerId)
                                        }
                                    },
                                    content = { Text(group.providerName) },
                                    trailingContent = {
                                        Icon(
                                            imageVector = if (expanded) {
                                                Icons.Rounded.ExpandMore
                                            } else {
                                                Icons.Rounded.ChevronRight
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                            if (expanded) {
                                group.models.forEach { model ->
                                    val selected = model.providerId == state.selectedModel?.providerId &&
                                        model.id == state.selectedModel?.id
                                    item(key = "${group.providerId}/${model.id}") {
                                        EtaSegmentedRow(
                                            onClick = {
                                                TouchHaptics.click(view)
                                                onModelSelected(model.providerId, model.id)
                                            },
                                            content = {
                                                Text(
                                                    text = model.displayName,
                                                    color = if (selected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        Color.Unspecified
                                                    },
                                                )
                                            },
                                            trailingContent = {
                                                if (selected) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                state.providerGroups.forEachIndexed { index, group ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    val expanded = group.providerId in expandedProviderIds
                    ListItem(
                        onClick = {
                            TouchHaptics.click(view)
                            expandedProviderIds = if (expanded) {
                                emptySet()
                            } else {
                                setOf(group.providerId)
                            }
                        },
                        content = { Text(group.providerName) },
                    )
                    if (expanded) {
                        group.models.forEach { model ->
                            ListItem(
                                modifier = Modifier.padding(start = 16.dp),
                                onClick = {
                                    TouchHaptics.click(view)
                                    onModelSelected(model.providerId, model.id)
                                },
                                content = { Text(model.displayName) },
                                trailingContent = {
                                    if (model.id == state.selectedModel?.id) {
                                        Icon(imageVector = Icons.Rounded.Check, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun buildCompressModelPickerState(
    prefs: SharedPreferences?,
): AgentModelPickerUiState {
    val providerId = prefs?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, null)
    val modelId = prefs?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID, null)
    return buildCompressModelPickerState(providerId, modelId)
}

internal suspend fun buildManualCompressModelPickerState(): AgentModelPickerUiState {
    val prefs = Prefs.localAgentPreferences()
    val manualProviderId = prefs?.getString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_PROVIDER_ID, null)
    val manualModelId = prefs?.getString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_ID, null)
    if (!manualProviderId.isNullOrBlank() && !manualModelId.isNullOrBlank()) {
        return buildCompressModelPickerState(manualProviderId, manualModelId)
    }
    val settings = SettingsDataStore.settings()
    return buildCompressModelPickerState(settings.selectedProviderId, settings.selectedModelId)
}

internal suspend fun buildCompressModelPickerState(
    selectedProviderId: String?,
    selectedModelId: String?,
): AgentModelPickerUiState {
    val providers = runCatching { ProviderRepository.allProviders() }.getOrNull() ?: emptyList()
    return AgentModelPickerProjector.project(
        providers = providers,
        selectedProviderId = selectedProviderId,
        selectedModelId = selectedModelId,
    )
}

internal suspend fun readCompressModelSelection(prefs: SharedPreferences): AgentModelOptionUi? {
    return buildCompressModelPickerState(prefs).selectedModel
}

