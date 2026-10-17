package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.media.AgentHistoryImageHydrator
import io.github.mangi.eta.agent.media.isVideoMedia
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

internal object AgentModelClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    fun loadConfig(): ModelConfig {
        val runtimeJson = Prefs.getString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
        if (runtimeJson.isNotBlank()) {
            runCatching {
                json.decodeFromString<ModelConfig>(runtimeJson)
            }.getOrNull()?.let { runtime ->
                val thinkingAllowed = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
                val effort = if (thinkingAllowed) {
                    runtime.effectiveReasoningEffort
                } else {
                    ReasoningEffort.OFF
                }
                return runtime.copy(
                    terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                    browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
                    deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                    deviceSensitiveReadTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                    deviceSensitiveActionTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                    thinkingEnabled = effort.enablesReasoning,
                    reasoningEffort = effort,
                )
            }
        }
        return ModelConfig(
            providerId = "builtin-openai",
            providerName = "OpenAI",
            providerType = ProviderTypes.OPENAI_COMPATIBLE,
            providerSourceType = ProviderSourceRegistry.resolve(
                providerId = "builtin-openai",
                baseUrl = "https://api.openai.com/v1",
                providerType = ProviderTypes.OPENAI_COMPATIBLE,
            ),
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            model = "gpt-5.5",
            modelDisplayName = "GPT-5.5",
            systemPrompt = BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            thinkingEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
            reasoningEffort = ReasoningEffort.fromLegacy(
                Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
            ),
        )
    }

    fun complete(
        config: ModelConfig,
        prompt: String,
        toolExecutor: ToolExecutor,
        images: List<ModelImage> = emptyList(),
        history: List<ConversationMessage> = emptyList(),
        provider: AgentProviderClient = ProviderClientFactory.getClient(config),
        runController: AgentRunController = AgentRunController(),
        skillContext: SkillContext = SkillContext.EMPTY,
        memoryContext: AgentMemoryContext = AgentMemoryContext.DISABLED,
        skillContextProvider: () -> SkillContext = { skillContext },
        memoryContextProvider: () -> AgentMemoryContext = { memoryContext },
        additionalTools: JSONArray = JSONArray(),
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
        sessionId: String = java.util.UUID.randomUUID().toString(),
        onEvent: (AgentEvent) -> Unit = {},
        linuxEnvironmentLabelProvider: () -> String = { "Linux" },
        terminalSessionEnvironmentProvider: (String) -> String? = { null },
        terminalSessionIdentityProvider: (String) -> String? = { null },
        skipHistoryTrimming: Boolean = false,
        compactPolicy: AgentLoop.CompactPolicy = AgentLoop.CompactPolicy.Disabled,
        compactionArchive: AgentCompactionArchive? = null,
        turnId: String = java.util.UUID.randomUUID().toString(),
        calibratedInputTokens: Int? = null,
        allowUnmeasuredContextSend: Boolean = false,
        onHistorySnapshot: ((Int, List<ConversationMessage>) -> String)? = null,
        // The parent owner freezes this value; other model callers do not inherit its preference.
        interactiveModeEnabled: Boolean = false,
    ): ModelResponse.Text {
        config.validate()
        val initialCapabilities = capabilitiesProvider()
        // Never trim by individual message count/size here. Runtime compaction selects balanced
        // ranges and the loop pauses when the protected request cannot fit.
        val trimmedHistory = AgentTurnIdentity.migrate(history)
        val imageInput = config.supportsVision || ModelFeaturePreferences.visionEnabled()
        val outboundImages = images.filter { image ->
            if (image.isVideoMedia()) config.supportsVideo else imageInput
        }
        val outboundHistory = if (!config.supportsVision && imageInput) trimmedHistory else AgentHistoryImageHydrator.hydrateAll(
            history = trimmedHistory,
            supportsVision = imageInput,
            supportsVideo = config.supportsVideo,
        )
        // Provider hydration must never erase durable media in a read-only branch snapshot.
        // Match the exact sanitized DTO, not a bare turn id (supplements share turn ids).
        val snapshotMedia = if (onHistorySnapshot == null) null else runCatching {
            trimmedHistory.zip(outboundHistory).filter { (durable, _) ->
                AgentConversationCodec.persistedImageSources(durable).isNotEmpty()
            }.groupBy({ (_, outbound) -> AgentConversationCodec.durableMessage(AgentConversationCodec.toJsonObject(outbound)) },
                { (durable, _) -> durable })
        }.getOrNull()
        val delegationAvailable = AgentPromptBuilder.delegationToolsAvailable(additionalTools)
        val messages = AgentPromptBuilder.buildInitialMessages(
            config,
            prompt,
            outboundImages,
            outboundHistory,
            skillContext,
            memoryContext,
            rootAvailable = initialCapabilities.rootAvailable,
            delegationAvailable = delegationAvailable,
            shellTools = initialCapabilities.shellTools,
            interactiveModeEnabled = interactiveModeEnabled,
        )
        val systemCount = AgentPromptBuilder.buildSystemMessages(
            config,
            skillContext,
            memoryContext,
            rootAvailable = initialCapabilities.rootAvailable,
            delegationAvailable = delegationAvailable,
            shellTools = initialCapabilities.shellTools,
            interactiveModeEnabled = interactiveModeEnabled,
        ).length()
        var transcriptStartIndex = messages.length()
        fun toolsFor(
            capabilities: AgentToolCapabilities,
            memoryEnabled: Boolean = memoryContext.enabled,
        ): JSONArray {
            val tools = AgentToolCatalog.build(
                terminalTools = config.terminalTools,
                browserTools = config.browserTools,
                deviceDirectTools = config.deviceDirectTools,
                deviceSensitiveReadTools = config.deviceSensitiveReadTools,
                deviceSensitiveActionTools = config.deviceSensitiveActionTools,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                memoryTools = memoryEnabled,
                selfSettingsTools = Prefs.isEnabled(Prefs.Keys.AGENT_SELF_SETTINGS_TOOLS),
                capabilities = capabilities,
            )
            for (index in 0 until additionalTools.length()) {
                tools.put(additionalTools.opt(index))
            }
            if (compactionArchive != null) tools.put(AgentCompactionArchive.tool())
            return tools
        }
        val tools = toolsFor(initialCapabilities)
        val traceFormatter = AgentTraceFormatter(
            linuxEnvironmentLabelProvider = linuxEnvironmentLabelProvider,
            terminalSessionEnvironmentProvider = terminalSessionEnvironmentProvider,
            terminalSessionIdentityProvider = terminalSessionIdentityProvider,
        )
        onEvent(
            AgentEvent.RunStarted(
                initialImages = images.size,
                initialImageBytes = images.sumOf { it.bytes },
                toolCount = tools.length(),
                terminalTools = config.terminalTools
            )
        )
        var promptRootAvailable = initialCapabilities.rootAvailable
        val loop = AgentLoop(
            sessionId = sessionId,
            config = config,
            messages = messages,
            tools = tools,
            provider = provider,
            toolExecutor = toolExecutor,
            runController = runController,
            traceFormatter = traceFormatter,
            onEvent = onEvent,
            compactPolicy = compactPolicy,
            systemCount = systemCount,
            compactionArchive = compactionArchive,
            turnId = turnId,
            calibratedInputTokens = calibratedInputTokens,
            allowUnmeasuredContextSend = allowUnmeasuredContextSend,
            onHistoryCompacted = { transcriptStartIndex = messages.length() },
            onHistorySnapshot = onHistorySnapshot?.let { publish ->
                snapshot@ { round, snapshot ->
                    val media = snapshotMedia ?: return@snapshot ""
                    val restored = snapshot.map { message ->
                        val originals = media[message].orEmpty().distinct()
                        if (originals.size > 1) return@snapshot ""
                        originals.singleOrNull() ?: message
                    }
                    publish(round, restored)
                }
            },
            toolsForRound = {
                val capabilities = capabilitiesProvider()
                val nextSkillContext = skillContextProvider()
                val nextMemoryContext = memoryContextProvider()
                val systemMessages = AgentPromptBuilder.buildSystemMessages(
                    config, nextSkillContext, nextMemoryContext, capabilities.rootAvailable, delegationAvailable,
                    capabilities.shellTools,
                    interactiveModeEnabled = interactiveModeEnabled,
                )
                for (index in 0 until systemMessages.length()) {
                    messages.put(index, systemMessages.getJSONObject(index))
                }
                promptRootAvailable = capabilities.rootAvailable
                toolsFor(capabilities, nextMemoryContext.enabled)
            },
        )
        val result = try {
            loop.run()
        } catch (throwable: Throwable) {
            loop.preserveIncompleteResponse()
            if (runController.isCancelled || throwable is AgentRunCancelledException) {
                AgentStoppedHistory.closePendingTools(messages, turnId)
                runController.takeStoppedSteering().forEach { supplement ->
                    messages.put(AgentSupplementMedia.userMessage(
                        AgentContextCompactor.steeringUserContent(supplement.text), supplement.imagesJson,
                    ).put(AgentTurnIdentity.JSON_KEY, turnId))
                }
                throw AgentRunCancelledException(
                    transcript = AgentConversationCodec.transcript(
                        messages, transcriptStartIndex, loop.sensitiveToolCallIdsSnapshot(),
                    ),
                    reasoningContent = loop.reasoningSnapshot(),
                )
            }
            throw AgentModelExecutionException(
                cause = throwable,
                reasoningContent = loop.reasoningSnapshot(),
                transcript = AgentConversationCodec.transcript(
                    messages,
                    transcriptStartIndex,
                    loop.sensitiveToolCallIdsSnapshot(),
                ),
            )
        }
        return ModelResponse.Text(
            content = result.content,
            reasoningContent = result.reasoningContent,
            transcript = AgentConversationCodec.transcript(
                messages,
                transcriptStartIndex,
                result.sensitiveToolCallIds,
            ),
        )
    }

    private fun ModelConfig.validate() {
        require(baseUrl.isNotBlank()) { "请先配置 API 地址" }
        require(apiKey.isNotBlank()) { "请先配置 API Key" }
        require(model.isNotBlank()) { "请先配置模型名" }
        require(
            reasoningCapabilities?.mandatory != true ||
                effectiveReasoningEffort != ReasoningEffort.OFF
        ) { "当前模型强制启用推理，不能选择 Off 或禁用思考权限" }
        if (extraBodyJson.isNotBlank()) {
            runCatching { JSONObject(extraBodyJson) }
                .getOrElse { throwable ->
                    error("额外请求体 JSON 无效：${throwable.message ?: throwable.javaClass.simpleName}")
                }
        }
    }

    fun buildUserHistoryMessage(
        text: String,
        images: List<ModelImage> = emptyList(),
        persistedImages: List<AgentConversationCodec.PersistedImage> = emptyList(),
    ): ConversationMessage =
        AgentConversationCodec.durableMessage(
            if (persistedImages.isNotEmpty()) {
                AgentConversationCodec.userPersistedImageMessage(text, persistedImages)
            } else {
                AgentConversationCodec.userMessage(text, images)
            },
        )

    internal fun summarizeOpenUriArguments(argumentsJson: String): String =
        AgentTraceFormatter().summarizeOpenUriArguments(argumentsJson)

    internal fun summarizeBrowserToolArguments(argumentsJson: String): String =
        AgentTraceFormatter().summarizeBrowserArguments(argumentsJson)

    internal fun summarizeToolResult(toolName: String, result: ToolResult): String =
        AgentTraceFormatter().summarizeResult(toolName, result)

    @Serializable
    data class ModelConfig(
        val providerId: String = "",
        val providerName: String = "",
        val providerType: String = ProviderTypes.OPENAI_COMPATIBLE,
        val providerSourceType: String = "",
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val modelDisplayName: String = "",
        val contextWindow: Int? = null,
        val summaryOutputLimit: Int? = null,
        val systemPrompt: String,
        val anthropicVersion: String = AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        val openAiEndpointMode: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
        val responsesStripReasoningStatus: Boolean = false,
        val hostedWebSearchEnabled: Boolean = false,
        val terminalTools: Boolean = false,
        val browserTools: Boolean = true,
        val deviceDirectTools: Boolean = true,
        val deviceSensitiveReadTools: Boolean = false,
        val deviceSensitiveActionTools: Boolean = false,
        val thinkingEnabled: Boolean = false,
        val reasoningEffort: ReasoningEffort? = null,
        val reasoningCapabilities: ModelReasoningCapabilities? = null,
        val extraBodyJson: String = "",
        val customHeaders: List<CustomHeader> = emptyList(),
        val customBody: List<CustomBody> = emptyList(),
        val sessionModelPattern: String = "^gpt-.*$",
        val sessionPathPattern: String = "/v1/responses",
        val sessionKeyField: String = "prompt_cache_key",
        val supportsVision: Boolean = false,
        val supportsVideo: Boolean = false,
        val assistantId: String = "",
        val gptSpeedMode: GptSpeedMode? = null,
        val errorReconnectPolicy: String = "none",
    ) {
        val effectiveReasoningEffort: ReasoningEffort
            get() = reasoningEffort ?: ReasoningEffort.fromLegacy(thinkingEnabled)
    }

    @Serializable
    data class ConversationMessage(
        val role: String,
        val content: String = "",
        val contentJson: String = "",
        val toolCallId: String = "",
        val reasoningContent: String = "",
        val toolCallsJson: String = "",
        val turnId: String = "",
        val responsesReasoningJson: String = "",
    )

    fun interface ToolExecutor {
        fun execute(toolCall: ToolCall): ToolResult
    }

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String
    )

    data class ToolResult(
        val content: String,
        val images: List<ModelImage> = emptyList(),
        /**
         * 敏感结果仍会供当前 Agent loop 使用，但工具参数与原始结果不会进入持久会话。
         * 最终 assistant 自己组织的答复不受此标记影响。
         */
        val sensitive: Boolean = false,
    )

    /** 图片引用：入口侧可为本地 URI/路径，进入模型协议前必须解析为远程 URL 或 data URL。 */
    data class ModelImage(
        val reference: String,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown"
    )

    sealed interface ModelResponse {
        data class Text(
            val content: String,
            val reasoningContent: String = "",
            val transcript: List<ConversationMessage> = emptyList(),
        ) : ModelResponse
    }

}

internal class AgentModelExecutionException(
    cause: Throwable,
    val reasoningContent: String,
    val transcript: List<AgentModelClient.ConversationMessage>,
) : RuntimeException(cause.message ?: cause.javaClass.simpleName, cause)
