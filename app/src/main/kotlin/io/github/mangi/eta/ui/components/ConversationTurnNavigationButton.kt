package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话跳转按钮组：上一轮 / 下一轮两颗连体图标按钮。
 *
 * 每颗按钮各自按「该方向是否还有轮次」独立启用：可点击时用 tertiaryContainer（强调），
 * 不可点击时用 secondaryContainer（默认档），二者只靠容器色区分可用性。两颗粒子在
 * 水平方向相邻成组（首位 / 末位连体形状），右端的「下一轮」与输入栏右下角通话圆钮同心。
 *
 * 长按语义不变：长按「上一轮」跳到会话开头、长按「下一轮」跳到会话末尾。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationTurnNavigationButton(
    upEnabled: Boolean,
    downEnabled: Boolean,
    visible: Boolean,
    onStep: (ConversationNavigationDirection) -> Unit,
    onEdge: (ConversationNavigationDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val material3 = isMaterial3Theme()
    // M3 Expressive：进出场动效统一取官方 motionScheme 规格；miuix 分支保留原时长。
    val fadeInSpec: FiniteAnimationSpec<Float> =
        if (material3) MaterialTheme.motionScheme.defaultSpatialSpec<Float>() else tween(160)
    val scaleInSpec: FiniteAnimationSpec<Float> =
        if (material3) MaterialTheme.motionScheme.defaultSpatialSpec<Float>() else tween(180)
    val fadeOutSpec: FiniteAnimationSpec<Float> =
        if (material3) MaterialTheme.motionScheme.defaultSpatialSpec<Float>() else tween(100)
    val scaleOutSpec: FiniteAnimationSpec<Float> =
        if (material3) MaterialTheme.motionScheme.defaultSpatialSpec<Float>() else tween(120)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(fadeInSpec) + scaleIn(scaleInSpec, initialScale = 0.82f),
        exit = fadeOut(fadeOutSpec) + scaleOut(scaleOutSpec, targetScale = 0.86f),
    ) {
        if (material3) {
            // 用户指定：可点击 = tertiaryContainer，不可点击 = secondaryContainer。
            // 未选中态（containerColor）即启用配色，disabledContainerColor 即不可点击配色；
            // checked 恒为 false，选中态参数只是补齐签名。
            val colors = ToggleButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                M3TurnNavigationToggle(
                    direction = ConversationNavigationDirection.Up,
                    enabled = upEnabled,
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    colors = colors,
                    onStep = onStep,
                    onEdge = onEdge,
                )
                M3TurnNavigationToggle(
                    direction = ConversationNavigationDirection.Down,
                    enabled = downEnabled,
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    colors = colors,
                    onStep = onStep,
                    onEdge = onEdge,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                MiuixTurnNavigationButton(
                    direction = ConversationNavigationDirection.Up,
                    enabled = upEnabled,
                    leading = true,
                    onStep = onStep,
                    onEdge = onEdge,
                )
                MiuixTurnNavigationButton(
                    direction = ConversationNavigationDirection.Down,
                    enabled = downEnabled,
                    leading = false,
                    onStep = onStep,
                    onEdge = onEdge,
                )
            }
        }
    }
}

/**
 * 连体组里的单颗跳转按钮（KernelSU AppProfile 同款官方模式）：官方 `ToggleButton` 承担
 * 容器、连体形状、涟漪与最小可点区域，尺寸取官方 `smallContainerSize`（40dp），
 * `contentPadding(0.dp)` 清掉文字按钮的水平内边距。
 *
 * 长按检测放在官方按钮内容里（官方按钮没有 onLongClick 槽位，material3 1.5.0-alpha29 中
 * 只有 ListItem 提供），只观察指针不消费，点击仍由官方按钮处理；长按语义由
 * ChatInputLongPressSlot 自己声明的 onLongClick key 提供，不要在按钮 modifier 上补一份，
 * 否则父非空会整份遮掉共享助手的语义。
 */
@Composable
private fun M3TurnNavigationToggle(
    direction: ConversationNavigationDirection,
    enabled: Boolean,
    shapes: ToggleButtonShapes,
    colors: ToggleButtonColors,
    onStep: (ConversationNavigationDirection) -> Unit,
    onEdge: (ConversationNavigationDirection) -> Unit,
) {
    val down = direction == ConversationNavigationDirection.Down
    val stepLabel = stringResource(if (down) R.string.chat_next_user_message else R.string.chat_previous_user_message)
    val edgeLabel = stringResource(if (down) R.string.chat_go_to_bottom else R.string.chat_go_to_top)
    val view = LocalView.current
    ToggleButton(
        checked = false,
        onCheckedChange = { TouchHaptics.click(view); onStep(direction) },
        enabled = enabled,
        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
        buttonSize = ToggleButtonSize.Small,
        shapes = shapes,
        contentPadding = PaddingValues(0.dp),
        colors = colors,
    ) {
        ChatInputLongPressSlot(
            enabled = enabled,
            onLongPress = { TouchHaptics.longPress(view); onEdge(direction) },
            longClickLabel = edgeLabel,
        ) {
            Icon(
                imageVector = if (down) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                contentDescription = stepLabel,
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
        }
    }
}

/**
 * miuix 分支：没有官方连体形状令牌，用一对 40dp 圆角方块拼成组（首位方角在左、末位方角
 * 在右），可用性同样靠容器色区分——可点击用 surfaceContainerHigh，不可点击退回 surface。
 * 命中区域、role、长按语义与 M3 分支一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiuixTurnNavigationButton(
    direction: ConversationNavigationDirection,
    enabled: Boolean,
    leading: Boolean,
    onStep: (ConversationNavigationDirection) -> Unit,
    onEdge: (ConversationNavigationDirection) -> Unit,
) {
    val down = direction == ConversationNavigationDirection.Down
    val stepLabel = stringResource(if (down) R.string.chat_next_user_message else R.string.chat_previous_user_message)
    val edgeLabel = stringResource(if (down) R.string.chat_go_to_bottom else R.string.chat_go_to_top)
    val view = LocalView.current
    val shape = if (leading) {
        RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 4.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp, topStart = 4.dp, bottomStart = 4.dp)
    }
    Box(
        modifier = Modifier.size(40.dp)
            .clip(shape)
            .background(
                if (enabled) MiuixTheme.colorScheme.surfaceContainerHigh
                else MiuixTheme.colorScheme.surface,
            )
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = stepLabel,
                onLongClickLabel = edgeLabel,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // Both gestures have one explicit feedback; never add the built-in long-press haptic.
                hapticFeedbackEnabled = false,
                onClick = { TouchHaptics.click(view); onStep(direction) },
                onLongClick = { TouchHaptics.longPress(view); onEdge(direction) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (down) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
            contentDescription = stepLabel,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)
            },
        )
    }
}
