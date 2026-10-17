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
                                        .put("description", "save_provider only: key for that endpoint. Omit it to keep the stored one, pass an empty string to clear it."),
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
    }
}
