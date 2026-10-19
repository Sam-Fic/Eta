package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.agent.runtime.localizedText
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.supportsGptSpeedBinding
import io.github.mangi.eta.data.repository.ProviderRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

internal data class SubAgentParallelLimitChange(val model: SubAgentParallelModel, val value: Int, val expected: Int)

internal sealed interface SubAgentEditorState {
    data object Loading : SubAgentEditorState
    data class Loaded(val config: ConversationSubAgentConfig) : SubAgentEditorState
    data class Error(val reason: String) : SubAgentEditorState
}

/** Capture this object when opening a picker: callbacks must never retarget the currently selected owner. */
internal class ConversationSubAgentEditor(
    val owner: SubAgentConfigKey,
    val repository: ConversationSubAgentPreferences,
    private val providerLookup: suspend (String) -> ProviderSetting?,
    val canEdit: () -> Boolean,
) {
    constructor(owner: SubAgentConfigKey, repository: ConversationSubAgentPreferences, canEdit: () -> Boolean) :
        this(owner, repository, { id -> ProviderRepository.providerById(id) }, canEdit)

    private class LostOwner : RuntimeException()
    private var storedState: SubAgentEditorState by mutableStateOf(SubAgentEditorState.Loading)
    private var lifecycleFailure: (() -> String?)? = null
    val state: SubAgentEditorState
        get() = lifecycleFailure?.invoke()?.let { SubAgentEditorState.Error(it) } ?: storedState
    private var retryVersion by mutableIntStateOf(0)
    private var disposed = false
    val isDisposed: Boolean get() = disposed
    val enabled: Boolean get() = !disposed && state is SubAgentEditorState.Loaded && canEdit()
    /** Irreversible: callbacks retained by a dismissed page must not revive on re-entry. */
    fun dispose() { disposed = true }

    /** Rows capture a whole application, not the root editor's subsequently refreshed state. */
    fun scoped(applicationToken: String?, gate: () -> Boolean = { true }): ConversationSubAgentEditor {
        val root = this
        return ConversationSubAgentEditor(owner, repository, providerLookup) {
            root.enabled && gate() && root.applicationMatches(applicationToken)
        }
    }
    private fun applicationMatches(token: String?): Boolean = try {
        repository.snapshot(owner).presetApplicationToken == token
    } catch (_: Exception) { false }

    fun applyPreset(id: String, canApply: () -> Boolean = { true }): ConversationSubAgentPreferences.WriteResult {
        if (!enabled || !canApply()) return ConversationSubAgentPreferences.WriteResult.Rejected
        return try {
            repository.applyPreset(owner, id) { enabled && canApply() }.also { result ->
                if (result is ConversationSubAgentPreferences.WriteResult.Saved)
                    storedState = SubAgentEditorState.Loaded(result.config)
            }
        } catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }
    private fun fail(failure: Exception) {
        if (failure is CancellationException) throw failure
        storedState = SubAgentEditorState.Error(failure.message ?: failure.javaClass.simpleName)
    }
    private var lifecycleRecovery: (() -> Boolean)? = null
    init {
        try { storedState = SubAgentEditorState.Loaded(repository.snapshot(owner)) }
        catch (failure: Exception) { fail(failure) }
    }
    fun bindLifecycleState(reason: () -> String?, recovery: () -> Boolean) {
        lifecycleFailure = reason
        lifecycleRecovery = recovery
    }
    /** Explicitly recover the durability fence, then restart the snapshot + live subscription. */
    fun retry() {
        try {
            check(repository.recoverDurability()) { "子代理配置恢复失败；请重试" }
            if (lifecycleFailure?.invoke() != null) {
                check(lifecycleRecovery?.invoke() == true) { "会话配置尚未恢复，原配置已保留" }
            }
            storedState = SubAgentEditorState.Loading
            retryVersion++
        } catch (failure: Exception) { fail(failure) }
    }
    @Composable
    fun observe(): SubAgentEditorState {
        val version = retryVersion
        LaunchedEffect(this, version) {
            // Opening another page cannot silently clear an error: only the retry action may do so.
            if (state is SubAgentEditorState.Error) return@LaunchedEffect
            try {
                storedState = SubAgentEditorState.Loaded(repository.snapshot(owner))
                repository.flow(owner).collect { config ->
                    if (state !is SubAgentEditorState.Error) storedState = SubAgentEditorState.Loaded(config)
                }
            } catch (failure: Exception) { fail(failure) }
        }
        return state
    }
    fun update(change: (ConversationSubAgentConfig) -> ConversationSubAgentConfig): ConversationSubAgentPreferences.WriteResult {
        if (!enabled) return ConversationSubAgentPreferences.WriteResult.Rejected
        val observedToken = (state as? SubAgentEditorState.Loaded)?.config?.presetApplicationToken
        return try {
            repository.update(owner) { old ->
                if (!enabled || old.presetApplicationToken != observedToken) throw LostOwner()
                change(old)
            }
        } catch (_: LostOwner) { ConversationSubAgentPreferences.WriteResult.Rejected }
          catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }
    private val resolvedBindings = mutableMapOf<String, SubAgentParallelModel>()

    /** No owner, directory, pool, seed, or memory write until explicit confirmation. */
    fun newProfileDraft(): SubAgentProfile {
        val current = repository.previewSnapshot(owner)
        // 默认名走字符串资源；纯 JVM 单测无 Context 时由 localizedText 回退中文基准。
        fun defaultName(n: Int) = localizedText(R.string.subagent_default_name, "子代理 %1\$d", n)
        var number = current.profiles.size + 1
        while (current.profiles.any { it.name == defaultName(number) }) number++
        val name = defaultName(number)
        return SubAgentProfile(UUID.randomUUID().toString(), name)
    }

    fun changeProfileModel(profile: SubAgentProfile, selection: ModelFeatureSelection): SubAgentProfile {
        require(selection.providerId.isBlank() == selection.modelId.isBlank()) { "Incomplete model selection" }
        val blank = profile.copy(providerId = selection.providerId, modelId = selection.modelId,
            enabled = true, tier = null, reasoning = null, imageResolution = null,
            reasoningByModel = emptyMap(), gptSpeedByModel = emptyMap())
        return repository.modelDefaults(blank)?.restore(blank) ?: blank
    }

    fun rememberedParallelLimit(profile: SubAgentProfile): Int? = repository.modelDefaults(profile)?.parallelLimit

    /** Resolve Room outside the repository lock, then CAS the entire profile/application and pool. */
    suspend fun commitProfileDraft(
        draft: SubAgentProfile,
        expectedProfile: SubAgentProfile?,
        expectedApplicationToken: String?,
        parallelLimitChange: SubAgentParallelLimitChange?,
        canCommit: () -> Boolean = { true },
    ): ConversationSubAgentPreferences.WriteResult {
        if (!enabled || !canCommit()) return ConversationSubAgentPreferences.WriteResult.Rejected
        return try {
            val capturedOwnerState = repository.ownerState(owner)
            if (owner is SubAgentConfigKey.Draft && !capturedOwnerState.exists) throw LostOwner()
            if (draft.providerId.isBlank() != draft.modelId.isBlank()) throw LostOwner()
            val binding = if (draft.providerId.isBlank()) null else {
                val provider = providerLookup(draft.providerId)?.takeIf { it.id == draft.providerId && it.isEnabled }
                    ?: throw LostOwner()
                currentCoroutineContext().ensureActive()
                val model = provider.models.singleOrNull { it.id == draft.modelId && it.isEnabled } ?: throw LostOwner()
                if (model.modelId.isBlank() || model.supportsSpeechSynthesis ||
                    !draft.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration))
                    throw LostOwner()
                SubAgentParallelModel(provider.id, model.modelId)
            }
            if (parallelLimitChange != null && (binding == null || parallelLimitChange.model != binding ||
                    parallelLimitChange.value < 0 || parallelLimitChange.expected < 0)) throw LostOwner()
            repository.updateConfirmedProfile(owner, draft.id, binding, { enabled && canCommit() },
                expectedOwnerState = capturedOwnerState) { old ->
                if (!enabled || !canCommit() || old.presetApplicationToken != expectedApplicationToken) throw LostOwner()
                val current = old.profiles.singleOrNull { it.id == draft.id }
                if (expectedProfile == null) {
                    if (current != null || old.profiles.any { it.name == draft.name }) throw LostOwner()
                } else if (expectedProfile.id != draft.id || current != expectedProfile) throw LostOwner()
                if (parallelLimitChange != null && old.parallelLimit(parallelLimitChange.model) != parallelLimitChange.expected)
                    throw LostOwner()
                old.copy(profiles = if (expectedProfile == null) old.profiles + draft else
                    old.profiles.map { if (it.id == draft.id) draft else it },
                    parallelLimits = parallelLimitChange?.let { old.parallelLimits + (it.model to it.value) } ?: old.parallelLimits)
            }.also { result ->
                if (result is ConversationSubAgentPreferences.WriteResult.Saved) {
                    binding?.let { resolvedBindings[SubAgentProfile.modelReasoningKey(draft.providerId, draft.modelId)] = it }
                    storedState = SubAgentEditorState.Loaded(result.config)
                }
            }
        } catch (_: LostOwner) { ConversationSubAgentPreferences.WriteResult.Rejected }
          catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }

    /** Legacy explicit add; new UI must use newProfileDraft + commitProfileDraft instead. */
    fun add(): ConversationSubAgentPreferences.WriteResult {
        val draft = newProfileDraft()
        return update { old -> old.copy(profiles = old.profiles + draft) }
    }
    private fun confirmedUpdate(id: String, binding: SubAgentParallelModel? = null,
        change: (ConversationSubAgentConfig) -> ConversationSubAgentConfig): ConversationSubAgentPreferences.WriteResult {
        if (!enabled) return ConversationSubAgentPreferences.WriteResult.Rejected
        val token = (state as? SubAgentEditorState.Loaded)?.config?.presetApplicationToken
        return try {
            repository.updateConfirmedProfile(owner, id, binding, { enabled }) { old ->
                if (!enabled || old.presetApplicationToken != token) throw LostOwner()
                change(old)
            }.also { result ->
                if (result is ConversationSubAgentPreferences.WriteResult.Saved) storedState = SubAgentEditorState.Loaded(result.config)
            }
        } catch (_: LostOwner) { ConversationSubAgentPreferences.WriteResult.Rejected }
          catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }
    fun updateProfile(id: String, change: (SubAgentProfile) -> SubAgentProfile): ConversationSubAgentPreferences.WriteResult = confirmedUpdate(id) { old ->
        if (old.profiles.none { it.id == id }) throw LostOwner()
        old.copy(profiles = old.profiles.map { profile ->
            if (profile.id != id) profile else change(profile).normalizedTaskTier().also { require(it.id == id) }
        })
    }
    fun remove(id: String) = update { old ->
        if (old.profiles.none { it.id == id }) throw LostOwner()
        old.copy(profiles = old.profiles.filterNot { profile -> profile.id == id })
    }
    fun setEnabled(value: Boolean) = update { it.copy(enabled = value) }
    fun saveParallelLimit(id: String, providerId: String, modelId: String, apiModel: String, limit: Int): ConversationSubAgentPreferences.WriteResult {
        if (limit < 0 || providerId.isBlank() || apiModel.isBlank()) return ConversationSubAgentPreferences.WriteResult.Rejected
        val binding = SubAgentParallelModel(providerId, apiModel)
        return confirmedUpdate(id, binding) { old ->
            val profile = old.profiles.singleOrNull { it.id == id && it.providerId == providerId && it.modelId == modelId }
                ?: throw LostOwner()
            val known = resolvedBindings[SubAgentProfile.modelReasoningKey(providerId, modelId)]
                ?: repository.modelDefaults(profile)?.apiModel?.let { SubAgentParallelModel(providerId, it) }
            if (known != null && known != binding) throw LostOwner()
            old.copy(parallelLimits = old.parallelLimits + (binding to limit))
        }.also { result ->
            if (result is ConversationSubAgentPreferences.WriteResult.Saved)
                resolvedBindings[SubAgentProfile.modelReasoningKey(providerId, modelId)] = binding
        }
    }
    suspend fun cycleGptSpeed(id: String, providerId: String, modelId: String, expected: SubAgentProfile? = null):
        ConversationSubAgentPreferences.WriteResult {
        if (!enabled) return ConversationSubAgentPreferences.WriteResult.Rejected
        return try {
            // Capture before suspension, even when the caller has no expected UI snapshot.
            val capturedConfig = repository.snapshot(owner)
            val capturedToken = capturedConfig.presetApplicationToken
            if ((state as? SubAgentEditorState.Loaded)?.config?.presetApplicationToken != capturedToken) throw LostOwner()
            val captured = capturedConfig.profiles.singleOrNull { it.id == id } ?: throw LostOwner()
            if (captured.providerId != providerId || captured.modelId != modelId ||
                (expected != null && captured != expected)) throw LostOwner()
            // Room lookup is suspendable and must never run inside the owner transaction/lock.
            val resolvedProvider = providerLookup(providerId)
            currentCoroutineContext().ensureActive()
            val provider = resolvedProvider?.takeIf { it.id == providerId } ?: throw LostOwner()
            val model = provider.models.singleOrNull { it.id == modelId } ?: throw LostOwner()
            if (!enabled || !supportsGptSpeedBinding(provider, model)) throw LostOwner()
            val binding = SubAgentParallelModel(providerId, model.modelId)
            confirmedUpdate(id, binding) { config ->
                if (config.presetApplicationToken != capturedToken) throw LostOwner()
                val old = config.profiles.singleOrNull { it.id == id } ?: throw LostOwner()
                if (old != (expected ?: captured) || old.providerId != providerId || old.modelId != modelId ||
                    !supportsGptSpeedBinding(provider, model)) throw LostOwner()
                config.copy(profiles = config.profiles.map { profile -> if (profile.id != id) profile else
                    old.copy(gptSpeedByModel = old.gptSpeedByModel +
                        (SubAgentProfile.modelReasoningKey(providerId, modelId) to old.gptSpeedForModel().next())) })
            }.also { result ->
                if (result is ConversationSubAgentPreferences.WriteResult.Saved)
                    resolvedBindings[SubAgentProfile.modelReasoningKey(providerId, modelId)] = binding
            }
        } catch (_: LostOwner) { ConversationSubAgentPreferences.WriteResult.Rejected }
          catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }

    fun saveModel(id: String, selection: ModelFeatureSelection, expected: SubAgentProfile? = null) = confirmedUpdate(id) { config ->
        val old = config.profiles.singleOrNull { it.id == id } ?: throw LostOwner()
        if (expected != null && old != expected) throw LostOwner()
        if (old.providerId == selection.providerId && old.modelId == selection.modelId) config
        else {
            val restored = changeProfileModel(old, selection)
            val memory = repository.modelDefaults(restored)
            val model = memory?.apiModel?.let { SubAgentParallelModel(restored.providerId, it) }
            val limit = memory?.parallelLimit
            config.copy(profiles = config.profiles.map { if (it.id == id) restored else it },
                parallelLimits = if (model != null && limit != null)
                    config.parallelLimits + (model to limit) else config.parallelLimits)
        }
    }
}

internal val LocalConversationSubAgentEditor = compositionLocalOf<ConversationSubAgentEditor?> { null }
