package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 朗读开关按钮。`index`/`count` 传入时（M3 主题）渲染进连体按钮组：官方 ToggleButton
 * 承担容器（secondaryContainer 配色与连体形状，见 [M3ConnectedIconAction]）；
 * 单独使用时保持原有官方 IconButton 默认档。miuix 分支不受影响。
 */
@Composable
internal fun SpeechPlaybackButton(
    messageId: String,
    text: String,
    index: Int = -1,
    count: Int = 0,
) {
    val context = LocalContext.current
    val state by SpeechPlayback.state.collectAsState()
    val active = state.owner == messageId
    val description = stringResource(if (active) R.string.tts_stop else R.string.tts_read)
    if (isMaterial3Theme()) {
        if (count > 0) {
            M3ConnectedIconAction(
                onClick = { SpeechPlayback.toggle(context, messageId, text) },
                enabled = !state.recording,
                index = index,
                count = count,
            ) {
                if (active && state.preparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                        color = LocalContentColor.current,
                    )
                } else {
                    Icon(
                        imageVector = if (active) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = description,
                        modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                    )
                }
            }
            return
        }
        // 官方 IconButton（无尺寸覆盖，走官方默认档），配色只引用官方 colorScheme 角色。
        androidx.compose.material3.IconButton(
            modifier = Modifier.semantics { contentDescription = description },
            onClick = { SpeechPlayback.toggle(context, messageId, text) },
            enabled = !state.recording,
        ) {
            if (active && state.preparing) {
                CircularProgressIndicator(Modifier.size(16.dp))
            } else {
                Icon(
                    imageVector = if (active) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = null,
                    tint = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        return
    }
    IconButton(
        modifier = Modifier.semantics { contentDescription = description },
        onClick = { SpeechPlayback.toggle(context, messageId, text) },
        enabled = !state.recording,
        minWidth = 30.dp,
        minHeight = 30.dp,
    ) {
        if (active && state.preparing) {
            CircularProgressIndicator(size = 15.dp, strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = if (active) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = stringResource(if (active) R.string.tts_stop else R.string.tts_read),
                modifier = Modifier.size(16.dp),
                tint = if (active) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
                },
            )
        }
    }
}
