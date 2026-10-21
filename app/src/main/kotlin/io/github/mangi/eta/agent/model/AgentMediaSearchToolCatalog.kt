package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明设备媒体语义搜索工具。
 *
 * 与 `search_media`（相册按文件名检索，Root）互不重叠：这里做**自然语言语义检索**，
 * 覆盖本机图片/视频/文件的本地向量索引，结果里的 `path` / `content_uri`
 * 可直接喂给 `read_image`、终端文件工具等已有能力链式使用。
 */
internal object AgentMediaSearchToolCatalog {
    const val SEARCH_MEDIA = "semantic_search_media"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = SEARCH_MEDIA,
                description = "Search the user's local media and documents by natural language " +
                    "(photos, videos and files indexed on-device with a multimodal embedding model, fully offline). " +
                    "action=search finds items matching the query; kinds filters by media type and limit caps the result count (default 10); " +
                    "min_score drops hits below the given similarity threshold. " +
                    "action=find_similar finds items similar to one reference item, given its content_uri or path from a previous result. " +
                    "action=list_sources lists configured scan scopes with their counts. " +
                    "action=index_status reports index size, model and whether the index is usable. " +
                    "action=reindex starts an incremental rebuild of the index. " +
                    "action=set_threshold stores the user's preferred similarity threshold in [0, 1] and returns it, so later calls " +
                    "can omit min_score; it uses the same preference as the media search screen's threshold slider. " +
                    "action=batch_act moves or copies search results into a folder: operation is move or copy, target_dir is a SAF tree uri " +
                    "(content://.../tree/...) or an absolute path, and source_keys are the source_key values from a previous search. " +
                    "When source_keys is omitted, batch_act re-runs the given query with kinds/min_score/limit (default 20, max 50) and acts on those hits. " +
                    "move relocates the originals (they leave their old location and reappear in the new one after the next incremental index); " +
                    "copy keeps the originals and writes new copies. " +
                    "Chained example: search(query=\"birthday at the beach\", min_score=0.7) -> take each result's source_key -> " +
                    "batch_act(operation=\"move\", target_dir=\"content://.../tree/...\", source_keys=[\"...\"]). " +
                    "Every result carries source_key plus path and content_uri, which other tools can use directly: read_image to view a photo " +
                    "or video frame, terminal file tools to open or copy items, eta_manage_history to link items to a conversation. " +
                    "Individual items that cannot be moved/copied (for example missing write permission) are reported per item with code " +
                    "PERMISSION_DENIED while the rest of the batch continues. " +
                    "If the index is not set up yet, the call fails with code MEDIA_INDEX_NOT_READY; report that and guide the user " +
                    "to the media search settings instead of guessing results.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("search")
                                            .put("find_similar")
                                            .put("list_sources")
                                            .put("index_status")
                                            .put("reindex")
                                            .put("set_threshold")
                                            .put("batch_act"),
                                    )
                                    .put("description", "Required. What to do."),
                            )
                            .put(
                                "query",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 500)
                                    .put(
                                        "description",
                                        "action=search: natural language query, e.g. \"blowing out birthday candles at the beach\". " +
                                            "action=batch_act: used only when source_keys is omitted, to select the items to act on.",
                                    ),
                            )
                            .put(
                                "reference",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 1000)
                                    .put("description", "action=find_similar only: content_uri or path of a previously found item."),
                            )
                            .put(
                                "kinds",
                                JSONObject()
                                    .put("type", "array")
                                    .put("items", JSONObject().put("type", "string").put("enum", JSONArray().put("image").put("video").put("audio").put("document")))
                                    .put("description", "Optional media type filter for search/find_similar/batch_act."),
                            )
                            .put(
                                "limit",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1)
                                    .put("maximum", 50)
                                    .put(
                                        "description",
                                        "Max results for search/find_similar (default 10) and for the re-run query of batch_act " +
                                            "(default 20). Hard cap 50.",
                                    ),
                            )
                            .put(
                                "min_score",
                                JSONObject()
                                    .put("type", "number")
                                    .put("minimum", 0)
                                    .put("maximum", 1)
                                    .put(
                                        "description",
                                        "Minimum similarity threshold in [0, 1]; hits below it are not returned. " +
                                            "Used by action=search and by the re-run query of batch_act when source_keys is omitted. " +
                                            "action=set_threshold requires this value. When omitted, the preference saved by " +
                                            "action=set_threshold is applied (0, no filter, if never set).",
                                    ),
                            )
                            .put(
                                "operation",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("move").put("copy"))
                                    .put(
                                        "description",
                                        "action=batch_act only: move relocates the originals (old index rows are removed; items reappear " +
                                            "at the new location after the next incremental index), copy keeps the originals.",
                                    ),
                            )
                            .put(
                                "target_dir",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 2000)
                                    .put(
                                        "description",
                                        "action=batch_act only, required: destination folder as a SAF tree uri " +
                                            "(content://.../tree/...) or an absolute filesystem path.",
                                    ),
                            )
                            .put(
                                "source_keys",
                                JSONObject()
                                    .put("type", "array")
                                    .put("items", JSONObject().put("type", "string"))
                                    .put(
                                        "description",
                                        "action=batch_act only: source_key values from a previous search result. " +
                                            "When omitted, batch_act re-runs query with kinds/min_score/limit and acts on those hits.",
                                    ),
                            ),
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}
