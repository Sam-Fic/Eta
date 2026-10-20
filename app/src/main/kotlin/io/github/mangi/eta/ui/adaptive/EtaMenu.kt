package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemShapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * KernelSU `OffsetAnchoredExpressiveMenu` 对应物：锚定在按下位置的表达性菜单。
 *
 * 菜单内容用 [EtaMenuGroupSurface]（与官方 `DropdownMenuGroup` 逐参数等价），与参考实现一致。
 */
@Composable
fun EtaOffsetAnchoredMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorOffset: IntOffset = IntOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(Constraints())
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
            layout(width, 0) {
                placeable.place(anchorOffset.x, anchorOffset.y)
            }
        }
    ) {
        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
        ) {
            EtaMenuGroupSurface(scrollable = true) {
                content()
            }
        }
    }
}

/** 记录按下位置（供 [EtaOffsetAnchoredMenu] 锚定），与 KernelSU `trackPressPosition` 一致。 */
fun Modifier.trackPressPosition(onPress: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPress(down.position)
    }
}

/**
 * Material 3 Expressive 菜单分组：官方 `DropdownMenuGroup` + 官方 `MenuDefaults.groupShape`。
 *
 * 形状由 [index] / [count] 决定：单组取 standalone 组形状，首组取 leading、末组取
 * trailing、中间组取 middle 组形状，因此多个分组拼起来就是官方那套「组内贴合、组间留白」
 * 的圆角形态（组间距见 [EtaMenuGroupSpacing]）。
 *
 * 容器色固定 standard 档（`MenuDefaults.groupStandardContainerColor`，即 surfaceContainerLow），
 * 不启用 vibrant 强调色——vibrant 会把整组染成 tertiaryContainer，在聊天输入区这种高频、
 * 低信息密度的菜单里过于抢眼。
 *
 * 组内边距沿用官方 `MenuDefaults.DropdownMenuGroupContentPadding`（左右 0、上下 2dp），
 * 条目自身的横向留白由官方条目内容内边距（12dp）提供——选中项因此是一枚内缩的圆角
 * 药丸，与「上下文压缩 → 请求接口」那个下拉（`EtaSegmentedDropdownRow`）同款。
 *
 * 官方 `DropdownMenuGroup` 的 `modifier` 落在最外层 Surface 上（`hoverable` 之后），
 * 需要滚动时圆角背景会跟着内容一起滚、被视口裁成直角；要滚动请改用
 * [EtaMenuGroupSurface]。
 *
 * material3 1.5.0-alpha29 起这批菜单 API 已是稳定 API（早期 alpha 里的
 * `@ExperimentalMaterial3ExpressiveApi` 只留在 `DropdownMenuItemChecked` /
 * `DropdownMenuItemSelected` 这两个已隐藏的旧名上），因此这里不需要 `@OptIn`。
 */
@Composable
fun EtaMenuGroup(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShape(index = index, count = count),
        modifier = modifier,
        containerColor = MenuDefaults.groupStandardContainerColor,
        content = content,
    )
}

/**
 * Expressive 菜单分组容器：与官方 `DropdownMenuGroup` 逐参数等价，但滚动落在内层 Column。
 *
 * 官方 `DropdownMenuGroup` 的 `modifier` 挂在外层 Surface 上，滚动时圆角背景会随内容
 * 一起滚、被视口裁成直角；这里把相同的 Surface 配置（组形状 / standard 容器色 /
 * 0dp tonal / 3dp shadow）留在外层，把滚动挂到内层 Column 上，圆角因此始终完整。
 *
 * 组内边距沿用官方 `MenuDefaults.DropdownMenuGroupContentPadding`（左右 0、上下 2dp），
 * 也就是「上下文压缩 → 请求接口」那个下拉的紧凑上下边距。
 */
@Composable
fun EtaMenuGroupSurface(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MenuDefaults.groupShape(index = 0, count = 1).shape,
        color = MenuDefaults.groupStandardContainerColor,
        // 官方 MenuDefaults.TonalElevation / ShadowElevation 取值（0dp / 3dp）。
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(MenuDefaults.DropdownMenuGroupContentPadding)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            content = content,
        )
    }
}

/**
 * 官方条目形状（未选中态）：单项取 standalone，多项按位置取 leading / middle / trailing。
 *
 * 直接代理官方 `MenuDefaults.itemShape`，跟随主题 `Shapes` 变化，不自造圆角。
 */
@Composable
fun EtaMenuItemShape(index: Int, count: Int): Shape =
    MenuDefaults.itemShape(index = index, count = count).shape

/**
 * 官方可选中条目形状：未选中态用官方条目形状，选中态用官方 medium 全圆角药丸
 * （与「上下文压缩 → 请求接口」那个下拉同款）。
 */
@Composable
fun EtaMenuItemShapes(index: Int, count: Int): MenuItemShapes =
    MenuDefaults.itemShape(index = index, count = count)

/** 纯文案菜单条目：文案 + 可用态 + 点击回调。 */
@Immutable
data class EtaMenuEntry(
    val text: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 纯文案下拉菜单：官方 Expressive 弹层 + 官方分组，条目形状按位置取官方 leading / middle / trailing。
 *
 * 条目数固定后形状由 [EtaMenuItemShape] 按位置推导，调用方不必自己算圆角。
 */
@Composable
fun EtaTextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    entries: List<EtaMenuEntry>,
    modifier: Modifier = Modifier,
) {
    EtaMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        EtaMenuGroup(index = 0, count = 1) {
            entries.forEachIndexed { index, entry ->
                DropdownMenuItem(
                    text = { Text(text = entry.text) },
                    enabled = entry.enabled,
                    shape = EtaMenuItemShape(index, entries.size),
                    onClick = entry.onClick,
                )
            }
        }
    }
}

/**
 * Expressive 菜单分组之间的官方留白（组与组之间是间距，不是分割线）。
 *
 * 官方 `DropdownMenuPopup` 的内容列不自带组间距，`MenuDefaults.GroupSpacing`
 * 需要调用方自己插入，因此统一从这里取，避免各处写死数值。
 */
val EtaMenuGroupSpacing: androidx.compose.ui.unit.Dp
    get() = MenuDefaults.GroupSpacing

/** Expressive 菜单宽度下限：够放下「前置图标 + 四字中文」，与旧版平台默认弹窗宽度接近。 */
val EtaMenuMinWidth: androidx.compose.ui.unit.Dp = 140.dp

/** Expressive 菜单宽度上限：避免超长文案把菜单撑到屏幕边缘。 */
val EtaMenuMaxWidth: androidx.compose.ui.unit.Dp = 280.dp

/**
 * Material 3 Expressive 菜单弹层：官方 `DropdownMenuPopup` + 本项目统一的宽度约束。
 *
 * 官方 `DropdownMenuPopup` 不带平台默认宽度（`usePlatformDefaultWidth` 恒为 false），
 * 弹层宽度完全由内容决定；这里补上 [EtaMenuMinWidth] / [EtaMenuMaxWidth]，
 * 让「图标 + 四字中文」的短菜单也不会缩成一条窄缝。
 *
 * 窗口属性沿用官方默认（`MenuDefaults.DefaultMenuProperties`，即 focusable = true）：
 * 覆盖 `focusable` 会给弹窗加 `FLAG_NOT_FOCUSABLE`，而 `createFlags()` 又无条件带
 * `FLAG_WATCH_OUTSIDE_TOUCH`，点开菜单的那一次抬手会被当成外部点击，菜单刚弹出就被关掉。
 */
@Composable
fun EtaMenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = EtaMenuMinWidth, max = EtaMenuMaxWidth),
        content = content,
    )
}
