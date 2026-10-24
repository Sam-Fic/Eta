package io.github.mangi.eta.ui.components

import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.mangi.eta.ui.adaptive.EtaMenuGroupSurface
import kotlinx.coroutines.delay

private val MenuCornerRadius = 16.dp
private val MenuMinWidth = 180.dp
private val MenuMaxWidth = 280.dp
private val MenuScreenMargin = 12.dp
private const val MenuExitDurationMs = 160L
private const val MenuDismissGuardMs = 80L
private val MenuExitAnimationSpec = tween<Float>(durationMillis = 150)
private val MenuEnterFractionSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
private val MenuEnterAlphaSpec = tween<Float>(durationMillis = 100)
private val MenuExitAlphaSpec = tween<Float>(durationMillis = 100)

@Stable
internal class EtaMenuState {
    var expanded by mutableStateOf(false)
        private set

    private var lastDismissUptimeMs = 0L

    fun onAnchorClick() {
        if (expanded) {
            dismiss()
            return
        }
        if (SystemClock.uptimeMillis() - lastDismissUptimeMs < MenuDismissGuardMs) {
            return
        }
        expanded = true
    }

    fun dismiss() {
        if (!expanded) return
        expanded = false
        lastDismissUptimeMs = SystemClock.uptimeMillis()
    }
}

@Composable
internal fun rememberEtaMenuState(): EtaMenuState = remember { EtaMenuState() }

@Composable
internal fun EtaDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    alignEnd: Boolean = false,
    preferAbove: Boolean = false,
    minWidth: Dp = MenuMinWidth,
    maxWidth: Dp = MenuMaxWidth,
    maxHeight: Dp? = null,
    focusable: Boolean = true,
    /** 条目带单选勾选时置 true：保持官方紧凑纵向边距；动作菜单默认补到上下 4dp。 */
    selectableMenu: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var mounted by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded) {
            mounted = true
        } else if (mounted) {
            delay(MenuExitDurationMs)
            mounted = false
        }
    }
    if (!mounted) return

    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded

    val density = LocalDensity.current
    val offsetXPx = with(density) { offset.x.roundToPx() }
    val offsetYPx = with(density) { offset.y.roundToPx() }
    val screenMarginPx = with(density) { MenuScreenMargin.roundToPx() }
    val positionProvider = remember(offsetXPx, offsetYPx, alignEnd, preferAbove, screenMarginPx) {
        EtaMenuPositionProvider(offsetXPx, offsetYPx, alignEnd, preferAbove, screenMarginPx)
    }
    val transition = rememberTransition(visibleState, label = "EtaDropdownMenu")
    val fraction = transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) {
                MenuEnterFractionSpec
            } else {
                MenuExitAnimationSpec
            }
        },
        label = "etaMenuFraction",
    ) { visible -> if (visible) 1f else 0f }
    val alpha = transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) {
                MenuEnterAlphaSpec
            } else {
                MenuExitAlphaSpec
            }
        },
        label = "etaMenuAlpha",
    ) { visible -> if (visible) 1f else 0f }
    val transformOrigin = remember(alignEnd, preferAbove) {
        TransformOrigin(
            pivotFractionX = if (alignEnd) 1f else 0f,
            pivotFractionY = if (preferAbove) 1f else 0f,
        )
    }
    val popupProperties = remember(focusable, expanded) {
        var flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (!expanded) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        PopupProperties(
            flags = flags,
            inheritSecurePolicy = true,
            dismissOnBackPress = focusable,
            dismissOnClickOutside = expanded,
            excludeFromSystemGesture = true,
            usePlatformDefaultWidth = false,
        )
    }

    Popup(
        onDismissRequest = onDismissRequest,
        popupPositionProvider = positionProvider,
        properties = popupProperties,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            // 容器用官方 Expressive 分组（surfaceContainerLow + 官方组圆角 + 3dp shadow +
            // 上下 2dp 紧凑内边距），与「上下文压缩 → 请求接口」那个下拉同款；
            // 自定义的只有弹层定位与进出场动画。
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        val progress = fraction.value
                        val scale = 0.15f + 0.85f * progress
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha.value
                        this.transformOrigin = transformOrigin
                    }
                    .then(modifier)
                    .width(IntrinsicSize.Max)
                    .widthIn(min = minWidth, max = maxWidth)
                    .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
                    .etaMenuClipReveal(
                        fractionProgress = {
                            if (visibleState.targetState) fraction.value else 1f
                        },
                        preferAbove = preferAbove,
                        cornerRadius = MenuCornerRadius,
                    ),
            ) {
                EtaMenuGroupSurface(scrollable = true, selectableMenu = selectableMenu) {
                    content()
                }
            }
        }
    }
}

private fun Modifier.etaMenuClipReveal(
    fractionProgress: () -> Float,
    preferAbove: Boolean,
    cornerRadius: Dp,
): Modifier = drawWithCache {
    val path = Path()
    val radiusPx = cornerRadius.toPx()
    onDrawWithContent {
        val progress = fractionProgress().coerceIn(0f, 1f)
        if (progress <= 0f) return@onDrawWithContent
        val visibleHeight = size.height * progress
        if (visibleHeight <= 0f) return@onDrawWithContent
        val clipStart = if (preferAbove) size.height * (1f - progress) else 0f
        path.rewind()
        path.addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = visibleHeight,
                cornerRadius = CornerRadius(radiusPx),
            ),
        )
        if (clipStart == 0f) {
            clipPath(path) {
                this@onDrawWithContent.drawContent()
            }
        } else {
            translate(top = clipStart) {
                clipPath(path) {
                    translate(top = -clipStart) {
                        this@onDrawWithContent.drawContent()
                    }
                }
            }
        }
    }
}

private class EtaMenuPositionProvider(
    private val offsetXPx: Int,
    private val offsetYPx: Int,
    private val alignEnd: Boolean,
    private val preferAbove: Boolean,
    private val screenMarginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = screenMarginPx
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val rawX = if (alignEnd) {
            anchorBounds.right - popupContentSize.width + offsetXPx
        } else {
            anchorBounds.left + offsetXPx
        }
        val x = rawX.coerceIn(margin, maxX)
        val belowY = anchorBounds.bottom + offsetYPx
        val aboveY = anchorBounds.top - popupContentSize.height - offsetYPx
        val fitsBelow = belowY + popupContentSize.height + margin <= windowSize.height
        val fitsAbove = aboveY >= margin
        val y = when {
            preferAbove && fitsAbove -> aboveY
            fitsBelow && !preferAbove -> belowY
            fitsAbove -> aboveY
            else -> belowY
        }
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        return IntOffset(x, y.coerceIn(margin, maxY))
    }
}
