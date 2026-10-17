package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive 基础组件套件。
 *
 * 全部基于 androidx.compose.material3 标准组件与官方令牌，
 * 颜色一律通过 `MaterialTheme.colorScheme` 角色引用，不写死颜色值；尺寸、形状、间距、
 * 动效曲线均取官方默认值（IconButtonDefaults / ButtonGroupDefaults /
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

/** 按钮外观：填充 / 色调 / 色调强调（tertiary 档）/ 描边 / 标准。 */
enum class EtaButtonStyle { Filled, Tonal, TonalEmphasis, Outlined, Standard }

/**
 * 自适应图标按钮（M3 Expressive）。
 *
 * @param style 按钮档位；`TonalEmphasis` 在 Tonal 基础上把容器提到 tertiaryContainer
 *   强调档（官方 `FilledTonalIconButton` + 官方 `filledTonalIconButtonColors` 覆盖，
 *   形状/尺寸/涟漪仍走官方默认）。
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
        EtaButtonStyle.TonalEmphasis -> FilledTonalIconButton(
            onClick = onClick,
            modifier = sizedModifier,
            enabled = enabled,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
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

/**
 * 相连按钮组统一配色（用户指定）：官方默认 secondaryContainer 档，选中态同色，
 * 与页面其他 FilledTonal 系组件同色阶。
 */
@Composable
fun etaConnectedGroupColors() = ToggleButtonDefaults.colors(
    containerColor = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

/**
 * 相连按钮组里的图标动作（KernelSU AppProfile 同款官方模式）：官方 `ToggleButton` +
 * `ButtonGroupDefaults` 连体形状（首位/中间/末位）。纯图标按钮用官方
 * `extraSmallContainerSize` 强制成等宽等高的方形容器（顶栏组用 Small/40dp，消息操作条
 * 再小一号用 XS/32dp），`contentPadding(0.dp)` 置零官方文字按钮的水平内边距。
 * 单按钮（`count <= 1`）时退回官方默认全圆角形状。
 *
 * 此前实现私藏在 `AgentTurnFooter.kt`（M3ConnectedIconAction / M3ConnectedGroupColors），
 * 现收编进本套件作为唯一出处。
 */
@Composable
fun EtaConnectedIconAction(
    onClick: () -> Unit,
    enabled: Boolean,
    index: Int,
    count: Int,
    content: @Composable RowScope.() -> Unit,
) {
    val shapes: ToggleButtonShapes? = when {
        count <= 1 -> null
        index <= 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        index >= count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }
    val colors = etaConnectedGroupColors()
    if (shapes == null) {
        ToggleButton(
            checked = false,
            onCheckedChange = { onClick() },
            enabled = enabled,
            modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
            buttonSize = ToggleButtonSize.Small,
            contentPadding = PaddingValues(0.dp),
            colors = colors,
            content = content,
        )
    } else {
        ToggleButton(
            checked = false,
            onCheckedChange = { onClick() },
            enabled = enabled,
            modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
            buttonSize = ToggleButtonSize.Small,
            shapes = shapes,
            contentPadding = PaddingValues(0.dp),
            colors = colors,
            content = content,
        )
    }
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
