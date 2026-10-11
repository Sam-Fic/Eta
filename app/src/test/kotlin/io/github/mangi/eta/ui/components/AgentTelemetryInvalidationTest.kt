package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class AgentTelemetryInvalidationTest {
    @get:Rule val compose = createComposeRule()
    private var bodyCommits = 0
    private var observedName = ""

    @Composable private fun UnrelatedBody() { SideEffect { bodyCommits++ } }
    @Composable private fun ContextReader() {
        val name = LocalAgentContextTelemetry.current.mainModelName
        SideEffect { observedName = name }
    }

    @Test fun telemetryUpdatesReaderWithoutInvalidatingUnrelatedBody() {
        val telemetry = mutableStateOf(AgentContextTelemetry(mainModelName = "first"))
        compose.setContent {
            CompositionLocalProvider(LocalAgentContextTelemetry provides telemetry.value) {
                UnrelatedBody()
                ContextReader()
            }
        }
        var baseline = 0
        compose.runOnIdle {
            baseline = bodyCommits
            assertEquals("first", observedName)
            telemetry.value = AgentContextTelemetry(mainModelName = "next")
        }
        compose.runOnIdle {
            assertEquals("next", observedName)
            assertEquals(baseline, bodyCommits)
        }
    }
}
