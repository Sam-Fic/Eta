package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.UserMessageUi

/** 以用户轮次为边界同步裁剪展示消息与模型上下文。 */
internal object AgentConversationRevisionReducer {
    data class Boundary(
        val userMessage: UserMessageUi,
        val userMessageIndex: Int,
        val historyPrefix: List<AgentModelClient.ConversationMessage>,
        val journalPrefix: List<AgentModelClient.ConversationMessage> = historyPrefix,
        val laterTurnCount: Int,
        val contextWasCompacted: Boolean,
    )

    fun boundary(state: AgentChatUiState, targetMessageId: String): Boundary? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val userMessageIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index] is UserMessageUi
        } ?: return null
        val userMessage = state.messages[userMessageIndex] as UserMessageUi
        val userMessageIndices = state.messages.indices.filter { state.messages[it] is UserMessageUi }
        val targetUserOrdinal = userMessageIndices.indexOf(userMessageIndex)
        if (targetUserOrdinal < 0) return null

        val source = state.journal.ifEmpty { state.history }
        val historyUserIndices = source.indices.filter { source[it].role == "user" }
        // 旧版本可能已经丢失前缀；只能对已有记录做尾部对齐，不能伪造恢复。
        val retainedUserOrdinal = historyUserIndices.size - (userMessageIndices.size - targetUserOrdinal)
        val historyIndex = historyUserIndices.getOrNull(retainedUserOrdinal)
        val prefix = historyIndex?.let(source::take).orEmpty()

        // 优先按 messageId 在 journal 中精确定位截断点，消除「UI/journal 条数差值」在巨型会话下的漂移
        // （真机实证：5050 条会话压缩后 UI 与 journal 条数不等，差值法算偏导致整段历史带出、CONTEXT_OVERFLOW）。
        // 新消息进入 journal 时已带 messageId（_eta_message_id）；旧数据可能无 ID → 退化到原差值逻辑。
        val idResolvedPrefix: List<AgentModelClient.ConversationMessage>? = targetMessageId
            .takeIf { it.isNotBlank() }
            ?.let { targetId ->
                // 从编辑点往前找最近一条、其 UI id 能在 journal source 中按 messageId 对上用户消息，
                // 以该消息在 source 中的 index 为截断边界（不含该消息本身之后的所有消息）。
                (targetUserOrdinal downTo 0).asSequence()
                    .mapNotNull { ordinal -> userMessageIndices.getOrNull(ordinal)?.let { state.messages[it].id } }
                    .mapNotNull { uiId ->
                        historyUserIndices.lastOrNull { source[it].messageId == uiId }
                    }
                    .firstOrNull()
                    ?.let { matchedIndex -> source.take(matchedIndex) }
            }
        val effectivePrefix = idResolvedPrefix ?: prefix
        val targetTurn = effectivePrefix.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 } + 1
        val invalidatesSummary = effectivePrefix.any { it.contextSummary && it.summaryThroughUserTurn >= targetTurn }
        val compacted = historyIndex == null || invalidatesSummary

        return Boundary(
            userMessage = userMessage,
            userMessageIndex = userMessageIndex,
            historyPrefix = effectivePrefix.filterNot { it.contextSummary && it.summaryThroughUserTurn >= targetTurn },
            laterTurnCount = userMessageIndices.size - targetUserOrdinal - 1,
            contextWasCompacted = compacted,
        )
    }

    fun deleteFromTurn(state: AgentChatUiState, targetMessageId: String): AgentChatUiState? {
        val boundary = boundary(state, targetMessageId) ?: return null
        return state.copy(
            messages = state.messages.take(boundary.userMessageIndex),
            history = boundary.historyPrefix,
            journal = boundary.journalPrefix,
            messageEdit = null,
        )
    }

    fun visibleMessagesForEdit(
        messages: List<AgentChatMessageUi>,
        targetMessageId: String?,
    ): List<AgentChatMessageUi> {
        if (targetMessageId == null) return messages
        val targetIndex = messages.indexOfFirst { it.id == targetMessageId }
        return if (targetIndex < 0) messages else messages.take(targetIndex + 1)
    }
}
