package io.github.mangi.eta.agent.media.index

import android.content.Context
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.data.db.MediaIndexEntity
import io.github.mangi.eta.data.db.MediaKind
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 媒体语义搜索测试夹具。
 *
 * 真实 `.litertlm` 模型与 LiteRT 引擎无法进入单测，这里提供：
 * - [entity]：按需构造 [MediaIndexEntity] 索引行（含损坏向量场景）；
 * - [installFakeModel]：造一份「字节数 + SHA-256 与清单自洽」的假模型文件并替换
 *   [EmbeddingModelStore] 状态，让 `modelFile()` 通过校验，从而走到索引/检索的可测分支；
 * - [writeWorkspaceFile]：在 workspace 扫描源的固定目录里放测试文件。
 *
 * 任何会触发真实推理（EtaEmbeddingEngine.compute）的分支在测试里一律避开。
 */
internal object MediaIndexTestFixtures {

    /** 假模型 id：既做目录名也做 media_index.model_id。 */
    const val FAKE_MODEL_ID = "eta-test-model"

    private const val FAKE_ASSET_NAME = "model.litertlm"

    /** 构造一行索引记录；损坏向量场景改用 [rawVector] 绕过编码。 */
    fun entity(
        sourceKey: String,
        vector: FloatArray = FloatArray(0),
        kind: MediaKind = MediaKind.IMAGE,
        modelId: String = FAKE_MODEL_ID,
        vectorDim: Int = 768,
        displayName: String = sourceKey.substringAfterLast('/'),
        mime: String = "image/png",
        sizeBytes: Long = 4_096L,
        modifiedAt: Long = 1_700_000_000_000L,
        contentHash: String = "",
        frameTimeMs: Long? = null,
        thumbPath: String? = null,
        indexedAt: Long = 1_700_000_000_001L,
        rawVector: ByteArray = Float16VectorCodec.encode(vector),
    ): MediaIndexEntity = MediaIndexEntity(
        sourceKey = sourceKey,
        modelId = modelId,
        vectorDim = vectorDim,
        kind = kind.wire,
        displayName = displayName,
        mime = mime,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        contentHash = contentHash,
        durationMs = null,
        width = null,
        height = null,
        frameTimeMs = frameTimeMs,
        thumbPath = thumbPath,
        vector = rawVector,
        indexedAt = indexedAt,
    )

    /** 单位基向量：第 [axis] 维取 [sign]，其余为 0；半精度下精确，便于点积断言。 */
    fun axisVector(dim: Int, axis: Int, sign: Float = 1f): FloatArray =
        FloatArray(dim) { index -> if (index == axis) sign else 0f }

    /**
     * 安装「已校验」的假模型：模型文件的字节数与 SHA-256 与清单自洽，
     * [EmbeddingModelStore.modelFile] 因此返回非空；推理仍不可用（引擎不进单测）。
     */
    fun installFakeModel(context: Context): EmbeddingModelCatalog.Model {
        val payload = "eta-test-embedding-model".toByteArray()
        val model = EmbeddingModelCatalog.Model(
            id = FAKE_MODEL_ID,
            revision = "test-revision",
            modalities = EmbeddingModelCatalog.Modalities.TEXT_ONLY,
            assets = listOf(
                EmbeddingModelCatalog.Asset(FAKE_ASSET_NAME, payload.size.toLong(), sha256Of(payload)),
            ),
            sources = listOf(
                EmbeddingModelCatalog.Source(
                    EmbeddingModelCatalog.SourceId.HUGGING_FACE,
                    "https://example.invalid/embedding/",
                ),
            ),
        )
        val directory = EmbeddingModelStore.directory(context, model).apply { mkdirs() }
        File(directory, FAKE_ASSET_NAME).writeBytes(payload)
        modelStateFlow().value = EmbeddingModelStore.State(
            checking = false,
            ready = true,
            model = model,
            source = model.sources.first(),
        )
        return model
    }

    /** 恢复默认状态（默认目录下没有真实模型文件），供「索引未就绪」分支用例使用。 */
    fun resetModelStore() {
        modelStateFlow().value = EmbeddingModelStore.State()
    }

    /** workspace 扫描源固定扫描终端用户工作区目录（见 MediaSourceScanner.scanWorkspace）。 */
    fun workspaceDir(context: Context): File = TerminalPrivateStorage.workspace(context.filesDir)

    /** 写一个可被扫描到的工作区文件：需 ≥ 8KB 且扩展名可识别（MIN_SIZE_BYTES / kindForName）。 */
    fun writeWorkspaceFile(
        context: Context,
        name: String,
        bytes: Int = 8_192,
        modifiedAt: Long = 1_700_000_100_000L,
    ): File {
        val directory = workspaceDir(context).apply { mkdirs() }
        val file = File(directory, name)
        file.writeBytes(ByteArray(bytes) { index -> (index % 251).toByte() })
        check(file.setLastModified(modifiedAt)) { "设置文件 mtime 失败" }
        return file
    }

    /** [EmbeddingModelStore] 的状态流是私有的，测试只能经反射替换状态值。 */
    @Suppress("UNCHECKED_CAST")
    private fun modelStateFlow(): MutableStateFlow<EmbeddingModelStore.State> {
        val field = EmbeddingModelStore::class.java.getDeclaredField("mutable")
        field.isAccessible = true
        return field.get(EmbeddingModelStore) as MutableStateFlow<EmbeddingModelStore.State>
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
