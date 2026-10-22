package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import kotlinx.coroutines.CompletableDeferred
import org.robolectric.shadows.ShadowToast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 子代理 GPT 速度按钮：单击循环、嵌套事件隔离、非 GPT 隐藏、无长按速度语义、
 * 动画闸门与 reset key、两个入口的布局位置、禁用不写。
 *
 * 动画的几何/颜色契约已在 [ThinkingSpeedAnimationTest] 里用纯逻辑覆盖，这里只验证
 * 子代理入口的接线与手势行为，不重复像素级断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "zh-rCN-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubAgentGptSpeedButtonTest {
    @get:Rule val compose = createComposeRule()

    private val providerId = "gpt-provider"
    private val gptRecord = Model(id = "gpt-record", modelId = "gpt-5", displayName = "GPT-5")
    private val gptRecord2 = Model(id = "gpt-record-2", modelId = "gpt-5-mini", displayName = "GPT-5 mini")
    private val textRecord = Model(id = "text-record", modelId = "deepseek-chat", displayName = "DeepSeek Chat")
    private val openAiProvider = OpenAiCompatibleProviderSetting(
        id = providerId, name = "OpenAI 兼容", baseUrl = "https://example.invalid",
        models = listOf(gptRecord, gptRecord2, textRecord),
    )
    private val anthropicProvider = AnthropicProviderSetting(
        id = "anthropic-provider", name = "Anthropic", baseUrl = "https://example.invalid",
        models = listOf(gptRecord),
    )
    private val providers: List<ProviderSetting> = listOf(openAiProvider, anthropicProvider)

    private val gptProfile = SubAgentProfile("speed-main", "测试代理", providerId = providerId, modelId = gptRecord.id)
    private val gptProfileAlt = gptProfile.copy(id = "speed-alt", modelId = gptRecord2.id)
    private val nonGptProfile = gptProfile.copy(id = "speed-text", modelId = textRecord.id)
    private val mediaProfile = SubAgentProfile(
        "speed-media", "图片代理", role = "image_generation", providerId = providerId, modelId = gptRecord.id,
    )
    private val foreignProtocolProfile = gptProfile.copy(id = "speed-foreign", name = "其他协议代理", providerId = anthropicProvider.id)
    private val implProfile = SubAgentProfile(
        "speed-impl", "执行代理", providerId = providerId, modelId = gptRecord.id, tier = SubAgentTaskTier.COMPLEX,
    )
    private val reviewProfile = SubAgentProfile(
        "speed-review", "审查代理", role = "review", providerId = providerId, modelId = gptRecord.id,
    )

    private fun speedNode(name: String) =
        compose.onNodeWithContentDescription("$name GPT 速度", substring = true)

    private fun assertSpeedContentCentered(name: String, label: String, minTouch: androidx.compose.ui.unit.Dp = 48.dp) {
        val touch = speedNode(name).fetchSemanticsNode().boundsInRoot
        val text = compose.onNode(
            hasText(label) and hasAnyAncestor(hasContentDescription("$name GPT 速度", substring = true)),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val minTouchSize = with(compose.density) { minTouch.toPx() }
        // Decorative Icon has no semantics node: recover the group bounds from the 18dp icon
        // slot that abuts the label in the official AssistChip layout.
        val iconAndGap = with(compose.density) { 18.dp.toPx() }
        val groupCenterX = (text.left - iconAndGap + text.right) / 2f
        assertTrue("$name touch width must be at least 48dp", touch.width >= minTouchSize - 1f)
        assertTrue("$name touch height must be at least 48dp", touch.height >= minTouchSize - 1f)
        assertEquals("$name icon/label group must be horizontally centered", touch.center.x, groupCenterX, 1f)
        assertEquals("$name label must be vertically centered", touch.center.y, text.center.y, 1f)
    }

    private fun modeOf(fixture: SubAgentUiFixture, id: String): GptSpeedMode {
        val profile = fixture.snapshot().profiles.first { it.id == id }
        return profile.gptSpeedForModel(profile.providerId, profile.modelId)
    }

    /** 复用真实 editor 快照，等价于父级在写回后把新的 profile 传回按钮。 */
    @Composable
    private fun liveProfile(fixture: SubAgentUiFixture, id: String): SubAgentProfile {
        val config = (fixture.editor.observe() as SubAgentEditorState.Loaded).config
        return config.profiles.first { it.id == id }
    }

    @Test
    fun customResponsesProviderShowsAndCyclesSpeedControl() {
        val custom = io.github.mangi.eta.data.model.CustomProviderSetting(
            id = providerId, name = "GPT", baseUrl = "https://example.invalid",
            endpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES,
            models = listOf(gptRecord.copy(modelId = "gpt-6-astra")),
        )
        val fixture = SubAgentUiFixture(providers = listOf(custom), profiles = listOf(gptProfile))
        compose.setSubAgentContent(fixture) {
            MaterialTheme { SubAgentProfileRow(liveProfile(fixture, gptProfile.id), listOf(custom), enabled = true, settings = true) }
        }
        speedNode("测试代理").assertExists().performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(GptSpeedMode.FAST, modeOf(fixture, gptProfile.id)) }
    }

    @Test
    fun singleClickCyclesThroughAllTiersAndShowsTierText() {
        val fixture = SubAgentUiFixture(providers = providers, profiles = listOf(gptProfile))
        compose.setSubAgentContent(fixture) {
            MaterialTheme { SubAgentGptSpeedButton(liveProfile(fixture, gptProfile.id), providers, enabled = true) }
        }
        val button = speedNode("测试代理")
        button.assertExists().assertHasClickAction()
        compose.runOnIdle { ShadowToast.reset() }
        // 图标 + 档位文字都可识别，而不是只有一个无文字的图标。
        compose.onNodeWithText("标准", useUnmergedTree = true).assertExists()
        assertSpeedContentCentered("测试代理", "标准")

        button.performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(GptSpeedMode.FAST, modeOf(fixture, gptProfile.id))
            assertEquals(1, ShadowToast.shownToastCount())
            assertEquals(gptSpeedToastMessage(GptSpeedMode.FAST), ShadowToast.getTextOfLatestToast())
        }
        compose.onNodeWithText("快速", useUnmergedTree = true).assertExists()
        assertSpeedContentCentered("测试代理", "快速")

        button.performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(GptSpeedMode.ULTRA_FAST, modeOf(fixture, gptProfile.id)) }
        compose.onNodeWithText("极速", useUnmergedTree = true).assertExists()
        assertSpeedContentCentered("测试代理", "极速")

        button.performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(GptSpeedMode.NORMAL, modeOf(fixture, gptProfile.id)) }
        compose.onNodeWithText("标准", useUnmergedTree = true).assertExists()
    }

    @Test
    fun clickingSpeedButtonIsIsolatedFromTheEnclosingRow() {
        val fixture = SubAgentUiFixture(providers = providers, profiles = listOf(gptProfile))
        var rowClicks = 0
        compose.setSubAgentContent(fixture) {
            MaterialTheme {
                SubAgentSettingsRow(
                    "模型", "GPT-5", Icons.Rounded.AccountTree, "隔离行",
                    onClick = { rowClicks++ },
                    trailing = { SubAgentGptSpeedButton(liveProfile(fixture, gptProfile.id), providers, enabled = true) },
                )
            }
        }
        // 触摸单击速度按钮：必须被按钮自己消费，不能触发整行的模型选择。
        speedNode("测试代理").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, rowClicks) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(GptSpeedMode.FAST, modeOf(fixture, gptProfile.id)) }

        compose.onNodeWithContentDescription("隔离行").performClick()
        compose.runOnIdle { assertEquals(1, rowClicks) }
    }

    @Test
    fun onlyNonGptProfilesHideTheButton() {
        val fixture = SubAgentUiFixture(providers = providers, profiles = listOf(nonGptProfile, mediaProfile, foreignProtocolProfile))
        compose.setSubAgentContent(fixture) {
            MaterialTheme {
                Column {
                    SubAgentGptSpeedButton(liveProfile(fixture, nonGptProfile.id), providers, enabled = true)
                    SubAgentGptSpeedButton(liveProfile(fixture, mediaProfile.id), providers, enabled = true)
                    SubAgentGptSpeedButton(liveProfile(fixture, foreignProtocolProfile.id), providers, enabled = true)
                }
            }
        }
        speedNode("测试代理").assertDoesNotExist()
        speedNode("图片代理").assertExists()
        speedNode("其他协议代理").assertExists()
    }

    @Test
    fun speedButtonHasNoLongPressSpeedSemantics() {
        val fixture = SubAgentUiFixture(providers = providers, profiles = listOf(gptProfile))
        compose.setSubAgentContent(fixture) {
            MaterialTheme { SubAgentGptSpeedButton(liveProfile(fixture, gptProfile.id), providers, enabled = true) }
        }
        val button = speedNode("测试代理")
        assertFalse(button.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
    }

    @Test
    fun gateRejectsReentryDuringAnimationAndKeyChangeResetsIt() {
        val fixture = SubAgentUiFixture(providers = providers, profiles = listOf(gptProfile, gptProfileAlt))
        val shown = mutableStateOf(gptProfile)
        compose.setSubAgentContent(fixture) {
            MaterialTheme {
                SubAgentGptSpeedButton(liveProfile(fixture, shown.value.id), providers, enabled = true)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        val button = speedNode("测试代理")

        button.performClick()
        repeat(3) { button.performClick() }
        compose.mainClock.advanceTimeBy(200)
        repeat(3) { button.performClick() }
        // 动画进行中拒绝新的单击：只有第一次被接受，后续不排队、不累计。
        compose.runOnUiThread {
            assertEquals(GptSpeedMode.FAST, modeOf(fixture, gptProfile.id))
            assertEquals(GptSpeedMode.NORMAL, modeOf(fixture, gptProfileAlt.id))
        }

        // reset key 用 profileId + providerId + modelId：切换绑定立刻换新闸门，可再次单击。
        compose.runOnUiThread { shown.value = gptProfileAlt }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        button.performClick()
        compose.runOnUiThread { assertEquals(GptSpeedMode.FAST, modeOf(fixture, gptProfileAlt.id)) }

        // 动画结束、被拒绝的点击也不补执行。
        compose.mainClock.advanceTimeBy(600)
        compose.runOnUiThread {
            assertEquals(GptSpeedMode.FAST, modeOf(fixture, gptProfile.id))
            assertEquals(GptSpeedMode.FAST, modeOf(fixture, gptProfileAlt.id))
        }
    }

    @Test
    fun staleSnapshotAfterSuspendedLookupHasNoToastAndReleasesGate() {
        val lookup = CompletableDeferred<ProviderSetting?>()
        var lookups = 0
        val fixture = SubAgentUiFixture(providers = providers, profiles = listOf(gptProfile), providerLookup = {
            lookups++
            lookup.await()
        })
        compose.setSubAgentContent(fixture) {
            // Deliberately keep the stale UI snapshot to exercise editor rejection, not key disposal.
            MaterialTheme { SubAgentGptSpeedButton(gptProfile, providers, enabled = true) }
        }
        compose.runOnIdle { ShadowToast.reset() }
        speedNode("测试代理").performClick()
        compose.waitForIdle()
        repeat(2) { speedNode("测试代理").performClick() }
        compose.runOnIdle {
            assertEquals(1, lookups)
            fixture.editor.updateProfile(gptProfile.id) { it.copy(modelId = gptRecord2.id) }
            lookup.complete(openAiProvider)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, ShadowToast.shownToastCount())
            assertEquals(GptSpeedMode.NORMAL, modeOf(fixture, gptProfile.id))
            fixture.editor.updateProfile(gptProfile.id) { gptProfile }
        }
        speedNode("测试代理").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, lookups)
            assertEquals(GptSpeedMode.FAST, modeOf(fixture, gptProfile.id))
            assertEquals(1, ShadowToast.shownToastCount())
        }
    }

    @Test
    fun cancellingSuspendedLookupReleasesGateWithoutSuccessFeedback() {
        val lookup = CompletableDeferred<ProviderSetting?>()
        var lookups = 0
        val fixture = SubAgentUiFixture(providers = providers, profiles = listOf(gptProfile), providerLookup = {
            lookups++
            if (lookups == 1) lookup.await() else openAiProvider
        })
        compose.setSubAgentContent(fixture) {
            MaterialTheme { SubAgentGptSpeedButton(liveProfile(fixture, gptProfile.id), providers, enabled = true) }
        }
        compose.runOnIdle { ShadowToast.reset() }
        speedNode("测试代理").performClick()
        compose.waitForIdle()
        speedNode("测试代理").performClick()
        compose.runOnIdle {
            assertEquals(1, lookups)
            lookup.cancel()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, ShadowToast.shownToastCount())
            assertEquals(GptSpeedMode.NORMAL, modeOf(fixture, gptProfile.id))
            assertTrue(fixture.editor.enabled)
        }
        speedNode("测试代理").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, lookups)
            assertEquals(GptSpeedMode.FAST, modeOf(fixture, gptProfile.id))
            assertEquals(1, ShadowToast.shownToastCount())
        }
    }

    @Test
    fun settingsRowKeepsCenteredSpeedShortcutInModelRow() {
        val fixture = SubAgentUiFixture(providers = providers, profiles = listOf(gptProfile))
        compose.setSubAgentContent(fixture) {
            MaterialTheme { SubAgentProfileRow(gptProfile, providers, enabled = true, settings = true) }
        }
        val modelRow = compose.onNodeWithContentDescription("测试代理 模型").fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("GPT-5", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val speed = speedNode("测试代理").fetchSemanticsNode().boundsInRoot
        // The settings shortcut remains inside the model row; the compact card has a config entry.
        assertTrue(speed.top >= modelRow.top - 1f)
        assertTrue(speed.bottom <= modelRow.bottom + 1f)
        assertTrue(speed.left > value.right)
        assertTrue(speed.right <= modelRow.right + 1f)
        // 设置行行高固定，芯片槽 32dp；触控下限由行整体高度承担。
        assertSpeedContentCentered("测试代理", "标准", minTouch = 32.dp)
    }

    @Test
    fun sessionCardPlacesCenteredSpeedBetweenModelInfoAndConfigEntryWithoutOuterTier() {
        val fixture = SubAgentUiFixture(providers = providers, profiles = listOf(implProfile, reviewProfile))
        compose.setSubAgentContent(fixture) {
            MaterialTheme {
                Column {
                    SubAgentProfileRow(implProfile, providers, enabled = true)
                    SubAgentProfileRow(reviewProfile, providers, enabled = true)
                }
            }
        }
        val implInfo = compose.onNode(hasText("执行代理") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val implSpeed = speedNode("执行代理").fetchSemanticsNode().boundsInRoot
        val implConfig = compose.onNodeWithContentDescription("配置 执行代理").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        assertTrue(implSpeed.left >= implInfo.right - 1f)
        assertTrue(implSpeed.right <= implConfig.left + 1f)
        assertSpeedContentCentered("执行代理", "标准")

        val reviewInfo = compose.onNode(hasText("审查代理") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val reviewSpeed = speedNode("审查代理").fetchSemanticsNode().boundsInRoot
        val reviewConfig = compose.onNodeWithContentDescription("配置 审查代理").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        assertTrue(reviewSpeed.left >= reviewInfo.right - 1f)
        assertTrue(reviewSpeed.right <= reviewConfig.left + 1f)
        assertSpeedContentCentered("审查代理", "标准")
        compose.onNodeWithContentDescription("设置执行代理任务分工").assertDoesNotExist()
        compose.onNodeWithContentDescription("设置 审查代理 的任务分工").assertDoesNotExist()
        compose.onNodeWithText("复杂任务").assertDoesNotExist()
    }

    @Test
    fun disabledButtonIsNotEnabledAndDoesNotWrite() {
        val fixture = SubAgentUiFixture(providers = providers, profiles = listOf(gptProfile))
        compose.setSubAgentContent(fixture) {
            MaterialTheme { SubAgentGptSpeedButton(liveProfile(fixture, gptProfile.id), providers, enabled = false) }
        }
        val button = speedNode("测试代理")
        button.assertIsNotEnabled()
        button.performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(GptSpeedMode.NORMAL, modeOf(fixture, gptProfile.id)) }
    }

    @Test
    fun runningEditorDisablesButtonWithoutWriting() {
        val fixture = SubAgentUiFixture(providers = providers, profiles = listOf(gptProfile), canEdit = { false })
        compose.setSubAgentContent(fixture) {
            MaterialTheme { SubAgentGptSpeedButton(liveProfile(fixture, gptProfile.id), providers, enabled = true) }
        }
        val button = speedNode("测试代理")
        button.assertIsNotEnabled()
        button.performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(GptSpeedMode.NORMAL, modeOf(fixture, gptProfile.id)) }
    }
}
