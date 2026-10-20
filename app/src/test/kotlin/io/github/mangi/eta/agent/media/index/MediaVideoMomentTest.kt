package io.github.mangi.eta.agent.media.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 视频「时刻检索」的纯逻辑单测：抽帧时刻序列计算与二段精排选帧。
 *
 * 方案为「入库每视频一行平均向量不变 + 检索命中后二段精排」：
 * 复用 [MediaExtraction.videoFrames] 重抽帧（含时刻戳），逐帧与查询点积，
 * 取最高分帧的时刻回填 `Hit.frameTimeMs`。真实抽帧/嵌入依赖设备能力，这里只测纯逻辑两段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaVideoMomentTest {

    @Test
    fun frameTimestampsSpreadEvenlyWhileAvoidingEdges() {
        val durationMs = 80_000L
        val timestamps = MediaExtraction.frameTimestamps(durationMs)
        assertEquals(MediaExtraction.MAX_VIDEO_FRAMES, timestamps.size)
        // 均匀布点但避开首尾极端位置：首帧 > 0、末帧 < 时长、严格递增。
        assertTrue("首帧应避开片头黑场：$timestamps", timestamps.first() > 0L)
        assertTrue("末帧应避开片尾：$timestamps", timestamps.last() < durationMs)
        assertEquals(timestamps.sorted(), timestamps)
        // 契约口径：durationMs * (index + 1) / (帧数 + 1)，毫秒单位。
        assertEquals(
            (0 until MediaExtraction.MAX_VIDEO_FRAMES).map { index ->
                durationMs * (index + 1) / (MediaExtraction.MAX_VIDEO_FRAMES + 1)
            },
            timestamps,
        )
    }

    @Test
    fun frameTimestampsFallBackToZeroWhenDurationUnknown() {
        assertEquals(List(MediaExtraction.MAX_VIDEO_FRAMES) { 0L }, MediaExtraction.frameTimestamps(0L))
    }

    @Test
    fun bestMomentPicksFrameWithHighestDotProduct() {
        val query = floatArrayOf(1f, 0f)
        val frames = listOf(
            1_000L to floatArrayOf(0f, 1f),
            2_000L to floatArrayOf(0.5f, 0.5f),
            3_000L to floatArrayOf(0.9f, 0.1f),
        )
        // 取点积最大的帧时刻，而不是首帧或末帧。
        assertEquals(3_000L, MediaSearchService.bestMoment(query, frames))
        // 反向查询应选另一端的帧，证明选帧随查询变化。
        assertEquals(1_000L, MediaSearchService.bestMoment(floatArrayOf(0f, 1f), frames))
    }

    @Test
    fun bestMomentSkipsMismatchedFramesAndReturnsNullWhenUnscoreable() {
        val query = floatArrayOf(1f, 0f)
        // 维度不一致的帧不参与选帧（与 rank 的维度一致性约定相同）。
        assertEquals(
            3_000L,
            MediaSearchService.bestMoment(
                query,
                listOf(3_000L to floatArrayOf(0.9f, 0.1f), 4_000L to floatArrayOf(1f)),
            ),
        )
        // 无帧或全部维度不一致 → null（命中时刻保持无值，UI/工具省略该字段）。
        assertNull(MediaSearchService.bestMoment(query, emptyList()))
        assertNull(MediaSearchService.bestMoment(query, listOf(1_000L to floatArrayOf(1f))))
    }
}
