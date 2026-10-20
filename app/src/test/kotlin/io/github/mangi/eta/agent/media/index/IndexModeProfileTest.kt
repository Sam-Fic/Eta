package io.github.mangi.eta.agent.media.index

import android.content.Context
import io.github.mangi.eta.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [IndexMode] / [IndexModeProfile] 与后端链覆盖的单测。
 *
 * 档位参数是纯函数（[IndexModeProfile.forMode]），这里断言两档取值口径：
 * 全力档 = [MediaExtraction] 常量满配（保持既有行为），低耗档 = 批更小 + 条目间节流 +
 * 抽取预算减配 + 精排放宽 + 强制 CPU 单线程；后端偏好链逻辑不受档位影响，
 * 只有显式覆盖（[EmbeddingBackendPolicy] 的 override 参数）才改变候选链。
 * 索引器的节流/批提交是 IO 编排（难在纯 JVM 断言），只覆盖到 profile 与 policy 这层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IndexModeProfileTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun indexModeParsesWireAndFallsBackToPowerSaver() {
        assertEquals(IndexMode.PERFORMANCE, IndexMode.from("performance"))
        assertEquals(IndexMode.POWER_SAVER, IndexMode.from("power_saver"))
        // 未知或空偏好一律回落默认档（低耗后台），偏好损坏不能影响索引可用性。
        assertEquals(IndexMode.DEFAULT, IndexMode.from(null))
        assertEquals(IndexMode.DEFAULT, IndexMode.from("turbo"))
        assertEquals(IndexMode.POWER_SAVER, IndexMode.DEFAULT)
    }

    @Test
    fun performanceProfileKeepsFullBudgetAndExistingBehavior() {
        val profile = IndexModeProfile.forMode(IndexMode.PERFORMANCE)
        // 全力档 = 既有行为：批提交 32、无节流、抽取预算取 MediaExtraction 常量、精排 top-5。
        assertEquals(32, profile.batchSize)
        assertEquals(0L, profile.throttleDelayMs)
        assertEquals(MediaExtraction.MAX_VIDEO_FRAMES, profile.videoFrames)
        assertEquals(MediaExtraction.MAX_IMAGE_EDGE, profile.imageMaxEdge)
        assertEquals(MediaExtraction.MAX_PDF_PAGES, profile.maxPdfPages)
        assertEquals(5, profile.refineTopK)
        // 不强制 CPU 线程数、不覆盖后端链：完全沿用用户偏好（AUTO = GPU → NPU → CPU）。
        assertEquals(0, profile.cpuThreads)
        assertNull(profile.backendChainOverride)
        assertEquals(
            listOf(InferenceBackend.GPU, InferenceBackend.NPU, InferenceBackend.CPU),
            EmbeddingBackendPolicy.chain(InferenceBackend.AUTO, profile.backendChainOverride),
        )
    }

    @Test
    fun powerSaverProfileDownsizesBatchThrottleAndExtractionBudget() {
        val full = IndexModeProfile.forMode(IndexMode.PERFORMANCE)
        val saver = IndexModeProfile.forMode(IndexMode.POWER_SAVER)
        // 批提交缩小、条目间节流常量可调且非零。
        assertEquals(8, saver.batchSize)
        assertTrue("低耗档批大小应小于全力档", saver.batchSize < full.batchSize)
        assertEquals(IndexModeProfile.POWER_SAVER_THROTTLE_MS, saver.throttleDelayMs)
        assertTrue("低耗档应有条目间节流", saver.throttleDelayMs > 0L)
        // 抽取预算减配：视频帧减半、图像边略降、PDF 页数减半。
        assertEquals(MediaExtraction.MAX_VIDEO_FRAMES / 2, saver.videoFrames)
        assertEquals(IndexModeProfile.POWER_SAVER_IMAGE_EDGE, saver.imageMaxEdge)
        assertTrue("低耗档图像边应低于满配", saver.imageMaxEdge in 1 until MediaExtraction.MAX_IMAGE_EDGE)
        assertEquals(MediaExtraction.MAX_PDF_PAGES / 2, saver.maxPdfPages)
        // 视频命中精排放宽：top-3。
        assertEquals(3, saver.refineTopK)
        assertTrue("低耗档精排条数应少于全力档", saver.refineTopK < full.refineTopK)
        // 强制 CPU 单线程：不抢 GPU、不发热。
        assertEquals(1, saver.cpuThreads)
        assertEquals(listOf(InferenceBackend.CPU), saver.backendChainOverride)
    }

    @Test
    fun powerSaverBackendChainForcesCpuRegardlessOfPreference() {
        val override = IndexModeProfile.forMode(IndexMode.POWER_SAVER).backendChainOverride
        // 无论用户偏好是 AUTO 还是显式 GPU/NPU，低耗档一律只试 CPU。
        for (preference in InferenceBackend.entries) {
            assertEquals(listOf(InferenceBackend.CPU), EmbeddingBackendPolicy.chain(preference, override))
        }
        // 覆盖链只有 CPU 一个候选：首次成功不算回退，失败即无候选（返回 null）。
        assertEquals(
            EmbeddingBackendPolicy.Selection(InferenceBackend.CPU, fellBack = false),
            EmbeddingBackendPolicy.resolve(InferenceBackend.AUTO, listOf(false), override),
        )
        assertNull(EmbeddingBackendPolicy.resolve(InferenceBackend.AUTO, listOf(true), override))
    }

    @Test
    fun backendChainWithoutOverrideKeepsPreferenceLogic() {
        // override = null 时偏好链逻辑不变，档位不影响既有后端选择的纯函数行为。
        assertEquals(
            EmbeddingBackendPolicy.chain(InferenceBackend.GPU),
            EmbeddingBackendPolicy.chain(InferenceBackend.GPU, null),
        )
        assertEquals(
            EmbeddingBackendPolicy.resolve(InferenceBackend.AUTO, listOf(true, false)),
            EmbeddingBackendPolicy.resolve(InferenceBackend.AUTO, listOf(true, false), null),
        )
    }

    @Test
    fun indexModePreferenceRoundTripsThroughEmbeddingModelStore() {
        // 与 backend/dim 同一 prefs（embedding_model）：写入后可读回，缺省回落默认档。
        EmbeddingModelStore.setIndexMode(context, IndexMode.PERFORMANCE)
        assertEquals(IndexMode.PERFORMANCE, EmbeddingModelStore.indexMode(context))
        EmbeddingModelStore.setIndexMode(context, IndexMode.POWER_SAVER)
        assertEquals(IndexMode.POWER_SAVER, EmbeddingModelStore.indexMode(context))
    }

    @Test
    fun modeLabelsAndSummariesAreDistinctPerMode() {
        // 两档各有独立的名称与一句说明（设置页单选行 + 通知摘要共用）。
        assertEquals(R.string.media_mode_performance, IndexMode.PERFORMANCE.labelRes)
        assertEquals(R.string.media_mode_power_saver, IndexMode.POWER_SAVER.labelRes)
        assertTrue(IndexMode.PERFORMANCE.labelRes != IndexMode.POWER_SAVER.labelRes)
        assertTrue(IndexMode.PERFORMANCE.summaryRes != IndexMode.POWER_SAVER.summaryRes)
        assertTrue(IndexMode.PERFORMANCE.labelRes != IndexMode.PERFORMANCE.summaryRes)
    }
}
