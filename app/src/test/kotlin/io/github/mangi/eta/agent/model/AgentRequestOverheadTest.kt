package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRequestOverheadTest {
    @Test fun protocolPreviewCountsHostedToolsButLeavesSilentOverheadUnchanged() {
        val c = modelConfig(false, false).copy(openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
        var withoutHosted = 0
        var withHosted = 0
        val silent = AgentRequestOverhead.estimate(c, onProtocolPreview = { withoutHosted = it })
        val silentWithHosted = AgentRequestOverhead.estimate(c.copy(hostedWebSearchEnabled = true),
            onProtocolPreview = { withHosted = it })
        // 开启托管搜索会多出一条系统提示（web_search 由服务端执行），静默开销随之变大；
        // 这里钉住的是「协议预览单独统计，且放大的那部分确实来自托管工具」。
        assertTrue(silentWithHosted > silent)
        assertTrue(withoutHosted > 0)
        assertTrue(withHosted > withoutHosted)
    }

    @Test fun anthropicPreviewUsesFinalCustomSystemAndToolsNotUnsentDefaults() {
        val c = modelConfig(false, false).copy(
            providerType = io.github.mangi.eta.data.model.ProviderTypes.ANTHROPIC,
            customBody = listOf(
                io.github.mangi.eta.data.model.CustomBody("system", kotlinx.serialization.json.JsonPrimitive("replacement")),
                io.github.mangi.eta.data.model.CustomBody("tools", kotlinx.serialization.json.JsonArray(emptyList())),
            ))
        var preview = 0
        val silent = AgentRequestOverhead.estimate(c, onProtocolPreview = { preview = it })
        org.junit.Assert.assertEquals(AgentContextBudget.countTokens("replacement"), preview)
        org.junit.Assert.assertEquals(AgentRequestOverhead.estimate(c), silent)
        assertTrue(silent > preview)
    }

    @Test
    fun estimateIncludesSystemPromptAndTools() {
        val tokens = AgentRequestOverhead.estimate(
            config = modelConfig(true, true),
            capabilities = AgentToolCapabilities(rootAvailable = false),
        )
        assertTrue(tokens > 1_000)
    }

    @Test
    fun estimateGrowsWhenSkillsAndExtraToolsAreAdded() {
        val caps = AgentToolCapabilities(rootAvailable = false)
        val config = modelConfig(false, false)
        val base = AgentRequestOverhead.estimate(config = config, capabilities = caps)
        val skill = SkillIndexEntry(
            id = "demo-skill",
            name = "Demo",
            description = "A fairly long skill description used to inflate the index payload.",
            rootPath = "/skills/demo",
            skillFilePath = "/skills/demo/SKILL.md",
            hasScripts = true,
            hasReferences = true,
            hasAssets = false,
            hasEvals = false,
        )
        val withSkill = AgentRequestOverhead.estimate(
            config = config,
            skillContext = SkillContext(installedSkills = listOf(skill)),
            capabilities = caps,
        )
        val extra = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject()
                    .put("name", "mcp_demo_tool")
                    .put("description", "An extra MCP tool schema")
                    .put("parameters", JSONObject().put("type", "object")),
            )
        )
        val withTools = AgentRequestOverhead.estimate(
            config = config,
            capabilities = caps,
            additionalTools = extra,
        )
        assertTrue(withSkill > base)
        assertTrue(withTools > base)
    }

    @Test
    fun estimateIncludesMemoryCore() {
        val caps = AgentToolCapabilities(rootAvailable = false)
        val config = modelConfig(false, false)
        val withoutMemory = AgentRequestOverhead.estimate(
            config = config,
            memoryContext = AgentMemoryContext.DISABLED,
            capabilities = caps,
        )
        val withMemory = AgentRequestOverhead.estimate(
            config = config,
            memoryContext = AgentMemoryContext(
                enabled = true,
                revision = "abc",
                byteSize = 120,
                coreContent = "# core\n- demo",
                coreTruncated = false,
                headingIndex = "# core",
                coreBudgetChars = 4_000,
            ),
            capabilities = caps,
        )
        assertTrue(withMemory > withoutMemory)
    }

    private fun modelConfig(
        terminalTools: Boolean,
        browserTools: Boolean,
    ): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "You are a test assistant.",
            terminalTools = terminalTools,
            browserTools = browserTools,
        )
}
