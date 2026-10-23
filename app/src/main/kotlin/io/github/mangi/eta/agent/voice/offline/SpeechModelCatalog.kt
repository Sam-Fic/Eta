package io.github.mangi.eta.agent.voice.offline

/**
 * Immutable on-device speech model catalog.
 *
 * Every source embeds a pinned revision (a mirror may use a different commit than the
 * upstream repository) and each file is checked by exact byte length and SHA-256. A model
 * may be served from more than one source; the verified bytes are identical, so switching
 * sources reuses the same directory. [Asset.url] overrides the repository URL for files
 * hosted elsewhere, such as Silero VAD. `sha256 = null` means upstream publishes no hash
 * for that text file and only the exact length is checked.
 */
internal object SpeechModelCatalog {
    /** Native engine family; decides which vendored JNI binding decodes the model. */
    enum class Engine { NCNN_TRANSDUCER, ONNX_ONLINE_ZIPFORMER2, ONNX_OFFLINE_SENSE_VOICE }

    /** Download source. Distinct enum constants keep prefs readable if more are added. */
    enum class SourceId(val wire: String) {
        HUGGING_FACE("huggingface"),
        MODEL_SCOPE("modelscope");

        companion object {
            fun from(wire: String?): SourceId? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** [url] overrides [Source.baseUrl] + [name] for files hosted outside the model repository. */
    data class Asset(val name: String, val bytes: Long, val sha256: String?, val url: String? = null)

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
    const val SENSE_VOICE_ID = "sense-voice-2025-09-09"

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

    /** SenseVoice 整句模型与配套 Silero VAD 的文件名。 */
    const val SENSE_VOICE_MODEL = "model.int8.onnx"
    const val SILERO_VAD = "silero_vad.onnx"

    const val TOKENS = "tokens.txt"

    private const val ZIPFORMER_2023_REVISION = "05945efc40afe4b572542f01104ca5c413a9f6e1"
    private const val X_ASR_REVISION = "689ff18c584d29910da37b6fe904db0c1489c9d1"
    private const val SENSE_VOICE_REVISION = "355f4d4884d8afd08aef04b9007a8556d7b463b2"
    // ModelScope 镜像由不同账号上传，按文件 SHA-256 与官方权重对齐后再固定镜像提交。
    private const val SENSE_VOICE_MIRROR_REVISION = "9c78da399b8e53c07b13a4ec40860fa05752311e"
    private const val SILERO_VAD_MIRROR_REVISION = "935742bb5963601c3bd1f4312c3e1f7e861f0cf6"
    private const val SILERO_VAD_URL =
        "https://www.modelscope.cn/models/fuyuantech/fuyuan-sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17" +
            "/resolve/$SILERO_VAD_MIRROR_REVISION/$SILERO_VAD"

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

    private val senseVoice2025 = Model(
        id = SENSE_VOICE_ID,
        revision = SENSE_VOICE_REVISION,
        engine = Engine.ONNX_OFFLINE_SENSE_VOICE,
        // SenseVoice Small int8：中 / 英 / 日 / 韩 / 粤自动检测，整句识别并做 ITN。
        // 国内网络通常直连不了 Hugging Face；ModelScope 镜像与官方仓库逐字节一致
        // （下方 SHA-256 与官方仓库 LFS oid 相同），所以默认走镜像，官方源仍可切换。
        sources = listOf(
            Source(
                SourceId.MODEL_SCOPE,
                "https://www.modelscope.cn/models/Mr7Cat/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09" +
                    "/resolve/$SENSE_VOICE_MIRROR_REVISION/",
            ),
            Source(
                SourceId.HUGGING_FACE,
                "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09" +
                    "/resolve/$SENSE_VOICE_REVISION/",
            ),
        ),
        assets = listOf(
            Asset(SENSE_VOICE_MODEL, 237_115_547L, "12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d"),
            Asset(TOKENS, 315_894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
            // VAD 不在识别模型仓库里；ModelScope 副本与官方 releases 的 silero_vad.onnx 哈希一致。
            Asset(SILERO_VAD, 643_854L, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", url = SILERO_VAD_URL),
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

    val models: List<Model> = listOf(senseVoice2025, zipformer2023, xAsrZhEn)

    val defaultModel: Model = senseVoice2025

    /** Unknown or missing ids fall back to the default model so prefs can never break startup. */
    fun model(id: String?): Model = models.firstOrNull { it.id == id } ?: defaultModel
}
