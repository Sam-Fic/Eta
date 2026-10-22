package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import org.junit.Assert.*
import org.junit.Test

class RequestOverheadCalibrationTest {
    private fun learn(previous: RequestOverheadCalibration.Sample?, id: String, input: Int = 37214,
        history: Int = 481, overhead: Int = 25270, route: String = "p:model:config") =
        requireNotNull(RequestOverheadCalibration.learn(previous, input, history, overhead,
            requestId = id, routeSignature = route))
    private fun stable(input: Int = 37214): RequestOverheadCalibration.Sample {
        var sample: RequestOverheadCalibration.Sample? = null
        repeat(3) { sample = learn(sample, "request-$it", input) }
        return requireNotNull(sample)
    }

    @Test fun threeDistinctRecentRequestsAreRequiredAndDuplicateDoesNotAdvance() {
        val first = learn(null, "one")
        assertNull(first.estimate(481, 25270))
        assertNull(RequestOverheadCalibration.learn(first, 37214, 481, 25270,
            requestId = "one", routeSignature = first.routeSignature))
        val second = learn(first, "two")
        assertNull(second.ratio)
        val third = learn(second, "three")
        assertEquals(3, third.samples)
        assertEquals(37214, third.estimate(481, 25270))
    }

    @Test fun correctedUsageReplacesItsSampleWithoutAddingOrReorderingRequests() {
        val initial = stable()
        val corrected = learn(initial, "request-1", 26000)
        assertEquals(initial.observations.map { it.requestId }, corrected.observations.map { it.requestId })
        assertEquals(3, corrected.samples)
        assertEquals(26000, corrected.observations[1].cloudInput)
        assertNull(corrected.ratio)
        assertNull(corrected.estimate(481, 25270))
        assertNull(RequestOverheadCalibration.learn(corrected, 26000, 481, 25270,
            requestId = "request-1", routeSignature = corrected.routeSignature))
    }

    @Test fun incompleteCorrectionRevokesTheBasisUntilCompleteEvidenceReturns() {
        val initial = stable()
        val revoked = requireNotNull(RequestOverheadCalibration.invalidateCorrection(initial, "request-1", 26000))
        assertEquals(3, revoked.samples)
        assertFalse(revoked.observations[1].complete)
        assertNull(revoked.ratio)
        assertNull(revoked.estimate(481, 25270))
        val restored = learn(revoked, "request-1")
        assertEquals(initial.observations, restored.observations)
        assertNotNull(restored.ratio)
    }

    @Test fun zeroOverheadCorrectionRevokesSameRoundEvenWhenCloudInputIsUnchanged() {
        for (correctedInput in listOf(37214, 26000)) {
            val initial = stable()
            val corrected = requireNotNull(RequestOverheadCalibration.recordReceipt(initial, correctedInput,
                history = 481, overhead = 0, requestId = "request-1", routeSignature = initial.routeSignature))
            assertEquals(initial.observations.map { it.requestId }, corrected.observations.map { it.requestId })
            assertEquals(3, corrected.samples)
            assertFalse(corrected.observations[1].complete)
            assertNull(corrected.ratio)
            assertNull(corrected.estimate(481, 25270))
            val repaired = requireNotNull(RequestOverheadCalibration.recordReceipt(corrected, 37214,
                history = 481, overhead = 25270, requestId = "request-1", routeSignature = initial.routeSignature))
            assertEquals(initial, repaired)
        }
    }

    @Test fun learnedRatiosNeverReachTheRingEvenWhenAStableSampleExists() {
        for (input in listOf(37214, 19000)) {
            val sample = stable(input)
            // 样本仍作为纯计算辅助存在，但显示只认真实回执：没有 actual 时一律未知。
            assertNotNull(sample.estimate(481, 25270))
            val ui = liveContextUsage(contextDisplayPolicy = ContextDisplayPolicy(firstTurn = true))
            assertNull(ui.contextTokens)
            assertFalse(ui.estimated)
            assertEquals("0k", formatContextUsage(ui))
            // 本地单位仍进入内部静默预算（显示与预算保持分离）。
            assertEquals(25751, compressionContextUsage(emptyList(), "", emptyList(), null,
                localHistoryTokenCount = 481, requestOverheadTokens = 25270).contextTokens)
        }
    }

    @Test fun runtimeInjectionToleranceStillAllowsRealUiBasisButNotLongSummary() {
        val sample = stable()
        assertNotNull(sample.estimate(6, 25237)) // observed runtime history 481 / overhead 25270
        assertNull(sample.estimate(120000, 25237))
        assertNull(sample.estimate(481, 28000))
        assertNull(sample.estimate(481, 0))
    }

    @Test fun latestOutlierAndConfigurationChangeInvalidateStability() {
        val stable = stable()
        assertNull(learn(stable, "outlier", 26000).ratio)
        val otherRoute = learn(stable, "other-route", route = "p:other:config")
        assertEquals(1, otherRoute.samples)
        assertNull(otherRoute.ratio)
        val extreme = requireNotNull(RequestOverheadCalibration.learn(stable, 100000, 481, 25270,
            requestId = "bad", routeSignature = stable.routeSignature))
        assertNull(extreme.ratio)
        assertNull(extreme.estimate(481, 25270))
        assertNull(RequestOverheadCalibration.learn(stable, 37214, 481, 25270, inflatedCache = true,
            requestId = "cache", routeSignature = stable.routeSignature))
    }

    @Test fun routeSignatureIncludesEndpointButNotPasswordOrTimestamps() {
        val model = Model("m", "model", "Model", createdAt = 1)
        val provider = OpenAiCompatibleProviderSetting("p", "Provider", "https://example.org/v1", apiKey = "first", createdAt = 1)
        val signature = RequestOverheadCalibration.routeSignature(provider, model)
        assertTrue(signature.isNotBlank())
        assertEquals(signature, RequestOverheadCalibration.routeSignature(provider.copy(apiKey = "second", createdAt = 100), model.copy(createdAt = 500)))
        assertNotEquals(signature, RequestOverheadCalibration.routeSignature(provider.copy(baseUrl = "https://other.org/v1"), model))
        assertNotEquals(signature, RequestOverheadCalibration.routeSignature(provider.copy(endpointMode = "responses"), model))
    }

    @Test fun noneAndUnknownIgnoreDraftAndSamplesWhileActualStaysExact() {
        for (first in listOf(true, false)) {
            val policy = ContextDisplayPolicy(firstTurn = first)
            val idle = liveContextUsage(contextDisplayPolicy = policy)
            val typing = liveContextUsage(contextDisplayPolicy = policy)
            assertEquals(idle, typing)
            assertNull(idle.contextTokens)
            assertNull(idle.progress)
            assertEquals(if (first) "0k" else "Unknown", formatContextUsage(idle))
        }
        // 稳定样本也不能覆盖真实回执：actual 优先且不标 estimated。
        val actual = liveContextUsage(billedContextTokens = 12345)
        assertEquals(12345, actual.contextTokens)
        assertFalse(actual.estimated)
    }

    @Test fun pendingFirstTurnPolicyIgnoresVisibleMessagesAndNeverUsesSamples() {
        val pending = AgentChatUiState(messages = listOf(UserMessageUi("u", "pending")),
            history = listOf(io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage("user", "pending")),
            input = "", isStreaming = true, thinkingEnabled = false)
        val policy = contextDisplayPolicy(pending)
        assertTrue(policy.firstTurn)
        assertFalse(policy.awaitingReceipt)
        val none = liveContextUsage(contextDisplayPolicy = policy)
        assertEquals("0k", formatContextUsage(none))
        assertNull(none.contextTokens)
        assertNull(none.progress)
        assertTrue(contextDisplayPolicy(pending.copy(contextAwaitingReceipt = true)).awaitingReceipt)
        val compacted = liveContextUsage(
            contextDisplayPolicy = contextDisplayPolicy(pending.copy(contextAwaitingReceipt = true)))
        assertEquals("Unknown", formatContextUsage(compacted))
        assertNull(compacted.contextTokens)
    }

    @Test fun awaitingReceiptAndFreshActualDoNotConsultStoredSamples() {
        val compacted = liveContextUsage(
            contextDisplayPolicy = ContextDisplayPolicy(awaitingReceipt = true))
        assertNull(compacted.contextTokens)
        assertEquals("Unknown", formatContextUsage(compacted))
        val fresh = liveContextUsage(billedContextTokens = 27723, contextDisplayPolicy = ContextDisplayPolicy())
        assertEquals(27723, fresh.contextTokens)
        assertFalse(fresh.estimated)
    }

    @Test fun latestActualStaysExactWhileReceiptAndSampleHelpersAreNotDisplayed() {
        // 纯辅助仍能算出 receipt 估计，但它不再进入显示。
        val estimate = RequestOverheadCalibration.receiptEstimate(37214, 481, 25270, 300, 25237)
        assertNotNull(estimate)
        val actual = liveContextUsage(
            billedContextTokens = 37214, contextDisplayPolicy = ContextDisplayPolicy(firstTurn = false))
        assertEquals(37214, actual.contextTokens)
        assertFalse(actual.estimated)
        // 本地 composition 不影响 positive receipt 是否算 actual。
        val unpaired = liveContextUsage(billedContextTokens = 43687, contextDisplayPolicy = ContextDisplayPolicy())
        assertEquals(43687, unpaired.contextTokens)
        assertFalse(unpaired.estimated)
        // 没有 actual 时不再回退任何旧估计：一律未知。
        val withoutActual = liveContextUsage(contextDisplayPolicy = ContextDisplayPolicy(firstTurn = false))
        assertNull(withoutActual.contextTokens)
        assertEquals("Unknown", formatContextUsage(withoutActual))
    }
}
