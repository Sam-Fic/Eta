package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import io.github.mangi.eta.ui.app.LocalTopBarBlurStyle
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberTopBarBackdrop(): LayerBackdrop? {
    // Material 3 不启用毛玻璃顶栏（AgentAppTheme 里 LocalBlurEnabled 恒为 false），
    // 这里提前返回，避免在 M3 主题下还去读 MiuixTheme 的调色盘。
    if (isMaterial3Theme()) return null
    if (!LocalBlurEnabled.current || !isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
internal fun TopBarBackdrop(
    backdrop: LayerBackdrop?,
    content: @Composable () -> Unit,
) {
    // 渐变板/底板色必须与当前主题族的页面背景同源：Material 3 页面背景由壳层 Scaffold
    // 显式取官方 surfaceContainer（AgentAppShell），这里同色；MIUI X 页面背景是 miuix
    // Scaffold 默认的 MiuixTheme.surface。混用会让渐变板与页面背景不同色，滚动时露出接缝。
    val material3 = isMaterial3Theme()
    val surfaceColor = if (material3) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MiuixTheme.colorScheme.surface
    }
    val modifier = when {
        // Material 3 不做毛玻璃：用与页面背景同色的渐变板代替不透明色块，
        // 顶端完全不透明、向下过渡到透明，与底部输入栏的渐变板对称，
        // 让滚入顶栏下方的消息自然淡出而不是被硬切。MIUI X 保持原有不透明底色。
        backdrop == null && material3 -> {
            Modifier.background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to surfaceColor,
                        TopBarGradientSolidFraction to surfaceColor,
                        1f to surfaceColor.copy(alpha = 0f),
                    ),
                ),
            )
        }
        backdrop == null -> Modifier.background(surfaceColor)
        LocalTopBarBlurStyle.current == AppearanceTopBarBlurStyle.PROGRESSIVE -> {
            Modifier.progressiveTextureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                blurRadius = ProgressiveTopBarBlurRadius,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(surfaceColor.copy(alpha = ProgressiveTopBarSurfaceAlpha)),
                    ),
                ),
            )
        }
        else -> {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = TopBarBlurRadius,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(surfaceColor.copy(alpha = TopBarSurfaceAlpha)),
                    ),
                ),
            )
        }
    }
    Box(modifier = modifier) { content() }
}

internal fun Modifier.captureForTopBar(backdrop: LayerBackdrop?): Modifier =
    if (backdrop == null) this else layerBackdrop(backdrop)

@Composable
internal fun topBarContainerColor(backdrop: LayerBackdrop?): Color =
    if (backdrop == null) MiuixTheme.colorScheme.surface else Color.Transparent

private const val TopBarBlurRadius = 25f
private const val TopBarSurfaceAlpha = 0.8f
private const val ProgressiveTopBarBlurRadius = 10f
private const val ProgressiveTopBarSurfaceAlpha = 0.3f

/** M3 顶栏渐变板仍保持完全不透明的比例，与底部渐变板的不透明段对称。 */
private const val TopBarGradientSolidFraction = 0.5f
