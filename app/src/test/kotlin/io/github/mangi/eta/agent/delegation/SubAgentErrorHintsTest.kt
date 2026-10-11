package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubAgentErrorHintsTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test", model = "child", systemPrompt = "")
    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("call", name, args.toString())

    @Test fun failuresWithoutMessageGetANextStep() {
        val annotated = SubAgentErrorHints.annotate(JSONObject().put("ok", false).put("code", "TASK_GROUP_PAUSED"))
        assertTrue(annotated.getString("message").contains("不要重试 delegate_task"))
        val kept = SubAgentErrorHints.annotate(JSONObject().put("ok", false).put("code", "TASK_GROUP_PAUSED").put("message", "原因"))
        assertEquals("原因", kept.getString("message"))
        val ok = SubAgentErrorHints.annotate(JSONObject().put("ok", true).put("code", "TASK_GROUP_PAUSED"))
        assertFalse(ok.has("message"))
        val unknown = SubAgentErrorHints.annotate(JSONObject().put("ok", false).put("code", "SOMETHING_NEW"))
        assertFalse(unknown.has("message"))
    }

    @Test fun unknownTaskIsNotReportedAsInvalidArguments() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> "done" }.use { c ->
            for (name in listOf("get_task_result", "cancel_task", "continue_task")) {
                val result = JSONObject(c.execute(call(name, JSONObject().put("task_id", "missing"))).content)
                assertEquals("TASK_NOT_FOUND", result.getString("code"))
                assertTrue(result.getString("message").contains("get_task_result"))
            }
            val supervise = JSONObject(c.execute(call("supervise_task", JSONObject().put("task_id", "missing").put("action", "pause"))).content)
            assertEquals("TASK_NOT_FOUND", supervise.getString("code"))
        }
    }

    @Test fun invalidArgumentsNameTheProblem() {
        SubAgentCoordinator(listOf(model, model), roles = listOf("image_generation", "research"),
            executeImageChild = { _, _, _, _ -> "image" }) { _, _, _ -> "done" }.use { c ->
            val media = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "draw").put("role", "image_generation")
                .put("project", "/workspace/p"))).content)
            assertEquals("INVALID_TASK_ARGUMENTS", media.getString("code"))
            assertTrue(media.getString("detail").contains("project"))
            assertTrue(media.getString("message").isNotBlank())
        }
    }

    @Test fun liveResultPagingErrorsGiveTheSameCorrectionAsArchivedResults() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> "report" }.use { c ->
            val id = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "inspect"))).content).getString("task_id")
            awaitStatus(c, id, "completed")
            val invalid = JSONObject(c.execute(call("get_task_result",
                JSONObject().put("task_id", id).put("text_limit", 10))).content)
            assertEquals("INVALID_TASK_ARGUMENTS", invalid.getString("code"))
            assertTrue(invalid.getString("detail").contains("require text_field"))
            assertTrue(invalid.getString("message").contains("不是子代理模型"))
            val valid = JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id)
                .put("text_field", "result").put("text_limit", 10))).content)
            assertTrue(valid.getBoolean("ok"))
            assertEquals("report", valid.getString("result"))
        }
    }

    @Test fun implementationWithoutWorkspaceBackendSaysSo() {
        SubAgentCoordinator(listOf(model), roles = listOf("implementation")) { _, _, _ -> "done" }.use { c ->
            val result = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "edit").put("role", "implementation")
                .put("project", "/workspace/Eta"))).content)
            assertEquals("WORKSPACE_UNAVAILABLE", result.getString("code"))
            assertTrue(result.getString("message").contains("implementation"))
        }
    }

    @Test fun guideWithoutGuidanceExplainsTheMissingField() {
        val release = java.util.concurrent.CountDownLatch(1)
        SubAgentCoordinator(listOf(model)) { _, _, _ -> release.await(); "done" }.use { c ->
            try {
                val id = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "inspect"))).content).getString("task_id")
                val deadline = System.currentTimeMillis() + 2_000
                while (JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id))).content).getString("status") != "running") {
                    assertTrue(System.currentTimeMillis() < deadline)
                    Thread.sleep(10)
                }
                val result = JSONObject(c.execute(call("supervise_task", JSONObject().put("task_id", id).put("action", "guide"))).content)
                assertEquals("INVALID_TASK_ARGUMENTS", result.getString("code"))
                assertTrue(result.getString("detail").contains("guidance"))
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun toolDescriptionsStateTheRulesModelsKeepBreaking() {
        val tools = JSONArray().also { SubAgentTools.appendTo(it, listOf("a", "b"), workspaceEnabled = true) }
        fun description(name: String): String {
            for (i in 0 until tools.length()) {
                val fn = tools.getJSONObject(i).getJSONObject("function")
                if (fn.getString("name") == name) return fn.getString("description")
            }
            error("missing $name")
        }
        val delegate = description("delegate_task")
        assertTrue(delegate.contains("TASK_GROUP_PAUSED"))
        assertTrue(delegate.contains("no uncommitted changes"))
        assertTrue(delegate.contains("never pass workspace_id"))
        assertTrue(delegate.contains("execution_stopped=true"))
        assertTrue(delegate.contains("different agent_id"))
        assertTrue(delegate.contains("missing shell is not a reason for the parent to read that source itself"))
        assertTrue(description("get_task_result").contains("text_offset and text_limit always require text_field"))
        assertTrue(description("get_task_result").contains("not a worker/provider failure"))
        assertTrue(description("supervise_task").contains("need status exactly running"))
        assertTrue(description("supervise_task").contains("allowed_actions"))
        assertTrue(description("manage_agent_workspace").contains("WORKSPACE_IN_USE"))
    }

    private fun awaitStatus(c: SubAgentCoordinator, id: String, status: String) {
        val deadline = System.currentTimeMillis() + 2_000
        while (JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id))).content).getString("status") != status) {
            assertTrue(System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    @Test fun unknownTaskListsKnownIds() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> "done" }.use { c ->
            val id = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "inspect"))).content).getString("task_id")
            val result = JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", "missing"))).content)
            assertEquals("TASK_NOT_FOUND", result.getString("code"))
            assertEquals(id, result.getJSONArray("known_task_ids").getString(0))
        }
    }

    @Test fun pausingAPausedTaskSucceedsAndGuideReportsStatus() {
        val release = java.util.concurrent.CountDownLatch(1)
        SubAgentCoordinator(listOf(model)) { _, _, controller -> release.await(); controller.throwIfCancelled(); "done" }.use { c ->
            try {
                val id = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "inspect"))).content).getString("task_id")
                awaitStatus(c, id, "running")
                val first = JSONObject(c.execute(call("supervise_task", JSONObject().put("task_id", id).put("action", "pause"))).content)
                assertEquals("awaiting_decision", first.getString("status"))
                val again = JSONObject(c.execute(call("supervise_task", JSONObject().put("task_id", id).put("action", "pause"))).content)
                assertTrue(again.getBoolean("ok"))
                assertEquals("awaiting_decision", again.getString("status"))
                assertTrue(again.getString("note").contains("continue_task"))
                val guide = JSONObject(c.execute(call("supervise_task", JSONObject().put("task_id", id).put("action", "guide").put("guidance", "x"))).content)
                assertEquals("TASK_NOT_RUNNING_TEXT", guide.getString("code"))
                assertEquals("awaiting_decision", guide.getString("status"))
                assertEquals("continue_task", guide.getJSONArray("allowed_actions").getString(0))
                c.execute(call("continue_task", JSONObject().put("task_id", id)))
                val resumed = JSONObject(c.execute(call("continue_task", JSONObject().put("task_id", id))).content)
                assertEquals("TASK_NOT_AWAITING_DECISION", resumed.getString("code"))
                assertTrue(resumed.getString("status") in setOf("queued", "running"))
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun finishedTaskOnlyAllowsReading() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> "done" }.use { c ->
            val id = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "inspect"))).content).getString("task_id")
            awaitStatus(c, id, "completed")
            val result = JSONObject(c.execute(call("supervise_task", JSONObject().put("task_id", id).put("action", "pause"))).content)
            assertEquals("TASK_NOT_RUNNING_TEXT", result.getString("code"))
            assertEquals("completed", result.getString("status"))
            assertEquals(1, result.getJSONArray("allowed_actions").length())
            assertEquals("get_task_result", result.getJSONArray("allowed_actions").getString(0))
        }
    }

    @Test fun oversizedPagingIsClampedNotRejected() {
        val tools = JSONArray().also { SubAgentTools.appendTo(it, listOf("a"), workspaceEnabled = false) }
        val get = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .single { it.getString("name") == "get_task_result" }
        val properties = get.getJSONObject("parameters").getJSONObject("properties")
        assertFalse(properties.getJSONObject("event_limit").has("maximum"))
        assertFalse(properties.getJSONObject("wait_ms").has("maximum"))
        SubAgentCoordinator(listOf(model)) { _, _, _ -> "done" }.use { c ->
            val id = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "inspect"))).content).getString("task_id")
            awaitStatus(c, id, "completed")
            val result = JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id).put("event_limit", 100))).content)
            assertTrue(result.getBoolean("ok"))
            assertEquals(32, result.getInt("event_limit_used"))
        }
    }
}
