package io.github.mangi.eta.ui

import io.github.mangi.eta.agent.voice.doubao.DoubaoAsrProtocol
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.SpeechModelManifest
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle

@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by OfflineSpeechPack.state.collectAsState()
    val config by DoubaoVoiceConfig.state.collectAsState()
    var confirmDownload by remember { mutableStateOf(false) }
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
        voiceGroup(key = "speech_pack") {
            item {
                EtaClickableRow(
                    title = stringResource(
                        when {
                            state.downloading -> R.string.speech_cancel_download
                            state.ready -> R.string.speech_pack_ready
                            else -> R.string.speech_download
                        },
                    ),
                    summary = stringResource(
                        when {
                            state.checking -> R.string.speech_checking
                            state.error -> R.string.speech_download_failed
                            else -> R.string.speech_pack_summary
                        },
                    ),
                    enabled = !state.checking && !state.ready,
                    onClick = {
                        if (state.downloading) OfflineSpeechPack.cancelDownload() else confirmDownload = true
                    },
                )
            }
            item(visible = state.downloading) {
                EtaSegmentedItemContainer {
                    val progress = (state.downloadedBytes.toFloat() / SpeechModelManifest.totalBytes).coerceIn(0f, 1f)
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        EtaText("${(progress * 100).toInt()}%", style = EtaTextStyle.Footnote,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
    EtaDialog(
        show = confirmDownload,
        title = stringResource(R.string.speech_download),
        onDismissRequest = { confirmDownload = false },
    ) {
        EtaText(stringResource(R.string.speech_download_confirm), style = EtaTextStyle.Secondary)
        EtaDialogActions(
            confirmText = stringResource(R.string.speech_download),
            cancelText = stringResource(R.string.action_cancel),
            modifier = Modifier.padding(top = 16.dp),
            onCancel = { confirmDownload = false },
            onConfirm = { confirmDownload = false; OfflineSpeechPack.download(context) },
        )
    }
}
