package io.github.mangi.eta.ui.adaptive

import androidx.compose.ui.unit.dp

/**
 * L1 设计令牌：间距 / 尺寸 / 笔画。
 *
 * 官方已给出的尺寸一律优先用官方令牌（`IconButtonDefaults.extraSmallIconSize`、
 * `ButtonDefaults.IconSize`、`ProgressIndicatorDefaults.CircularStrokeWidth`…），
 * 本文件只收官方没有、但项目内反复出现的值。新代码从这里取值，不要在文件里
 * 再定义私有尺寸常量；存量字面量渐进收敛，不做一次性全量替换。
 */
object EtaDimens {

    // ── 间距阶（4 的倍数；官方组件内部间距不在此列，跟随官方默认） ──

    /** 图标与相邻文字的最小间隙（状态标记、图标 + 文本行）。 */
    val SpaceXs = 4.dp

    /** 行内元素间距（图标 + 文本、chip 间隙）。 */
    val SpaceSm = 8.dp

    /** 卡片内段落间距。 */
    val SpaceMd = 12.dp

    /** 卡片水平外边距、展开体内容列内边距。 */
    val SpaceLg = 16.dp

    // ── 尺寸 / 笔画 ──

    /** 状态标记图标（工具行、朗读按钮等 16dp 状态槽）。 */
    val StatusIconSize = 16.dp

    /** 小号确定进度环笔画（输入区上下文用量环、朗读按钮 spinner 同款）。 */
    val SmallRingStroke = 2.5.dp

    /** 空态装饰形状（M3E 形状）的边长：图标坐在上面，形状在下缘露出一截。 */
    val EmptyStateShapeSize = 128.dp
}
