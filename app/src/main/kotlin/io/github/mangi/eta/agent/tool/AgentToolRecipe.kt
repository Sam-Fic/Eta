package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明式工具配方（Recipe）——「模型给自己写工具」的可执行载体。
 *
 * Android 不能在运行期加载新的 Kotlin 代码，但 Eta 的工具 schema 本来就是运行期 JSON，
 * 执行体也完全可以数据化。于是把「一个新工具」表达成一份配方：
 *
 * ```
 * {
 *   "name": "fetch_weather",
 *   "description": "查询某个城市的天气",
 *   "parameters": { "type": "object", "properties": { "city": { "type": "string" } }, "required": ["city"] },
 *   "steps": [
 *     { "op": "http_request", "url": "https://wttr.in/{{city}}?format=j1" }
 *   ]
 * }
 * ```
 *
 * 配方由 [AgentToolRecipeStore] 落盘，由 [AgentToolRecipeExecutor] 解释执行。
 * 步骤类型是白名单，模型无法借此写出任意代码；参数插值一律走 [ToolRecipeTemplate]
 * 的结构化占位符，避免把模型输入直接拼进 shell 或 URL。
 */
internal object ToolRecipeConstants {
    /** 配方工具在模型侧的名字前缀，便于与内置工具区分。 */
    const val TOOL_PREFIX = "recipe_"

    /** 配方名允许的字符：与内置工具的 snake_case 风格一致。 */
    val TOOL_NAME_PATTERN = Regex("^[a-z][a-z0-9_]{2,39}$")

    /** 单个配方允许的最大步骤数。 */
    const val MAX_STEPS = 24

    /** 单个配方允许的最大描述长度。 */
    const val MAX_DESCRIPTION_LENGTH = 1_200

    /** 单次执行的输出截断上限。 */
    const val MAX_OUTPUT_CHARS = 16_000

    /** 配方里允许引用的其它配方名（用于组合），深度受限。 */
    const val MAX_COMPOSITION_DEPTH = 3

    /** 最多同时暴露给模型的配方数量——工具列表有上限，不能让自建工具挤掉内置能力。 */
    const val MAX_VISIBLE_RECIPES = 24

    /** 设备上允许存在的配方总数上限。 */
    const val MAX_STORED_RECIPES = 64
}

/** 配方的一个步骤。`op` 决定语义，其余字段是固定生成的键值，不接受自由格式。 */
internal sealed interface ToolRecipeStep {
    val op: String

    /** HTTP 请求。 */
    data class HttpRequest(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val bodyIsJson: Boolean = false,
    ) : ToolRecipeStep {
        override val op: String get() = OP_HTTP_REQUEST
    }

    /** 执行一条固定构造的 Root 命令（模型只能填参数占位符）。 */
    data class RootShell(
        val command: String,
        val timeoutMillis: Long = 15_000L,
    ) : ToolRecipeStep {
        override val op: String get() = OP_ROOT_SHELL
    }

    /** 读一个 Settings 值。 */
    data class SettingsRead(
        val namespace: String,
        val key: String,
    ) : ToolRecipeStep {
        override val op: String get() = OP_SETTINGS_READ
    }

    /** 写一个 Settings 值。 */
    data class SettingsWrite(
        val namespace: String,
        val key: String,
        val value: String,
    ) : ToolRecipeStep {
        override val op: String get() = OP_SETTINGS_WRITE
    }

    /** 读文件。 */
    data class FileRead(
        val path: String,
        val maxBytes: Int = 64 * 1024,
    ) : ToolRecipeStep {
        override val op: String get() = OP_FILE_READ
    }

    /** 写文件。 */
    data class FileWrite(
        val path: String,
        val content: String,
        val append: Boolean = false,
    ) : ToolRecipeStep {
        override val op: String get() = OP_FILE_WRITE
    }

    /** 查询系统 Content Provider。 */
    data class ContentQuery(
        val uri: String,
        val projection: String? = null,
        val selection: String? = null,
        val limit: Int = 50,
    ) : ToolRecipeStep {
        override val op: String get() = OP_CONTENT_QUERY
    }

    /** 组合另一个配方，把上一步结果作为输入。 */
    data class Compose(
        val recipe: String,
        val passOutput: Boolean = true,
    ) : ToolRecipeStep {
        override val op: String get() = OP_COMPOSE
    }

    /** 常量文本，用于把上一步输出拼进后续模板。 */
    data class Literal(
        val text: String,
    ) : ToolRecipeStep {
        override val op: String get() = OP_LITERAL
    }

    companion object {
        const val OP_HTTP_REQUEST = "http_request"
        const val OP_ROOT_SHELL = "root_shell"
        const val OP_SETTINGS_READ = "settings_read"
        const val OP_SETTINGS_WRITE = "settings_write"
        const val OP_FILE_READ = "file_read"
        const val OP_FILE_WRITE = "file_write"
        const val OP_CONTENT_QUERY = "content_query"
        const val OP_COMPOSE = "compose"
        const val OP_LITERAL = "literal"

        val ALL_OPS = setOf(
            OP_HTTP_REQUEST,
            OP_ROOT_SHELL,
            OP_SETTINGS_READ,
            OP_SETTINGS_WRITE,
            OP_FILE_READ,
            OP_FILE_WRITE,
            OP_CONTENT_QUERY,
            OP_COMPOSE,
            OP_LITERAL,
        )
    }
}

/** 一份配方的完整定义。 */
internal data class ToolRecipe(
    val name: String,
    val description: String,
    val parameters: JSONObject,
    val steps: List<ToolRecipeStep>,
    val enabled: Boolean = true,
    val version: Int = 1,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    /** 模型侧看到的工具名，带 `recipe_` 前缀避免与内置工具撞名。 */
    val modelToolName: String get() = ToolRecipeConstants.TOOL_PREFIX + name

    /**
     * 该配方是否需要 Root。
     *
     * 只看本配方自身：任一步骤是 [ToolRecipeStep.RootShell] 即需要。
     * 组合链上的 Root 需求由 [AgentToolRecipeStore] 依据完整配方图推导，
     * 因为这里拿不到被组合配方的定义。
     */
    val requiresRoot: Boolean
        get() = steps.any { it is ToolRecipeStep.RootShell }

    /** 组合引用的其它配方名（用于校验循环引用）。 */
    val composedRecipes: List<String>
        get() = steps.filterIsInstance<ToolRecipeStep.Compose>().map { it.recipe }

    /** 转成模型可见的 function schema。 */
    fun toSchema(): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", modelToolName)
                    .put(
                        "description",
                        buildString {
                            append("自建工具「").append(name).append("」：").append(description)
                            append(" 由模型先前创建，参数含义见下方 schema。")
                        },
                    )
                    .put("parameters", JSONObject(parameters.toString())),
            )
}

/**
 * 配方模板插值——把 `{{param}}` 替换成实参。
 *
 * 三条硬规则：
 * 1. 只替换 `{{name}}` 这种结构化占位符，模型参数永远不会成为「模板语法」。
 * 2. 替换值按目标上下文转义：URL 走 percent-encoding，shell 走单引号包裹并转义。
 * 3. 未提供的占位符原样保留（便于回读时发现缺失），而不是静默变空串。
 */
internal object ToolRecipeTemplate {

    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_]{0,39})\s*\}\}""")

    /** 收集模板里引用到的所有占位符名。 */
    fun placeholders(template: String): Set<String> =
        PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()

    /** 通用插值：不做额外转义，仅做占位符替换（用于纯文本场景）。 */
    fun interpolate(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match ->
            values[match.groupValues[1]] ?: match.value
        }

    /**
     * URL 插值：占位符值做 percent-encoding，避免 `&`、`#`、空格等破坏 URL 结构。
     * 未提供的占位符原样保留，让调用方看到「缺参数」而不是拼出坏 URL。
     */
    fun interpolateUrl(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            val value = values[key] ?: return@replace match.value
            java.net.URLEncoder.encode(value, "UTF-8")
        }

    /**
     * Shell 插值：占位符值用单引号包裹，内部单引号按 POSIX 惯例转义（`'` -> `'\''`）。
     * 这样模型传入的任何内容都只能作为「一个参数」出现，无法注入第二条命令。
     */
    fun interpolateShell(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            val value = values[key] ?: return@replace match.value
            shellQuote(value)
        }

    /** POSIX 单引号包裹。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** 配方解析与校验——把模型写的 JSON 变成可信的 [ToolRecipe]，或在出错时给出可执行的原因。 */
internal object ToolRecipeParser {

    /** 解析结果：成功给出配方，失败给出错误码与可读原因。 */
    sealed interface ParseResult {
        data class Ok(val recipe: ToolRecipe) : ParseResult
        data class Invalid(val code: String, val message: String) : ParseResult
    }

    /**
     * 解析一份配方 JSON。
     *
     * @param existingNames 当前已存在的配方名——组合目标必须在这个集合里。
     */
    fun parse(
        raw: JSONObject,
        existingNames: Set<String> = emptySet(),
        nowMillis: Long = 0L,
    ): ParseResult {
        val name = raw.optString("name").trim()
        if (!ToolRecipeConstants.TOOL_NAME_PATTERN.matches(name)) {
            return ParseResult.Invalid(
                "INVALID_RECIPE_NAME",
                "配方名必须是 3 到 40 位小写字母/数字/下划线，且以字母开头；收到「$name」",
            )
        }
        if (name in ToolRecipeReservedNames.RESERVED) {
            return ParseResult.Invalid(
                "RESERVED_RECIPE_NAME",
                "配方名「$name」与内置能力冲突，换一个名字",
            )
        }

        val description = raw.optString("description").trim()
        if (description.isBlank()) {
            return ParseResult.Invalid("INVALID_RECIPE", "配方必须有 description，说明它做什么")
        }
        if (description.length > ToolRecipeConstants.MAX_DESCRIPTION_LENGTH) {
            return ParseResult.Invalid(
                "INVALID_RECIPE",
                "description 超过 ${ToolRecipeConstants.MAX_DESCRIPTION_LENGTH} 字符",
            )
        }

        val parameters = parseParameters(raw.optJSONObject("parameters"))
        if (parameters is ParseResult.Invalid) return parameters

        val stepsArray = raw.optJSONArray("steps")
        if (stepsArray == null || stepsArray.length() == 0) {
            return ParseResult.Invalid("INVALID_RECIPE", "配方必须有至少一个 step")
        }
        if (stepsArray.length() > ToolRecipeConstants.MAX_STEPS) {
            return ParseResult.Invalid(
                "INVALID_RECIPE",
                "步骤数超过 ${ToolRecipeConstants.MAX_STEPS} 个，请拆成多个配方",
            )
        }

        val steps = mutableListOf<ToolRecipeStep>()
        for (index in 0 until stepsArray.length()) {
            val stepObject = stepsArray.optJSONObject(index)
                ?: return ParseResult.Invalid("INVALID_RECIPE", "第 ${index + 1} 个 step 不是对象")
            when (val step = parseStep(stepObject)) {
                is ParseResult.Invalid -> return ParseResult.Invalid(step.code, "第 ${index + 1} 个 step：${step.message}")
                is ParseResult.Ok -> steps += step.recipe
            }
        }

        // 组合引用必须存在，且不能指向自己。
        steps.filterIsInstance<ToolRecipeStep.Compose>().forEach { compose ->
            if (compose.recipe == name) {
                return ParseResult.Invalid("RECIPE_CYCLE", "配方不能组合自己")
            }
            if (compose.recipe !in existingNames) {
                return ParseResult.Invalid(
                    "RECIPE_NOT_FOUND",
                    "组合目标「${compose.recipe}」不存在；先创建它，或改用其它步骤",
                )
            }
        }

        val enabled = if (raw.has("enabled")) raw.optBoolean("enabled", true) else true
        val version = raw.optInt("version", 1).coerceAtLeast(1)
        return ParseResult.Ok(
            ToolRecipe(
                name = name,
                description = description,
                parameters = (parameters as ParseResult.Ok).recipe,
                steps = steps,
                enabled = enabled,
                version = version,
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
            ),
        )
    }

    private fun parseParameters(raw: JSONObject?): ParseResult {
        if (raw == null) {
            return ParseResult.Ok(JSONObject().put("type", "object").put("properties", JSONObject()))
        }
        val type = raw.optString("type", "object")
        if (type != "object") {
            return ParseResult.Invalid("INVALID_RECIPE", "parameters.type 必须是 object")
        }
        val properties = raw.optJSONObject("properties")
        if (properties == null) {
            return ParseResult.Invalid("INVALID_RECIPE", "parameters.properties 必须是对象")
        }
        val names = properties.keys().asSequence().toList()
        names.forEach { key ->
            if (!Regex("^[A-Za-z_][A-Za-z0-9_]{0,39}$").matches(key)) {
                return ParseResult.Invalid(
                    "INVALID_RECIPE",
                    "参数名「$key」非法，只允许字母、数字、下划线且以字母开头",
                )
            }
            val property = properties.optJSONObject(key) ?: return ParseResult.Invalid(
                "INVALID_RECIPE",
                "参数「$key」的定义必须是对象",
            )
            val propertyType = property.optString("type", "string")
            if (propertyType !in SUPPORTED_PARAMETER_TYPES) {
                return ParseResult.Invalid(
                    "INVALID_RECIPE",
                    "参数「$key」的 type「$propertyType」不支持；可用：${SUPPORTED_PARAMETER_TYPES.joinToString(", ")}",
                )
            }
        }
        val required = raw.optJSONArray("required")
        if (required != null) {
            for (index in 0 until required.length()) {
                val key = required.optString(index)
                if (key !in names) {
                    return ParseResult.Invalid(
                        "INVALID_RECIPE",
                        "required 里的「$key」没有在 properties 中定义",
                    )
                }
            }
        }
        val normalized = JSONObject(raw.toString())
        normalized.put("type", "object")
        normalized.put("properties", properties)
        normalized.put("additionalProperties", false)
        return ParseResult.Ok(normalized)
    }

    private fun parseStep(raw: JSONObject): ParseResult {
        val op = raw.optString("op").trim()
        if (op !in ToolRecipeStep.ALL_OPS) {
            return ParseResult.Invalid(
                "UNKNOWN_STEP_OP",
                "op「$op」不支持；可用：${ToolRecipeStep.ALL_OPS.joinToString(", ")}",
            )
        }
        return when (op) {
            ToolRecipeStep.OP_HTTP_REQUEST -> {
                val url = raw.optString("url").trim()
                if (url.isBlank()) {
                    ParseResult.Invalid("INVALID_STEP", "http_request 需要 url")
                } else if (!url.startsWith("https://") && !url.startsWith("http://")) {
                    ParseResult.Invalid("INVALID_STEP", "http_request 的 url 必须以 http:// 或 https:// 开头")
                } else {
                    val method = raw.optString("method", "GET").trim().uppercase()
                    if (method !in SUPPORTED_METHODS) {
                        ParseResult.Invalid("INVALID_STEP", "http_request 的 method「$method」不支持")
                    } else {
                        val headers = mutableMapOf<String, String>()
                        raw.optJSONObject("headers")?.let { headerObject ->
                            headerObject.keys().asSequence().forEach { key ->
                                headers[key] = headerObject.optString(key)
                            }
                        }
                        ParseResult.Ok(
                            ToolRecipeStep.HttpRequest(
                                url = url,
                                method = method,
                                headers = headers,
                                body = raw.optString("body").takeIf { raw.has("body") },
                                bodyIsJson = raw.optBoolean("body_is_json", false),
                            ),
                        )
                    }
                }
            }

            ToolRecipeStep.OP_ROOT_SHELL -> {
                val command = raw.optString("command").trim()
                if (command.isBlank()) {
                    ParseResult.Invalid("INVALID_STEP", "root_shell 需要 command")
                } else {
                    ParseResult.Ok(
                        ToolRecipeStep.RootShell(
                            command = command,
                            timeoutMillis = raw.optLong("timeout_ms", 15_000L).coerceIn(500L, 30_000L),
                        ),
                    )
                }
            }

            ToolRecipeStep.OP_SETTINGS_READ, ToolRecipeStep.OP_SETTINGS_WRITE -> {
                val namespace = raw.optString("namespace", "system").trim().lowercase()
                if (namespace !in SUPPORTED_NAMESPACES) {
                    ParseResult.Invalid("INVALID_STEP", "namespace「$namespace」不支持；可用：${SUPPORTED_NAMESPACES.joinToString(", ")}")
                } else {
                    val key = raw.optString("key").trim()
                    if (key.isBlank()) {
                        ParseResult.Invalid("INVALID_STEP", "$op 需要 key")
                    } else {
                        ParseResult.Ok(
                            if (op == ToolRecipeStep.OP_SETTINGS_READ) {
                                ToolRecipeStep.SettingsRead(namespace = namespace, key = key)
                            } else {
                                ToolRecipeStep.SettingsWrite(
                                    namespace = namespace,
                                    key = key,
                                    value = raw.optString("value"),
                                )
                            },
                        )
                    }
                }
            }

            ToolRecipeStep.OP_FILE_READ, ToolRecipeStep.OP_FILE_WRITE -> {
                val path = raw.optString("path").trim()
                if (path.isBlank()) {
                    ParseResult.Invalid("INVALID_STEP", "$op 需要 path")
                } else if (!path.startsWith("/")) {
                    ParseResult.Invalid("INVALID_STEP", "$op 的 path 必须是绝对路径")
                } else {
                    ParseResult.Ok(
                        if (op == ToolRecipeStep.OP_FILE_READ) {
                            ToolRecipeStep.FileRead(
                                path = path,
                                maxBytes = raw.optInt("max_bytes", 64 * 1024).coerceIn(1024, 512 * 1024),
                            )
                        } else {
                            ToolRecipeStep.FileWrite(
                                path = path,
                                content = raw.optString("content"),
                                append = raw.optBoolean("append", false),
                            )
                        },
                    )
                }
            }

            ToolRecipeStep.OP_CONTENT_QUERY -> {
                val uri = raw.optString("uri").trim()
                if (uri.isBlank()) {
                    ParseResult.Invalid("INVALID_STEP", "content_query 需要 uri")
                } else if (!uri.startsWith("content://")) {
                    ParseResult.Invalid("INVALID_STEP", "content_query 的 uri 必须以 content:// 开头")
                } else {
                    ParseResult.Ok(
                        ToolRecipeStep.ContentQuery(
                            uri = uri,
                            projection = raw.optString("projection").takeIf { raw.has("projection") },
                            selection = raw.optString("selection").takeIf { raw.has("selection") },
                            limit = raw.optInt("limit", 50).coerceIn(1, 500),
                        ),
                    )
                }
            }

            ToolRecipeStep.OP_COMPOSE -> {
                val recipe = raw.optString("recipe").trim()
                if (recipe.isBlank()) {
                    ParseResult.Invalid("INVALID_STEP", "compose 需要 recipe")
                } else {
                    ParseResult.Ok(
                        ToolRecipeStep.Compose(
                            recipe = recipe,
                            passOutput = raw.optBoolean("pass_output", true),
                        ),
                    )
                }
            }

            ToolRecipeStep.OP_LITERAL -> ParseResult.Ok(
                ToolRecipeStep.Literal(raw.optString("text")),
            )

            else -> ParseResult.Invalid("UNKNOWN_STEP_OP", "op「$op」不支持")
        }
    }

    private val SUPPORTED_PARAMETER_TYPES = setOf("string", "number", "integer", "boolean", "array", "object")
    private val SUPPORTED_METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH")
    private val SUPPORTED_NAMESPACES = setOf("system", "secure", "global")
}

/** 与内置能力冲突的配方名（防止模型写的配方遮蔽现有工具）。 */
internal object ToolRecipeReservedNames {
    val RESERVED: Set<String> = setOf(
        "terminal",
        "run_command",
        "read_file",
        "write_file",
        "list_directory",
        "browser_use",
        "memory_get",
        "memory_write",
        "get_current_context",
        "search_apps",
        "launch_app",
        "open_uri",
        "observe_screen",
        "set_alarm",
        "set_timer",
        "ask_user",
    )
}

/** 配方参数值的规范化——把模型的 JSON 实参转成模板可用的字符串。 */
internal object ToolRecipeArguments {

    /**
     * 从实参 JSON 提取模板占位符对应的字符串值。
     *
     * - string 直接取
     * - number/boolean 转字面量
     * - array/object 转紧凑 JSON
     * - 缺失时返回 null（模板保留原占位符）
     */
    fun flatten(arguments: JSONObject): Map<String, String> {
        val result = linkedMapOf<String, String>()
        arguments.keys().asSequence().forEach { key ->
            val value = arguments.opt(key) ?: return@forEach
            if (value == JSONObject.NULL) return@forEach
            result[key] = when (value) {
                is String -> value
                is Boolean -> value.toString()
                is Number -> value.toString()
                is JSONObject -> value.toString()
                is JSONArray -> value.toString()
                else -> value.toString()
            }
        }
        return result
    }
}
