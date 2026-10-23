package io.github.mangi.eta.agent.tool

import android.content.Context
import android.provider.Settings
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配方解释执行器——把一份声明式配方跑成结果。
 *
 * 设计原则：
 * - **只执行白名单步骤**：每种 op 走固定的实现路径，模型无法借此执行任意代码。
 * - **插值即转义**：URL 走 percent-encoding，shell 走单引号包裹，文件/Settings 走原值。
 * - **写后回读**：settings_write / file_write 之后立刻回读校验，不把「命令已发出」当「已生效」。
 * - **有界**：每步超时、输出截断、组合深度受限，避免配方把设备拖死。
 */
internal class AgentToolRecipeExecutor(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val store: AgentToolRecipeStore,
    private val rootAvailable: () -> Boolean = { false },
) {

    /**
     * 执行一份配方。
     *
     * @param recipe 目标配方
     * @param arguments 模型传入的实参
     */
    fun execute(recipe: ToolRecipe, arguments: JSONObject): String {
        val values = ToolRecipeArguments.flatten(arguments)
        val missing = missingParameters(recipe, values)
        if (missing.isNotEmpty()) {
            return error(
                "RECIPE_MISSING_PARAMETERS",
                "缺少必填参数：${missing.joinToString(", ")}；补齐后重试一次",
            )
        }
        val outcome = runSteps(recipe, values, depth = 0)
        return when (outcome) {
            is StepOutcome.Failed -> error(outcome.code, outcome.message)
            is StepOutcome.Ok -> JSONObject()
                .put("ok", true)
                .put("tool", recipe.modelToolName)
                .put("recipe", recipe.name)
                .put("result", outcome.text)
                .put("steps_executed", outcome.stepsExecuted)
                .toString()
        }
    }

    /** 找出配方里用到但实参没提供的占位符。 */
    private fun missingParameters(recipe: ToolRecipe, values: Map<String, String>): List<String> {
        val required = recipe.parameters.optJSONArray("required") ?: return emptyList()
        val missing = mutableListOf<String>()
        for (index in 0 until required.length()) {
            val key = required.optString(index)
            if (key.isNotBlank() && !values.containsKey(key)) missing += key
        }
        return missing
    }

    private fun runSteps(
        recipe: ToolRecipe,
        values: Map<String, String>,
        depth: Int,
    ): StepOutcome {
        if (depth > ToolRecipeConstants.MAX_COMPOSITION_DEPTH) {
            return StepOutcome.Failed("RECIPE_DEPTH_EXCEEDED", "配方组合层级过深")
        }
        var lastOutput = ""
        var executed = 0
        for (step in recipe.steps) {
            when (val outcome = runStep(step, values, lastOutput, depth)) {
                is StepOutcome.Failed -> return outcome
                is StepOutcome.Ok -> {
                    lastOutput = outcome.text
                    executed++
                }
            }
        }
        return StepOutcome.Ok(lastOutput, executed)
    }

    private fun runStep(
        step: ToolRecipeStep,
        values: Map<String, String>,
        previousOutput: String,
        depth: Int,
    ): StepOutcome = when (step) {
        is ToolRecipeStep.HttpRequest -> httpRequest(step, values)
        is ToolRecipeStep.RootShell -> rootShell(step, values)
        is ToolRecipeStep.SettingsRead -> settingsRead(step, values)
        is ToolRecipeStep.SettingsWrite -> settingsWrite(step, values)
        is ToolRecipeStep.FileRead -> fileRead(step, values)
        is ToolRecipeStep.FileWrite -> fileWrite(step, values)
        is ToolRecipeStep.ContentQuery -> contentQuery(step, values)
        is ToolRecipeStep.Literal -> StepOutcome.Ok(
            ToolRecipeTemplate.interpolate(step.text, values),
        )

        is ToolRecipeStep.Compose -> {
            val composed = store.find(step.recipe)
                ?: return StepOutcome.Failed(
                    "RECIPE_COMPOSE_TARGET_MISSING",
                    "组合目标「${step.recipe}」已不存在；删除该步骤或重新创建目标配方",
                )
            if (composed.enabled.not()) {
                return StepOutcome.Failed(
                    "RECIPE_COMPOSE_TARGET_DISABLED",
                    "组合目标「${step.recipe}」已停用；先启用它再调用",
                )
            }
            val nested = if (step.passOutput) {
                values + ("previous_output" to previousOutput)
            } else {
                values
            }
            runSteps(composed, nested, depth + 1)
        }
    }

    private fun httpRequest(step: ToolRecipeStep.HttpRequest, values: Map<String, String>): StepOutcome {
        val url = ToolRecipeTemplate.interpolateUrl(step.url, values)
        if (url.contains("{{")) {
            return StepOutcome.Failed(
                "RECIPE_MISSING_PARAMETERS",
                "URL 里的占位符没有对应实参，请补齐参数后重试一次",
            )
        }
        val bodyText = step.body?.let { ToolRecipeTemplate.interpolate(it, values) }
        val requestBody = when {
            bodyText == null -> null
            step.bodyIsJson -> bodyText.toRequestBody("application/json; charset=utf-8".toMediaType())
            else -> bodyText.toRequestBody("text/plain; charset=utf-8".toMediaType())
        }
        val builder = Request.Builder().url(url)
        step.headers.forEach { (key, value) ->
            builder.header(key, ToolRecipeTemplate.interpolate(value, values))
        }
        val request = when (step.method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post(requestBody ?: EMPTY_BODY)
            "PUT" -> builder.put(requestBody ?: EMPTY_BODY)
            "PATCH" -> builder.patch(requestBody ?: EMPTY_BODY)
            "DELETE" -> builder.delete(requestBody)
            else -> builder.get()
        }.build()

        return runCatching {
            // 用独立客户端：模型请求的客户端读超时是 10 分钟，配方步骤必须自己限时，
            // 否则一个卡住的接口会拖死整个回合。
            recipeHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@runCatching StepOutcome.Failed(
                        "RECIPE_HTTP_FAILED",
                        "HTTP ${response.code} ${response.message}：${body.take(300)}",
                    )
                }
                StepOutcome.Ok(body.take(ToolRecipeConstants.MAX_OUTPUT_CHARS))
            }
        }.getOrElse {
            StepOutcome.Failed("RECIPE_HTTP_FAILED", it.message ?: "HTTP 请求失败")
        }
    }

    private fun rootShell(step: ToolRecipeStep.RootShell, values: Map<String, String>): StepOutcome {
        if (!rootAvailable()) {
            return StepOutcome.Failed(
                "ROOT_REQUIRED",
                "该配方需要 Root 权限；未授权前不要重复调用本工具",
            )
        }
        val command = ToolRecipeTemplate.interpolateShell(step.command, values)
        if (command.contains("{{")) {
            return StepOutcome.Failed(
                "RECIPE_MISSING_PARAMETERS",
                "命令里的占位符没有对应实参，请补齐参数后重试一次",
            )
        }
        val result = root.execute(command, timeoutMillis = step.timeoutMillis)
        if (!result.ok) {
            val code = result.errorCode.ifBlank { if (result.timedOut) "RECIPE_TIMEOUT" else "RECIPE_COMMAND_FAILED" }
            return StepOutcome.Failed(
                code,
                "命令失败（exit=${result.exitCode}）：${result.stderr.take(300).ifBlank { result.stdout.take(300) }}",
            )
        }
        return StepOutcome.Ok(result.stdout.take(ToolRecipeConstants.MAX_OUTPUT_CHARS))
    }

    private fun settingsRead(step: ToolRecipeStep.SettingsRead, values: Map<String, String>): StepOutcome {
        val key = ToolRecipeTemplate.interpolate(step.key, values)
        if (key.contains("{{")) {
            return StepOutcome.Failed("RECIPE_MISSING_PARAMETERS", "settings 键缺少实参")
        }
        val resolver = context.contentResolver
        val value = runCatching {
            when (step.namespace) {
                "secure" -> Settings.Secure.getString(resolver, key)
                "global" -> Settings.Global.getString(resolver, key)
                else -> Settings.System.getString(resolver, key)
            }
        }.getOrNull()
        if (value == null) {
            return StepOutcome.Failed("RECIPE_SETTING_NOT_FOUND", "设置项 ${step.namespace}/$key 不存在或读不到")
        }
        return StepOutcome.Ok(value)
    }

    private fun settingsWrite(step: ToolRecipeStep.SettingsWrite, values: Map<String, String>): StepOutcome {
        val key = ToolRecipeTemplate.interpolate(step.key, values)
        val value = ToolRecipeTemplate.interpolate(step.value, values)
        if (key.contains("{{")) {
            return StepOutcome.Failed("RECIPE_MISSING_PARAMETERS", "settings 键缺少实参")
        }
        val resolver = context.contentResolver
        val canWrite = Settings.System.canWrite(context)
        val wrote = if (canWrite) {
            runCatching {
                when (step.namespace) {
                    "secure" -> Settings.Secure.putString(resolver, key, value)
                    "global" -> Settings.Global.putString(resolver, key, value)
                    else -> Settings.System.putString(resolver, key, value)
                }
            }.getOrDefault(false)
        } else {
            // 免 Root 写不了就走 Root 保底。
            if (!rootAvailable()) {
                return StepOutcome.Failed(
                    "WRITE_SETTINGS_REQUIRED",
                    "修改系统设置需要「修改系统设置」权限或 Root；未授权前不要重复调用本工具",
                )
            }
            val result = root.execute(
                "settings put ${step.namespace} ${ToolRecipeTemplate.shellQuote(key)} " +
                    ToolRecipeTemplate.shellQuote(value),
                timeoutMillis = QUICK_TIMEOUT_MS,
            )
            result.ok
        }
        if (!wrote) {
            return StepOutcome.Failed("RECIPE_SETTING_WRITE_FAILED", "写入设置项 ${step.namespace}/$key 失败")
        }
        // 写后回读：不把「命令已发出」当「已生效」。
        val actual = runCatching {
            when (step.namespace) {
                "secure" -> Settings.Secure.getString(resolver, key)
                "global" -> Settings.Global.getString(resolver, key)
                else -> Settings.System.getString(resolver, key)
            }
        }.getOrNull()
        if (actual != value) {
            return StepOutcome.Failed(
                "RECIPE_SETTING_NOT_APPLIED",
                "设置项 ${step.namespace}/$key 写入后回读不一致（期望 $value，实际 ${actual ?: "空"}）",
            )
        }
        return StepOutcome.Ok("已写入 ${step.namespace}/$key")
    }

    private fun fileRead(step: ToolRecipeStep.FileRead, values: Map<String, String>): StepOutcome {
        val path = ToolRecipeTemplate.interpolate(step.path, values)
        if (path.contains("{{")) {
            return StepOutcome.Failed("RECIPE_MISSING_PARAMETERS", "文件路径缺少实参")
        }
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return StepOutcome.Failed("RECIPE_FILE_NOT_FOUND", "文件不存在：$path")
        }
        val content = runCatching { file.readText() }.getOrNull()
            ?: return StepOutcome.Failed("RECIPE_FILE_READ_FAILED", "读取失败：$path")
        return StepOutcome.Ok(content.take(step.maxBytes))
    }

    private fun fileWrite(step: ToolRecipeStep.FileWrite, values: Map<String, String>): StepOutcome {
        val path = ToolRecipeTemplate.interpolate(step.path, values)
        val content = ToolRecipeTemplate.interpolate(step.content, values)
        if (path.contains("{{")) {
            return StepOutcome.Failed("RECIPE_MISSING_PARAMETERS", "文件路径缺少实参")
        }
        val file = File(path)
        val written = runCatching {
            file.parentFile?.mkdirs()
            if (step.append) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }
        }.isSuccess
        if (!written) {
            return StepOutcome.Failed("RECIPE_FILE_WRITE_FAILED", "写入失败：$path")
        }
        // 写后回读：不把「命令已发出」当「已生效」。
        // append 模式只校验尾部确实是刚写入的内容；覆盖模式校验全文一致。
        val actual = runCatching { file.readText() }.getOrNull()
        val applied = when {
            actual == null -> false
            step.append -> actual.endsWith(content)
            else -> actual == content
        }
        if (!applied) {
            return StepOutcome.Failed(
                "RECIPE_FILE_NOT_APPLIED",
                "文件写入后回读不一致：$path",
            )
        }
        return StepOutcome.Ok("已写入 $path")
    }

    private fun contentQuery(step: ToolRecipeStep.ContentQuery, values: Map<String, String>): StepOutcome {
        val uri = ToolRecipeTemplate.interpolate(step.uri, values)
        if (uri.contains("{{")) {
            return StepOutcome.Failed("RECIPE_MISSING_PARAMETERS", "Provider URI 缺少实参")
        }
        if (!rootAvailable()) {
            return StepOutcome.Failed(
                "ROOT_REQUIRED",
                "查询系统 Provider 需要 Root 权限；未授权前不要重复调用本工具",
            )
        }
        val command = buildString {
            append("content query --uri ").append(ToolRecipeTemplate.shellQuote(uri))
            step.projection?.let {
                append(" --projection ").append(ToolRecipeTemplate.shellQuote(ToolRecipeTemplate.interpolate(it, values)))
            }
            step.selection?.let {
                append(" --where ").append(ToolRecipeTemplate.shellQuote(ToolRecipeTemplate.interpolate(it, values)))
            }
        }
        val result = root.execute(command, timeoutMillis = QUERY_TIMEOUT_MS)
        if (!result.ok) {
            return StepOutcome.Failed(
                result.errorCode.ifBlank { "RECIPE_PROVIDER_QUERY_FAILED" },
                "Provider 查询失败：${result.stderr.take(300)}",
            )
        }
        return StepOutcome.Ok(result.stdout.take(ToolRecipeConstants.MAX_OUTPUT_CHARS))
    }

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    /** 步骤执行结果。 */
    private sealed interface StepOutcome {
        data class Ok(val text: String, val stepsExecuted: Int = 1) : StepOutcome
        data class Failed(val code: String, val message: String) : StepOutcome
    }

    private companion object {
        const val QUICK_TIMEOUT_MS = 10_000L
        const val QUERY_TIMEOUT_MS = 15_000L
        const val HTTP_TIMEOUT_SECONDS = 20L

        /** 空请求体：POST/PUT/PATCH 没有 body 时也需要一个合法对象。 */
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody("application/octet-stream".toMediaType())

        /** 配方专用的短超时客户端，避免慢接口拖死模型回合。 */
        val recipeHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/**
 * 配方的 JSON 视图——给模型看的可读摘要。
 *
 * 单独抽出来是为了让「列出配方」这条路径不需要知道执行器细节。
 */
internal object ToolRecipeSummary {

    /** 单个配方的摘要 JSON。 */
    fun of(recipe: ToolRecipe, rootNeeding: Set<String>): JSONObject =
        JSONObject()
            .put("name", recipe.name)
            .put("tool_name", recipe.modelToolName)
            .put("description", recipe.description)
            .put("enabled", recipe.enabled)
            .put("version", recipe.version)
            .put("requires_root", recipe.name in rootNeeding)
            .put(
                "parameters",
                recipe.parameters.optJSONObject("properties")?.let { properties ->
                    val schema = JSONObject()
                    properties.keys().asSequence().forEach { key ->
                        val property = properties.optJSONObject(key) ?: return@forEach
                        schema.put(
                            key,
                            JSONObject()
                                .put("type", property.optString("type", "string"))
                                .put("description", property.optString("description")),
                        )
                    }
                    schema
                } ?: JSONObject(),
            )
            .put("steps", stepSummary(recipe))

    private fun stepSummary(recipe: ToolRecipe): JSONArray {
        val array = JSONArray()
        recipe.steps.forEach { step ->
            array.put(
                when (step) {
                    is ToolRecipeStep.HttpRequest -> JSONObject()
                        .put("op", step.op)
                        .put("method", step.method)
                        .put("url", step.url)

                    is ToolRecipeStep.RootShell -> JSONObject()
                        .put("op", step.op)
                        .put("command", step.command)

                    is ToolRecipeStep.SettingsRead -> JSONObject()
                        .put("op", step.op)
                        .put("namespace", step.namespace)
                        .put("key", step.key)

                    is ToolRecipeStep.SettingsWrite -> JSONObject()
                        .put("op", step.op)
                        .put("namespace", step.namespace)
                        .put("key", step.key)

                    is ToolRecipeStep.FileRead -> JSONObject()
                        .put("op", step.op)
                        .put("path", step.path)

                    is ToolRecipeStep.FileWrite -> JSONObject()
                        .put("op", step.op)
                        .put("path", step.path)
                        .put("append", step.append)

                    is ToolRecipeStep.ContentQuery -> JSONObject()
                        .put("op", step.op)
                        .put("uri", step.uri)

                    is ToolRecipeStep.Compose -> JSONObject()
                        .put("op", step.op)
                        .put("recipe", step.recipe)

                    is ToolRecipeStep.Literal -> JSONObject()
                        .put("op", step.op)
                        .put("text", step.text)
                },
            )
        }
        return array
    }
}
