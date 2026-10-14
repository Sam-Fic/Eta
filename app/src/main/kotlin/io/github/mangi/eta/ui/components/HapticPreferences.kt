package io.github.mangi.eta.ui.components

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference as MiuixArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference as MiuixSwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference as MiuixWindowSpinnerPreference
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.alpha
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.etaSegmentedRowColors
import io.github.mangi.eta.ui.adaptive.etaSegmentedRowShapes
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme

/** M3 列表行禁用态整体透明度，与 miuix 侧观感一致。 */
private const val M3DisabledAlpha = 0.5f

/**
 * M3 分支共用的列表行骨架：官方 SegmentedListItem（KernelSU 式分段列表行）。
 * 分段形状/容器色由 `EtaSegmentedList` 统一提供（外层分段列注入，独立使用时退化为
 * 单条目形状）；标题 + 可选摘要 + 前导图标 + 尾部插槽，禁用态整体半透明。
 */
@Composable
private fun M3PreferenceRow(
    title: String,
    summary: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    startAction: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val shapes = etaSegmentedRowShapes()
    val colors = etaSegmentedRowColors()
    val rowModifier = modifier.alpha(if (enabled) 1f else M3DisabledAlpha)
    val supporting: (@Composable () -> Unit)? = summary?.let { text ->
        {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (onClick != null) {
        SegmentedListItem(
            verticalAlignment = Alignment.CenterVertically,
            onClick = onClick,
            shapes = shapes,
            colors = colors,
            modifier = rowModifier,
            enabled = enabled,
            leadingContent = startAction,
            trailingContent = trailing,
            supportingContent = supporting,
            content = { Text(text = title) },
        )
    } else {
        SegmentedListItem(
            verticalAlignment = Alignment.CenterVertically,
            shapes = shapes,
            colors = colors,
            modifier = rowModifier,
            enabled = false,
            leadingContent = startAction,
            trailingContent = trailing,
            supportingContent = supporting,
            content = { Text(text = title) },
        )
    }
}

@Composable
internal fun ArrowPreference(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onClick: (() -> Unit)? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    if (isMaterial3Theme()) {
        // 显式类型：let 返回嵌套 lambda 时推断器需要标注才能落到 (() -> Unit)?。
        val m3Click: (() -> Unit)? = onClick?.let { click -> { TouchHaptics.click(view); click() } }
        // Row 的 receiver 就是 RowScope，直接转发 endActions。
        val m3Trailing: @Composable () -> Unit = { Row { endActions() } }
        Column(modifier = modifier) {
            M3PreferenceRow(
                title = title,
                summary = summary,
                enabled = enabled,
                startAction = startAction,
                onClick = m3Click,
                trailing = m3Trailing,
            )
            bottomAction?.invoke()
        }
        return
    }
    MiuixArrowPreference(
        title = title,
        modifier = modifier,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = endActions,
        bottomAction = bottomAction,
        insideMargin = insideMargin,
        onClick = onClick?.let { click ->
            {
                TouchHaptics.click(view)
                click()
            }
        },
        holdDownState = holdDownState,
        enabled = enabled,
    )
}

@Composable
internal fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    if (isMaterial3Theme()) {
        M3PreferenceRow(
            title = title,
            summary = summary,
            enabled = enabled,
            modifier = modifier,
            startAction = startAction,
            onClick = if (enabled) {
                { TouchHaptics.click(view); onCheckedChange(!checked) }
            } else {
                null
            },
            trailing = {
                EtaSwitch(
                    checked = checked,
                    onCheckedChange = { value ->
                        TouchHaptics.click(view)
                        onCheckedChange(value)
                    },
                    enabled = enabled,
                )
            },
        )
        return
    }
    MiuixSwitchPreference(
        title = title,
        checked = checked,
        onCheckedChange = { value ->
            TouchHaptics.click(view)
            onCheckedChange(value)
        },
        modifier = modifier,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        insideMargin = insideMargin,
        holdDownState = holdDownState,
        enabled = enabled,
    )
}

@Composable
internal fun WindowSpinnerPreference(
    items: List<DropdownItem>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    spinnerColors: DropdownColors = DropdownDefaults.dropdownColors(),
    startAction: @Composable (() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    maxHeight: Dp? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    val view = LocalView.current
    if (isMaterial3Theme()) {
        // M3：ListItem 承载当前值，点击把选择交给调用方的 onSelectedIndexChange；
        // miuix 的弹出层在 M3 下以「行内点击循环到下一项」的轻量交互替代，
        // 保持单行几何不变，避免为每个 spinner 页面引入新的导航结构。
        M3PreferenceRow(
            title = title,
            summary = summary,
            enabled = enabled,
            modifier = modifier,
            startAction = startAction,
            onClick = if (enabled && onSelectedIndexChange != null) {
                {
                    TouchHaptics.click(view)
                    val next = (selectedIndex + 1).mod(items.size)
                    onExpandedChange?.invoke(true)
                    onSelectedIndexChange.invoke(next)
                }
            } else {
                null
            },
            trailing = if (showValue && selectedIndex in items.indices) {
                {
                    Text(
                        text = items[selectedIndex].text,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = M3DisabledAlpha)
                        },
                    )
                }
            } else {
                null
            },
        )
        return
    }
    MiuixWindowSpinnerPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        modifier = modifier,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        spinnerColors = spinnerColors,
        startAction = startAction,
        bottomAction = bottomAction,
        insideMargin = insideMargin,
        maxHeight = maxHeight,
        enabled = enabled,
        showValue = showValue,
        onExpandedChange = { expanded ->
            if (expanded) TouchHaptics.click(view)
            onExpandedChange?.invoke(expanded)
        },
        onSelectedIndexChange = { index ->
            TouchHaptics.click(view)
            onSelectedIndexChange?.invoke(index)
        },
    )
}

@Composable
internal fun HapticBasicComponent(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onClick: (() -> Unit)? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    if (isMaterial3Theme()) {
        Column(modifier = modifier) {
            M3PreferenceRow(
                title = title,
                summary = summary,
                enabled = enabled,
                startAction = startAction,
                onClick = onClick?.let { click -> { TouchHaptics.click(view); click() } },
                trailing = endActions?.let { actions ->
                    val trailing: @Composable () -> Unit = { Row { actions() } }
                    trailing
                },
            )
            bottomAction?.invoke()
        }
        return
    }
    BasicComponent(
        title = title,
        modifier = modifier,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = endActions,
        bottomAction = bottomAction,
        insideMargin = insideMargin,
        onClick = onClick?.let { click ->
            {
                TouchHaptics.click(view)
                click()
            }
        },
        holdDownState = holdDownState,
        enabled = enabled,
    )
}
