package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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

/**
 * 朗读开关按钮。`index`/`count` 传入时渲染进连体按钮组：官方 ToggleButton
 * 承担容器（secondaryContainer 配色与连体形状，见 [M3ConnectedIconAction]）；
 * 单独使用时走官方 IconButton 默认档。
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
}
