package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * M3 顶栏不启用毛玻璃，backdrop 恒为空。
 *
 * 保留 `Nothing?` 形参只是为了维持既有调用签名（`TopBarBackdrop` / `captureForTopBar` /
 * `topBarContainerColor` 的入参），调用方无需改动。
 */
@Composable
internal fun rememberTopBarBackdrop(): Nothing? = null

@Composable
internal fun TopBarBackdrop(
    backdrop: Nothing?,
    content: @Composable () -> Unit,
) {
    // 页面背景由壳层 Scaffold 显式取官方 surfaceContainer，这里同色；
    // 用与页面背景同色的渐变板代替不透明色块，顶端完全不透明、向下过渡到透明，
    // 与底部输入栏的渐变板对称，让滚入顶栏下方的消息自然淡出而不是被硬切。
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    Box(
        modifier = Modifier.background(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to surfaceColor,
                    TopBarGradientSolidFraction to surfaceColor,
                    1f to surfaceColor.copy(alpha = 0f),
                ),
            ),
        ),
    ) { content() }
}

internal fun Modifier.captureForTopBar(backdrop: Nothing?): Modifier = this

@Composable
internal fun topBarContainerColor(backdrop: Nothing?): Color =
    MaterialTheme.colorScheme.surface

/** M3 顶栏渐变板仍保持完全不透明的比例，与底部渐变板的不透明段对称。 */
private const val TopBarGradientSolidFraction = 0.5f
