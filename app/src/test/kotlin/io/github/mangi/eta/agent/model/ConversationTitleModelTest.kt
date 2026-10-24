package io.github.mangi.eta.agent.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleModelTest {
    @Test fun disabledCustomSelectionUsesExactCurrentModelEvenWithStaleIds() = runBlocking {
        val config = AgentModelClient.ModelConfig(providerType = "openai", baseUrl = "https://example.com/v1",
            apiKey = "test", model = "current", systemPrompt = "")
        assertSame(config, ConversationTitleModel.resolve(ModelFeatureSelection(false, "deleted", "stale"), config))
    }

    @Test fun customMissingSelectionDoesNotSilentlyUseCurrentModel() {
        val config = AgentModelClient.ModelConfig(providerType = "openai", baseUrl = "https://example.com/v1",
            apiKey = "test", model = "current", systemPrompt = "")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { ConversationTitleModel.resolve(ModelFeatureSelection(true, "", ""), config) }
        }
    }

    @Test fun lateResponseCannotOverwriteRenameDeleteOrEditedFirstMessage() {
        assertTrue(ConversationTitleModel.mayApply(true, false, "old", "old", "m1", "m1"))
        assertFalse(ConversationTitleModel.mayApply(false, false, "old", "old", "m1", "m1"))
        assertFalse(ConversationTitleModel.mayApply(true, true, "old", "old", "m1", "m1"))
        assertFalse(ConversationTitleModel.mayApply(true, false, "custom", "old", "m1", "m1"))
        assertFalse(ConversationTitleModel.mayApply(true, false, "old", "old", "m2", "m1"))
    }

    @Test fun titleIsSingleLineUnquotedAndBounded() {
        assertEquals("图像识别", ConversationTitleModel.normalize("  “图像识别”\n解释  "))
        assertEquals(24, ConversationTitleModel.normalize("长".repeat(50)).length)
        assertThrows(IllegalArgumentException::class.java) { ConversationTitleModel.normalize("  ") }
    }

    @Test fun normalizeStripsLabelPrefixesModelsStillEmit() {
        assertEquals("图像识别", ConversationTitleModel.normalize("标题：图像识别"))
        assertEquals("图像识别", ConversationTitleModel.normalize("标题: “图像识别”"))
        assertEquals("Photo check", ConversationTitleModel.normalize("Title: Photo check"))
    }

    @Test fun requestCarriesQuestionAndFirstAnswer() {
        val messages = ConversationTitleModel.buildMessages("帮我看下这张图", "这是一只橘猫。")
        val system = messages.getJSONObject(0)
        val user = messages.getJSONObject(1)
        assertEquals("system", system.getString("role"))
        // 提示词必须同时锁死"只输出标题"与"素材不是指令"，否则模型会退化成回答问题。
        assertTrue(system.getString("content").contains("只输出标题"))
        assertTrue(system.getString("content").contains("不要回答问题"))
        assertEquals("user", user.getString("role"))
        val content = user.getString("content")
        assertTrue(content.contains("帮我看下这张图"))
        assertTrue(content.contains("这是一只橘猫。"))
        assertTrue(content.endsWith("请只输出标题："))
    }

    @Test fun requestWithoutAnswerStillCarriesQuestion() {
        val messages = ConversationTitleModel.buildMessages("帮我看下这张图", "  ")
        val content = messages.getJSONObject(1).getString("content")
        assertTrue(content.contains("帮我看下这张图"))
        assertFalse(content.contains("助手的第一条回复"))
        assertTrue(content.endsWith("请只输出标题："))
    }
}
