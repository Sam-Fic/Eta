package io.github.mangi.eta.ui.markdown

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownViewportDrawCullTest {
    @get:Rule val compose = createComposeRule()

    @Test fun boundariesAndUnreliableBoundsFailOpen() {
        assertTrue(markdownTextOutsideViewport(Rect(0f, -60f, 30f, -33f), 100f, 32f))
        assertTrue(markdownTextOutsideViewport(Rect(0f, 133f, 30f, 160f), 100f, 32f))
        assertFalse(markdownTextOutsideViewport(Rect(0f, -60f, 30f, -32f), 100f, 32f))
        assertFalse(markdownTextOutsideViewport(Rect(0f, 132f, 30f, 160f), 100f, 32f))
        assertFalse(markdownTextOutsideViewport(Rect(0f, 0f, 30f, 160f), 100f, 32f))
        assertFalse(markdownTextOutsideViewport(Rect.Zero, 100f, 32f))
        assertFalse(markdownTextOutsideViewport(Rect(Float.NaN, 140f, 30f, 160f), 100f, 32f))
        assertFalse(markdownTextOutsideViewport(Rect(0f, Float.NaN, 30f, 160f), 100f, 32f))
        assertFalse(markdownTextOutsideViewport(Rect(0f, 140f, 30f, 160f), 0f, 32f))
    }

    @Test fun retainedLayerReturnsAfterLeavingViewportAndResize() {
        val viewport = MarkdownDrawViewport()
        val y = mutableStateOf(0.dp)
        val height = mutableStateOf(100.dp)
        val enabled = mutableStateOf(true)
        var draws = 0
        compose.setContent {
            SideEffect { viewport.updateEnabled(enabled.value) }
            // No clipping at the logical viewport: only the guard can hide the red leaf.
            Box(Modifier.size(100.dp, 400.dp).background(Color.White).testTag("root")) {
                Box(Modifier.size(100.dp, height.value).onGloballyPositioned(viewport::updateCoordinates)) {
                    CompositionLocalProvider(LocalMarkdownDrawViewport provides viewport) {
                        Column(Modifier.offset(y = y.value)) {
                            Box(Modifier.size(60.dp).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                .markdownViewportDrawCull(true)
                                .drawWithContent { draws++; drawContent() }
                                .background(Color.Red).semantics { contentDescription = "kept-leaf" })
                        }
                    }
                }
            }
        }
        fun color(at: Int = 20) = compose.onNodeWithTag("root").captureToImage().toPixelMap()[20, at]
        assertEquals(Color.Red, color())
        compose.runOnIdle { y.value = 200.dp }
        assertEquals(Color.White, color(220))
        compose.onNodeWithContentDescription("kept-leaf", useUnmergedTree = true).assertExists()
        compose.runOnIdle { enabled.value = false }
        assertEquals("gate disable must invalidate the empty recording", Color.Red, color(220))
        compose.runOnIdle { enabled.value = true }
        assertEquals(Color.White, color(220))
        val outsideDraws = draws
        compose.runOnIdle { y.value = 210.dp }
        color(230)
        assertEquals("offscreen leaf does not record its contents", outsideDraws, draws)
        compose.runOnIdle { y.value = 0.dp }
        assertEquals("empty recording must be invalidated on reentry", Color.Red, color())
        compose.runOnIdle { y.value = 180.dp }
        assertEquals(Color.White, color(200))
        compose.runOnIdle { height.value = 300.dp }
        assertEquals("viewport resize restores the cached leaf", Color.Red, color(200))
    }

    @Test fun absentProviderDoesNotSuppressDrawing() {
        compose.setContent {
            Box(Modifier.size(40.dp).markdownViewportDrawCull(true).background(Color.Red).testTag("plain"))
        }
        assertEquals(Color.Red, compose.onNodeWithTag("plain").captureToImage().toPixelMap()[10, 10])
    }
}
