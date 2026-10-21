package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本轮回复结束后，把回答之前的思考/工具整组收起」投影策略。
 *
 * 只断言纯投影结果，不起 Compose：这条路径同时决定懒加载行的数量，回归点全在数据层。
 */
class WorkProcessAutoFoldTest {

    private fun tool(id: String, running: Boolean = false) = ToolActivityMessageUi(
        id = id, toolName = "terminal",
        status = if (running) ToolActivityStatusUi.Running else ToolActivityStatusUi.Success,
        argumentsSummary = "cmd-$id", command = "cmd-$id", resultSummary = "res-$id",
    )

    private fun thinking(id: String, streaming: Boolean = false) = ThinkingMessageUi(
        id = id, content = "thought-$id", isStreaming = streaming, elapsedSeconds = if (streaming) null else 7,
    )

    private fun steps(entries: List<AgentTimelineEntry>) = entries.filterIsInstance<AgentTimelineEntry.WorkProcess>()

    @Test fun foldedSetCoversEveryWorkGroupPrecedingTheAnswer() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"), tool("a1"),
            AgentMessageUi("mid", "中间说明"),
            thinking("t2"), tool("a2"),
            AgentMessageUi("final", "最终回答", isStreaming = false),
        )
        val groups = messages.toTimelineEntries()
        val work = steps(groups)
        assertEquals(2, work.size)

        // 开关关闭时一个都不收，行为与旧投影一致。
        assertTrue(groups.autoFoldedWorkKeys(enabled = false).isEmpty())

        val folded = groups.autoFoldedWorkKeys(enabled = true)
        assertEquals(setOf(work[0].key, work[1].key), folded)
    }

    @Test fun trailingGroupStillStreamingIsNotFolded() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"), tool("a1"),
            AgentMessageUi("mid", "中间说明"),
            tool("a2", running = true),
        )
        val groups = messages.toTimelineEntries()
        val work = steps(groups)

        // 回答之前那组收；仍在跑的尾组保持摊开，让用户看见进度。
        assertEquals(setOf(work[0].key), groups.autoFoldedWorkKeys(enabled = true))
    }

    @Test fun nextTurnResetsTheBoundaryAndStaysOpenUntilItsOwnAnswer() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "first"),
            tool("a1"), AgentMessageUi("r1", "answer one"),
            UserMessageUi("u2", "second"),
            tool("a2", running = true),
        )
        val groups = messages.toTimelineEntries()
        val work = steps(groups)

        assertEquals(setOf(work[0].key), groups.autoFoldedWorkKeys(enabled = true))
        val rows = groups.toLazyTimelineRows(emptyMap(), isStreaming = true, foldedWorkKeys = groups.autoFoldedWorkKeys(true))
        val headers = rows.filterIsInstance<AgentTimelineRow.WorkHeader>()
        assertFalse(headers[0].expanded)
        assertTrue(headers[1].expanded)
    }

    @Test fun manualOverrideAlwaysBeatsTheFoldPolicy() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            tool("a1"), tool("a2"),
            AgentMessageUi("final", "answer"),
        )
        val groups = messages.toTimelineEntries()
        val key = steps(groups).single().key
        val folded = groups.autoFoldedWorkKeys(enabled = true)

        val expanded = groups.toLazyTimelineRows(mapOf(key to true), isStreaming = false, foldedWorkKeys = folded)
        assertTrue(expanded.filterIsInstance<AgentTimelineRow.WorkHeader>().single().expanded)
        assertEquals(2, expanded.count { it is AgentTimelineRow.WorkStep })

        val collapsed = groups.toLazyTimelineRows(mapOf(key to false), isStreaming = false, foldedWorkKeys = folded)
        assertFalse(collapsed.filterIsInstance<AgentTimelineRow.WorkHeader>().single().expanded)
    }

    @Test fun foldCollapsesEveryStepWithoutLosingTheHeaderOrKeys() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"), tool("a1"), tool("a2"), thinking("t2"),
            AgentMessageUi("final", "answer"),
        )
        val groups = messages.toTimelineEntries()
        val open = groups.toLazyTimelineRows(emptyMap(), isStreaming = false, foldedWorkKeys = emptySet())
        val closed = groups.toLazyTimelineRows(emptyMap(), isStreaming = false, foldedWorkKeys = groups.autoFoldedWorkKeys(true))

        assertEquals(1, closed.filterIsInstance<AgentTimelineRow.WorkHeader>().size)
        assertTrue(closed.none { it is AgentTimelineRow.WorkStep })
        assertEquals(listOf("u1", "work-t1", "final"), closed.map { it.key })
        assertEquals(open.map { it.key }.filter { it !in setOf("work-step:t1", "work-step:a1", "work-step:a2", "work-step:t2") }, closed.map { it.key })
    }

    @Test fun stuckRunningTailFoldsOnceTheAnswerLands() {
        // 断线重连后步骤永远停在 Running，回答也已给出：整组必须收进「已完成 N 个步骤」。
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            tool("a1", running = true), thinking("t1", streaming = true),
            AgentMessageUi("final", "answer"),
        )
        val groups = messages.toTimelineEntries()
        val rows = groups.toLazyTimelineRows(emptyMap(), isStreaming = false, foldedWorkKeys = groups.autoFoldedWorkKeys(true))
        val header = rows.filterIsInstance<AgentTimelineRow.WorkHeader>().single()

        assertFalse(header.expanded)
        assertTrue(header.folded)
        assertTrue(rows.none { it is AgentTimelineRow.WorkStep })
    }

    @Test fun steerSupplementAndResumeDoNotOpenANewTurn() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            tool("a1"),
            UserMessageUi("u1-supplement-1", "steer"),
            tool("a2"),
            AgentMessageUi("final", "answer"),
        )
        val groups = messages.toTimelineEntries()
        val work = steps(groups)
        assertEquals(2, work.size)
        assertEquals(setOf(work[0].key, work[1].key), groups.autoFoldedWorkKeys(enabled = true))
    }

    @Test fun emptyAssistantBodyDoesNotFoldTheTraceBehindIt() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            tool("a1"), tool("a2", running = true),
            AgentMessageUi("blank", ""),
            SystemNoticeMessageUi("n1", SystemNoticeCode.Completed),
        )
        val groups = messages.toTimelineEntries()

        assertTrue(groups.autoFoldedWorkKeys(enabled = true).isEmpty())
        val rows = groups.toLazyTimelineRows(emptyMap(), isStreaming = true, foldedWorkKeys = emptySet())
        assertTrue(rows.filterIsInstance<AgentTimelineRow.WorkHeader>().single().expanded)
    }

    @Test fun incrementalRowCacheMatchesFullProjectionWhenTheFoldAppears() {
        val before = listOf<AgentChatMessageUi>(
            UserMessageUi("u1", "task"), tool("a1"), tool("a2"),
        ).toTimelineEntries()
        val cache = AgentTimelineRowsCache()
        cache.project(before, emptyMap(), isStreaming = true, retainedSteps = emptyMap(), foldedWorkKeys = emptySet())

        val after = listOf<AgentChatMessageUi>(
            UserMessageUi("u1", "task"), tool("a1"), tool("a2"), AgentMessageUi("final", "answer"),
        ).toTimelineEntries()
        val folded = after.autoFoldedWorkKeys(enabled = true)
        val cached = cache.project(after, emptyMap(), isStreaming = false, foldedWorkKeys = folded)
        val full = after.toLazyTimelineRows(emptyMap(), isStreaming = false, foldedWorkKeys = folded)

        assertEquals(full, cached)
        assertFalse(cached.filterIsInstance<AgentTimelineRow.WorkHeader>().single().expanded)
    }
}
