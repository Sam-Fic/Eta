package io.github.mangi.eta.agent.voice.offline

/**
 * Immutable on-device speech model catalog.
 *
 * Every model pins one upstream revision plus per-file byte lengths and SHA-256 values.
 * A model may be served from more than one download source; the bytes and hashes are the
 * same across sources (verified locally), so only the URL prefix differs. `sha256 = null`
 * means upstream publishes no hash for that text file and only the exact length is checked.
 */
internal object SpeechModelCatalog {
    /** Native engine family; decides which vendored JNI binding decodes the model. */
    enum class Engine { NCNN_TRANSDUCER, ONNX_ONLINE_ZIPFORMER2 }

    /** Download source. Distinct enum constants keep prefs readable if more are added. */
    enum class SourceId(val wire: String) {
        HUGGING_FACE("huggingface"),
        MODEL_SCOPE("modelscope");

        companion object {
            fun from(wire: String?): SourceId? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class Asset(val name: String, val bytes: Long, val sha256: String?)

    /** [baseUrl] already embeds the pinned revision and always ends with `/`. */
    data class Source(val id: SourceId, val baseUrl: String)

    data class Model(
        val id: String,
        val revision: String,
        val engine: Engine,
        val assets: List<Asset>,
        val sources: List<Source>,
    ) {
        val totalBytes: Long = assets.sumOf { it.bytes }

        /** 未知或空来源回退到该模型的第一个来源，偏好损坏也不能让启动失败。 */
        fun source(id: SourceId?): Source = sources.firstOrNull { it.id == id } ?: sources.first()
    }

    /** Stable model ids; also the first path segment of the on-device directory. */
    const val ZIPFORMER_ID = "zipformer-bilingual-2023"
    const val X_ASR_ID = "x-asr-zh-en-480ms"

    /** ncnn/pnnx transducer file names for the 2023 bilingual pack. */
    const val NCNN_ENCODER_PARAM = "encoder_jit_trace-pnnx.ncnn.param"
    const val NCNN_ENCODER_BIN = "encoder_jit_trace-pnnx.ncnn.bin"
    const val NCNN_DECODER_PARAM = "decoder_jit_trace-pnnx.ncnn.param"
    const val NCNN_DECODER_BIN = "decoder_jit_trace-pnnx.ncnn.bin"
    const val NCNN_JOINER_PARAM = "joiner_jit_trace-pnnx.ncnn.param"
    const val NCNN_JOINER_BIN = "joiner_jit_trace-pnnx.ncnn.bin"

    /** ONNX streaming Zipformer2 file names for X-ASR-zh-en chunk-480ms. */
    const val ONNX_ENCODER = "encoder-480ms.onnx"
    const val ONNX_DECODER = "decoder-480ms.onnx"
    const val ONNX_JOINER = "joiner-480ms.onnx"

    const val TOKENS = "tokens.txt"

    private const val ZIPFORMER_2023_REVISION = "05945efc40afe4b572542f01104ca5c413a9f6e1"
    private const val X_ASR_REVISION = "689ff18c584d29910da37b6fe904db0c1489c9d1"

    private val zipformer2023 = Model(
        id = ZIPFORMER_ID,
        revision = ZIPFORMER_2023_REVISION,
        engine = Engine.NCNN_TRANSDUCER,
        // No ModelScope mirror exists for this upstream repository.
        // Weight licensing is not declared by upstream metadata (see third_party notice).
        sources = listOf(
            Source(
                SourceId.HUGGING_FACE,
                "https://huggingface.co/csukuangfj/sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13/resolve/$ZIPFORMER_2023_REVISION/",
            ),
        ),
        assets = listOf(
            Asset(NCNN_DECODER_BIN, 6412296L, "dc4df2d8e1ddee1b90ac72a2de982eb1d320ee6c9a70e1dee4d23d9acfc8b978"),
            Asset(NCNN_DECODER_PARAM, 439L, "cb88f5894978fd3e85369d2f8ea55621809fceb2b5158243fb0cd025eb4f1aaf"),
            Asset(NCNN_ENCODER_BIN, 127364056L, "4ed65f05b78c0106d3d176018ab01e26a15c200604490d3d49b08cc75a122dd0"),
            Asset(NCNN_ENCODER_PARAM, 161888L, "97ad0954fb2cb4730f87a7eb66401b024f756752ece246e4b2063f870ebf3e18"),
            Asset(NCNN_JOINER_BIN, 7350724L, "0e6c4370017394de5d74128756233d2e4451209e63ac2abd525da3b089e8bee1"),
            Asset(NCNN_JOINER_PARAM, 490L, "46c339f3869136c2f6d9d9d6983a6cbc2bfbcd0e3dab0f76ae25e9477f00a360"),
            Asset(TOKENS, 56317L, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"),
        ),
    )

    private val xAsrZhEn = Model(
        id = X_ASR_ID,
        revision = X_ASR_REVISION,
        engine = Engine.ONNX_ONLINE_ZIPFORMER2,
        sources = listOf(
            Source(
                SourceId.HUGGING_FACE,
                "https://huggingface.co/GilgameshWind/X-ASR-zh-en/resolve/$X_ASR_REVISION/deployment/models/chunk-480ms-model/",
            ),
            // The ModelScope mirror is owned by a different account than the Hugging Face repo.
            // ModelScope pins files by content hash, so the moving `master` revision is still
            // verified against the same per-file SHA-256 values below.
            Source(
                SourceId.MODEL_SCOPE,
                "https://www.modelscope.ai/api/v1/models/Gilgamesh-J/X-ASR-zh-en/repo?Revision=master&FilePath=deployment/models/chunk-480ms-model/",
            ),
        ),
        assets = listOf(
            Asset(ONNX_ENCODER, 592968361L, "0c3454033d249081df124ddcd7adaf3deca07d0b999b26f2ee5d2475d37abc74"),
            Asset(ONNX_DECODER, 11309084L, "3658368d274a5d5fd39a7ac20c46bed0ad9cfea1f0feddef30d5d89797c1f499"),
            Asset(ONNX_JOINER, 10260467L, "03781c98165a2385024c9cecdd2b6b13310d81db23a62c7da420782c2915cf81"),
            Asset(TOKENS, 58806L, "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c"),
        ),
    )

    val models: List<Model> = listOf(zipformer2023, xAsrZhEn)

    val defaultModel: Model = zipformer2023

    /** Unknown or missing ids fall back to the default model so prefs can never break startup. */
    fun model(id: String?): Model = models.firstOrNull { it.id == id } ?: defaultModel
}
