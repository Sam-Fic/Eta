package io.github.mangi.eta.ui.components

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import io.github.mangi.eta.R
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
internal fun SubAgentContextStats.cloudContextSummary(context: Context, locale: Locale = Locale.getDefault()): String {
    val usage = cloudContextUsage()
    return if (usage.contextTokens == null) context.getString(R.string.act_context_usage_unknown)
    else formatContextUsage(usage, noLimitText = context.getString(R.string.act_context_no_limit), unknownText = context.getString(R.string.amp_unknown), locale = locale)
}

private fun SubAgentContextStats.roleLabel(context: Context): String = when (role) {
    "implementation" -> context.getString(R.string.act_role_implementation)
    "review" -> context.getString(R.string.act_role_review)
    "summary" -> context.getString(R.string.act_role_summary)
    "image_generation" -> context.getString(R.string.act_role_image)
    "video_generation" -> context.getString(R.string.act_role_video)
    else -> context.getString(R.string.act_role_research)
}

internal fun SubAgentContextStats.contextLabel(context: Context): String =
    "$modelName（${agentName.ifBlank { roleLabel(context) }} ${taskId.take(6)}）"

/** 上下文统计行的目标标识：不带模型名，只用代理/角色与短任务 ID 区分。 */
internal fun SubAgentContextStats.contextTargetLabel(context: Context): String =
    "${agentName.ifBlank { roleLabel(context) }} ${taskId.take(6)}"

internal fun SubAgentContextStats.contextStatusLabel(context: Context): String = when {
    // Confirmed lifecycle state wins over a stale compaction/request flag.
    status == "awaiting_decision" || status == "paused" -> context.getString(R.string.act_status_paused)
    status == "pausing" -> context.getString(R.string.act_status_pausing)
    status == "completed" -> context.getString(R.string.act_status_completed)
    status == "timed_out" -> context.getString(R.string.act_status_timed_out)
    status == "cancelled" -> context.getString(R.string.act_status_cancelled)
    status == "failed" -> context.getString(R.string.act_status_failed)
    status == "queued" -> context.getString(R.string.act_status_queued)
    manualCompactionState == "pending" -> context.getString(R.string.act_status_waiting_compaction)
    isCompacting -> context.getString(R.string.act_status_compacting)
    status == "running" -> context.getString(R.string.act_status_running)
    else -> context.getString(R.string.act_status_unknown)
}
