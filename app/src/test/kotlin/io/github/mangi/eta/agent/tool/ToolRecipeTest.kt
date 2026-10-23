package io.github.mangi.eta.agent.tool

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自进化配方的纯逻辑测试。
 *
 * 覆盖三件最容易出错的事：
 * 1. 占位符插值的转义（防注入）
 * 2. 配方校验（名字、步骤白名单、参数声明）
 * 3. 组合链的 Root 推导与环检测
 */
class ToolRecipeTest {

    @Test
    fun placeholdersAreCollected() {
        val names = ToolRecipeTemplate.placeholders("https://x/{{city}}/y/{{ unit }}")
        assertEquals(setOf("city", "unit"), names)
    }

    @Test
    fun urlInterpolationPercentEncodesValues() {
        val result = ToolRecipeTemplate.interpolateUrl(
            "https://api.test/?q={{query}}",
            mapOf("query" to "a b&c=d"),
        )
        // & 与 = 必须被编码，否则会破坏 URL 结构。
        assertFalse("URL 里不该出现裸 & 或 =", result.contains("&c") || result.contains("=d"))
        assertTrue(result.startsWith("https://api.test/?q="))
    }

    @Test
    fun urlInterpolationKeepsMissingPlaceholderVisible() {
        val result = ToolRecipeTemplate.interpolateUrl("https://api.test/{{missing}}", emptyMap())
        // 缺失时保留原样，让执行器能报「缺参数」而不是拼出坏 URL。
        assertEquals("https://api.test/{{missing}}", result)
    }

    @Test
    fun shellInterpolationQuotesAndEscapes() {
        val result = ToolRecipeTemplate.interpolateShell(
            "echo {{text}}",
            mapOf("text" to "it's; rm -rf /"),
        )
        // 单引号包裹后，内部单引号按 POSIX 惯例转义，命令无法被拆开。
        assertTrue(result.startsWith("echo '"))
        assertTrue(result.endsWith("'"))
        assertTrue("内部单引号必须转义", result.contains("'\\''"))
    }

    @Test
    fun shellQuoteWrapsPlainValue() {
        assertEquals("'abc'", ToolRecipeTemplate.shellQuote("abc"))
    }

    @Test
    fun parserAcceptsMinimalRecipe() {
        val raw = JSONObject()
            .put("name", "fetch_weather")
            .put("description", "查询天气")
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("city", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("city")),
            )
            .put(
                "steps",
                org.json.JSONArray().put(
                    JSONObject().put("op", "http_request").put("url", "https://wttr.in/{{city}}"),
                ),
            )

        val parsed = ToolRecipeParser.parse(raw)
        assertTrue(parsed is ToolRecipeParser.ParseResult.Ok)
        val recipe = (parsed as ToolRecipeParser.ParseResult.Ok<ToolRecipe>).value
        assertEquals("fetch_weather", recipe.name)
        assertEquals("recipe_fetch_weather", recipe.modelToolName)
        assertFalse(recipe.requiresRoot)
    }

    @Test
    fun parserRejectsBadName() {
        val raw = JSONObject()
            .put("name", "Bad-Name")
            .put("description", "x")
            .put("steps", org.json.JSONArray().put(JSONObject().put("op", "literal").put("text", "a")))

        val parsed = ToolRecipeParser.parse(raw)
        assertTrue(parsed is ToolRecipeParser.ParseResult.Invalid)
        assertEquals("INVALID_RECIPE_NAME", (parsed as ToolRecipeParser.ParseResult.Invalid).code)
    }

    @Test
    fun parserRejectsUnknownStepOp() {
        val raw = JSONObject()
            .put("name", "danger_tool")
            .put("description", "x")
            .put("steps", org.json.JSONArray().put(JSONObject().put("op", "eval_code").put("code", "rm -rf /")))

        val parsed = ToolRecipeParser.parse(raw)
        assertTrue(parsed is ToolRecipeParser.ParseResult.Invalid)
        assertEquals("UNKNOWN_STEP_OP", (parsed as ToolRecipeParser.ParseResult.Invalid).code)
    }

    @Test
    fun parserRejectsRequiredParameterNotDeclared() {
        val raw = JSONObject()
            .put("name", "needs_arg")
            .put("description", "x")
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("other", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("a")),
            )
            .put(
                "steps",
                org.json.JSONArray().put(JSONObject().put("op", "http_request").put("url", "https://x/{{a}}")),
            )

        val parsed = ToolRecipeParser.parse(raw)
        assertTrue(parsed is ToolRecipeParser.ParseResult.Invalid)
        assertEquals("INVALID_RECIPE", (parsed as ToolRecipeParser.ParseResult.Invalid).code)
    }

    @Test
    fun parserRejectsUnsupportedParameterType() {
        val raw = JSONObject()
            .put("name", "bad_type")
            .put("description", "x")
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("a", JSONObject().put("type", "function"))),
            )
            .put("steps", org.json.JSONArray().put(JSONObject().put("op", "literal").put("text", "x")))

        val parsed = ToolRecipeParser.parse(raw)
        assertTrue(parsed is ToolRecipeParser.ParseResult.Invalid)
        assertEquals("INVALID_RECIPE", (parsed as ToolRecipeParser.ParseResult.Invalid).code)
    }

    @Test
    fun parserRejectsSelfComposition() {
        val raw = JSONObject()
            .put("name", "loop_tool")
            .put("description", "x")
            .put(
                "steps",
                org.json.JSONArray().put(JSONObject().put("op", "compose").put("recipe", "loop_tool")),
            )

        val parsed = ToolRecipeParser.parse(raw, existingNames = setOf("loop_tool"))
        assertTrue(parsed is ToolRecipeParser.ParseResult.Invalid)
        assertEquals("RECIPE_CYCLE", (parsed as ToolRecipeParser.ParseResult.Invalid).code)
    }

    @Test
    fun rootRequirementPropagatesThroughComposition() {
        val base = recipe(
            name = "base_root",
            steps = listOf(ToolRecipeStep.RootShell("id")),
        )
        val middle = recipe(
            name = "middle",
            steps = listOf(ToolRecipeStep.Compose("base_root")),
        )
        val top = recipe(
            name = "top",
            steps = listOf(ToolRecipeStep.Compose("middle")),
        )

        val rootNeeding = AgentToolRecipeStore(tempDir()).rootDependentRecipes(listOf(base, middle, top))
        assertTrue("base 需要 Root", "base_root" in rootNeeding)
        assertTrue("组合了 base 的 middle 也需要 Root", "middle" in rootNeeding)
        assertTrue("间接组合的 top 同样需要 Root", "top" in rootNeeding)
    }

    @Test
    fun cycleDetectionFindsLoop() {
        val a = recipe("a", listOf(ToolRecipeStep.Compose("b")))
        val b = recipe("b", listOf(ToolRecipeStep.Compose("a")))

        val cycle = AgentToolRecipeStore(tempDir()).detectCycle(listOf(a, b))
        assertNotNull("a → b → a 应当被检出", cycle)
    }

    @Test
    fun cycleDetectionPassesForAcyclicGraph() {
        val a = recipe("a", listOf(ToolRecipeStep.Compose("b")))
        val b = recipe("b", listOf(ToolRecipeStep.Compose("c")))
        val c = recipe("c", listOf(ToolRecipeStep.Literal("x")))

        assertEquals(null, AgentToolRecipeStore(tempDir()).detectCycle(listOf(a, b, c)))
    }

    @Test
    fun storePersistsAndReloadsRecipe() {
        val dir = tempDir()
        val store = AgentToolRecipeStore(dir)
        val recipe = recipe("saved_tool", listOf(ToolRecipeStep.Literal("hello")))

        val saved = store.save(recipe)
        assertTrue(saved is AgentToolRecipeStore.SaveResult.Ok)

        // 新实例模拟进程重启，配方应当仍在。
        val reopened = AgentToolRecipeStore(dir)
        val loaded = reopened.find("saved_tool")
        assertNotNull("配方应当落盘并在重开后仍在", loaded)
        assertEquals("hello", (loaded!!.steps.first() as ToolRecipeStep.Literal).text)
    }

    @Test
    fun schemasHideRootRecipesWhenRootUnavailable() {
        val dir = tempDir()
        val store = AgentToolRecipeStore(dir)
        store.save(recipe("plain_tool", listOf(ToolRecipeStep.Literal("x"))))
        store.save(recipe("root_tool", listOf(ToolRecipeStep.RootShell("id"))))

        val withRoot = store.schemas(rootAvailable = true).map { it.getJSONObject("function").getString("name") }
        val withoutRoot = store.schemas(rootAvailable = false).map { it.getJSONObject("function").getString("name") }

        assertTrue("recipe_plain_tool" in withRoot)
        assertTrue("recipe_root_tool" in withRoot)
        assertTrue("recipe_plain_tool" in withoutRoot)
        assertFalse("无 Root 时需要 Root 的配方应隐藏", "recipe_root_tool" in withoutRoot)
    }

    @Test
    fun disabledRecipeIsNotExposed() {
        val store = AgentToolRecipeStore(tempDir())
        store.save(recipe("off_tool", listOf(ToolRecipeStep.Literal("x"))).copy(enabled = false))

        val names = store.schemas(rootAvailable = true).map { it.getJSONObject("function").getString("name") }
        assertFalse("recipe_off_tool" in names)
    }

    @Test
    fun argumentsFlattenKeepsOnlyPresentKeys() {
        val args = JSONObject()
            .put("city", "北京")
            .put("count", 3)
            .put("flag", true)

        val flat = ToolRecipeArguments.flatten(args)
        assertEquals("北京", flat["city"])
        assertEquals("3", flat["count"])
        assertEquals("true", flat["flag"])
        assertFalse(flat.containsKey("missing"))
    }

    private fun recipe(name: String, steps: List<ToolRecipeStep>): ToolRecipe = ToolRecipe(
        name = name,
        description = "测试配方",
        parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
        steps = steps,
    )

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "eta-recipe-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
