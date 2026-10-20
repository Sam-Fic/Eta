package io.github.mangi.eta.ui.screens.search

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.media.index.MediaSearchService
import io.github.mangi.eta.data.db.MediaKind
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDecorativeShape
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSearchScaffold
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.etaRandomDecorativeShape
import io.github.mangi.eta.ui.components.ContainedMorphLoadingIndicator
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.navigation.AppRoute
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 类型过滤 chips：全部 / 图片 / 视频 / 音频 / 文件。 */
private val FILTER_LABELS = intArrayOf(
    R.string.media_filter_all,
    R.string.media_filter_image,
    R.string.media_filter_video,
    R.string.media_filter_audio,
    R.string.media_filter_document,
)

/** 与 [FILTER_LABELS] 一一对应的 [MediaKind] 过滤集合；空集合表示不过滤。 */
private fun kindsForFilter(index: Int): Set<MediaKind> = when (index) {
    1 -> setOf(MediaKind.IMAGE)
    2 -> setOf(MediaKind.VIDEO)
    3 -> setOf(MediaKind.AUDIO)
    4 -> setOf(MediaKind.DOCUMENT)
    else -> emptySet()
}

/**
 * 本地多模态语义检索页：自然语言搜本机图片 / 视频 / 音频 / 文件。
 *
 * 版式沿用应用内其它搜索页（[EtaSearchScaffold]）：顶栏是「返回钮 + 透明底搜索框 +
 * 清除按钮 + 搜索按钮」，没有大标题页，搜索词就在顶栏里改；类型标签条常驻在顶栏下方，
 * 搜索前后都能切换范围。
 *
 * 检索统一走 [MediaSearchService]（与 AI 工具 `search_media` 同一入口），全程离线；
 * 模型未下载或扫描源未确认时显示引导卡片跳设置页。
 */
@Composable
internal fun MediaSearchScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<MediaSearchService.Status?>(null) }
    var searching by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MediaSearchService.SearchResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        status = runCatching { MediaSearchService.status(context) }.getOrNull()
    }

    /** 执行一次语义检索；查询为空或索引未就绪时不动作（引导卡片负责指向设置页）。 */
    fun runSearch() {
        val text = query.trim()
        if (text.isEmpty() || searching) return
        // 检索是显式动作：触发后立刻收起键盘，结果区不再被输入法挤成半屏。
        focusManager.clearFocus()
        scope.launch {
            searching = true
            errorText = null
            val outcome = runCatching {
                MediaSearchService.search(
                    context = context,
                    query = text,
                    kinds = kindsForFilter(filterIndex),
                    limit = 20,
                )
            }
            result = outcome.getOrNull()
            outcome.exceptionOrNull()?.let { errorText = it.message ?: "search failed" }
            searching = false
            status = runCatching { MediaSearchService.status(context) }.getOrNull()
        }
    }

    val ready = status?.ready == true
    val hits = result?.hits.orEmpty()

    EtaSearchScaffold(
        query = query,
        onQueryChange = { query = it },
        searchPlaceholder = stringResource(R.string.media_search_placeholder),
        onBack = onBack,
        // IME 的搜索键与顶栏的「搜索」按钮走同一条路径。
        onSubmit = { runSearch() },
        actions = {
            // 语义检索每次都要跑嵌入模型，不能随输入实时触发，所以顶栏常驻一个显式按钮。
            // 用放大镜图标表示（与设置主页的搜索入口同一套图标）。
            IconButton(
                onClick = { runSearch() },
                enabled = query.trim().isNotEmpty() && ready && !searching,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.media_action_search),
                )
            }
        },
        topContent = {
            // 类型标签常驻顶栏下方：搜索前后都能切换范围，不用滚回页面顶部。
            MediaFilterBar(
                selectedIndex = filterIndex,
                onSelect = { index ->
                    TouchHaptics.click(view)
                    filterIndex = index
                    if (result != null && query.trim().isNotEmpty()) runSearch()
                },
            )
        },
    ) {
        // 选中「音频 / 文件」时的索引口径提示：常驻在结果列表上方。
        if (filterIndex == 3 || filterIndex == 4) {
            item(key = "filter-note") {
                EtaText(
                    text = stringResource(
                        if (filterIndex == 3) {
                            R.string.media_filter_audio_note
                        } else {
                            R.string.media_filter_document_note
                        },
                    ),
                    secondary = true,
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        top = 4.dp,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                )
            }
        }

        when {
            // 未就绪态：模型未下载或扫描源未确认，引导到设置页。
            !ready -> {
                item(key = "not-ready") {
                    EtaSegmentedColumn(
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            bottom = EtaSegmentedGroupSpacing,
                        ),
                    ) {
                        item {
                            EtaClickableRow(
                                title = stringResource(R.string.media_search_not_ready_title),
                                summary = stringResource(R.string.media_search_not_ready_summary),
                                onClick = { onNavigate(AppRoute.MediaIndexSettings) },
                            )
                        }
                    }
                }
            }
            searching -> {
                // 检索中：块级 loading（M3E 官方 morph 指示器），整体居中展示。
                item(key = "searching") {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(horizontal = 32.dp),
                        ) {
                            ContainedMorphLoadingIndicator()
                            EtaText(
                                text = stringResource(R.string.media_search_searching),
                                style = EtaTextStyle.Secondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            errorText != null -> {
                item(key = "search-error") {
                    EtaText(
                        text = stringResource(R.string.media_search_error, errorText ?: ""),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            bottom = EtaSegmentedGroupSpacing,
                        ),
                    )
                }
            }
            result == null -> {
                // 空闲态：与其它搜索页同一套官方 Empty state（装饰形状 + 放大镜 + 一句说明）。
                item(key = "search-idle") {
                    MediaSearchEmptyState(
                        text = stringResource(R.string.media_search_hint),
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
            }
            hits.isEmpty() -> {
                // 无结果态：索引为空时直接给「去建立索引」引导。
                item(key = "no-results") {
                    EtaSegmentedColumn(
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            bottom = EtaSegmentedGroupSpacing,
                        ),
                    ) {
                        item {
                            EtaClickableRow(
                                title = stringResource(R.string.media_search_no_results),
                                summary = if (result?.totalCandidates == 0) {
                                    stringResource(R.string.media_search_empty_index)
                                } else {
                                    null
                                },
                                onClick = null,
                                showChevron = false,
                            )
                        }
                        item(visible = result?.totalCandidates == 0) {
                            EtaClickableRow(
                                title = stringResource(R.string.media_action_build_index),
                                onClick = { onNavigate(AppRoute.MediaIndexSettings) },
                            )
                        }
                    }
                }
            }
            else -> {
                // 结果计数作组标题：与设置主页的分组标题同一套样式（primary + titleSmallEmphasized）。
                item(key = "result-count") {
                    Text(
                        text = stringResource(R.string.media_search_result_count, hits.size),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            bottom = EtaSegmentedTitleSpacing,
                        ),
                    )
                }
                // 结果行完全复用设置主页的分组列表：行距 2dp、组末 13dp、左右页边距 16dp，
                // 分段形状由 [EtaSegmentedItem] 按 index/count 注入（首末项外圆角、中间内圆角）。
                hits.forEachIndexed { index, hit ->
                    item(key = hit.sourceKey) {
                        EtaSegmentedItem(index = index, count = hits.size) {
                            EtaClickableRow(
                                title = hit.displayName,
                                summary = hitSummary(context, hit),
                                leading = { HitThumbnail(hit) },
                                showChevron = false,
                                onClick = { openHit(context, hit) },
                                modifier = Modifier.padding(
                                    start = EtaGroupHorizontalPadding,
                                    end = EtaGroupHorizontalPadding,
                                    bottom = if (index == hits.lastIndex) {
                                        EtaSegmentedGroupSpacing
                                    } else {
                                        EtaSegmentedGap
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 类型过滤标签条：全部 / 图片 / 视频 / 音频 / 文件。
 *
 * 走官方 [FilterChip]（自带选中态、涟漪与语义），横向滚动排布，常驻在顶栏下方。
 */
@Composable
private fun MediaFilterBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedGroupSpacing,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FILTER_LABELS.forEachIndexed { index, labelRes ->
            FilterChip(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                label = { Text(text = stringResource(labelRes)) },
            )
        }
    }
}

/**
 * 空闲态：官方 Empty state 观感——装饰形状 + 放大镜 + 一句说明（与其它搜索页一致）。
 *
 * 装饰形状每次进页面换一枚轮廓，页内保持稳定（纯装饰，不承载信息）。
 */
@Composable
private fun MediaSearchEmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    val decorativeShape = remember { etaRandomDecorativeShape() }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(
                modifier = Modifier.size(EtaDimens.EmptyStateShapeSize),
                contentAlignment = Alignment.Center,
            ) {
                EtaDecorativeShape(
                    modifier = Modifier.size(EtaDimens.EmptyStateShapeSize),
                    shape = decorativeShape,
                )
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EtaText(
                text = text,
                style = EtaTextStyle.Secondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 结果行摘要：类型 · 分数 · 命中时刻（视频）· 尺寸/时长 · 体积 · 修改时间。 */
@Composable
private fun hitSummary(context: Context, hit: MediaSearchService.Hit): String {
    val kindLabel = stringResource(
        when (hit.kind) {
            MediaKind.IMAGE -> R.string.media_kind_image
            MediaKind.VIDEO -> R.string.media_kind_video
            MediaKind.AUDIO -> R.string.media_kind_audio
            MediaKind.DOCUMENT -> R.string.media_kind_document
        },
    )
    val parts = buildList {
        add(kindLabel)
        add(context.getString(R.string.media_score, String.format(Locale.US, "%.2f", hit.score)))
        // 视频「时刻检索」命中时刻（如 01:23）。
        hit.frameTimeMs?.let { add(context.getString(R.string.media_hit_moment, formatDuration(it))) }
        hit.width?.let { width ->
            hit.height?.let { height -> add("${width}x${height}") }
        }
        hit.durationMs?.let { add(formatDuration(it)) }
        add(formatBytes(hit.sizeBytes))
        add(formatTime(hit.modifiedAt))
    }
    return parts.joinToString(" · ")
}

/** 结果行缩略图：thumbPath 有值用缩略图，否则按 kind 图标占位。 */
@Composable
private fun HitThumbnail(hit: MediaSearchService.Hit) {
    val thumbPath = hit.thumbPath
    val bitmap by produceState<ImageBitmap?>(initialValue = null, thumbPath) {
        value = thumbPath?.let { path ->
            withContext(Dispatchers.IO) {
                runCatching {
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
                        ?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            // 右侧留 8dp：与设置主页行首图标的间距一致，标题起点对齐。
            .padding(end = 8.dp)
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = when (hit.kind) {
                    MediaKind.IMAGE -> Icons.Rounded.Image
                    MediaKind.VIDEO -> Icons.Rounded.Videocam
                    MediaKind.AUDIO -> Icons.Rounded.MusicNote
                    MediaKind.DOCUMENT -> Icons.Rounded.Description
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 打开命中项：优先 content uri（MediaStore），路径走 FileProvider 的 ACTION_VIEW；
 * 与聊天文件链接同一套打开链路，不做额外预览。
 */
private fun openHit(context: Context, hit: MediaSearchService.Hit) {
    try {
        val uri = hit.contentUri?.let { Uri.parse(it) }
            ?: hit.path?.let {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(it))
            }
            ?: error("no uri")
        val mime = hit.mime.ifEmpty { "*/*" }
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = android.content.ClipData.newRawUri(hit.displayName, uri)
        })
    } catch (e: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.media_open_failed, e.message ?: ""),
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(Locale.getDefault(), bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(Locale.getDefault(), bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.1f KB".format(Locale.getDefault(), bytes / 1_000.0)
    else -> "$bytes B"
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(Locale.getDefault(), minutes, seconds)
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
