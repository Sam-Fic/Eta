package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/**
 * 一条回答只使用一个显现时钟，保证同一帧不会有多个 Markdown 块同时“打字”。
 *
 * 解析和文本排版仅在目标文本变化时发生；帧间推进只更新普通字段并调用
 * [invalidateDraw]。只有显现跨入新行时才额外请求一次测量以增长消息高度，
 * 全程不写 Compose State，因此字符帧不会触发重组或重新排版。
 */
@Stable
internal class SmoothTextRevealCoordinator {
    private val records = sortedMapOf<RevealBlockKey, RevealRecord>()
    /** First record that may still be revealing. Completed prefixes stay in the map. */
    private var pendingCursor: RevealBlockKey? = null
    private val wakeups = Channel<Unit>(capacity = Channel.CONFLATED)
    private val drainedState = MutableStateFlow(true)
    private val startedState = MutableStateFlow<Set<RevealBlockKey>>(emptySet())
    private var animationsPaused = false
    /** Settled and fully covered: park the clock without completing pending glyphs. */
    private var animationsHeld = false
    private var restoredSourceLength = 0

    /** Layout nodes may attach after the parent's restore callback; those old blocks
     * must also start complete rather than replaying from zero. */
    fun restoreHistoryThrough(sourceLength: Int) {
        restoredSourceLength = sourceLength.coerceAtLeast(0)
        pauseAnimationsAndCatchUp()
    }

    private var onRevealAdvanced: ((Float) -> Unit)? = null

    fun setOnRevealAdvanced(listener: ((Float) -> Unit)?) {
        onRevealAdvanced = listener
    }

    val drained: StateFlow<Boolean> = drainedState
    /** 已经开始显现的块，用于让列表 marker 与正文保持同一生命周期。 */
    val started: StateFlow<Set<RevealBlockKey>> = startedState

    val isAnimationPaused: Boolean
        get() = animationsPaused

    val isAnimationHeld: Boolean
        get() = animationsHeld

    /**
     * 页面不可见时帧时钟会停，但 Runtime 仍可能继续追加文本。此时直接追平当前目标，
     * 并让后续排版结果同样立即完成，避免回到页面后补播后台积压的显现动画。
     */
    fun pauseAnimationsAndCatchUp() {
        animationsHeld = false
        animationsPaused = true
        records.values.forEach(::completeRecord)
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun resumeAnimationsAfterCatchUp() {
        animationsHeld = false
        records.values.forEach(::completeRecord)
        updateDrainedState()
        animationsPaused = false
        wakeups.trySend(Unit)
    }

    /** 暂停会话后续写：已显示的字保持追平，之后新到的字重新走打字机。 */
    fun resumeAnimationsWithoutCatchingUp() {
        animationsHeld = false
        animationsPaused = false
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    /**
     * 停稳且完全盖住时停住显现。已显示的进度保留，不追平，
     * 后续排版也不会把未显示的字一次性补完。横滑或回到聊天后再继续。
     */
    fun holdAnimations() {
        if (animationsHeld && !animationsPaused) {
            wakeups.trySend(Unit)
            return
        }
        animationsPaused = false
        animationsHeld = true
        wakeups.trySend(Unit)
    }

    fun retainBlocks(activeBlocks: Set<RevealBlockKey>) {
        val iterator = records.iterator()
        var removedPendingBlock = false
        while (iterator.hasNext()) {
            val (_, record) = iterator.next()
            if (record.key !in activeBlocks) {
                removedPendingBlock = removedPendingBlock || record.progress < record.targetCount
                if (pendingCursor == record.key) pendingCursor = null
                iterator.remove()
            }
        }
        val retainedStarted = startedState.value.intersect(activeBlocks)
        if (retainedStarted != startedState.value) {
            startedState.value = retainedStarted
        }
        if (removedPendingBlock || records.none { (_, record) -> record.progress < record.targetCount }) {
            updateDrainedState()
        }
        wakeups.trySend(Unit)
    }

    fun attach(
        key: RevealBlockKey,
        node: SmoothTextRevealNode,
        text: String?,
        layoutResult: TextLayoutResult?,
    ) {
        val record = records.getOrPut(key) { RevealRecord(key) }
        notePending(key)
        record.node = node
        if (text != null && layoutResult != null) {
            updateRecord(record, text, layoutResult)
        }
        wakeups.trySend(Unit)
    }

    fun detach(key: RevealBlockKey, node: SmoothTextRevealNode) {
        val record = records[key]?.takeIf { it.node === node } ?: return
        record.node = null
        // 已离开组合的块不再消费帧时钟；保留完成进度，重挂载时只显现后续新增文本。
        completeRecord(record)
        // The cached coordinator needs progress, not the detached paragraph's layout graph.
        record.layoutResult = null
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    /**
     * Keep the attached layout graph, but finish every in-range reveal the same way
     * a freeze remount used to finish them through [detach]. Later blocks stay pending.
     */
    fun completeAttachedRecordsIn(startOffset: Int, endOffset: Int) {
        var completed = false
        records.values.forEach { record ->
            val offset = record.key.sourceOffset
            if (offset >= startOffset && offset < endOffset && record.progress < record.targetCount) {
                completeRecord(record)
                completed = true
            }
        }
        if (completed) {
            updateDrainedState()
            wakeups.trySend(Unit)
        }
    }

    fun updateLayout(
        key: RevealBlockKey,
        node: SmoothTextRevealNode?,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        StreamPerformanceDiagnostics.measure("reveal.layout.update", text.length.toLong()) {
            val record = records.getOrPut(key) { RevealRecord(key) }
            notePending(key)
            if (node != null) record.node = node
            updateRecord(record, text, layoutResult)
            wakeups.trySend(Unit)
        }
    }

    fun drawSnapshot(key: RevealBlockKey): RevealDrawSnapshot? {
        val record = records[key] ?: return null
        if (record.layoutResult == null) return null
        return record.drawSnapshot
    }

    suspend fun runFrameClock() {
        while (currentCoroutineContext().isActive) {
            // 显现在推进，或已显现的字素还在淡入，都需要继续跑帧。
            val active = if (animationsHeld) null else firstPendingRecord() ?: firstAnimatingRecord()
            if (active == null) {
                updateDrainedState()
                wakeups.receive()
                continue
            }

            drainedState.value = false
            var previousFrameNanos = withFrameNanos { it }
            while (currentCoroutineContext().isActive) {
                if (animationsHeld) break
                val record = firstPendingRecord() ?: firstAnimatingRecord() ?: break
                val frameNanos = withFrameNanos { it }
                if (animationsHeld) break
                StreamPerformanceDiagnostics.record("reveal.frameGap", frameNanos - previousFrameNanos)
                previousFrameNanos = frameNanos

                // 淡入窗按真实帧时刻变老：已经追平、只剩渐变的字素靠这里收尾。
                records.values.forEach { candidate ->
                    if (candidate.advanceFade(frameNanos)) candidate.node?.onRevealDataChanged()
                }

                if (record.progress < record.targetCount) {
                    StreamPerformanceDiagnostics.measure("reveal.step") {
                        // 到达即显现：已排版出来的字素当帧跨过显现线并登记出现时刻，
                        // 不再按打字机速度排队等待；呈现节奏完全交给固定时长的淡入上浮。
                        val previous = record.progress
                        record.progress = record.targetCount
                        val delta = record.progress - previous
                        if (delta > 0f) {
                            onRevealAdvanced?.invoke(delta)
                            // 同一帧到达的字素共用同一出现时刻，各自按真实帧时刻老化。
                            record.fadeDurationNanos = REVEAL_FADE_NANOS
                            record.noteRevealedGlyphs(frameNanos)
                        }
                        if (record.progress > 0f && record.key !in startedState.value) {
                            startedState.value = startedState.value + record.key
                        }
                        record.node?.onRevealDataChanged()
                    }
                }
            }
        }
    }

    private fun updateRecord(
        record: RevealRecord,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        if (record.text == text && record.layoutResult === layoutResult) {
            if (StreamPerformanceDiagnostics.enabled) StreamPerformanceDiagnostics.record("reveal.record.cacheHit")
            return
        }
        StreamPerformanceDiagnostics.measureDetail("reveal.record.update", text.length.toLong()) {
            // Detach releases the layout, but must not make a restored block "new" again.
            val firstLayoutOfRestoredBlock = !record.hasReceivedLayout && record.key.sourceOffset < restoredSourceLength
            if (text != record.text) {
                // 流式文本只追加不修改，但行内语法闭合（**粗体**、`code`、链接折叠等）会让
                // 渲染文本丢掉标记字符而变短或错位。此时进度只能保持单调前进：一旦回退，
                // 已显现的文字会消失并重新打字，表现为输出反复闪烁。
                val appended = text.startsWith(record.text)
                record.boundaries = updateGraphemeBoundaries(
                    previousText = record.text,
                    previousBoundaries = record.boundaries,
                    text = text,
                )
                record.text = text
                record.targetCount = record.boundaries.lastIndex.toFloat()
                record.progress = record.progress.coerceAtMost(record.targetCount)
                // 非追加重写会让字素下标错位，旧的淡入时间戳必须作废。
                if (!appended) record.clearFade()
            }
            if (record.layoutResult !== layoutResult) {
                record.layoutResult = layoutResult
            }
            record.hasReceivedLayout = true
            if (animationsPaused || record.node == null || firstLayoutOfRestoredBlock) completeRecord(record)
            updateDrainedState()
            record.node?.onRevealDataChanged()
        }
    }

    private fun completeRecord(record: RevealRecord) {
        record.progress = record.targetCount
        // 追平后不再有渐入，绘制回到纯裁剪路径，避免残留半透明或上浮的字素。
        record.clearFade()
        if (record.targetCount > 0f && record.key !in startedState.value) {
            startedState.value = startedState.value + record.key
        }
        record.node?.onRevealDataChanged()
    }

    private fun firstPendingRecord(): RevealRecord? {
        val cursor = pendingCursor
        val tail = if (cursor == null) records.values else records.tailMap(cursor).values
        val found = tail.firstOrNull { record ->
            record.progress < record.targetCount && record.node != null && record.layoutResult != null
        }
        pendingCursor = found?.key
        return found
    }

    private fun notePending(key: RevealBlockKey) {
        val cursor = pendingCursor
        if (cursor == null || key < cursor) pendingCursor = key
    }

    /**
     * 已经追平、只剩字素淡入的块：推进线不再需要它，但帧时钟必须继续跑到
     * 最后一个字素的渐变结束。
     */
    private fun firstAnimatingRecord(): RevealRecord? =
        records.values.firstOrNull { record ->
            record.fadeAppearNanos.isNotEmpty() && record.node != null && record.layoutResult != null
        }

    private fun updateDrainedState() {
        drainedState.value = records.values.none { record -> record.progress < record.targetCount }
    }
}

@JvmInline
internal value class RevealBlockKey(val sourceOffset: Int) : Comparable<RevealBlockKey> {
    override fun compareTo(other: RevealBlockKey): Int = sourceOffset.compareTo(other.sourceOffset)
}

@Stable
internal class SmoothTextRevealState(
    val key: RevealBlockKey,
    private val coordinator: SmoothTextRevealCoordinator,
) {
    private var node: SmoothTextRevealNode? = null
    private var text: String? = null
    private var layoutResult: TextLayoutResult? = null

    fun onTextLayout(text: String, layoutResult: TextLayoutResult) {
        this.text = text
        this.layoutResult = layoutResult
        coordinator.updateLayout(key, node, text, layoutResult)
    }

    internal fun attach(node: SmoothTextRevealNode) {
        this.node = node
        coordinator.attach(key, node, text, layoutResult)
    }

    internal fun detach(node: SmoothTextRevealNode) {
        if (this.node === node) this.node = null
        coordinator.detach(key, node)
    }

    internal fun drawSnapshot(): RevealDrawSnapshot? = coordinator.drawSnapshot(key)
}

@Composable
internal fun rememberSmoothTextRevealState(
    key: RevealBlockKey,
    coordinator: SmoothTextRevealCoordinator,
): SmoothTextRevealState = remember(key, coordinator) {
    SmoothTextRevealState(key, coordinator)
}

internal fun Modifier.smoothTextReveal(state: SmoothTextRevealState): Modifier =
    this then SmoothTextRevealElement(state)

private data class SmoothTextRevealElement(
    val state: SmoothTextRevealState,
) : ModifierNodeElement<SmoothTextRevealNode>() {
    override fun create(): SmoothTextRevealNode = SmoothTextRevealNode(state)

    override fun update(node: SmoothTextRevealNode) {
        node.updateState(state)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "smoothTextReveal"
    }
}

internal class SmoothTextRevealNode(
    private var state: SmoothTextRevealState,
) : Modifier.Node(), DrawModifierNode, LayoutModifierNode {
    private val alphaPaint = Paint()
    private var cachedLayoutResult: TextLayoutResult? = null
    private var cachedFullCount = -1
    private var cachedFullPath: Path? = null
    private var cachedNextPath: Path? = null
    private var cachedVisibleHeight = -1
    private var cachedSettledPicture: GraphicsLayer? = null
    private var cachedSettledLine = -1
    private var cachedSettledWidth = -1
    private var cachedSettledPrefix = -1
    private var cachedSettledLayout: TextLayoutResult? = null

    override fun onAttach() {
        state.attach(this)
        onRevealDataChanged()
    }

    override fun onDetach() {
        releaseSettledLayer()
        state.detach(this)
        clearPathCache()
        cachedVisibleHeight = -1
    }

    fun updateState(next: SmoothTextRevealState) {
        if (state === next) return
        releaseSettledLayer()
        if (isAttached) state.detach(this)
        state = next
        clearPathCache()
        cachedVisibleHeight = -1
        if (isAttached) {
            state.attach(this)
            onRevealDataChanged()
        }
    }

    fun onRevealDataChanged() {
        val visibleHeight = state.visibleHeightPx()
        if (visibleHeight != cachedVisibleHeight) {
            cachedVisibleHeight = visibleHeight
            if (isAttached) {
                StreamPerformanceDiagnostics.record("reveal.remeasure")
                invalidateMeasurement()
            }
        }
        if (isAttached) invalidateDraw()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        return StreamPerformanceDiagnostics.measureDetail("reveal.measure") {
            val placeable = measurable.measure(constraints)
            // Reattached/replaced Markdown nodes may not have delivered onTextLayout yet.
            // Missing reveal metadata must not collapse an already measured paragraph to zero.
            val visibleHeight = if (state.drawSnapshot() == null) placeable.height
                else state.visibleHeightPx().coerceAtMost(placeable.height)
            cachedVisibleHeight = visibleHeight
            val measuredHeight = visibleHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(placeable.width, measuredHeight) {
                // 逐帧裁剪不可引入独立子图层，否则未显现的文字会整段合成出来。
                // 直接放置子内容，让 clip 和逐字淡入留在同一次绘制里。
                placeable.place(0, 0)
            }
        }
    }

    override fun ContentDrawScope.draw() {
        // 字形可能伸出行框。卡片高度只认测量高度，不把超出的部分裁掉就会盖住底边。
        // clipRect 的 block 接收者是 DrawScope，外层 ContentDrawScope 在这里只能显式传递，
        // 否则成员扩展函数无法用隐式接收者调用；裁剪与逐字淡入仍留在同一次绘制里。
        val contentScope = this
        clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
            contentScope.drawInsideMeasuredHeight()
        }
    }

    private fun ContentDrawScope.drawInsideMeasuredHeight() {
        val snapshot = state.drawSnapshot()
        if (snapshot == null) {
            releaseSettledLayer()
            drawDiagnosticContent()
            return
        }
        val contentScope = this
        val targetCount = snapshot.boundaries.lastIndex
        if (targetCount <= 0 || (snapshot.progress >= targetCount && !snapshot.liveFade)) {
            releaseSettledLayer()
            drawDiagnosticContent()
            return
        }
        if (snapshot.liveFade) {
            // 淡入窗内的字素按各自出现时刻画，窗外（更早显现）的部分继续走裁剪路径。
            drawFadingWindow(snapshot)
            return
        }

        val fullCount = floor(snapshot.progress).toInt().coerceIn(0, targetCount)
        val layout = snapshot.layoutResult
        val textLength = layout.layoutInput.text.length
        val fullEnd = snapshot.boundaries[fullCount].coerceIn(0, textLength)
        // Finished lines are recorded once into a layer. Scrolling blits that layer
        // instead of repainting the whole prefix. The line still being typed, and the
        // fading glyph, stay live. Line height is not frozen.
        var typedLine = -1
        if (fullEnd > 0 && layout.lineCount > 0) {
            typedLine = layout.getLineForOffset((fullEnd - 1).coerceAtMost(textLength - 1))
            val completedBottom = if (typedLine > 0) layout.getLineBottom(typedLine - 1) else 0f
            if (completedBottom > 0f) {
                drawSettledLines(layout, typedLine, completedBottom)
            }
            val cursor = layout.getHorizontalPosition(fullEnd, usePrimaryDirection = true).coerceAtLeast(0f)
            if (cursor > 0f) {
                clipRect(
                    top = layout.getLineTop(typedLine),
                    right = cursor,
                    bottom = layout.getLineBottom(typedLine),
                ) { contentScope.drawDiagnosticContent() }
            }
        }
        val partialAlpha = (snapshot.progress - fullCount).coerceIn(0f, 1f)
        if (partialAlpha > 0f && fullCount < targetCount) {
            ensurePaths(snapshot, fullCount)
            cachedNextPath?.let { path ->
                clipPath(path) {
                    alphaPaint.alpha = partialAlpha
                    StreamPerformanceDiagnostics.measure("reveal.saveLayer") {
                        contentScope.drawContext.canvas.saveLayer(path.getBounds(), alphaPaint)
                    }
                    try {
                        contentScope.drawDiagnosticContent()
                    } finally {
                        contentScope.drawContext.canvas.restore()
                    }
                }
            }
        }
    }

    private fun ContentDrawScope.drawSettledLines(
        layout: TextLayoutResult,
        currentLine: Int,
        completedBottom: Float,
    ) {
        val widthPx = ceil(size.width).toInt()
        val heightPx = ceil(completedBottom).toInt()
        val prefix = if (currentLine in 0 until layout.lineCount) layout.getLineStart(currentLine) else 0
        if (widthPx <= 0 || heightPx <= 0 || heightPx > 8192) {
            releaseSettledLayer()
            val contentScope = this
            clipRect(right = size.width, bottom = completedBottom) { contentScope.drawDiagnosticContent() }
            return
        }
        val reusable = cachedSettledPicture != null &&
            cachedSettledLine == currentLine &&
            cachedSettledWidth == widthPx &&
            cachedSettledPrefix == prefix &&
            cachedSettledLayout === layout
        if (!reusable) {
            releaseSettledLayer()
            val layer = requireGraphicsContext().createGraphicsLayer()
            val contentScope = this
            // record(size) retargets this draw scope. The density overload would run on a
            // fresh scope and could not draw the paragraph content.
            layer.record(size = IntSize(widthPx, heightPx)) {
                contentScope.clipRect(right = widthPx.toFloat(), bottom = completedBottom) {
                    contentScope.drawDiagnosticContent()
                }
            }
            cachedSettledPicture = layer
            cachedSettledLine = currentLine
            cachedSettledWidth = widthPx
            cachedSettledPrefix = prefix
            cachedSettledLayout = layout
            if (StreamPerformanceDiagnostics.enabled) {
                StreamPerformanceDiagnostics.record("reveal.settled.record")
            }
        } else if (StreamPerformanceDiagnostics.enabled) {
            StreamPerformanceDiagnostics.record("reveal.settled.hit")
        }
        drawLayer(cachedSettledPicture!!)
    }

    private fun releaseSettledLayer() {
        val layer = cachedSettledPicture ?: return
        cachedSettledPicture = null
        cachedSettledLine = -1
        cachedSettledWidth = -1
        cachedSettledPrefix = -1
        cachedSettledLayout = null
        if (isAttached) requireGraphicsContext().releaseGraphicsLayer(layer)
    }

    private fun ContentDrawScope.drawDiagnosticContent() {
        StreamPerformanceDiagnostics.measure("reveal.drawContent") { drawContent() }
    }

    private fun ensurePaths(snapshot: RevealDrawSnapshot, fullCount: Int) {
        val sameLayout = cachedLayoutResult === snapshot.layoutResult
        if (sameLayout && cachedFullCount == fullCount) {
            if (StreamPerformanceDiagnostics.enabled) StreamPerformanceDiagnostics.record("reveal.paths.cacheHit")
            return
        }

        if (canAppendRevealPath(sameLayout, cachedFullCount, fullCount)) {
            StreamPerformanceDiagnostics.measure("reveal.paths.append", (fullCount - cachedFullCount).toLong()) {
                // Fast output commonly reveals several graphemes per frame. Append only
                // that range, not a fresh path for the entire already-visible prefix.
                val textLength = snapshot.layoutResult.layoutInput.text.length
                val start = snapshot.boundaries[cachedFullCount].coerceIn(0, textLength)
                val end = snapshot.boundaries[fullCount].coerceIn(start, textLength)
                val addedPath = if (fullCount == cachedFullCount + 1) {
                    cachedNextPath
                } else if (end > start) {
                    snapshot.layoutResult.getPathForRange(start, end)
                } else null
                if (addedPath != null) {
                    val accumulatedPath = cachedFullPath ?: Path()
                    accumulatedPath.addPath(addedPath)
                    cachedFullPath = accumulatedPath
                }
                cachedFullCount = fullCount
                cachedNextPath = nextGraphemePath(snapshot, fullCount)
            }
            return
        }

        StreamPerformanceDiagnostics.measure("reveal.paths.rebuild", fullCount.toLong()) {
            cachedLayoutResult = snapshot.layoutResult
            cachedFullCount = fullCount
            val textLength = snapshot.layoutResult.layoutInput.text.length
            val fullEnd = snapshot.boundaries[fullCount].coerceIn(0, textLength)
            cachedFullPath = if (fullEnd > 0) {
                snapshot.layoutResult.getPathForRange(0, fullEnd)
            } else {
                null
            }
            cachedNextPath = nextGraphemePath(snapshot, fullCount)
        }
    }

    private fun nextGraphemePath(
        snapshot: RevealDrawSnapshot,
        fullCount: Int,
    ): Path? {
        return StreamPerformanceDiagnostics.measureDetail("reveal.paths.nextGrapheme") {
            val textLength = snapshot.layoutResult.layoutInput.text.length
            val start = snapshot.boundaries.getOrNull(fullCount)?.coerceIn(0, textLength)
                ?: return@measureDetail null
            val end = snapshot.boundaries.getOrNull(fullCount + 1)?.coerceIn(start, textLength)
                ?: return@measureDetail null
            if (end > start) {
                snapshot.layoutResult.getPathForRange(start, end)
            } else {
                null
            }
        }
    }

    private fun clearPathCache() {
        cachedLayoutResult = null
        cachedFullCount = -1
        cachedFullPath = null
        cachedNextPath = null
    }

    /**
     * 淡入窗的绘制：窗口起点之前的文字仍按行层/光标裁剪画成不透明，
     * 窗内的字素逐个按出现时刻给出不透明度与上浮位移。
     */
    private fun ContentDrawScope.drawFadingWindow(snapshot: RevealDrawSnapshot) {
        val layout = snapshot.layoutResult
        val textLength = layout.layoutInput.text.length
        val boundaries = snapshot.boundaries
        val startIndex = snapshot.fadeGlyphIndex.coerceIn(0, boundaries.lastIndex)
        val windowStart = boundaries[startIndex].coerceIn(0, textLength)
        val windowStartLine = if (textLength > 0 && layout.lineCount > 0) {
            layout.getLineForOffset(windowStart.coerceAtMost(textLength - 1))
        } else 0
        // 窗口所在行之前的完整行同样可以整层复用；行内窗口之前的部分走光标裁剪。
        val opaqueBottom = if (windowStartLine > 0) layout.getLineBottom(windowStartLine - 1) else 0f
        if (opaqueBottom > 0f) {
            drawSettledLines(layout, windowStartLine, opaqueBottom)
        }
        val cursor = layout.getHorizontalPosition(windowStart, usePrimaryDirection = true).coerceAtLeast(0f)
        if (cursor > 0f) {
            val contentScope = this
            clipRect(
                top = layout.getLineTop(windowStartLine),
                right = cursor,
                bottom = layout.getLineBottom(windowStartLine),
            ) { contentScope.drawDiagnosticContent() }
        }
        drawFadingGlyphs(snapshot)
    }

    /**
     * 窗口内的字素：越新出现的越透明、越靠下，随年龄一起淡入并升到最终位置。
     *
     * 同一帧出现的字素不透明度与上浮量完全相同，合进一个图层一起画。淡入时长固定后，
     * 文字生成再快，窗内也只是同时排下更多字素；每帧新建的图层数只跟窗内出现的帧数
     * 有关，不会随生成速度线性增长。
     */
    private fun ContentDrawScope.drawFadingGlyphs(snapshot: RevealDrawSnapshot) {
        val layout = snapshot.layoutResult
        val textLength = layout.layoutInput.text.length
        val boundaries = snapshot.boundaries
        val startIndex = snapshot.fadeGlyphIndex.coerceIn(0, boundaries.lastIndex)
        val endIndex = (startIndex + snapshot.fadeSize).coerceIn(startIndex, boundaries.lastIndex)
        val contentScope = this
        var index = startIndex
        while (index < endIndex) {
            val alpha = fadeAlphaAt(snapshot, index)
            if (alpha <= MIN_FADE_ALPHA) {
                index += 1
                continue
            }
            val paths = ArrayList<Path>(4)
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            var rise = 0f
            var runEnd = index
            while (runEnd < endIndex && fadeAlphaAt(snapshot, runEnd) == alpha) {
                val start = boundaries[runEnd].coerceIn(0, textLength)
                val end = boundaries[runEnd + 1].coerceIn(start, textLength)
                runEnd += 1
                if (end <= start) continue
                val path = layout.getPathForRange(start, end)
                val bounds = path.getBounds()
                if (bounds.width <= 0f || bounds.height <= 0f) continue
                paths += path
                left = min(left, bounds.left)
                top = min(top, bounds.top)
                right = max(right, bounds.right)
                bottom = max(bottom, bounds.bottom)
                val line = layout.getLineForOffset(start.coerceAtMost((textLength - 1).coerceAtLeast(0)))
                rise = max(
                    rise,
                    (1f - alpha) * FADE_RISE_LINE_FRACTION *
                        (layout.getLineBottom(line) - layout.getLineTop(line)).coerceAtLeast(0f),
                )
            }
            index = runEnd
            if (paths.isEmpty()) continue
            // 上浮会把字形探出原行，图层范围要跟着放宽，且始终收在测量高度内。
            val layerTop = (top - rise).coerceAtLeast(0f)
            val layerBottom = (bottom + rise).coerceAtMost(size.height)
            if (layerBottom <= layerTop) continue
            alphaPaint.alpha = alpha
            StreamPerformanceDiagnostics.measure("reveal.saveLayer") {
                contentScope.drawContext.canvas.saveLayer(
                    Rect(
                        left = left.coerceAtLeast(0f),
                        top = layerTop,
                        right = right.coerceAtMost(size.width),
                        bottom = layerBottom,
                    ),
                    alphaPaint,
                )
            }
            try {
                translate(top = rise) {
                    paths.forEach { path -> clipPath(path) { contentScope.drawDiagnosticContent() } }
                }
            } finally {
                contentScope.drawContext.canvas.restore()
            }
        }
    }

    /** 淡入窗内某字素此刻的不透明度，含正在打的那一个字素只显现了一部分的比例。 */
    private fun fadeAlphaAt(snapshot: RevealDrawSnapshot, index: Int): Float =
        snapshot.fadeAlpha(index) * (snapshot.progress - index).coerceIn(0f, 1f)
}

internal class RevealDrawSnapshot(
    private val record: RevealRecord,
) {
    val layoutResult: TextLayoutResult
        get() = checkNotNull(record.layoutResult)
    val boundaries: IntArray
        get() = record.boundaries
    val progress: Float
        get() = record.progress
    /** 还有字素处在淡入窗内；为假时绘制完全走原来的纯裁剪路径。 */
    val liveFade: Boolean
        get() = record.fadeAppearNanos.isNotEmpty()
    val fadeGlyphIndex: Int
        get() = record.fadeGlyphIndex
    val fadeSize: Int
        get() = record.fadeAppearNanos.size

    /**
     * 淡入窗内第 [glyphIndex] 个字素的不透明度：从头索引开始按真实帧时刻老化到 1，
     * 再用 smoothstep 收出平缓的头尾。窗外的字素一律视为已经完全显现。
     */
    fun fadeAlpha(glyphIndex: Int): Float {
        val duration = record.fadeDurationNanos
        val queueIndex = glyphIndex - record.fadeGlyphIndex
        if (duration <= 0L || queueIndex < 0 || queueIndex >= record.fadeAppearNanos.size) return 1f
        val ageNanos = record.lastFrameNanos - record.fadeAppearNanos[queueIndex]
        if (ageNanos <= 0L) return 0f
        val fraction = (ageNanos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        return fraction * fraction * (3f - 2f * fraction)
    }
}

internal class RevealRecord(
    val key: RevealBlockKey,
) {
    val drawSnapshot = RevealDrawSnapshot(this)
    var node: SmoothTextRevealNode? = null
    var text: String = ""
    var layoutResult: TextLayoutResult? = null
    var hasReceivedLayout = false
    var boundaries: IntArray = intArrayOf(0)
    var progress: Float = 0f
    var targetCount: Float = 0f
    /** 淡入窗的起写字素下标；窗外（更早显现）的字素一律不透明。 */
    var fadeGlyphIndex: Int = 0
    /** 单次淡入的时长，随当前显现速度推得；0 表示不做淡入。 */
    var fadeDurationNanos: Long = 0L
    /** 最近一帧的时刻，绘制阶段用它给窗口内的字素算年龄。 */
    var lastFrameNanos: Long = 0L
    /** 淡入窗内每个字素的出现时刻，与 [fadeGlyphIndex] 连续对应。 */
    val fadeAppearNanos = ArrayDeque<Long>()

    /** 丢弃淡入窗：追平目标、文本重写或进度被压短时，旧时刻已经不再对应任何字素。 */
    fun clearFade() {
        fadeAppearNanos.clear()
        fadeGlyphIndex = floor(progress).toInt().coerceAtLeast(0)
    }

    /**
     * 为本帧新显现的字素登记出现时刻。同一帧显现的字素共用同一时刻；
     * 帧间只更新普通字段，不写 Compose State。
     */
    fun noteRevealedGlyphs(now: Long) {
        lastFrameNanos = now
        val highest = floor(progress).toInt().coerceAtMost(targetCount.toInt() - 1)
        var index = fadeGlyphIndex + fadeAppearNanos.size
        while (index <= highest) {
            fadeAppearNanos.addLast(now)
            index += 1
        }
    }

    /**
     * 按真实帧时刻让淡入窗变老：到时的字素出队并把窗口前移。
     * 窗口非空即返回 true，让帧时钟一直跑到最后一个字素淡完。
     */
    fun advanceFade(now: Long): Boolean {
        if (fadeAppearNanos.isEmpty()) return false
        lastFrameNanos = now
        val duration = fadeDurationNanos
        if (duration <= 0L) {
            // 速度为 0 之类的退化情形必须一次清空，否则窗口永远变不老。
            fadeGlyphIndex += fadeAppearNanos.size
            fadeAppearNanos.clear()
            return true
        }
        while (fadeAppearNanos.isNotEmpty() && now - fadeAppearNanos.first() >= duration) {
            fadeAppearNanos.removeFirst()
            fadeGlyphIndex += 1
        }
        return true
    }
}

private fun SmoothTextRevealState.visibleHeightPx(): Int {
    val snapshot = drawSnapshot() ?: return 0
    val layoutResult = snapshot.layoutResult
    val targetCount = snapshot.boundaries.lastIndex
    if (targetCount <= 0 || snapshot.progress >= targetCount) {
        return layoutResult.size.height
    }

    val visibleCount = ceil(snapshot.progress).toInt().coerceIn(0, targetCount)
    if (visibleCount == 0) return 0
    val textLength = layoutResult.layoutInput.text.length
    val visibleEnd = snapshot.boundaries[visibleCount].coerceIn(0, textLength)
    if (visibleEnd == 0 || layoutResult.lineCount == 0) return 0
    val line = layoutResult.getLineForOffset((visibleEnd - 1).coerceAtMost(textLength - 1))
    return ceil(layoutResult.getLineBottom(line)).toInt()
        .coerceIn(0, layoutResult.size.height)
}

internal fun graphemeBoundaries(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<Int>(text.length + 1)
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        result += boundary
        boundary = iterator.next()
    }
    if (result.lastOrNull() != text.length) result += text.length
    return result.toIntArray()
}

/**
 * 为只追加文本增量维护字素边界。
 *
 * 新内容可能把旧文本的最后一个字素继续延长，例如组合音标、ZWJ emoji、旗帜和 CRLF。
 * 因此保留倒数第二个边界之前的结果，只重算最后一个旧字素和新增后缀，避免每个流式
 * 分片都从头扫描整条回答。
 */
internal fun updateGraphemeBoundaries(
    previousText: String,
    previousBoundaries: IntArray,
    text: String,
): IntArray {
    if (
        previousText.isEmpty() ||
        !text.startsWith(previousText) ||
        previousBoundaries.isEmpty() ||
        previousBoundaries.first() != 0 ||
        previousBoundaries.last() != previousText.length
    ) {
        return StreamPerformanceDiagnostics.measure("reveal.graphemes.rebuild", text.length.toLong()) {
            graphemeBoundaries(text)
        }
    }
    if (text == previousText) {
        if (StreamPerformanceDiagnostics.enabled) StreamPerformanceDiagnostics.record("reveal.graphemes.cacheHit")
        return previousBoundaries
    }

    return StreamPerformanceDiagnostics.measure("reveal.graphemes.append", (text.length - previousText.length).toLong()) {
        val restartBoundaryIndex = (previousBoundaries.lastIndex - 1).coerceAtLeast(0)
        val restartOffset = previousBoundaries[restartBoundaryIndex]
        val suffixBoundaries = graphemeBoundaries(text.substring(restartOffset))
        IntArray(restartBoundaryIndex + suffixBoundaries.size).also { merged ->
            for (index in 0 until restartBoundaryIndex) {
                merged[index] = previousBoundaries[index]
            }
            suffixBoundaries.forEachIndexed { index, boundary ->
                merged[restartBoundaryIndex + index] = restartOffset + boundary
            }
        }
    }
}

internal class AppendOnlyGraphemeIndex {
    private var indexedText = ""
    private var boundaries = intArrayOf(0)

    fun update(text: String) {
        boundaries = updateGraphemeBoundaries(
            previousText = indexedText,
            previousBoundaries = boundaries,
            text = text,
        )
        indexedText = text
    }

    fun endAfter(start: Int, maxGraphemes: Int): Int {
        val clampedStart = start.coerceIn(0, indexedText.length)
        if (clampedStart == indexedText.length || maxGraphemes <= 0) return clampedStart

        val foundIndex = boundaries.binarySearch(clampedStart)
        val firstEndIndex = if (foundIndex >= 0) foundIndex + 1 else -foundIndex - 1
        val endIndex = (firstEndIndex + maxGraphemes - 1).coerceAtMost(boundaries.lastIndex)
        return boundaries[endIndex]
    }
}

internal fun commonUtf16PrefixLength(first: String, second: String): Int {
    val limit = minOf(first.length, second.length)
    var index = 0
    while (index < limit && first[index] == second[index]) index += 1
    if (
        index in 1 until limit &&
        first[index - 1].isHighSurrogate() &&
        first[index].isLowSurrogate()
    ) {
        index -= 1
    }
    return index
}

/**
 * 单个字素淡入与上浮的固定时长。这是"每个字的动画时间"本身，不随文字生成速度变化：
 * 生成再快，也只是同一帧有更多字素一起开始动画，单个字的动画时长不变。
 */
internal const val REVEAL_FADE_NANOS = 160_000_000L

/** 字素刚出现时相对最终位置的下沉比例（按行高衡量）。 */
private const val FADE_RISE_LINE_FRACTION = 0.12f
/** 低于这个不透明度的字素不再单独建层，直接跳过。 */
private const val MIN_FADE_ALPHA = 0.001f

/** Layout changes and progress corrections require rebuilding; forward batches do not. */
internal fun canAppendRevealPath(sameLayout: Boolean, cachedCount: Int, nextCount: Int): Boolean =
    sameLayout && cachedCount >= 0 && nextCount > cachedCount
