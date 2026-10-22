package io.github.mangi.eta.agent.model

import androidx.annotation.StringRes
import io.github.mangi.eta.R

/** Semantic intent, not a promise of K pixels. Legacy values are read-only aliases. */
internal object ImageResolutionTier {
    private val aliases = mapOf("低" to "low", "中" to "medium", "高" to "high", "超高" to "ultra",
        "1k" to "low", "1.5k" to "medium", "2k" to "high", "4k" to "ultra")
    fun normalize(value: String): String = value.trim().lowercase().let { aliases[it] ?: it }

    /** 展示名的字符串资源；未登记返回 null（调用方回退「跟随端点」或协议原文）。
     *  本对象不携带任何语言的硬编码文案，UI 层按当前语言解析资源。 */
    @StringRes
    fun labelRes(value: String): Int? = when (normalize(value)) {
        "low" -> R.string.image_resolution_low
        "medium" -> R.string.image_resolution_medium
        "high" -> R.string.image_resolution_high
        "ultra" -> R.string.image_resolution_ultra
        else -> null
    }
    fun legacy(value: String): String = when (normalize(value)) {
        "low" -> "1k"; "medium" -> "1.5k"; "high" -> "2k"; "ultra" -> "4k"; else -> value
    }
    val values = listOf("low", "medium", "high", "ultra")
}
