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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationTurnNavigationButton(
    direction: ConversationNavigationDirection,
    visible: Boolean,
    onStep: () -> Unit,
    onEdge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val down = direction == ConversationNavigationDirection.Down
    val stepLabel = stringResource(if (down) R.string.chat_next_user_message else R.string.chat_previous_user_message)
    val edgeLabel = stringResource(if (down) R.string.chat_go_to_bottom else R.string.chat_go_to_top)
    val view = LocalView.current
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
            // M3 Expressive：官方 FilledTonalIconButton 承担容器、官方 filledShape（整圆）、
            // 涟漪与最小可点区域；配色走 tertiaryContainer/onTertiaryContainer 强调档（用户指定）；
            // 容器尺寸取官方 IconButtonDefaults.smallContainerSize()，图标取官方 smallIconSize。
            // 长按跳到首/尾：官方图标按钮没有 onLongClick 槽位（material3 1.5.0-alpha29 中只有
            // ListItem 提供，而 ListItem 无法表达圆形浮动按钮），因此长按检测放在官方按钮内容里，
            // 只观察指针不消费，点击仍由官方按钮处理。
            // 长按语义由 ChatInputLongPressSlot 自己用 onLongClick 这个 key 声明：它挂在子节点上，
            // 而官方 IconButton 永不提供 onLongClick，父为 null 时子节点的 label/action 会合并进来。
            // 这里不要再在按钮 modifier 上补一份 onLongClick —— 同一 key 父非空会赢，反而把共享
            // 助手的语义整份遮掉，等于用一个副本覆盖了唯一的实现。
            FilledTonalIconButton(
                onClick = { TouchHaptics.click(view); onStep() },
                modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                ChatInputLongPressSlot(
                    enabled = true,
                    onLongPress = { TouchHaptics.longPress(view); onEdge() },
                    longClickLabel = edgeLabel,
                ) {
                    Icon(
                        imageVector = if (down) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                        contentDescription = stepLabel,
                        modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.size(40.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = stepLabel,
                        onLongClickLabel = edgeLabel,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        // Both gestures have one explicit feedback; never add the built-in long-press haptic.
                        hapticFeedbackEnabled = false,
                        onClick = { TouchHaptics.click(view); onStep() },
                        onLongClick = { TouchHaptics.longPress(view); onEdge() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (down) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                    contentDescription = stepLabel,
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
