package io.github.mangi.eta.ui.components

import android.widget.Toast
import android.net.Uri
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mangi.eta.agent.media.AgentVideoCodec
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.fullImageSourceAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator as M3CircularProgressIndicator
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.IconButton as M3IconButton

/** 取官方 surfaceContainer 角色。 */
@Composable
private fun previewPlaceholderColor(): Color = MaterialTheme.colorScheme.surfaceContainer

/** 图片占位与失败提示的圆角：走官方 shapes 令牌（medium = 12dp）。 */
@Composable
private fun previewMediaShape(): Shape = MaterialTheme.shapes.medium

/** 失败提示文字：走主页统一字阶的 Secondary 档。 */
@Composable
private fun previewBodyStyle(): TextStyle = etaTextStyle(EtaType.Secondary)

/**
 * 图片加载指示：用官方 `CircularProgressIndicator`（官方颜色角色与官方笔画宽度）。
 */
@Composable
private fun PreviewProgressIndicator(
    size: Dp,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
) {
    M3CircularProgressIndicator(modifier = modifier.size(size))
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

internal val LocalOpenChatImagePreview = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
internal fun ChatImagePreviewHost(
    gallery: List<String> = emptyList(),
    content: @Composable () -> Unit,
) {
    var source by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalOpenChatImagePreview provides { source = it }) {
        content()
    }
    val current = source
    if (current != null) {
        val sources = remember(current, gallery) { previewGalleryFor(current, gallery) }
        ChatImagePreviewDialog(
            sources = sources,
            initialIndex = sources.indexOf(current).coerceAtLeast(0),
            onDismiss = { source = null },
        )
    }
}

internal fun previewGalleryFor(source: String, gallery: List<String>): List<String> {
    val images = gallery.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (source.isBlank()) return images
    return if (source in images) images else images + source
}

internal fun collectPreviewableChatImages(
    messages: List<AgentChatMessageUi>,
    pendingImages: List<PendingImageUi> = emptyList(),
    imageSources: (AgentMessageUi) -> List<String> = { collectMarkdownImageSources(it.content) },
): List<String> {
    val out = LinkedHashSet<String>()
    messages.forEach { message ->
        when (message) {
            is UserMessageUi -> {
                message.images.indices.forEach { index ->
                    message.fullImageSourceAt(index).trim().takeIf { it.isNotEmpty() }?.let(out::add)
                }
            }
            is AgentMessageUi -> imageSources(message).forEach(out::add)
            else -> Unit
        }
    }
    pendingImages.forEach { image ->
        val source = image.uri.trim().ifEmpty { image.dataUrl.trim() }
        if (source.isNotEmpty()) out += source
    }
    return out.toList()
}

@Composable
internal fun ChatClickableImage(
    source: String,
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val openPreview = LocalOpenChatImagePreview.current
    val view = LocalView.current
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.clickable {
            TouchHaptics.click(view)
            openPreview(source)
        },
    )
}

@Composable
internal fun ChatMarkdownImage(
    content: String,
    node: ASTNode,
    modifier: Modifier = Modifier,
    sourceOverride: String? = null,
    fillPlaceholder: Boolean = false,
) {
    val source = remember(content, node.startOffset, node.endOffset, sourceOverride) {
        resolveChatImageSource(content, node, sourceOverride)
    } ?: return
    val imageModifier = if (fillPlaceholder) {
        modifier.fillMaxSize().clipToBounds()
    } else {
        modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(previewMediaShape())
    }
    DisableSelection {
        ChatRemoteClickableImage(
            source = source,
            modifier = imageModifier,
            contentScale = ContentScale.Fit,
            compactLoading = fillPlaceholder,
        )
    }
}

@Composable
private fun ChatRemoteClickableImage(
    source: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    compactLoading: Boolean = false,
) {
    val context = LocalContext.current
    val openPreview = LocalOpenChatImagePreview.current
    val view = LocalView.current
    val isVideo = AgentVideoCodec.isVideoSource(source)
    var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        val loaded = withContext(Dispatchers.IO) {
            if (isVideo) {
                val file = AgentVideoCodec.fileFromSource(source) ?: return@withContext null
                ChatImageBytes.load(context, AgentVideoCodec.previewThumbnail(file, source).reference)
            } else {
                ChatImageBytes.load(context, source)
            }
        }
        bitmap = loaded?.bitmap
        failed = loaded == null
    }
    val image = bitmap
    when {
        image != null -> {
            @Composable fun imageContent() {
                ChatClickableImage(
                    source = source,
                    bitmap = image,
                    contentDescription = stringResource(
                        if (isVideo) R.string.chat_video_preview else R.string.chat_image_preview,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
                if (isVideo) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.chat_video_preview),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            if (compactLoading) {
                Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) { imageContent() }
            } else {
                // Bound the measured height explicitly. A fillMaxSize child in an unbounded
                // paragraph used to depend on the text placeholder's height rather than media.
                BoxWithConstraints(modifier.clipToBounds()) {
                    val ratio = image.width.toFloat() / image.height.coerceAtLeast(1)
                    val displayHeight = (maxWidth / ratio).coerceAtMost(320.dp).coerceAtMost(maxHeight)
                    Box(Modifier.fillMaxWidth().height(displayHeight), contentAlignment = Alignment.Center) {
                        imageContent()
                    }
                }
            }
        }
        isVideo && failed -> {
            val videoPoster: @Composable () -> Unit = {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.chat_video_preview),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            val openPreviewAction: () -> Unit = {
                TouchHaptics.click(view)
                openPreview(source)
            }
            // M3 Expressive：官方 Surface(onClick) 承担容器、形状、容器色与按压涟漪；
            // 纯黑是视频信箱底色，属于媒体浮层固有色，不随主题变化。
            M3Surface(
                onClick = openPreviewAction,
                modifier = modifier.heightIn(min = 180.dp),
                color = Color.Black,
                content = videoPoster,
            )
        }
        failed -> Unit
        else -> Box(
            modifier = if (compactLoading) {
                modifier.background(previewPlaceholderColor())
            } else {
                modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(previewPlaceholderColor())
            },
            contentAlignment = Alignment.Center,
        ) {
            PreviewProgressIndicator(size = 18.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatImagePreviewDialog(
    sources: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0)),
        pageCount = { sources.size.coerceAtLeast(1) },
    )
    val source = sources.getOrNull(pagerState.currentPage).orEmpty()
    var loaded by remember(source) { mutableStateOf<LoadedChatImage?>(null) }
    var saving by remember { mutableStateOf(false) }
    var scale by remember(pagerState.currentPage) { mutableFloatStateOf(MIN_ZOOM) }
    var offset by remember(pagerState.currentPage) { mutableStateOf(Offset.Zero) }

    val isVideo = AgentVideoCodec.isVideoSource(source)
    LaunchedEffect(source) {
        if (source.isBlank() || isVideo) {
            loaded = null
            return@LaunchedEffect
        }
        loaded = withContext(Dispatchers.IO) { ChatImageBytes.load(context, source) }
    }

    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = scale <= MIN_ZOOM && sources.size > 1,
            ) { page ->
                ChatImagePreviewPage(
                    source = sources.getOrNull(page).orEmpty(),
                    active = page == pagerState.currentPage,
                    scale = if (page == pagerState.currentPage) scale else MIN_ZOOM,
                    offset = if (page == pagerState.currentPage) offset else Offset.Zero,
                    onTransform = { nextScale, nextOffset ->
                        scale = nextScale
                        offset = nextOffset
                    },
                )
            }
            val image = loaded
            val saveEnabled = !saving && (isVideo || image != null)
            val saveAction: () -> Unit = {
                TouchHaptics.click(view)
                saving = true
                scope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        if (isVideo) {
                            ChatImageGallery.saveVideo(context, source)
                        } else {
                            val payload = loaded ?: return@withContext null
                            ChatImageGallery.save(context, payload.bytes, payload.mimeType)
                        }
                    }
                    saving = false
                    Toast.makeText(
                        context,
                        context.getString(
                            if (uri != null) {
                                R.string.chat_image_saved
                            } else {
                                R.string.chat_image_save_failed
                            },
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            val saveButtonModifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
            val saveButtonContent: @Composable () -> Unit = {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.action_save),
                    tint = Color.White,
                )
            }
            // M3 Expressive：全屏媒体浮层上的保存动作走官方 IconButton（官方涟漪与图标槽位）。
            M3IconButton(
                onClick = saveAction,
                enabled = saveEnabled,
                modifier = saveButtonModifier,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                content = saveButtonContent,
            )
            if (saving) {
                PreviewProgressIndicator(
                    size = 20.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatImagePreviewPage(
    source: String,
    active: Boolean,
    scale: Float,
    offset: Offset,
    onTransform: (Float, Offset) -> Unit,
) {
    val context = LocalContext.current
    val currentScale = rememberUpdatedState(scale)
    val currentOffset = rememberUpdatedState(offset)
    val currentTransform = rememberUpdatedState(onTransform)
    var loaded by remember(source) { mutableStateOf<LoadedChatImage?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    val isVideo = AgentVideoCodec.isVideoSource(source)
    LaunchedEffect(source) {
        if (source.isBlank() || isVideo) {
            loaded = null
            failed = source.isBlank()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { ChatImageBytes.load(context, source) }
        loaded = result
        failed = result == null
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (isVideo) {
            ChatVideoPreviewPlayer(
                source = source,
                active = active,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        val image = loaded
        when {
            image != null -> {
                Image(
                    bitmap = image.bitmap,
                    contentDescription = stringResource(R.string.chat_image_preview),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize()
                        .pointerInput(source, active) {
                            if (!active) return@pointerInput
                            detectPreviewGestures(
                                scale = { currentScale.value },
                                offset = { currentOffset.value },
                                onTransform = { nextScale, nextOffset ->
                                    currentTransform.value(nextScale, nextOffset)
                                },
                            )
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }
            failed -> {
                Text(
                    text = stringResource(R.string.chat_image_load_failed),
                    color = Color.White,
                    style = previewBodyStyle(),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> {
                PreviewProgressIndicator(
                    size = 28.dp,
                    modifier = Modifier.align(Alignment.Center),
                    strokeWidth = 2.5.dp,
                )
            }
        }
        }
    }
}

private suspend fun PointerInputScope.detectPreviewGestures(
    scale: () -> Float,
    offset: () -> Offset,
    onTransform: (Float, Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val pointerCount = event.changes.count { it.pressed }
            val currentScale = scale()
            val zoomed = currentScale > MIN_ZOOM + 0.001f
            if (pointerCount >= 2 || zoomed) {
                event.changes.forEach { change ->
                    if (change.positionChanged()) change.consume()
                }
                val nextScale = (currentScale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                onTransform(
                    nextScale,
                    if (nextScale <= MIN_ZOOM) Offset.Zero else offset() + pan,
                )
            }
        } while (event.changes.any { it.pressed })
    }
}


@Composable
private fun ChatVideoPreviewPlayer(
    source: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VideoView(context).apply {
                setOnPreparedListener { player ->
                    player.isLooping = true
                    if (active) start()
                }
            }
        },
        update = { view ->
            val uri = when {
                source.startsWith("content://") || source.startsWith("file://") -> Uri.parse(source)
                source.startsWith("/") -> Uri.fromFile(java.io.File(source))
                else -> Uri.parse(source)
            }
            if (view.tag != source) {
                view.tag = source
                view.setVideoURI(uri)
            }
            if (active) {
                if (!view.isPlaying) view.start()
            } else if (view.isPlaying) {
                view.pause()
            }
        },
    )
}

/** Prepared document image entry; no AST traversal or reparsing in composition. */
@Composable
internal fun ChatDocumentImage(source: String, modifier: Modifier = Modifier) {
    DisableSelection {
        ChatRemoteClickableImage(
            source = source,
            modifier = modifier.fillMaxWidth().heightIn(max = 320.dp).clip(previewMediaShape()),
            contentScale = ContentScale.Fit,
            compactLoading = false,
        )
    }
}
