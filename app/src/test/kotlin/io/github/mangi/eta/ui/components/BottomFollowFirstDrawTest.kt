package io.github.mangi.eta.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** No follow coroutine and no post-growth test scroll: inspect the FIRST real draw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomFollowFirstDrawTest {
    @get:Rule val compose = createComposeRule()
    private val height = mutableIntStateOf(100)
    private val following = mutableStateOf(false)
    private val extraRows = mutableIntStateOf(0)
    // A passive draw dependency: a cached child layer must not hide its first frame
    // from the outer recorder. This state is never read during composition/layout.
    private var revision by mutableIntStateOf(0)
    private var measuredHeight = 0
    private var expectedCount = 22
    private lateinit var state: LazyListState
    private lateinit var scope: CoroutineScope
    private lateinit var recovery: BottomFollowViewportRecovery
    private lateinit var baseline: Bitmap
    private var consumed = 0f
    private var actualLift = 0f
    private var callbacks = 0
    private var clicks = 0
    /** 追赶预算按两次评估之间的流逝时间发放，这里用假时钟确定地控制每帧额度。 */
    private var clockNanos = 0L
    private val probes = mutableMapOf<Int, Probe>()
    private val images = mutableMapOf<Int, Bitmap>()
    private data class Probe(
        val overflow: Int?, val bottom: Int?, val lift: Float,
        val consumed: Float, val callbacks: Int, val index: Int, val offset: Int,
    )

    @Test fun firstDrawAndContinuousMeasureOnlyGrowthKeepWholeTail() {
        setup()
        var oldOverflow = 0
        var oldConsumed = consumed
        var oldCallbacks = callbacks
        for (growth in listOf(24, 18, 3 * PAD, 24, PAD + 37, 1, 2 * PAD)) {
            // 追赶按帧预算发放：先把挂钟推一帧，本帧额度才是确定值。
            clockNanos += FRAME_NANOS + 1
            val p = grow(growth)
            assertTrue("fixed viewport still needs the post-layout callback", p.callbacks > oldCallbacks)
            val overflow = checkNotNull(p.overflow)
            val raw = oldOverflow + growth
            assertTrue("FIRST draw overflow=$overflow raw=$raw", overflow in 0..raw)
            // 首帧绘制用的就是恢复后的布局：上提量与残余超出量（封顶在绘制缓冲）一致。
            assertEquals(
                "actual layer must use recovered layout",
                min(overflow, PAD).toFloat(),
                p.lift,
                0f,
            )
            // 一次评估最多按固定追赶速度前进一帧，不再一帧滚完整个差值。
            val expected = min(CATCH_UP_PER_FRAME, max(0f, raw - PAD.toFloat()))
            val delta = p.consumed - oldConsumed
            assertTrue("actual delta=$delta expected=$expected", abs(delta - expected) <= 1.5f)
            if (raw <= PAD) assertEquals(0f, delta, 0f)
            oldOverflow = overflow
            oldConsumed = p.consumed
            oldCallbacks = p.callbacks
        }
        // 限速后剩下的差值由后续帧逐帧补齐；补齐后残留必须落进绘制缓冲，
        // 由绘制层上提把它收进静止线，尾部才算完整贴底。
        settleToRest()
        assertTrue("settled residual=${currentOverflow()}", currentOverflow() in 0..PAD)
        assertWholeTail(compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap())
        compose.onNodeWithTag(TAP).performTouchInput { click() }
        compose.runOnIdle { assertEquals("drawn and hit-test coordinates agree", 1, clicks) }
    }

    @Test fun catchUpBudgetBoundsEachFrameWithoutLosingTheDistance() {
        assertEquals(25.6f, followCatchUpBudgetPx(0.016f, 1f), 0.01f)
        // 掉帧或长时间静止后只发一帧的额度，不会一次性补成一大段跳动。
        assertEquals(followCatchUpBudgetPx(0.032f, 1f), followCatchUpBudgetPx(5f, 1f), 0f)
        assertEquals(0f, followCatchUpBudgetPx(0.016f, 0f), 0f)
        // 差值远大于额度时，一帧只前进一帧的追赶距离。
        assertEquals(
            CATCH_UP_PER_FRAME,
            resolveBottomFollowViewportStep(0f, 3 * PAD, PAD, maxCatchUpPx = CATCH_UP_PER_FRAME),
            0.01f,
        )
        // 差值在一帧额度内时仍然收完全部绘制缓冲之外的部分。
        assertEquals(
            (PAD + 3 - PAD).toFloat(),
            resolveBottomFollowViewportStep(0f, PAD + 3, PAD, maxCatchUpPx = CATCH_UP_PER_FRAME),
            0.01f,
        )
        // 没有额度时不吞掉绘制缓冲以内的差值：那部分由绘制层上提负责。
        assertEquals(
            0f,
            resolveBottomFollowViewportStep(0f, PAD + 5, PAD, maxCatchUpPx = 0f),
            0f,
        )
    }

    @Test fun oldPathIsNegativeControlOnFirstDraw() {
        setup(recoveryEnabled = false)
        val p = grow(3 * PAD)
        assertTrue(checkNotNull(p.overflow) > PAD)
        assertEquals(0f, p.consumed, 0f)
        assertTrue("old lift remains bounded", p.lift in 0f..PAD.toFloat())
        assertTrue(checkNotNull(p.bottom) - p.lift > REST)
        val image = checkNotNull(images[revision])
        assertEquals("the tail marker really is clipped", 0, count(image, MARKER))
        assertCleanInput(image)
    }

    @Test fun unknownTailNeverAuthorizesAnEstimatedRecovery() {
        setup()
        val p = grow(3 * PAD, appendedRows = 10)
        assertNull("final row and sentinel must both be outside measured items", p.overflow)
        assertEquals(0f, p.consumed, 0f)
        assertCleanInput(checkNotNull(images[revision]))
    }

    @Test fun leavingFollowPreservesHistoricalAnchor() {
        setup()
        val index = state.firstVisibleItemIndex
        val offset = state.firstVisibleItemScrollOffset
        setFollowing(false)
        val p = grow(3 * PAD)
        assertEquals(0f, p.consumed, 0f)
        assertEquals(0f, p.lift, 0f)
        assertEquals(index, p.index)
        assertEquals(offset, p.offset)
    }

    private fun setup(recoveryEnabled: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                recovery = remember(state) {
                    BottomFollowViewportRecovery(state, SENTINEL, nowNanos = { clockNanos })
                }
                val held = remember { intArrayOf(0) }
                val snapshots = List(12) { rememberGraphicsLayer() }
                val cardShape = RoundedCornerShape(12.dp)
                Box(Modifier.size(WIDTH.dp, HEIGHT.dp).testTag(ROOT).drawWithContent {
                    val rev = revision
                    if (rev > 0 && probes[rev] == null && measuredHeight == height.intValue &&
                        state.layoutInfo.totalItemsCount == expectedCount
                    ) {
                        val layer = snapshots[rev]
                        layer.record {
                            drawRect(BG, size = Size(size.width, REST.toFloat()))
                            drawRect(INPUT, Offset(0f, REST.toFloat()), Size(size.width, size.height - REST))
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(layer)
                        val info = state.layoutInfo
                        probes[rev] = Probe(
                            info.measuredBottomFollowTailOverflow(SENTINEL),
                            info.visibleItemsInfo.firstOrNull { it.key == TAIL }?.let { it.offset + it.size },
                            actualLift, consumed, callbacks,
                            state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset,
                        )
                        // Keep a separate display list for each FIRST draw; never overwrite it
                        // with later frames or use the host draw-pump screenshot for assertions.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            images[rev] = layer.toImageBitmap().asAndroidBitmap()
                                .copy(Bitmap.Config.ARGB_8888, false)
                        }
                    } else {
                        drawRect(BG, size = Size(size.width, REST.toFloat()))
                        drawRect(INPUT, Offset(0f, REST.toFloat()), Size(size.width, size.height - REST))
                        drawContent()
                    }
                }) {
                    Box(Modifier.fillMaxSize()
                        .then(if (following.value) Modifier.graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                            clip = true
                            shape = ComposerRestClip(PAD.dp)
                        } else Modifier)
                        .drawWithContent {
                            if (following.value) clipRect(bottom = REST.toFloat()) {
                                this@drawWithContent.drawContent()
                            } else drawContent()
                        }
                    ) {
                        LazyColumn(
                            state = state,
                            contentPadding = PaddingValues(bottom = PAD.dp),
                            overscrollEffect = null,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                val overflow = state.layoutInfo.measuredBottomFollowTailOverflow(SENTINEL)
                                    ?.coerceAtMost(state.layoutInfo.afterContentPadding)
                                held[0] = nextHeldTailLift(following.value, overflow, held[0])
                                actualLift = held[0].toFloat()
                                translationY = -actualLift
                            }.onGloballyPositioned {
                                callbacks++
                                if (recoveryEnabled) consumed += recovery.recover { following.value }
                            },
                        ) {
                            items(20, key = { "filler-$it" }) {
                                Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Gray))
                            }
                            item(key = TAIL) {
                                Column(
                                    Modifier.fillMaxWidth().layout { measurable, constraints ->
                                        // Row composition does not read height; only its measurement does.
                                        val h = height.intValue
                                        val child = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                                        measuredHeight = h
                                        layout(child.width, child.height) { child.place(0, 0) }
                                    }.border(2.dp, Color.Black, cardShape).clip(cardShape).background(CARD),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Spacer(Modifier.weight(1f))
                                    BasicText("LAST LINE", style = TextStyle(
                                        color = Color.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    ))
                                    Box(Modifier.fillMaxWidth().height(14.dp).background(MARKER)
                                        .testTag(TAP).clickable { clicks++ })
                                }
                            }
                            items(extraRows.intValue, key = { "new-$it" }) {
                                Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Gray))
                            }
                            item(key = SENTINEL) { Spacer(Modifier.height(1.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        var snapped = false
        compose.runOnIdle { scope.launch { state.scrollBy(100_000f); snapped = true } }
        compose.waitUntil(5_000) { snapped }
        setFollowing(true)
        compose.runOnIdle { assertEquals(0, state.layoutInfo.measuredBottomFollowTailOverflow(SENTINEL)) }
        baseline = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        assertEquals(WIDTH, baseline.width)
        assertEquals(HEIGHT, baseline.height)
        assertTrue("baseline must contain actual tail marker", count(baseline, MARKER) > 100)
        assertCleanInput(baseline)
        compose.mainClock.autoAdvance = false
    }

    private fun setFollowing(value: Boolean) {
        val old = compose.mainClock.autoAdvance
        try {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { following.value = value }
            compose.waitForIdle()
        } finally { compose.mainClock.autoAdvance = old }
    }

    private fun grow(px: Int, appendedRows: Int = 0): Probe {
        compose.runOnIdle {
            revision++
            expectedCount = 22 + appendedRows
            extraRows.intValue = appendedRows
            height.intValue += px
        }
        compose.mainClock.advanceTimeByFrame()
        // Flush Android measure/layout/draw without advancing the manual Compose clock.
        // The immutable probe still captures the FIRST draw, never the eventual idle image.
        compose.waitForIdle()
        // Robolectric can lay out without issuing a host draw. Pump that draw with
        // the Compose clock still frozen. Discard this screenshot: all assertions
        // use the immutable FIRST-draw probe/layer, even if a draw already occurred.
        compose.onNodeWithTag(ROOT).captureToImage()
        try {
            compose.waitUntil(5_000) { probes.containsKey(revision) && images.containsKey(revision) }
        } catch (error: Throwable) {
            throw AssertionError(
                "First-draw capture stalled: revision=$revision measured=$measuredHeight " +
                    "target=${height.intValue} expectedCount=$expectedCount " +
                    "actualCount=${state.layoutInfo.totalItemsCount} " +
                    "probes=$probes images=${images.keys} callbacks=$callbacks consumed=$consumed",
                error,
            )
        }
        assertFalse(compose.mainClock.autoAdvance)
        return checkNotNull(probes[revision])
    }

    /** 追赶已经收尾后的绘制：尾部完整贴在静止线上且不侵入输入区。 */
    private fun assertWholeTail(image: Bitmap) {
        for (dy in 1..32) for (x in 0 until WIDTH) {
            assertTrue("settled tail mismatch x=$x dy=$dy",
                near(image.getPixel(x, REST - dy), baseline.getPixel(x, REST - 1 - dy)))
        }
        assertCleanInput(image)
    }

    /**
     * 限速后剩余差值由后续帧逐帧补齐：每帧推一次挂钟再评估一次，直到差值落进绘制缓冲。
     * 缓冲以内的部分由绘制层上提负责，因此收敛条件是残余不大于 [PAD]。
     */
    private fun settleToRest() {
        var frames = 0
        while (currentOverflow() > PAD) {
            assertTrue("catch-up did not settle after $frames frames", frames++ < 400)
            clockNanos += FRAME_NANOS + 1
            compose.runOnIdle { consumed += recovery.recover { following.value } }
            compose.waitForIdle()
        }
        assertFalse(compose.mainClock.autoAdvance)
    }

    private fun currentOverflow(): Int = compose.runOnIdle {
        state.layoutInfo.measuredBottomFollowTailOverflow(SENTINEL) ?: 0
    }

    private fun assertCleanInput(image: Bitmap) {
        for (y in REST until HEIGHT) for (x in 0 until WIDTH) {
            assertTrue("input leak at $x,$y", near(image.getPixel(x, y), INPUT.toArgb()))
        }
    }
    private fun count(image: Bitmap, color: Color): Int =
        (0 until image.height).sumOf { y ->
            (0 until image.width).count { x -> near(image.getPixel(x, y), color.toArgb()) }
        }
    private fun near(a: Int, b: Int): Boolean = listOf(0, 8, 16, 24).all {
        abs(((a ushr it) and 255) - ((b ushr it) and 255)) <= 3
    }
    private companion object {
        const val ROOT = "first-draw-root"
        const val TAP = "tail-tap"
        const val TAIL = "tail"
        const val SENTINEL = "sentinel"
        const val WIDTH = 240
        const val HEIGHT = 600
        const val PAD = 142
        const val REST = HEIGHT - PAD
        const val FRAME_NANOS = 16_666_667L
        /** 1600px/s × 一帧，与 followCatchUpBudgetPx 的每帧额度一致。 */
        const val CATCH_UP_PER_FRAME = 1600f * 0.016666667f
        val BG = Color.White
        val INPUT = Color(0xff30a0d0)
        val CARD = Color(0xffffd080)
        val MARKER = Color(0xffe00050)
    }
}
