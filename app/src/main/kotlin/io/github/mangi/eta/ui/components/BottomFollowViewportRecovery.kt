package io.github.mangi.eta.ui.components

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState

/**
 * Post-layout recovery only; never call from measure, placement or draw.
 * Normal follow consumes only measured overflow beyond the existing padding budget.
 * An explicitly pinned work expansion can also preserve a pre-click, measured stable key.
 * No unseen tail height is estimated. Every raw delta is ownership-guarded and its actual
 * consumption and fresh layout are checked before requesting another bounded step.
 */
internal class BottomFollowViewportRecovery(
    private val state: LazyListState,
    private val sentinelKey: Any,
    private val density: Float = 1f,
    // 时间源可注入：追赶预算按两次评估之间的流逝时间发放，测试用假时钟才能确定地控制每帧额度。
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var recovering = false
    private var workExpansion: WorkExpansion? = null
    // 尾部追赶按帧预算限速：每次评估都推进时钟，两次评估之间的最长时间就是本帧拿到的
    // 追赶额度（followCatchUpBudgetPx 内部再按 32ms 封顶，从 0 起算时等价于一整帧额度）。
    private var lastTailRecoveryNanos: Long = 0L

    private data class WorkExpansion(
        val groupKey: Any,
        val precedingStepKeys: Set<Any>,
        val anchorKey: Any,
        val anchorOffset: Int,
        val expiresAtNanos: Long,
    )

    /** Capture before inserting the flat lazy rows, not after their first height change. */
    fun beginWorkExpansion(
        groupKey: Any,
        stepKeys: Set<Any>,
        expiresAtNanos: Long,
        canOwnViewport: Boolean,
    ): Boolean {
        // A new attempt replaces the previous explicit owner even when it cannot capture
        // fresh evidence. A rapid second toggle must never scroll using the old group's key.
        workExpansion = null
        // Capturing evidence does not scroll. An in-flight auto-follow may finish before
        // the insertion lays out; recover() still refuses raw deltas while ANY scroll runs.
        if (!canOwnViewport || stepKeys.isEmpty() || System.nanoTime() >= expiresAtNanos) return false
        val items = state.layoutInfo.visibleItemsInfo
        val header = items.firstOrNull { it.key == groupKey } ?: return false
        val anchor = items.firstOrNull { it.index > header.index } ?: return false
        workExpansion = WorkExpansion(groupKey, stepKeys, anchor.key, anchor.offset, expiresAtNanos)
        return true
    }

    /** Capture before a stable chat row changes its measured height. */
    fun beginThinkingExpansion(
        rowKey: Any,
        expiresAtNanos: Long,
        canOwnViewport: Boolean,
    ): Boolean {
        // Replace any previous owner before checking capture eligibility. This also makes a
        // rapid toggle unable to reuse a stale work-group anchor.
        workExpansion = null
        if (!canOwnViewport || System.nanoTime() >= expiresAtNanos) return false
        val items = state.layoutInfo.visibleItemsInfo
        val row = items.firstOrNull { it.key == rowKey } ?: return false
        val anchor = items.firstOrNull { it.index > row.index } ?: return false
        // This existing row grows BEFORE the captured anchor. If that anchor is temporarily
        // virtualized, the row's measured bottom proves a lower bound without guessing tail height.
        workExpansion = WorkExpansion(rowKey, setOf(rowKey), anchor.key, anchor.offset, expiresAtNanos)
        return true
    }

    fun cancelWorkExpansion(groupKey: Any, childRowKeys: Collection<Any> = emptyList()) {
        val owner = workExpansion?.groupKey ?: return
        if (owner == groupKey || owner in childRowKeys) workExpansion = null
    }

    /** Read the same explicit owner; a snap timer alone is not capture evidence. */
    fun hasActiveExpansion(canOwnViewport: Boolean): Boolean {
        val expansion = workExpansion ?: return false
        return canOwnViewport && System.nanoTime() < expansion.expiresAtNanos
    }

    fun recover(
        canRecoverExpansion: () -> Boolean = { false },
        canRecover: () -> Boolean,
    ): Float {
        if (recovering) return 0f
        if (!canRecoverExpansion() || System.nanoTime() >= (workExpansion?.expiresAtNanos ?: 0L)) {
            workExpansion = null
        }
        if (state.isScrollInProgress || (!canRecover() && workExpansion == null)) return 0f
        recovering = true
        var consumed = 0f
        try {
            // Unknown-tail expansion may need to reveal several virtualized rows before the
            // old key is measured again. Work stays bounded even for arbitrarily large groups.
            val attempts = workExpansion?.let { (it.precedingStepKeys.size + 4).coerceAtMost(36) } ?: 4
            repeat(attempts) {
                if (state.isScrollInProgress) return consumed
                if (!canRecoverExpansion() || System.nanoTime() >= (workExpansion?.expiresAtNanos ?: 0L)) {
                    workExpansion = null
                }
                val info = state.layoutInfo
                val expansion = workExpansion
                val expansionStep = if (expansion != null) {
                    val anchor = info.visibleItemsInfo.firstOrNull { it.key == expansion.anchorKey }
                    // These exact rows were inserted or grew BEFORE the old anchor. Their measured
                    // bottoms are a lower bound on its new offset, not an estimated tail.
                    val precedingBottom = info.visibleItemsInfo
                        .filter { it.key in expansion.precedingStepKeys }
                        .maxOfOrNull { it.offset + it.size }
                    resolveWorkExpansionViewportStep(expansion.anchorOffset, anchor?.offset, precedingBottom)
                } else 0f
                val tailStep = if (canRecover()) {
                    // 与上次评估的流逝时间即本帧额度；从未评估过时差值是「距纪元」的巨值，
                    // 被 32ms 封顶后等价于先给一整帧的启动额度，保证恢复器能自行起步。
                    val now = nowNanos()
                    val elapsedSeconds = (now - lastTailRecoveryNanos) / 1_000_000_000f
                    lastTailRecoveryNanos = now
                    resolveBottomFollowViewportStep(
                        0f, info.measuredBottomFollowTailOverflow(sentinelKey), info.afterContentPadding,
                        // 尾部追赶限速成平滑动画；工作展开走 expansionStep，仍整段瞬时回收。
                        maxCatchUpPx = followCatchUpBudgetPx(elapsedSeconds, density),
                    )
                } else {
                    lastTailRecoveryNanos = 0L
                    0f
                }
                val step = maxOf(expansionStep, tailStep)
                if (step <= 0f || !state.canScrollForward) return consumed
                // dispatchRawDelta bypasses scroll arbitration: recheck BOTH owners right
                // before dispatch, including synchronous pointer/navigation cancellation.
                if (state.isScrollInProgress ||
                    !(canRecover() || (expansion != null && canRecoverExpansion()))
                ) return consumed
                val actual = state.dispatchRawDelta(step)
                if (!actual.isFinite() || actual <= 0f) return consumed
                consumed += actual
            }
            return consumed
        } finally {
            recovering = false
        }
    }
}

internal fun LazyListLayoutInfo.measuredBottomFollowTailOverflow(sentinelKey: Any): Int? {
    val sentinel = visibleItemsInfo.firstOrNull { it.key == sentinelKey }
    val last = visibleItemsInfo.lastOrNull()
    val bottom = resolveTailBottomPx(
        sentinelBottom = sentinel?.let { it.offset + it.size },
        lastVisibleIndex = last?.index,
        lastVisibleBottom = last?.let { it.offset + it.size },
        totalItems = totalItemsCount,
    ) ?: return null
    return bottom - (viewportEndOffset - afterContentPadding)
}
