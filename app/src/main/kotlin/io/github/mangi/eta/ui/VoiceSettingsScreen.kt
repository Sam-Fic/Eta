package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedScope

internal enum class VoiceSettingsSection(val title: Int) {
    RECOGNITION(R.string.voice_section_speech),
    READ_ALOUD(R.string.voice_section_tts),
    CONVERSATION(R.string.voice_section_conversation),
    REALTIME(R.string.voice_section_realtime),
    PERSONAL(R.string.voice_section_personal),
}

/** 「我的声音」内的子页：作为独立顶层页呈现（自带顶栏/返回），不再嵌套进分区内。 */
internal enum class VoiceSubPage { DOUBAO_VOICES, MIMO_VOICES }

internal val LocalEmbeddedVoiceSettings = staticCompositionLocalOf { false }

/** 一组语音设置条目的统一左右边距与组间距。 */
internal val VoiceGroupInsets = PaddingValues(start = 16.dp, end = 16.dp, bottom = 13.dp)

/**
 * 一组语音设置条目（KernelSU 式分段列表）。
 *
 * 用单一入口替代各页 `item { EtaSegmentedColumn(modifier = padding) { item { … } } }`
 * 的三层包裹；条件行用 [EtaSegmentedScope.item] 的 `visible` 注册，显隐带分段动画。
 */
@Composable
internal fun VoiceGroup(content: EtaSegmentedScope.() -> Unit) {
    EtaSegmentedColumn(modifier = Modifier.padding(VoiceGroupInsets), content = content)
}

/** [VoiceGroup] 的 LazyListScope 形式。 */
internal fun LazyListScope.voiceGroup(key: Any? = null, content: EtaSegmentedScope.() -> Unit) {
    item(key = key) { VoiceGroup(content) }
}

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
    var subPage by rememberSaveable { mutableStateOf<VoiceSubPage?>(null) }
    if (subPage != null) {
        // 子页整体替换本页（各自带顶栏与返回），避免 Scaffold 套 Scaffold 的双层顶栏。
        when (subPage) {
            VoiceSubPage.DOUBAO_VOICES -> DoubaoVoiceSettings(onBack = { subPage = null })
            VoiceSubPage.MIMO_VOICES -> MimoVoicesScreen(onBack = { subPage = null })
            null -> Unit
        }
        return
    }
    EtaScaffold(title = stringResource(R.string.voice_title), onBack = onBack) { padding, scrollConnection, sidePadding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .padding(horizontal = sidePadding).nestedScroll(scrollConnection)) {
            // 分区选择器：KernelSU 式下拉行——点击在按下位置弹出选项列表，选中即切换，
            // 不再展开成长列表把内容往下挤，也不再手写 clip + background + clickable 拼装。
            VoiceGroup {
                item {
                    val sections = VoiceSettingsSection.entries
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.voice_section_choose),
                        items = sections.map { stringResource(it.title) },
                        selectedIndex = selected.ordinal,
                        onItemSelected = { index -> selected = sections[index] },
                    )
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
                            VoiceSettingsSection.PERSONAL -> PersonalVoicesScreen(
                                onBack = onBack,
                                onOpenSubPage = { subPage = it },
                            )
                        }
                    }
                }
            }
        }
    }
}
