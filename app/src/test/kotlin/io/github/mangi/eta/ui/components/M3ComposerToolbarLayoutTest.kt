package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 单行浮动工具栏输入区：有输入内容时左侧动作组收起、输入框展开、尾部发送按钮替代语音按钮；
 * 清空后动作组展开回来。断言用英文默认资源的内容描述定位官方图标按钮。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class M3ComposerToolbarLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun contentCollapsesActionGroupAndSwapsVoiceForSendButton() {
        val field = TextFieldState()
        compose.setContent {
            MaterialExpressiveTheme(
                colorScheme = lightColorScheme(),
                motionScheme = MotionScheme.expressive(),
            ) {
                // 与生产调用方一致：只有存在草稿内容时才允许发送。
                val canSend = field.text.isNotBlank()
                Material3ComposerBody(
                    textFieldState = field,
                    focusRequester = remember { FocusRequester() },
                    interactionBlocked = false,
                    isEditingMessage = false,
                    isStreaming = false,
                    isPaused = false,
                    isCompressingContext = false,
                    canContinueDisconnected = false,
                    contextSendBlocked = false,
                    canSend = canSend,
                    usage = AgentContextUsageUi(contextTokens = null, contextWindow = null),
                    popupMaxHeight = 360.dp,
                    onPositionedTopPx = {},
                    conversationId = null,
                    assistantId = "",
                    modelPickerState = AgentModelPickerUiState(),
                    reasoningEffort = ReasoningEffort.OFF,
                    availableReasoningEfforts = emptyList(),
                    gptSpeedMode = GptSpeedMode.NORMAL,
                    gptSpeedSupported = false,
                    voiceState = VoiceModeState(),
                    showMorphLoading = false,
                    pendingImages = emptyList(),
                    pendingFileReferences = emptyList(),
                    conversationMentions = ConversationMentionInputUi(),
                    onStartVoiceMode = {},
                    onStopVoiceMode = {},
                    onCancelMessageEdit = {},
                    onAttachImage = {},
                    onAttachVideo = {},
                    onAttachFiles = {},
                    onAttachFolder = {},
                    onAttachFilePath = {},
                    onEditAssistant = {},
                    onAssistantSelected = {},
                    onModelSelected = { _, _ -> },
                    onReasoningEffortChange = {},
                    onCycleGptSpeedMode = {},
                    onSubmit = {},
                    onStop = {},
                    onContinue = {},
                    onAbortPausedRun = {},
                )
            }
        }
        // 空输入：左侧三个入口（+ / 相机 / 助手与模型）都在，尾部还没有发送按钮。
        compose.onNodeWithContentDescription("Add attachment").assertExists()
        compose.onNodeWithContentDescription("Image").assertExists()
        compose.onNodeWithContentDescription("Assistant & model").assertExists()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
        val expandedActionsFieldWidth = fieldWidth()
        // 左侧三个按钮与输入框竖直居中：官方工具栏内容 Row 是顶对齐（Row 默认 Alignment.Top），
        // 必须显式 align 居中，否则 40dp 按钮相对 56dp 输入框会偏高 8dp。
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val idleFieldBounds = fieldBounds()
        // 空输入（未触发输入）时工具栏应保持单行高度：输入框 56dp + 上下各 8dp 内边距 = 72dp，
        // 外层 36dp 圆角恰好是胶囊；输入框距工具栏顶部因此必须是 8dp（公差 0.5px）。
        assertEquals(
            "空输入时输入框距工具栏顶部应为 8dp（工具栏保持 72dp 胶囊）",
            8f,
            idleFieldBounds.top,
            0.5f,
        )
        val addBounds = compose.onNodeWithContentDescription("Add attachment")
            .fetchSemanticsNode().boundsInRoot
        listOf("Add attachment", "Image", "Assistant & model").forEach { description ->
            val buttonBounds = compose.onNodeWithContentDescription(description)
                .fetchSemanticsNode().boundsInRoot
            assertEquals(
                "$description 应与输入框竖直居中对齐",
                idleFieldBounds.center.y,
                buttonBounds.center.y,
                0.5f,
            )
        }
        // 同心（左侧端盖）：40dp 圆钮到工具栏左端与上下的间距应相等。
        // 外层胶囊高度 72dp → 半径 36dp，减去圆钮半径 20dp = 16dp 同心间距；
        // 工具栏顶边由垂直居中的输入框回推（输入框距工具栏上下各 8dp）。
        val barTop = idleFieldBounds.top - 8f
        assertEquals(
            "+ 按钮到工具栏上端与左端的间距应相等",
            addBounds.top - barTop,
            addBounds.left - root.left,
            0.5f,
        )
        assertEquals(
            "+ 按钮到工具栏左端的同心间距应为 16dp",
            16f,
            addBounds.left - root.left,
            0.5f,
        )

        // 输入文字：左侧动作组收起，输入框展开占满，发送按钮替代语音按钮的位置。
        compose.runOnIdle { field.setTextAndPlaceCursorAtEnd("hello") }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add attachment").assertDoesNotExist()
        compose.onNodeWithContentDescription("Image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Assistant & model").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send").assertExists()
        assertTrue(
            "输入框展开后应比动作组可见时更宽",
            fieldWidth() > expandedActionsFieldWidth,
        )
        // 同心圆角布置：动作组收起后，输入框左右各贴工具栏 8dp 内边距（两端间距相等），
        // 这样外层胶囊（半径 = 高度/2）与内层胶囊（半径 = 高度/2 − 间距）才是同心关系。
        val fieldBounds = fieldBounds()
        assertEquals(
            "输入框距工具栏两端的间距应相等",
            fieldBounds.left - root.left,
            root.right - fieldBounds.right,
            0.5f,
        )
        // 同心圆角（尾部按钮）：40dp 圆形按钮到输入框上下与右端的间距应一致（8dp）。
        val sendBounds = compose.onNodeWithContentDescription("Send")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "尾部按钮到输入框右端与上下的间距应一致",
            (fieldBounds.height - sendBounds.height) / 2f,
            fieldBounds.right - sendBounds.right,
            0.5f,
        )

        // 清空草稿：左侧动作组展开回来，发送按钮让位给语音按钮。
        compose.runOnIdle { field.setTextAndPlaceCursorAtEnd("") }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add attachment").assertExists()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()

        // 多行草稿：输入框随内容长高（最多 6 行），工具栏只应比输入框高 2 × 8dp 内边距；
        // 输入框到工具栏顶部仍是 8dp（工具栏不会被官方补偿内边距撑成巨型胶囊）。
        compose.runOnIdle { field.setTextAndPlaceCursorAtEnd("1\n2") }
        compose.waitForIdle()
        val multiLineBounds = fieldBounds()
        assertTrue("两行草稿应高于单行 56dp", multiLineBounds.height > 60f)
        assertEquals(
            "多行时输入框距工具栏顶部仍应保持 8dp",
            8f,
            multiLineBounds.top,
            0.5f,
        )
        // 发送按钮固定在右下角：与内层 28dp 圆角同心（40dp 圆钮半径 20dp ⇒ 端距 8dp）。
        val multiLineSend = compose.onNodeWithContentDescription("Send")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "多行时发送按钮距输入框右端应为 8dp",
            8f,
            multiLineBounds.right - multiLineSend.right,
            0.5f,
        )
        assertEquals(
            "多行时发送按钮距输入框底端应为 8dp",
            8f,
            multiLineBounds.bottom - multiLineSend.bottom,
            0.5f,
        )
    }

    private fun fieldBounds() =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot

    private fun fieldWidth(): Float = fieldBounds().width
}
