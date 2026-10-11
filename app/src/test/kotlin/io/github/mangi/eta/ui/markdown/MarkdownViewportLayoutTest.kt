package io.github.mangi.eta.ui.markdown

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownViewportLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scrollPlacesOnlyVisibleBlocksAndEagerRestoresAllWithoutRemount() {
        val viewport = MarkdownDrawViewport()
        val admission = MarkdownLayoutAdmission().apply { request(false, false) }
        val y = mutableIntStateOf(0)
        val viewportHeight = mutableIntStateOf(100)
        val blockHeight = mutableIntStateOf(40)
        val enabled = mutableStateOf(true)
        val placed = IntArray(20)
        val mounts = IntArray(20)
        compose.setContent {
            val gate = enabled.value
            SideEffect { viewport.updateEnabled(gate) }
            CompositionLocalProvider(LocalMarkdownDrawViewport provides viewport,
                LocalMarkdownLayoutAdmission provides admission) {
                Box(Modifier.size(200.dp, viewportHeight.intValue.dp).onGloballyPositioned(viewport::updateCoordinates)) {
                    MarkdownViewportLayout(Modifier.offset { androidx.compose.ui.unit.IntOffset(0, y.intValue) }
                        .wrapContentHeight(Alignment.Top, unbounded = true).testTag("document")) {
                        repeat(20) { index ->
                            DisposableEffect(Unit) { mounts[index]++; onDispose { mounts[index]-- } }
                            Layout(content = {}, modifier = Modifier.testTag("block-$index")) { _, _ ->
                                layout(200, blockHeight.intValue) { placed[index]++ }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val initialHeight = compose.onNodeWithTag("document").fetchSemanticsNode().layoutInfo.height
        compose.runOnIdle { placed.fill(0); y.intValue = -400 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("new visible block must be placed in scroll frame", placed[10] > 0)
            assertEquals("offscreen block must skip actual placement", 0, placed[0])
            assertTrue(mounts.all { it == 1 })
            placed.fill(0)
            admission.request(true, false)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("eager restores offscreen placement", placed[0] > 0)
            assertEquals(admission.revision, admission.placedRevision)
            assertTrue(mounts.all { it == 1 })
            admission.request(false, false)
        }
        compose.waitForIdle()
        compose.runOnIdle { placed.fill(0); enabled.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("transition gate restores all", placed[0] > 0) }
        assertEquals(initialHeight, compose.onNodeWithTag("document").fetchSemanticsNode().layoutInfo.height)
        compose.runOnIdle { enabled.value = true }
        compose.waitForIdle()
        compose.runOnIdle { placed.fill(0); viewportHeight.intValue = 300 }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("resized viewport places newly visible blocks", placed[15] > 0) }
        compose.runOnIdle { blockHeight.intValue = 50 }
        compose.waitForIdle()
        assertEquals(1000, compose.onNodeWithTag("document").fetchSemanticsNode().layoutInfo.height)
    }

    @Test fun boundariesIncludeTouchingAndZeroHeightBlocks() {
        val window = Rect(0f, 100f, 200f, 200f)
        assertFalse(markdownBlockIntersectsWindow(0, 99, window))
        assertTrue(markdownBlockIntersectsWindow(0, 100, window))
        assertTrue(markdownBlockIntersectsWindow(200, 10, window))
        assertFalse(markdownBlockIntersectsWindow(201, 10, window))
        assertTrue(markdownBlockIntersectsWindow(0, 0, window))
    }
}
