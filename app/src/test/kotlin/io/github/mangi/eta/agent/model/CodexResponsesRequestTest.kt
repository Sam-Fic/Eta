package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexResponsesRequestTest {
    @Test
    fun codexUsesFlatToolsAndStatelessResponsesHistory() {
        val messages = JSONArray("""[
          {"role":"assistant","tool_calls":[{"id":"call_a","type":"function",
            "function":{"name":"read_file","arguments":"{}"}}]},
          {"role":"tool","tool_call_id":"call_a","content":"fixture"}
        ]""")
        val request = OpenAiResponsesProvider.buildRequestJson(config(), messages, tools())
        val tool = request.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("read_file", tool.getString("name"))
        val input = request.getJSONArray("input")
        assertEquals("read_file", input.getJSONObject(0).getString("name"))
        assertFalse(input.getJSONObject(0).has("namespace"))
        assertEquals("call_a", input.getJSONObject(1).getString("call_id"))
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getBoolean("stream"))
        assertTrue(request.getBoolean("parallel_tool_calls"))
        assertEquals("reasoning.encrypted_content", request.getJSONArray("include").getString(0))
    }

    @Test
    fun codexRequestEncodesAdvertisedMaxForModelsBeyondTheInitialPresets() {
        for (model in listOf("gpt-6.1-sol", "remote-model-alias")) {
            val request = OpenAiResponsesProvider.buildRequestJson(config().copy(
                model = model,
                reasoningEffort = ReasoningEffort.MAX,
                reasoningCapabilities = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.MAX)),
            ), JSONArray(), tools())
            assertEquals("max", request.getJSONObject("reasoning").getString("effort"))
            assertTrue(request.getBoolean("stream"))
        }
    }

    @Test
    fun codexRejectsUnsupportedOverridesAndLeavesApiKeyBehaviorIntact() {
        listOf("temperature", "top_p", "max_output_tokens", "max_tokens", "background", "conversation").forEach { field ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                OpenAiResponsesProvider.buildRequestJson(
                    config().copy(customBody = listOf(CustomBody(field, JsonPrimitive(1)))), JSONArray(), tools(),
                )
            }
            assertTrue(failure.message.orEmpty().contains(field))
        }
        val apiRequest = OpenAiResponsesProvider.buildRequestJson(
            config().copy(authMode = ProviderAuthModes.API_KEY, extraBodyJson = """{"temperature":0.2}"""),
            JSONArray(), tools(),
        )
        assertEquals(0.2, apiRequest.getDouble("temperature"), 0.0)
    }

    private fun config() = AgentModelClient.ModelConfig(
        providerId = "account", baseUrl = "https://chatgpt.com/backend-api/codex", apiKey = "",
        model = "fixture", systemPrompt = "fixture", authMode = ProviderAuthModes.OPENAI_CHATGPT,
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
    )

    private fun tools() = JSONArray("""[{"type":"function","function":{
      "name":"read_file","description":"读取文件","parameters":{"type":"object","properties":{}}
    }}]""")
}
