package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One visual card, but still one independently composed lazy item per step. */
internal enum class WorkProcessCardPart(val startsCard: Boolean, val endsCard: Boolean) {
    Whole(true, true), First(true, false), Middle(false, false), Last(false, true),
}

internal data class WorkProcessCardGeometry(
    val topExtension: Float,
    val bottomExtension: Float,
    val virtualHeight: Float,
)

/** Move joined edges outside the row's clip; never draw a horizontal seam. */
internal fun workProcessCardGeometry(
    part: WorkProcessCardPart,
    height: Float,
    radius: Float,
    strokeWidth: Float,
): WorkProcessCardGeometry {
    val extension = radius * 2f + strokeWidth
    val top = if (part.startsCard) 0f else extension
    val bottom = if (part.endsCard) 0f else extension
    return WorkProcessCardGeometry(top, bottom, height + top + bottom)
}

@Composable
internal fun WorkProcessCardSlice(
    part: WorkProcessCardPart,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    val border = MiuixTheme.colorScheme.outline.copy(alpha = 0.50f)
    val squircle = isSquircleEnabled()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = if (part.startsCard) 4.dp else 0.dp,
                bottom = if (part.endsCard) 4.dp else 0.dp,
            )
            // Even an empty summary tail must contain the whole 15.4dp corner tile.
            .heightIn(min = 16.dp)
            .drawWithCache {
                val radius = 14.dp.toPx()
                val strokeWidth = 0.5.dp.toPx()
                val inset = strokeWidth / 2f
                val geometry = workProcessCardGeometry(part, size.height, radius, strokeWidth)
                val fillPath = Path().apply {
                    addSquircleRect(size.width, geometry.virtualHeight, radius, squircleEnabled = squircle)
                    translate(Offset(0f, -geometry.topExtension))
                }
                val borderPath = Path().apply {
                    addSquircleRect(
                        (size.width - strokeWidth).coerceAtLeast(0f),
                        (geometry.virtualHeight - strokeWidth).coerceAtLeast(0f),
                        (radius - inset).coerceAtLeast(0f),
                        squircleEnabled = squircle,
                    )
                    translate(Offset(inset, inset - geometry.topExtension))
                }
                val stroke = Stroke(strokeWidth)
                // 中间段的圆角都被推到裁剪区外，可见区域内 fillPath 就是整矩形；
                // 此时用 clipRect 即可，避免展开动画每帧对整段内容做路径裁剪。
                val needsPathClip = part.startsCard || part.endsCard
                onDrawWithContent {
                    clipRect {
                        drawPath(fillPath, surface)
                        if (needsPathClip) {
                            // 填充路径含描边外沿。文字若画到那里，会露出卡片底边一点点。
                            clipPath(borderPath) { this@onDrawWithContent.drawContent() }
                        } else {
                            this@onDrawWithContent.drawContent()
                        }
                        drawPath(borderPath, border, style = stroke)
                    }
                }
            },
    ) { content() }
}
