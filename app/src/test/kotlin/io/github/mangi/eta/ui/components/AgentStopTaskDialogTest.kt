package io.github.mangi.eta.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// 断言用的是中文文案：固定 zh-rCN locale，否则 Robolectric 默认 en 命中英文 base 字符串。
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentStopTaskDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pauseKeepsChildrenAndDoesNotSendStop() {
        val visible = mutableStateOf(true)
        var pauses = 0
        var stops = 0
        compose.setContent {
            if (visible.value) AgentStopTaskDialog(
                onPauseChildren = { pauses++; visible.value = false },
                onStopChildren = { stops++; visible.value = false },
                onDismiss = { visible.value = false },
            )
        }
        compose.onNodeWithText("暂停子代理").assertExists()
        compose.onNodeWithText("停止子代理").assertExists()
        compose.onNodeWithText("返回或关闭默认保持暂停", substring = true).assertExists()
        compose.onNodeWithText("暂停子代理").performClick()
        compose.runOnIdle {
            assertEquals(1, pauses)
            assertEquals(0, stops)
            assertFalse(visible.value)
        }
    }

    @Test fun explicitStopIsSeparateFromPause() {
        var pauses = 0
        var stops = 0
        compose.setContent {
            AgentStopTaskDialog(
                onPauseChildren = { pauses++ },
                onStopChildren = { stops++ },
                onDismiss = {},
            )
        }
        compose.onNodeWithText("停止子代理").performClick()
        compose.runOnIdle { assertEquals(0, pauses); assertEquals(1, stops) }
    }

    @Test fun oldRangeDialogAndCostWarningAreGone() {
        compose.setContent { AgentStopTaskDialog({}, {}, {}) }
        compose.onNodeWithText("停止任务？").assertDoesNotExist()
        compose.onNodeWithText("仅停止主回复").assertDoesNotExist()
        compose.onNodeWithText("停止整个任务").assertDoesNotExist()
        compose.onNodeWithText("后台子任务 · 管理").assertDoesNotExist()
        compose.onNodeWithText("可能继续产生费用", substring = true).assertDoesNotExist()
        compose.onNodeWithText("如何处理子代理？").assertExists()
    }
}
