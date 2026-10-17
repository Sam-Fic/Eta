package io.github.mangi.eta.ui.adaptive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.data.model.GptSpeedMode

/** 次要前景色（摘要文本、尾部图标着色）。 */
@Composable
internal fun etaOnSurfaceVariant(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** 主要前景色（正文文本）。 */
@Composable
internal fun etaOnSurface(): Color = MaterialTheme.colorScheme.onSurface

/** 强调色。 */
@Composable
internal fun etaPrimary(): Color = MaterialTheme.colorScheme.primary

/** 高层级表面色（用于嵌套容器背景）。 */
@Composable
internal fun etaSurfaceContainerHigh(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/** 次要容器色（进度环 track、普通标签底色）。 */
@Composable
internal fun etaSecondaryContainer(): Color = MaterialTheme.colorScheme.secondaryContainer

/** 第三容器色（强调标签、待发送缩略图「×」圆钮底色）。 */
@Composable
internal fun etaTertiaryContainer(): Color = MaterialTheme.colorScheme.tertiaryContainer

/** 第三容器色上的前景。 */
@Composable
internal fun etaOnTertiaryContainer(): Color = MaterialTheme.colorScheme.onTertiaryContainer

/** 中层表面色（缩略图、嵌套卡片底）。 */
@Composable
internal fun etaSurfaceContainer(): Color = MaterialTheme.colorScheme.surfaceContainer

/**
 * GPT 速度档位的前景色（Material 3）。
 *
 * 只取官方 `colorScheme` 角色：快速＝`tertiary`、极速＝`error`、标准＝调用方原色。
 */
@Composable
internal fun etaGptSpeedColor(mode: GptSpeedMode, normal: Color): Color =
    when (mode) {
        GptSpeedMode.FAST -> MaterialTheme.colorScheme.tertiary
        GptSpeedMode.ULTRA_FAST -> MaterialTheme.colorScheme.error
        else -> normal
    }
