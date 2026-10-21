package io.github.mangi.eta.agent.media.index

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [MediaIndexAutoUpdate] 的单测：只覆盖纯函数与偏好存取，不真跑索引。
 *
 * 断言口径：
 * - 定时档位解析：6/24/72/168 合法，非法值一律回落 0（关闭）；
 * - [MediaIndexAutoUpdate.dueReason] 守卫：模型未下载 / 索引进行中 / 防抖窗口内跳过，
 *   判定优先级为 模型未下载 > 索引进行中 > 防抖；
 * - 自动更新固定 [IndexMode.POWER_SAVER]（低耗后台），不跟随用户手选档位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaIndexAutoUpdateTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun intervalHoursParsesAllowedValuesAndFallsBackToOff() {
        assertEquals(0, MediaIndexAutoUpdate.parseIntervalHours(0))
        assertEquals(6, MediaIndexAutoUpdate.parseIntervalHours(6))
        assertEquals(24, MediaIndexAutoUpdate.parseIntervalHours(24))
        assertEquals(72, MediaIndexAutoUpdate.parseIntervalHours(72))
        assertEquals(168, MediaIndexAutoUpdate.parseIntervalHours(168))
        // 非法值（每 5 小时 / 12 小时 / 超过一周 / 负数）一律回落 0（关闭）。
        for (illegal in listOf(1, 5, 12, 25, 169, 999, -6)) {
            assertEquals(0, MediaIndexAutoUpdate.parseIntervalHours(illegal))
        }
    }

    @Test
    fun intervalHoursRoundTripsThroughPrefsAndNormalizesIllegalValue() {
        MediaIndexAutoUpdate.setAutoIndexIntervalHours(context, 72)
        assertEquals(72, MediaIndexAutoUpdate.autoIndexIntervalHours(context))
        // 写入非法值也归一化为 0（关闭）：偏好损坏不能把闹钟排到未知档位。
        MediaIndexAutoUpdate.setAutoIndexIntervalHours(context, 12)
        assertEquals(0, MediaIndexAutoUpdate.autoIndexIntervalHours(context))
    }

    @Test
    fun onLaunchSwitchDefaultsOffAndRoundTrips() {
        MediaIndexAutoUpdate.setAutoIndexOnLaunch(context, false)
        assertEquals(false, MediaIndexAutoUpdate.autoIndexOnLaunch(context))
        MediaIndexAutoUpdate.setAutoIndexOnLaunch(context, true)
        assertTrue(MediaIndexAutoUpdate.autoIndexOnLaunch(context))
        MediaIndexAutoUpdate.setAutoIndexOnLaunch(context, false)
        assertEquals(false, MediaIndexAutoUpdate.autoIndexOnLaunch(context))
    }

    @Test
    fun dueReasonSkipsWhenModelNotDownloaded() {
        assertEquals(
            MediaIndexAutoUpdate.SkipReason.MODEL_NOT_DOWNLOADED,
            MediaIndexAutoUpdate.dueReason(
                modelDownloaded = false,
                indexing = false,
                nowMillis = NOW,
                lastAutoAtMillis = 0L,
            ),
        )
    }

    @Test
    fun dueReasonSkipsWhileIndexing() {
        assertEquals(
            MediaIndexAutoUpdate.SkipReason.INDEXING,
            MediaIndexAutoUpdate.dueReason(
                modelDownloaded = true,
                indexing = true,
                nowMillis = NOW,
                lastAutoAtMillis = 0L,
            ),
        )
    }

    @Test
    fun dueReasonDebouncesWithinThirtyMinutes() {
        // 距上次自动索引启动不足 30 分钟（含 0、窗口前一毫秒）跳过。
        for (elapsed in listOf(0L, 1L, 15 * 60 * 1000L, MediaIndexAutoUpdate.DEBOUNCE_WINDOW_MS - 1)) {
            assertEquals(
                MediaIndexAutoUpdate.SkipReason.DEBOUNCED,
                MediaIndexAutoUpdate.dueReason(
                    modelDownloaded = true,
                    indexing = false,
                    nowMillis = NOW,
                    lastAutoAtMillis = NOW - elapsed,
                ),
            )
        }
        // 到达窗口边界放行；从未跑过（0）不防抖。
        assertNull(
            MediaIndexAutoUpdate.dueReason(
                modelDownloaded = true,
                indexing = false,
                nowMillis = NOW,
                lastAutoAtMillis = NOW - MediaIndexAutoUpdate.DEBOUNCE_WINDOW_MS,
            ),
        )
        assertNull(
            MediaIndexAutoUpdate.dueReason(
                modelDownloaded = true,
                indexing = false,
                nowMillis = NOW,
                lastAutoAtMillis = 0L,
            ),
        )
    }

    @Test
    fun dueReasonGuardPriorityIsModelThenIndexingThenDebounce() {
        // 三者同时命中时按 模型未下载 > 索引进行中 > 防抖 判定。
        assertEquals(
            MediaIndexAutoUpdate.SkipReason.MODEL_NOT_DOWNLOADED,
            MediaIndexAutoUpdate.dueReason(
                modelDownloaded = false,
                indexing = true,
                nowMillis = NOW,
                lastAutoAtMillis = NOW,
            ),
        )
        assertEquals(
            MediaIndexAutoUpdate.SkipReason.INDEXING,
            MediaIndexAutoUpdate.dueReason(
                modelDownloaded = true,
                indexing = true,
                nowMillis = NOW,
                lastAutoAtMillis = NOW,
            ),
        )
    }

    @Test
    fun autoUpdateAlwaysUsesPowerSaverRegardlessOfStoredMode() {
        // 自动更新固定低耗后台档；手选档位只影响手动索引/检索，不改变自动更新档位。
        assertEquals(IndexMode.POWER_SAVER, MediaIndexAutoUpdate.AUTO_MODE)
        EmbeddingModelStore.setIndexMode(context, IndexMode.PERFORMANCE)
        assertEquals(IndexMode.POWER_SAVER, MediaIndexAutoUpdate.AUTO_MODE)
        assertEquals(IndexMode.PERFORMANCE, EmbeddingModelStore.indexMode(context))
        EmbeddingModelStore.setIndexMode(context, IndexMode.POWER_SAVER)
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
