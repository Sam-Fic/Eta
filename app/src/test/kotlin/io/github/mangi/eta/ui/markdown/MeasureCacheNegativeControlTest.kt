package io.github.mangi.eta.ui.markdown

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Constraints
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h900dp-mdpi",
    shadows = [ShadowWorkLayoutNode::class], instrumentedPackages = ["androidx.compose.ui.node"])
class MeasureCacheNegativeControlTest {
    @get:Rule val compose = createComposeRule()
    @Test fun parentRemeasureIsNotStableTextRemeasure() {
        val generation = mutableIntStateOf(0)
        val width = mutableIntStateOf(220)
        val tail = mutableStateOf("tail")
        compose.setContent {
            val captured = generation.intValue
            val requestedWidth = width.intValue
            Column(Modifier.testTag("parent").layout { measurable, _ ->
                check(captured >= 0)
                val child = measurable.measure(Constraints.fixedWidth(requestedWidth))
                layout(child.width, child.height) { child.place(0, 0) }
            }) {
                BasicText("Stable prefix ".repeat(20))
                BasicText(tail.value)
            }
        }
        compose.waitForIdle()
        val parent = compose.onNodeWithTag("parent").fetchSemanticsNode().layoutInfo
        val prefix = compose.onNodeWithText("Stable prefix ".repeat(20)).fetchSemanticsNode().layoutInfo
        fun sample(change: () -> Unit): Map<Any, Work> {
            compose.runOnIdle { WorkCounts.begin(); change() }
            compose.waitForIdle()
            return compose.runOnIdle { WorkCounts.end() }
        }
        val unrelated = sample { generation.intValue++ }
        assertTrue("Positive control must execute parent measure", (unrelated[parent]?.measure ?: 0L) > 0L)
        assertEquals(0L, unrelated[prefix]?.measure ?: 0L)
        val append = sample { tail.value += " grows" }
        assertEquals(0L, append[prefix]?.measure ?: 0L)
        val narrower = sample { width.intValue = 140 }
        assertTrue("A real constraint change must execute text measure", (narrower[prefix]?.measure ?: 0) > 0)
    }
}
