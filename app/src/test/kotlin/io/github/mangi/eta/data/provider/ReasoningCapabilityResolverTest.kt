package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningCapabilityResolverTest {
    private val allSelectable = listOf(
        ReasoningEffort.OFF,
        ReasoningEffort.MINIMAL,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
        ReasoningEffort.MAX,
    )
    private val allTiers = allSelectable.filter { it != ReasoningEffort.OFF }

    @Test
    fun userFacingEffortLabelsAreLocalizedStringResources() {
        // 档位展示名走字符串资源（R.string.effort_*），按当前语言解析；
        // 这里只校验映射完备且互不相同，具体文案由资源与翻译对比保障。
        val labelResIds = ReasoningEffort.entries.map { it.labelRes }
        assertTrue("每个档位都必须映射到字符串资源", labelResIds.all { it != 0 })
        assertEquals("档位标签资源必须互不相同", labelResIds.size, labelResIds.distinct().size)
    }

    @Test
    fun reasoningModelsExposeEveryThinkingLevel() {
        assertEquals(allSelectable, resolve(ProviderSourceTypes.DEEPSEEK, "deepseek-v4-flash").selectableEfforts)
        assertEquals(allSelectable, resolve(ProviderSourceTypes.DEEPSEEK, "deepseek-v4-pro").selectableEfforts)
        assertEquals(allSelectable, resolve(ProviderSourceTypes.OPENAI, "openai/gpt-5.6-sol").selectableEfforts)
        assertEquals(allSelectable, resolve(ProviderSourceTypes.MIMO, "mimo-v2.5").selectableEfforts)
        assertEquals(allSelectable, resolve(ProviderSourceTypes.STEPFUN, "step-3.7-flash").selectableEfforts)
        assertEquals(allSelectable, resolve(ProviderSourceTypes.CUSTOM, "unknown-thinking-model").selectableEfforts)
    }

    @Test
    fun mandatoryKimiStillExposesEveryLevelWithoutOff() {
        assertEquals(allTiers, resolve(ProviderSourceTypes.MOONSHOT, "kimi-k3").selectableEfforts)
    }

    @Test
    fun declaredSparseTiersStillCheckEveryLevel() {
        val resolved = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.CUSTOM,
            model = Model(
                id = "glm",
                modelId = "glm-5.2",
                displayName = "GLM",
                reasoning = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.HIGH, ReasoningEffort.MAX),
                    mandatory = true,
                ),
            ),
        )
        assertEquals(allTiers, resolved?.selectableEfforts)
    }

    @Test
    fun userOverrideWinsOverRemoteMetadata() {
        val overridden = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.MINIMAL),
            canDisable = true,
        )
        val resolved = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.DEEPSEEK,
            model = Model(
                id = "id",
                modelId = "deepseek-v4-flash",
                displayName = "DeepSeek",
                reasoning = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.HIGH),
                ),
                reasoningOverride = true,
                reasoningCapabilitiesOverride = overridden,
            ),
        )
        assertEquals(allSelectable, resolved?.selectableEfforts)
        assertEquals(true, resolved?.canDisable)
    }

    @Test
    fun responsesRelayInfersOnlyExactOfficialModelUnlessExplicitlyDisabled() {
        val inferred = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.OPENAI,
            model = Model(id = "luna", modelId = "gpt-5.6-luna", displayName = "Luna"),
            inferExactCatalogModel = true,
        )
        val unknown = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.OPENAI,
            model = Model(id = "unknown", modelId = "relay-thinking", displayName = "Unknown"),
            inferExactCatalogModel = true,
        )
        val disabled = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.OPENAI,
            model = Model(
                id = "disabled",
                modelId = "gpt-5.6-luna",
                displayName = "Disabled",
                reasoning = false,
            ),
            inferExactCatalogModel = true,
        )
        assertEquals(ReasoningEffort.MEDIUM, inferred?.defaultEffort)
        assertNull(unknown)
        assertNull(disabled)
    }

    @Test
    fun grok46UsesCatalogLevelsEvenWithoutRemoteReasoningFlag() {
        val grok = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.CUSTOM,
            model = Model(
                id = "grok",
                modelId = "grok-4.6",
                displayName = "grok-4.6",
            ),
        )
        assertEquals(allSelectable, grok?.selectableEfforts)
    }

    private fun resolve(source: String, modelId: String): ModelReasoningCapabilities =
        requireNotNull(
            ReasoningCapabilityResolver.resolve(
                sourceType = source,
                model = Model(
                    id = "id-$modelId",
                    modelId = modelId,
                    displayName = modelId,
                    reasoning = true,
                ),
            )
        )
}