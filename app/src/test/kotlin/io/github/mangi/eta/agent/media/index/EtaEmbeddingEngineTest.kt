package io.github.mangi.eta.agent.media.index

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [EtaEmbeddingEngine] 的指令前缀拼接与 [Float16VectorCodec] 编解码单测。
 *
 * 真实推理（compute）依赖 LiteRT 运行时与模型文件，单测里有意不触发；
 * 这里只覆盖纯 JVM 可验证的文本约定、半精度编解码数学与后端选择策略。
 * `android.util.Half` 由 Robolectric 的真实框架代码提供（API 26+），故本类跑在 Robolectric 上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaEmbeddingEngineTest {

    @Test
    fun documentAndQueryPrefixesMatchEmbeddingGemmaInstructionContract() {
        assertEquals("task: search result | text: ", EtaEmbeddingEngine.DOCUMENT_PREFIX)
        assertEquals("task: search query | text: ", EtaEmbeddingEngine.QUERY_PREFIX)
        // 入库与查询前缀必须非对称，混用会显著降低召回。
        assertTrue(EtaEmbeddingEngine.DOCUMENT_PREFIX != EtaEmbeddingEngine.QUERY_PREFIX)
    }

    @Test
    fun documentTextTrimsEdgesBeforePrefixing() {
        assertEquals(
            "task: search result | text: 海边 日落",
            EtaEmbeddingEngine.documentText("  海边 日落\n"),
        )
        // 内部空白属于语义内容，只允许去掉首尾空白。
        assertEquals(
            "task: search result | text: a  b\tc",
            EtaEmbeddingEngine.documentText("a  b\tc"),
        )
        // 全空白文本退化为「仅前缀」，不能抛异常。
        assertEquals("task: search result | text: ", EtaEmbeddingEngine.documentText(" \t "))
    }

    @Test
    fun queryTextTrimsEdgesBeforePrefixing() {
        assertEquals(
            "task: search query | text: 吹蜡烛",
            EtaEmbeddingEngine.queryText("\t吹蜡烛  "),
        )
        assertEquals("task: search query | text: ", EtaEmbeddingEngine.queryText(""))
        // 同一文本经两条前缀拼接后必须不同，验证 query/document 不会串味。
        assertTrue(EtaEmbeddingEngine.queryText("x") != EtaEmbeddingEngine.documentText("x"))
    }

    @Test
    fun float16CodecRoundTripsExactValuesForAllSupportedDims() {
        for (dim in listOf(768, 512, 256)) {
            // 构造 k/8 系列取值（半精度可精确表示），让往返断言不含舍入噪声。
            val vector = FloatArray(dim) { index -> ((index % 21) - 10) / 8f }
            val bytes = Float16VectorCodec.encode(vector)
            assertEquals("$dim 维向量应编码为 dim*2 字节", dim * 2, bytes.size)
            val decoded = Float16VectorCodec.decode(bytes)
            assertEquals("$dim 维向量解码长度应一致", dim, decoded.size)
            assertArrayEquals("dim=$dim 编解码往返应无损", vector, decoded, 0f)
        }
    }

    @Test
    fun float16CodecRoundTripsHalfExtremesExactly() {
        // 含 0、正负号、半精度最大值 65504 与最小正规数 2^-14，均应精确往返。
        val values = floatArrayOf(0f, 1f, -1f, 1.5f, -0.25f, 32f, -65504f, 65504f, 6.1035156E-5f)
        val decoded = Float16VectorCodec.decode(Float16VectorCodec.encode(values))
        assertArrayEquals(values, decoded, 0f)
    }

    @Test
    fun float16RoundTripKeepsUnitVectorNormalized() {
        // [0.5 x 4] 是单位向量；归一化后余弦相似度等于点积，往返必须保持近似单位长。
        val unit = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val decoded = Float16VectorCodec.decode(Float16VectorCodec.encode(unit))
        val norm = kotlin.math.sqrt(decoded.fold(0f) { acc, value -> acc + value * value })
        assertEquals("半精度往返后仍应近似单位长", 1.0, norm.toDouble(), 1e-3)
    }

    @Test
    fun float16ApproximatesUnrepresentableValuesWithinHalfPrecision() {
        val decoded = Float16VectorCodec.decode(Float16VectorCodec.encode(floatArrayOf(0.1f, 3.14159f)))
        // 半精度只有 10 位尾数，不可精确表示的值允许有 2^-11 相对误差。
        assertEquals(0.1, decoded[0].toDouble(), 1e-3)
        assertEquals(3.14159, decoded[1].toDouble(), 1e-2)
    }

    @Test
    fun float16DecodeRejectsOddByteLength() {
        // 每个分量固定占 2 字节，奇数长度说明 BLOB 损坏，必须拒绝而不是读越界。
        assertThrows(IllegalArgumentException::class.java) {
            Float16VectorCodec.decode(ByteArray(3))
        }
    }

    @Test
    fun float16CodecHandlesEmptyVector() {
        assertEquals(0, Float16VectorCodec.encode(FloatArray(0)).size)
        assertEquals(0, Float16VectorCodec.decode(ByteArray(0)).size)
    }

    @Test
    fun backendPreferenceParsesWireAndFallsBackToAuto() {
        assertEquals(InferenceBackend.AUTO, InferenceBackend.from("auto"))
        assertEquals(InferenceBackend.CPU, InferenceBackend.from("cpu"))
        assertEquals(InferenceBackend.GPU, InferenceBackend.from("gpu"))
        assertEquals(InferenceBackend.NPU, InferenceBackend.from("npu"))
        // 未知或空偏好一律回落 AUTO，偏好损坏不能影响引擎可用性。
        assertEquals(InferenceBackend.AUTO, InferenceBackend.from(null))
        assertEquals(InferenceBackend.AUTO, InferenceBackend.from("tpu"))
    }

    @Test
    fun backendChainAutoTriesGpuThenNpuThenCpu() {
        assertEquals(
            listOf(InferenceBackend.GPU, InferenceBackend.NPU, InferenceBackend.CPU),
            EmbeddingBackendPolicy.chain(InferenceBackend.AUTO),
        )
    }

    @Test
    fun backendChainFallsBackToCpuAfterExplicitChoice() {
        // 显式选择先试所选、失败回退 CPU；选 CPU 时不重复追加。
        assertEquals(listOf(InferenceBackend.CPU), EmbeddingBackendPolicy.chain(InferenceBackend.CPU))
        assertEquals(
            listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            EmbeddingBackendPolicy.chain(InferenceBackend.GPU),
        )
        assertEquals(
            listOf(InferenceBackend.NPU, InferenceBackend.CPU),
            EmbeddingBackendPolicy.chain(InferenceBackend.NPU),
        )
    }

    @Test
    fun backendResolvePicksFirstSuccessInChain() {
        // failures 逐项记录每次尝试结果（true = 该次初始化失败），首个 false 即最终生效后端。
        assertEquals(
            EmbeddingBackendPolicy.Selection(InferenceBackend.GPU, fellBack = false),
            EmbeddingBackendPolicy.resolve(InferenceBackend.AUTO, listOf(false)),
        )
        assertEquals(
            EmbeddingBackendPolicy.Selection(InferenceBackend.NPU, fellBack = true),
            EmbeddingBackendPolicy.resolve(InferenceBackend.AUTO, listOf(true, false)),
        )
        assertEquals(
            EmbeddingBackendPolicy.Selection(InferenceBackend.CPU, fellBack = true),
            EmbeddingBackendPolicy.resolve(InferenceBackend.AUTO, listOf(true, true, false)),
        )
        // 显式 GPU 失败后落到 CPU，同样标注回退。
        assertEquals(
            EmbeddingBackendPolicy.Selection(InferenceBackend.CPU, fellBack = true),
            EmbeddingBackendPolicy.resolve(InferenceBackend.GPU, listOf(true, false)),
        )
        assertEquals(
            EmbeddingBackendPolicy.Selection(InferenceBackend.NPU, fellBack = false),
            EmbeddingBackendPolicy.resolve(InferenceBackend.NPU, listOf(false)),
        )
    }

    @Test
    fun backendResolveReturnsNullWhenAllCandidatesFail() {
        // 全部失败 / 无尝试 / 尝试超出链长都返回 null，由引擎抛最后一次异常。
        assertNull(EmbeddingBackendPolicy.resolve(InferenceBackend.AUTO, listOf(true, true, true)))
        assertNull(EmbeddingBackendPolicy.resolve(InferenceBackend.CPU, listOf(true)))
        assertNull(EmbeddingBackendPolicy.resolve(InferenceBackend.AUTO, emptyList()))
        assertNull(EmbeddingBackendPolicy.resolve(InferenceBackend.GPU, listOf(true, true, false)))
    }
}
