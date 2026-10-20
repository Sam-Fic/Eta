package io.github.mangi.eta.ui.adaptive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * 命中词在文本中的出现区间（闭区间，忽略大小写）。
 *
 * 空查询、空文本都返回空列表；命中首尾相接时区间不重叠，
 * 保证同一段文字不会因为反复匹配而叠出多层样式。
 */
internal fun highlightMatchRanges(text: String, needle: String): List<IntRange> {
    val target = needle.trim()
    if (target.isEmpty() || text.isEmpty()) return emptyList()
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (from <= text.length - target.length) {
        val index = text.indexOf(target, from, ignoreCase = true)
        if (index < 0) break
        ranges += index..(index + target.length - 1)
        from = index + target.length
    }
    return ranges
}

/**
 * 把命中词标出来的富文本：命中片段用 tertiary 容器色打底并加粗。
 *
 * 查询词为空、或文本里没有命中时原样返回（仍是 [AnnotatedString]），
 * 调用方不需要区分两种情况，直接渲染即可。
 */
@Composable
internal fun highlightedText(text: String, needle: String?): AnnotatedString {
    val ranges = highlightMatchRanges(text, needle.orEmpty())
    if (ranges.isEmpty()) return AnnotatedString(text)
    val background = MaterialTheme.colorScheme.tertiaryContainer
    val foreground = MaterialTheme.colorScheme.onTertiaryContainer
    return buildAnnotatedString {
        append(text)
        ranges.forEach { range ->
            addStyle(
                SpanStyle(
                    color = foreground,
                    background = background,
                    fontWeight = FontWeight.SemiBold,
                ),
                range.first,
                range.last + 1,
            )
        }
    }
}
