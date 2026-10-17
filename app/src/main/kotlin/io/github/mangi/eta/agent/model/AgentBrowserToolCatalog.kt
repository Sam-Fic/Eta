package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBrowserToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "browser_use",
                description = "操作Eta的离屏多标签浏览器（每个会话最多 3 个标签，编号只用 0、1、2，不会占用其他会话的页面），不会切换到外部浏览器。一次调用只执行一个 action。支持 new_tab、close_tab、list_tabs；tab_id 指定标签，不提供则操作当前标签。navigate 接受完整 URL、域名、搜索词、/workspace、/var/minis 或 minis://。可用 desktop_chrome / mobile_chrome。get_cookies 只返回摘要和 /var/minis/offloads/env_cookies_xxx.sh，明文不进对话。通常先 navigate，再用 get_readable 提取 Markdown 正文，或用 find_elements / get_backbone 了解结构。选择器以当前 DOM 为准，搜索框可能是 textarea 或 contenteditable，不一定是 input；scroll_and_collect 返回 0 条时先检查结构和关键词过滤，不直接认定页面无内容。go_back/go_forward 逐条移动历史索引。需要把 URI 显式交给外部应用时使用 open_uri。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "本次唯一执行的浏览器动作。")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("new_tab")
                                            .put("close_tab")
                                            .put("list_tabs")
                                            .put("navigate")
                                            .put("get_readable")
                                            .put("get_text")
                                            .put("find_elements")
                                            .put("click")
                                            .put("type")
                                            .put("scroll")
                                            .put("screenshot")
                                            .put("get_page_info")
                                            .put("go_back")
                                            .put("go_forward")
                                            .put("reload")
                                            .put("wait_for_selector")
                                            .put("execute_js")
                                            .put("get_backbone")
                                            .put("hover")
                                            .put("fetch")
                                            .put("get_cookies")
                                            .put("set_cookies")
                                            .put("set_user_agent")
                                            .put("set_viewport")
                                            .put("scroll_and_collect")
                                            .put("wait_for_dom_stable"),
                                    ),
                            )
                            .put("tab_id", JSONObject().put("type", "integer").put("description", "目标标签 ID，来自 list_tabs 或前次结果；无此标签时不会自动换到其他标签。"))
                            .put("url", JSONObject().put("type", "string").put("description", "navigate 或 fetch 的目标；navigate 也接受域名或搜索词。"))
                            .put("selector", JSONObject().put("type", "string").put("description", "click、type、hover、get_text、find_elements、scroll 或 wait_for_selector 使用的 CSS selector。"))
                            .put("text", JSONObject().put("type", "string").put("description", "type 要输入的文本。只会发送给工具，不会显示在运行摘要中。"))
                            .put("submit", JSONObject().put("type", "boolean").put("description", "type 输入后是否提交所在表单，默认 false。"))
                            .put("coordinate_x", JSONObject().put("type", "integer").put("description", "click、type 或 hover 的视口 X 坐标，和 coordinate_y 一起使用。"))
                            .put("coordinate_y", JSONObject().put("type", "integer").put("description", "click、type 或 hover 的视口 Y 坐标，和 coordinate_x 一起使用。"))
                            .put("amount", JSONObject().put("type", "integer").put("description", "scroll 的滚动像素量。"))
                            .put("direction", JSONObject().put("type", "string").put("enum", JSONArray().put("up").put("down")).put("description", "scroll 的滚动方向。"))
                            .put("offset", JSONObject().put("type", "integer").put("description", "get_readable 或 get_text 的文本起始偏移，默认 0。"))
                            .put("max_chars", JSONObject().put("type", "integer").put("description", "get_readable 或 get_text 最多返回的文本字符数。"))
                            .put("read_image", JSONObject().put("type", "boolean").put("description", "screenshot 时是否把截图附给模型直接查看，默认 true。"))
                            .put("full_page", JSONObject().put("type", "boolean").put("description", "screenshot 时是否尽量截取整页，默认 false。高度有上限。"))
                            .put("timeout_ms", JSONObject().put("type", "integer").put("description", "navigate、wait_for_selector 或 wait_for_dom_stable 的超时毫秒数。"))
                            .put("timeout", JSONObject().put("type", "integer").put("description", "wait_for_dom_stable 的超时毫秒数，兼容 timeout_ms。"))
                            .put("script", JSONObject().put("type", "string").put("description", "execute_js 要运行的脚本。在 async 函数中执行，支持 await 和 return。"))
                            .put("user_agent", JSONObject().put("type", "string").put("enum", JSONArray().put("desktop_chrome").put("mobile_chrome")).put("description", "set_user_agent 的浏览器身份。"))
                            .put("max_depth", JSONObject().put("type", "integer").put("description", "get_backbone 的最大 DOM 深度，默认 5。"))
                            .put("viewport_width", JSONObject().put("type", "integer").put("description", "set_viewport 的视口宽度，不小于 320。"))
                            .put("viewport_height", JSONObject().put("type", "integer").put("description", "set_viewport 的视口高度，不小于 320。"))
                            .put("reset", JSONObject().put("type", "boolean").put("description", "set_viewport 时为 true 则恢复当前 UA 的默认视口。"))
                            .put("item_selector", JSONObject().put("type", "string").put("description", "scroll_and_collect 收集条目使用的 CSS selector。"))
                            .put("scroll_count", JSONObject().put("type", "integer").put("description", "scroll_and_collect 的滚动次数，默认 5。"))
                            .put("keywords", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "get_cookies 或 scroll_and_collect 的关键词过滤。"))
                            .put("fuzzy", JSONObject().put("type", "boolean").put("description", "get_cookies 关键词匹配：true 时名称需包含全部关键词，false 时精确匹配任一关键词，默认 true。"))
                            .put("cookies", JSONObject().put("type", "string").put("description", "set_cookies 的 JSON 数组字符串，每项至少包含 name 与 value。")),
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}
