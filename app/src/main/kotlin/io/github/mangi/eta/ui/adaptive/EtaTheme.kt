package io.github.mangi.eta.ui.adaptive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.data.model.GptSpeedMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 当前是否处于 Material 3 Expressive（标准组件）模式。
 *
 * 全量迁移到 M3 后恒为 true；miuix 分支已成为待删除的死代码（阶段 2 清理）。
 */
@Composable
internal fun isMaterial3Theme(): Boolean = true

/** 次要前景色（摘要文本、尾部图标着色）。 */
@Composable
internal fun etaOnSurfaceVariant(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    }

/** 主要前景色（正文文本）。 */
@Composable
internal fun etaOnSurface(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurface
    }

/** 强调色。 */
@Composable
internal fun etaPrimary(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.primary
    }

/** 高层级表面色（用于嵌套容器背景）。 */
@Composable
internal fun etaSurfaceContainerHigh(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }

/** 容器强调色（Badge、标签底色）。 */
@Composable
internal fun etaPrimaryContainer(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MiuixTheme.colorScheme.primaryContainer
    }

/** 容器强调色上的前景。 */
@Composable
internal fun etaOnPrimaryContainer(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MiuixTheme.colorScheme.onPrimaryContainer
    }

/** 次要容器色（进度环 track、普通标签底色）。 */
@Composable
internal fun etaSecondaryContainer(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MiuixTheme.colorScheme.secondaryContainer
    }

/** 次要容器色上的前景。 */
@Composable
internal fun etaOnSecondaryContainer(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MiuixTheme.colorScheme.onSecondaryContainer
    }

/** 第三容器色（强调标签、待发送缩略图「×」圆钮底色）。 */
@Composable
internal fun etaTertiaryContainer(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MiuixTheme.colorScheme.tertiaryContainer
    }

/** 第三容器色上的前景。 */
@Composable
internal fun etaOnTertiaryContainer(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MiuixTheme.colorScheme.onTertiaryContainer
    }

/** 中层表面色（缩略图、嵌套卡片底）。 */
@Composable
internal fun etaSurfaceContainer(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }

/** 变体表面色（MIUI X 的 surfaceVariant 语义，如指示条轨道）。 */
@Composable
internal fun etaSurfaceVariant(): Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MiuixTheme.colorScheme.surfaceVariant
    }

/**
 * GPT 速度档位的前景色（Material 3）。
 *
 * 只取官方 `colorScheme` 角色：快速＝`tertiary`、极速＝`error`、标准＝调用方原色；
 * MIUI X 下由调用方继续使用 `gptSpeedChipColor()` 的淡粉／酒红品牌色。
 */
@Composable
internal fun etaGptSpeedColor(mode: GptSpeedMode, normal: Color): Color =
    when (mode) {
        GptSpeedMode.FAST -> MaterialTheme.colorScheme.tertiary
        GptSpeedMode.ULTRA_FAST -> MaterialTheme.colorScheme.error
        else -> normal
    }
