package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明 Eta 自身配置的可读写工具。
 *
 * 目标是「运行本助手的 App 自己」：模型配置、语音、工具开关、触感。
 * Android 系统设置由 `get_setting` / `set_setting` 负责，两者互不重叠。
 */
internal object AgentSelfSettingsToolCatalog {
    const val GET_SETTINGS = "eta_get_settings"
    const val SET_SETTINGS = "eta_set_settings"
    const val MANAGE_MODELS = "eta_manage_models"
    const val MANAGE_SUBAGENTS = "eta_manage_subagents"
    const val MANAGE_HISTORY = "eta_manage_history"

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = GET_SETTINGS,
                    description = "Read the configuration of Eta, the assistant app you are running: every switch you can change, its current value, and the meaning of each choice. Call this before changing anything so you report the real current value instead of guessing. The active model is not included; use $MANAGE_MODELS for models.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                        .put("additionalProperties", false),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = SET_SETTINGS,
                    description = "Change switches of Eta, the assistant app you are running; this never touches Android system settings. Pass one or more pairs of key and value. Keys and accepted values come from $GET_SETTINGS: use \"on\" or \"off\" for switches, and the exact option for a choice. Only change what the user actually asked for, and state the result in your reply. A tool availability switch applies to the next tool call in this run; every other change applies from the next reply, not to the answer you are writing now.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "settings",
                                JSONObject()
                                    .put("type", "array")
                                    .put("maxItems", 24)
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "object")
                                            .put(
                                                "properties",
                                                JSONObject()
                                                    .put(
                                                        "key",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("maxLength", 64)
                                                            .put("description", "Setting key from $GET_SETTINGS."),
                                                    )
                                                    .put(
                                                        "value",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("maxLength", 64)
                                                            .put("description", "New value: \"on\" or \"off\" for a switch, otherwise one of its listed options."),
                                                    ),
                                            )
                                            .put("required", JSONArray().put("key").put("value")),
                                    ),
                            ),
                        )
                        .put("required", JSONArray().put("settings")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = MANAGE_MODELS,
                    description = "Configure the models and providers of Eta, the assistant app you are running. " +
                        "action=list returns every provider with its models and the current selection and needs no other field. " +
                        "select sets the model used for chat (default), text-to-speech (tts), voice conversation (voice) or auto compression (compress). " +
                        "save_provider creates a provider when the name or base_url is new and otherwise updates that provider; models lists the model ids to keep and api_key is stored as a secret. " +
                        "save_model changes one existing model; only pass the fields the user asked about, and use auto to hand a capability back to whatever the provider reports. " +
                        "delete_model removes one model and delete_provider removes a provider you added, never a built-in one. " +
                        "fetch_models reads the provider's own model list to refresh the stored models and report the new ones it found. " +
                        "Every change applies from the next reply, not to the request you are answering now.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("list")
                                                .put("select")
                                                .put("save_provider")
                                                .put("delete_provider")
                                                .put("save_model")
                                                .put("delete_model")
                                                .put("fetch_models"),
                                        )
                                        .put("description", "Required. What to do with a provider or a model."),
                                )
                                .put(
                                    "target",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("chat").put("tts").put("voice").put("compress"))
                                        .put("description", "select only: which feature owns the model. Default chat."),
                                )
                                .put(
                                    "provider",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Provider id or displayed name. Required by every action except list, which may omit it."),
                                )
                                .put(
                                    "model",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Model id or displayed name. Required by save_model and delete_model; select may omit it to keep the provider's current model."),
                                )
                                .put(
                                    "name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "save_provider only: displayed provider name, also used to find an existing provider to update."),
                                )
                                .put(
                                    "base_url",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "save_provider only: OpenAI compatible endpoint, for example https://api.example.com/v1."),
                                )
                                .put(
                                    "api_key",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "save_provider only: secret key for that endpoint. Omit it to keep the stored one, pass an empty string to clear it."),
                                )
                                .put(
                                    "models",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("maxItems", 60)
                                        .put("items", JSONObject().put("type", "string").put("maxLength", 200))
                                        .put("description", "save_provider only: model ids to keep on that provider. Missing ones are created; existing ones are left alone."),
                                )
                                .put(
                                    "is_enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "save_provider only: false hides the provider from the model picker without deleting it."),
                                )
                                .put(
                                    "display_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "save_model only: the name shown for this model."),
                                )
                                .put(
                                    "enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "save_model only: false hides the model from the model picker without deleting it."),
                                )
                                .put(
                                    "vision",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("on").put("off").put("auto"))
                                        .put("description", "save_model only: force whether this model can read images; auto uses the provider's answer."),
                                )
                                .put(
                                    "reasoning",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("on").put("off").put("auto"))
                                        .put("description", "save_model only: force whether this model can think before answering; auto uses the provider's answer."),
                                )
                                .put(
                                    "context_window",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 10_000_000)
                                        .put("description", "save_model only: context length in tokens, used for session clipping and context budgeting."),
                                )
                                .put(
                                    "reasoning_effort",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("off")
                                                .put("default")
                                                .put("minimal")
                                                .put("low")
                                                .put("medium")
                                                .put("high")
                                                .put("xhigh")
                                                .put("max"),
                                        )
                                        .put("description", "save_model only: how much thinking the model does by default. Only useful when the model supports reasoning."),
                                )
                                .put(
                                    "add_new",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "fetch_models only: also store the new models it found. Default false, which only refreshes the ones already stored."),
                                ),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = MANAGE_SUBAGENTS,
                    description = "Create and manage the sub-agent groups of Eta, the assistant app you are running. " +
                        "A group is a reusable preset of sub-agents that the main agent delegates to with delegate_task, and it can be applied to a conversation. " +
                        "action=list returns every group with its sub-agents and which group this conversation applied, and needs no other field. " +
                        "create_group, rename_group, delete_group and set_delegation manage a group itself, where set_delegation turns its automatic delegation on or off. " +
                        "add_agent, update_agent and remove_agent manage the sub-agents inside one group. " +
                        "apply copies a group onto this conversation. " +
                        "Only change what the user actually asked for, and state the result in your reply. " +
                        "Every change applies from the next reply, not to the answer you are writing now; editing a group does not change conversations that already applied it.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("list")
                                                .put("create_group")
                                                .put("rename_group")
                                                .put("delete_group")
                                                .put("set_delegation")
                                                .put("add_agent")
                                                .put("update_agent")
                                                .put("remove_agent")
                                                .put("apply"),
                                        )
                                        .put("description", "Required. What to do with a group or one of its sub-agents."),
                                )
                                .put(
                                    "group",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "Group id or displayed name. Required by every action except list and create_group."),
                                )
                                .put(
                                    "name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "create_group and rename_group only: the group's name, which is the new name when renaming."),
                                )
                                .put(
                                    "agent",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "update_agent and remove_agent only: the sub-agent's id or displayed name."),
                                )
                                .put(
                                    "agent_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "add_agent and update_agent only: the sub-agent's displayed name."),
                                )
                                .put(
                                    "role",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray().put("implementation").put("review").put("image_generation").put("video_generation"),
                                        )
                                        .put("description", "add_agent and update_agent only: what the sub-agent does. Default implementation."),
                                )
                                .put(
                                    "provider",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "add_agent and update_agent only: provider id or displayed name; pass it together with model to bind the sub-agent to a model."),
                                )
                                .put(
                                    "model",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "add_agent and update_agent only: model id or displayed name on that provider."),
                                )
                                .put(
                                    "enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "set_delegation: the group's automatic delegation. add_agent and update_agent: whether this sub-agent is enabled; a new sub-agent starts enabled."),
                                )
                                .put(
                                    "task_tier",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("simple").put("regular").put("complex"))
                                        .put("description", "add_agent and update_agent only: task tier for role=implementation; ignored by other roles."),
                                )
                                .put(
                                    "reasoning",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("off")
                                                .put("default")
                                                .put("minimal")
                                                .put("low")
                                                .put("medium")
                                                .put("high")
                                                .put("xhigh")
                                                .put("max"),
                                        )
                                        .put("description", "add_agent and update_agent only: reasoning effort for this sub-agent; pass an empty string to clear it."),
                                )
                                .put(
                                    "image_resolution",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("low").put("medium").put("high").put("ultra"))
                                        .put("description", "add_agent and update_agent only: resolution for role=image_generation; pass an empty string to clear it."),
                                ),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = MANAGE_HISTORY,
                    description = "Browse and organize the chat history of Eta, the assistant app you are running: its folders and its saved conversations. " +
                        "action=list_folders returns every folder with how many conversations it holds and needs no other field. " +
                        "action=list_conversations returns the stored conversations, pinned ones first and then newest first. " +
                        "action=search_conversations finds conversations by the text inside their messages. " +
                        "create_folder, rename_folder and delete_folder manage folders; deleting a folder keeps its conversations and moves them back to the ungrouped list. " +
                        "rename_conversation, move_conversation, set_pinned and delete_conversation change one conversation, where set_pinned sets the value rather than toggling it. " +
                        "conversation accepts an id or a title, or the special value current for the conversation you are answering in; folder accepts an id or a name, or none for the ungrouped list. " +
                        "When a conversation id or title matches more than one conversation the call fails and lists the candidates instead of guessing. " +
                        "Deleting a conversation is permanent and cannot be undone. " +
                        "Only change what the user actually asked for, and state the result in your reply; every change shows up in the history list right away.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("list_folders")
                                                .put("list_conversations")
                                                .put("search_conversations")
                                                .put("create_folder")
                                                .put("rename_folder")
                                                .put("delete_folder")
                                                .put("rename_conversation")
                                                .put("move_conversation")
                                                .put("set_pinned")
                                                .put("delete_conversation"),
                                        )
                                        .put("description", "Required. What to do with a folder or a conversation."),
                                )
                                .put(
                                    "folder",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "Folder id or name; also the target of move_conversation. Pass none to mean the ungrouped list."),
                                )
                                .put(
                                    "name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "create_folder, rename_folder and rename_conversation only: the new name."),
                                )
                                .put(
                                    "conversation",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Conversation id or title; pass current for the conversation you are answering in."),
                                )
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "search_conversations only: text to look for inside the messages."),
                                )
                                .put(
                                    "pinned",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "set_pinned only: true pins the conversation to the top, false unpins it."),
                                ),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
    }
}
