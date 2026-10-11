package io.github.mangi.eta.ui.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the production node's path cache, not the legacy components renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmoothTextRevealPathCacheTest {
    private fun layout(text: String, width: Int = 160, scale: Float = 1f): TextLayoutResult {
        val measurer = TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(RuntimeEnvironment.getApplication()),
            defaultDensity = Density(1f, scale),
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
        val styled = AnnotatedString.Builder(text).apply {
            if (text.length > 5) addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 5)
        }.toAnnotatedString()
        return measurer.measure(styled, TextStyle(fontSize = 18.sp), constraints = Constraints(maxWidth = width))
    }

    private fun node() = SmoothTextRevealNode(SmoothTextRevealState(RevealBlockKey(0), SmoothTextRevealCoordinator()))
    private fun update(node: SmoothTextRevealNode, layout: TextLayoutResult, count: Int): Path? {
        val record = RevealRecord(RevealBlockKey(0)).apply {
            this.layoutResult = layout
            boundaries = graphemeBoundaries(layout.layoutInput.text.text)
            progress = count + 0.5f
        }
        node.javaClass.getDeclaredMethod("ensurePaths", RevealDrawSnapshot::class.java, Integer.TYPE).apply {
            isAccessible = true
        }.invoke(node, record.drawSnapshot, count)
        return node.javaClass.getDeclaredField("cachedFullPath").apply { isAccessible = true }.get(node) as Path?
    }

    private fun pixels(path: Path?, layout: TextLayoutResult): IntArray {
        val w = layout.size.width.coerceAtLeast(1) + 8
        val h = layout.size.height.coerceAtLeast(1) + 8
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (path != null) {
            val canvas = Canvas(bitmap)
            canvas.clipPath(path.asAndroidPath())
            canvas.drawColor(Color.WHITE)
        }
        return IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h); bitmap.recycle() }
    }

    private fun assertPrefix(node: SmoothTextRevealNode, layout: TextLayoutResult, count: Int): Path? {
        val actual = update(node, layout, count)
        val end = graphemeBoundaries(layout.layoutInput.text.text)[count]
        val expected = if (end > 0) layout.getPathForRange(0, end) else null
        val boundaries = graphemeBoundaries(layout.layoutInput.text.text)
        val next = node.javaClass.getDeclaredField("cachedNextPath").apply { isAccessible = true }.get(node) as Path?
        val expectedNext = if (count < boundaries.lastIndex) layout.getPathForRange(end, boundaries[count + 1]) else null
        assertArrayEquals("partial grapheme=$count", pixels(expectedNext, layout), pixels(next, layout))
        assertArrayEquals("prefix=$count text=${layout.layoutInput.text}", pixels(expected, layout), pixels(actual, layout))
        return actual
    }

    @Test fun multiGraphemeCatchUpMatchesCanonicalPrefixPixels() {
        val samples = listOf(
            "A long paragraph with several words and wrapped lines. More text to reveal.",
            "跨行中文，追赶一次推进多个字素。保留已经显现的文字。\n第二行继续。",
            "abc אבגדה مرحبا xyz mixed direction text followed by another line",
            "Cafe\u0301 👩‍👩‍👧‍👦 emoji 👍🏽 and spaces    then\nnew line of text",
        )
        for (text in samples) {
            val layout = layout(text)
            val node = node()
            val last = graphemeBoundaries(text).lastIndex
            assertPrefix(node, layout, 0)
            var previous = assertPrefix(node, layout, 1)
            for (count in listOf(5, 11, last / 2, last - 1, last).filter { it in 2..last }.distinct().sorted()) {
                val actual = assertPrefix(node, layout, count)
                assertNotSame(previous, actual)
                assertSame(actual, assertPrefix(node, layout, count))
                previous = actual
            }
        }
    }

    @Test fun singleGraphemeStepsAcrossLinesMatchCanonicalPrefix() {
        for (text in listOf(
            "A long paragraph with enough text to wrap across multiple lines.",
            "abc אבגדה مرحبا xyz mixed direction text followed by another line",
            "跨行中文，逐字检查换行边界。\n第二行继续。",
        )) {
            for (width in listOf(90, 160)) {
                val layout = layout(text, width = width)
                assertTrue(layout.lineCount > 1)
                val node = node()
                for (count in 0..graphemeBoundaries(text).lastIndex) {
                    val path = assertPrefix(node, layout, count)
                    assertSame(path, assertPrefix(node, layout, count))
                }
            }
        }
    }

    @Test fun backwardsProgressAndChangedLayoutRebuildInsteadOfKeepingOldPixels() {
        val text = "Stable prefix with enough text to wrap across multiple lines."
        val node = node()
        val original = layout(text)
        val first = assertPrefix(node, original, 20)
        val rewind = assertPrefix(node, original, 4)
        assertNotSame(first, rewind)
        val narrow = layout(text, width = 90, scale = 1.3f)
        assertNotSame(rewind, assertPrefix(node, narrow, 12))
        assertPrefix(node, layout("Corrected prefix and new content."), 9)
        assertPrefix(node, original, 0)
    }
}
