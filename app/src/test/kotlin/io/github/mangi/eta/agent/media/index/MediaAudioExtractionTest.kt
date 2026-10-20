package io.github.mangi.eta.agent.media.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MediaExtraction] 音频抽取的纯逻辑单测：WAV 封装、30s 窗口切片、5 分钟硬预算、
 * 声道混合与线性插值重采样。
 *
 * 真实解码（MediaExtractor + MediaCodec）依赖设备编解码器，不在纯 JVM 覆盖范围；
 * 这里只验证解码之后的纯函数段，组合口径与 [MediaExtraction.audioWindows] 一致：
 * `capToBudget → splitWindows → wavBytes`（每窗一个完整 WAV，供 InputData.Audio 消费）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaAudioExtractionTest {

    @Test
    fun wavBytesWrapsPcmWithStandardRiffHeader() {
        val samples = shortArrayOf(0, 1, -1, 32767, -32768)
        val wav = MediaExtraction.wavBytes(samples, MediaExtraction.AUDIO_SAMPLE_RATE)
        // 44 字节标准头 + 小端 16-bit 样本。
        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", wav.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(36 + samples.size * 2, le32(wav, 4))
        assertEquals(16, le32(wav, 16)) // fmt 块长度
        assertEquals(1, le16(wav, 20)) // PCM 编码
        assertEquals(1, le16(wav, 22)) // 单声道
        assertEquals(MediaExtraction.AUDIO_SAMPLE_RATE, le32(wav, 24))
        assertEquals(MediaExtraction.AUDIO_SAMPLE_RATE * 2, le32(wav, 28)) // 字节率
        assertEquals(2, le16(wav, 32)) // 块对齐
        assertEquals(16, le16(wav, 34)) // 位深
        assertEquals(samples.size * 2, le32(wav, 40))
        // 样本按小端无损落位（含负数与半幅边界）。
        samples.forEachIndexed { index, sample ->
            assertEquals("第 $index 个样本", sample.toInt() and 0xffff, le16(wav, 44 + index * 2))
        }
    }

    @Test
    fun splitWindowsSlicesIntoThirtySecondWindowsKeepingTailWindow() {
        val sampleRate = MediaExtraction.AUDIO_SAMPLE_RATE
        val windowSamples = (MediaExtraction.AUDIO_WINDOW_MS * sampleRate / 1000L).toInt()
        // 65 秒 → 30s + 30s + 5s 尾窗（尾窗不足整窗也保留）。
        val total = sampleRate * 65
        val windows = MediaExtraction.splitWindows(ShortArray(total), sampleRate)
        assertEquals(listOf(windowSamples, windowSamples, total - 2 * windowSamples), windows.map { it.size })
    }

    @Test
    fun splitWindowsHandlesShortExactAndEmptyAudio() {
        val sampleRate = MediaExtraction.AUDIO_SAMPLE_RATE
        val windowSamples = (MediaExtraction.AUDIO_WINDOW_MS * sampleRate / 1000L).toInt()
        // 1 秒 → 单窗；恰好一窗 → 不产生空尾窗；空输入 → 无窗。
        assertEquals(1, MediaExtraction.splitWindows(ShortArray(sampleRate), sampleRate).size)
        assertEquals(listOf(windowSamples), MediaExtraction.splitWindows(ShortArray(windowSamples), sampleRate).map { it.size })
        assertEquals(emptyList<ShortArray>(), MediaExtraction.splitWindows(ShortArray(0), sampleRate))
    }

    @Test
    fun capToBudgetKeepsOnlyFirstFiveMinutes() {
        val sampleRate = MediaExtraction.AUDIO_SAMPLE_RATE
        val fiveMinutes = sampleRate * 300
        // 6 分钟音频只保留前 5 分钟（硬预算），预算内原样返回。
        val capped = MediaExtraction.capToBudget(ShortArray(fiveMinutes + sampleRate * 60), sampleRate)
        assertEquals(fiveMinutes, capped.size)
        assertEquals(30, MediaExtraction.capToBudget(ShortArray(sampleRate * 30), sampleRate).size / sampleRate)
        // 预算组合口径：5 分钟恰好切成 10 个 30s 窗。
        assertEquals(10, MediaExtraction.splitWindows(capped, sampleRate).size)
    }

    @Test
    fun pcmToMonoAveragesChannelsAndKeepsMonoAsIs() {
        // 2 声道 3 帧：逐帧按声道平均（向零截断）。
        val stereo = shortArrayOf(100, 200, -100, 300, 32767, -32768)
        val mono = MediaExtraction.pcmToMono(stereo, 2)
        assertEquals(3, mono.size)
        assertEquals(150, mono[0].toInt())
        assertEquals(100, mono[1].toInt())
        assertEquals(0, mono[2].toInt())
        // 单声道原样返回，不做拷贝。
        val single = shortArrayOf(1, 2)
        assertSame(single, MediaExtraction.pcmToMono(single, 1))
    }

    @Test
    fun resamplePcmConvertsRatesWithLinearInterpolation() {
        // 同采样率原样返回；空输入返回空。
        val same = shortArrayOf(1, 2, 3)
        assertSame(same, MediaExtraction.resamplePcm(same, 16_000, 16_000))
        assertEquals(0, MediaExtraction.resamplePcm(ShortArray(0), 48_000, 16_000).size)
        // 48k → 16k 降采样（3:1）：样本数收缩，取样点落在输入流对应位置。
        val down = MediaExtraction.resamplePcm(shortArrayOf(0, 300, 600), 48_000, 16_000)
        assertEquals(1, down.size)
        assertEquals(0, down[0].toInt())
        // 8k → 16k 升采样（1:2）：样本数翻倍，中点取两样本线性插值。
        val up = MediaExtraction.resamplePcm(shortArrayOf(0, 100), 8_000, 16_000)
        assertEquals(4, up.size)
        assertEquals(0, up[0].toInt())
        assertEquals(50, up[1].toInt())
        assertEquals(100, up[2].toInt())
        // 30s 窗在 16k 下的样本数与窗口预算一致。
        assertTrue(
            (MediaExtraction.AUDIO_WINDOW_MS * MediaExtraction.AUDIO_SAMPLE_RATE / 1000L).toInt() ==
                MediaExtraction.splitWindows(ShortArray(MediaExtraction.AUDIO_SAMPLE_RATE * 30), MediaExtraction.AUDIO_SAMPLE_RATE)
                    .single().size,
        )
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)
}
