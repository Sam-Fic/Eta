package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentTerminalToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "terminal",
                    description = "Manage terminal sessions on the current device. environment=android runs Android system commands and root operations; environment=linux runs the Alpine or Debian environment selected by the user. Default android: explicitly pass environment=linux and cwd=/workspace/<project> for Linux tools and project Git commands. run_command is Android-only and never enters the Linux environment. Do not repeat a failed call in the unchanged environment. Apktool build is unavailable until an ARM64 AAPT2 runtime is installed. Use open_and_exec for one-shot commands. Use open to create a persistent shell session and exec with session_id for multi-step work. Use async=true without session_id for long-running independent commands, then read_async_result with job_id to stream output chunks. Use daemon_start for services that must keep running after the Agent run (listening ports, web panels, watchers): the process detaches from any command shell, logs to a file, and survives until daemon_stop or device reboot. Manage daemons with daemon_list, daemon_logs and daemon_stop by task_id. Use close to stop jobs or close sessions.",
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
                                                .put("open")
                                                .put("exec")
                                                .put("open_and_exec")
                                                .put("read_async_result")
                                                .put("close")
                                                .put("daemon_start")
                                                .put("daemon_list")
                                                .put("daemon_logs")
                                                .put("daemon_stop")
                                        )
                                        .put("description", "open creates a session. exec runs command in a session or cwd. open_and_exec runs a one-shot command. read_async_result reads async output by job_id. close closes a session_id or job_id. daemon_start launches a detached long-lived service and returns task_id. daemon_list lists daemon tasks with liveness. daemon_logs tails a task log. daemon_stop terminates and removes a task.")
                                )
                                .put(
                                    "identity",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("user").put("root"))
                                        .put("description", "宿主执行身份。Android 默认使用当前可用身份；Linux 根据已选择的后端使用 user 或 root。PRoot 内模拟 root 不授予 Android 特权。")
                                )
                                .put(
                                    "environment",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("android").put("linux"))
                                        .put("description", "android uses the native Android shell with BusyBox applets when available. linux uses the Alpine or Debian environment selected in Eta settings. Default android.")
                                )
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Shell command to execute in the selected environment. Required for exec/open_and_exec.")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Working directory. Defaults to /data/local/tmp/eta for android and /workspace for linux. Relative paths use the environment default. ~/ means /storage/emulated/0.")
                                )
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Command timeout in milliseconds. Default 30000, max 180000.")
                                )
                                .put(
                                    "merge_stderr",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether stderr should be appended to stdout in command responses.")
                                )
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Session id returned by action=open. Use with exec or close.")
                                )
                                .put(
                                    "job_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Async job id returned when async=true. Use with read_async_result or close.")
                                )
                                .put(
                                    "task_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Daemon task id returned by daemon_start. Use with daemon_logs or daemon_stop.")
                                )
                                .put(
                                    "async",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Start command in a separate background shell and return immediately with job_id. Do not combine with session_id. Use read_async_result to stream output.")
                                )
                                .put(
                                    "offset_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For read_async_result, read stdout from this character offset. Default 0.")
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For read_async_result, maximum stdout characters to return. Default 8000, max 16000.")
                                )
                                .put(
                                    "close_if_done",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "For read_async_result, remove the async job when it has completed.")
                                )
                        )
                        .put("required", JSONArray().put("action"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "run_command",
                    description = "只在 Android 系统的非交互 Root Shell 执行命令，不会进入 Debian 或 Alpine。适合 Android 系统信息、包管理和本机文件检查。Linux 工具及工作区 Git 操作必须使用 terminal(environment=linux,cwd=/workspace/项目名)，本工具不接受 environment。每次调用都是新 shell；不要运行交互式或长期驻留命令。遇到命令不存在时不要在相同环境原样重试，更不要自动跨环境重放有副作用的命令。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要执行的 shell 命令，可使用管道和重定向。")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "工作目录，默认 /data/local/tmp/eta。相对路径也按该目录解析；用户存储可用 ~/ 表示 /storage/emulated/0。")
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "超时秒数，1 到 180，默认 30。")
                                )
                        )
                        .put("required", JSONArray().put("command"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "read_file",
                    description = "读取 Android 文件内容；支持当前所选 Linux 的 /workspace、/var/minis 和 minis:// 路径，自动映射到宿主文件。适合配置、日志、小文本文件；大文件用 offset_bytes/max_bytes 分段读取。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put(
                                    "offset_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "从第几个字节开始，默认 0。")
                                )
                                .put(
                                    "max_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多读取字节数，1 到 262144，默认 65536。")
                                )
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "write_file",
                    description = "写入 Android 文件。可覆盖或追加；会自动创建父目录。用于明确需要修改文件的任务。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("content", JSONObject().put("type", "string"))
                                .put(
                                    "append",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "true 追加，false 覆盖，默认 false。")
                                )
                        )
                        .put("required", JSONArray().put("path").put("content"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "list_directory",
                    description = "列出目录内容；支持当前所选 Linux 的 /workspace、/var/minis 和 minis:// 路径，自动映射到宿主目录。默认 /data/local/tmp/eta，输出类似 ls -l。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("show_hidden", JSONObject().put("type", "boolean"))
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 200 行，默认 80。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "stat_file",
                    description = "查询已知文件或目录的规范路径、类型、大小及版本，不读取正文。" +
                        "返回的 revision 用于 edit_file 与分页续读。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            fileProperties()
                                .put("path", filePathSchema())
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "edit_file",
                    description = "精确替换不超过 512 KiB 的 UTF-8 文件中的 old_text。默认必须唯一匹配；" +
                        "未找到或多处匹配时不写入，应重新读取或补充上下文。写入前再次检查旧内容。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            fileProperties()
                                .put("path", filePathSchema())
                                .put(
                                    "old_text",
                                    fileTextSchema("必须与文件中旧内容完全匹配，不可为空。", 524288)
                                        .put("minLength", 1)
                                )
                                .put("new_text", fileTextSchema("替换内容，空字符串表示删除匹配片段。", 524288))
                                .put(
                                    "replace_all",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", false)
                                )
                                .put("expected_revision", fileRevisionSchema())
                        )
                        .put("required", JSONArray().put("path").put("old_text").put("new_text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "glob_files",
                    description = "按路径模式递归查找工作目录内的文件。支持 *、?、**，默认跳过隐藏目录且不追踪目录链接。" +
                        "结果有界；继续查询使用原样 cursor，partial=true 时不能断言已搜索全部文件。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            fileProperties()
                                .put("path", filePathSchema())
                                .put(
                                    "pattern",
                                    fileTextSchema("相对于搜索目录的模式，例如 **/*.json。", 500)
                                        .put("minLength", 1)
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回条目数，默认 80。")
                                        .put("minimum", 1)
                                        .put("maximum", 200)
                                )
                                .put("cursor", fileTextSchema("上次查询返回的不透明续查游标，保持所有查询条件不变。", 512))
                                .put(
                                    "include_hidden",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", false)
                                )
                        )
                        .put("required", JSONArray().put("pattern"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "grep_files",
                    description = "在工作目录的 UTF-8 文本中搜索字面文本，返回路径、行号与匹配行。默认区分大小写；" +
                        "按条目和字节预算分段扫描，可通过游标续查；二进制和不可访问项会被跳过并报告不完整范围。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            fileProperties()
                                .put("path", filePathSchema())
                                .put(
                                    "query",
                                    fileTextSchema("要查找的字面文本，不是正则表达式。", 500)
                                        .put("minLength", 1)
                                )
                                .put("glob", fileTextSchema("相对于目录的文件模式，默认 **/*。", 500))
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回匹配条数，默认 50。")
                                        .put("minimum", 1)
                                        .put("maximum", 200)
                                )
                                .put("cursor", fileTextSchema("上次查询返回的续查游标，保持所有查询条件不变。", 512))
                                .put(
                                    "case_sensitive",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", true)
                                )
                                .put(
                                    "include_hidden",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", false)
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
    }

    /** 文件工具共用的环境、身份与工作目录；与终端保持同一命名空间。 */
    private fun fileProperties(): JSONObject = JSONObject()
        .put(
            "environment",
            JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(listOf("android", "linux")))
                .put("description", "文件路径所属环境，默认 android；linux 使用设置中选定的发行版和后端。")
        )
        .put(
            "identity",
            JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(listOf("user", "root")))
                .put("description", "可选的宿主执行身份，与终端一致；缺省按环境与 Root 授权自动选择。")
        )
        .put(
            "cwd",
            fileTextSchema("可选的工作目录；省略时与终端共用当前环境的默认工作区。", 4096)
        )

    private fun filePathSchema(): JSONObject =
        fileTextSchema("绝对路径或相对于 cwd 的路径；仅接受文件系统路径，不接受 content URI。", 4096)

    private fun fileRevisionSchema(): JSONObject =
        fileTextSchema("上一次文件或目录结果中的 revision；不同环境和身份的版本不能混用。", 256)

    private fun fileTextSchema(description: String, maxLength: Int): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .put("maxLength", maxLength)

}
