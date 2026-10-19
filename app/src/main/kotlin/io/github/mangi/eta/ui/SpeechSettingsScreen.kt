package io.github.mangi.eta.ui

import io.github.mangi.eta.agent.voice.doubao.DoubaoAsrProtocol
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow

@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config by DoubaoVoiceConfig.state.collectAsState()
    LaunchedEffect(Unit) { DoubaoVoiceConfig.load(context); OfflineSpeechPack.initialize(context) }
    VoiceSettingsSectionPage(title = stringResource(R.string.speech_title), onBack = onBack) {
        voiceGroup(key = "speech_enable") {
            item {
                EtaSwitchRow(
                    title = stringResource(R.string.speech_enable),
                    summary = stringResource(R.string.speech_input_summary),
                    checked = config.inputEnabled,
                    enabled = true,
                    onCheckedChange = { enabled ->
                        DoubaoVoiceConfig.save(context, config.copy(inputEnabled = enabled))
                    },
                )
            }
        }
        voiceGroup(key = "recognition_method") {
            // 识别方式：选「豆包识别」后连接设置直接就地展开，不再跳子页面；
            // 展开/收起走分段动画，首末圆角自动交接。
            item {
                EtaSegmentedDropdownRow(
                    title = stringResource(R.string.speech_recognition_method),
                    items = listOf(stringResource(R.string.speech_recognition_local), stringResource(R.string.speech_recognition_doubao)),
                    selectedIndex = if (config.cloudAsr) 1 else 0,
                    onItemSelected = { index ->
                        DoubaoVoiceConfig.save(context, config.copy(cloudAsr = index == 1))
                    },
                )
            }
            item(visible = config.cloudAsr) {
                // KernelSU `SegmentedTextField` 同款：无边框输入框嵌进分段行，
                // 与上下相邻行共用同一容器与圆角，不再是一个孤零零的描边框。
                EtaSegmentedTextField(
                    label = "ASR API Key",
                    value = config.asrKey,
                    onValueChange = { DoubaoVoiceConfig.save(context, config.copy(asrKey = it)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text(stringResource(R.string.speech_paste_api_key)) },
                )
            }
            item(visible = config.cloudAsr) {
                EtaSegmentedDropdownRow(
                    title = stringResource(R.string.voice_conversation_asr_resource),
                    items = DoubaoAsrProtocol.resources,
                    selectedIndex = DoubaoAsrProtocol.resources.indexOf(config.resource).coerceAtLeast(0),
                    onItemSelected = { index ->
                        DoubaoVoiceConfig.save(context, config.copy(resource = DoubaoAsrProtocol.resources[index]))
                    },
                )
            }
        }
        // 模型/来源/下载三行由共用组件提供，与「对话识别」页保持同一份实现。
        item(key = "speech_pack") {
            OfflineSpeechPackColumn(modifier = Modifier.padding(VoiceGroupInsets))
        }
    }
}
