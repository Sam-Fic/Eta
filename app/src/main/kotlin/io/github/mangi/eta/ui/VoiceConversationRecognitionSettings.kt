package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.doubao.DoubaoAsrProtocol
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.SpeechModelManifest
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText

/** Own ASR selections; only the downloaded offline model is shared with dictation. */
@Composable
internal fun VoiceConversationRecognitionSettings() {
    val context = LocalContext.current
    val config by DoubaoVoiceConfig.state.collectAsState()
    val pack by OfflineSpeechPack.state.collectAsState()
    var resourcePicker by remember { mutableStateOf(false) }
    var confirmDownload by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { DoubaoVoiceConfig.load(context); OfflineSpeechPack.initialize(context) }
    // 云识别/本机识别两套配置随开关用 visible 平铺切换，共用一组分段列表。
    VoiceGroup {
        item {
            EtaSwitchRow(
                title = stringResource(R.string.voice_mode_universal_enable),
                checked = config.conversationEnabled,
                onCheckedChange = { DoubaoVoiceConfig.save(context, config.copy(conversationEnabled = it)) },
            )
        }
        item {
            EtaSwitchRow(
                title = stringResource(R.string.voice_conversation_cloud_asr),
                checked = config.conversationCloudAsr,
                onCheckedChange = { DoubaoVoiceConfig.save(context, config.copy(conversationCloudAsr = it)) },
            )
        }
        item(visible = config.conversationCloudAsr) {
            // 与上方分段行同容器：无边框输入框嵌进行内，不再突兀。
            EtaSegmentedTextField(
                label = "ASR API Key",
                value = config.conversationAsrKey,
                onValueChange = { DoubaoVoiceConfig.save(context, config.copy(conversationAsrKey = it)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text(stringResource(R.string.speech_paste_api_key)) },
            )
        }
        item(visible = config.conversationCloudAsr) {
            EtaClickableRow(
                title = stringResource(R.string.voice_conversation_asr_resource),
                summary = config.conversationResource,
                onClick = { resourcePicker = true },
            )
        }
        item(visible = !config.conversationCloudAsr) {
            EtaClickableRow(
                title = stringResource(when {
                        pack.downloading -> R.string.speech_cancel_download
                        pack.ready -> R.string.speech_pack_ready
                        else -> R.string.speech_download
                }),
                summary = stringResource(when {
                        pack.checking -> R.string.speech_checking
                        pack.error -> R.string.speech_download_failed
                        else -> R.string.speech_pack_summary
                }),
                enabled = !pack.checking && !pack.ready,
                onClick = { if (pack.downloading) OfflineSpeechPack.cancelDownload() else confirmDownload = true },
            )
        }
        item(visible = !config.conversationCloudAsr && pack.downloading) {
            EtaSegmentedItemContainer {
                val progress = (pack.downloadedBytes.toFloat() / SpeechModelManifest.totalBytes).coerceIn(0f, 1f)
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    EtaText("${(progress * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
    SpeechRadioPickerDialog(
        show = resourcePicker,
        title = stringResource(R.string.voice_conversation_asr_resource),
        rows = DoubaoAsrProtocol.resources.map { it to it },
        selectedId = config.conversationResource,
        emptyText = "",
        onDismiss = { resourcePicker = false },
        onSelected = {
            DoubaoVoiceConfig.save(context, config.copy(conversationResource = it)); resourcePicker = false
        },
    )
    EtaDialog(
        show = confirmDownload,
        title = stringResource(R.string.speech_download),
        onDismissRequest = { confirmDownload = false },
    ) {
        EtaText(stringResource(R.string.speech_download_confirm))
        EtaDialogActions(
            confirmText = stringResource(R.string.speech_download),
            cancelText = stringResource(R.string.action_cancel),
            onCancel = { confirmDownload = false },
            onConfirm = { confirmDownload = false; OfflineSpeechPack.download(context) },
        )
    }
}
