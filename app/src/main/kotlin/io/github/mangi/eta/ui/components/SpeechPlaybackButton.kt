package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeechPlaybackButton(messageId: String, text: String) {
    val context = LocalContext.current
    val state by SpeechPlayback.state.collectAsState()
    val active = state.owner == messageId
    val description = stringResource(if (active) R.string.tts_stop else R.string.tts_read)
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
