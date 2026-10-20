package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.media.index.EmbeddingModelStore
import io.github.mangi.eta.agent.media.index.MediaIndexer
import io.github.mangi.eta.agent.media.index.MediaIndexService
import io.github.mangi.eta.agent.media.index.MediaSearchService
import io.github.mangi.eta.agent.media.index.MediaSourceScanner
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * `semantic_search_media` 执行器：本地多模态语义检索（EmbeddingGemma 2，全程离线）。
 *
 * GUI 搜索框与本工具共用 [MediaSearchService]，保证「用户搜到的」与「AI 搜到的」一致；
 * 结果里的 path / content_uri 可直接被 read_image、终端文件工具等已有能力链式使用。
 */
internal class AgentMediaSearchTools(private val context: Context) {

    fun execute(args: JSONObject): String = io {
        try {
            when (val action = args.optString("action").trim()) {
                "search" -> search(args)
                "find_similar" -> findSimilar(args)
                "list_sources" -> listSources()
                "index_status" -> indexStatus()
                "reindex" -> reindex()
                else -> error("INVALID_ARGUMENT", "action 取值无效：$action")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (invalid: IllegalArgumentException) {
            error("INVALID_ARGUMENT", invalid.message ?: "参数无效")
        } catch (failure: Throwable) {
            error("INTERNAL_ERROR", failure.message ?: "媒体语义检索失败")
        }
    }

    private suspend fun search(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return error("INVALID_ARGUMENT", "action=search 需要 query")
        // 参数先于就绪状态校验，保证非法 kinds 一律报 INVALID_ARGUMENT。
        val kinds = parseKinds(args)
        notReady()?.let { return it }
        val result = MediaSearchService.search(
            context,
            query = query,
            kinds = kinds,
            limit = parseLimit(args),
        )
        return JSONObject()
            .put("ok", true)
            .put("action", "search")
            .put("query", result.query)
            .put("model", result.modelId)
            .put("dim", result.dim)
            .put("count", result.hits.size)
            .put("total_candidates", result.totalCandidates)
            .put("results", JSONArray().also { array -> result.hits.forEach { array.put(hitJson(it)) } })
            .toString()
    }

    private suspend fun findSimilar(args: JSONObject): String {
        val reference = args.optString("reference").trim()
        if (reference.isEmpty()) return error("INVALID_ARGUMENT", "action=find_similar 需要 reference")
        // 参数先于就绪状态校验，保证非法 kinds 一律报 INVALID_ARGUMENT。
        val kinds = parseKinds(args)
        notReady()?.let { return it }
        val result = MediaSearchService.findSimilar(
            context,
            reference = reference,
            kinds = kinds,
            limit = parseLimit(args),
        )
        return JSONObject()
            .put("ok", true)
            .put("action", "find_similar")
            .put("reference", reference)
            .put("model", result.modelId)
            .put("dim", result.dim)
            .put("count", result.hits.size)
            .put("results", JSONArray().also { array -> result.hits.forEach { array.put(hitJson(it)) } })
            .toString()
    }

    private suspend fun listSources(): String {
        val sources = EtaDatabase.get(context).mediaIndexDao().sources()
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("type", source.type)
                    .put("label", source.label)
                    .put("uri", source.uri)
                    .put("enabled", source.enabled)
                    .put("item_count", source.itemCount)
                    .put("last_scan_at", source.lastScanAt ?: JSONObject.NULL),
            )
        }
        // 已登记但尚未配置的推荐源一并列出，方便模型引导用户完成首次设置。
        val configured = sources.map { it.id }.toSet()
        MediaSourceScanner.recommendedSources()
            .filter { it.id !in configured }
            .forEach { source ->
                array.put(
                    JSONObject()
                        .put("id", source.id)
                        .put("type", source.type)
                        .put("label", source.label)
                        .put("enabled", false)
                        .put("configured", false),
                )
            }
        return JSONObject()
            .put("ok", true)
            .put("action", "list_sources")
            .put("count", array.length())
            .put("sources", array)
            .toString()
    }

    private suspend fun indexStatus(): String {
        val status = MediaSearchService.status(context)
        val progress = MediaIndexer.state.value
        return JSONObject()
            .put("ok", true)
            .put("action", "index_status")
            .put("ready", status.ready)
            .put("model", status.modelId)
            .put("model_downloaded", status.modelDownloaded)
            .put("dim", status.dim)
            .put("indexed_count", status.indexedCount)
            .put("last_indexed_at", status.lastIndexedAt ?: JSONObject.NULL)
            .put("enabled_sources", status.enabledSources)
            .put("indexing", status.indexing)
            .put("index_mode", EmbeddingModelStore.indexMode(context).wire)
            // 增量语义标注：已索引且未变化的条目计 skipped、消失条目计 removed，均不会重复处理已索引内容。
            .put("incremental", true)
            .put(
                "progress",
                JSONObject()
                    .put("phase", progress.phase)
                    .put("processed", progress.processed)
                    .put("total", progress.total)
                    .put("indexed", progress.indexed)
                    .put("skipped", progress.skipped)
                    .put("failed", progress.failed)
                    .put("removed", progress.removed),
            )
            .toString()
    }

    /** reindex 是长任务：只负责触发（交 [MediaIndexService] 按存储的索引档位后台执行），进度经 action=index_status 轮询。 */
    private fun reindex(): String {
        val mode = EmbeddingModelStore.indexMode(context)
        if (MediaIndexer.isRunning()) {
            return JSONObject()
                .put("ok", true)
                .put("action", "reindex")
                .put("started", false)
                .put("index_mode", mode.wire)
                .put("note", "索引任务已在进行中（增量索引，已索引且未变化的条目会自动跳过），稍后用 action=index_status 查询进度")
                .toString()
        }
        MediaIndexService.start(context)
        return JSONObject()
            .put("ok", true)
            .put("action", "reindex")
            .put("started", true)
            .put("index_mode", mode.wire)
            .put("note", "后台增量索引已启动（模式：${mode.wire}），已索引且未变化的条目会自动跳过；用 action=index_status 查询进度")
            .toString()
    }

    /** 未就绪时返回结构化错误，就绪时返回 null。 */
    private suspend fun notReady(): String? {
        val status = MediaSearchService.status(context)
        return when {
            !status.modelDownloaded -> error(
                "MEDIA_INDEX_NOT_READY",
                "嵌入模型尚未下载。请提示用户到「媒体搜索」设置页下载模型并确认扫描范围。",
            )
            status.enabledSources == 0 -> error(
                "MEDIA_INDEX_NOT_READY",
                "尚未确认扫描范围。请提示用户到「媒体搜索」设置页确认要索引的范围。",
            )
            else -> null
        }
    }

    private fun parseKinds(args: JSONObject): Set<MediaKind> {
        val raw = args.optJSONArray("kinds") ?: return emptySet()
        val kinds = mutableSetOf<MediaKind>()
        for (index in 0 until raw.length()) {
            val wire = raw.optString(index).trim()
            val kind = MediaKind.from(wire)
                ?: throw IllegalArgumentException("kinds 取值无效：$wire（可选 image / video / audio / document）")
            kinds += kind
        }
        return kinds
    }

    private fun parseLimit(args: JSONObject): Int =
        if (args.has("limit")) args.optInt("limit", 10).coerceIn(1, 50) else DEFAULT_LIMIT

    private fun hitJson(hit: MediaSearchService.Hit): JSONObject {
        val item = JSONObject()
            .put("kind", hit.kind.wire)
            .put("display_name", hit.displayName)
            .put("mime", hit.mime)
            .put("score", hit.score.toDouble())
            .put("size_bytes", hit.sizeBytes)
            .put("modified_at", hit.modifiedAt)
        hit.contentUri?.let { item.put("content_uri", it) }
        hit.path?.let { item.put("path", it) }
        hit.durationMs?.let { item.put("duration_ms", it) }
        hit.width?.let { item.put("width", it) }
        hit.height?.let { item.put("height", it) }
        // 视频「时刻检索」的命中时刻（毫秒）；二段精排未命中/未执行时省略。
        hit.frameTimeMs?.let { item.put("matched_at_ms", it) }
        return item
    }

    private fun io(block: suspend () -> String): String = runBlocking(Dispatchers.IO) { block() }

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private companion object {
        const val DEFAULT_LIMIT = 10
    }
}
