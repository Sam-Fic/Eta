package io.github.mangi.eta.ui.components

import androidx.compose.runtime.compositionLocalOf
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.formatContextUsage
import java.util.Locale

internal data class AgentContextTelemetry(
    val children: List<SubAgentContextStats> = emptyList(),
    val mainModelName: String = "",
    val compactingModelName: String = "",
    val selectedTaskId: String? = null,
    val onTaskSelected: ((String?) -> Unit)? = null,
)
internal val LocalAgentContextTelemetry = compositionLocalOf { AgentContextTelemetry() }

/** Legacy telemetry can still be replayed after upgrading. Its projection and
 * compaction estimates must never be presented as cloud-measured occupancy. */
internal fun SubAgentContextStats.cloudContextUsage(): AgentContextUsageUi = AgentContextUsageUi(
    contextTokens = contextTokens?.takeIf { !projected && it > 0 },
    contextWindow = contextWindow,
)

/** A missing child receipt is unknown, not the legacy formatter's 0K/0% placeholder. */
internal fun SubAgentContextStats.cloudContextSummary(locale: Locale = Locale.getDefault()): String {
    val usage = cloudContextUsage()
    return if (usage.contextTokens == null) "上下文用量未知，等待云端统计"
    else formatContextUsage(usage, noLimitText = "当前模型未提供上下文上限", locale = locale)
}

internal fun SubAgentContextStats.contextLabel(): String {
    val roleLabel = when (role) {
        "implementation" -> "实现"
        "review" -> "审查"
        "summary" -> "总结"
        "image_generation" -> "图片生成"
        "video_generation" -> "视频生成"
        else -> "研究"
    }
    return "$modelName（${agentName.ifBlank { roleLabel }} ${taskId.take(6)}）"
}

internal fun SubAgentContextStats.contextStatusLabel(): String = when {
    // Confirmed lifecycle state wins over a stale compaction/request flag.
    status == "awaiting_decision" || status == "paused" -> "已暂停"
    status == "pausing" -> "正在暂停"
    status == "completed" -> "已完成"
    status == "timed_out" -> "已超时"
    status == "cancelled" -> "已取消"
    status == "failed" -> "失败"
    status == "queued" -> "排队中"
    manualCompactionState == "pending" -> "等待压缩"
    isCompacting -> "正在压缩"
    status == "running" -> "执行中"
    else -> "状态未知"
}
