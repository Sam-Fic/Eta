package io.github.mangi.eta.agent.delegation

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** Process-local reports stay separate from the bounded, sensitive tool response.
 * Offsets are UTF-16 code units and independent of supervision event sequence numbers.
 */
internal object SubAgentResultPage {
    val fields = listOf("result", "partial_result", "model_report_unverified")
    private val revisionKey by lazy { SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "HmacSHA256") }

    /** Stable across all public pages; exact UTF-16 units, keyed and process-local. No body retained. */
    fun attachRevision(record: JSONObject): JSONObject {
        val mac = Mac.getInstance("HmacSHA256").apply { init(revisionKey) }
        val buffer = ByteArray(2048)
        for (field in fields) {
            val text = record.optString(field)
            mac.update(field.toByteArray(Charsets.US_ASCII))
            for (shift in listOf(24, 16, 8, 0)) mac.update((text.length ushr shift).toByte())
            var cursor = 0
            while (cursor < text.length) {
                var bytes = 0
                while (cursor < text.length && bytes < buffer.size) {
                    val code = text[cursor++].code
                    buffer[bytes++] = (code ushr 8).toByte()
                    buffer[bytes++] = code.toByte()
                }
                mac.update(buffer, 0, bytes)
            }
        }
        return record.put("text_revision", mac.doFinal().take(16).joinToString("") { "%02x".format(it) })
    }

    const val DEFAULT_LIMIT = 4000
    const val MAX_LIMIT = 16000

    fun addProperties(properties: JSONObject): JSONObject = properties
        .put("text_field", JSONObject().put("type", "string").put("enum", JSONArray(fields)))
        .put("text_offset", JSONObject().put("type", "integer").put("minimum", 0)
            .put("description", "Requires text_field, including for result. UTF-16 character offset, independent of after_seq."))
        .put("text_limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT)
            .put("description", "Requires text_field. Default 4000; maximum 16000 UTF-16 units. A limit of 1 may return a complete 2-unit surrogate pair for progress."))

    fun validate(args: JSONObject) {
        if (args.has("text_field")) require(args.opt("text_field") is String && args.getString("text_field") in fields) {
            "text_field must be result, partial_result or model_report_unverified"
        }
        require(args.has("text_field") || (!args.has("text_offset") && !args.has("text_limit"))) {
            "text_offset/text_limit require text_field"
        }
        for (name in listOf("text_offset", "text_limit")) if (args.has(name)) {
            val value = args.opt(name)
            require(value is Int || value is Long) { "$name must be an integer" }
            val n = (value as Number).toLong()
            require(n in (if (name == "text_limit") 1L..MAX_LIMIT.toLong() else 0L..Int.MAX_VALUE.toLong())) {
                "$name is out of range"
            }
        }
    }

    /** Mutates a detached snapshot only; never the retained report or task state. */
    fun project(record: JSONObject, args: JSONObject = JSONObject()): JSONObject {
        validate(args)
        if (!record.has("text_revision")) attachRevision(record)
        val bodies = fields.associateWith { record.optString(it) }
        val unavailable = record.optBoolean("text_evicted")
        val metadata = JSONObject()
        fields.forEach { field ->
            val body = bodies.getValue(field)
            metadata.put(field, JSONObject().put("total_chars", body.length).put("available", !unavailable)
                .put("unverified", field != "result" || record.optString("role") != "implementation")
                .put("truncated", record.optBoolean("${field}_truncated"))
                .apply {
                    if (unavailable) put("unavailable_reason", "archive_capacity")
                    if (field == "partial_result" && body.isNotEmpty()) {
                        fields.firstOrNull { it != field && bodies[it] == body }?.let { put("same_as", it) }
                    }
                })
            record.put(field, "")
        }
        record.put("text_fields", metadata)
        val selected = if (args.has("text_field")) args.getString("text_field") else
            "result".takeIf { record.optString("status") !in setOf("queued", "running") && bodies.getValue(it).isNotEmpty() }
        if (selected == null) return record
        val body = bodies.getValue(selected)
        val offset = args.optInt("text_offset", 0).coerceAtMost(body.length)
        require(offset == 0 || offset == body.length || !body[offset].isLowSurrogate() || !body[offset - 1].isHighSurrogate()) {
            "text_offset must not split a surrogate pair; use text_page.next_offset"
        }
        val limit = args.optInt("text_limit", DEFAULT_LIMIT)
        var end = minOf(body.length.toLong(), offset.toLong() + limit).toInt()
        // Do not split an emoji at our outgoing page boundary; reject caller offsets inside a surrogate pair.
        if (end < body.length && end > offset && body[end - 1].isHighSurrogate() && body[end].isLowSurrogate()) end--
        if (end == offset && offset < body.length) end = minOf(body.length, offset + 2)
        record.put(selected, if (unavailable) "" else body.substring(offset, end))
        record.put("text_page", JSONObject().put("field", selected).put("offset", offset)
            .put("next_offset", end).put("total_chars", body.length).put("has_more", !unavailable && end < body.length)
            .put("available", !unavailable).put("unverified", metadata.getJSONObject(selected).getBoolean("unverified")))
        return record
    }
}
