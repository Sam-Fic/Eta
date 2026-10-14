package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * KernelSU 式 Expressive 按钮组件（仅 Material 3 分支有意义；调用方自行做主题分流）：
 *
 * - [EtaTopBarBackButton]：顶栏返回钮带 surfaceContainerHighest 底色圆形，返回键从
 *   「裸图标」升级为「可感知的可点目标」；
 * - [EtaExpressiveSwitch]：选中显示 Check、关闭显示 Close 的拇指图标 + 完整禁用态配色；
 * - [EtaExpressiveToggleButton]：选中 primary / 未选 surfaceContainerHighest 的切换钮。
 */

/** 顶栏返回钮：surfaceContainerHighest 底色圆形 + onSurfaceVariant 图标（KernelSU TopBarBackButton）。 */
@Composable
fun EtaTopBarBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    IconButton(
        modifier = modifier.padding(horizontal = 10.dp),
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = contentDescription,
        )
    }
}

/**
 * Expressive 开关：拇指内常驻状态图标（选中 Check / 关闭 Close），配色取 KernelSU 的
 * expressiveSwitchColors —— 选中图标 primary、未选图标 surfaceContainerHighest，
 * 禁用态全部由官方角色降透明表达。
 */
@Composable
fun EtaExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showThumbIcon: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = if (showThumbIcon && (checked || enabled)) {
            {
                Icon(
                    imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        } else {
            null
        },
        enabled = enabled,
        colors = etaExpressiveSwitchColors(),
    )
}

@Composable
private fun etaExpressiveSwitchColors(): SwitchColors {
    val scheme = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedIconColor = scheme.primary,
        uncheckedIconColor = scheme.surfaceContainerHighest,
        disabledCheckedThumbColor = scheme.surface.copy(alpha = 0.38f),
        disabledCheckedTrackColor = scheme.onSurface.copy(alpha = 0.12f),
        disabledCheckedIconColor = scheme.onSurface.copy(alpha = 0.12f),
        disabledUncheckedThumbColor = scheme.outline.copy(alpha = 0.38f),
        disabledUncheckedTrackColor = scheme.surfaceContainerHighest.copy(alpha = 0.12f),
        disabledUncheckedBorderColor = scheme.onSurface.copy(alpha = 0.12f),
        disabledUncheckedIconColor = scheme.surfaceContainerHighest,
    )
}

/**
 * Expressive 切换钮：选中 primary/onPrimary，未选 surfaceContainerHighest/onSurface。
 *
 * [shapes] 为连接按钮组位置形状（`ButtonGroupDefaults.connected*Shapes()`），与 KernelSU
 * `ExpressiveToggleButton` 一致为必填；单体使用请传 `ToggleButtonDefaults.shapesFor(height)`。
 */
@Composable
fun EtaExpressiveToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shapes: ToggleButtonShapes,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = ToggleButtonDefaults.colors(
            checkedContainerColor = scheme.primary,
            checkedContentColor = scheme.onPrimary,
            containerColor = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurface,
        ),
        shapes = shapes,
        content = content,
    )
}

/**
 * 自适应扩展 FAB：
 *
 * - Material 3：官方 [SmallExtendedFloatingActionButton]（KernelSU `TemplateMaterial` 同款），
 *   形状 / 容器色 / 图标尺寸全部取 `FloatingActionButtonDefaults` 默认值；[expanded] 由调用方
 *   的滚动状态机驱动，收起时只留图标，与 KernelSU 的 fabExpanded 一致；
 * - MIUI X：保持本页原有的胶囊文字按钮（`FilledTonalButton`）不变，避免 MIUI X 分支失去文字标签。
 *
 * 位置与底部安全区由宿主骨架（`EtaScaffoldPage`）负责，本组件只负责外观。
 */
@Composable
fun EtaExtendedFab(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
) {
    if (isMaterial3Theme()) {
        SmallExtendedFloatingActionButton(
            expanded = expanded,
            onClick = onClick,
            icon = { Icon(imageVector = icon, contentDescription = null) },
            text = { Text(text = text) },
            modifier = modifier,
        )
    } else {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp).widthIn(min = 168.dp),
            shape = RoundedCornerShape(50),
        ) {
            Text(text = text, style = MiuixTheme.textStyles.body1)
        }
    }
}

/** 预留：与 KernelSU expressiveToggleButtonColors 等价的取色入口（供需要覆盖默认色的调用方）。 */
@Composable
fun etaExpressiveToggleButtonColors(
    checkedContainerColor: Color = MaterialTheme.colorScheme.primary,
    checkedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
): ToggleButtonColors = ToggleButtonDefaults.colors(
    checkedContainerColor = checkedContainerColor,
    checkedContentColor = checkedContentColor,
    containerColor = containerColor,
    contentColor = contentColor,
)
