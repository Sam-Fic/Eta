package io.github.mangi.eta.ui.components

import android.content.SharedPreferences

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "zh-rCN-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationCollaborationDialogTest {
    @get:Rule val compose = createComposeRule()

    /** Fails before memory changes; the repository still requires explicit durability recovery. */
    private class FailOncePreferences(private val real: SharedPreferences) : SharedPreferences by real {
        var failNextCommit = false
        override fun edit(): SharedPreferences.Editor {
            val edit = real.edit()
            return object : SharedPreferences.Editor by edit {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    edit.putString(key, value); return this
                }
                override fun remove(key: String?): SharedPreferences.Editor { edit.remove(key); return this }
                override fun commit(): Boolean {
                    if (failNextCommit) { failNextCommit = false; return false }
                    return edit.commit()
                }
            }
        }
    }

    @Test fun appliedPanelWriteFailureIsVisibleAndExplicitRetryRestoresEditing() {
        lateinit var prefs: FailOncePreferences
        val fixture = SubAgentUiFixture(preferenceTransform = {
            FailOncePreferences(it).also { wrapped -> prefs = wrapped }
        })
        val preset = fixture.createPreset()
        compose.setSubAgentContent(fixture) {
            ConversationCollaborationDialog(true, true, {}, {})
        }
        compose.onNodeWithContentDescription("应用子代理组${preset.name}").performScrollTo().performClick()
        compose.onNodeWithText("切换子代理组").assertIsEnabled()
        val before = fixture.snapshot().enabled
        compose.runOnIdle { prefs.failNextCommit = true }
        compose.onNodeWithText("自动委派").performScrollTo().performClick()
        compose.onNodeWithText("本会话配置保存或读取失败", substring = true).assertExists()
        compose.onNodeWithText("切换子代理组").assertIsNotEnabled()
        compose.onNodeWithText("重试本会话配置").performScrollTo().performClick()
        compose.onNodeWithText("本会话配置保存或读取失败", substring = true).assertDoesNotExist()
        compose.onNodeWithText("切换子代理组").assertIsEnabled()
        compose.runOnIdle { org.junit.Assert.assertEquals(before, fixture.snapshot().enabled) }
        compose.onNodeWithText("自动委派").performScrollTo().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(!before, fixture.snapshot().enabled)
            org.junit.Assert.assertEquals(preset.config.enabled,
                fixture.repository.snapshot(io.github.mangi.eta.agent.delegation.SubAgentConfigKey.Preset(preset.id)).enabled)
        }
    }

    @Test fun showsRolesAndToggleWithoutObsoleteReadOnlyParagraph() {
        val fixture = SubAgentUiFixture(enabled = false)
        val visible = mutableStateOf(true)
        compose.setSubAgentContent(fixture) {
            ConversationCollaborationDialog(visible.value, false,
                {}, { visible.value = false })
        }
        compose.onNodeWithText("本会话协作").assertExists()
        compose.onNodeWithText("选择子代理组").assertExists()
        compose.onNodeWithText("使用当前配置").assertIsDisplayed().performClick()
        listOf("执行代理 1", "执行代理 2", "执行代理 3", "审查／总结代理").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("点按模型切换 · 长按调整思考", substring = true).assertExists()
        compose.onNodeWithText("最多两个只读子代理", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertFalse(fixture.snapshot().enabled) }
        compose.onNodeWithText("自动委派").performClick()
        compose.runOnIdle { assertTrue(fixture.snapshot().enabled) }
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertFalse(visible.value) }
    }
    @Test fun emptySlotClickOpensModelPickerAndLongPressDoesNotTriggerClick() {
        val fixture = SubAgentUiFixture()
        compose.setSubAgentContent(fixture) {
            ConversationCollaborationDialog(true, true, {}, {})
        }
        compose.onNodeWithText("使用当前配置").assertIsDisplayed().performClick()
        compose.onNode(hasText("执行代理 1") and hasText("无")).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithText("为 执行代理 1 选择模型").assertDoesNotExist()
        compose.onNode(hasText("执行代理 1") and hasText("无")).performTouchInput { click() }
        compose.onNodeWithText("为 执行代理 1 选择模型").assertExists()
        compose.onNode(hasText("无") and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)).performClick()
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithText("本会话协作").assertExists()
        compose.runOnIdle { assertTrue(fixture.snapshot().profiles.first { it.id == "legacy-0" }.modelId.isBlank()) }
    }

    @Test fun runningTaskDisablesAllControlsAndClosesOpenPicker() {
        val running = mutableStateOf(false)
        val fixture = SubAgentUiFixture(canEdit = { !running.value })
        val before = fixture.snapshot()
        val revisionBefore = fixture.repository.revision(fixture.owner).value
        var changes = 0
        var dismissals = 0
        compose.setSubAgentContent(fixture) {
            ConversationCollaborationDialog(true, true, { changes++ }, { dismissals++ }, taskRunning = running.value)
        }
        compose.onNodeWithText("使用当前配置").assertIsDisplayed().performClick()
        compose.onNode(hasText("执行代理 1") and hasText("无")).performClick()
        compose.onNodeWithText("为 执行代理 1 选择模型").assertExists()
        compose.runOnIdle { running.value = true }
        compose.onNodeWithText("为 执行代理 1 选择模型").assertDoesNotExist()
        compose.onNodeWithText("完成").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("执行代理 1").assertIsNotEnabled()
        compose.onNode(hasText("执行代理 1") and hasText("无")).performTouchInput { click(); longClick() }
        compose.runOnIdle { org.junit.Assert.assertEquals("disabled model row must not dismiss", 0, dismissals) }
        compose.onNodeWithText("自动委派").performTouchInput { click() }
        compose.runOnIdle { org.junit.Assert.assertEquals("disabled toggle must not dismiss", 0, dismissals) }
        compose.onNodeWithText("完成").performTouchInput { click() }
        compose.runOnIdle {
            org.junit.Assert.assertEquals(0, changes)
            org.junit.Assert.assertEquals(0, dismissals)
            org.junit.Assert.assertEquals(before, fixture.snapshot())
            org.junit.Assert.assertEquals(revisionBefore, fixture.repository.revision(fixture.owner).value)
            running.value = false
        }
        compose.onNodeWithText("执行代理 1").assertIsEnabled()
        compose.onNodeWithText("完成").assertIsEnabled().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, dismissals) }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h480dp")
    fun lockedDialogKeepsDisabledDoneButtonInsideSmallViewport() {
        var dismissals = 0
        compose.setSubAgentContent {
            ConversationCollaborationDialog(true, true, {}, { dismissals++ }, taskRunning = true)
        }
        compose.onNodeWithText("完成").assertIsDisplayed().assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { org.junit.Assert.assertEquals(0, dismissals) }
    }

    // Robolectric #8460: dialog + TextField livelocks above ~340dp window width.
    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h891dp")
    fun fullConfigShowsTierChoicesAndClosesWhenTaskStarts() {
        val running = mutableStateOf(false)
        val fixture = SubAgentUiFixture(canEdit = { !running.value })
        val before = fixture.snapshot()
        val revision = fixture.repository.revision(fixture.owner).value
        compose.setSubAgentContent(fixture) {
            ConversationCollaborationDialog(true, true, {}, {}, taskRunning = running.value)
        }
        compose.onNodeWithText("使用当前配置").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("设置 执行代理 1 的任务分工").assertDoesNotExist()
        compose.onNodeWithContentDescription("配置 执行代理 1").performClick()
        compose.onNodeWithContentDescription("名称").assertIsDisplayed().performTextReplacement("运行前未确认草稿")
        compose.onNodeWithContentDescription("草稿任务分工").performScrollTo().performClick()
        listOf("简单任务", "常规任务", "复杂任务").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("为 执行代理 1 选择模型").assertDoesNotExist()
        compose.runOnIdle { running.value = true }
        compose.onNodeWithText("复杂任务").assertDoesNotExist()
        compose.onNodeWithContentDescription("名称").assertDoesNotExist()
        compose.onNodeWithText("执行代理 1").assertIsNotEnabled()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(before, fixture.snapshot())
            org.junit.Assert.assertEquals(revision, fixture.repository.revision(fixture.owner).value)
        }
    }

    // Robolectric #8460: dialog + TextField livelocks above ~340dp window width.
    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h891dp")
    fun conversationTaskTierDraftSavesReopensAndRoleSwitchDoesNotWriteBackToPreset() {
        val fixture = SubAgentUiFixture(profiles = listOf(SubAgentProfile("tier-agent", "分工代理")))
        val preset = fixture.createPreset()
        val presetOwner = SubAgentConfigKey.Preset(preset.id)
        val visible = mutableStateOf(true)
        compose.setSubAgentContent(fixture) {
            ConversationCollaborationDialog(visible.value, true, {}, { visible.value = false })
        }
        compose.onNodeWithContentDescription("应用子代理组${preset.name}").performClick()
        compose.onNodeWithContentDescription("配置 分工代理").performClick()
        compose.onNodeWithContentDescription("草稿任务分工").performScrollTo().performClick()
        compose.onNodeWithText("常规任务").performClick()
        compose.runOnIdle {
            assertNull(fixture.snapshot().profiles.single().tier) // Still a draft until confirmed.
            assertEquals(preset.config, fixture.repository.snapshot(presetOwner))
        }
        compose.onNodeWithText("确认").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("草稿任务分工").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(SubAgentTaskTier.REGULAR, fixture.snapshot().profiles.single().tier)
            assertEquals(preset.config, fixture.repository.snapshot(presetOwner))
        }
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithContentDescription("配置 分工代理").performClick()
        compose.onNodeWithContentDescription("草稿任务分工").performScrollTo().assert(hasText("常规任务"))
        compose.onNodeWithContentDescription("草稿职责").performScrollTo().performClick()
        compose.onNodeWithText("审查/总结").performClick()
        compose.onNodeWithContentDescription("草稿任务分工").assertDoesNotExist()
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle {
            assertEquals("review", fixture.snapshot().profiles.single().role)
            assertNull(fixture.snapshot().profiles.single().tier)
            assertEquals(preset.config, fixture.repository.snapshot(presetOwner))
        }
        compose.onNodeWithContentDescription("配置 分工代理").performClick()
        compose.onNodeWithContentDescription("草稿任务分工").assertDoesNotExist()
        compose.onNodeWithContentDescription("草稿职责").performScrollTo().performClick()
        compose.onNodeWithText("实现").performClick()
        compose.onNodeWithContentDescription("草稿任务分工").performScrollTo().assert(hasText("未设置分工"))
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithContentDescription("配置 分工代理").performClick()
        compose.onNodeWithContentDescription("草稿任务分工").performScrollTo().assert(hasText("未设置分工"))
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals("implementation", fixture.snapshot().profiles.single().role)
            assertNull(fixture.snapshot().profiles.single().tier)
            assertEquals(preset.config, fixture.repository.snapshot(presetOwner))
        }
    }

    @Test fun agentModelAndNameShareLeftColumnAndConfigLivesOnRight() {
        compose.setSubAgentContent {
            ConversationCollaborationDialog(true, true, {}, {})
        }
        compose.onNodeWithText("使用当前配置").assertIsDisplayed().performClick()
        val info = compose.onNode(hasText("执行代理 1") and hasText("无")).fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithContentDescription("配置 执行代理 1").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("config must be to the right of the model column", more.left >= info.right)
        org.junit.Assert.assertTrue("name and model belong to the same column", compose
            .onNode(hasText("执行代理 1") and hasText("无")).fetchSemanticsNode().config
            .contains(androidx.compose.ui.semantics.SemanticsProperties.Text))
        compose.onNodeWithContentDescription("设置 审查／总结代理 的任务分工").assertDoesNotExist()
        compose.onNodeWithText("完成").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h480dp")
    fun compactConfigKeepsAccessibleTouchTargetAndDoneStaysVisible() {
        compose.setSubAgentContent {
            ConversationCollaborationDialog(true, true, {}, {})
        }
        compose.onNodeWithText("使用当前配置").assertIsDisplayed().performClick()
        // M3 1.5 官方 IconButton：容器 40dp，触控区由 minimumInteractiveComponentSize 保证 48dp。
        compose.onNodeWithContentDescription("配置 执行代理 1")
            .assertHeightIsAtLeast(40.dp)
            .assertWidthIsAtLeast(40.dp)
        compose.onNodeWithText("完成").assertIsDisplayed()
        compose.onNode(hasText("审查／总结代理") and hasText("无")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成").assertIsDisplayed()
    }

    @Test fun openingLegacyConfigDoesNotWriteAndUseCurrentRetainsIt() {
        val fixture = SubAgentUiFixture(enabled = false)
        val before = fixture.snapshot()
        val revision = fixture.repository.revision(fixture.owner).value
        compose.setSubAgentContent(fixture) {
            ConversationCollaborationDialog(true, false, {}, {})
        }
        compose.onNodeWithText("选择子代理组").assertExists()
        compose.onNode(hasText("执行代理 1") and hasText("无")).assertDoesNotExist()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(before, fixture.snapshot())
            org.junit.Assert.assertEquals(revision, fixture.repository.revision(fixture.owner).value)
        }
        compose.onNodeWithText("使用当前配置").assertIsDisplayed().performClick()
        compose.onNodeWithText("切换子代理组").assertExists()
        compose.onNode(hasText("执行代理 1") and hasText("无")).assertExists()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(before, fixture.snapshot())
            org.junit.Assert.assertEquals(revision, fixture.repository.revision(fixture.owner).value)
        }
    }

    @Test fun applyingCopiesToConversationAndSwitchingDisposesOpenRows() {
        val fixture = SubAgentUiFixture()
        val first = fixture.repository.addPreset("第一组")
        val second = fixture.repository.addPreset("第二组")
        val profile = io.github.mangi.eta.agent.delegation.SubAgentProfile("shared", "组代理")
        val firstOwner = io.github.mangi.eta.agent.delegation.SubAgentConfigKey.Preset(first.id)
        fixture.repository.update(firstOwner) { it.copy(profiles = listOf(profile)) }
        fixture.repository.update(io.github.mangi.eta.agent.delegation.SubAgentConfigKey.Preset(second.id)) {
            it.copy(profiles = listOf(profile.copy(name = "第二组代理")), enabled = false)
        }
        compose.setSubAgentContent(fixture) {
            ConversationCollaborationDialog(true, true, {}, {})
        }
        compose.onNodeWithContentDescription("应用子代理组第一组").performScrollTo().performClick()
        compose.onNodeWithText("选择子代理组").assertDoesNotExist()
        compose.onNodeWithText("组代理").assertExists()
        val token = fixture.snapshot().presetApplicationToken
        compose.onNodeWithText("自动委派").performClick()
        compose.runOnIdle {
            assertFalse(fixture.snapshot().enabled)
            assertTrue(fixture.repository.snapshot(firstOwner).enabled)
            org.junit.Assert.assertEquals(first.id, fixture.snapshot().appliedPresetId)
        }
        compose.onNode(hasText("组代理") and hasText("无")).performClick()
        compose.onNodeWithText("为 组代理 选择模型").assertExists()
        // External apply simulates switching in another owner-bound view while a picker is open.
        compose.runOnIdle { fixture.editor.applyPreset(second.id) }
        compose.onNodeWithText("为 组代理 选择模型").assertDoesNotExist()
        compose.onNodeWithText("第二组代理").assertExists()
        compose.runOnIdle { org.junit.Assert.assertNotEquals(token, fixture.snapshot().presetApplicationToken) }
        compose.onNodeWithText("切换子代理组").performClick()
        compose.onNodeWithContentDescription("应用子代理组第一组").performScrollTo().performClick()
        compose.onNodeWithText("组代理").assertExists()
        compose.onNodeWithText("切换子代理组").assertExists()
    }

    @Test fun runningTaskLocksPresetCardsAndCurrentConfigEntry() {
        val running = mutableStateOf(true)
        val fixture = SubAgentUiFixture(canEdit = { !running.value })
        val group = fixture.createPreset()
        val before = fixture.snapshot()
        compose.setSubAgentContent(fixture) {
            ConversationCollaborationDialog(true, true, {}, {}, taskRunning = running.value)
        }
        compose.onNodeWithContentDescription("应用子代理组${group.name}").assertIsNotEnabled()
            .performTouchInput { click() }
        compose.onNodeWithText("使用当前配置").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(before, fixture.snapshot())
            running.value = false
        }
        compose.onNodeWithContentDescription("应用子代理组${group.name}").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("切换子代理组").assertExists()
    }

    private fun assertFooterAligned(leftLabel: String) {
        compose.onAllNodesWithText(leftLabel).assertCountEquals(1)
        val left = compose.onNodeWithText(leftLabel).assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("subagent-collaboration-footer"))).fetchSemanticsNode().boundsInRoot
        val done = compose.onNodeWithText("完成").assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("subagent-collaboration-footer"))).fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("subagent-collaboration-footer").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("subagent-collaboration-body").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("subagent-collaboration-card").fetchSemanticsNode().boundsInRoot
        assertTrue("footer actions must not overlap", left.right <= done.left)
        assertTrue("footer actions must be horizontally aligned", kotlin.math.abs(left.center.y - done.center.y) < 1f)
        assertTrue("scrolling body must end above footer", body.bottom <= footer.top)
        assertTrue("left action must remain in the card", left.left >= card.left && left.bottom <= card.bottom)
        assertTrue("done action must remain in the card", done.right <= card.right && done.bottom <= card.bottom)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h480dp")
    fun emptyChooserAndEmptyCurrentConfigWrapHeightAndAlignFooterWithoutWrites() {
        val fixture = SubAgentUiFixture(profiles = emptyList(), enabled = false)
        val before = fixture.snapshot()
        val revision = fixture.repository.revision(fixture.owner).value
        compose.setSubAgentContent(fixture) { ConversationCollaborationDialog(true, false, {}, {}) }
        assertFooterAligned("使用当前配置")
        // M3E sheet 头部+空态+底部实测约 320dp；仍须明显小于全屏（480dp 视口）。
        assertTrue("empty content must not force a tall card",
            compose.onNodeWithTag("subagent-collaboration-card").getUnclippedBoundsInRoot().let { it.bottom - it.top < 400.dp })
        compose.onNodeWithText("切换子代理组").assertDoesNotExist()
        compose.onNodeWithText("使用当前配置").performClick()
        assertFooterAligned("切换子代理组")
        // M3E sheet 头部+空态+底部实测约 320dp；仍须明显小于全屏（480dp 视口）。
        assertTrue("empty content must not force a tall card",
            compose.onNodeWithTag("subagent-collaboration-card").getUnclippedBoundsInRoot().let { it.bottom - it.top < 400.dp })
        compose.onNodeWithText("使用当前配置").assertDoesNotExist()
        compose.onNodeWithText("自动委派").assertIsDisplayed()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(before, fixture.snapshot())
            org.junit.Assert.assertEquals(revision, fixture.repository.revision(fixture.owner).value)
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h480dp")
    fun longChooserAndAppliedPanelKeepBothFooterActionsFixedDuringScroll() {
        val profiles = (1..12).map { io.github.mangi.eta.agent.delegation.SubAgentProfile("worker-$it", "代理 $it") }
        val fixture = SubAgentUiFixture(profiles = profiles)
        val groups = (1..8).map { fixture.createPreset("子代理组 $it") }
        compose.setSubAgentContent(fixture) { ConversationCollaborationDialog(true, true, {}, {}) }
        assertFooterAligned("使用当前配置")
        val chooserFooter = compose.onNodeWithTag("subagent-collaboration-footer").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("应用子代理组${groups.last().name}").performScrollTo().assertIsDisplayed()
        assertFooterAligned("使用当前配置")
        org.junit.Assert.assertEquals(chooserFooter,
            compose.onNodeWithTag("subagent-collaboration-footer").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("应用子代理组${groups.last().name}").performClick()
        compose.onNodeWithText("已应用：${groups.last().name}").assertExists()
        assertFooterAligned("切换子代理组")
        val panelFooter = compose.onNodeWithTag("subagent-collaboration-footer").fetchSemanticsNode().boundsInRoot
        compose.onNode(hasText("代理 12") and hasText("无")).performScrollTo().assertIsDisplayed()
        assertFooterAligned("切换子代理组")
        org.junit.Assert.assertEquals(panelFooter,
            compose.onNodeWithTag("subagent-collaboration-footer").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("切换子代理组").performClick()
        assertFooterAligned("使用当前配置")
        compose.onNode(hasText("代理 12") and hasText("无")).assertDoesNotExist()
    }

    @Test fun retainedFooterCallbacksCannotOperateAReopenedChooserOrReappliedPanel() {
        val fixture = SubAgentUiFixture()
        val preset = fixture.createPreset()
        compose.setSubAgentContent(fixture) { ConversationCollaborationDialog(true, true, {}, {}) }
        val oldUseCurrent = compose.onNodeWithText("使用当前配置").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.onNodeWithText("使用当前配置").performClick()
        compose.onNodeWithText("切换子代理组").performClick()
        compose.runOnIdle { oldUseCurrent() }
        compose.onNodeWithText("选择子代理组").assertExists()
        compose.onNodeWithText("使用当前配置").assertIsDisplayed()
        compose.onNodeWithContentDescription("应用子代理组${preset.name}").performScrollTo().performClick()
        val oldSwitch = compose.onNodeWithText("切换子代理组").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { fixture.editor.applyPreset(preset.id) }
        compose.onNodeWithText("切换子代理组").assertIsDisplayed()
        compose.runOnIdle { oldSwitch() }
        compose.onNodeWithText("选择子代理组").assertDoesNotExist()
        compose.onNodeWithText("切换子代理组").assertIsDisplayed()
    }

    @Test fun ownerLossWhileChoosingDismissesOldCardsWithoutApplying() {
        val matches = mutableStateOf(true)
        val fixture = SubAgentUiFixture()
        val before = fixture.snapshot()
        compose.setSubAgentContent(fixture) {
            ConversationCollaborationDialog(true, true, {}, {}, ownerMatches = { matches.value })
        }
        compose.onNodeWithText("选择子代理组").assertExists()
        compose.runOnIdle { matches.value = false }
        compose.onNodeWithText("选择子代理组").assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertEquals(before, fixture.snapshot()) }
    }
}
