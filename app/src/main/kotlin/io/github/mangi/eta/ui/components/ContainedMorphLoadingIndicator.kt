package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Material 3 ContainedLoadingIndicator 的同款动画：圆底里一块形状在变形、旋转。
 * RikkaHub 关闭「APP 图标风格」后用的就是这个。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ContainedMorphLoadingIndicator(
    modifier: Modifier = Modifier,
    indicatorSize: Dp = 28.dp,
    animate: Boolean = true,
    containerColor: Color? = null,
    indicatorColor: Color? = null,
) {
    // 为空表示沿用主题默认（primaryContainer / onPrimaryContainer）。
    // 调用方可以显式覆盖：输入区把本指示器当作“填充主色圆钮”的内容时，默认的
    // primaryContainer 会和同样取 primaryContainer 的外层浮动工具栏同色，圆底不可见。
    val resolvedContainerColor = containerColor ?: MaterialTheme.colorScheme.primaryContainer
    val resolvedIndicatorColor = indicatorColor ?: MaterialTheme.colorScheme.onPrimaryContainer
    // 使用官方 ContainedLoadingIndicator（表达性形变动画）。
    // 停止态（animate=false）表示「待机」，官方组件没有静态形态，继续用本地实现画出静止圆。
    if (animate) {
        ContainedLoadingIndicator(
            modifier = modifier.size(indicatorSize),
            containerColor = resolvedContainerColor,
            indicatorColor = resolvedIndicatorColor,
            containerShape = CircleShape,
        )
        return
    }

    val activityBlend = remember { Animatable(if (animate) 1f else 0f) }
    val morphProgress = remember { Animatable(0f) }
    val globalRotation = remember { Animatable(0f) }
    var currentShape by remember { mutableIntStateOf(0) }
    var morphRotationTarget by remember { mutableFloatStateOf(90f) }

    LaunchedEffect(animate) {
        if (!animate) {
            // Cancellation freezes the current frame; blend that frame back to the idle
            // circle before resetting the oscillator, rather than leaving a random shape.
            activityBlend.animateTo(0f, tween(180))
            currentShape = 0
            morphProgress.snapTo(0f)
            globalRotation.snapTo(0f)
            morphRotationTarget = 90f
            return@LaunchedEffect
        }
        launch { activityBlend.animateTo(1f, tween(180)) }
        launch {
            globalRotation.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = GlobalRotationMillis, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
        }
        launch {
            val morphSpec = spring<Float>(
                dampingRatio = 0.6f,
                stiffness = 200f,
                visibilityThreshold = 0.1f,
            )
            while (true) {
                val deferred = async {
                    val result = morphProgress.animateTo(1f, morphSpec)
                    if (result.endReason == AnimationEndReason.Finished) {
                        currentShape = (currentShape + 1) % IndicatorShapes.size
                        morphProgress.snapTo(0f)
                        morphRotationTarget = (morphRotationTarget + 90f) % 360f
                    }
                }
                delay(MorphIntervalMillis)
                deferred.await()
            }
        }
    }

    Box(
        modifier = modifier
            .size(indicatorSize)
            .clip(CircleShape)
            .background(resolvedContainerColor),
    ) {
        val from = IndicatorShapes[currentShape]
        val to = IndicatorShapes[(currentShape + 1) % IndicatorShapes.size]
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read the animated values in the draw phase. Hoisting morphProgress.value into
            // the composable body recomposed this indicator on every animation frame, and
            // it is composed while a response streams — exactly when frames are scarce.
            val progress = morphProgress.value
            val activity = activityBlend.value
            val radii = FloatArray(from.size) { index ->
                idleBlendedRadius(from[index], to[index], progress, activity)
            }
            val canvasSize = this.size
            val rotation = progress * 90f + morphRotationTarget + globalRotation.value
            val bounce = 1f + 0.08f * sin(progress * PI).toFloat() * activity
            rotate(rotation) {
                drawPath(
                    path = smoothPolarPath(
                        radii = radii,
                        center = Offset(canvasSize.width / 2f, canvasSize.height / 2f),
                        radius = minOf(canvasSize.width, canvasSize.height) * 0.40f * bounce,
                    ),
                    color = resolvedIndicatorColor,
                )
            }
        }
    }
}

private fun smoothPolarPath(
    radii: FloatArray,
    center: Offset,
    radius: Float,
): Path {
    val count = radii.size
    val points = Array(count) { index ->
        val angle = (index.toFloat() / count) * 2f * PI.toFloat() - PI.toFloat() / 2f
        val r = radius * radii[index]
        Offset(center.x + cos(angle) * r, center.y + sin(angle) * r)
    }
    val path = Path()
    if (count == 0) return path
    val mids = Array(count) { index ->
        val next = points[(index + 1) % count]
        Offset((points[index].x + next.x) / 2f, (points[index].y + next.y) / 2f)
    }
    path.moveTo(mids[0].x, mids[0].y)
    for (index in 0 until count) {
        val vertex = points[(index + 1) % count]
        val mid = mids[(index + 1) % count]
        path.quadraticTo(vertex.x, vertex.y, mid.x, mid.y)
    }
    path.close()
    return path
}

private const val GlobalRotationMillis = 4666
private const val MorphIntervalMillis = 650L

/** 8 个顶点的半径序列，对应圆 / 软星 / 三角 / 胶囊等之间的变形。 */
private val IndicatorShapes: List<FloatArray> = listOf(
    FloatArray(8) { 1f },
    floatArrayOf(1.00f, 0.62f, 1.00f, 0.62f, 1.00f, 0.62f, 1.00f, 0.62f),
    floatArrayOf(1.00f, 0.78f, 0.58f, 0.78f, 1.00f, 0.78f, 0.58f, 0.78f),
    floatArrayOf(0.86f, 1.00f, 0.70f, 1.00f, 0.86f, 1.00f, 0.70f, 1.00f),
    floatArrayOf(1.00f, 0.55f, 0.92f, 0.55f, 1.00f, 0.55f, 0.92f, 0.55f),
    floatArrayOf(0.72f, 1.00f, 0.72f, 0.62f, 1.00f, 0.72f, 1.00f, 0.62f),
    floatArrayOf(1.00f, 0.68f, 0.88f, 0.68f, 1.00f, 0.68f, 0.88f, 0.68f),
)

/** A zero activity blend always renders the original circle, regardless of the stopped frame. */
internal fun idleBlendedRadius(from: Float, to: Float, progress: Float, activity: Float): Float {
    val animatedRadius = from + (to - from) * progress
    return 1f + (animatedRadius - 1f) * activity.coerceIn(0f, 1f)
}
