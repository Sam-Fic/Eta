package io.github.mangi.eta.agent.media.index

/**
 * 端侧多模态 embedding 模型目录（EmbeddingGemma 2，Apache 2.0）。
 *
 * 每个变体固定一个上游 revision 与 `.litertlm` 文件的字节数、SHA-256；
 * 下载源只是同一份字节的不同镜像，落盘后一律按哈希全量校验，禁止引入未校验的二进制。
 */
internal object EmbeddingModelCatalog {
    /** 模型变体的模态能力；决定检索页允许出现哪些类型过滤项。 */
    data class Modalities(val vision: Boolean, val audio: Boolean) {
        companion object {
            val OMNIMODAL = Modalities(vision = true, audio = true)
            val TEXT_ONLY = Modalities(vision = false, audio = false)
        }
    }

    /** 下载源；不同枚举常量保证偏好可读，后续新增镜像只需扩展此处。 */
    enum class SourceId(val wire: String) {
        HUGGING_FACE("huggingface"),
        HF_MIRROR("hf_mirror");

        companion object {
            fun from(wire: String?): SourceId? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class Asset(val name: String, val bytes: Long, val sha256: String)

    /** [baseUrl] 已内嵌固定 revision，且以 `/` 结尾。 */
    data class Source(val id: SourceId, val baseUrl: String)

    data class Model(
        val id: String,
        val revision: String,
        val modalities: Modalities,
        val assets: List<Asset>,
        val sources: List<Source>,
    ) {
        val totalBytes: Long = assets.sumOf { it.bytes }

        /** 未知或空来源回退到第一个来源，偏好损坏不能影响下载可用性。 */
        fun source(id: SourceId?): Source = sources.firstOrNull { it.id == id } ?: sources.first()
    }

    /** 稳定模型 id，同时是设备目录的第一段路径。 */
    const val OMNIMODAL_ID = "embeddinggemma-2-740m"
    const val TEXT_ONLY_ID = "embeddinggemma-2-text-270m"

    private const val OMNIMODAL_REVISION = "24d962e906c7d332c6428e71c9676855024569e2"
    private const val TEXT_ONLY_REVISION = "9be6e8b90982095dc05c2bd162e4b954ee4dbac7"

    private const val OMNIMODAL_FILE = "embeddinggemma-2-740m.litertlm"
    private const val TEXT_ONLY_FILE = "embeddinggemma-2-text-270m.litertlm"

    private val omnimodal740m = Model(
        id = OMNIMODAL_ID,
        revision = OMNIMODAL_REVISION,
        modalities = Modalities.OMNIMODAL,
        sources = listOf(
            Source(
                SourceId.HUGGING_FACE,
                "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/$OMNIMODAL_REVISION/",
            ),
            Source(
                SourceId.HF_MIRROR,
                "https://hf-mirror.com/litert-community/embeddinggemma-2-740m-litert-lm/resolve/$OMNIMODAL_REVISION/",
            ),
        ),
        assets = listOf(
            Asset(
                OMNIMODAL_FILE,
                484_622_336L,
                "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c",
            ),
        ),
    )

    private val textOnly270m = Model(
        id = TEXT_ONLY_ID,
        revision = TEXT_ONLY_REVISION,
        modalities = Modalities.TEXT_ONLY,
        sources = listOf(
            Source(
                SourceId.HUGGING_FACE,
                "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/$TEXT_ONLY_REVISION/",
            ),
            Source(
                SourceId.HF_MIRROR,
                "https://hf-mirror.com/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/$TEXT_ONLY_REVISION/",
            ),
        ),
        assets = listOf(
            Asset(
                TEXT_ONLY_FILE,
                164_626_432L,
                "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
            ),
        ),
    )

    val models: List<Model> = listOf(omnimodal740m, textOnly270m)

    /** 低内存判定阈值：物理内存低于 4 GiB 视为低内存设备，与 isLowRamDevice 取或。 */
    const val LOW_MEMORY_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

    /** 纯函数：低内存判定（[android.app.ActivityManager.isLowRamDevice] 或 totalMem 低于阈值）。 */
    fun isLowMemory(isLowRamDevice: Boolean, totalMemBytes: Long): Boolean =
        isLowRamDevice || totalMemBytes < LOW_MEMORY_TOTAL_BYTES

    /**
     * 默认变体：低内存设备降级纯文本 270M 减负（图像/视频/音频按 modalities 门禁跳过），
     * 其余默认全模态 740M。用户显式选择后以存储偏好为准，可随时手动切回 740M。
     */
    fun defaultModel(lowMemory: Boolean = false): Model = if (lowMemory) textOnly270m else omnimodal740m

    /** 未知或空 id 一律回落默认模型（含低内存降级），偏好损坏不能让启动失败。 */
    fun model(id: String?, lowMemory: Boolean = false): Model =
        models.firstOrNull { it.id == id } ?: defaultModel(lowMemory)
}
