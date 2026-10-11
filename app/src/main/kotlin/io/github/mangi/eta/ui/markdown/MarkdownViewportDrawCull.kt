package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo

internal val LocalMarkdownDrawViewport = staticCompositionLocalOf<MarkdownDrawViewport?> { null }

/** Shared viewport for draw culling; measurement, placement and semantics remain complete. */
internal class MarkdownDrawViewport {
    private var coordinates: LayoutCoordinates? = null
    private var lastBounds: Rect? = null
    private var enabled = false
    private val nodes = mutableSetOf<MarkdownViewportCullNode>()

    fun updateEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        nodes.forEach { it.refresh() }
    }

    fun updateCoordinates(value: LayoutCoordinates) {
        val bounds = value.boundsInRoot()
        val changed = coordinates !== value || lastBounds != bounds
        coordinates = value
        lastBounds = bounds
        // Coordinates mutate in place. Compare value snapshots, not just their identity.
        if (changed) nodes.forEach { it.refresh() }
    }

    internal fun register(node: MarkdownViewportCullNode) { nodes.add(node) }
    internal fun unregister(node: MarkdownViewportCullNode) { nodes.remove(node) }

    internal fun shouldSkip(leaf: LayoutCoordinates?, margin: Float): Boolean {
        val viewport = coordinates ?: return false
        if (!enabled || !viewport.isAttached || leaf?.isAttached != true) return false
        if (viewport.size.width <= 0 || viewport.size.height <= 0 || leaf.size.height <= 0) return false
        val bounds = try {
            viewport.localBoundingBoxOf(leaf, clipBounds = false)
        } catch (_: IllegalArgumentException) {
            return false // Coordinates from different roots during reparenting.
        } catch (_: IllegalStateException) {
            return false // A detached coordinator cannot provide a reliable transform.
        }
        return markdownTextOutsideViewport(bounds, viewport.size.height.toFloat(), margin)
    }
}

internal fun markdownTextOutsideViewport(bounds: Rect, height: Float, margin: Float): Boolean {
    if (!height.isFinite() || height <= 0f || !margin.isFinite() || margin < 0f ||
        !bounds.left.isFinite() || !bounds.right.isFinite() || bounds.right <= bounds.left ||
        !bounds.top.isFinite() || !bounds.bottom.isFinite() || bounds.bottom <= bounds.top) return false
    return bounds.bottom < -margin || bounds.top > height + margin
}

@Composable
internal fun Modifier.markdownViewportDrawCull(enabled: Boolean): Modifier {
    val viewport = LocalMarkdownDrawViewport.current
    return if (enabled && viewport != null) then(MarkdownViewportCullElement(viewport)) else this
}

private data class MarkdownViewportCullElement(val viewport: MarkdownDrawViewport) :
    ModifierNodeElement<MarkdownViewportCullNode>() {
    override fun create() = MarkdownViewportCullNode(viewport)
    override fun update(node: MarkdownViewportCullNode) = node.update(viewport)
    override fun InspectorInfo.inspectableProperties() { name = "markdownViewportDrawCull" }
}

internal class MarkdownViewportCullNode(private var viewport: MarkdownDrawViewport) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null
    private var skip = false
    private var margin = 0f

    override fun onAttach() { viewport.register(this) }
    override fun onDetach() {
        viewport.unregister(this)
        coordinates = null
        skip = false
    }

    fun update(value: MarkdownDrawViewport) {
        if (viewport === value) return
        if (isAttached) viewport.unregister(this)
        viewport = value
        if (isAttached) viewport.register(this)
        refresh()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        refresh()
    }

    fun refresh() {
        val next = viewport.shouldSkip(coordinates, margin)
        if (skip != next) {
            skip = next
            // A previously empty recording must be rebuilt before it re-enters the viewport.
            if (isAttached) invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        margin = 32f * density * fontScale
        skip = viewport.shouldSkip(coordinates, margin)
        if (!skip) drawContent()
    }
}
