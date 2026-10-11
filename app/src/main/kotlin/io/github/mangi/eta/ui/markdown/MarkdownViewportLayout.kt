package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight

/** Selection and accessibility opt back into the same fully placed document. */
@Stable
internal class MarkdownLayoutAdmission {
    var eager by mutableStateOf(true)
    var nativeSelection by mutableStateOf(true)
    var placedRevision by mutableIntStateOf(-1)
        private set
    var revision by mutableIntStateOf(0)
        private set

    fun request(eager: Boolean, nativeSelection: Boolean) {
        if (this.eager == eager && this.nativeSelection == nativeSelection) return
        this.eager = eager
        this.nativeSelection = nativeSelection
        revision++
    }

    fun acknowledge(revision: Int) {
        if (eager && this.revision == revision && placedRevision != revision) placedRevision = revision
    }
}

internal val LocalMarkdownLayoutAdmission = compositionLocalOf<MarkdownLayoutAdmission?> { null }

/**
 * All children remain composed and measured: heights, fonts and asynchronous content stay exact.
 * Only placement is virtualized. Placeables belong to this measure result, never a cross-measure cache.
 */
@Composable
internal fun MarkdownViewportLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val viewport = LocalMarkdownDrawViewport.current
    val admission = LocalMarkdownLayoutAdmission.current
    val revision = admission?.revision ?: 0
    var documentOrigin by remember(viewport) { mutableStateOf<Offset?>(null) }
    Layout(content = content, modifier = modifier.onGloballyPositioned {
        documentOrigin = it.positionInRoot()
    }) { measurables, constraints ->
        var remainingHeight = constraints.maxHeight
        val children = measurables.map { measurable ->
            measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = remainingHeight)).also {
                if (remainingHeight != Constraints.Infinity) remainingHeight = (remainingHeight - it.height).coerceAtLeast(0)
            }
        }
        val width = constraints.constrainWidth(children.maxOfOrNull { it.width } ?: 0)
        val height = constraints.constrainHeight(children.sumOf { it.height })
        layout(width, height) {
            // Layer-backed ancestor offsets can move coordinates without running child placement.
            // The published origin invalidates placement; bounds are still read fresh here.
            val window = if (documentOrigin == null) null else viewport?.layoutWindow(coordinates)
            val eager = admission?.eager != false || window == null
            var y = 0
            children.forEach { child ->
                if (eager || markdownBlockIntersectsWindow(y, child.height, checkNotNull(window))) {
                    child.placeRelative(0, y)
                }
                y += child.height
            }
            if (eager) admission?.acknowledge(revision)
        }
    }
}

internal fun markdownBlockIntersectsWindow(top: Int, height: Int, window: Rect): Boolean =
    height <= 0 || top.toLong() + height >= window.top && top <= window.bottom
