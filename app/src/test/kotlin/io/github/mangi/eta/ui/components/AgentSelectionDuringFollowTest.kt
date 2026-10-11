package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.haptics.SelectionTestMagnifier
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h900dp-mdpi",
    shadows = [SelectionTestMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentSelectionDuringFollowTest {
    @get:Rule val compose = createComposeRule()
    @Before fun initPreferences() { Prefs.initLocal(RuntimeEnvironment.getApplication()) }

    @Test fun growingTailDoesNotMovePressedTextAndNativeSelectionStarts() = checkSelection(longHistory = true)

    @Test fun shortBottomAlignedConversationKeepsPressedTextWhenTailOutgrowsViewport() = checkSelection(longHistory = false)

    private fun checkSelection(longHistory: Boolean) {
        val text = "Stable selectable answer alpha bravo charlie."
        val answer = AgentMessageUi("answer", text, renderMarkdown = false)
        val tail = AgentMessageUi("tail", "Tail", renderMarkdown = false)
        val history = (if (longHistory) (0..7).toList() else emptyList()).map { AgentMessageUi("history-$it", "Earlier answer $it.\n".repeat(4), renderMarkdown = false) }
        val messages = mutableStateOf<List<AgentChatMessageUi>>(history + listOf(answer, tail))
        lateinit var list: LazyListState
        compose.setContent {
            MiuixTheme {
                list = rememberLazyListState()
                Box(Modifier.size(360.dp, 400.dp)) {
                    AgentConversationMessages(visibleMessages = messages.value, scrollState = list,
                        isStreaming = true, bottomInset = 24.dp, keepBottomAnchored = true,
                        onBottomAnchorChanged = {})
                }
            }
        }
        compose.waitForIdle()
        assertEquals("Fixture scrollability", longHistory, list.canScrollBackward)
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val point = layouts.single().getBoundingBox(3).center
        val top = node.fetchSemanticsNode().boundsInRoot.top
        val index = list.firstVisibleItemIndex
        val offset = list.firstVisibleItemScrollOffset
        node.performTouchInput { down(point) }
        compose.runOnIdle { messages.value = history + listOf(answer, tail.copy(content = "Later output line\n".repeat(35))) }
        compose.waitForIdle()
        assertEquals(top, node.fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(index, list.firstVisibleItemIndex)
        assertEquals(offset, list.firstVisibleItemScrollOffset)
        node.performTouchInput { advanceEventTime(800); moveTo(point) }
        compose.waitForIdle()
        val handles = compose.onAllNodes(SemanticsMatcher("native selection handles") { semantics ->
            semantics.config.any { it.key.name == "SelectionHandleInfo" }
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Native long press must create a selection handle", handles.isNotEmpty())
        node.performTouchInput { up() }
        compose.waitUntil(5_000) {
            list.firstVisibleItemIndex != index || list.firstVisibleItemScrollOffset != offset
        }
    }
}
