package io.github.mangi.eta.agent.media.index

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaIndexEntity
import io.github.mangi.eta.data.db.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 语义检索唯一入口：GUI 搜索框与 `semantic_search_media` 工具都走这里，
 * 保证「用户搜到的」与「AI 搜到的」结果完全一致。
 *
 * 检索实现收敛到 [vectorIndex]（当前为 [BruteForceVectorIndex] 暴力点积）：万级条目在手机上
 * 可接受；规模超过 [ANN_SWITCH_THRESHOLD] 后提示并由后续版本换 ANN（HNSW/sqlite-vec）实现，
 * 对外接口保持不变。
 */
internal object MediaSearchService {
    /**
     * 暴力检索规模阈值：条目数超过该值后在 Status/设置页给出「后续版本切换 ANN」提示。
     * 真实 ANN 索引（HNSW/sqlite-vec）不在本期，只预留切换点（见 [VectorIndex]）。
     */
    const val ANN_SWITCH_THRESHOLD = 50_000

    /** 检索实现入口：当前为暴力点积；规模超阈值后在此切换 ANN 实现，调用方不变。 */
    private val vectorIndex: VectorIndex = BruteForceVectorIndex

    /**
     * 检索结果一行；[contentUri] / [path] 可直接被 read_image、终端文件工具链式使用。
     * [frameTimeMs] 为视频「时刻检索」的命中时刻（毫秒）：二段精排逐帧比对后回填，无值表示未精排。
     */
    data class Hit(
        val sourceKey: String,
        val kind: MediaKind,
        val displayName: String,
        val mime: String,
        val score: Float,
        val contentUri: String?,
        val path: String?,
        val sizeBytes: Long,
        val modifiedAt: Long,
        val durationMs: Long?,
        val width: Int?,
        val height: Int?,
        val thumbPath: String?,
        val frameTimeMs: Long? = null,
    )

    data class SearchResult(
        val query: String,
        val hits: List<Hit>,
        val totalCandidates: Int,
        val modelId: String,
        val dim: Int,
    )

    data class Status(
        val modelDownloaded: Boolean,
        val modelId: String,
        val dim: Int,
        val indexedCount: Int,
        val lastIndexedAt: Long?,
        val enabledSources: Int,
        val indexing: Boolean,
    ) {
        /** 模型就绪且至少配置过一个扫描源才算「可检索」。 */
        val ready: Boolean get() = modelDownloaded && enabledSources > 0

        /**
         * 条目规模已超过暴力检索阈值（[ANN_SWITCH_THRESHOLD]）：暴力点积仍可用但偏慢，
         * 后续版本切换 ANN。仅该字段为 true 时 UI 才显示提示。
         */
        val approachingAnnSwitch: Boolean
            get() = MediaSearchService.exceedsAnnSwitchThreshold(indexedCount)
    }

    /** 纯函数：条目规模是否已超过暴力检索阈值（超过才提示后续切换 ANN）。 */
    internal fun exceedsAnnSwitchThreshold(indexedCount: Int): Boolean = indexedCount > ANN_SWITCH_THRESHOLD

    suspend fun status(context: Context): Status = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val model = EmbeddingModelStore.state.value.model
        val dim = EmbeddingModelStore.vectorDim(app)
        val dao = EtaDatabase.get(app).mediaIndexDao()
        Status(
            modelDownloaded = EmbeddingModelStore.modelFile(app) != null,
            modelId = model.id,
            dim = dim,
            indexedCount = dao.countForModel(model.id, dim),
            lastIndexedAt = dao.lastIndexedAt(),
            enabledSources = dao.sources().count { it.enabled },
            indexing = MediaIndexer.isRunning(),
        )
    }

    /**
     * 自然语言搜索：query 走 query 前缀嵌入，与库中归一化向量做点积取 top-K。
     * 入库是「每视频一行平均向量」，命中视频后可选做二段时刻精排（[refineMoments]）：
     * 重新抽帧逐帧比对，把最高分帧的时刻回填到 [Hit.frameTimeMs]。
     * 精排条数与重抽帧预算按 [IndexModeProfile] 取值（全力档 top-5 / 低耗档 top-3）。
     */
    suspend fun search(
        context: Context,
        query: String,
        kinds: Set<MediaKind> = emptySet(),
        limit: Int = 10,
        refineMoments: Boolean = true,
    ): SearchResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        require(query.isNotBlank()) { "query 不能为空" }
        val model = EmbeddingModelStore.state.value.model
        val dim = EmbeddingModelStore.vectorDim(app)
        val queryVector = EtaEmbeddingEngine.embedQuery(app, query, dim)
        val dao = EtaDatabase.get(app).mediaIndexDao()
        val rows = dao.listForModel(model.id, dim)
        val ranked = rank(queryVector, rows, kinds, limit)
        // 二段精排预算按索引模式取值：全力档 top-5，低耗档 top-3（逐帧嵌入较贵）。
        val profile = IndexModeProfile.forMode(EmbeddingModelStore.indexMode(app))
        var refined = 0
        val hits = ranked
            .map { (row, score) -> hitOf(app, row, score) }
            .map { hit ->
                val shouldRefine = refineMoments &&
                    hit.kind == MediaKind.VIDEO &&
                    model.modalities.vision &&
                    refined < profile.refineTopK
                if (shouldRefine) {
                    refined++
                    refineMoment(app, queryVector, dim, hit, profile.videoFrames) ?: hit
                } else {
                    hit
                }
            }
        SearchResult(
            query = query.trim(),
            hits = hits,
            totalCandidates = rows.size,
            modelId = model.id,
            dim = dim,
        )
    }

    /**
     * 二段精排：对视频命中重新抽帧（含时刻戳，最多 [maxFrames] 帧，按索引模式取值）逐帧嵌入，
     * 取与查询点积最大帧的时刻。只精排结果里前 [IndexModeProfile.refineTopK] 个视频命中；
     * 引擎不可用/抽取失败一律静默跳过（[Hit.frameTimeMs] 保持 null），不影响其余结果。
     */
    private suspend fun refineMoment(
        context: Context,
        queryVector: FloatArray,
        dim: Int,
        hit: Hit,
        maxFrames: Int,
    ): Hit? {
        return runCatching {
            val candidate = MediaSourceScanner.Candidate(
                sourceKey = hit.sourceKey,
                kind = MediaKind.VIDEO,
                displayName = hit.displayName,
                mime = hit.mime,
                sizeBytes = hit.sizeBytes,
                modifiedAt = hit.modifiedAt,
                contentUri = hit.contentUri,
                filePath = hit.path,
                durationMs = hit.durationMs,
                width = hit.width,
                height = hit.height,
            )
            val frames = MediaExtraction.videoFrames(context, candidate, maxFrames)
            if (frames.isEmpty()) return@runCatching null
            val scored = frames.map { frame ->
                frame.timeMs to EtaEmbeddingEngine.embedImage(context, frame.bytes, dim)
            }
            bestMoment(queryVector, scored)?.let { timeMs -> hit.copy(frameTimeMs = timeMs) }
        }.getOrNull()
    }

    /** 二段精排选帧（纯逻辑）：取与查询点积最大的帧时刻；无帧可比返回 null。 */
    internal fun bestMoment(query: FloatArray, frames: List<Pair<Long, FloatArray>>): Long? =
        frames
            .map { (timeMs, vector) -> timeMs to if (vector.size == query.size) dot(query, vector) else Float.NEGATIVE_INFINITY }
            .maxByOrNull { (_, score) -> score }
            ?.takeIf { (_, score) -> score > Float.NEGATIVE_INFINITY }
            ?.first

    /** 「找相似」：以某条目自身向量为参考找相近媒体；[reference] 用结果里的 content_uri 或 path。 */
    suspend fun findSimilar(
        context: Context,
        reference: String,
        kinds: Set<MediaKind> = emptySet(),
        limit: Int = 10,
    ): SearchResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        require(reference.isNotBlank()) { "reference 不能为空" }
        val model = EmbeddingModelStore.state.value.model
        val dim = EmbeddingModelStore.vectorDim(app)
        val dao = EtaDatabase.get(app).mediaIndexDao()
        val rows = dao.listForModel(model.id, dim)
        val referenceRow = rows.firstOrNull { it.sourceKey == reference }
            ?: dao.findBySourceKey(reference).firstOrNull { it.modelId == model.id && it.vectorDim == dim }
            ?: throw IllegalArgumentException("找不到已索引的参考项：$reference")
        val referenceVector = Float16VectorCodec.decode(referenceRow.vector)
        val ranked = rank(referenceVector, rows.filter { it.sourceKey != referenceRow.sourceKey }, kinds, limit)
        SearchResult(
            query = "similar:${referenceRow.displayName}",
            hits = ranked.map { (row, score) -> hitOf(app, row, score) },
            totalCandidates = rows.size,
            modelId = model.id,
            dim = dim,
        )
    }

    /**
     * 归一化点积排名；纯函数便于单测（构造向量即可验证排名与过滤）。
     * 过滤维度由调用方保证（SQL 已按 vector_dim 过滤），这里再按 kind 过滤。
     * 实现已收敛到 [vectorIndex] 单一入口（当前为 [BruteForceVectorIndex]）。
     */
    internal fun rank(
        query: FloatArray,
        rows: List<MediaIndexEntity>,
        kinds: Set<MediaKind> = emptySet(),
        limit: Int = 10,
    ): List<Pair<MediaIndexEntity, Float>> = vectorIndex.search(query, rows, kinds, limit)

    internal fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** 索引行 → 检索结果；[row.frameTimeMs] 有值（帧级行）时直接带出命中时刻。 */
    internal fun hitOf(
        context: Context,
        row: MediaIndexEntity,
        score: Float,
    ): Hit = Hit(
        sourceKey = row.sourceKey,
        kind = MediaKind.from(row.kind) ?: MediaKind.DOCUMENT,
        displayName = row.displayName,
        mime = row.mime,
        score = score,
        contentUri = row.sourceKey.takeIf { it.startsWith("content://") },
        path = row.sourceKey.takeIf { it.startsWith("/") } ?: resolvePath(context, row.sourceKey),
        sizeBytes = row.sizeBytes,
        modifiedAt = row.modifiedAt,
        durationMs = row.durationMs,
        width = row.width,
        height = row.height,
        thumbPath = row.thumbPath,
        frameTimeMs = row.frameTimeMs,
    )

    /** MediaStore 条目补一个文件系统路径，方便终端类工具直接链式使用。 */
    private fun resolvePath(context: Context, sourceKey: String): String? = runCatching {
        context.contentResolver.query(
            Uri.parse(sourceKey),
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}

/**
 * 向量检索抽象：暴力检索与未来 ANN 的统一入口。
 *
 * **ANN 接入点（HNSW / sqlite-vec）**：新增本接口的实现类（如 `AnnVectorIndex`），
 * 在 [MediaSearchService] 的 `vectorIndex` 处替换实例即可，`search` / `findSimilar`
 * 与工具层调用方无需改动；实现需自行维护「入库增量建索引 / 清理」的生命周期。
 * 切换时机由 [MediaSearchService.ANN_SWITCH_THRESHOLD] 标定：超过阈值后 UI 提示，
 * 随后把 [BruteForceVectorIndex] 换成 ANN 实现。
 *
 * 实现约定：query 与库内向量均已归一化（余弦相似度 = 点积），返回按分数降序、
 * 长度不超过 limit；[kinds] 为空不过滤。
 */
internal interface VectorIndex {
    fun search(
        query: FloatArray,
        rows: List<MediaIndexEntity>,
        kinds: Set<MediaKind>,
        limit: Int,
    ): List<Pair<MediaIndexEntity, Float>>
}

/**
 * 暴力点积实现：全量解码 Float16 后逐条点积排序，万级条目在手机上可接受。
 * 规模超过 [MediaSearchService.ANN_SWITCH_THRESHOLD] 后仍可用，仅提示后续切换 ANN。
 */
internal object BruteForceVectorIndex : VectorIndex {
    override fun search(
        query: FloatArray,
        rows: List<MediaIndexEntity>,
        kinds: Set<MediaKind>,
        limit: Int,
    ): List<Pair<MediaIndexEntity, Float>> {
        val effectiveLimit = limit.coerceIn(1, 100)
        return rows.asSequence()
            .filter { row -> kinds.isEmpty() || MediaKind.from(row.kind) in kinds }
            .mapNotNull { row ->
                val vector = runCatching { Float16VectorCodec.decode(row.vector) }.getOrNull()
                    ?: return@mapNotNull null
                if (vector.size != query.size) return@mapNotNull null
                row to MediaSearchService.dot(query, vector)
            }
            .sortedByDescending { it.second }
            .take(effectiveLimit)
            .toList()
    }
}
