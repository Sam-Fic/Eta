package io.github.mangi.eta.agent.overlay

import android.content.res.Resources
import android.icu.text.ListFormatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.ToolPresentation

/** 浮窗只保存语义状态，文案在渲染时根据当前系统语言解析。 */
internal sealed interface AgentOverlayStatus {
    data object Preparing : AgentOverlayStatus
    data object Received : AgentOverlayStatus
    data class PreparingTools(val count: Int) : AgentOverlayStatus
    data class ReasoningRound(val round: Int) : AgentOverlayStatus
    data object RequestingModel : AgentOverlayStatus
    data object ModelResponded : AgentOverlayStatus
    data object GeneratingToolArguments : AgentOverlayStatus
    data object Reasoning : AgentOverlayStatus
    data object PreparingAnswer : AgentOverlayStatus
    data class PlanningTools(val names: List<String>) : AgentOverlayStatus
    data object SupplementReceived : AgentOverlayStatus
    data class RunningTool(val name: String) : AgentOverlayStatus
    data class ToolCompleted(val name: String) : AgentOverlayStatus
    data class HostedToolRunning(val name: String) : AgentOverlayStatus
    data class HostedToolFinished(val name: String, val success: Boolean) : AgentOverlayStatus
    data class ImagesRead(val count: Int) : AgentOverlayStatus
    data object ResultReady : AgentOverlayStatus
    data object RunFailed : AgentOverlayStatus
    data object GeneratingAnswer : AgentOverlayStatus
    data object Stopping : AgentOverlayStatus
    data object WaitingForAnswer : AgentOverlayStatus
    data object Paused : AgentOverlayStatus
    data object Continuing : AgentOverlayStatus
    data object Finishing : AgentOverlayStatus
    data object ContinuationUnavailable : AgentOverlayStatus
    data object Stopped : AgentOverlayStatus
}

@Composable
internal fun AgentOverlayStatus.localizedText(): String = localizedText(LocalResources.current)

/** 通知等非 Compose 表面与浮层共用同一套状态文案。 */
internal fun AgentOverlayStatus.localizedText(resources: Resources): String = when (this) {
    AgentOverlayStatus.Preparing -> resources.getString(R.string.overlay_preparing)
    AgentOverlayStatus.Received -> resources.getString(R.string.overlay_received)
    is AgentOverlayStatus.PreparingTools -> resources.getQuantityString(R.plurals.overlay_preparing_tools, count, count)
    is AgentOverlayStatus.ReasoningRound -> resources.getString(R.string.overlay_reasoning_round, round)
    AgentOverlayStatus.RequestingModel -> resources.getString(R.string.overlay_requesting_model)
    AgentOverlayStatus.ModelResponded -> resources.getString(R.string.overlay_model_responded)
    AgentOverlayStatus.GeneratingToolArguments -> resources.getString(R.string.overlay_generating_tool_arguments)
    AgentOverlayStatus.Reasoning -> resources.getString(R.string.overlay_reasoning)
    AgentOverlayStatus.PreparingAnswer -> resources.getString(R.string.overlay_preparing_answer)
    is AgentOverlayStatus.PlanningTools -> resources.getString(
        R.string.overlay_planning_tools,
        ListFormatter.getInstance(resources.configuration.locales[0]).format(names.map { toolDisplayName(resources, it) }),
    )
    AgentOverlayStatus.SupplementReceived -> resources.getString(R.string.overlay_supplement_received)
    is AgentOverlayStatus.RunningTool -> resources.getString(R.string.overlay_running_tool, toolDisplayName(resources, name))
    is AgentOverlayStatus.ToolCompleted -> resources.getString(R.string.overlay_tool_completed, toolDisplayName(resources, name))
    is AgentOverlayStatus.HostedToolRunning -> resources.getString(R.string.overlay_hosted_tool_running, name)
    is AgentOverlayStatus.HostedToolFinished -> resources.getString(
        if (success) R.string.overlay_hosted_tool_completed else R.string.overlay_hosted_tool_failed,
        name,
    )
    is AgentOverlayStatus.ImagesRead -> resources.getQuantityString(R.plurals.overlay_images_read, count, count)
    AgentOverlayStatus.ResultReady -> resources.getString(R.string.overlay_result_ready)
    AgentOverlayStatus.RunFailed -> resources.getString(R.string.overlay_run_failed)
    AgentOverlayStatus.GeneratingAnswer -> resources.getString(R.string.overlay_generating_answer)
    AgentOverlayStatus.Stopping -> resources.getString(R.string.overlay_stopping)
    AgentOverlayStatus.WaitingForAnswer -> resources.getString(R.string.question_waiting)
    AgentOverlayStatus.Paused -> resources.getString(R.string.overlay_paused)
    AgentOverlayStatus.Continuing -> resources.getString(R.string.overlay_continuing)
    AgentOverlayStatus.Finishing -> resources.getString(R.string.overlay_finishing)
    AgentOverlayStatus.ContinuationUnavailable -> resources.getString(R.string.overlay_continuation_unavailable)
    AgentOverlayStatus.Stopped -> resources.getString(R.string.overlay_stopped)
}

@Composable
internal fun toolDisplayName(name: String): String = toolDisplayName(LocalResources.current, name)

/**
 * 工具显示名：查 [ToolPresentation]（图标与名称的单一数据源）。
 * 浮窗与聊天界面共用同一张表，新增工具只改 ToolPresentation 一处。
 */
internal fun toolDisplayName(resources: Resources, name: String): String =
    ToolPresentation.displayName(resources, name)
