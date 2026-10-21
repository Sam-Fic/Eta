package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.question.AgentQuestionOption
import io.github.mangi.eta.agent.question.AgentQuestionRequest
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 激进折叠档（[io.github.mangi.eta.data.model.AnswerFoldLevel.AGGRESSIVE]）的归组投影。
 *
 * 目标展示形态：一轮跑完后，界面只剩用户消息、一张（默认收起的）工作过程卡片、最后一条回复；
 * 普通/不折叠档必须与旧投影逐字节一致。只断言纯投影与懒加载行，不起 Compose。
 */
class AgentTimelineAggressiveFoldTest {

    private fun tool(id: String, running: Boolean = false) = ToolActivityMessageUi(
        id = id, toolName = "terminal",
        status = if (running) ToolActivityStatusUi.Running else ToolActivityStatusUi.Success,
        argumentsSummary = "cmd-$id", command = "cmd-$id", resultSummary = "res-$id",
    )

    private fun thinking(id: String, streaming: Boolean = false) = ThinkingMessageUi(
        id = id, content = "thought-$id", isStreaming = streaming,
        elapsedSeconds = if (streaming) null else 7,
    )

    private fun question(id: String) = AgentQuestionMessageUi(
        id = id,
        request = AgentQuestionRequest(
            "Which way?", "chat", "run", "call", "Decide", "Choose one",
            listOf(AgentQuestionOption("a", "A"), AgentQuestionOption("b", "B")),
            recommendedOptionId = "a",
        ),
    )

    private fun List<AgentTimelineEntry>.flattenMessages(): List<AgentChatMessageUi> = flatMap { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> listOf(entry.message)
            is AgentTimelineEntry.WorkProcess -> entry.messages
        }
    }

    private fun steps(entries: List<AgentTimelineEntry>) =
        entries.filterIsInstance<AgentTimelineEntry.WorkProcess>()

    @Test fun intermediateRepliesMergeIntoASingleCardBeforeTheFinalReply() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"), tool("a1"),
            AgentMessageUi("mid", "中间说明"),
            thinking("t2"),
            AgentMessageUi("final", "最终回答"),
        )

        val aggressive = messages.toTimelineEntries(aggressive = true)
        assertEquals(listOf("u1", "work-t1", "final"), aggressive.map { it.key })
        assertEquals(
            listOf(thinking("t1"), tool("a1"), AgentMessageUi("mid", "中间说明"), thinking("t2")),
            steps(aggressive).single().messages,
        )
        // 中间回复一个不丢，全部在卡片里。
        assertEquals(messages, aggressive.flattenMessages())

        // 普通/不折叠档与旧投影完全一致：中间回复仍是独立行，分两组。
        val normal = messages.toTimelineEntries(aggressive = false)
        assertEquals(messages.toTimelineEntries(), normal)
        assertEquals(listOf("u1", "work-t1", "mid", "work-t2", "final"), normal.map { it.key })
    }

    @Test fun noticesAndBlankRepliesInTheRunAreAbsorbedToo() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"),
            SystemNoticeMessageUi("n1", SystemNoticeCode.Stopped),
            AgentMessageUi("blank", ""),
            tool("a1"),
            SystemNoticeMessageUi("n2", SystemNoticeCode.Completed),
            AgentMessageUi("final", "answer"),
        )

        val entries = messages.toTimelineEntries(aggressive = true)
        assertEquals(listOf("u1", "work-t1", "final"), entries.map { it.key })
        assertEquals(messages, entries.flattenMessages())
    }

    @Test fun turnWithoutAnyReplyAbsorbsNothingSoErrorsStayVisible() {
        // 整轮没有正文（断线、失败）：不许把错误藏进卡片。
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            tool("a1", running = true),
            AgentMessageUi("blank", ""),
            SystemNoticeMessageUi("n1", SystemNoticeCode.Stopped),
        )

        val entries = messages.toTimelineEntries(aggressive = true)
        assertEquals(listOf("u1", "work-a1", "blank", "n1"), entries.map { it.key })
        assertTrue(entries.autoFoldedWorkKeys(enabled = true).isEmpty())
    }

    @Test fun contentAfterTheFinalReplyKeepsItsOwnRows() {
        // 尾部（Completed 分隔、建议芯片等）在最终回复之后，保持可见。
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"),
            AgentMessageUi("final", "answer"),
            SystemNoticeMessageUi("n1", SystemNoticeCode.Completed),
            SuggestionChipsMessageUi("chips", listOf("Try again")),
        )

        val entries = messages.toTimelineEntries(aggressive = true)
        assertEquals(listOf("u1", "work-t1", "final", "n1", "chips"), entries.map { it.key })
    }

    @Test fun interactiveQuestionCardStaysOutsideTheAbsorbedCard() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"),
            question("q1"),
            AgentMessageUi("final", "answer"),
        )

        val entries = messages.toTimelineEntries(aggressive = true)
        assertEquals(listOf("u1", "work-t1", "q1", "final"), entries.map { it.key })
    }

    @Test fun streamingIntermediateReplyIsNeverAbsorbed() {
        // 流式中的回复保留为行内指示，等它停下、并且后面还有回复时才并入卡片。
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"),
            AgentMessageUi("mid", "streaming", isStreaming = true),
            thinking("t2"),
            AgentMessageUi("final", "answer"),
        )

        val entries = messages.toTimelineEntries(aggressive = true)
        assertEquals(listOf("u1", "work-t1", "mid", "work-t2", "final"), entries.map { it.key })
    }

    @Test fun steerBubbleSplitsTheCardsButOpensNoNewTurn() {
        // steer 是可见锚点（切成两张卡），但同属一轮：最终回复到达后两张卡一起收。
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"), AgentMessageUi("mid", "mid answer"),
            UserMessageUi("u1-supplement-1", "steer"),
            thinking("t2"),
            AgentMessageUi("final", "final answer"),
        )

        val entries = messages.toTimelineEntries(aggressive = true)
        assertEquals(listOf("u1", "work-t1", "u1-supplement-1", "work-t2", "final"), entries.map { it.key })

        val folded = entries.autoFoldedWorkKeys(enabled = true)
        assertEquals(setOf("work-t1", "work-t2"), folded)
        val rows = entries.toLazyTimelineRows(emptyMap(), isStreaming = false, foldedWorkKeys = folded)
        assertEquals(listOf("u1", "work-t1", "u1-supplement-1", "work-t2", "final"), rows.map { it.key })
        assertTrue(rows.filterIsInstance<AgentTimelineRow.WorkHeader>().all { !it.expanded && it.folded })
    }

    @Test fun settledTurnEndsAsUserCardAndFinalReplyOnly() {
        // 规格验收：一轮跑完（已停下）后只剩用户消息、一张收起的卡片、最后一条回复。
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"), tool("a1"),
            AgentMessageUi("mid", "中间说明"),
            thinking("t2"), tool("a2"),
            AgentMessageUi("final", "最终回答"),
        )

        val entries = messages.toTimelineEntries(aggressive = true)
        val rows = entries.toLazyTimelineRows(
            emptyMap(), isStreaming = false, foldedWorkKeys = entries.autoFoldedWorkKeys(enabled = true),
        )

        assertEquals(listOf("u1", "work-t1", "final"), rows.map { it.key })
        val header = rows[1] as AgentTimelineRow.WorkHeader
        assertFalse(header.expanded)
        assertTrue(header.folded)
        assertTrue(rows.none { it is AgentTimelineRow.WorkStep })
        // 生成进行中（还没有任何回复）：工作卡片在末尾、步骤还在跑，照旧摊开看进度。
        val inFlight = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1", streaming = true), tool("a1", running = true),
        ).toTimelineEntries(aggressive = true)
        val inFlightRows = inFlight.toLazyTimelineRows(emptyMap(), isStreaming = true, foldedWorkKeys = emptySet())
        val inFlightHeader = inFlightRows[1] as AgentTimelineRow.WorkHeader
        assertTrue(inFlightHeader.expanded)
        assertFalse(inFlightHeader.folded)
    }

    @Test fun manualExpansionOverrideStillBeatsTheAggressiveFold() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"), tool("a1"),
            AgentMessageUi("final", "answer"),
        )
        val entries = messages.toTimelineEntries(aggressive = true)
        val key = steps(entries).single().key
        val folded = entries.autoFoldedWorkKeys(enabled = true)

        val reopened = entries.toLazyTimelineRows(
            mapOf(key to true), isStreaming = false, foldedWorkKeys = folded,
        )
        assertTrue(reopened.filterIsInstance<AgentTimelineRow.WorkHeader>().single().expanded)
        assertEquals(2, reopened.count { it is AgentTimelineRow.WorkStep })
    }

    @Test fun batchLimitStillBoundsASingleAbsorbedRun() {
        // 吸收也遵守 32 条一批：一次长跑会得到多张相邻卡片，不丢消息、键不重复。
        val work = List(40) { thinking("t$it") }
        val messages: List<AgentChatMessageUi> =
            listOf(UserMessageUi("u1", "task")) + work + listOf(AgentMessageUi("final", "answer"))

        val entries = messages.toTimelineEntries(aggressive = true)
        assertEquals(listOf("u1", "work-t0", "work-t32", "final"), entries.map { it.key })
        assertEquals(listOf(32, 8), steps(entries).map { it.messages.size })
        assertEquals(messages, entries.flattenMessages())
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test fun projectionCacheMatchesFullProjectionAcrossReGrouping() {
        val cache = AgentTimelineProjectionCache(
            fullProjection = { it.toTimelineEntries(aggressive = true) },
            aggressiveGrouping = true,
        )
        val first: List<AgentChatMessageUi> = listOf(
            UserMessageUi("u1", "task"),
            thinking("t1"),
            AgentMessageUi("mid", "intermediate"),
        )
        assertEquals(first.toTimelineEntries(aggressive = true), cache.project(first))

        // 第二条回复追加后，mid 应被吸进卡片：追加快路径必须让位于全量投影。
        val second = first + AgentMessageUi("final", "answer", isStreaming = true)
        assertEquals(second.toTimelineEntries(aggressive = true), cache.project(second))
        assertTrue(steps(cache.project(second)).single().messages.any { it.id == "mid" })

        // 流式增长（可见回复）与被吸收回复的载荷替换都必须与全量投影一致。
        val third = second.map { message ->
            when {
                message is AgentMessageUi && message.id == "final" ->
                    message.copy(content = message.content + " more")
                message is AgentMessageUi && message.id == "mid" ->
                    message.copy(content = message.content + "（补充）")
                else -> message
            }
        }
        assertEquals(third.toTimelineEntries(aggressive = true), cache.project(third))

        // 恒等输入可复用缓存快照（吸收态下映射不能退化成全量路径）。
        assertSameEntries(cache.project(third), cache.project(third))
    }

    private fun assertSameEntries(
        expected: List<AgentTimelineEntry>,
        actual: List<AgentTimelineEntry>,
    ) {
        assertTrue("Cached projection should reuse the recorded entry list", expected === actual)
    }
}
