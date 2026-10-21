package io.github.mangi.eta.agent.browser.ported.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签栏宽度份额的回归护栏。
 *
 * 背景：Compose 的 `Modifier.weight` 对 **小于等于 0** 的权重直接抛
 * IllegalArgumentException（"invalid weight; must be greater than zero"），且这个检查发生在
 * **组合期**（构造 Modifier 时），不是测量期。标签栏在「整行只剩一个标签」时会补一份
 * 互补占位，稳态下它的权重是 `1f - 1f == 0f` —— 于是点「打开浏览器」的瞬间，标签栏首帧
 * 组合就抛异常、应用当场闪退，浏览器页面一个像素都画不出来。
 *
 * 这些用例把「权重恒为正」钉死，避免以后再有人把占位改回 `1f - 份额` 的裸写法。
 */
class BrowserTabWeightTest {
    /** 采样覆盖稳态、两端越界、欠阻尼弹簧的过冲与 NaN。 */
    private val samples = listOf(
        0f,
        BrowserTabCollapsedFraction,
        0.25f,
        0.5f,
        0.999f,
        1f,
        1.0001f,
        1.5f,
        -0.0001f,
        -1f,
        Float.NaN,
        Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
    )

    @Test
    fun chipWeightIsAlwaysPositive() {
        samples.forEach { fraction ->
            val weight = browserTabChipWeight(fraction)
            assertTrue(
                "标签份额必须 > 0（输入 $fraction 得到 $weight）",
                weight > 0f,
            )
        }
    }

    @Test
    fun spacerWeightIsAlwaysPositive() {
        samples.forEach { fraction ->
            val weight = browserTabSpacerWeight(fraction)
            assertTrue(
                "占位份额必须 > 0（输入 $fraction 得到 $weight）",
                weight > 0f,
            )
        }
    }

    @Test
    fun steadyStateKeepsFullShare() {
        assertEquals(1f, browserTabChipWeight(1f), 0f)
        // 稳态下占位几乎为 0（但仍是正数，否则 Compose 会抛异常）。
        assertEquals(BrowserTabCollapsedFraction, browserTabSpacerWeight(1f), 0f)
    }

    @Test
    fun collapsedStateKeepsComplementShare() {
        assertEquals(BrowserTabCollapsedFraction, browserTabChipWeight(0f), 0f)
        assertEquals(1f - BrowserTabCollapsedFraction, browserTabSpacerWeight(0f), 1e-6f)
    }

    @Test
    fun totalWeightStaysCloseToOne() {
        samples.forEach { fraction ->
            val total = browserTabChipWeight(fraction) + browserTabSpacerWeight(fraction)
            assertTrue(
                "总份额应约为 1f（输入 $fraction 得到 $total）",
                total > 0.999f && total <= 1.002f,
            )
        }
    }

    @Test
    fun nanDoesNotLeakIntoWeights() {
        assertEquals(BrowserTabCollapsedFraction, browserTabChipWeight(Float.NaN), 0f)
        assertEquals(1f - BrowserTabCollapsedFraction, browserTabSpacerWeight(Float.NaN), 1e-6f)
    }
}
