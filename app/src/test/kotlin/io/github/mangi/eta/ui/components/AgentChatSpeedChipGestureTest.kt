package io.github.mangi.eta.ui.components

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 思考图标手势：GPT 速度模型长按切速度、单击仍开旧思考弹窗；非 GPT 无长按语义且不切速度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentChatSpeedChipGestureTest {
    @get:Rule val compose = createComposeRule()

    private val gptSupported = mutableStateOf(true)
    private val options = mutableStateOf<List<ReasoningEffort>>(emptyList())
    private val mode = mutableStateOf(GptSpeedMode.NORMAL)
    private var cycles = 0
    private var effortChanges = 0

    private lateinit var context: Context

    private fun render() {
        context = RuntimeEnvironment.getApplication()
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                ThinkingEffortChip(
                    effort = ReasoningEffort.HIGH,
                    options = options.value,
                    enabled = true,
                    gptSpeedMode = mode.value,
                    gptSpeedSupported = gptSupported.value,
                    onCycleGptSpeedMode = {
                        cycles++
                        mode.value = mode.value.next()
                    },
                    onEffortChange = { effortChanges++ },
                )
            }
        }
    }

    private fun chip() = compose.onNodeWithContentDescription(
        context.getString(R.string.chat_reasoning_effort, context.getString(ReasoningEffort.HIGH.labelRes)),
        substring = true,
    )

    private fun reasoningDialogTitle() =
        context.getString(R.string.reasoning_picker_title)

    @Test
    fun customResponsesProjectionEnablesActualLongPress() {
        val model = io.github.mangi.eta.data.model.Model("m", "gpt-6-astra", "GPT")
        val provider = io.github.mangi.eta.data.model.CustomProviderSetting(
            "p", "GPT", "https://example.invalid",
            endpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES,
            models = listOf(model),
        )
        val selected = io.github.mangi.eta.ui.model.AgentModelPickerProjector
            .project(listOf(provider), "p", "m").selectedModel
        gptSupported.value = requireNotNull(selected).gptSpeedSupported
        render()
        assertTrue(chip().fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        chip().performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, cycles)
            assertEquals(GptSpeedMode.FAST, mode.value)
        }
    }

    @Test
    fun gptLongPressCyclesSpeedWhileSingleClickDoesNot() {
        gptSupported.value = true
        options.value = emptyList()
        render()
        chip().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, cycles) }
        chip().performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, cycles) }
    }

    @Test
    fun gptKeepsLongPressSemanticsAndStillOpensReasoningDialogOnClick() {
        gptSupported.value = true
        options.value = listOf(ReasoningEffort.OFF, ReasoningEffort.LOW, ReasoningEffort.HIGH)
        render()
        assertTrue(chip().fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        chip().performTouchInput { longClick() }
        // 长按只切速度，不触发旧的思考弹窗。
        compose.onNodeWithText(reasoningDialogTitle()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, cycles) }
        chip().performTouchInput { click() }
        compose.onNodeWithText(reasoningDialogTitle()).assertExists()
        compose.runOnIdle { assertEquals(1, cycles) }
    }

    @Test
    fun semanticLongPressIgnoresReentryUntilAnimationFinishes() {
        compose.mainClock.autoAdvance = false
        render()
        compose.mainClock.advanceTimeByFrame()

        val startedAt = compose.mainClock.currentTime
        chip().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        // 直接走真实语义入口，不让触摸长按的等待时间替我们跑完450ms动画。
        repeat(3) {
            chip().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        }
        compose.mainClock.advanceTimeBy(200)
        repeat(3) {
            chip().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        }
        assertTrue(compose.mainClock.currentTime - startedAt < 450L)
        compose.runOnUiThread {
            assertEquals(1, cycles)
            assertEquals(GptSpeedMode.FAST, mode.value)
            assertEquals(0, effortChanges)
        }

        // 留出启动帧余量；被拒绝的长按不能在动画结束后排队补执行。
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread {
            assertEquals(1, cycles)
            assertEquals(GptSpeedMode.FAST, mode.value)
        }
        chip().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.runOnUiThread {
            assertEquals(2, cycles)
            assertEquals(GptSpeedMode.ULTRA_FAST, mode.value)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread {
            assertEquals(2, cycles)
            assertEquals(GptSpeedMode.ULTRA_FAST, mode.value)
            assertEquals(0, effortChanges)
        }
    }

    @Test
    fun switchingAwayDuringAnimationRemovesLongPressAndGptCanRestartFromOwnerNormal() {
        compose.mainClock.autoAdvance = false
        render()
        compose.mainClock.advanceTimeByFrame()
        chip().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnUiThread {
            assertEquals(1, cycles)
            assertEquals(GptSpeedMode.FAST, mode.value)
            gptSupported.value = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertFalse(chip().fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        compose.runOnUiThread {
            assertEquals(1, cycles)
            // Chip只撤掉入口/动画；业务档位仍由owner持有，不能把归一化算作UI行为。
            assertEquals(GptSpeedMode.FAST, mode.value)
            mode.value = GptSpeedMode.NORMAL
        }
        compose.mainClock.advanceTimeByFrame()
        assertFalse(chip().fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        compose.runOnUiThread {
            assertEquals(1, cycles)
            assertEquals(GptSpeedMode.NORMAL, mode.value)
            gptSupported.value = true
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(chip().fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        chip().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        chip().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.runOnUiThread {
            assertEquals(2, cycles)
            assertEquals(GptSpeedMode.FAST, mode.value)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread {
            assertEquals(2, cycles)
            assertEquals(GptSpeedMode.FAST, mode.value)
            assertEquals(0, effortChanges)
        }
    }

    @Test
    fun nonGptChipHasNoSpeedLongPressSemantics() {
        gptSupported.value = false
        options.value = listOf(ReasoningEffort.OFF, ReasoningEffort.LOW, ReasoningEffort.HIGH)
        render()
        assertFalse(chip().fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        chip().performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(0, cycles)
            assertEquals(0, effortChanges)
        }
    }
}
