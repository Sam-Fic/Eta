package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TwoWayConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DrawerSettleMotionTest {
    private val converter = object : TwoWayConverter<Float, AnimationVector1D> {
        override val convertToVector: (Float) -> AnimationVector1D = { AnimationVector1D(it) }
        override val convertFromVector: (AnimationVector1D) -> Float = { it.value }
    }
    private val spec = DrawerSettleSpringSpec(density = 2.75f)

    private fun valueAt(initial: Float, target: Float, velocity: Float, nanos: Long): Float {
        val animation = spec.vectorize(converter)
        return converter.convertFromVector(
            animation.getValueFromNanos(
                nanos,
                converter.convertToVector(initial),
                converter.convertToVector(target),
                converter.convertToVector(velocity),
            ),
        )
    }

    private fun sample(initial: Float, target: Float, velocity: Float): List<Float> {
        val animation = spec.vectorize(converter)
        val initialVector = converter.convertToVector(initial)
        val targetVector = converter.convertToVector(target)
        val velocityVector = converter.convertToVector(velocity)
        val duration = animation.getDurationNanos(initialVector, targetVector, velocityVector)
        val step = 8_000_000L
        return buildList {
            var time = 0L
            while (time <= duration + step) {
                add(
                    converter.convertFromVector(
                        animation.getValueFromNanos(
                            time,
                            initialVector,
                            targetVector,
                            velocityVector,
                        ),
                    ),
                )
                time += step
            }
        }
    }

    @Test
    fun fastFlickNeverOvershootsOpenAnchor() {
        val samples = sample(initial = -600f, target = 0f, velocity = 6000f)
        assertTrue(samples.all { it <= 0.001f })
        assertTrue(abs(samples.last()) < 0.01f)
    }

    @Test
    fun shortRemainingDistanceCapsVelocityInsteadOfSlamming() {
        val samples = sample(initial = -80f, target = 0f, velocity = 6000f)
        assertTrue(samples.all { it <= 0.001f })
        assertTrue(abs(samples.last()) < 0.01f)
    }

    @Test
    fun fastFlickSettlesSoonerThanSlowRelease() {
        fun duration(velocity: Float): Long = spec.vectorize(converter).getDurationNanos(
            converter.convertToVector(-600f),
            converter.convertToVector(0f),
            converter.convertToVector(velocity),
        )
        assertTrue(duration(5000f) < duration(300f))
    }

    @Test
    fun releaseVelocityShapesTheApproach() {
        val slow = valueAt(-600f, 0f, 100f, 32_000_000L)
        val fast = valueAt(-600f, 0f, 4000f, 32_000_000L)
        assertTrue(fast > slow)
        assertTrue(fast < 0f)
    }

    @Test
    fun springStartsAtCurrentOffsetWithReleaseVelocity() {
        val animation = spec.vectorize(converter)
        val initial = converter.convertToVector(-600f)
        val target = converter.convertToVector(0f)
        val velocity = converter.convertToVector(4000f)
        assertEquals(-600f, converter.convertFromVector(animation.getValueFromNanos(0L, initial, target, velocity)), 1e-4f)
        assertEquals(4000f, converter.convertFromVector(animation.getVelocityFromNanos(0L, initial, target, velocity)), 1f)
    }

    @Test
    fun approachIsMonotonicWithoutBounce() {
        val samples = sample(initial = -600f, target = 0f, velocity = 2500f)
        for (i in 1 until samples.size) {
            assertTrue(samples[i] >= samples[i - 1] - 0.001f)
        }
    }
}
