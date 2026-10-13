package io.github.mangi.eta.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** M3 下取官方 secondaryContainer 角色，miuix 主题族保持原色。 */
@Composable
private fun avatarContainerColor(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MiuixTheme.colorScheme.secondaryContainer
    }

/** 容器上的前景色：M3 由官方 Surface 的 contentColor 规则给出 onSecondaryContainer。 */
@Composable
private fun avatarContentColor(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MiuixTheme.colorScheme.onSecondaryContainer
    }

@Composable
internal fun AssistantAvatar(
    assistant: AssistantProfile?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberAssistantAvatarBitmap(assistant?.id, assistant?.avatarFileName)
    AssistantAvatar(bitmap = bitmap, size = size, modifier = modifier)
}

/** A new request owns a new state: late results cannot flash the previous assistant. */
@Composable
internal fun rememberAssistantAvatarBitmap(assistantId: String?, fileName: String?): Bitmap? {
    val revision by AssistantRepository.avatarRevision.collectAsState()
    val result = remember(assistantId, fileName, revision) {
        mutableStateOf(AssistantRepository.cachedAvatarBitmap(fileName, revision))
    }
    LaunchedEffect(result, fileName, revision) {
        val loaded = AssistantRepository.loadAvatarBitmap(fileName, revision)
        currentCoroutineContext().ensureActive()
        if (AssistantRepository.avatarRevision.value == revision) result.value = loaded
    }
    return result.value
}

@Composable
internal fun AssistantAvatar(
    bitmap: Bitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageBitmap = remember(bitmap, context) {
        bitmap
            ?.takeUnless { it.isRecycled }
            ?.let { runCatching { it.asImageBitmap() }.getOrNull() }
            ?: rasterizeDefaultAvatar(context)
    }
    val contentColor = avatarContentColor()
    // M3 Expressive：官方 Surface 承担圆形容器、容器色与内容色（含 onSecondaryContainer 配对）；
    // miuix 主题族仍用等价的 clip + 背景，视觉不变。
    if (isMaterial3Theme()) {
        Surface(
            modifier = modifier.size(size),
            shape = CircleShape,
            color = avatarContainerColor(),
            contentColor = contentColor,
        ) {
            AssistantAvatarContent(imageBitmap = imageBitmap)
        }
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            AssistantAvatarContent(imageBitmap = imageBitmap)
        }
    }
}

@Composable
private fun AssistantAvatarContent(imageBitmap: ImageBitmap?) {
    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = avatarContentColor(),
        )
    }
}

private fun rasterizeDefaultAvatar(context: Context): ImageBitmap? {
    val drawable = ContextCompat.getDrawable(context, R.drawable.ic_assistant_default)
        ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        ?: return null
    val size = maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight, 128)
    return runCatching {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}
