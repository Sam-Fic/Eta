package io.github.mangi.eta.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import io.github.mangi.eta.data.model.AppearanceThemeFamily
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.app.LocalThemeFamily
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

/**
 * 思考深度弹窗必须按主题族各自渲染原生组件：
 * - Material 3 Expressive：官方 `Slider` + 官方排版/色角色，并带官方式的两端档位标签；
 * - MIUI X：保留 miuix 滑块（无两端标签）。
 *
 * 旧实现的回归点是：M3E 下弹窗内容仍然用 miuix 滑块与 miuix 颜色（胶囊轨道 + 大圆拇指）。
 * 这里用「两端边界标签」作为 M3E 分支的判别特征，并验证档位回调仍然工作。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThinkingEffortPickerThemeTest {
    @get:Rule val compose = createComposeRule()

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
                CompositionLocalProvider(
                    LocalThemeFamily provides AppearanceThemeFamily.MATERIAL3_EXPRESSIVE,
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
    }

    private fun renderMiuix(onEffortChange: (ReasoningEffort) -> Unit) {
        compose.setContent {
            MiuixTheme(colors = miuixLightColorScheme()) {
                CompositionLocalProvider(
                    LocalThemeFamily provides AppearanceThemeFamily.MIUI_X,
                ) {
                    ThinkingEffortPickerDialog(
                        show = true,
                        effort = ReasoningEffort.MEDIUM,
                        options = options,
                        onDismiss = {},
                        onEffortChange = onEffortChange,
                    )
                }
            }
        }
    }

    @Test
    fun material3RendersOfficialSliderWithBoundaryLabels() {
        renderM3(ReasoningEffort.MEDIUM) {}
        // 当前档位大字 + 两端边界标签（官方离散滑块的做法）。
        compose.onNodeWithText(ReasoningEffort.MEDIUM.displayName).assertExists()
        compose.onNodeWithText(options.first().displayName).assertExists()
        compose.onNodeWithText(options.last().displayName).assertExists()
    }

    @Test
    fun miuixBranchKeepsOriginalSliderWithoutBoundaryLabels() {
        renderMiuix {}
        // miuix 分支不额外画两端标签，只有当前档位大字。
        compose.onNodeWithText(ReasoningEffort.MEDIUM.displayName).assertExists()
        compose.onNodeWithText(options.first().displayName).assertDoesNotExist()
        compose.onNodeWithText(options.last().displayName).assertDoesNotExist()
    }

    @Test
    fun material3SliderChangeStillReportsEffort() {
        var reported: ReasoningEffort? = null
        renderM3(ReasoningEffort.OFF) { reported = it }
        // 官方滑块的存在本身就是 M3E 分支的判别特征（tag 只在 M3 分支出现）。
        val slider = compose.onNodeWithTag("thinking-effort-slider")
        slider.assertExists()
        // 走官方 SetProgress 语义把值推到“高”档（索引 3）。
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { assertEquals(ReasoningEffort.HIGH, reported) }
    }
}
