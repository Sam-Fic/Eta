package io.github.mangi.eta.ui.adaptive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 运行状态语义档位（L1 设计令牌）。
 *
 * Material 3 Expressive 的 colorScheme 没有 success / running 语义角色，各处自行
 * `when` 映射就会漂移（ChatMessageItem 的工具状态、AgentStatusCard 的运行状态本已
 * 各写一套）。到官方角色的映射规则只此一处：Running / Success → `primary`，
 * Failed → `error`，Unknown → `onSurfaceVariant`。
 *
 * 强调度的降档（alpha）属于排版选择，不属于语义，留给调用方自行处理。
 */
enum class EtaStatus {
    Running,
    Success,
    Failed,
    Unknown,
}

/** 状态前景色：图标、小字等前景元素使用。 */
@Composable
fun EtaStatus.foregroundColor(): Color = when (this) {
    EtaStatus.Running -> MaterialTheme.colorScheme.primary
    EtaStatus.Success -> MaterialTheme.colorScheme.primary
    EtaStatus.Failed -> MaterialTheme.colorScheme.error
    EtaStatus.Unknown -> etaOnSurfaceVariant()
}
