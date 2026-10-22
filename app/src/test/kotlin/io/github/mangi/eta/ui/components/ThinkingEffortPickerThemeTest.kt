package io.github.mangi.eta.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 思考深度弹窗使用官方 Material 3 Expressive 组件渲染：
 * 官方 `Slider` + 官方排版/色角色，并带官方式的两端档位标签。
 *
 * 这里用「两端边界标签」与滑块 tag 作为判别特征，并验证档位回调仍然工作。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThinkingEffortPickerThemeTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    private val options = listOf(
        ReasoningEffort.OFF,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
    )

    private fun renderM3(
        effort: ReasoningEffort,
        onEffortChange: (ReasoningEffort) -> Unit,
    ) {
        compose.setContent {
            MaterialExpressiveTheme(
                colorScheme = lightColorScheme(),
                motionScheme = MotionScheme.expressive(),
            ) {
                ThinkingEffortPickerDialog(
                    show = true,
                    effort = effort,
                    options = options,
                    onDismiss = {},
                    onEffortChange = onEffortChange,
                )
            }
        }
    }

    @Test
    fun material3RendersOfficialSliderWithBoundaryLabels() {
        renderM3(ReasoningEffort.MEDIUM) {}
        // 当前档位大字 + 两端边界标签（官方离散滑块的做法）。
        compose.onNodeWithText(context.getString(ReasoningEffort.MEDIUM.labelRes)).assertExists()
        compose.onNodeWithText(context.getString(options.first().labelRes)).assertExists()
        compose.onNodeWithText(context.getString(options.last().labelRes)).assertExists()
    }

    @Test
    fun material3SliderChangeStillReportsEffort() {
        var reported: ReasoningEffort? = null
        renderM3(ReasoningEffort.OFF) { reported = it }
        // 官方滑块的存在本身就是判别特征（tag 只在 M3 分支出现）。
        val slider = compose.onNodeWithTag("thinking-effort-slider")
        slider.assertExists()
        // 走官方 SetProgress 语义把值推到“高”档（索引 3）。
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { assertEquals(ReasoningEffort.HIGH, reported) }
    }
}
