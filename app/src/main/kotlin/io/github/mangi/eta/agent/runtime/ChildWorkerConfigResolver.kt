package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.isGptSpeedModel
import io.github.mangi.eta.data.model.supportsGptSpeedBinding
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.json.JSONObject

/**
 * Exact-selection replacement for RunExecutor's runCatching/mapNotNull configuration loop.
 * Every requested worker produces either one selected configuration or a safe, explicit reason.
 * Never selects the first model/provider/worker as a fallback. Not used to re-resolve old snapshots.
 */
internal object ChildWorkerConfigResolver {
    // Do not make this a data class: model contains credentials and must not appear in toString.
    class Configuration(val profile: SubAgentProfile, val model: AgentModelClient.ModelConfig)

    /**
     * Lookups are nullable callbacks, not default suspend lambdas: a suspend lambda used as a
     * default argument value currently crashes the Kotlin IR backend ("has no continuation").
     * null means "not injected" and runs the persisted default in [resolveWorker]; an injected
     * callback that itself returns null must fail closed instead of reaching ProviderRepository.
     */
    suspend fun resolve(
        ownerId: String,
        config: ConversationSubAgentConfig,
        providerLookup: (suspend (String) -> ProviderSetting?)? = null,
        modelResolver: (suspend (SubAgentProfile) -> AgentModelClient.ModelConfig?)? = null,
        parentConfig: AgentModelClient.ModelConfig? = null,
    ): List<ChildTaskConfigPolicy.Candidate<Configuration>> = config.profiles.map { profile ->
        resolveWorker(ownerId, config, profile.id, profile.role, providerLookup, modelResolver, parentConfig)
    }

    /** Resolve by stable ID, never by a position in the filtered configuredChildren list. */
    suspend fun resolveWorker(
        ownerId: String,
        config: ConversationSubAgentConfig,
        workerId: String,
        expectedRole: String,
        providerLookup: (suspend (String) -> ProviderSetting?)? = null,
        modelResolver: (suspend (SubAgentProfile) -> AgentModelClient.ModelConfig?)? = null,
        parentConfig: AgentModelClient.ModelConfig? = null,
    ): ChildTaskConfigPolicy.Candidate<Configuration> {
        val expected = ChildTaskConfigPolicy.WorkerKey(ownerId, workerId, expectedRole)
        fun unavailable(reason: ChildTaskConfigPolicy.Availability) =
            ChildTaskConfigPolicy.Candidate<Configuration>(expected, reason)
        if (!config.enabled) return unavailable(ChildTaskConfigPolicy.Availability.DELEGATION_DISABLED)
        val profiles = config.profiles.filter { it.id == workerId }
        if (profiles.isEmpty()) return unavailable(ChildTaskConfigPolicy.Availability.WORKER_REMOVED)
        if (profiles.size != 1) return unavailable(ChildTaskConfigPolicy.Availability.INVALID_CONFIGURATION)
        val profile = profiles.single().let { it.copy(reasoningByModel = it.reasoningByModel.toMap(), gptSpeedByModel = it.gptSpeedByModel.toMap()) }
        if (!profile.enabled) return unavailable(ChildTaskConfigPolicy.Availability.WORKER_DISABLED)
        if (profile.role != expectedRole) return unavailable(ChildTaskConfigPolicy.Availability.ROLE_INCOMPATIBLE)
        if (profile.providerId.isBlank() || profile.modelId.isBlank())
            return unavailable(ChildTaskConfigPolicy.Availability.SELECTION_MISSING)
        try {
            val provider = (if (providerLookup != null) providerLookup(profile.providerId)
                else ProviderRepository.providerById(profile.providerId))?.takeIf { it.isEnabled }
                ?: return unavailable(ChildTaskConfigPolicy.Availability.PROVIDER_UNAVAILABLE)
            val model = provider.models.firstOrNull { it.id == profile.modelId && it.isEnabled }
                ?: return unavailable(ChildTaskConfigPolicy.Availability.MODEL_UNAVAILABLE)
            if (model.supportsSpeechSynthesis || !profile.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration))
                return unavailable(ChildTaskConfigPolicy.Availability.ROLE_INCOMPATIBLE)
            // The revision is based on persisted user settings BEFORE OAuth token refresh. A parent
            // network failure, token renewal, profile rename or another worker's edit is not a change.
            val revision = userConfigurationRevision(profile, RuntimeConfigRepository.buildRuntimeConfig(provider, model),
                supportsGptSpeedBinding(provider, model))
            val resolved = if (modelResolver != null) modelResolver(profile)
                else profile.selection.resolve(generationRole = profile.role.takeIf { profile.isMedia })
            if (resolved == null) return unavailable(ChildTaskConfigPolicy.Availability.MODEL_UNAVAILABLE)
            if (resolved.providerId != profile.providerId || resolved.model != model.modelId.trim())
                return unavailable(ChildTaskConfigPolicy.Availability.SELECTION_CHANGED_DURING_RESOLUTION)
            val currentProvider = (if (providerLookup != null) providerLookup(profile.providerId)
                else ProviderRepository.providerById(profile.providerId))?.takeIf { it.isEnabled }
                ?: return unavailable(ChildTaskConfigPolicy.Availability.PROVIDER_UNAVAILABLE)
            val currentModel = currentProvider.models.firstOrNull { it.id == profile.modelId && it.isEnabled }
                ?: return unavailable(ChildTaskConfigPolicy.Availability.MODEL_UNAVAILABLE)
            val speedEligible = supportsGptSpeedBinding(currentProvider, currentModel)
            if (revision != userConfigurationRevision(profile, RuntimeConfigRepository.buildRuntimeConfig(currentProvider, currentModel), speedEligible))
                return unavailable(ChildTaskConfigPolicy.Availability.SELECTION_CHANGED_DURING_RESOLUTION)
            val configured = applyProfile(profile, resolved, speedEligible).let {
                it.copy(customHeaders = it.customHeaders.toList(), customBody = it.customBody.toList(),
                    errorReconnectPolicy = parentConfig?.errorReconnectPolicy ?: it.errorReconnectPolicy)
            }
            // 订阅连接（Codex / Grok）不用 apiKey：凭据由运行时按 providerId 解析并注入，
            // 这里要求它非空会把可用的订阅子代理误判成缺凭据。
            if (!ProviderAuthModes.isOAuth(configured.authMode) && configured.apiKey.isBlank()) {
                return unavailable(ChildTaskConfigPolicy.Availability.CREDENTIALS_MISSING)
            }
            if (configured.baseUrl.isBlank()) return unavailable(ChildTaskConfigPolicy.Availability.ENDPOINT_MISSING)
            if (configured.model.isBlank()) return unavailable(ChildTaskConfigPolicy.Availability.MODEL_NAME_MISSING)
            if (configured.reasoningCapabilities?.mandatory == true && !configured.effectiveReasoningEffort.enablesReasoning)
                return unavailable(ChildTaskConfigPolicy.Availability.INVALID_CONFIGURATION)
            if (configured.extraBodyJson.isNotBlank()) JSONObject(configured.extraBodyJson)
            return ChildTaskConfigPolicy.Candidate(expected, ChildTaskConfigPolicy.Availability.AVAILABLE,
                revision, Configuration(profile, configured))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: AgentRunCancelledException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: IllegalArgumentException) {
            return unavailable(ChildTaskConfigPolicy.Availability.INVALID_CONFIGURATION)
        } catch (_: org.json.JSONException) {
            return unavailable(ChildTaskConfigPolicy.Availability.INVALID_CONFIGURATION)
        } catch (_: Exception) {
            // Exception text may contain endpoints/headers/credentials. Never report it to the model.
            return unavailable(ChildTaskConfigPolicy.Availability.RESOLUTION_FAILED)
        }
    }

    /** Safe tool-facing DTO. Neither the configuration, revision nor credentials are serialized. */
    fun describe(candidate: ChildTaskConfigPolicy.Candidate<Configuration>): JSONObject = JSONObject()
        .put("agent_id", candidate.worker.workerId)
        .put("role", candidate.worker.role)
        .put("configuration_available", candidate.availability == ChildTaskConfigPolicy.Availability.AVAILABLE)
        .put("configuration_code", candidate.availability.name)
        .put("configuration_reason", candidate.availability.reason)

    fun <C : Any> describe(decision: ChildTaskConfigPolicy.Decision<C>): JSONObject = JSONObject()
        .put("configuration_available", decision.available)
        .put("configuration_source", decision.source.name)
        .put("configuration_code", decision.code.name)
        .put("configuration_reason", decision.reason)
        .also { json ->
            decision.availability?.let { json.put("selected_configuration_code", it.name) }
            decision.predecessor?.let { json.put("replaces_task_id", it.taskId) }
        }

    /** In-memory equality token only; do not persist/log/send it as a configuration description. */
    internal fun userConfigurationRevision(profile: SubAgentProfile, userModel: AgentModelClient.ModelConfig,
        speedEligible: Boolean = runtimeSupportsGptSpeed(userModel),
    ): String {
        val execution = applyProfile(profile, userModel, speedEligible).copy(providerName = "", modelDisplayName = "")
        val values = listOf(profile.id, profile.role, profile.providerId, profile.modelId,
            profile.reasoning?.wireValue.orEmpty(), profile.imageResolution.orEmpty(),
            profile.tier?.wireValue.orEmpty(), RuntimeConfigRepository.runtimeConfigJson(execution))
        val canonical = values.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun runtimeSupportsGptSpeed(model: AgentModelClient.ModelConfig): Boolean =
        isGptSpeedModel(model.model)

    private fun applyProfile(profile: SubAgentProfile, model: AgentModelClient.ModelConfig, speedEligible: Boolean) =
        SubAgentPreferences.applyImageResolution(profile, SubAgentPreferences.applyReasoning(profile, model)).copy(
            // Always replace the incoming value: a child never inherits the parent's speed selection.
            gptSpeedMode = if (speedEligible && runtimeSupportsGptSpeed(model))
                profile.gptSpeedForModel() else null,
        )
}
