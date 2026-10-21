package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentIncrementalList
import io.github.mangi.eta.ui.model.incrementalSnapshot
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isResumeAfterCompress
import io.github.mangi.eta.ui.model.isSteerSupplement

/** Flat lazy-list rows: expanding a group must not eagerly compose every detail. */
internal sealed interface AgentTimelineRow {
    val key: String
    data class Message(val message: AgentChatMessageUi) : AgentTimelineRow {
        override val key: String get() = message.id
    }
    data class WorkHeader(
        val group: AgentTimelineEntry.WorkProcess,
        val expanded: Boolean,
        /** 本轮已出回答，被「回答后整组收起」策略收起来；组头据此不再报「正在处理」。 */
        val folded: Boolean = false,
    ) : AgentTimelineRow {
        override val key: String get() = group.key
    }
    data class WorkStep(
        val groupKey: String,
        val message: AgentChatMessageUi,
        val isFirst: Boolean,
        val isLast: Boolean,
        val expanded: Boolean = true,
    ) : AgentTimelineRow {
        override val key: String get() = "work-step:${message.id}"
    }
}

internal fun List<AgentTimelineEntry>.toLazyTimelineRows(
    expandedOverrides: Map<String, Boolean>,
    isStreaming: Boolean,
    retainedSteps: Map<String, Set<String>> = emptyMap(),
    foldedWorkKeys: Set<String> = emptySet(),
): List<AgentTimelineRow> = buildList {
    val trailingWorkKey = (this@toLazyTimelineRows.lastOrNull() as? AgentTimelineEntry.WorkProcess)?.key
    this@toLazyTimelineRows.forEach { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> add(AgentTimelineRow.Message(entry.message))
            is AgentTimelineEntry.WorkProcess -> addWorkGroupRows(
                entry,
                trailingWorkKey,
                expandedOverrides,
                isStreaming,
                retainedSteps,
                folded = entry.key in foldedWorkKeys,
            )
        }
    }
}

/**
 * 「本轮已经给出正文回答 ⇒ 回答之前的思考/工具步骤整组收起」的分组键集合。
 *
 * 判定只看顺序：一个工作过程组，只要同一轮里它后面出现过正文（非空的 [AgentMessageUi]），就收进
 * 「已完成 N 个步骤」。正在跑的那一组此刻仍是「本组之后还没有回答」，因此照常摊开跟随进度，直到
 * 回答开始输出才收 —— 这正是想要的「忙完了，只留最终答案」。
 *
 * 轮次边界与 [AgentTurnFooterProjection] 一致：真实用户消息开新轮，steer 补充与压缩后的 resume
 * 只是续跑，不收边界。重连/中断后步骤永远停在 Running 的那一组也在这里被收掉，否则它会一直摊开。
 * 用户手动点过的组（[AgentChatBody] 里的 overrides）优先级始终高于本策略。
 */
internal fun List<AgentTimelineEntry>.autoFoldedWorkKeys(enabled: Boolean): Set<String> {
    if (!enabled) return emptySet()
    var pending: MutableList<String>? = null
    var folded: MutableSet<String>? = null
    forEach { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> {
                val message = entry.message
                when {
                    message is UserMessageUi ->
                        if (!message.isSteerSupplement() && !message.isResumeAfterCompress()) pending = null
                    message is AgentMessageUi && message.content.isNotBlank() -> {
                        pending?.let { keys ->
                            if (folded == null) folded = LinkedHashSet()
                            folded?.addAll(keys)
                        }
                        pending = null
                    }
                    else -> Unit
                }
            }
            is AgentTimelineEntry.WorkProcess ->
                (pending ?: ArrayList<String>().also { pending = it }) += entry.key
        }
    }
    return folded.orEmpty()
}

/** One group's header and steps; shared by the full projection and the row cache. */
private fun MutableList<AgentTimelineRow>.addWorkGroupRows(
    entry: AgentTimelineEntry.WorkProcess,
    trailingWorkKey: String?,
    expandedOverrides: Map<String, Boolean>,
    isStreaming: Boolean,
    retainedSteps: Map<String, Set<String>>,
    folded: Boolean = false,
) {
    val running = entry.messages.any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    // 手动点过 > 本轮已出回答的整组收起 > 运行中/尾部跟随。
    val expanded = expandedOverrides[entry.key] ?: (!folded && (running || (isStreaming && entry.key == trailingWorkKey)))
    add(AgentTimelineRow.WorkHeader(entry, expanded, folded))
    // During exit only retained rows are projected. A newly appended
    // hidden step (or a deleted old tail) must not steal the card bottom.
    val projectedMessages = if (expanded) entry.messages else entry.messages.filter { message ->
        "work-step:${message.id}" in retainedSteps[entry.key].orEmpty()
    }
    projectedMessages.forEachIndexed { index, message ->
        add(AgentTimelineRow.WorkStep(entry.key, message,
            isFirst = message.id == entry.messages.firstOrNull()?.id,
            isLast = index == projectedMessages.lastIndex,
            expanded = expanded))
    }
}

/** The streaming flag only affects the un-overridden trailing work group's default. */
internal fun List<AgentTimelineEntry>.streamingWorkDefault(
    expandedOverrides: Map<String, Boolean>,
    isStreaming: Boolean,
): Boolean = isStreaming && (lastOrNull() as? AgentTimelineEntry.WorkProcess)?.let {
    it.key !in expandedOverrides
} == true

/**
 * Keep the exact row policy and work groups from the full projection. Assistant
 * rows and same-key/step-ID work payloads can be patched at their existing slots.
 * Changes to policy, structure or a group's projected row count fall back.
 */
internal class AgentTimelineRowsCache(
    private val fullProjection: (
        List<AgentTimelineEntry>, Map<String, Boolean>, Boolean, Map<String, Set<String>>, Set<String>,
    ) -> List<AgentTimelineRow> = { entries, overrides, streaming, retained, folded ->
        entries.toLazyTimelineRows(overrides, streaming, retained, folded)
    },
) {
    private var source: List<AgentTimelineEntry>? = null
    private var expanded: Map<String, Boolean> = emptyMap()
    private var streaming = false
    private var retained: Map<String, Set<String>> = emptyMap()
    private var folded: Set<String> = emptySet()
    private var rows: List<AgentTimelineRow> = emptyList()
    private var entryToRow: IntArray? = null
    private var groupRowStart: IntArray? = null

    fun project(
        entries: List<AgentTimelineEntry>,
        expandedOverrides: Map<String, Boolean>,
        isStreaming: Boolean,
        retainedSteps: Map<String, Set<String>> = emptyMap(),
        foldedWorkKeys: Set<String> = emptySet(),
    ): List<AgentTimelineRow> {
        val previous = source
        val mapping = entryToRow
        if (previous != null && mapping != null && groupRowStart != null && previous.size == entries.size &&
            expanded == expandedOverrides && streaming == isStreaming && retained == retainedSteps &&
            folded == foldedWorkKeys
        ) {
            val changed = (entries as? AgentIncrementalList<AgentTimelineEntry>)?.singleReplacementFrom(previous)
            if (changed != null) {
                val old = (previous[changed] as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                val current = (entries[changed] as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                if (old != null && current != null && old.id == current.id && old.isStreaming && current.isStreaming &&
                    current.content.startsWith(old.content) && mapping[changed] >= 0) {
                    rows = rows.incrementalSnapshot().replacing(mapping[changed], AgentTimelineRow.Message(current))
                    source = entries
                    return rows
                }
                patchWorkGroup(previous, entries, changed)?.let { patched ->
                    rows = patched
                    source = entries
                    return rows
                }
            }
            if (changed == null) {
                val changedIndices = ArrayList<Int>(1)
                var changedWorkGroup = -1
                var compatible = true
                for (index in entries.indices) {
                    val old = previous[index]
                    val current = entries[index]
                    if (old === current || old.hasSamePayloadReferences(current)) continue
                    if (old is AgentTimelineEntry.WorkProcess && current is AgentTimelineEntry.WorkProcess && changedWorkGroup < 0) {
                        changedWorkGroup = index
                        continue
                    }
                    val oldMessage = (old as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                    val newMessage = (current as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                    if (oldMessage == null || newMessage == null || oldMessage.id != newMessage.id || mapping[index] < 0) {
                        compatible = false
                        break
                    }
                    changedIndices += index
                }
                // A full/unhinted entry snapshot can still be one ordinary work
                // payload delta. Certify the unchanged entries before patching it.
                if (compatible && changedWorkGroup >= 0 && changedIndices.isEmpty()) {
                    patchWorkGroup(previous, entries, changedWorkGroup)?.let { patched ->
                        rows = patched
                        source = entries.incrementalSnapshot()
                        return rows
                    }
                }
                if (compatible && changedWorkGroup < 0) {
                    if (changedIndices.isEmpty()) {
                        source = entries.incrementalSnapshot()
                        return rows
                    }
                    val updated = rows.toMutableList()
                    changedIndices.forEach { index ->
                        updated[mapping[index]] = AgentTimelineRow.Message((entries[index] as AgentTimelineEntry.Message).message)
                    }
                    source = if (entries is AgentIncrementalList<AgentTimelineEntry>) entries else entries.toList()
                    rows = updated.incrementalSnapshot()
                    return rows
                }
            }
        }
        val input = entries.incrementalSnapshot()
        val result = fullProjection(input, expandedOverrides, isStreaming, retainedSteps, foldedWorkKeys).incrementalSnapshot()
        source = input
        expanded = expandedOverrides.toMap()
        streaming = isStreaming
        retained = retainedSteps.mapValues { it.value.toSet() }
        folded = foldedWorkKeys.toSet()
        rows = result
        entryToRow = mapAssistantRows(input, result)
        groupRowStart = if (entryToRow == null) null else mapGroupRows(input, result)
        return result
    }

    // Full timeline projection recreates entry wrappers, even when their payload
    // objects are unchanged. Compare only references, never historical text bodies.
    private fun AgentTimelineEntry.hasSamePayloadReferences(other: AgentTimelineEntry): Boolean = when (this) {
        is AgentTimelineEntry.Message -> other is AgentTimelineEntry.Message && message === other.message
        is AgentTimelineEntry.WorkProcess -> other is AgentTimelineEntry.WorkProcess && key == other.key &&
            messages.size == other.messages.size && messages.indices.all { messages[it] === other.messages[it] }
    }

    /**
     * A work group whose step IDs are unchanged (thinking text grows, a tool finishes)
     * re-projects only its own header and steps. Group key, trailing key and policies
     * are unchanged, so the result equals the full projection when the row count holds.
     */
    private fun patchWorkGroup(
        previous: List<AgentTimelineEntry>,
        entries: List<AgentTimelineEntry>,
        changed: Int,
    ): List<AgentTimelineRow>? {
        val starts = groupRowStart ?: return null
        val old = previous[changed] as? AgentTimelineEntry.WorkProcess ?: return null
        val current = entries[changed] as? AgentTimelineEntry.WorkProcess ?: return null
        val start = starts[changed]
        if (start < 0 || old.key != current.key || old.messages.size != current.messages.size) return null
        for (i in old.messages.indices) if (old.messages[i].id != current.messages[i].id) return null
        val trailingWorkKey = (entries.lastOrNull() as? AgentTimelineEntry.WorkProcess)?.key
        val oldCount = (if (changed + 1 < starts.size) starts[changed + 1] else rows.size) - start
        val next = ArrayList<AgentTimelineRow>(oldCount)
        next.addWorkGroupRows(current, trailingWorkKey, expanded, streaming, retained, current.key in folded)
        if (next.size != oldCount) return null
        var updated = rows.incrementalSnapshot()
        next.forEachIndexed { offset, row ->
            val previousRow = updated[start + offset]
            if (row.key != previousRow.key) return null
            if (row is AgentTimelineRow.WorkStep && previousRow is AgentTimelineRow.WorkStep &&
                row.message === previousRow.message && row.groupKey == previousRow.groupKey &&
                row.isFirst == previousRow.isFirst && row.isLast == previousRow.isLast &&
                row.expanded == previousRow.expanded
            ) return@forEachIndexed
            updated = updated.replacing(start + offset, row)
        }
        return updated.withoutReplacementHint()
    }

    /** First row of every entry; null when rows are not one contiguous run per entry. */
    private fun mapGroupRows(entries: List<AgentTimelineEntry>, rows: List<AgentTimelineRow>): IntArray? {
        val starts = IntArray(entries.size)
        var row = 0
        entries.forEachIndexed { index, entry ->
            starts[index] = row
            when (entry) {
                is AgentTimelineEntry.Message -> {
                    if ((rows.getOrNull(row) as? AgentTimelineRow.Message)?.message !== entry.message) return null
                    row++
                }
                is AgentTimelineEntry.WorkProcess -> {
                    if ((rows.getOrNull(row) as? AgentTimelineRow.WorkHeader)?.group !== entry) return null
                    row++
                    while ((rows.getOrNull(row) as? AgentTimelineRow.WorkStep)?.groupKey == entry.key) row++
                }
            }
        }
        return if (row == rows.size) starts else null
    }

    private fun mapAssistantRows(entries: List<AgentTimelineEntry>, rows: List<AgentTimelineRow>): IntArray? {
        val seen = HashSet<String>()
        val assistantSlots = HashMap<String, Int>()
        entries.forEachIndexed { index, entry ->
            when (entry) {
                is AgentTimelineEntry.Message -> {
                    if (!seen.add(entry.message.id)) return null
                    if (entry.message is AgentMessageUi) assistantSlots[entry.message.id] = index
                }
                is AgentTimelineEntry.WorkProcess -> entry.messages.forEach {
                    if (!seen.add(it.id)) return null
                }
            }
        }
        // Duplicate lazy keys must not become an ambiguous mapping either.
        if (rows.map { it.key }.toSet().size != rows.size) return null
        val mapping = IntArray(entries.size) { -1 }
        rows.forEachIndexed { rowIndex, row ->
            val message = (row as? AgentTimelineRow.Message)?.message as? AgentMessageUi
                ?: return@forEachIndexed
            val entryIndex = assistantSlots[message.id] ?: return null
            if ((entries[entryIndex] as AgentTimelineEntry.Message).message !== message || mapping[entryIndex] >= 0) return null
            mapping[entryIndex] = rowIndex
        }
        assistantSlots.values.forEach { if (mapping[it] < 0) return null }
        return mapping
    }
}

internal fun List<AgentTimelineRow>.lazyUserMessageIndices(): List<Int> = mapIndexedNotNull { index, row ->
    val user = (row as? AgentTimelineRow.Message)?.message as? UserMessageUi
    index.takeIf { user != null && !user.isResumeAfterCompress() }
}

/**
 * 渲染层连续 User 气泡分组：两个相邻 User 气泡之间没有任何行（工作过程 / 正文 / 其它消息）即同组。
 *
 * 组边界只由相邻行决定：流式内容到达只会改变「自身→下一条」一侧，组不随内容跳变。
 */
internal fun List<AgentTimelineRow>.userBubblePosition(index: Int): UserBubblePosition {
    fun isUserBubble(row: AgentTimelineRow?): Boolean =
        (row as? AgentTimelineRow.Message)?.message is UserMessageUi
    val stickyUp = index > 0 && isUserBubble(getOrNull(index - 1))
    val stickyDown = index < lastIndex && isUserBubble(getOrNull(index + 1))
    return when {
        stickyUp && stickyDown -> UserBubblePosition.GroupMid
        stickyUp -> UserBubblePosition.GroupLast
        stickyDown -> UserBubblePosition.GroupFirst
        else -> UserBubblePosition.Single
    }
}

internal fun AgentTimelineRow.containsMessageId(id: String): Boolean = when (this) {
    is AgentTimelineRow.Message -> message.id == id
    is AgentTimelineRow.WorkHeader -> key == id || (!expanded && group.messages.any { it.id == id })
    is AgentTimelineRow.WorkStep -> message.id == id
}

/** Terminal notices must not hide an assistant that is still revealing text. */
internal fun hasPendingAssistantReveal(
    messages: List<AgentChatMessageUi>,
    isPending: (AgentMessageUi) -> Boolean,
): Boolean = (messages.lastOrNull { it is AgentMessageUi && it.content.isNotBlank() }
    as? AgentMessageUi)?.let(isPending) == true
