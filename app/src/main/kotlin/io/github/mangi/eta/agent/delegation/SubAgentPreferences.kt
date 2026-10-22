package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.MediaReasoningSettings
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject

/** Whole-list writes are atomic; stable IDs, not list positions, own model and reasoning settings. */
internal object SubAgentPreferences {
    internal const val PROFILES_KEY = "agent_child_profiles_v1"
    private val revision = MutableStateFlow(0L)
    fun profilesFlow() = revision.map { profiles() }.distinctUntilChanged()

    @Synchronized fun profiles(): List<SubAgentProfile> {
        return repository().legacyProfiles()
    }

    private fun repository() = ConversationSubAgentPreferences()
    private fun persist(profiles: List<SubAgentProfile>, confirmed: SubAgentProfile? = null) {
        repository().saveLegacyConfiguration(profiles = profiles, confirmedProfile = confirmed)
        revision.value += 1
    }
    @Synchronized fun add(): SubAgentProfile {
        val current = profiles()
        var number = current.size + 1
        while (current.any { it.name == "子代理 $number" }) number++
        val profile = SubAgentProfile(UUID.randomUUID().toString(), "子代理 $number")
        persist(current + profile)
        return profile
    }
    @Synchronized fun update(id: String, change: (SubAgentProfile) -> SubAgentProfile) {
        val current = profiles()
        if (current.none { it.id == id }) return // A stale dialog must not recreate a deleted profile.
        val next = current.map { old -> if (old.id == id) change(old).normalizedTaskTier().also { require(it.id == old.id) } else old }
        persist(next, next.single { it.id == id })
    }
    @Synchronized fun remove(id: String) { persist(profiles().filterNot { it.id == id }) }
    @Synchronized fun saveModel(id: String, selection: ModelFeatureSelection) {
        require(selection.providerId.isBlank() == selection.modelId.isBlank())
        val current = profiles()
        val old = current.singleOrNull { it.id == id } ?: return
        val blank = old.copy(providerId = selection.providerId, modelId = selection.modelId, enabled = true,
            tier = null, reasoning = null, imageResolution = null, reasoningByModel = emptyMap(), gptSpeedByModel = emptyMap())
        val store = repository()
        val memory = store.modelDefaults(blank)
        val next = if (old.providerId == selection.providerId && old.modelId == selection.modelId) old
            else memory?.restore(blank) ?: blank
        val binding = memory?.apiModel?.let { SubAgentParallelModel(next.providerId, it) }
        store.saveLegacyConfiguration(profiles = current.map { if (it.id == id) next else it },
            confirmedProfile = next, binding = binding, parallelLimit = memory?.parallelLimit)
        revision.value += 1
    }
    fun applyReasoning(profile: SubAgentProfile, config: AgentModelClient.ModelConfig): AgentModelClient.ModelConfig {
        if (profile.isMedia) {
            val media = MediaReasoningSettings.resolve(config, profile.role)
            // Keep a stale explicit choice visible; generation rejects it rather than silently changing it.
            val effort = media.effective(profile.reasoning)
            return config.copy(reasoningEffort = effort, thinkingEnabled = effort.enablesReasoning)
        }
        val requested = profile.reasoning ?: return config
        val effort = config.reasoningCapabilities?.normalize(requested) ?: ReasoningEffort.OFF
        return config.copy(reasoningEffort = effort, thinkingEnabled = effort.enablesReasoning)
    }
    fun applyImageResolution(profile: SubAgentProfile, config: AgentModelClient.ModelConfig): AgentModelClient.ModelConfig {
        val tier = profile.imageResolution?.takeIf { profile.role == "image_generation" } ?: return config
        require(tier in io.github.mangi.eta.agent.model.ImageResolutionTier.values)
        val body = if (config.extraBodyJson.isBlank()) JSONObject() else JSONObject(config.extraBodyJson)
        io.github.mangi.eta.agent.model.RequestBodyMerge.mergeCustomBody(body, config.customBody)
        body.remove("size")
        body.put("resolution", tier)
        return config.copy(extraBodyJson = body.toString(), customBody = emptyList())
    }
    fun workerDescription(profile: SubAgentProfile, workerNumber: Int, config: AgentModelClient.ModelConfig, parallelLimit: Int = parallelLimit(config.providerId, config.model)): String {
        val tier = if (!profile.supportsTaskTier) "not applicable (only implementation agents have task tiers)" else profile.tier?.let { "${it.wireValue}; suited tasks: ${it.routingHint}" }
            ?: "unspecified; capability unknown"
        return "$workerNumber: agent_id=${profile.id}, name=${profile.name}, role=${profile.role} — " +
            "${config.providerName} / ${config.modelDisplayName.ifBlank { config.model }}; " +
            "image resolution default=${profile.imageResolution ?: "endpoint default"}; shared provider/model parallel limit=${parallelLimit.let { if (it == 0) "unlimited" else it.toString() }}; user-assigned task tier=$tier; reasoning=${if (profile.isMedia) {
                val media = MediaReasoningSettings.resolve(config, profile.role)
                if (media.status == MediaReasoningSettings.Status.SUPPORTED)
                    "${config.effectiveReasoningEffort.wireValue} (explicit media endpoint mapping; not a text reasoning loop)"
                else if (media.status == MediaReasoningSettings.Status.UNSUPPORTED) "current endpoint not adapted for native thinking"
                else "media thinking configuration invalid"
            } else config.effectiveReasoningEffort.wireValue}"
    }

    private fun parallelKey(providerId: String, model: String): String = "agent_model_parallel_" +
        java.security.MessageDigest.getInstance("SHA-256").digest((providerId + "\u0000" + model).toByteArray())
            .joinToString("") { "%02x".format(it) }
    fun parallelLimit(providerId: String, model: String): Int =
        Prefs.getString(parallelKey(providerId, model), "1").toIntOrNull()?.takeIf { it >= 0 } ?: 1
    fun parallelLimitFlow(providerId: String, model: String) =
        revision.map { parallelLimit(providerId, model) }.distinctUntilChanged()

    /** A stale row must not save after the profile is removed or rebound to another model. */
    @Synchronized fun saveProfileParallelLimit(profileId: String, providerId: String, modelId: String,
        apiModel: String, limit: Int): Boolean {
        val profile = profiles().firstOrNull { it.id == profileId } ?: return false
        if (profile.providerId != providerId || profile.modelId != modelId) return false
        require(apiModel.isNotBlank() && limit >= 0)
        repository().saveLegacyConfiguration(confirmedProfile = profile,
            binding = SubAgentParallelModel(providerId, apiModel), parallelLimit = limit)
        SubAgentModelPools.configure(providerId + "\u0000" + apiModel, limit)
        revision.value += 1
        return true
    }

    @Synchronized fun saveParallelLimit(providerId: String, model: String, limit: Int) {
        require(providerId.isNotBlank() && model.isNotBlank() && limit >= 0)
        repository().saveLegacyConfiguration(binding = SubAgentParallelModel(providerId, model), parallelLimit = limit)
        SubAgentModelPools.configure(providerId + "\u0000" + model, limit)
        revision.value += 1
    }

    // Compatibility for old saved slot references and migration tests; runtime/UI use profiles exclusively.
    const val SLOT_COUNT = 4
    val displayOrder = listOf(0, 2, 3, 1)
    private fun legacyLabel(slot: Int) = when (slot) {
        0 -> "执行代理 1"
        1 -> "审查／总结代理"
        2 -> "执行代理 2"
        3 -> "执行代理 3"
        else -> error("Invalid legacy slot")
    }
    private fun legacy(slot: Int) = profiles().first { it.id == "legacy-$slot" }
    fun label(slot: Int) = legacy(slot).name
    fun role(slot: Int) = legacy(slot).role
    fun selection(slot: Int) = legacy(slot).selection
    fun save(slot: Int, selection: ModelFeatureSelection) = saveModel(legacy(slot).id, selection)
    fun reasoning(slot: Int) = legacy(slot).reasoning
    fun saveReasoning(slot: Int, effort: ReasoningEffort?) = update(legacy(slot).id) { it.copy(reasoning = effort) }
    fun applyReasoning(slot: Int, config: AgentModelClient.ModelConfig) = applyReasoning(legacy(slot), config)

    private fun key(conversation: String?) = "agent_collaboration_${conversation ?: "draft"}"
    fun enabled(conversation: String?) = Prefs.getString(key(conversation), "false") == "true"
    fun setEnabled(conversation: String?, enabled: Boolean) = Prefs.putString(key(conversation), enabled.toString())
    fun promote(conversation: String) {
        setEnabled(conversation, enabled(null))
        setEnabled(null, false)
    }
}
