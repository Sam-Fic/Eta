package io.github.mangi.eta.agent.delegation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubAgentPagingErrorHintTest {
    @Test fun invalidPagingHintNamesTheDependencyAndRejectsRedispatch() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SubAgentResultPage.validate(JSONObject().put("text_limit", 100))
        }
        val result = SubAgentErrorHints.annotate(JSONObject().put("ok", false)
            .put("code", "INVALID_TASK_ARGUMENTS").put("detail", failure.message))
        assertTrue(result.getString("detail").contains("require text_field"))
        assertTrue(result.getString("message").contains("不是子代理模型或供应商故障"))
        assertTrue(result.getString("message").contains("text_offset/text_limit"))
        assertTrue(result.getString("message").contains("必须同时指定 text_field"))
        assertTrue(result.getString("message").contains("不要因此重派、替换任务"))
        val corrected = SubAgentResultPage.project(JSONObject().put("status", "completed").put("result", "report"),
            JSONObject().put("text_field", "result").put("text_limit", 100))
        assertEquals("report", corrected.getString("result"))
    }

    @Test fun archiveFailureDoesNotClaimBadArgumentsOrProviderFailure() {
        val result = SubAgentErrorHints.annotate(JSONObject().put("ok", false).put("code", "TASK_RESULT_UNAVAILABLE"))
        assertTrue(result.getString("message").contains("不是调用参数错误"))
        assertTrue(result.getString("message").contains("不要重派、替换或重放任务"))
    }

    @Test fun existingFailureMessageAndSuccessfulResultsStayUnchanged() {
        val failure = JSONObject().put("ok", false).put("code", "INVALID_TASK_ARGUMENTS").put("message", "specific")
        assertEquals("specific", SubAgentErrorHints.annotate(failure).getString("message"))
        val success = JSONObject().put("ok", true).put("result", "report")
        assertFalse(SubAgentErrorHints.annotate(success).has("message"))
    }
}
