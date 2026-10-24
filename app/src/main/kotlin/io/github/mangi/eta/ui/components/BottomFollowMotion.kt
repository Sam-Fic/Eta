package io.github.mangi.eta.ui.components

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stateful follow motion: target jumps do not become velocity jumps. Units are px/s.
 *
 * The target speed is `remaining / durationSeconds`, so the curve speeds up while the
 * gap is wide and lets go as it closes: new content starts from rest and lands softly
 * instead of being pushed at one constant rate. The speed ceiling only matters for
 * whole-screen gaps; a line height never reaches it.
 */
internal class BottomFollowMotion(
    private val maxSpeedPxPerSecond: Float = 1600f,
    private val accelerationPxPerSecondSquared: Float = 2400f,
    private val durationSeconds: Float = 0.24f,
    private val finishEpsilonPx: Float = 0.5f,
) {
    var velocityPxPerSecond = 0f
        private set
    private var lastFrameNanos: Long? = null

    fun reset() {
        velocityPxPerSecond = 0f
        lastFrameNanos = null
    }

    fun step(distancePx: Float, frameTimeNanos: Long, density: Float): Float {
        if (!distancePx.isFinite() || distancePx <= 0f || !density.isFinite() || density <= 0f) {
            reset()
            return 0f
        }
        val previous = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        if (previous == null || frameTimeNanos <= previous) return 0f
        // A stalled UI never repays the entire missed time in one large scroll.
        var timeLeft = ((frameTimeNanos - previous) / 1_000_000_000f).coerceAtMost(0.032f)
        val acceleration = accelerationPxPerSecondSquared * density
        var remaining = distancePx
        var movement = 0f
        while (timeLeft > 0.000001f && remaining > 0f) {
            val dt = min(timeLeft, 1f / 240f)
            val targetVelocity = min(
                maxSpeedPxPerSecond * density,
                min(remaining / durationSeconds, sqrt(2f * acceleration * remaining)),
            )
            val nextVelocity = targetVelocity.coerceIn(
                (velocityPxPerSecond - acceleration * dt).coerceAtLeast(0f),
                velocityPxPerSecond + acceleration * dt,
            )
            val delta = min(remaining, (velocityPxPerSecond + nextVelocity) * 0.5f * dt)
            velocityPxPerSecond = nextVelocity
            movement += delta
            remaining -= delta
            timeLeft -= dt
        }
        // Eliminate the asymptotic subpixel tail without a visible snap.
        if (remaining <= finishEpsilonPx && movement > 0f) {
            movement = distancePx
            reset()
        }
        return movement.coerceIn(0f, distancePx)
    }
}

/**
 * 尾部上提的缓慢跟随：与滚动共用同一套「限加速度 + 距离调速」的缓动曲线，
 * 但速度上限更低、时间常数更长，起步和收尾都不会突然。
 */
internal fun createTailLiftMotion(): BottomFollowMotion = BottomFollowMotion(
    maxSpeedPxPerSecond = 240f,
    accelerationPxPerSecondSquared = 640f,
    durationSeconds = 0.30f,
    finishEpsilonPx = 0f,
)
