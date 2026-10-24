package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GptServiceTierTest {
    private fun config(mode: GptSpeedMode? = null) = AgentModelClient.ModelConfig(
        baseUrl = "https://relay.example.invalid/v1", apiKey = "", model = "gpt-99-relay",
        systemPrompt = "", gptSpeedMode = mode,
    )

    private fun build(config: AgentModelClient.ModelConfig, responses: Boolean): JSONObject =
        if (responses) ResponsesRequestBuilder.build(
            config.copy(openAiEndpointMode = OpenAiEndpointMode.RESPONSES), JSONArray(), JSONArray(),
        ) else OpenAiChatCompletionsProvider.buildRequestJson(config, JSONArray(), JSONArray())

    @Test fun modelConfigJsonRoundTripsModesAndOldJsonDefaultsToNull() {
        GptSpeedMode.entries.forEach { mode ->
            val original = config(mode)
            val encoded = Json.encodeToString(original)
            assertTrue(encoded.contains("\"gptSpeedMode\":\"${mode.name}\""))
            assertEquals(original, Json.decodeFromString<AgentModelClient.ModelConfig>(encoded))
        }
        val legacy = Json.encodeToString(config())
        assertFalse(legacy.contains("gptSpeedMode"))
        assertNull(Json.decodeFromString<AgentModelClient.ModelConfig>(legacy).gptSpeedMode)
        val explicitNull = JSONObject(legacy).put("gptSpeedMode", JSONObject.NULL).toString()
        assertNull(Json.decodeFromString<AgentModelClient.ModelConfig>(explicitNull).gptSpeedMode)
    }

    @Test fun bothProtocolsOverrideCustomTiersOnlyWhenModeIsExplicit() {
        val tiers = mapOf(GptSpeedMode.NORMAL to "default", GptSpeedMode.FAST to "fast",
            GptSpeedMode.ULTRA_FAST to "ultrafast")
        listOf(false, true).forEach { responses ->
            tiers.forEach { (mode, tier) ->
                val request = build(config(mode).copy(
                    extraBodyJson = "{\"service_tier\":\"auto\"}",
                    customBody = listOf(CustomBody("service_tier", JsonPrimitive("priority"))),
                ), responses)
                assertEquals(tier, request.getString("service_tier"))
                assertEquals("gpt-99-relay", request.getString("model"))
            }
            assertFalse(build(config(), responses).has("service_tier"))
            assertEquals("priority", build(config().copy(
                customBody = listOf(CustomBody("service_tier", JsonPrimitive("priority"))),
            ), responses).getString("service_tier"))
        }
    }

    @Test fun codexUsesOfficialFastWireValueAndOmitsNormalAfterCustomBodyMerge() {
        // Codex 判定现在看认证方式（订阅连接），不再看 baseUrl 是否像官方域名。
        for (baseUrl in listOf("https://chatgpt.com/backend-api/codex", "https://auth.openai.com")) {
            for (responses in listOf(false, true)) {
                val original = config(GptSpeedMode.FAST).copy(
                    baseUrl = baseUrl,
                    authMode = ProviderAuthModes.OPENAI_CHATGPT,
                    extraBodyJson = "{\"service_tier\":\"ultrafast\"}",
                    customBody = listOf(CustomBody("service_tier", JsonPrimitive("fast"))),
                )
                val fast = build(original, responses)
                assertEquals("priority", fast.getString("service_tier"))
                assertFalse(build(original.copy(gptSpeedMode = GptSpeedMode.NORMAL), responses)
                    .has("service_tier"))
                assertEquals(GptSpeedMode.FAST, original.gptSpeedMode)
                // No explicit speed selection preserves the existing manual-body contract.
                assertEquals("fast", build(original.copy(gptSpeedMode = null), responses)
                    .getString("service_tier"))
            }
        }
    }

    @Test fun codexUltraFastIsRejectedBeforeSendingRatherThanSilentlyDowngraded() {
        for (responses in listOf(false, true)) {
            val failure = runCatching {
                build(config(GptSpeedMode.ULTRA_FAST).copy(
                    baseUrl = "https://chatgpt.com/backend-api/codex",
                    authMode = ProviderAuthModes.OPENAI_CHATGPT,
                ), responses)
            }.exceptionOrNull()
            assertTrue(failure is AgentModelFailure)
            assertTrue(failure?.message.orEmpty().contains("ULTRA_FAST"))
            assertEquals(GptServiceTier.UNSUPPORTED_CODEX_TIER, (failure as AgentModelFailure).code)
        }
    }

    @Test fun codexMappingDoesNotChangeModelOrReasoningOrMutateRejectedRequest() {
        for (mode in GptSpeedMode.entries) {
            val request = JSONObject("{\"model\":\"gpt-5\",\"reasoning\":{\"effort\":\"high\"},\"reasoning_effort\":\"high\"}")
            val before = request.toString()
            val outcome = runCatching {
                GptServiceTier.apply(
                    request,
                    config(mode).copy(
                        baseUrl = "https://chatgpt.com/backend-api/codex",
                        authMode = ProviderAuthModes.OPENAI_CHATGPT,
                    ),
                )
            }
            if (mode == GptSpeedMode.ULTRA_FAST) {
                assertTrue(outcome.exceptionOrNull() is AgentModelFailure)
                assertEquals(before, request.toString())
            } else {
                outcome.getOrThrow()
            }
            assertEquals("gpt-5", request.getString("model"))
            assertEquals("high", request.getJSONObject("reasoning").getString("effort"))
            assertEquals("high", request.getString("reasoning_effort"))
        }
    }

    @Test fun relayHostContainingCodexDoesNotReceiveOfficialMapping() {
        // 判定依据是认证方式：API Key 走中转时，即使 baseUrl 长成官方 Codex 样子也不套官方档位。
        for (baseUrl in listOf("https://chatgpt.com.relay.example.invalid/backend-api/codex",
            "https://relay.example.invalid/backend-api/codex")) {
            assertEquals("fast", build(config(GptSpeedMode.FAST).copy(baseUrl = baseUrl), true)
                .getString("service_tier"))
        }
    }

    @Test fun nonGptModelsNeverGetAnInjectedTier() {
        listOf(false, true).forEach { responses ->
            listOf("o3", "codex", "foo-gpt-5", "claude-sonnet", "deepseek-chat").forEach { model ->
                assertFalse(model, build(config(GptSpeedMode.FAST).copy(model = model), responses)
                    .has("service_tier"))
            }
        }
        val request = JSONObject().put("model", "gpt-5")
        GptServiceTier.apply(request, config(GptSpeedMode.FAST).copy(providerType = ProviderTypes.ANTHROPIC))
        assertEquals("fast", request.getString("service_tier"))
    }

    @Test fun finalActualModelRatherThanConfiguredLabelControlsInjection() {
        val toNonGpt = config(GptSpeedMode.FAST).copy(
            customBody = listOf(CustomBody("model", JsonPrimitive("o3"))),
        )
        assertFalse(build(toNonGpt, false).has("service_tier"))
        // Responses protects its model field from custom-body overrides.
        assertEquals("fast", build(toNonGpt, true).getString("service_tier"))
        val toGpt = config(GptSpeedMode.ULTRA_FAST).copy(model = "o3",
            customBody = listOf(CustomBody("model", JsonPrimitive("OpenAI/GPT-99-relay"))),
        )
        assertEquals("ultrafast", build(toGpt, false).getString("service_tier"))
        assertFalse(build(toGpt, true).has("service_tier"))
    }

    @Test fun endpointDoesNotRestrictGptTier() {
        val request = JSONObject().put("model", "gpt-6-astra")
        GptServiceTier.apply(request, config(GptSpeedMode.FAST).copy(openAiEndpointMode = "unknown"))
        assertEquals("fast", request.getString("service_tier"))
    }

    @Test fun helperChangesOnlyServiceTierAndDoesNotMutateSnapshots() {
        val original = config(GptSpeedMode.NORMAL)
        val request = JSONObject("{\"model\":\"gpt-5\",\"reasoning\":{\"effort\":\"high\"},\"reasoning_effort\":\"high\"}")
        GptServiceTier.apply(request, original)
        val sent = request.toString()
        val next = original.copy(gptSpeedMode = original.gptSpeedMode!!.next())
        assertEquals(GptSpeedMode.NORMAL, original.gptSpeedMode)
        assertEquals(sent, request.toString())
        assertEquals("fast", build(next, false).getString("service_tier"))
        assertEquals("gpt-5", request.getString("model"))
        assertEquals("high", request.getJSONObject("reasoning").getString("effort"))
        assertEquals("high", request.getString("reasoning_effort"))
        assertEquals(4, request.length())
    }
}
