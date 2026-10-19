package io.github.mangi.eta.agent.voice.doubao

import io.github.mangi.eta.R
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Explicit, read-only IAM ListProjects request. Never sends the voice clone API key. */
internal object VoiceProjectCatalog {
    data class Project(val name: String, val displayName: String, val allowed: Boolean)
    data class Page(val projects: List<Project>, val count: Int, val total: Int)
    private const val HOST = "iam.volcengineapi.com"
    private const val LIMIT = 100
    /** IAM 请求没有 Context：资源解析失败（如纯 JVM 测试）时回退中文原文。 */
    private fun label(resId: Int, zh: String): String =
        runCatching { io.github.mangi.eta.agent.runtime.AgentAppContext.resolve()?.getString(resId) }.getOrNull() ?: zh
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    private fun hmac(key: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(text.toByteArray(Charsets.UTF_8))
    }
    internal fun signedRequest(ak: String, sk: String, offset: Int, now: Instant): Request {
        require(offset >= 0)
        val query = "Action=ListProjects&Limit=$LIMIT&Offset=$offset&Version=2021-08-01"
        val date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now)
        val day = date.take(8)
        val headers = "host;x-date"
        val scope = "$day/cn-beijing/iam/request"
        val canonical = "GET\n/\n$query\nhost:$HOST\nx-date:$date\n\n$headers\n${hash("")}"
        var key = hmac(sk.toByteArray(Charsets.UTF_8), day)
        key = hmac(key, "cn-beijing"); key = hmac(key, "iam"); key = hmac(key, "request")
        val signature = hex(hmac(key, "HMAC-SHA256\n$date\n$scope\n${hash(canonical)}"))
        return Request.Builder().url("https://$HOST/?$query").get()
            .header("Host", HOST).header("X-Date", date)
            .header("Authorization", "HMAC-SHA256 Credential=$ak/$scope, SignedHeaders=$headers, Signature=$signature")
            .build()
    }
    internal fun parsePage(root: JSONObject): Page {
        val result = root.getJSONObject("Result")
        val items = result.getJSONArray("Projects")
        val total = result.getInt("Total")
        check(total >= 0) { label(R.string.pv_total_invalid, "项目列表总数无效") }
        val projects = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val name = item.getString("ProjectName")
            check(name.isNotBlank()) { label(R.string.pv_name_invalid, "项目列表包含无效名称") }
            Project(name, item.optString("DisplayName").ifBlank { name }, item.optBoolean("HasPermission", false))
        }
        return Page(projects, items.length(), total)
    }
    internal fun collectPages(fetch: (Int) -> Page): List<Project> {
        val result = linkedMapOf<String, Project>()
        for (page in 0 until 20) {
            val offset = page * LIMIT
            val data = fetch(offset)
            data.projects.forEach { result[it.name] = it }
            if (offset + data.count >= data.total) return result.values.toList()
            check(data.count == LIMIT) { label(R.string.pv_pages_incomplete, "项目列表未完整返回，请重试或手动填写项目名称") }
        }
        error("项目超过 2000 个，请手动填写项目名称")
    }
    fun list(ak: String, sk: String): List<Project> {
        require(ak.isNotBlank() && sk.isNotBlank()) { label(R.string.pv_fill_ak_sk, "请先填写 AK 和 SK") }
        return collectPages { offset ->
            DoubaoVoiceCatalog.client.newCall(signedRequest(ak, sk, offset, Instant.now())).execute().use { response ->
                val source = response.body.source()
                check(!source.request(2L * 1024 * 1024 + 1)) { "项目列表响应过大" }
                val root = runCatching { JSONObject(source.readUtf8()) }.getOrDefault(JSONObject())
                DoubaoVoiceCatalog.responseError(response.code, root, listOf(ak, sk))?.let {
                    error(it.replace("未取得音色列表", "未取得项目列表"))
                }
                parsePage(root)
            }
        }
    }
}
