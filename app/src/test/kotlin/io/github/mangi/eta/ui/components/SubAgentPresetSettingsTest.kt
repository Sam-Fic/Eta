package io.github.mangi.eta.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.ui.SubAgentSettingsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "zh-rCN-w320dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubAgentPresetSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsStartsAtGroupsAndBothBackEntrypointsLeaveDetailFirst() {
        val fixture = SubAgentUiFixture(canEdit = { false }) // A running conversation must not lock presets.
        val group = fixture.createPreset()
        val before = fixture.snapshot()
        var exits = 0
        compose.setSubAgentContent(fixture) {
            MaterialTheme(colorScheme = lightColorScheme()) { SubAgentSettingsScreen({ exits++ }, { fixture.repository }) }
        }
        compose.onNodeWithText("添加子代理组", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("添加子代理", substring = false).assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑子代理组 ${group.name}").performClick()
        compose.onNodeWithText("添加子代理", substring = false, useUnmergedTree = true).assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("名称").performTextReplacement("未确认的新代理")
        compose.onNodeWithText("确认").assertIsNotEnabled() // New profiles require a valid model.
        compose.runOnIdle {
            assertEquals(group.config, fixture.repository.snapshot(SubAgentConfigKey.Preset(group.id)))
            assertEquals(before, fixture.snapshot())
            assertEquals(0, exits)
        }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(group.config, fixture.repository.snapshot(SubAgentConfigKey.Preset(group.id))) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("添加子代理组", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("编辑子代理组 ${group.name}").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("添加子代理组", useUnmergedTree = true).assertExists()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h480dp")
    fun presetMoreOpensActionMenuOnSmallScreenWithoutEditingOrCopyAction() {
        val fixture = SubAgentUiFixture(enabled = false)
        val group = fixture.createPreset("一个较长的子代理组名称用于窄屏面板")
        val before = fixture.snapshot()
        compose.setSubAgentContent(fixture) { SubAgentSettingsScreen({}, { fixture.repository }) }
        compose.onNodeWithContentDescription("${group.name} 组操作").performScrollTo()
            .assertHeightIsAtLeast(48.dp).performClick()
        val rename = compose.onNodeWithText("重命名组").assertIsDisplayed().assertHasClickAction()
        val delete = compose.onNodeWithText("删除组").assertIsDisplayed().assertHasClickAction()
        val renameBounds = rename.fetchSemanticsNode().boundsInRoot
        val deleteBounds = delete.fetchSemanticsNode().boundsInRoot
        assertTrue("actions must stack vertically", deleteBounds.top >= renameBounds.bottom)
        assertTrue("icons and labels must align", kotlin.math.abs(deleteBounds.left - renameBounds.left) < 1f)
        compose.onNodeWithText("复制").assertDoesNotExist()
        compose.onNodeWithText("配置").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(before, fixture.snapshot())
            assertEquals(group.config, fixture.repository.snapshot(SubAgentConfigKey.Preset(group.id)))
        }
        rename.performClick()
        compose.onNodeWithText("重命名组").assertDoesNotExist()
        compose.onNodeWithContentDescription("组名称").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("${group.name} 组操作").performScrollTo().performClick()
        compose.onNodeWithText("删除组").performClick()
        compose.onNodeWithText("删除组").assertDoesNotExist()
        compose.onNodeWithText("删除子代理组？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(group.config, fixture.repository.snapshot(SubAgentConfigKey.Preset(group.id)))
            assertEquals(before, fixture.snapshot())
        }
    }

    @Test fun presetDetailHidesGlobalSwitchesAndProfileEditsPreserveAutomaticDelegation() {
        val fixture = SubAgentUiFixture(enabled = false)
        val group = fixture.createPreset()
        val before = fixture.snapshot()
        compose.setSubAgentContent(fixture) { SubAgentSettingsScreen({}, { fixture.repository }) }
        compose.onNodeWithContentDescription("编辑子代理组 ${group.name}").performClick()
        compose.onNodeWithText("自动委派", substring = false).assertDoesNotExist()
        compose.onNodeWithText("子代理诊断日志").assertDoesNotExist()
        compose.onNodeWithContentDescription("启用 执行代理 1").performTouchInput { click() }
        compose.onNodeWithContentDescription("执行代理 1 操作").performClick()
        compose.onNodeWithText("重命名", substring = false).performClick()
        compose.onNodeWithText("重命名代理").assertIsDisplayed()
        compose.onNodeWithContentDescription("名称").assertIsDisplayed().performTextReplacement("预设代理新名")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val saved = fixture.repository.snapshot(SubAgentConfigKey.Preset(group.id))
            assertFalse("removing the switch must not replace the stored enabled value", saved.enabled)
            assertFalse(saved.profiles.first().enabled)
            assertEquals("预设代理新名", saved.profiles.first().name)
            assertEquals(before, fixture.snapshot())
            assertTrue(fixture.repository.update(SubAgentConfigKey.Preset(group.id)) { it.copy(enabled = true) }
                is io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences.WriteResult.Saved)
        }
        compose.onNodeWithContentDescription("启用 预设代理新名").performTouchInput { click() }
        compose.runOnIdle {
            val saved = fixture.repository.snapshot(SubAgentConfigKey.Preset(group.id))
            assertTrue("profile edits must also preserve an enabled preset", saved.enabled)
            assertTrue(saved.profiles.first().enabled)
            assertEquals(before, fixture.snapshot())
        }
    }

    @Test fun presetTaskTierSavesReopensAndCanBeClearedWithoutChangingConversation() {
        val agent = SubAgentProfile("tier-agent", "分工代理")
        val fixture = SubAgentUiFixture(profiles = listOf(agent))
        val group = fixture.createPreset()
        val owner = SubAgentConfigKey.Preset(group.id)
        val before = fixture.snapshot()
        compose.setSubAgentContent(fixture) { SubAgentSettingsScreen({}, { fixture.repository }) }
        compose.onNodeWithContentDescription("编辑子代理组 ${group.name}").performClick()
        compose.onNodeWithContentDescription("设置 分工代理 的任务分工").assertIsEnabled().assert(hasText("未设置分工"))
        compose.runOnIdle { assertNull(fixture.repository.snapshot(owner).profiles.single().tier) }
        for (tier in SubAgentTaskTier.entries) {
            compose.onNodeWithContentDescription("设置 分工代理 的任务分工").performClick()
            compose.onNode(isSelectable() and hasText(tier.label)).performClick()
            compose.runOnIdle {
                assertEquals(tier, fixture.repository.snapshot(owner).profiles.single().tier)
                assertEquals(before, fixture.snapshot())
            }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("编辑子代理组 ${group.name}").performClick()
            compose.onNodeWithContentDescription("设置 分工代理 的任务分工").assert(hasText(tier.label)).performClick()
            compose.onNode(isSelectable() and hasText(tier.label)).assertIsSelected().performClick()
        }
        compose.onNodeWithContentDescription("设置 分工代理 的任务分工").performClick()
        compose.onNode(isSelectable() and hasText("未设置分工")).performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("编辑子代理组 ${group.name}").performClick()
        compose.onNodeWithContentDescription("设置 分工代理 的任务分工").assert(hasText("未设置分工")).performClick()
        compose.onNode(isSelectable() and hasText("未设置分工")).assertIsSelected().performClick()
        compose.runOnIdle {
            assertNull(fixture.repository.snapshot(owner).profiles.single().tier)
            assertEquals(before, fixture.snapshot())
        }
    }

    @Test fun presetRoleSwitchOnlyShowsTaskTierForImplementationAndDropsInvalidTier() {
        val fixture = SubAgentUiFixture(profiles = listOf(
            SubAgentProfile("tier-agent", "分工代理", tier = SubAgentTaskTier.COMPLEX)))
        val group = fixture.createPreset()
        val owner = SubAgentConfigKey.Preset(group.id)
        val before = fixture.snapshot()
        compose.setSubAgentContent(fixture) { SubAgentSettingsScreen({}, { fixture.repository }) }
        compose.onNodeWithContentDescription("编辑子代理组 ${group.name}").performClick()
        compose.onNodeWithContentDescription("设置 分工代理 的任务分工").assert(hasText("复杂任务"))
        for ((role, label) in listOf("review" to "审查/总结", "image_generation" to "图片生成", "video_generation" to "视频生成")) {
            compose.onNodeWithContentDescription("为 分工代理 选择职责").performClick()
            compose.onNode(isSelectable() and hasText(label)).performClick()
            compose.onNodeWithContentDescription("设置 分工代理 的任务分工").assertDoesNotExist()
            compose.onNodeWithText("任务分工").assertDoesNotExist()
            compose.runOnIdle {
                val saved = fixture.repository.snapshot(owner).profiles.single()
                assertEquals(role, saved.role)
                assertNull(saved.tier)
                assertEquals(before, fixture.snapshot())
            }
            compose.onNodeWithContentDescription("为 分工代理 选择职责").performClick()
            compose.onNode(isSelectable() and hasText("实现")).performClick()
            compose.onNodeWithContentDescription("设置 分工代理 的任务分工").assertIsEnabled().assert(hasText("未设置分工"))
            compose.runOnIdle { assertNull(fixture.repository.snapshot(owner).profiles.single().tier) }
        }
    }

    @Test fun addNamedEmptyGroupRenameAndDeleteDoNotChangeConversation() {
        val fixture = SubAgentUiFixture()
        val before = fixture.snapshot()
        compose.setSubAgentContent(fixture) {
            MaterialTheme(colorScheme = lightColorScheme()) { SubAgentSettingsScreen({}, { fixture.repository }) }
        }
        assertTrue("The real form must settle with the standard Compose clock", compose.mainClock.autoAdvance)
        val nameField = compose.onNodeWithContentDescription("组名称")

        compose.onNodeWithText("添加子代理组", useUnmergedTree = true).performClick()
        // CI used to time out fetching this node, before performTextInput requested focus.
        // Assert layout and semantics have settled separately from the subsequent focus/input action.
        nameField.assertIsDisplayed().assertIsNotFocused().assert(hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("保存").assertIsNotEnabled()
        nameField.performTextInput("未保存的新组")
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.onNodeWithText("取消").performClick()
        nameField.assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(fixture.repository.presets().any { it.name == "未保存的新组" })
            assertEquals(before, fixture.snapshot())
        }

        compose.onNodeWithText("添加子代理组", useUnmergedTree = true).performClick()
        nameField.assertIsDisplayed().assertIsNotFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        nameField.performTextInput(" 新组 ")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        nameField.assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑子代理组 新组").performScrollTo().performClick()
        compose.onNodeWithText("添加子代理", substring = false, useUnmergedTree = true).assertExists()
        var createdId = ""
        compose.runOnIdle {
            val created = fixture.repository.presets().single { it.name == "新组" }
            createdId = created.id
            assertTrue(created.config.profiles.isEmpty())
            assertEquals(before, fixture.snapshot())
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("新组 组操作").performScrollTo().performClick()
        compose.onNodeWithText("重命名组").performClick()
        nameField.assertIsDisplayed().assertIsNotFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("新组")))
        nameField.performTextReplacement("未保存的改名")
        compose.onNodeWithText("取消").performClick()
        nameField.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("新组", fixture.repository.presets().single { it.id == createdId }.name)
            assertEquals(before, fixture.snapshot())
        }

        compose.onNodeWithContentDescription("新组 组操作").performScrollTo().performClick()
        compose.onNodeWithText("重命名组").performClick()
        nameField.assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("新组")))
        nameField.performTextReplacement(" 改名组 ")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        nameField.assertDoesNotExist()
        compose.runOnIdle {
            val renamed = fixture.repository.presets().single { it.id == createdId }
            assertEquals("改名组", renamed.name)
            assertTrue(renamed.config.profiles.isEmpty())
            assertEquals(before, fixture.snapshot())
        }
        compose.onNodeWithContentDescription("改名组 组操作").performScrollTo().performClick()
        compose.onNodeWithText("删除组").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.onNodeWithContentDescription("编辑子代理组 改名组").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(fixture.repository.presets().any { it.name == "改名组" })
            assertFalse(fixture.repository.presets().any { it.id == createdId })
            assertEquals(before, fixture.snapshot())
        }
    }
}
