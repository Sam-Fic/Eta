package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.media.index.EmbeddingModelStore
import io.github.mangi.eta.agent.media.index.MediaIndexTestFixtures
import io.github.mangi.eta.agent.media.index.MediaIndexer
import io.github.mangi.eta.agent.media.index.MediaSourceScanner
import io.github.mangi.eta.agent.model.AgentMediaSearchToolCatalog
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaIndexDao
import io.github.mangi.eta.data.db.MediaSourceEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `semantic_search_media` 工具的 schema 契约与参数校验单测。
 *
 * 覆盖：schema（工具名 / required / action 枚举 5 项 / kinds 枚举 / limit 边界）、
 * 非法参数的结构化错误（ok=false + code）、索引未就绪的 MEDIA_INDEX_NOT_READY 两个分支、
 * find_similar 的纯向量检索路径（不触发推理，可用构造向量端到端验证）。
 * search 的成功路径需要 LiteRT 推理，不在纯 JVM 覆盖范围。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMediaSearchToolTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        MediaIndexTestFixtures.resetModelStore()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        MediaIndexTestFixtures.resetModelStore()
    }

    private fun dao(): MediaIndexDao = EtaDatabase.get(context).mediaIndexDao()

    private fun execute(vararg pairs: Pair<String, Any>): JSONObject {
        val args = JSONObject()
        pairs.forEach { (key, value) -> args.put(key, value) }
        return JSONObject(AgentMediaSearchTools(context).execute(args))
    }

    /** 模型已校验 + 至少一个已启用扫描源：满足 notReady() 放行的两个条件。 */
    private fun readyIndex() {
        MediaIndexTestFixtures.installFakeModel(context)
        runBlocking {
            dao().upsertSource(
                MediaSourceEntity(
                    id = MediaSourceScanner.TYPE_WORKSPACE,
                    type = MediaSourceScanner.TYPE_WORKSPACE,
                    uri = "",
                    label = "终端工作区",
                    enabled = true,
                ),
            )
        }
    }

    @Test
    fun schemaDeclaresSemanticSearchMediaContract() {
        val tools = JSONArray()
        AgentMediaSearchToolCatalog.appendTo(tools)
        assertEquals(1, tools.length())

        val function = tools.getJSONObject(0).getJSONObject("function")
        assertEquals("function", tools.getJSONObject(0).getString("type"))
        assertEquals("semantic_search_media", function.getString("name"))
        assertEquals(AgentMediaSearchToolCatalog.SEARCH_MEDIA, function.getString("name"))

        val parameters = function.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals(JSONArray().put("action"), parameters.getJSONArray("required"))

        val properties = parameters.getJSONObject("properties")
        assertEquals(
            JSONArray().put("search").put("find_similar").put("list_sources").put("index_status").put("reindex"),
            properties.getJSONObject("action").getJSONArray("enum"),
        )
        assertEquals(
            JSONArray().put("image").put("video").put("audio").put("document"),
            properties.getJSONObject("kinds").getJSONObject("items").getJSONArray("enum"),
        )
        val limit = properties.getJSONObject("limit")
        assertEquals(1, limit.getInt("minimum"))
        assertEquals(50, limit.getInt("maximum"))
        // 错误码契约写进描述，模型才知道引导用户去设置页而不是编造结果。
        assertTrue(function.getString("description").contains("MEDIA_INDEX_NOT_READY"))
    }

    @Test
    fun invalidOrMissingActionIsRejectedWithInvalidArgument() {
        listOf(execute(), execute("action" to "bogus")).forEach { response ->
            assertFalse(response.getBoolean("ok"))
            assertEquals("INVALID_ARGUMENT", response.getString("code"))
            assertTrue(response.getString("message").isNotEmpty())
        }
    }

    @Test
    fun searchRequiresNonBlankQuery() {
        listOf(
            execute("action" to "search"),
            execute("action" to "search", "query" to "   "),
        ).forEach { response ->
            assertFalse(response.getBoolean("ok"))
            assertEquals("INVALID_ARGUMENT", response.getString("code"))
        }
    }

    @Test
    fun findSimilarRequiresNonBlankReference() {
        listOf(
            execute("action" to "find_similar"),
            execute("action" to "find_similar", "reference" to " "),
        ).forEach { response ->
            assertFalse(response.getBoolean("ok"))
            assertEquals("INVALID_ARGUMENT", response.getString("code"))
        }
    }

    @Test
    fun searchWithoutDownloadedModelReportsMediaIndexNotReady() {
        MediaIndexTestFixtures.resetModelStore()
        val response = execute("action" to "search", "query" to "海边日落")
        assertFalse(response.getBoolean("ok"))
        assertEquals("MEDIA_INDEX_NOT_READY", response.getString("code"))
        assertTrue(response.getString("message").contains("尚未下载"))
    }

    @Test
    fun searchWithoutConfirmedScanScopeReportsMediaIndexNotReady() {
        // 模型已就绪但一个扫描源都没启用：命中第二条未就绪分支。
        MediaIndexTestFixtures.installFakeModel(context)
        val response = execute("action" to "search", "query" to "海边日落")
        assertFalse(response.getBoolean("ok"))
        assertEquals("MEDIA_INDEX_NOT_READY", response.getString("code"))
        assertTrue(response.getString("message").contains("尚未确认扫描范围"))
    }

    @Test
    fun invalidKindsAreRejectedBeforeReachingSearch() {
        readyIndex()
        val response = execute(
            "action" to "search",
            "query" to "海边日落",
            "kinds" to JSONArray().put("bogus"),
        )
        assertFalse(response.getBoolean("ok"))
        // 非法 kinds 与其他参数错误同级，统一报 INVALID_ARGUMENT；消息里带取值说明。
        assertEquals("INVALID_ARGUMENT", response.getString("code"))
        assertTrue(response.getString("message").contains("kinds 取值无效"))
    }

    @Test
    fun findSimilarWithUnknownReferenceFailsWithInvalidArgument() {
        readyIndex()
        val response = execute("action" to "find_similar", "reference" to "/missing.png")
        assertFalse(response.getBoolean("ok"))
        assertEquals("INVALID_ARGUMENT", response.getString("code"))
        assertTrue(response.getString("message").contains("找不到已索引的参考项"))
    }

    @Test
    fun findSimilarRanksNeighboursByDotProductAndRespectsLimit() {
        readyIndex()
        val dim = 768
        runBlocking {
            dao().upsertAll(
                listOf(
                    MediaIndexTestFixtures.entity("/ref.png", MediaIndexTestFixtures.axisVector(dim, 0)),
                    MediaIndexTestFixtures.entity("/same.png", MediaIndexTestFixtures.axisVector(dim, 0)),
                    MediaIndexTestFixtures.entity("/other.png", MediaIndexTestFixtures.axisVector(dim, 1)),
                    MediaIndexTestFixtures.entity("/opposite.png", MediaIndexTestFixtures.axisVector(dim, 0, -1f)),
                ),
            )
        }

        val response = execute("action" to "find_similar", "reference" to "/ref.png")
        assertTrue(response.getBoolean("ok"))
        assertEquals("find_similar", response.getString("action"))
        assertEquals(3, response.getInt("count"))
        val names = response.getJSONArray("results").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("display_name") }
        }
        // 按点积降序：同向 > 正交 > 反向；参考项自身不进结果。
        assertEquals(listOf("same.png", "other.png", "opposite.png"), names)

        // limit 越界按 1..50 收敛：limit=0 至少返回 1 条，且只返回最相似的那条。
        val limited = execute("action" to "find_similar", "reference" to "/ref.png", "limit" to 0)
        assertTrue(limited.getBoolean("ok"))
        assertEquals(1, limited.getInt("count"))
        assertEquals(
            "same.png",
            limited.getJSONArray("results").getJSONObject(0).getString("display_name"),
        )
    }

    @Test
    fun indexStatusAndListSourcesRouteWithoutModel() {
        MediaIndexTestFixtures.resetModelStore()
        val status = execute("action" to "index_status")
        assertTrue(status.getBoolean("ok"))
        assertEquals("index_status", status.getString("action"))
        assertFalse(status.getBoolean("model_downloaded"))
        assertFalse(status.getBoolean("ready"))
        assertEquals(EmbeddingModelStore.DEFAULT_DIM, status.getInt("dim"))
        assertEquals(0, status.getInt("indexed_count"))

        val sources = execute("action" to "list_sources")
        assertTrue(sources.getBoolean("ok"))
        assertEquals("list_sources", sources.getString("action"))
        val entries = sources.getJSONArray("sources")
        val byId = (0 until entries.length()).associate {
            entries.getJSONObject(it).getString("id") to entries.getJSONObject(it)
        }
        // 未配置的推荐源也一并列出（configured=false），便于模型引导用户完成首次设置。
        val mediaStore = byId.getValue(MediaSourceScanner.TYPE_MEDIASTORE)
        assertFalse(mediaStore.getBoolean("configured"))
        assertFalse(mediaStore.getBoolean("enabled"))
    }

    @Test
    fun reindexReturnsStructuredAckWithoutCrashing() {
        MediaIndexTestFixtures.resetModelStore()
        val response = execute("action" to "reindex")
        assertTrue(response.getBoolean("ok"))
        assertEquals("reindex", response.getString("action"))
        assertTrue(response.has("started"))
        assertTrue(response.getString("note").isNotEmpty())
        // 等后台触发的索引任务（因模型未就绪快速失败）落地，避免 running 标志影响后续用例。
        val deadline = System.currentTimeMillis() + 5_000
        while (MediaIndexer.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(MediaIndexer.isRunning())
    }
}
