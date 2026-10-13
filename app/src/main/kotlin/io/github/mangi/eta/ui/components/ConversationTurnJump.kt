package io.github.mangi.eta.ui.components

import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/** One scroll mutation and one velocity profile, including across supplement/answer boundaries. */
internal suspend fun LazyListState.animateToConversationTurn(targetIndex: Int) {
    require(targetIndex >= 0)
    scroll {
        val lazyScope = LazyLayoutScrollScope(this@animateToConversationTurn, this)
        if (lazyScope.itemCount == 0) return@scroll
        val target = targetIndex.coerceAtMost(lazyScope.itemCount - 1)
        val forward = target > firstVisibleItemIndex
        val sign = if (forward) 1f else -1f
        val motion = ConversationTurnMotion(layoutInfo.viewportSize.height.toFloat())
        var previousFrame = withFrameNanos { it }
        // 完全无进度的连续帧数：测量被钳住或滚动标志陈旧时 scrollBy 会一直返回 0。
        // 「零进度 3 帧」才是停住；欠消费但仍在推进的帧不算（那是估算高度滞后，会自愈）。
        var edgeStreak = 0
        while (true) {
            val visible = layoutInfo.visibleItemsInfo.any { it.index == target }
            // Only an actually measured target may trigger deceleration. Do not settle at
            // estimated distances based on the heights of intermediate supplement bubbles.
            val remaining = if (visible) lazyScope.calculateDistanceTo(target).toFloat() else null
            if (remaining != null && (abs(remaining) <= 0.5f || remaining * sign < 0f)) {
                lazyScope.snapToItem(target)
                break
            }
            val frame = withFrameNanos { it }
            val elapsed = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previousFrame = frame
            val distance = motion.advance(elapsed, remaining?.let(::abs))
            if (distance <= 0f) continue
            val delta = sign * distance
            val consumed = scrollBy(delta)
            // A small target may become visible and be crossed in this very measurement.
            // Correct before drawing this frame, never start a second reverse animation.
            val crossed = if (forward) firstVisibleItemIndex >= target else firstVisibleItemIndex < target
            if (crossed || (remaining != null && distance >= abs(remaining))) {
                lazyScope.snapToItem(target)
                break
            }
            // 停滞判定只看「零进度」，不看欠消费：消费不足一帧只是估算高度还没校准，
            // 列报仍在推进时下一帧就恢复了，把这种帧数进放弃计数会让「跳到下一条用户
            // 消息 / 回到底部」停在半路任意位置（只滚一小截）。完全不动才是真的停住。
            edgeStreak = if (abs(consumed) < 0.5f) edgeStreak + 1 else 0
            if (edgeStreak >= 3) {
                // 停住后用布局而不是消费量分辨两种情况：目标一侧贴住边界 = 真到底/到顶，
                // 放弃即可；仍有越界余量 = 滚动被钳住（滚动标志陈旧等），scrollBy 会一直
                // 返回 0，必须直接 snapToItem 定位到目标，否则按钮表现为原地不动或停半路。
                if (!hasClampedScrollReserve(forward)) break // Actual list edge.
                lazyScope.snapToItem(target)
                break
            }
        }
    }
}

/** 滚动被钳位时布局侧仍有的越界余量：正向看尾部越静止线，反向看头部越起点线。 */
internal fun LazyListState.hasClampedScrollReserve(forward: Boolean): Boolean {
    val info = layoutInfo
    return if (forward) {
        val last = info.visibleItemsInfo.lastOrNull() ?: return false
        last.offset + last.size > info.viewportEndOffset - info.afterContentPadding + 1
    } else {
        val first = info.visibleItemsInfo.firstOrNull() ?: return false
        first.offset < info.viewportStartOffset - 1
    }
}
