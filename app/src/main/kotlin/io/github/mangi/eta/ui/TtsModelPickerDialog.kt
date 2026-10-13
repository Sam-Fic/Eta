package io.github.mangi.eta.ui
import io.github.mangi.eta.ui.components.etaRippleConfiguration

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.ui.components.WithoutPressRipple
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerUiState

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
    val view = LocalView.current
    var expanded by remember { mutableStateOf(state.selectedModel?.providerId ?: state.providerGroups.singleOrNull()?.providerId) }

    if (isMaterial3Theme()) {
        // M3 Expressive：官方 AlertDialog + 官方 ListItem（单选版 / 可点版）+ 官方 HorizontalDivider。
        // 不再覆盖 tonalElevation / containerColor，也不再用 WithoutPressRipple 关掉涟漪。
        // 选中态由 ListItem(selected=…) 的官方容器色承担，因此 M3 下 highlightSelection 不再需要额外高亮。
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (onClearSelection != null) {
                        ListItem(
                            selected = state.selectedModel == null,
                            onClick = { TouchHaptics.click(view); onClearSelection() },
                            // 不挂 contentDescription：官方 ListItem 内部已是
                            // semantics(mergeDescendants = true)，content 槽位的 Text 会被合并成播报文本，
                            // 再叠一份会重复朗读。
                            modifier = Modifier.fillMaxWidth(),
                            content = { Text("无") },
                        )
                        HorizontalDivider()
                    }
                    if (state.providerGroups.isEmpty()) Text(stringResource(R.string.provider_empty))
                    state.providerGroups.forEachIndexed { index, group ->
                        if (index > 0) HorizontalDivider()
                        val isExpanded = expanded == group.providerId
                        ListItem(
                            onClick = { TouchHaptics.click(view); expanded = if (isExpanded) null else group.providerId },
                            modifier = Modifier.fillMaxWidth(),
                            trailingContent = {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = if (isExpanded) "收起" else "展开",
                                )
                            },
                            content = { Text(group.providerName, style = MaterialTheme.typography.bodyLarge) },
                        )
                        if (isExpanded) Column(Modifier.selectableGroup()) {
                            group.models.forEach { model ->
                                val selected = state.selectedModel?.providerId == model.providerId &&
                                    state.selectedModel?.id == model.id
                                ListItem(
                                    selected = selected,
                                    onClick = { TouchHaptics.click(view); onModelSelected(model.providerId, model.id) },
                                    // 播报文本由官方合并 content 槽位的 Text 提供，不额外挂 contentDescription。
                                    modifier = Modifier.fillMaxWidth(),
                                    content = { Text(model.displayName) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
            },
        )
        return
    }

    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
    WithoutPressRipple {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        tonalElevation = 0.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (onClearSelection != null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .modelSelectionBackground(highlightSelection && state.selectedModel == null, MaterialTheme.colorScheme.surfaceVariant)
                            .selectable(selected = state.selectedModel == null, role = Role.RadioButton,
                                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {
                                TouchHaptics.click(view)
                                onClearSelection()
                            }).padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!highlightSelection) RadioButton(selected = state.selectedModel == null, onClick = null)
                        Text("无", modifier = Modifier.weight(1f).padding(start = 12.dp),
                            color = if (highlightSelection && state.selectedModel == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (state.providerGroups.isEmpty()) Text(stringResource(R.string.provider_empty))
                state.providerGroups.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val isExpanded = expanded == group.providerId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                TouchHaptics.click(view)
                                expanded = if (isExpanded) null else group.providerId
                            }.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group.providerName, style = MiuixTheme.textStyles.subtitle, modifier = Modifier.weight(1f))
                        Icon(if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (isExpanded) "收起" else "展开")
                    }
                    if (isExpanded) Column(Modifier.selectableGroup()) {
                        group.models.forEach { model ->
                            val selected = state.selectedModel?.providerId == model.providerId && state.selectedModel?.id == model.id
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                    .modelSelectionBackground(highlightSelection && selected, MaterialTheme.colorScheme.surfaceVariant)
                                    .selectable(selected = selected, role = Role.RadioButton,
                                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {
                                        TouchHaptics.click(view)
                                        onModelSelected(model.providerId, model.id)
                                    }).padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!highlightSelection) RadioButton(selected = selected, onClick = null)
                                Text(model.displayName, style = MiuixTheme.textStyles.body2,
                                    color = if (highlightSelection && selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f).padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
        },
    )
    }
    }
}

/** Only inset the painted selection; row size, text and the full click target stay unchanged. */
private fun Modifier.modelSelectionBackground(selected: Boolean, color: Color): Modifier =
    if (!selected) this else drawBehind {
        val inset = 1.dp.toPx()
        val radius = 10.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, inset),
            size = Size(size.width, (size.height - 2 * inset).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(radius, radius),
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
    if (isMaterial3Theme()) {
        // M3 Expressive：官方 AlertDialog 默认容器色与海拔，不再覆盖。
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = body,
            confirmButton = {
                TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            tonalElevation = 0.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            text = body,
            confirmButton = {
                TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
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
    if (isMaterial3Theme()) {
        // M3 Expressive：官方 AlertDialog 默认容器色与海拔，不再覆盖。
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = body,
            confirmButton = {
                TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            tonalElevation = 0.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            text = body,
            confirmButton = {
                TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

@Composable
private fun SpeechRadioRow(id: String, label: String, selected: Boolean, onSelected: (String) -> Unit) {
    val view = LocalView.current
    if (isMaterial3Theme()) {
        // M3 Expressive：官方单选版 ListItem 提供选中容器色、Role.RadioButton 语义与按压形变；
        // 正文槽位参数名是 content（headlineContent 只属于无 onClick 的展示重载）。
        ListItem(
            selected = selected,
            onClick = { TouchHaptics.click(view); onSelected(id) },
            // 播报文本由官方合并 content 槽位的 Text 提供，不额外挂 contentDescription。
            modifier = Modifier.fillMaxWidth(),
            content = { Text(label) },
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = {
                TouchHaptics.click(view)
                onSelected(id)
            }).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MiuixTheme.textStyles.body2,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 12.dp))
    }
}

@Composable
private fun VoiceSectionTitle(text: String) {
    if (isMaterial3Theme()) {
        // M3：官方 typography 令牌 + colorScheme.onSurfaceVariant。
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun VoiceRow(
    voice: io.github.mangi.eta.agent.voice.tts.SpeechVoice,
    selectedId: String,
    onSelected: (String) -> Unit,
) {
    val view = LocalView.current
    val selected = voice.id == selectedId
    if (isMaterial3Theme()) {
        // M3 Expressive：官方单选版 ListItem 提供选中容器色、Role.RadioButton 语义与按压形变。
        ListItem(
            selected = selected,
            onClick = { TouchHaptics.click(view); onSelected(voice.id) },
            // 播报文本由官方合并 content 槽位的 Text 提供，不额外挂 contentDescription。
            modifier = Modifier.fillMaxWidth(),
            content = { Text(voice.name) },
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = {
                TouchHaptics.click(view)
                onSelected(voice.id)
            })
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = voice.name,
            style = MiuixTheme.textStyles.body1,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}
