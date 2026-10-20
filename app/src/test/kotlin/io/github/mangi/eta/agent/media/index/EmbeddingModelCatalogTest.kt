package io.github.mangi.eta.agent.media.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmbeddingModelCatalog] 的低内存判定与默认变体选择纯逻辑单测。
 *
 * 低内存设备首次未显式选择模型时默认纯文本 270M 降级（§7 低内存减负），
 * 用户显式选择优先于默认，可随时手动切回全模态 740M。
 * 低内存检测的 Context 部分在 [EmbeddingModelStore.lowMemoryDevice]，此处只测纯函数。
 */
class EmbeddingModelCatalogTest {

    @Test
    fun lowMemoryWhenLowRamDeviceOrSmallTotalMem() {
        assertTrue(
            EmbeddingModelCatalog.isLowMemory(
                isLowRamDevice = true,
                totalMemBytes = 8L * 1024 * 1024 * 1024,
            ),
        )
        assertTrue(
            EmbeddingModelCatalog.isLowMemory(
                isLowRamDevice = false,
                totalMemBytes = EmbeddingModelCatalog.LOW_MEMORY_TOTAL_BYTES - 1,
            ),
        )
        assertFalse(
            EmbeddingModelCatalog.isLowMemory(
                isLowRamDevice = false,
                totalMemBytes = EmbeddingModelCatalog.LOW_MEMORY_TOTAL_BYTES,
            ),
        )
        assertFalse(
            EmbeddingModelCatalog.isLowMemory(
                isLowRamDevice = false,
                totalMemBytes = 8L * 1024 * 1024 * 1024,
            ),
        )
    }

    @Test
    fun defaultModelDegradesToTextOnlyOnLowMemory() {
        assertEquals(
            EmbeddingModelCatalog.TEXT_ONLY_ID,
            EmbeddingModelCatalog.defaultModel(lowMemory = true).id,
        )
        assertEquals(
            EmbeddingModelCatalog.OMNIMODAL_ID,
            EmbeddingModelCatalog.defaultModel(lowMemory = false).id,
        )
    }

    @Test
    fun explicitModelSelectionWinsOverLowMemoryDefault() {
        // 用户显式选择优先于低内存默认，仍可手动切 740M。
        assertEquals(
            EmbeddingModelCatalog.OMNIMODAL_ID,
            EmbeddingModelCatalog.model(EmbeddingModelCatalog.OMNIMODAL_ID, lowMemory = true).id,
        )
        assertEquals(
            EmbeddingModelCatalog.TEXT_ONLY_ID,
            EmbeddingModelCatalog.model(EmbeddingModelCatalog.TEXT_ONLY_ID, lowMemory = false).id,
        )
    }

    @Test
    fun unknownOrMissingIdFallsBackToLowMemoryDefault() {
        // 偏好损坏或从未选择时回落默认（含低内存降级），不能让启动失败。
        assertEquals(
            EmbeddingModelCatalog.TEXT_ONLY_ID,
            EmbeddingModelCatalog.model(null, lowMemory = true).id,
        )
        assertEquals(
            EmbeddingModelCatalog.OMNIMODAL_ID,
            EmbeddingModelCatalog.model("does-not-exist", lowMemory = false).id,
        )
    }

    @Test
    fun textOnlyVariantDisablesVisionAndAudio() {
        // 纯文本降级后，图像/视频/音频按 modalities 门禁跳过（MediaIndexer.embedCandidate）。
        assertEquals(
            EmbeddingModelCatalog.Modalities.TEXT_ONLY,
            EmbeddingModelCatalog.defaultModel(lowMemory = true).modalities,
        )
        assertTrue(EmbeddingModelCatalog.defaultModel(lowMemory = true).modalities.vision.not())
        assertTrue(EmbeddingModelCatalog.defaultModel(lowMemory = true).modalities.audio.not())
        assertTrue(EmbeddingModelCatalog.defaultModel(lowMemory = false).modalities.vision)
        assertTrue(EmbeddingModelCatalog.defaultModel(lowMemory = false).modalities.audio)
    }
}
