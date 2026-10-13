package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationTurnJumpTest {
    @get:Rule val compose = createComposeRule()

    @Test fun upwardTransitionPassesSupplementsWithoutStoppingOrReversing() = assertContinuousJump(12, 0)
    @Test fun downwardTransitionPassesSupplementsWithoutStoppingOrReversing() = assertContinuousJump(0, 12)

    @Test fun clippedUserMessageReturnsToItsOwnTopBeforePreviousMessage() {
        val state = LazyListState(firstVisibleItemIndex = 4, firstVisibleItemScrollOffset = 120)
        lateinit var scope: CoroutineScope
        compose.setContent {
            val rememberedScope = rememberCoroutineScope()
            SideEffect { scope = rememberedScope }
            LazyColumn(state = state, modifier = Modifier.size(300.dp, 400.dp)) {
                items(12) { Box(Modifier.height(900.dp)) }
            }
        }
        compose.runOnIdle {
            val target = conversationUserMessageTarget(listOf(0, 4, 8), state.firstVisibleItemIndex, 12,
                ConversationNavigationDirection.Up, false, state.firstVisibleItemScrollOffset)
            assertEquals(4, target)
            scope.launch { state.animateToConversationTurn(target) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(4, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertEquals(0, conversationUserMessageTarget(listOf(0, 4, 8), state.firstVisibleItemIndex, 12,
                ConversationNavigationDirection.Up, false, state.firstVisibleItemScrollOffset))
        }
    }

    @Test fun stalledJumpReserveTestIsTrueWhileContentOverflowsAndFalseAtRealEdges() {
        val state = LazyListState()
        lateinit var scope: CoroutineScope
        compose.setContent {
            val rememberedScope = rememberCoroutineScope()
            SideEffect { scope = rememberedScope }
            // 与应用一致的布局不变量：末项是短哨兵，真到底时其底边贴住静止线。
            LazyColumn(state = state, modifier = Modifier.size(300.dp, 400.dp)) {
                items(18) { index -> Box(Modifier.height(if (index == 17) 80.dp else 900.dp)) }
            }
        }
        compose.waitForIdle()
        // 中途位置：头尾都在裁剪 = 被钳住时布局上仍有越界余量（允许 snapToItem 救援）。
        compose.runOnIdle {
            scope.launch { state.scrollToItem(6, 300) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("forward reserve expected mid-list", state.hasClampedScrollReserve(forward = true))
            assertTrue("backward reserve expected mid-list", state.hasClampedScrollReserve(forward = false))
            scope.launch { state.scrollToItem(17) }
        }
        compose.waitForIdle()
        // 真到底：尾部贴住静止线，正向越界测试必须不成立（允许判定为真边界并放弃）。
        compose.runOnIdle {
            assertFalse("forward reserve must be false at the real end", state.hasClampedScrollReserve(forward = true))
            scope.launch { state.scrollToItem(0) }
        }
        compose.waitForIdle()
        // 真到顶：头部贴住起点线，反向越界测试必须不成立。
        compose.runOnIdle {
            assertFalse("backward reserve must be false at the real top", state.hasClampedScrollReserve(forward = false))
        }
    }

    private fun assertContinuousJump(start: Int, target: Int) {
        val state = LazyListState(firstVisibleItemIndex = start)
        val observed = mutableListOf<Pair<Int, Int>>()
        lateinit var scope: CoroutineScope
        compose.setContent {
            val rememberedScope = rememberCoroutineScope()
            SideEffect { scope = rememberedScope }
            LaunchedEffect(state) {
                snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect { observed += it }
            }
            LazyColumn(state = state, modifier = Modifier.size(300.dp, 400.dp)) {
                items(18) { index ->
                    // Oversized replies alternate with short supplement bubbles.
                    Box(Modifier.height(if (index % 3 == 1) 1800.dp else 80.dp))
                }
            }
        }
        compose.runOnIdle {
            assertEquals(start, state.firstVisibleItemIndex)
            observed.clear()
            scope.launch { state.animateToConversationTurn(target) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(target, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertTrue("Expected a real animated transition: $observed", observed.size > 3)
            assertTrue("Should pass intermediate messages, not teleport", observed.any { it.first != target && it.first != start })
            val compare = compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }
            assertTrue("Transition reversed: $observed", observed.zipWithNext().all { (a, b) ->
                if (target < start) compare.compare(a, b) >= 0 else compare.compare(a, b) <= 0
            })
            assertEquals(target to 0, observed.last())
        }
    }
}
