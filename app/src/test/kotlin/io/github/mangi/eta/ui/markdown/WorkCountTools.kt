package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.Constraints
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector
import java.util.IdentityHashMap

internal data class Work(var measure: Long = 0, var placement: Long = 0, var intrinsic: Long = 0)
internal object WorkCounts {
    private var active = false
    private val rows = IdentityHashMap<Any, Work>()
    fun begin() { check(!active); rows.clear(); active = true }
    fun hit(node: Any, increment: (Work) -> Unit) {
        if (!active) return
        val row = rows[node] ?: Work().also { check(rows.size < 4096); rows[node] = it }
        increment(row)
    }
    fun end(): Map<Any, Work> {
        active = false
        return IdentityHashMap<Any, Work>().also { out ->
            rows.forEach { (node, work) -> out[node] = work.copy() }
        }
    }
}
private class CountingPolicy(val delegate: MeasurePolicy, private val node: Any) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        WorkCounts.hit(node) { it.measure++ }
        val result = with(delegate) { measure(measurables, constraints) }
        return object : MeasureResult by result {
            override fun placeChildren() { WorkCounts.hit(node) { it.placement++ }; result.placeChildren() }
        }
    }
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        WorkCounts.hit(node) { it.intrinsic++ }; return with(delegate) { minIntrinsicWidth(measurables, height) }
    }
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        WorkCounts.hit(node) { it.intrinsic++ }; return with(delegate) { maxIntrinsicWidth(measurables, height) }
    }
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        WorkCounts.hit(node) { it.intrinsic++ }; return with(delegate) { minIntrinsicHeight(measurables, width) }
    }
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        WorkCounts.hit(node) { it.intrinsic++ }; return with(delegate) { maxIntrinsicHeight(measurables, width) }
    }
}
@ForType(className = "androidx.compose.ui.node.LayoutNode")
internal interface DirectLayoutPolicy {
    @Direct fun getMeasurePolicy(): MeasurePolicy
    @Direct fun setMeasurePolicy(value: MeasurePolicy)
}
@Implements(className = "androidx.compose.ui.node.LayoutNode", isInAndroidSdk = false, callThroughByDefault = true)
class ShadowWorkLayoutNode {
    @RealObject private lateinit var real: Any
    @Implementation protected fun setMeasurePolicy(value: MeasurePolicy) {
        val direct = reflector(DirectLayoutPolicy::class.java, real)
        val installed = direct.getMeasurePolicy()
        val previous = (installed as? CountingPolicy)?.delegate ?: installed
        val next = (value as? CountingPolicy)?.delegate ?: value
        if (previous == next) return
        direct.setMeasurePolicy(CountingPolicy(next, real))
    }
}
