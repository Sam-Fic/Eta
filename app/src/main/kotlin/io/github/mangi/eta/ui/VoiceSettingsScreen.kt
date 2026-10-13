package io.github.mangi.eta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.etaOnSurface
import io.github.mangi.eta.ui.adaptive.etaSurfaceContainerHigh
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.haptics.TouchHaptics

internal enum class VoiceSettingsSection(val title: Int) {
    RECOGNITION(R.string.voice_section_speech),
    READ_ALOUD(R.string.voice_section_tts),
    CONVERSATION(R.string.voice_section_conversation),
    REALTIME(R.string.voice_section_realtime),
    PERSONAL(R.string.voice_section_personal),
}

internal val LocalEmbeddedVoiceSettings = staticCompositionLocalOf { false }

/** Reuses section content without nesting a second page title/scaffold. */
@Composable
internal fun VoiceSettingsSectionPage(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    if (LocalEmbeddedVoiceSettings.current) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), content = content)
    } else {
        EtaScaffoldPage(title = title, onBack = onBack, content = content)
    }
}

@Composable
internal fun VoiceSettingsScreen(
    onBack: () -> Unit,
    initialSection: VoiceSettingsSection = VoiceSettingsSection.RECOGNITION,
) {
    var selected by rememberSaveable { mutableStateOf(initialSection) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    EtaScaffold(title = stringResource(R.string.voice_title), onBack = onBack) { padding, scrollConnection, sidePadding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .padding(horizontal = sidePadding).nestedScroll(scrollConnection)) {
            if (isMaterial3Theme()) {
                // M3 Expressive：分区选择器整体交给官方 ListItem + HorizontalDivider，
                // 容器形状、内边距（ListItemDefaults.ContentPadding）、排版、涟漪与按压形变都由组件提供，
                // 不再用 clip(RoundedCornerShape) + background + clickable + 手写 padding 拼装。
                Column(Modifier.fillMaxWidth()) {
                    ListItem(
                        onClick = { TouchHaptics.click(view); expanded = !expanded },
                        modifier = Modifier.fillMaxWidth(),
                        trailingContent = {
                            Icon(
                                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = stringResource(R.string.voice_section_choose),
                            )
                        },
                        content = {
                            Text(
                                stringResource(selected.title),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                    )
                    if (expanded) {
                        // 只有 5 个枚举项，用普通 Column 即可，不再套 LazyColumn + heightIn(max = 220.dp)。
                        VoiceSettingsSection.entries.forEach { section ->
                            ListItem(
                                onClick = { TouchHaptics.click(view); selected = section; expanded = false },
                                modifier = Modifier.fillMaxWidth(),
                                content = {
                                    Text(
                                        stringResource(section.title),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                            )
                        }
                    }
                    HorizontalDivider()
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp)).background(etaSurfaceContainerHigh())) {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        TouchHaptics.click(view); expanded = !expanded
                    }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        EtaText(stringResource(selected.title), modifier = Modifier.weight(1f), style = EtaTextStyle.Body)
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = stringResource(R.string.voice_section_choose), tint = etaOnSurface())
                    }
                    if (expanded) {
                        LazyColumn(Modifier.heightIn(max = 220.dp).padding(bottom = 8.dp)) {
                            items(VoiceSettingsSection.entries, key = { it.name }) { section ->
                                EtaText(stringResource(section.title), style = EtaTextStyle.Secondary,
                                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                        TouchHaptics.click(view); selected = section; expanded = false
                                    }.padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalEmbeddedVoiceSettings provides true) {
                    key(selected) {
                        when (selected) {
                            VoiceSettingsSection.RECOGNITION -> SpeechSettingsScreen(onBack)
                            VoiceSettingsSection.READ_ALOUD -> TtsSettingsScreen(onBack)
                            VoiceSettingsSection.CONVERSATION -> TtsSettingsScreen(onBack, conversation = true)
                            VoiceSettingsSection.REALTIME -> VoiceModeSettingsScreen(onBack)
                            VoiceSettingsSection.PERSONAL -> PersonalVoicesScreen(onBack)
                        }
                    }
                }
            }
        }
    }
    BackHandler(enabled = expanded) { expanded = false }
}
