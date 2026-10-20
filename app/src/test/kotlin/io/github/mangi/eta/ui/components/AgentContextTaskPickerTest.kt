package io.github.mangi.eta.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 上下文目标选择菜单（长按页内上下文用量触发的弹层）的行为。
 *
 * 菜单改用 Material 3 Expressive 的 `DropdownMenuPopup` + `DropdownMenuGroup` 之后，
 * 这里确认条目仍然逐条列出、回传的仍是子任务 ID（同模型的多个子任务不合并）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentContextTaskPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sameModelTasksStaySeparateAndSelectionTracksTaskId() {
        val first = SubAgentContextStats(
            taskId = "taskAA-1", worker = 1, role = "research", model = "same",
            modelName = "同一模型", providerName = "提供商", contextWindow = 10000,
            contextTokens = 1000, status = "completed", agentId = "agent", agentName = "执行A",
        )
        val second = first.copy(taskId = "taskBB-2", contextTokens = 2000, status = "running")
        var selectedTaskId: String? = null
        compose.setContent {
            MaterialExpressiveTheme(
                colorScheme = lightColorScheme(),
                motionScheme = MotionScheme.expressive(),
            ) {
                ContextTargetSelectorMenu(
                    telemetry = AgentContextTelemetry(
                        children = listOf(first, second),
                        mainModelName = "主模型",
                        onTaskSelected = { selectedTaskId = it },
                    ),
                    expanded = true,
                    onDismiss = {},
                    popupMaxHeight = { 360.dp },
                )
            }
        }
        // 同模型的子任务各自占一行，不合并。
        compose.onNodeWithText("taskAA", substring = true).assertExists().performClick()
        assertEquals("taskAA-1", selectedTaskId)

        selectedTaskId = null
        compose.onNodeWithText("taskBB", substring = true).assertExists().performClick()
        assertEquals("taskBB-2", selectedTaskId)
    }

    /** 选主代理时回传 null（回到主上下文）。 */
    @Test
    fun pickingMainAgentClearsTaskSelection() {
        var selectedTaskId: String? = "unset"
        compose.setContent {
            MaterialExpressiveTheme(
                colorScheme = lightColorScheme(),
                motionScheme = MotionScheme.expressive(),
            ) {
                ContextTargetSelectorMenu(
                    telemetry = AgentContextTelemetry(
                        mainModelName = "主模型",
                        onTaskSelected = { selectedTaskId = it },
                    ),
                    expanded = true,
                    onDismiss = {},
                    popupMaxHeight = { 360.dp },
                )
            }
        }
        compose.onNodeWithText("主模型", substring = true).assertExists().performClick()
        assertNull(selectedTaskId)
    }
}
