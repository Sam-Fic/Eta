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
                    "action=search finds items matching the query; kinds filters by media type and limit caps the result count (default 10). " +
                    "action=find_similar finds items similar to one reference item, given its content_uri or path from a previous result. " +
                    "action=list_sources lists configured scan scopes with their counts. " +
                    "action=index_status reports index size, model and whether the index is usable. " +
                    "action=reindex starts an incremental rebuild of the index. " +
                    "Results carry path and content_uri that other tools can use directly: read_image to view a photo or video frame, " +
                    "terminal file tools to copy/move/open, eta_manage_history to link items to a conversation. " +
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
                                            .put("reindex"),
                                    )
                                    .put("description", "Required. What to do."),
                            )
                            .put(
                                "query",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 500)
                                    .put("description", "action=search only: natural language query, e.g. \"blowing out birthday candles at the beach\"."),
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
                                    .put("description", "Optional media type filter for search/find_similar."),
                            )
                            .put(
                                "limit",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1)
                                    .put("maximum", 50)
                                    .put("description", "Max results for search/find_similar. Default 10."),
                            ),
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}
