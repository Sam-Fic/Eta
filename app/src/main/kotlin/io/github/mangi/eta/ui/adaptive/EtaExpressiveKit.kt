package io.github.mangi.eta.ui.adaptive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive 基础组件套件。
 *
 * 仅在 `MATERIAL3_EXPRESSIVE` 主题族下使用，全部基于 androidx.compose.material3 标准组件与官方令牌，
 * 颜色一律通过 `MaterialTheme.colorScheme` 角色引用，不写死颜色值；尺寸、形状、间距、
 * 动效曲线均取官方默认值（IconButtonDefaults / ButtonGroupDefaults / SplitButtonDefaults /
 * MaterialTheme.motionScheme），不自行定义。
 */

// ---------------------------------------------------------------------------
// 图标按钮
// ---------------------------------------------------------------------------

/**
 * M3 Expressive 图标按钮档位。容器与图标尺寸一律取官方 `IconButtonDefaults` 令牌：
 * XS 32/20、S 40/24、M 56/24、L 96/32、XL 136/40。
 */
enum class EtaIconSize { XS, S, M, L, XL }

/** 官方图标按钮容器尺寸（正方形）。 */
@Composable
private fun EtaIconSize.containerSize(): DpSize = when (this) {
    EtaIconSize.XS -> IconButtonDefaults.extraSmallContainerSize()
    EtaIconSize.S -> IconButtonDefaults.smallContainerSize()
    EtaIconSize.M -> IconButtonDefaults.mediumContainerSize()
    EtaIconSize.L -> IconButtonDefaults.largeContainerSize()
    EtaIconSize.XL -> IconButtonDefaults.extraLargeContainerSize()
}

/** 官方图标按钮图标尺寸。 */
@Composable
private fun EtaIconSize.iconSize(): Dp = when (this) {
    EtaIconSize.XS -> IconButtonDefaults.extraSmallIconSize
    EtaIconSize.S -> IconButtonDefaults.smallIconSize
    EtaIconSize.M -> IconButtonDefaults.mediumIconSize
    EtaIconSize.L -> IconButtonDefaults.largeIconSize
    EtaIconSize.XL -> IconButtonDefaults.extraLargeIconSize
}

/** 按钮外观：填充 / 色调 / 描边 / 标准。 */
enum class EtaButtonStyle { Filled, Tonal, Outlined, Standard }

/**
 * 自适应图标按钮（M3 Expressive）。
 *
 * @param stretch 为 true 时横向拉伸占满剩余宽度（用于“最后的按钮向右拉伸”的行布局）。
 */
@Composable
fun EtaIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: EtaIconSize = EtaIconSize.M,
    style: EtaButtonStyle = EtaButtonStyle.Tonal,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
) {
    val containerSize = size.containerSize()
    val iconSize = size.iconSize()
    val sizedModifier = if (fillWidth) {
        modifier.fillMaxWidth().heightIn(min = containerSize.height)
    } else {
        modifier.size(containerSize)
    }
    val content: @Composable () -> Unit = {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
    when (style) {
        EtaButtonStyle.Filled -> FilledIconButton(
            onClick = onClick,
            modifier = sizedModifier,
            enabled = enabled,
            content = content,
        )
        EtaButtonStyle.Tonal -> FilledTonalIconButton(
            onClick = onClick,
            modifier = sizedModifier,
            enabled = enabled,
            content = content,
        )
        EtaButtonStyle.Outlined -> OutlinedIconButton(
            onClick = onClick,
            modifier = sizedModifier,
            enabled = enabled,
            content = content,
        )
        EtaButtonStyle.Standard -> IconButton(
            onClick = onClick,
            modifier = sizedModifier,
            enabled = enabled,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// 拆分组件的连接形状
// ---------------------------------------------------------------------------

/** 相连按钮组中的位置，决定哪一侧保持胶囊、哪一侧收成内圆角。 */
enum class EtaGroupPosition { Single, Leading, Middle, Trailing }

/**
 * 官方相连按钮组形状：外侧 `CornerFull`、内侧 `small`（8dp），取自
 * `ButtonGroupDefaults`，不自定义圆角。
 */
@Composable
fun etaConnectedShape(position: EtaGroupPosition): Shape = when (position) {
    EtaGroupPosition.Single -> CircleShape
    EtaGroupPosition.Leading -> ButtonGroupDefaults.connectedLeadingButtonShape
    EtaGroupPosition.Middle -> MaterialTheme.shapes.small
    EtaGroupPosition.Trailing -> ButtonGroupDefaults.connectedTrailingButtonShape
}

/** 官方拆分按钮左段形状：外侧 `CornerFull`、内侧 `extraSmall`（4dp），按压时 12dp。 */
@Composable
fun etaSplitLeadingShape(
    height: Dp = SplitButtonDefaults.SmallContainerHeight,
): Shape = SplitButtonDefaults.leadingButtonShapesFor(height).shape

/** 官方拆分按钮右段（箭头）形状：外侧 `CornerFull`、内侧 `extraSmall`（4dp）。 */
@Composable
fun etaSplitTrailingShape(
    height: Dp = SplitButtonDefaults.SmallContainerHeight,
): Shape = SplitButtonDefaults.trailingButtonShapesFor(height).shape

// ---------------------------------------------------------------------------
// 拆分按钮
// ---------------------------------------------------------------------------

/**
 * M3 Expressive 拆分按钮：官方 `SplitButtonLayout` + `SplitButtonDefaults.LeadingButton/TrailingButton`。
 *
 * 尺寸、间距（2dp）、内圆角（4dp）、按压形变、禁用态与动效曲线全部来自官方令牌；
 * 箭头旋转使用官方 `MaterialTheme.motionScheme` 的空间动效规格。
 */
@Composable
fun EtaSplitButton(
    startContent: @Composable RowScope.() -> Unit,
    onStartClick: () -> Unit,
    endIcon: ImageVector,
    endContentDescription: String?,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    style: EtaButtonStyle = EtaButtonStyle.Tonal,
    startEnabled: Boolean = true,
    endEnabled: Boolean = true,
    height: Dp = SplitButtonDefaults.MediumContainerHeight,
) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(),
        label = "eta-split-arrow",
    )
    val leadingShapes = SplitButtonDefaults.leadingButtonShapesFor(height)
    val trailingShapes = SplitButtonDefaults.trailingButtonShapesFor(height)
    val colors = when (style) {
        EtaButtonStyle.Filled -> ButtonDefaults.buttonColors()
        EtaButtonStyle.Tonal -> ButtonDefaults.filledTonalButtonColors()
        EtaButtonStyle.Outlined, EtaButtonStyle.Standard -> ButtonDefaults.outlinedButtonColors()
    }
    val border = if (style == EtaButtonStyle.Outlined) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    } else {
        null
    }
    Box(modifier = modifier) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = onStartClick,
                    modifier = Modifier.height(height),
                    enabled = startEnabled,
                    shapes = leadingShapes,
                    colors = colors,
                    border = border,
                    content = startContent,
                )
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    onClick = { if (endEnabled) expanded = !expanded },
                    modifier = Modifier.size(height),
                    enabled = endEnabled,
                    shapes = trailingShapes,
                    colors = colors,
                    border = border,
                ) {
                    Icon(
                        imageVector = endIcon,
                        contentDescription = endContentDescription,
                        modifier = Modifier
                            .size(SplitButtonDefaults.trailingButtonIconSizeFor(height))
                            .graphicsLayer { rotationZ = arrowRotation },
                    )
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menu { expanded = false }
        }
    }
}

// ---------------------------------------------------------------------------
// 相连按钮组
// ---------------------------------------------------------------------------

/** 相连按钮组的容器：官方间距（`ButtonGroupDefaults.ConnectedSpaceBetween` = 2dp）、垂直居中。 */
@Composable
fun EtaConnectedGroupRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// 圆形进度
// ---------------------------------------------------------------------------

/**
 * 圆形进度环：`wavy = true` 时使用 M3 Expressive 的波浪样式。
 *
 * @param stroke 轨道粗细；未指定默认 4dp。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EtaCircularProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    stroke: Dp = 4.dp,
    wavy: Boolean = false,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val strokePx = with(LocalDensity.current) { stroke.toPx() }
    if (wavy) {
        CircularWavyProgressIndicator(
            progress = { clamped },
            modifier = modifier.size(size),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            stroke = Stroke(width = strokePx),
            trackStroke = Stroke(width = strokePx),
        )
    } else {
        CircularProgressIndicator(
            progress = { clamped },
            modifier = modifier.size(size),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            strokeWidth = stroke,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
