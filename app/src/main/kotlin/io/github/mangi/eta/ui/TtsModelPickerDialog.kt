package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRadioRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerUiState

/**
 * 模型选择弹窗（辅助视觉模型 / 标题模型 / TTS / 子代理等处共用）。
 *
 * 列表按提供商分组平铺，组内为单选行（KernelSU「Select KMI」式）。选中只改变弹窗内的待确认
 * 状态，需再点「确认」才通过 [onModelSelected]（或 [onClearSelection]）提交，「取消」则直接关闭。
 *
 * @param onClearSelection 非空时在列表顶部提供「无」选项；确认「无」时回调它。
 * @param highlightSelection 仅为兼容既有调用方保留；新设计统一由单选行呈现选中态，此参数不再生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TtsModelPickerDialog(
    state: AgentModelPickerUiState,
    show: Boolean,
    onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit,
    title: String,
    onClearSelection: (() -> Unit)? = null,
    highlightSelection: Boolean = false,
) {
    if (!show) return
    // 弹窗内的「待确认」选择：与参考图一致——先选中，再点确认提交；取消直接丢弃。
    var pendingProviderId by remember { mutableStateOf(state.selectedModel?.providerId) }
    var pendingModelId by remember { mutableStateOf(state.selectedModel?.id) }
    val hasPending = pendingModelId != null
    val confirmEnabled = hasPending || onClearSelection != null
    val confirm: () -> Unit = {
        if (hasPending) {
            onModelSelected(pendingProviderId.orEmpty(), pendingModelId.orEmpty())
        } else {
            onClearSelection?.invoke()
        }
    }
    val selectNone: () -> Unit = {
        pendingProviderId = null
        pendingModelId = null
    }

    // 官方 AlertDialog + KernelSU 式分段单选列表——提供商作分组标题、模型为组内单选行，
    // 底部「取消 / 确认」提交，整体对应参考图「Select KMI」的信息结构。
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                if (onClearSelection != null) {
                    EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                        item(key = "none") {
                            ModelPickerRadioRow(
                                title = stringResource(R.string.tts_none),
                                selected = !hasPending,
                                onClick = selectNone,
                            )
                        }
                    }
                }
                if (state.providerGroups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.provider_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.providerGroups.forEach { group ->
                    EtaSegmentedColumn(
                        modifier = Modifier.fillMaxWidth(),
                        title = group.providerName,
                    ) {
                        group.models.forEach { model ->
                            item(key = "${model.providerId}/${model.id}") {
                                ModelPickerRadioRow(
                                    title = model.displayName,
                                    selected = pendingProviderId == model.providerId &&
                                        pendingModelId == model.id,
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
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = confirmEnabled) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * 模型选择弹窗中的单选项：走 [EtaSegmentedRadioRow]（分段单选行，与参考图三一致），
 * 常显单选按钮，选中态由整行承担。
 *
 * 由模型选择类弹窗（[TtsModelPickerDialog]、上下文压缩的模型选择）共用。
 */
@Composable
internal fun ModelPickerRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val click = {
        TouchHaptics.click(view)
        onClick()
    }
    EtaSegmentedRadioRow(title = title, selected = selected, onClick = click)
}

/**
 * 模型选择弹窗中的可选项（对勾版）：左侧不放单选按钮，改为选中行行尾显示对勾，
 * 选中态由整行底色 + 对勾共同承担（与 [ModelPickerRadioRow] 共用分段行外观与轻触反馈）。
 *
 * 由输入区「Assistant & model」底部弹窗的模型列表使用。
 */
@Composable
internal fun ModelPickerCheckRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 显式类型：if 分支里的可组合 lambda 需要标注，避免推断落到 Nothing?。
    val trailing: (@Composable () -> Unit)? = if (selected) {
        { Icon(imageVector = Icons.Rounded.Check, contentDescription = null) }
    } else {
        null
    }
    EtaSegmentedRow(
        selected = selected,
        onClick = onClick,
        trailingContent = trailing,
        content = { Text(text = title) },
    )
}

internal data class SpeechVoiceSections(
    val female: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    val male: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    val other: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    val personal: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
)

internal fun groupedSpeechVoices(
    voices: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
): SpeechVoiceSections {
    val personal = voices.filter { it.personal }
    val publicVoices = voices.filter { !it.personal }
    return SpeechVoiceSections(
        female = publicVoices.filter { "_female_" in it.id },
        male = publicVoices.filter { "_male_" in it.id },
        other = publicVoices.filter { "_female_" !in it.id && "_male_" !in it.id },
        personal = personal,
    )
}

@Composable
internal fun TtsVoicePickerDialog(
    show: Boolean,
    voices: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
    title: String,
) {
    if (!show) return
    val view = LocalView.current
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            val sections = groupedSpeechVoices(voices)
            if (sections.female.isNotEmpty()) {
                VoiceSectionTitle(stringResource(R.string.tts_voice_female))
                sections.female.forEach { VoiceRow(it, selectedId, onSelected) }
            }
            if (sections.male.isNotEmpty()) {
                VoiceSectionTitle(stringResource(R.string.tts_voice_male))
                sections.male.forEach { VoiceRow(it, selectedId, onSelected) }
            }
            sections.other.forEach { VoiceRow(it, selectedId, onSelected) }
            if (sections.personal.isNotEmpty()) {
                VoiceSectionTitle(stringResource(R.string.tts_voice_personal))
                sections.personal.forEach { VoiceRow(it, selectedId, onSelected) }
            }
        }
    }
    // 官方 AlertDialog 默认容器色与海拔，不再覆盖。
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body,
        confirmButton = {
            TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
internal fun SpeechRadioPickerDialog(
    show: Boolean,
    title: String,
    rows: List<Pair<String, String>>,
    selectedId: String,
    emptyText: String = "",
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    if (!show) return
    val view = LocalView.current
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            if (rows.isEmpty()) Text(emptyText)
            rows.forEach { (id, label) -> SpeechRadioRow(id, label, id == selectedId, onSelected) }
        }
    }
    // 官方 AlertDialog 默认容器色与海拔，不再覆盖。
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body,
        confirmButton = {
            TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun SpeechRadioRow(id: String, label: String, selected: Boolean, onSelected: (String) -> Unit) {
    val view = LocalView.current
    // 官方单选版 ListItem 提供选中容器色、Role.RadioButton 语义与按压形变；
    // 正文槽位参数名是 content（headlineContent 只属于无 onClick 的展示重载）。
    ListItem(
        selected = selected,
        onClick = { TouchHaptics.click(view); onSelected(id) },
        // 播报文本由官方合并 content 槽位的 Text 提供，不额外挂 contentDescription。
        modifier = Modifier.fillMaxWidth(),
        content = { Text(label) },
    )
}

@Composable
private fun VoiceSectionTitle(text: String) {
    // 官方 typography 令牌 + colorScheme.onSurfaceVariant。
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun VoiceRow(
    voice: io.github.mangi.eta.agent.voice.tts.SpeechVoice,
    selectedId: String,
    onSelected: (String) -> Unit,
) {
    val view = LocalView.current
    val selected = voice.id == selectedId
    // 官方单选版 ListItem 提供选中容器色、Role.RadioButton 语义与按压形变。
    ListItem(
        selected = selected,
        onClick = { TouchHaptics.click(view); onSelected(voice.id) },
        // 播报文本由官方合并 content 槽位的 Text 提供，不额外挂 contentDescription。
        modifier = Modifier.fillMaxWidth(),
        content = { Text(voice.name) },
    )
}
