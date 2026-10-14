package io.github.mangi.eta.ui.components

import android.content.Context
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN")
class AgentOwnerContextTelemetryTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun stats(status: String = "running", tokens: Int? = null) = SubAgentContextStats(
        taskId = "abcdef-task", worker = 1, role = "implementation", model = "model",
        modelName = "Model", providerName = "Provider", contextWindow = 100_000,
        contextTokens = tokens, status = status,
    )

    @Test fun pauseRequestAndConfirmedPauseHaveDifferentChineseLabels() {
        assertEquals("正在暂停", stats("pausing").contextStatusLabel(context))
        assertEquals("已暂停", stats("awaiting_decision").contextStatusLabel(context))
        assertEquals("已暂停", stats("paused").contextStatusLabel(context))
        assertEquals("排队中", stats("queued").contextStatusLabel(context))
        assertEquals("执行中", stats("running").contextStatusLabel(context))
    }

    @Test fun lifecycleStateWinsOverStaleCompactionFlags() {
        for ((status, label) in mapOf(
            "awaiting_decision" to "已暂停", "pausing" to "正在暂停", "completed" to "已完成",
            "cancelled" to "已取消", "timed_out" to "已超时", "failed" to "失败", "queued" to "排队中",
        )) {
            assertEquals(label, stats(status).copy(isCompacting = true, manualCompactionState = "pending").contextStatusLabel(context))
        }
        assertEquals("等待压缩", stats().copy(manualCompactionState = "pending").contextStatusLabel(context))
        assertEquals("正在压缩", stats().copy(isCompacting = true).contextStatusLabel(context))
        assertEquals("状态未知", stats("unrecognized").contextStatusLabel(context))
    }

    @Test fun noReceiptOrLegacyProjectionNeverBecomesMeasuredContext() {
        assertNull(stats().cloudContextUsage().contextTokens)
        assertNull(stats(tokens = 0).cloudContextUsage().contextTokens)
        assertNull(stats(tokens = -1).cloudContextUsage().contextTokens)
        assertNull(stats(tokens = 9_999).copy(projected = true).cloudContextUsage().contextTokens)
        assertNull(stats().copy(inputTokens = 100_000, outputTokens = 50_000).cloudContextUsage().contextTokens)
        assertEquals(1_234, stats(tokens = 1_234).cloudContextUsage().contextTokens)
    }

    @Test fun noReceiptShowsWaitingInsteadOfZeroPercentAndMeasuredUsageStillFormats() {
        for (sample in listOf(stats(), stats(tokens = 0), stats(tokens = 9_999).copy(projected = true))) {
            val summary = sample.cloudContextSummary(context, Locale.US)
            assertTrue(summary.contains("未知"))
            assertTrue(summary.contains("等待"))
            assertFalse(summary.contains("0K"))
            assertFalse(summary.contains("%"))
        }
        val measured = stats(tokens = 1_234).cloudContextSummary(context, Locale.US)
        assertTrue(measured.contains("1.23K"))
        assertTrue(measured.contains("1.2%"))
        assertFalse(measured.contains("未知"))
    }

    @Test fun taskLabelsDistinguishMultipleTasksForOneWorkerAndModel() {
        val first = stats().copy(agentName = "Implementer")
        val second = first.copy(taskId = "ghijkl-task")
        assertNotEquals(first.contextLabel(context), second.contextLabel(context))
        assertTrue(first.contextLabel(context).contains("abcdef"))
        assertTrue(second.contextLabel(context).contains("ghijkl"))
    }
}
