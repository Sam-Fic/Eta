package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmoothTextRevealCoordinatorTest {
    private val textMeasurer by lazy {
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(RuntimeEnvironment.getApplication()),
            defaultDensity = Density(1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }

    @Test
    fun detachedEarlierBlockCompletesAndLaterBlockKeepsRevealing() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        val earlierKey = RevealBlockKey(0)
        val laterKey = RevealBlockKey(100)
        val earlierNode = attach(coordinator, earlierKey, "早先的思考正文")
        attach(coordinator, laterKey, "随后到达的工具输出内容")
        val earlierSnapshot = coordinator.drawSnapshot(earlierKey)!!

        coordinator.detach(earlierKey, earlierNode)

        assertNull(coordinator.drawSnapshot(earlierKey))
        // The record keeps progress while the detached layout is released.
        assertEquals(7f, earlierSnapshot.progress, 0f)
        assertTrue(earlierKey in coordinator.started.value)
        assertFalse(coordinator.drained.value)
        val clock = TestFrameClock()
        val frameJob = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runFrameClock()
        }
        try {
            clock.send(0L)
            clock.send(50_000_000L)
            yield()

            assertTrue(coordinator.drawSnapshot(laterKey)!!.progress > 0f)
        } finally {
            frameJob.cancelAndJoin()
        }
    }

    @Test
    fun oneFrameRevealsEveryArrivedBlockWithoutBacklog() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        val keys = (0 until 10).map { RevealBlockKey(it * 20) }
        keys.reversed().forEach { attach(coordinator, it, "abcdefghij") }
        var feedbackFrames = 0
        var feedbackAdvance = 0f
        coordinator.setOnRevealAdvanced { feedbackFrames++; feedbackAdvance += it }
        val clock = TestFrameClock()
        val frameJob = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runFrameClock()
        }
        try {
            clock.send(0L)
            clock.send(50_000_000L)
            yield()

            // 到达即显现：同一帧内所有已就绪的块一起跨过显现线，不再按帧预算排队。
            keys.forEach { assertEquals(10f, coordinator.drawSnapshot(it)!!.progress, 0f) }
            assertEquals(keys.toSet(), coordinator.started.value)
            assertEquals(1, feedbackFrames)
            assertEquals(100f, feedbackAdvance, 0f)
            assertTrue(coordinator.drained.value)

            // 淡入窗走完后帧时钟自会停下，不会再有任何推进。
            repeat(60) { clock.send((it + 3) * 50_000_000L) }
            yield()
            assertTrue(coordinator.drained.value)
            assertEquals(1, feedbackFrames)
        } finally {
            frameJob.cancelAndJoin()
        }
    }

    @Test
    fun reattachedBlockKeepsCompletedPrefixAndAnimatesOnlyNewText() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val oldNode = attach(coordinator, key, "已有文字")

        coordinator.detach(key, oldNode)
        assertTrue(coordinator.drained.value)
        attach(coordinator, key, "已有文字和新增文字")

        val snapshot = coordinator.drawSnapshot(key)!!
        assertEquals(4f, snapshot.progress, 0f)
        assertEquals(9, snapshot.boundaries.lastIndex)
        assertFalse(coordinator.drained.value)
    }

    @Test
    fun layoutWithoutMountedNodeIsImmediatelyReadableOnLaterAttach() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val text = "尚未挂载时收到的历史内容"
        val state = SmoothTextRevealState(key, coordinator)
        state.onTextLayout(text, layout(text))

        assertTrue(coordinator.drained.value)
        assertEquals(text.length.toFloat(), coordinator.drawSnapshot(key)!!.progress, 0f)
        state.attach(SmoothTextRevealNode(state))

        assertTrue(coordinator.drained.value)
        assertEquals(text.length.toFloat(), coordinator.drawSnapshot(key)!!.progress, 0f)
    }

    @Test
    fun staleDetachDoesNotCompleteTheReplacementNode() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val oldNode = attach(coordinator, key, "原始内容")
        val replacementNode = attach(coordinator, key, "替换后的完整内容")

        coordinator.detach(key, oldNode)

        assertEquals(0f, coordinator.drawSnapshot(key)!!.progress, 0f)
        assertFalse(coordinator.drained.value)
        coordinator.detach(key, replacementNode)
        assertTrue(coordinator.drained.value)
    }

    @Test
    fun fastArrivalKeepsEarlierGlyphsFadingForTheirFullDuration() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        attach(coordinator, key, "abcdefghijklmnopqrstuvwxyz0123456789")
        val clock = TestFrameClock()
        val frameJob = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runFrameClock()
        }
        try {
            // 一帧吃掉一大段积压：这批字素全部在同一帧（首帧）出现。
            clock.send(0L)
            clock.send(50_000_000L)
            yield()
            assertTrue(coordinator.drawSnapshot(key)!!.liveFade)

            // 再推 30ms：字素出现后共走过 80ms，正好是固定时长的一半，仍在半透明渐变中，
            // 不会因为生成很快就把它们的动画压缩掉。
            clock.send(80_000_000L)
            yield()
            val halfway = coordinator.drawSnapshot(key)!!
            assertEquals(0, halfway.fadeGlyphIndex)
            assertTrue("alpha=${halfway.fadeAlpha(0)}", halfway.fadeAlpha(0) in 0.4f..0.6f)
        } finally {
            frameJob.cancelAndJoin()
        }
    }

    private fun attach(
        coordinator: SmoothTextRevealCoordinator,
        key: RevealBlockKey,
        text: String,
    ): SmoothTextRevealNode {
        val state = SmoothTextRevealState(key, coordinator)
        val node = SmoothTextRevealNode(state)
        state.attach(node)
        state.onTextLayout(text, layout(text))
        return node
    }

    private fun layout(text: String): TextLayoutResult = textMeasurer.measure(
        text = text,
        style = TextStyle(fontSize = 16.sp),
        constraints = Constraints(maxWidth = 320),
    )

    private class TestFrameClock : MonotonicFrameClock {
        private val frames = Channel<Long>(Channel.UNLIMITED)

        fun send(timeNanos: Long) {
            check(frames.trySend(timeNanos).isSuccess)
        }

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
    }
}
