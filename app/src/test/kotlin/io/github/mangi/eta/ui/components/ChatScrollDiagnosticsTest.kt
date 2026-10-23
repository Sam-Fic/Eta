package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollDiagnosticsTest {
    private class RecordingSink : ChatScrollTraceSink {
        val counters = mutableListOf<Pair<String, Long>>()
        val sections = mutableListOf<String>()

        override fun counter(name: String, value: Long) {
            counters += name to value
        }

        override fun section(name: String) {
            sections += name
        }

        fun last(name: String): Long = counters.last { it.first == name }.second
        fun names(): List<String> = counters.map { it.first } + sections
    }

    private fun geometry(
        rows: List<ChatScrollRowGeometry> = listOf(
            ChatScrollRowGeometry("private-first", 3, -12, 100),
            ChatScrollRowGeometry("private-second", 4, 88, 80),
        ),
    ) = ChatScrollGeometry(
        firstIndex = 3,
        firstOffset = 12,
        viewportStart = -24,
        viewportEnd = 600,
        totalCount = 30,
        canScrollForward = true,
        canScrollBackward = true,
        isScrollInProgress = false,
        visibleCount = rows.size,
        rows = rows,
    )

    @Test fun anonymousIdsAreStableByEqualityWhileResident() {
        val ids = ChatScrollAnonymousIds(3)
        val key = "private-key"
        val first = ids.idFor(key)
        assertTrue(first > 0L)
        assertEquals(first, ids.idFor(String(key.toCharArray())))
        assertNotEquals(first, ids.idFor("different-private-key"))
        assertEquals(2, ids.size)
    }

    @Test fun anonymousIdsAreBoundedLruAndNeverRecycleEvictedIds() {
        val ids = ChatScrollAnonymousIds(2)
        val first = ids.idFor("first")
        val second = ids.idFor("second")
        assertEquals(first, ids.idFor("first")) // Refresh first, evict second.
        val third = ids.idFor("third")
        assertEquals(first, ids.idFor("first"))
        assertTrue(ids.idFor("second") > third)
        assertNotEquals(second, ids.idFor("second"))
        repeat(1_000) { ids.idFor("private-$it") }
        assertEquals(2, ids.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anonymousIdsRejectZeroCapacity() {
        ChatScrollAnonymousIds(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anonymousIdsRejectCapacityAboveTheHardLimit() {
        ChatScrollAnonymousIds(CHAT_SCROLL_TRACE_MAX_IDENTITIES + 1)
    }

    @Test fun defaultOrAnonymousKeysAreNeverStringified() {
        val key = object {
            override fun toString(): String = error("Must not stringify a row key")
        }
        val ids = ChatScrollAnonymousIds()
        val sink = RecordingSink()
        ChatScrollGeometryWriter(ids, sink).record(
            true, 7L, geometry(listOf(ChatScrollRowGeometry(key, 0, 0, 100))),
        )
        assertEquals(ids.idFor(key), sink.last("chat.list.v0.id"))
    }

    @Test fun disabledWritersDoNotEmitOrAllocateIdentities() {
        val ids = ChatScrollAnonymousIds()
        val sink = RecordingSink()
        val row = ChatScrollRowTraceState(5L, ids, sink)
        val writer = ChatScrollGeometryWriter(ids, sink)
        repeat(3) {
            row.commit(false, "private-$it", "tool")
            writer.record(false, 2L, geometry().copy(firstOffset = it))
            emitChatScrollToggle(false, "thinking", true, sink)
        }
        row.dispose()
        assertTrue(sink.names().isEmpty())
        assertEquals(0, ids.size)
    }

    @Test fun enablingAnExistingRowAttachesRatherThanInventingAStart() {
        val sink = RecordingSink()
        val row = ChatScrollRowTraceState(5L, ChatScrollAnonymousIds(), sink)
        row.commit(false, "private", "work-tool")
        row.commit(true, "private", "work-tool")
        assertEquals(
            listOf(
                "chat.row.attach t=work-tool r=1 i=5",
                "chat.row.commit t=work-tool r=1 i=5",
            ),
            sink.sections,
        )
    }

    @Test fun freshInstancesOfTheSameRowHaveSeparateStartsButTheSameIdentity() {
        val sink = RecordingSink()
        val ids = ChatScrollAnonymousIds()
        val first = ChatScrollRowTraceState(5L, ids, sink)
        first.commit(true, "private", "message")
        first.dispose()
        ChatScrollRowTraceState(6L, ids, sink).commit(true, "private", "message")
        assertTrue(sink.sections.contains("chat.row.start t=message r=1 i=5"))
        assertTrue(sink.sections.contains("chat.row.dispose t=message r=1 i=5"))
        assertTrue(sink.sections.contains("chat.row.start t=message r=1 i=6"))
        assertEquals(1, ids.size)
    }

    @Test fun togglingDiagnosticsKeepsTheSameRememberedInstanceAndDoesNotDisposeIt() {
        val sink = RecordingSink()
        val row = ChatScrollRowTraceState(5L, ChatScrollAnonymousIds(), sink)
        row.commit(true, "private", "message")
        row.commit(false, "private", "message")
        row.commit(true, "private", "message")
        assertEquals(1, sink.sections.count { it.startsWith("chat.row.start ") })
        assertEquals(1, sink.sections.count { it.startsWith("chat.row.attach ") })
        assertEquals(2, sink.sections.count { it.startsWith("chat.row.commit ") })
        assertFalse(sink.sections.any { it.startsWith("chat.row.dispose ") })
        assertTrue(sink.sections.all { it.endsWith("r=1 i=5") })
    }

    @Test fun disabledDisposalEmitsNothingAndEnabledDisposalIsIdempotent() {
        val sink = RecordingSink()
        val ids = ChatScrollAnonymousIds()
        val row = ChatScrollRowTraceState(5L, ids, sink)
        row.commit(true, "private", "agent")
        row.commit(false, "private", "agent")
        row.dispose()
        assertFalse(sink.sections.any { it.startsWith("chat.row.dispose ") })
        val live = ChatScrollRowTraceState(6L, ids, sink)
        live.commit(true, "private", "agent")
        live.dispose()
        live.dispose()
        assertEquals(1, sink.sections.count { it.startsWith("chat.row.dispose ") })
    }

    @Test fun retainedInstancesCanChangeIdentityWithoutFakingRecreation() {
        val sink = RecordingSink()
        val row = ChatScrollRowTraceState(5L, ChatScrollAnonymousIds(), sink)
        row.commit(true, "old-private-key", "user")
        row.commit(true, "new-private-key", "agent")
        assertEquals(1, sink.sections.count { it.startsWith("chat.row.start ") })
        assertEquals("chat.row.commit t=agent r=2 i=5", sink.sections.last())
    }

    @Test fun rowTypesAndToggleKindsAreAllowlistedAndOutputNeverContainsKeysOrUris() {
        val privateValue = "content://private/message/" + "sensitive".repeat(100)
        val sink = RecordingSink()
        val ids = ChatScrollAnonymousIds()
        val row = ChatScrollRowTraceState(Long.MAX_VALUE, ids, sink)
        row.commit(true, privateValue, privateValue)
        row.dispose()
        emitChatScrollToggle(true, privateValue, true, sink)
        ChatScrollGeometryWriter(ids, sink).record(
            true, Long.MAX_VALUE, geometry(listOf(ChatScrollRowGeometry(privateValue, 0, 0, 30))),
        )
        assertEquals("other", chatScrollTraceType(privateValue))
        assertTrue(sink.sections.any { it == "chat.toggle k=other expanded=1" })
        assertTrue(sink.names().all { it.length < 127 })
        assertTrue(sink.names().none { it.contains("content:") || it.contains("sensitive") })
        assertTrue(sink.names().none { it.contains(privateValue) })
        assertEquals(sink.last("chat.list.v0.id"), ids.idFor(privateValue))
    }

    @Test fun supportedRowTypesRemainRecognizable() {
        listOf(
            "message", "user", "agent", "thinking", "tool", "work",
            "work-header", "work-tool", "work-thinking", "work-question", "work-summary",
        ).forEach { assertEquals(it, chatScrollTraceType(it)) }
        assertEquals("other", chatScrollTraceType("MESSAGE"))
        assertEquals("other", chatScrollTraceType("message|private"))
    }

    @Test fun toggleMarkersArePointEventsWithNumericExpandedState() {
        val sink = RecordingSink()
        emitChatScrollToggle(true, "tool", true, sink)
        emitChatScrollToggle(true, "thinking", false, sink)
        assertEquals(
            listOf("chat.toggle k=tool expanded=1", "chat.toggle k=thinking expanded=0"),
            sink.sections,
        )
    }

    @Test fun workToggleKindIsNotCollapsedIntoOther() {
        val sink = RecordingSink()
        emitChatScrollToggle(true, "work", true, sink)
        emitChatScrollToggle(true, "work", false, sink)
        assertEquals(
            listOf("chat.toggle k=work expanded=1", "chat.toggle k=work expanded=0"),
            sink.sections,
        )
        assertFalse(sink.sections.any { it.contains("other") })
    }

    @Test fun identicalGeometryIsDeduplicatedAndReattachmentGetsABaseline() {
        val sink = RecordingSink()
        val writer = ChatScrollGeometryWriter(ChatScrollAnonymousIds(), sink)
        val first = geometry()
        writer.record(true, 7L, first)
        val count = sink.counters.size
        writer.record(true, 7L, first.copy(rows = first.rows.toList()))
        assertEquals(count, sink.counters.size)
        writer.record(false, 7L, first)
        assertEquals(count, sink.counters.size)
        writer.record(true, 7L, first)
        assertEquals(count * 2, sink.counters.size)
        assertEquals(2L, sink.last("chat.list.sample"))
        writer.record(true, 8L, first)
        assertEquals(8L, sink.last("chat.list.instance"))
        assertEquals(3L, sink.last("chat.list.sample"))
    }

    @Test fun geometryIncludesBothAnchorsViewportCountsAndRealScrollState() {
        val sink = RecordingSink()
        ChatScrollGeometryWriter(ChatScrollAnonymousIds(), sink).record(true, 7L, geometry())
        assertEquals(3L, sink.last("chat.list.first_index"))
        assertEquals(12L, sink.last("chat.list.first_offset"))
        assertEquals(-24L, sink.last("chat.list.viewport_start"))
        assertEquals(600L, sink.last("chat.list.viewport_end"))
        assertEquals(30L, sink.last("chat.list.total_count"))
        assertEquals(1L, sink.last("chat.list.can_forward"))
        assertEquals(1L, sink.last("chat.list.can_backward"))
        assertEquals(0L, sink.last("chat.list.scroll_in_progress"))
        assertEquals(-12L, sink.last("chat.list.v0.offset"))
        assertEquals(100L, sink.last("chat.list.v0.size"))
        assertEquals(4L, sink.last("chat.list.v1.index"))
        assertEquals(88L, sink.last("chat.list.v1.offset"))
        assertEquals(80L, sink.last("chat.list.v1.size"))
        assertNotEquals(sink.last("chat.list.v0.id"), sink.last("chat.list.v1.id"))
        assertEquals("chat.list.sample", sink.counters.last().first)
    }

    @Test fun heightChangeMovingTheSecondAnchorIsNotReportedAsAScrollDelta() {
        val sink = RecordingSink()
        val writer = ChatScrollGeometryWriter(ChatScrollAnonymousIds(), sink)
        val before = geometry()
        writer.record(true, 7L, before)
        val after = before.copy(rows = listOf(
            before.rows[0].copy(size = 150),
            before.rows[1].copy(offset = 138),
        ))
        writer.record(true, 7L, after)
        assertEquals(2L, sink.last("chat.list.sample"))
        assertEquals(12L, sink.last("chat.list.first_offset"))
        assertEquals(-12L, sink.last("chat.list.v0.offset"))
        assertEquals(150L, sink.last("chat.list.v0.size"))
        assertEquals(138L, sink.last("chat.list.v1.offset"))
        assertEquals(0L, sink.last("chat.list.scroll_in_progress"))
        assertTrue(sink.names().none { it.contains("delta") || it.contains("timing") })
    }

    @Test fun changesToSecondAnchorAloneAreNotDeduplicatedAway() {
        val sink = RecordingSink()
        val writer = ChatScrollGeometryWriter(ChatScrollAnonymousIds(), sink)
        var sample = geometry()
        writer.record(true, 7L, sample)
        val mutations = listOf(
            sample.rows[1].copy(index = 5),
            sample.rows[1].copy(offset = 99),
            sample.rows[1].copy(size = 90),
            sample.rows[1].copy(key = "replacement-private"),
        )
        mutations.forEach { second ->
            sample = sample.copy(rows = listOf(sample.rows[0], second))
            writer.record(true, 7L, sample)
        }
        assertEquals(5L, sink.last("chat.list.sample"))
    }

    @Test fun scrollFlagsViewportAndCountChangesAreIncludedInDeduplication() {
        val sink = RecordingSink()
        val writer = ChatScrollGeometryWriter(ChatScrollAnonymousIds(), sink)
        val initial = geometry()
        val samples = listOf(
            initial,
            initial.copy(isScrollInProgress = true),
            initial.copy(canScrollForward = false),
            initial.copy(canScrollBackward = false),
            initial.copy(viewportStart = -32),
            initial.copy(viewportEnd = 700),
            initial.copy(totalCount = 31),
            initial.copy(firstIndex = 4),
            initial.copy(firstOffset = 13),
        )
        samples.forEach { writer.record(true, 7L, it) }
        assertEquals(samples.size.toLong(), sink.last("chat.list.sample"))
    }

    @Test fun visibleGeometryAndCounterNamesAreBoundedAndUnsampledChangesAreIgnored() {
        val ids = ChatScrollAnonymousIds()
        val sink = RecordingSink()
        val writer = ChatScrollGeometryWriter(ids, sink)
        val rows = (0 until 100).map { ChatScrollRowGeometry("private-$it", it, it * 10, 10) }
        writer.record(true, 7L, geometry(rows))
        val count = sink.counters.size
        assertEquals(CHAT_SCROLL_TRACE_MAX_ROWS, ids.size)
        assertEquals(100L, sink.last("chat.list.visible_count"))
        assertEquals(CHAT_SCROLL_TRACE_MAX_ROWS.toLong(), sink.last("chat.list.sampled_count"))
        assertEquals((100 - CHAT_SCROLL_TRACE_MAX_ROWS).toLong(), sink.last("chat.list.omitted_count"))
        assertEquals(13 + 4 * CHAT_SCROLL_TRACE_MAX_ROWS, sink.counters.size)
        assertTrue(sink.names().all { it.length < 127 && !it.contains("private") })
        val tailOnlyChange = rows.toMutableList().apply {
            this[lastIndex] = last().copy(offset = 2_000)
        }
        writer.record(true, 7L, geometry(tailOnlyChange))
        assertEquals(count, sink.counters.size)
    }

    @Test fun disappearingAnchorsClearAllSlotsInsteadOfLeavingStaleGeometry() {
        val sink = RecordingSink()
        val writer = ChatScrollGeometryWriter(ChatScrollAnonymousIds(), sink)
        writer.record(true, 7L, geometry())
        writer.record(true, 7L, geometry(emptyList()).copy(
            firstIndex = 0, firstOffset = 0, totalCount = 0,
            canScrollForward = false, canScrollBackward = false,
        ))
        repeat(CHAT_SCROLL_TRACE_MAX_ROWS) { slot ->
            assertEquals(0L, sink.last("chat.list.v$slot.id"))
            assertEquals(-1L, sink.last("chat.list.v$slot.index"))
            assertEquals(0L, sink.last("chat.list.v$slot.offset"))
            assertEquals(0L, sink.last("chat.list.v$slot.size"))
        }
        assertEquals(0L, sink.last("chat.list.visible_count"))
        assertEquals(0L, sink.last("chat.list.sampled_count"))
        assertEquals(0L, sink.last("chat.list.omitted_count"))
    }
}
