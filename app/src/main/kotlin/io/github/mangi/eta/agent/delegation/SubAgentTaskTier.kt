package io.github.mangi.eta.agent.delegation

import androidx.annotation.StringRes
import io.github.mangi.eta.R

/**
 * User-assigned responsibility, not a benchmark or an inferred model capability.
 *
 * 协议值（[wireValue]）落库并随委派消息传递；展示名走 [labelRes] 字符串资源，
 * 由 UI 层按当前语言解析（枚举本身不携带任何语言的硬编码文案）。
 */
internal enum class SubAgentTaskTier(
    val wireValue: String,
    @StringRes val labelRes: Int,
    val routingHint: String,
) {
    SIMPLE("simple", R.string.satt_simple, "search, organization, mechanical changes with explicit instructions"),
    REGULAR("regular", R.string.satt_regular, "localized fixes and well-scoped feature implementation"),
    COMPLEX("complex", R.string.satt_complex, "cross-module changes, architecture, difficult debugging and edge cases");

    companion object {
        fun fromWireValue(value: String): SubAgentTaskTier? = entries.firstOrNull { it.wireValue == value }
    }
}
