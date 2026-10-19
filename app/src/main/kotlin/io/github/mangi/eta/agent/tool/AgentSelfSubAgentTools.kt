package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentPreset
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.agent.model.ImageResolutionTier
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ProviderRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 管理 Eta 自己的子代理组：组的增删改，以及组内子代理（名称、角色、模型、任务档位、推理强度）。
 *
 * 复用设置页同一份存储（预设目录 + 每个 p_ 拥有者的配置），所以这里的改动与人手编辑完全等价。
 * 子代理配置在任务开始时冻结，因此任何改动都从下一次回复生效。
 */
internal class AgentSelfSubAgentTools(private val conversationId: String = "") {

    fun execute(args: JSONObject): String = io {
        // 校验失败（组名不合法、存档损坏、存储耐久性未知）会抛出异常；
        // 工具门禁本身不捕获，所以在这里转成结构化错误回报给模型。
        try {
            when (val action = args.optString("action").trim()) {
                "list" -> list()
                "create_group" -> createGroup(args)
                "rename_group" -> renameGroup(args)
                "delete_group" -> deleteGroup(args)
                "set_delegation" -> setDelegation(args)
                "add_agent" -> addAgent(args)
                "update_agent" -> updateAgent(args)
                "remove_agent" -> removeAgent(args)
                "apply" -> apply(args)
                else -> error("INVALID_ARGUMENT", "action 取值无效：$action")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            error("INTERNAL_ERROR", failure.message ?: "子代理组操作失败")
        }
    }

    private suspend fun list(): String {
        val repository = ConversationSubAgentPreferences()
        val providers = ProviderRepository.allProviders()
        val presets = repository.presets()
        val appliedId = conversationId.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { repository.snapshot(SubAgentConfigKey.Conversation(id)).appliedPresetId }.getOrNull()
        }
        val groups = JSONArray()
        presets.forEach { preset ->
            val agents = JSONArray()
            preset.config.profiles.forEach { agents.put(profileDetail(it, providers)) }
            groups.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("auto_delegation", preset.config.enabled)
                    .put("sub_agent_count", preset.config.profiles.size)
                    .put("applied_to_this_conversation", preset.id == appliedId)
                    .put("sub_agents", agents),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("action", "list")
            .put("applied_group_id", appliedId ?: "")
            .put("groups", groups)
            .toString()
    }

    private fun createGroup(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isBlank()) return error("INVALID_ARGUMENT", "create_group 需要提供 name")
        if (name.length > MAX_NAME) return error("INVALID_ARGUMENT", "组名不能超过 $MAX_NAME 个字符")
        val created = ConversationSubAgentPreferences().addPreset(name)
        return groupResult("create_group", created)
    }

    private fun renameGroup(args: JSONObject): String {
        val repository = ConversationSubAgentPreferences()
        val group = requireGroup(repository, args) ?: return groupError(args)
        val name = args.optString("name").trim()
        if (name.isBlank()) return error("INVALID_ARGUMENT", "rename_group 需要提供 name")
        if (name.length > MAX_NAME) return error("INVALID_ARGUMENT", "组名不能超过 $MAX_NAME 个字符")
        if (!repository.renamePreset(group.id, name)) return error("WRITE_FAILED", "重命名子代理组失败")
        return groupResult("rename_group", group.copy(name = name))
    }

    private fun deleteGroup(args: JSONObject): String {
        val repository = ConversationSubAgentPreferences()
        val group = requireGroup(repository, args) ?: return groupError(args)
        if (!repository.removePreset(group.id)) return error("WRITE_FAILED", "删除子代理组失败")
        return JSONObject()
            .put("ok", true)
            .put("action", "delete_group")
            .put("group", groupSummary(group))
            .put("effect", "next_reply")
            .toString()
    }

    private fun setDelegation(args: JSONObject): String {
        if (!args.has("enabled")) return error("INVALID_ARGUMENT", "set_delegation 需要提供 enabled")
        val enabled = args.optBoolean("enabled")
        val repository = ConversationSubAgentPreferences()
        val group = requireGroup(repository, args) ?: return groupError(args)
        val result = repository.update(SubAgentConfigKey.Preset(group.id)) { it.copy(enabled = enabled) }
        if (result !is ConversationSubAgentPreferences.WriteResult.Saved) return error("WRITE_FAILED", "写入子代理组失败")
        return groupResult("set_delegation", result.config.let { group.copy(config = it) })
    }

    private suspend fun addAgent(args: JSONObject): String {
        val repository = ConversationSubAgentPreferences()
        val group = requireGroup(repository, args) ?: return groupError(args)
        if (group.config.profiles.size >= MAX_AGENTS) return error("GROUP_FULL", "一个组最多 $MAX_AGENTS 个子代理")
        val name = args.optString("agent_name").trim()
        if (name.isBlank()) return error("INVALID_ARGUMENT", "add_agent 需要提供 agent_name")
        if (name.length > MAX_NAME) return error("INVALID_ARGUMENT", "子代理名称不能超过 $MAX_NAME 个字符")
        val role = args.optString("role").trim().ifBlank { "implementation" }
        if (role !in ROLES) return error("INVALID_ARGUMENT", "role 取值无效：$role")
        if (group.config.profiles.any { it.name.equals(name, ignoreCase = true) }) {
            return error("NAME_TAKEN", "该组已有同名子代理：$name")
        }
        val base = SubAgentProfile(
            id = UUID.randomUUID().toString(), name = name, role = role,
            enabled = if (args.has("enabled")) args.optBoolean("enabled") else true,
        )
        val providers = ProviderRepository.allProviders()
        when (val outcome = applyProfileFields(base, args, providers)) {
            is Outcome.Failure -> return error(outcome.code, outcome.message)
            is Outcome.Ok -> {
                val result = repository.updateConfirmedProfile(
                    owner = SubAgentConfigKey.Preset(group.id),
                    profileId = outcome.profile.id,
                    binding = outcome.binding,
                ) { config -> config.copy(profiles = config.profiles + outcome.profile) }
                if (result !is ConversationSubAgentPreferences.WriteResult.Saved) {
                    return error("WRITE_FAILED", "写入子代理失败")
                }
                return agentResult("add_agent", group, outcome.profile, providers)
            }
        }
    }

    private suspend fun updateAgent(args: JSONObject): String {
        val repository = ConversationSubAgentPreferences()
        val group = requireGroup(repository, args) ?: return groupError(args)
        val agentInput = args.optString("agent").trim()
        if (agentInput.isBlank()) return error("INVALID_ARGUMENT", "update_agent 需要提供 agent")
        val current = group.config.profiles.firstOrNull { it.id == agentInput || it.name.equals(agentInput, ignoreCase = true) }
            ?: return error("AGENT_NOT_FOUND", "组 ${group.name} 中没有子代理：$agentInput")
        val providers = ProviderRepository.allProviders()
        val outcome = when (val value = applyProfileFields(current, args, providers)) {
            is Outcome.Failure -> return error(value.code, value.message)
            is Outcome.Ok -> value
        }
        if (group.config.profiles.any { it.id != current.id && it.name.equals(outcome.profile.name, ignoreCase = true) }) {
            return error("NAME_TAKEN", "该组已有同名子代理：${outcome.profile.name}")
        }
        val result = repository.updateConfirmedProfile(
            owner = SubAgentConfigKey.Preset(group.id),
            profileId = current.id,
            binding = outcome.binding,
        ) { config -> config.copy(profiles = config.profiles.map { if (it.id == current.id) outcome.profile else it }) }
        if (result !is ConversationSubAgentPreferences.WriteResult.Saved) return error("WRITE_FAILED", "写入子代理失败")
        return agentResult("update_agent", group, outcome.profile, providers)
    }

    private fun removeAgent(args: JSONObject): String {
        val repository = ConversationSubAgentPreferences()
        val group = requireGroup(repository, args) ?: return groupError(args)
        val agentInput = args.optString("agent").trim()
        if (agentInput.isBlank()) return error("INVALID_ARGUMENT", "remove_agent 需要提供 agent")
        val current = group.config.profiles.firstOrNull { it.id == agentInput || it.name.equals(agentInput, ignoreCase = true) }
            ?: return error("AGENT_NOT_FOUND", "组 ${group.name} 中没有子代理：$agentInput")
        val result = repository.update(SubAgentConfigKey.Preset(group.id)) { config ->
            config.copy(profiles = config.profiles.filterNot { it.id == current.id })
        }
        if (result !is ConversationSubAgentPreferences.WriteResult.Saved) return error("WRITE_FAILED", "删除子代理失败")
        return JSONObject()
            .put("ok", true)
            .put("action", "remove_agent")
            .put("group", groupSummary(group))
            .put("agent_id", current.id)
            .put("agent_name", current.name)
            .put("effect", "next_reply")
            .toString()
    }

    private fun apply(args: JSONObject): String {
        if (conversationId.isBlank()) return error("CONVERSATION_UNAVAILABLE", "当前没有可应用子代理组的会话")
        val repository = ConversationSubAgentPreferences()
        val group = requireGroup(repository, args) ?: return groupError(args)
        val result = repository.applyPreset(SubAgentConfigKey.Conversation(conversationId), group.id)
        if (result !is ConversationSubAgentPreferences.WriteResult.Saved) return error("WRITE_FAILED", "应用子代理组失败")
        return JSONObject()
            .put("ok", true)
            .put("action", "apply")
            .put("group", groupSummary(group))
            .put("sub_agent_count", result.config.profiles.size)
            .put("effect", "next_reply")
            .toString()
    }

    private fun requireGroup(repository: ConversationSubAgentPreferences, args: JSONObject): SubAgentPreset? {
        val input = args.optString("group").trim()
        if (input.isBlank()) return null
        val presets = repository.presets()
        return presets.firstOrNull { it.id.equals(input, ignoreCase = true) }
            ?: presets.firstOrNull { it.name.equals(input, ignoreCase = true) }
            ?: presets.firstOrNull { it.name.contains(input, ignoreCase = true) }
    }

    private fun groupError(args: JSONObject): String {
        val input = args.optString("group").trim()
        return if (input.isBlank()) {
            error("INVALID_ARGUMENT", "该 action 需要提供 group")
        } else {
            error("GROUP_NOT_FOUND", "找不到子代理组：$input")
        }
    }

    /** 把一个 profile 的字段改动落成新值；provider/model 会解析成真实实体并给出运行池绑定。 */
    private fun applyProfileFields(
        current: SubAgentProfile,
        args: JSONObject,
        providers: List<ProviderSetting>,
    ): Outcome {
        var next = current
        if (args.has("agent_name")) {
            val name = args.optString("agent_name").trim()
            if (name.isEmpty()) return Outcome.Failure("INVALID_ARGUMENT", "agent_name 不能为空")
            if (name.length > MAX_NAME) return Outcome.Failure("INVALID_ARGUMENT", "子代理名称不能超过 $MAX_NAME 个字符")
            next = next.copy(name = name)
        }
        if (args.has("enabled")) next = next.copy(enabled = args.optBoolean("enabled"))
        if (args.has("role")) {
            val role = args.optString("role").trim()
            if (role !in ROLES) return Outcome.Failure("INVALID_ARGUMENT", "role 取值无效：$role")
            next = next.withRole(role)
        }
        if (args.has("task_tier")) {
            val raw = args.optString("task_tier").trim()
            if (raw.isEmpty()) {
                next = next.copy(tier = null)
            } else {
                if (!next.supportsTaskTier) return Outcome.Failure("INVALID_ARGUMENT", "task_tier 只适用于 role=implementation")
                val tier = SubAgentTaskTier.fromWireValue(raw)
                    ?: return Outcome.Failure("INVALID_ARGUMENT", "task_tier 取值无效：$raw")
                next = next.copy(tier = tier)
            }
        }
        if (args.has("reasoning")) {
            val raw = args.optString("reasoning").trim()
            if (raw.isEmpty()) {
                next = next.copy(reasoning = null)
            } else {
                val effort = ReasoningEffort.fromWireValue(raw)
                    ?: return Outcome.Failure("INVALID_ARGUMENT", "reasoning 取值无效：$raw")
                next = next.copy(reasoning = effort)
            }
        }
        if (args.has("image_resolution")) {
            val raw = args.optString("image_resolution").trim()
            if (raw.isEmpty()) {
                next = next.copy(imageResolution = null)
            } else {
                if (next.role != "image_generation") {
                    return Outcome.Failure("INVALID_ARGUMENT", "image_resolution 只适用于 role=image_generation")
                }
                if (raw !in ImageResolutionTier.values) return Outcome.Failure("INVALID_ARGUMENT", "image_resolution 取值无效：$raw")
                next = next.copy(imageResolution = raw)
            }
        }
        if (args.has("provider") || args.has("model")) {
            val providerInput = args.optString("provider").trim().ifBlank { next.providerId }
            if (providerInput.isEmpty()) return Outcome.Failure("INVALID_ARGUMENT", "绑定模型需要提供 provider")
            val provider = findProvider(providers, providerInput)
                ?: return Outcome.Failure("PROVIDER_NOT_FOUND", "找不到供应商：$providerInput")
            if (!provider.isEnabled) return Outcome.Failure("PROVIDER_NOT_FOUND", "供应商已禁用：${provider.name}")
            val modelInput = args.optString("model").trim().ifBlank { next.modelId }
            if (modelInput.isEmpty()) return Outcome.Failure("INVALID_ARGUMENT", "绑定模型需要提供 model")
            val model = findModel(provider, modelInput)
                ?: return Outcome.Failure("MODEL_NOT_FOUND", "供应商 ${provider.name} 下找不到模型：$modelInput")
            if (model.modelId.isBlank() || model.supportsSpeechSynthesis ||
                !next.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration)
            ) {
                return Outcome.Failure("MODEL_NOT_SUPPORTED", "模型 ${model.modelId} 不能用于 role=${next.role}")
            }
            next = next.copy(providerId = provider.id, modelId = model.id)
            return Outcome.Ok(next.normalizedTaskTier(), SubAgentParallelModel(provider.id, model.modelId))
        }
        return Outcome.Ok(next.normalizedTaskTier(), resolveBinding(next, providers))
    }

    private fun resolveBinding(profile: SubAgentProfile, providers: List<ProviderSetting>): SubAgentParallelModel? {
        if (profile.providerId.isBlank() || profile.modelId.isBlank()) return null
        val provider = providers.firstOrNull { it.id == profile.providerId } ?: return null
        val model = provider.models.firstOrNull { it.id == profile.modelId } ?: return null
        if (model.modelId.isBlank()) return null
        return SubAgentParallelModel(provider.id, model.modelId)
    }

    private fun groupResult(action: String, preset: SubAgentPreset): String =
        JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("group", groupSummary(preset))
            .put("effect", "next_reply")
            .toString()

    private fun agentResult(action: String, preset: SubAgentPreset, profile: SubAgentProfile, providers: List<ProviderSetting>): String =
        JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("group", groupSummary(preset))
            .put("sub_agent", profileDetail(profile, providers))
            .put("effect", "next_reply")
            .toString()

    private fun groupSummary(preset: SubAgentPreset): JSONObject =
        JSONObject()
            .put("id", preset.id)
            .put("name", preset.name)
            .put("auto_delegation", preset.config.enabled)
            .put("sub_agent_count", preset.config.profiles.size)

    private fun profileDetail(profile: SubAgentProfile, providers: List<ProviderSetting>): JSONObject {
        val provider = providers.firstOrNull { it.id == profile.providerId }
        val model = provider?.models?.firstOrNull { it.id == profile.modelId }
        return JSONObject()
            .put("id", profile.id)
            .put("name", profile.name)
            .put("role", profile.role)
            .put("enabled", profile.enabled)
            .put("provider", provider?.name ?: profile.providerId)
            .put("model", model?.modelId ?: profile.modelId)
            .put("task_tier", profile.tier?.wireValue.orEmpty())
            .put("reasoning", profile.reasoning?.wireValue.orEmpty())
            .put("image_resolution", profile.imageResolution.orEmpty())
    }

    private fun findProvider(providers: List<ProviderSetting>, input: String): ProviderSetting? =
        providers.firstOrNull { it.id.equals(input, ignoreCase = true) }
            ?: providers.firstOrNull { it.name.equals(input, ignoreCase = true) }
            ?: providers.firstOrNull { it.name.contains(input, ignoreCase = true) }

    private fun findModel(provider: ProviderSetting, input: String): Model? =
        matchModel(provider.models.filter { it.isEnabled }, input) ?: matchModel(provider.models, input)

    private fun matchModel(models: List<Model>, input: String): Model? =
        models.firstOrNull { it.id.equals(input, ignoreCase = true) }
            ?: models.firstOrNull { it.modelId.equals(input, ignoreCase = true) }
            ?: models.firstOrNull { it.displayName.equals(input, ignoreCase = true) }
            ?: models.firstOrNull { it.modelId.contains(input, ignoreCase = true) }
            ?: models.firstOrNull { it.displayName.contains(input, ignoreCase = true) }

    private sealed interface Outcome {
        data class Ok(val profile: SubAgentProfile, val binding: SubAgentParallelModel?) : Outcome
        data class Failure(val code: String, val message: String) : Outcome
    }

    private fun io(block: suspend () -> String): String = runBlocking(Dispatchers.IO) { block() }

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private companion object {
        val ROLES = setOf("implementation", "review", "image_generation", "video_generation")
        const val MAX_NAME = 80
        const val MAX_AGENTS = 24
    }
}
