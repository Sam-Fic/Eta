package io.github.mangi.eta.ui.pages.providers

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.ProviderBalanceState
import io.github.mangi.eta.data.repository.formatBalanceDisplay
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Text as M3Text

/*
 * 顶部栏余额的变化动画，与静态的 ProviderBalanceIndicator 分开存放：
 * 静态指示器保持中性色、无定时逻辑；这里只在收到新的 BalanceChange 时短暂播放。
 *
 * 全部动效曲线都取自 MaterialTheme.motionScheme 的官方 Spec。
 */

/** 余额变化动画的强度档位。 */
internal enum class BalanceEffectLevel { Increase, Light, Strong }

/** 超过这个时长才被 UI 看到的变化不再播放。 */
private const val BalanceChangeStaleMs = 5_000L

/** 扣费达到这个金额即使用强档动画。 */
private val StrongDeductionThreshold: BigDecimal = BigDecimal.ONE

/** 按两位小数后为 0 的变化不值得提示，返回 null。 */
internal fun balanceEffectLevel(delta: BigDecimal): BalanceEffectLevel? {
    val rounded = delta.setScale(2, RoundingMode.HALF_UP)
    return when {
        rounded.signum() == 0 -> null
        rounded.signum() > 0 -> BalanceEffectLevel.Increase
        rounded.negate() >= StrongDeductionThreshold -> BalanceEffectLevel.Strong
        else -> BalanceEffectLevel.Light
    }
}

/** 形如 "-0.52"、"+5.00"；不使用千分位，保持飘字短小。 */
internal fun formatBalanceDelta(delta: BigDecimal): String {
    val magnitude = delta.abs().setScale(2, RoundingMode.HALF_UP).toPlainString()
    return if (delta.signum() < 0) "-$magnitude" else "+$magnitude"
}

private fun Context.animatorsEnabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

private data class BalanceBurst(val delta: BigDecimal, val level: BalanceEffectLevel)

/** 余额上升色：取官方 tertiary 角色色。 */
@Composable
private fun balanceIncreaseColor(): Color = MaterialTheme.colorScheme.tertiary

/** 余额中性色：取官方 onSurfaceVariant 角色色。 */
@Composable
private fun balanceNeutralColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** 余额下降色：取官方 error 角色色。 */
@Composable
private fun balanceDecreaseColor(): Color = MaterialTheme.colorScheme.error

@Composable
private fun floatRiseSpec(): FiniteAnimationSpec<Float> =
    MaterialTheme.motionScheme.slowSpatialSpec()

@Composable
private fun floatFadeSpec(): FiniteAnimationSpec<Float> =
    MaterialTheme.motionScheme.defaultEffectsSpec()

@Composable
private fun pulseScaleSpec(): FiniteAnimationSpec<Float> =
    MaterialTheme.motionScheme.fastSpatialSpec()

@Composable
private fun bounceSpec(): FiniteAnimationSpec<Float> =
    MaterialTheme.motionScheme.defaultSpatialSpec()

@Composable
private fun rollSpec(): FiniteAnimationSpec<Float> =
    MaterialTheme.motionScheme.defaultSpatialSpec()

@Composable
private fun nudgeSpec(): FiniteAnimationSpec<Float> =
    MaterialTheme.motionScheme.fastSpatialSpec()

@Composable
private fun shakeSpec(): FiniteAnimationSpec<Float> =
    // 官方 motionScheme 不提供阻尼振荡曲线，退化为一次默认空间位移。
    MaterialTheme.motionScheme.defaultSpatialSpec()

@Composable
private fun amountColorSpec(fast: Boolean): FiniteAnimationSpec<Color> =
    if (fast) {
        MaterialTheme.motionScheme.fastEffectsSpec()
    } else {
        MaterialTheme.motionScheme.defaultEffectsSpec()
    }

@Composable
internal fun AnimatedProviderBalanceIndicator(
    state: ProviderBalanceState,
    amount: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val neutralColor = balanceNeutralColor()
    val decreaseColor = balanceDecreaseColor()
    val change = state.lastChange

    // 动效规格在组合期解析一次，供 LaunchedEffect 内的协程复用。
    val riseSpec = floatRiseSpec()
    val fadeSpec = floatFadeSpec()
    val scaleSpec = pulseScaleSpec()
    val bounce = bounceSpec()
    val roll = rollSpec()
    val nudge = nudgeSpec()
    val shake = shakeSpec()

    // 首次组合时把已有变化视为已看过，避免打开页面就重播旧事件。
    // 不用 rememberSaveable：进程重建后 seq 从头计数，恢复旧值会吞掉新事件。
    var lastSeenSeq by remember { mutableLongStateOf(change?.seq ?: 0L) }
    var burst by remember { mutableStateOf<BalanceBurst?>(null) }
    var rollingActive by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf(false) }
    val rolling = remember { Animatable(0f) }
    val amountShift = remember { Animatable(0f) }
    val iconRotation = remember { Animatable(0f) }
    val floatOffset = remember { Animatable(0f) }
    val floatAlpha = remember { Animatable(0f) }
    val floatScale = remember { Animatable(1f) }
    val amountColor by animateColorAsState(
        targetValue = if (highlight) decreaseColor else neutralColor,
        animationSpec = amountColorSpec(fast = highlight),
        label = "balanceAmountColor",
    )

    LaunchedEffect(change?.seq) {
        val incoming = change ?: return@LaunchedEffect
        if (incoming.seq <= lastSeenSeq) return@LaunchedEffect
        lastSeenSeq = incoming.seq
        // 新事件会取消上一次播放；未播完的差值在这里合并。
        val merged = (burst?.delta ?: BigDecimal.ZERO) + incoming.delta
        val level = balanceEffectLevel(merged)
        val stale = System.currentTimeMillis() - incoming.atMillis > BalanceChangeStaleMs
        // 被打断的上一次播放可能停在中途，先复位再决定是否播放。
        rollingActive = false
        highlight = false
        iconRotation.snapTo(0f)
        amountShift.snapTo(0f)
        if (level == null || stale || !context.animatorsEnabled()) {
            burst = null
            floatAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        burst = BalanceBurst(merged, level)
        floatOffset.snapTo(0f)
        floatScale.snapTo(1f)
        floatAlpha.snapTo(1f)
        val riseDistance = with(density) { (if (level == BalanceEffectLevel.Strong) 8.dp else 6.dp).toPx() }
        coroutineScope {
            launch {
                floatOffset.animateTo(
                    targetValue = -riseDistance,
                    animationSpec = riseSpec,
                )
            }
            launch {
                delay(if (level == BalanceEffectLevel.Strong) 900L else 550L)
                floatAlpha.animateTo(0f, fadeSpec)
            }
            val target = state.amountValue
            if (level == BalanceEffectLevel.Strong) {
                launch {
                    floatScale.animateTo(1.2f, scaleSpec)
                    floatScale.animateTo(1f, bounce)
                }
                launch {
                    iconRotation.snapTo(0f)
                    iconRotation.animateTo(
                        targetValue = 0f,
                        animationSpec = shake,
                    )
                }
                launch {
                    highlight = true
                    delay(500L)
                    highlight = false
                }
                if (target != null) {
                    launch {
                        rolling.snapTo((target - merged).toFloat())
                        rollingActive = true
                        rolling.animateTo(
                            targetValue = target.toFloat(),
                            animationSpec = roll,
                        )
                        rollingActive = false
                    }
                }
            } else {
                launch {
                    amountShift.snapTo(1f)
                    amountShift.animateTo(0f, nudge)
                }
            }
        }
        burst = null
    }

    // 强档事件被 LaunchedEffect 接手前，先显示旧值，避免新数字闪一帧再滚动。
    val pendingStrongFrom = change
        ?.takeIf { it.seq > lastSeenSeq }
        ?.let { incoming ->
            val target = state.amountValue ?: return@let null
            val merged = (burst?.delta ?: BigDecimal.ZERO) + incoming.delta
            if (balanceEffectLevel(merged) == BalanceEffectLevel.Strong) target - merged else null
        }
    val displayText = when {
        rollingActive -> formatBalanceDisplay(rolling.value.toString())
        pendingStrongFrom != null -> formatBalanceDisplay(pendingStrongFrom.toPlainString())
        else -> amount
    }

    Box(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Savings,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(12.dp)
                    .graphicsLayer { rotationZ = iconRotation.value },
                tint = amountColor,
            )
            M3Text(
                text = displayText,
                style = MaterialTheme.typography.labelSmall,
                color = amountColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    val shift = amountShift.value
                    translationY = shift * 4.dp.toPx()
                    alpha = 1f - 0.5f * shift
                },
            )
        }
        val current = burst
        if (current != null) {
            val strong = current.level == BalanceEffectLevel.Strong
            val deltaColor = if (current.level == BalanceEffectLevel.Increase) {
                balanceIncreaseColor()
            } else {
                decreaseColor
            }
            val deltaModifier = Modifier
                .align(Alignment.BottomEnd)
                // 零尺寸占位：飘字画在金额下方，不参与指示器测量，顶栏宽度不会跳动。
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints())
                    layout(0, 0) { placeable.placeRelative(-placeable.width, 0) }
                }
                .graphicsLayer {
                    translationY = floatOffset.value
                    alpha = floatAlpha.value
                    scaleX = floatScale.value
                    scaleY = floatScale.value
                    transformOrigin = TransformOrigin(1f, 0f)
                }
                .clearAndSetSemantics {}
            M3Text(
                text = formatBalanceDelta(current.delta),
                style = if (strong) {
                    MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.labelSmall
                },
                color = deltaColor,
                maxLines = 1,
                modifier = deltaModifier,
            )
        }
    }
}
