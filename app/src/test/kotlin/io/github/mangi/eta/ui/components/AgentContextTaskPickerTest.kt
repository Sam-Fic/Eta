package io.github.mangi.eta.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentContextTaskPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sameModelTasksStaySeparateAndSelectionTracksTaskId() {
        val first = SubAgentContextStats("taskAA-1", 1, "research", "same", "同一模型", "提供商", 10000,
            contextTokens = 1000, status = "completed", agentId = "agent", agentName = "执行A")
        val second = first.copy(taskId = "taskBB-2", contextTokens = 2000, status = "running")
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompositionLocalProvider(LocalAgentContextTelemetry provides AgentContextTelemetry(
                    children = listOf(first, second), mainModelName = "主模型")) {
                    AgentContextUsageButton(AgentContextUsageUi(900, 10000))
                }
            }
        }
        compose.onNodeWithContentDescription("主模型", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("taskAA", substring = true).assertExists().performClick()
        compose.onNodeWithContentDescription("taskAA", substring = true).assertExists()
            .performTouchInput { longClick() }
        compose.onNodeWithText("taskBB", substring = true).assertExists().performClick()
        compose.onNodeWithContentDescription("taskBB", substring = true).assertExists()
        compose.onNodeWithContentDescription("taskAA", substring = true).assertDoesNotExist()
    }
}
