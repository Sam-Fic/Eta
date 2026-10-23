package io.github.mangi.eta.agent.media

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentAppContext
import org.json.JSONArray

/**
 * 工具读回/生成的图片是瞬时观察，下一次推理后原始字节就会被丢弃；
 * 工具卡片要展示的是一份独立的小预览，因此单独编码成 512px JPEG data URL，
 * 随工具完成事件进入会话记录。与聊天附件预览同一档位，不参与模型请求。
 */
internal object AgentToolMediaPreviews {
    const val EMPTY = "[]"

    /** 一张卡片最多展示三张，避免多图工具把事件与数据库撑大。 */
    const val MAX_PREVIEWS = 3

    /** 预览本身是 512px JPEG，超过这个长度的（异常大图）宁可不展示。 */
    private const val MAX_PREVIEW_CHARS = 220_000

    fun encode(images: List<AgentModelClient.ModelImage>): String {
        if (images.isEmpty()) return EMPTY
        val context = AgentAppContext.resolve() ?: return EMPTY
        val previews = images.take(MAX_PREVIEWS).mapNotNull { image ->
            runCatching { AgentImageCodec.previewFromReference(context, image) }.getOrNull()?.reference
        }
        return encodePreviews(previews)
    }

    /** UI 侧回写会话记录时只处理已经是预览 data URL 的字符串。 */
    fun encodePreviews(previews: List<String>): String {
        val accepted = previews.asSequence()
            .map(String::trim)
            .filter { it.startsWith("data:image/", ignoreCase = true) }
            .filter { it.length <= MAX_PREVIEW_CHARS }
            .take(MAX_PREVIEWS)
            .toList()
        if (accepted.isEmpty()) return EMPTY
        val array = JSONArray()
        accepted.forEach(array::put)
        return array.toString()
    }

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw == EMPTY) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val source = array.optString(index).trim()
                if (source.startsWith("data:image/", ignoreCase = true)) add(source)
                if (size >= MAX_PREVIEWS) break
            }
        }
    }
}
