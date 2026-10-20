package io.github.mangi.eta.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索命中词的标色区间计算。
 *
 * 命中词要高亮出来，所以区间必须覆盖每一次出现、且不重叠，
 * 否则同一段文字会叠出多层样式，或漏掉后面的命中。
 */
class EtaHighlightTest {

    @Test
    fun `blank needle produces no ranges`() {
        assertTrue(highlightMatchRanges("随便一段文字", "").isEmpty())
        assertTrue(highlightMatchRanges("随便一段文字", "   ").isEmpty())
        assertTrue(highlightMatchRanges("", "关键词").isEmpty())
    }

    @Test
    fun `single match covers the needle`() {
        assertEquals(listOf(2..4), highlightMatchRanges("xx关键词yy", "关键词"))
    }

    @Test
    fun `match is case insensitive`() {
        assertEquals(listOf(0..2), highlightMatchRanges("Key 命中", "key"))
        assertEquals(listOf(3..5), highlightMatchRanges("命中 Key", "KEY"))
    }

    @Test
    fun `repeated matches do not overlap`() {
        assertEquals(
            listOf(0..1, 3..4, 6..7),
            highlightMatchRanges("ab ab ab", "ab"),
        )
    }

    @Test
    fun `needle longer than text produces no ranges`() {
        assertTrue(highlightMatchRanges("短", "更长的关键词").isEmpty())
    }

    @Test
    fun `surrounding text is preserved`() {
        val text = "前缀 中间 关键词 后缀"
        val ranges = highlightMatchRanges(text, "关键词")
        assertEquals(1, ranges.size)
        assertEquals("关键词", text.substring(ranges.first().first, ranges.first().last + 1))
    }
}
