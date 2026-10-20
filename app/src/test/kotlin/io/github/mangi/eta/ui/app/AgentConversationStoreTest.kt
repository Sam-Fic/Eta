package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UNCATEGORIZED_FOLDER_ID
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        // 模型历史另存在 filesDir 下，不清掉会串到下一条用例。
        java.io.File(context.filesDir, "conversation-history").deleteRecursively()
    }

    @Test fun missingReceiptCannotResurrectAnUnscopedHistoricalBill() {
        val state = AgentChatHomeUiState(
            messages = listOf(AgentMessageUi(
                id = "assistant-1", content = "done", isStreaming = false,
                usage = TokenUsageUi(inputTokens = 126364),
            )),
            history = listOf(AgentModelClient.ConversationMessage("user", "task")),
            input = "", isStreaming = false, thinkingEnabled = false,
            providerId = "p", modelId = "m",
        )
        runBlocking { AgentConversationStore.save(context, "c", mapOf("c" to state), mapOf("c" to "task"), mapOf("c" to 1L)) }
        EtaDatabase.closeForTests()
        val restored = requireNotNull(AgentConversationStore.load(context).conversationsById["c"])
        assertNull(restored.livePromptTokens)
        assertFalse(restored.livePromptIsProjected)
        assertEquals(126364, (restored.messages.single() as AgentMessageUi).usage?.inputTokens)
    }

    @Test fun compactedUnknownSurvivesReopenWithoutAnyVisibleMarker() {
        val compacted = AgentChatHomeUiState(messages = emptyList(),
            history = listOf(AgentModelClient.ConversationMessage("system", "summary")),
            input = "", isStreaming = false, thinkingEnabled = false,
            providerId = "p", modelId = "m", contextHasStarted = true, contextAwaitingReceipt = true)
        runBlocking { AgentConversationStore.save(context, "c", mapOf("c" to compacted), mapOf("c" to "task"), mapOf("c" to 1L)) }
        EtaDatabase.closeForTests()
        val restored = requireNotNull(AgentConversationStore.load(context).conversationsById["c"])
        assertTrue(restored.contextHasStarted)
        assertTrue(restored.contextAwaitingReceipt)
        assertTrue(restored.messages.isEmpty())
        assertNull(restored.livePromptTokens)
    }

    @Test fun trashedConversationSurvivesReopenAndRestoreClearsMarker() {
        val state = AgentChatHomeUiState(messages = emptyList(),
            history = listOf(AgentModelClient.ConversationMessage("user", "task")),
            input = "", isStreaming = false, thinkingEnabled = false,
            providerId = "p", modelId = "m")
        // 回收站往返：软删除的会话内容保留、快照带删除时间，但启动选中跳过它。
        runBlocking {
            AgentConversationStore.save(
                context, "live", mapOf("live" to state, "gone" to state),
                mapOf("live" to "keep", "gone" to "trashed"), mapOf("live" to 1L, "gone" to 2L),
                deletedAt = mapOf("gone" to 99L),
            )
        }
        EtaDatabase.closeForTests()
        val snapshot = AgentConversationStore.load(context)
        assertEquals("live", snapshot.selectedConversationId)
        assertEquals(99L, snapshot.deletedAt.getValue("gone"))
        assertTrue("回收站会话必须留在内存快照里", "gone" in snapshot.conversationsById)
        // 恢复 = 照常保存但不带 deletedAt，标记应被清空。
        runBlocking {
            AgentConversationStore.save(
                context, "live", snapshot.conversationsById, snapshot.titles, snapshot.updatedAt,
                folderIds = snapshot.folderIds, pinnedIds = snapshot.pinnedIds,
                completionMarkerIds = snapshot.completionMarkerIds, folders = snapshot.folders,
            )
        }
        EtaDatabase.closeForTests()
        val restored = AgentConversationStore.load(context)
        assertTrue("恢复后不应残留删除标记", restored.deletedAt.isEmpty())
        assertTrue("gone" in restored.conversationsById)
    }

    @Test fun cloudInputSurvivesDatabaseReopenAndInvalidationStaysEmpty() {
        val original = AgentChatHomeUiState(messages = emptyList(),
            history = listOf(AgentModelClient.ConversationMessage("user", "task")),
            input = "", isStreaming = false, thinkingEnabled = false,
            providerId = "p", modelId = "m", livePromptTokens = 152885)
        runBlocking { AgentConversationStore.save(context, "c", mapOf("c" to original), mapOf("c" to "task"), mapOf("c" to 1L)) }
        EtaDatabase.closeForTests()
        val restored = requireNotNull(AgentConversationStore.load(context).conversationsById["c"])
        assertEquals(152885, restored.livePromptTokens)
        runBlocking { AgentConversationStore.save(context, "c", mapOf("c" to restored.copy(livePromptTokens = null)), mapOf("c" to "task"), mapOf("c" to 1L)) }
        EtaDatabase.closeForTests()
        assertEquals(null, AgentConversationStore.load(context).conversationsById["c"]?.livePromptTokens)
    }

    @Test
    fun saveAndLoadPreservesConversations() {
        val conversation = AgentChatHomeUiState(
            messages = listOf(
                UserMessageUi(
                    id = "user-1",
                    content = "看一下当前屏幕",
                    isEdited = true,
                ),
                ThinkingMessageUi(
                    id = "thinking-1",
                    content = "需要先观察屏幕",
                    isStreaming = false,
                    elapsedSeconds = 3,
                    collapsed = true,
                ),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "执行命令 · Android · root",
                    command = "pm list packages | head",
                    resultSummary = "ok=true, chars=100",
                    imageCount = 1,
                ),
                AgentMessageUi(
                    id = "assistant-1",
                    content = "| 项目 | 内容 |\n| --- | --- |\n| 电量 | 88% |",
                    isStreaming = false,
                    renderMarkdown = true,
                    generatedAtMillis = 1_800_000_000_000L,
                    usage = TokenUsageUi(
                        contextTokens = 100,
                        inputTokens = 30,
                        outputTokens = 40,
                        reasoningTokens = 20,
                        cachedTokens = 10,
                    ),
                ),
            ),
            history = listOf(
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "user",
                    content = "看一下当前屏幕",
                    turnId = "1",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "",
                    reasoningContent = "需要先观察屏幕",
                    toolCallsJson = """[{"id":"toolu_1","type":"function","function":{"name":"observe_screen","arguments":"{}"}}]""",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "tool",
                    content = "{\"ok\":true}",
                    toolCallId = "toolu_1",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "| 项目 | 内容 |\n| --- | --- |\n| 电量 | 88% |",
                ),
            ),
            input = "不应该保存草稿",
            isStreaming = true,
            thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH,
            providerId = "provider-1",
            modelId = "model-1",
        )

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf("conv-1" to conversation),
                titles = mapOf("conv-1" to "屏幕分析"),
                updatedAt = mapOf("conv-1" to 1234L),
            )
        }

        val snapshot = AgentConversationStore.load(context)

        assertEquals("conv-1", snapshot.selectedConversationId)
        assertEquals("屏幕分析", snapshot.titles.getValue("conv-1"))
        assertEquals(1234L, snapshot.updatedAt.getValue("conv-1"))
        val restored = snapshot.conversationsById.getValue("conv-1")
        assertEquals("", restored.input)
        assertFalse(restored.isStreaming)
        assertTrue(restored.thinkingEnabled)
        assertEquals(ReasoningEffort.HIGH, restored.reasoningEffort)
        assertEquals("provider-1", restored.providerId)
        assertEquals("model-1", restored.modelId)
        assertEquals(conversation.messages.drop(1), restored.messages.drop(1))
        assertEquals((conversation.messages.first() as UserMessageUi).content,
            (restored.messages.first() as UserMessageUi).content)
        assertTrue(restored.messages.first().id.startsWith("user-"))
        assertEquals(io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(conversation.history), restored.history)
    }

    @Test
    fun saveAndLoadKeepsDifferentModelsPerConversation() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-a",
                conversationsById = mapOf(
                    "conv-a" to AgentChatHomeUiState(
                        messages = emptyList(),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                        providerId = "provider-a",
                        modelId = "model-a",
                    ),
                    "conv-b" to AgentChatHomeUiState(
                        messages = emptyList(),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                        providerId = "provider-b",
                        modelId = "model-b",
                    ),
                ),
                titles = mapOf("conv-a" to "A", "conv-b" to "B"),
                updatedAt = mapOf("conv-a" to 2L, "conv-b" to 1L),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertEquals("provider-a", snapshot.conversationsById.getValue("conv-a").providerId)
        assertEquals("model-a", snapshot.conversationsById.getValue("conv-a").modelId)
        assertEquals("provider-b", snapshot.conversationsById.getValue("conv-b").providerId)
        assertEquals("model-b", snapshot.conversationsById.getValue("conv-b").modelId)
    }

    @Test
    fun saveAndLoadPreservesSemanticSystemNoticesWithoutTranslatedContent() {
        val notice = SystemNoticeMessageUi(
            id = "assistant-run-1-1",
            code = SystemNoticeCode.RuntimeFailed,
            detail = "upstream timeout",
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-notice",
                conversationsById = mapOf(
                    "conv-notice" to AgentChatHomeUiState(
                        messages = listOf(notice),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-notice" to ""),
                updatedAt = mapOf("conv-notice" to 1L),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertEquals("", snapshot.titles.getValue("conv-notice"))
        val loaded = snapshot.conversationsById.getValue("conv-notice").messages.single()
            as io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
        assertEquals(notice.id, loaded.id)
        assertEquals(notice.detail, loaded.reasonDetail)
        assertEquals(io.github.mangi.eta.ui.model.ErrorReconnectStatus.Failed, loaded.status)
        assertFalse(loaded.isReconnect)
    }

    @Test fun reconnectMarkersSurviveDatabaseReopenWithStableIdsAndFullDiagnostics() {
        val detail = "HTTP 502\n" + "long diagnostic\n".repeat(2_000)
        val statuses = io.github.mangi.eta.ui.model.ErrorReconnectStatus.entries
        val markers = statuses.mapIndexed { index, status ->
            io.github.mangi.eta.ui.model.ErrorReconnectMessageUi(
                id = io.github.mangi.eta.ui.model.errorReconnectMessageId("run", "disconnect-$index"),
                runId = "run", reconnectId = "disconnect-$index", round = 2, status = status,
                elapsedMs = 90_061_123L + index, reasonCode = "MODEL_TIMEOUT", reasonDetail = detail,
            )
        }
        val partial = AgentMessageUi("assistant-run-2-0", "partial answer", isStreaming = true)
        val tool = ToolActivityMessageUi("run-tool-1-call", "read_file", ToolActivityStatusUi.Success, "{}")
        val state = AgentChatHomeUiState(messages = listOf(partial, tool) + markers,
            input = "", isStreaming = true, thinkingEnabled = false)
        runBlocking { AgentConversationStore.save(context, "c", mapOf("c" to state), mapOf("c" to "task"), mapOf("c" to 1L)) }
        EtaDatabase.closeForTests()
        val restored = AgentConversationStore.load(context).conversationsById.getValue("c")
        assertEquals(state.messages.map { it.id }, restored.messages.map { it.id })
        assertEquals("partial answer", (restored.messages.first() as AgentMessageUi).content)
        assertEquals(tool, restored.messages[1])
        val loadedMarkers = restored.messages.filterIsInstance<io.github.mangi.eta.ui.model.ErrorReconnectMessageUi>()
        markers.zip(loadedMarkers).forEach { (before, after) ->
            assertEquals(before.copy(status = if (before.status == io.github.mangi.eta.ui.model.ErrorReconnectStatus.Running)
                io.github.mangi.eta.ui.model.ErrorReconnectStatus.Stopped else before.status), after)
        }
        assertFalse(restored.isStreaming)
        assertTrue(restored.history.none { it.content.contains("MODEL_TIMEOUT") || it.content.contains("long diagnostic") })
    }

    @Test
    fun unknownStoredEffortFallsBackToDefault() {
        runBlocking {
            EtaDatabase.get(context).conversationDao().replaceAll(
                conversations = listOf(
                    ConversationEntity(
                        id = "conv-unknown",
                        title = "Unknown",
                        thinkingEnabled = false,
                        reasoningEffort = "future_effort",
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                ),
                messages = emptyList(),
                state = ConversationStateEntity(selectedConversationId = "conv-unknown"),
            )
        }

        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-unknown")

        assertEquals(ReasoningEffort.DEFAULT, restored.reasoningEffort)
        assertTrue(restored.thinkingEnabled)
    }

    @Test
    fun saveAndLoadPreservesAllConversationsAndMessagesWithoutClipping() {
        val longContent = "x".repeat(20_000)
        val primaryMessages = buildList {
            add(UserMessageUi(id = "conv-0-user-long", content = longContent))
            repeat(130) { index ->
                add(
                    AgentMessageUi(
                        id = "conv-0-assistant-$index",
                        content = "assistant-$index",
                        isStreaming = false,
                    )
                )
            }
        }
        val conversations = buildMap {
            put(
                "conv-0",
                AgentChatHomeUiState(
                    messages = primaryMessages,
                    input = "",
                    isStreaming = false,
                    thinkingEnabled = false,
                )
            )
            repeat(59) { index ->
                val id = "conv-${index + 1}"
                put(
                    id,
                    AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "$id-user", content = "message-$id")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                )
            }
        }
        val titles = conversations.keys.associateWith { id -> "title-$id" }
        val updatedAt = conversations.keys.associateWith { id -> id.removePrefix("conv-").toLong() }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-0",
                conversationsById = conversations,
                titles = titles,
                updatedAt = updatedAt,
            )
        }

        val snapshot = AgentConversationStore.load(context)

        assertEquals(60, snapshot.conversationsById.size)
        val restored = snapshot.conversationsById.getValue("conv-0")
        assertEquals(131, restored.messages.size)
        assertEquals(longContent, (restored.messages.first() as UserMessageUi).content)
        assertEquals("assistant-129", (restored.messages.last() as AgentMessageUi).content)
    }

    @Test
    fun saveBoundsConversationCheckpointWithoutClippingDisplayedMessages() {
        val displayedContent = "展示消息-${"d".repeat(120_000)}"
        val history = buildList {
            repeat(30) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "历史-$index-${"h".repeat(50_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "最新上下文"))
        }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-large",
                conversationsById = mapOf(
                    "conv-large" to AgentChatHomeUiState(
                        messages = listOf(
                            UserMessageUi(id = "user-large", content = displayedContent)
                        ),
                        history = history,
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-large" to "长对话"),
                updatedAt = mapOf("conv-large" to 1L),
            )
        }

        val checkpoint = runBlocking {
            EtaDatabase.get(context)
                .conversationDao()
                .contextCheckpoint("conv-large")!!
        }
        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-large")

        assertTrue(
            checkpoint.historyJson.length <=
                AgentConversationCodec.MAX_CONVERSATION_CHECKPOINT_CHARS
        )
        assertEquals(displayedContent, (restored.messages.single() as UserMessageUi).content)
        // Room 检查点仍受上限约束，但模型历史走单独文件（4MB），重新打开不会退回截短的检查点。
        assertEquals(history.size, restored.history.size)
        assertEquals(history.first().content, restored.history.first().content)
        assertFalse(restored.history.first().content.contains("容量上限已压缩"))
        assertEquals("最新上下文", restored.history.last().content)
    }

    @Test
    fun loadIgnoresLegacyHistoryColumnAndFallsBackToMessageRows() {
        runBlocking {
            val dao = EtaDatabase.get(context).conversationDao()
            dao.insertConversations(
                listOf(
                    ConversationEntity(
                        id = "conv-legacy-large",
                        title = "旧长对话",
                        thinkingEnabled = false,
                        historyJson = "x".repeat(2_500_000),
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                )
            )
            dao.insertMessages(
                listOf(
                    ConversationMessageEntity(
                        id = "legacy-user",
                        conversationId = "conv-legacy-large",
                        sortIndex = 0,
                        type = "user",
                        content = "从消息记录恢复",
                    )
                )
            )
        }

        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-legacy-large")

        assertEquals("从消息记录恢复", restored.history.single().content)
        assertEquals("从消息记录恢复", (restored.messages.single() as UserMessageUi).content)
    }

    @Test
    fun loadingRepairsLegacyMessageIdsAndPersistsThemAcrossReopen() {
        val history = listOf(
            AgentModelClient.ConversationMessage("user", "first task", turnId = "turn-first"),
            AgentModelClient.ConversationMessage("assistant", "first answer", turnId = "turn-first"),
            AgentModelClient.ConversationMessage("user", "second task", turnId = "turn-second"),
        )
        val state = AgentChatHomeUiState(
            messages = listOf(
                UserMessageUi("user-run-old", "first task"),
                AgentMessageUi("assistant-run-old", "first answer"),
                UserMessageUi("user-run-old-supplement-7", "用户补充指令：检查结果"),
                UserMessageUi("user-run-second", "second task"),
            ),
            history = history,
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-identity",
                conversationsById = mapOf("conv-identity" to state),
                titles = mapOf("conv-identity" to "identity"),
                updatedAt = mapOf("conv-identity" to 1L),
            )
        }

        val firstLoad = AgentConversationStore.load(context).conversationsById.getValue("conv-identity")
        assertEquals(
            listOf(
                "user-turn-first",
                "assistant-run-old",
                "user-turn-first-supplement-7",
                "user-turn-second",
            ),
            firstLoad.messages.map { it.id },
        )
        assertEquals(listOf("turn-first", "turn-first", "turn-second"), firstLoad.history.map { it.turnId })

        val dao = EtaDatabase.get(context).conversationDao()
        assertEquals(
            firstLoad.messages.map { it.id },
            runBlocking { dao.messagesForConversation("conv-identity").map { it.id } },
        )
        EtaDatabase.closeForTests()

        val secondLoad = AgentConversationStore.load(context).conversationsById.getValue("conv-identity")
        assertEquals(firstLoad.messages.map { it.id }, secondLoad.messages.map { it.id })
        assertEquals(firstLoad.history.map { it.turnId }, secondLoad.history.map { it.turnId })
    }

    @Test
    fun loadingDoesNotRepairAmbiguousRepeatedUserText() {
        val state = AgentChatHomeUiState(
            messages = listOf(
                UserMessageUi("user-old-one", "repeat"),
                UserMessageUi("user-old-two", "repeat"),
            ),
            history = listOf(
                AgentModelClient.ConversationMessage("user", "repeat", turnId = "turn-one"),
                AgentModelClient.ConversationMessage("user", "repeat", turnId = "turn-two"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-ambiguous",
                conversationsById = mapOf("conv-ambiguous" to state),
                titles = mapOf("conv-ambiguous" to "ambiguous"),
                updatedAt = mapOf("conv-ambiguous" to 1L),
            )
        }

        val loaded = AgentConversationStore.load(context).conversationsById.getValue("conv-ambiguous")
        assertEquals(listOf("user-old-one", "user-old-two"), loaded.messages.map { it.id })
    }

    @Test
    fun loadingAnEmptyDatabaseDoesNotCreateAPlaceholderRecord() {
        val snapshot = AgentConversationStore.load(context)

        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }

    @Test
    fun explicitNewConversationsExistBeforeSendingAndSurviveSwitching() = withNavigationState { state ->
        assertNull(state.conversationPaneState.selectedConversationId)
        assertTrue(state.conversationPaneState.conversations.isEmpty())

        state.createConversation()
        val first = requireNotNull(state.conversationPaneState.selectedConversationId)
        assertEquals(first, state.conversationPaneState.conversations.single().id)
        assertTrue(state.homeState.messages.isEmpty())
        state.currentDraftField().setTextAndPlaceCursorAtEnd("unsent first draft")

        state.createConversation()
        val second = requireNotNull(state.conversationPaneState.selectedConversationId)
        assertTrue(first != second)
        assertEquals(setOf(first, second), state.conversationPaneState.conversations.map { it.id }.toSet())
        assertEquals("", state.currentDraftField().text.toString())
        assertTrue(state.homeState.messages.isEmpty())

        state.selectConversation(first)
        assertEquals(first, state.conversationPaneState.selectedConversationId)
        assertEquals("unsent first draft", state.currentDraftField().text.toString())
        assertTrue(state.homeState.messages.isEmpty())
        state.selectConversation(second)
        assertEquals(second, state.conversationPaneState.selectedConversationId)
        assertEquals("", state.currentDraftField().text.toString())
        assertEquals(2, state.conversationPaneState.conversations.size)
    }

    @Test
    fun explicitNewConversationBelongsToTheSelectedFolderImmediately() = withNavigationState { state ->
        state.createFolder("Project")
        val folder = state.conversationPaneState.folders.single().id
        state.createConversation()
        val id = requireNotNull(state.conversationPaneState.selectedConversationId)
        assertEquals(folder, state.conversationPaneState.conversations.single().folderId)
        // 语义与生产 UI 对齐：null 是包含全部对话的「聊天」tab，
        // 表示「未分类」的哨兵值是 UNCATEGORIZED_FOLDER_ID，只有它会把已归档对话筛掉。
        state.selectFolder(UNCATEGORIZED_FOLDER_ID)
        assertTrue(state.conversationPaneState.conversations.isEmpty())
        state.selectFolder(folder)
        assertEquals(id, state.conversationPaneState.conversations.single().id)
    }

    @Test
    fun deletingLastExplicitConversationReturnsToAnUnstoredPlaceholder() = withNavigationState { state ->
        state.createConversation()
        val id = requireNotNull(state.conversationPaneState.selectedConversationId)
        state.deleteConversation(id)
        assertNull(state.conversationPaneState.selectedConversationId)
        assertTrue(state.conversationPaneState.conversations.isEmpty())
        assertTrue(state.homeState.messages.isEmpty())
    }

    @Test
    fun explicitNewConversationDoesNotStealTheStartupDraft() = withNavigationState { state ->
        state.currentDraftField().setTextAndPlaceCursorAtEnd("startup draft")
        state.createConversation()
        val id = requireNotNull(state.conversationPaneState.selectedConversationId)
        assertEquals("", state.currentDraftField().text.toString())
        state.deleteConversation(id)
        assertEquals("startup draft", state.currentDraftField().text.toString())
    }

    @Test
    fun emptyUntitledConversationsSurviveSavingAnotherSelectionAndReopeningDatabase() {
        val empty = AgentChatHomeUiState(
            messages = emptyList(), history = emptyList(), input = "",
            isStreaming = false, thinkingEnabled = false,
        )
        val conversations = mapOf("empty-first" to empty, "empty-second" to empty.copy())
        runBlocking {
            AgentConversationStore.save(context, "empty-second", conversations, emptyMap(),
                mapOf("empty-first" to 1L, "empty-second" to 2L))
            AgentConversationStore.save(context, "empty-first", conversations, emptyMap(),
                mapOf("empty-first" to 1L, "empty-second" to 2L))
        }
        EtaDatabase.closeForTests()
        val restored = AgentConversationStore.load(context)
        assertEquals(conversations.keys, restored.conversationsById.keys)
        assertEquals("empty-first", restored.selectedConversationId)
        restored.conversationsById.values.forEach {
            assertTrue(it.messages.isEmpty())
            assertTrue(it.history.isEmpty())
        }
    }

    private fun withNavigationState(block: (AgentAppState) -> Unit) {
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        context.getSharedPreferences("conversation_input_drafts", Context.MODE_PRIVATE).edit().clear().commit()
        // These tests exercise synchronous navigation, not asynchronous persistence/runtime jobs.
        // The separate Store round-trip test covers empty conversation persistence.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { it.cancel() }
        try { block(AgentAppState(context, scope)) }
        finally { scope.cancel() }
    }

    @Test
    fun savingEmptySnapshotClearsPreviouslyPersistedConversations() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf(
                    "conv-1" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-1", content = "hello")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-1" to "hello"),
                updatedAt = mapOf("conv-1" to 1L),
            )
            AgentConversationStore.save(
                context = context,
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }

    @Test
    fun saveAndLoadPreservesFoldersPinAndFolderAssignment() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf(
                    "conv-1" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-1", content = "hello")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                    "conv-2" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-2", content = "world")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-1" to "hello", "conv-2" to "world"),
                updatedAt = mapOf("conv-1" to 2L, "conv-2" to 1L),
                folderIds = mapOf("conv-1" to "folder-work"),
                pinnedIds = setOf("conv-2"),
                completionMarkerIds = setOf("conv-2"),
                folders = listOf(
                    ConversationFolderUi(id = "folder-work", name = "工作", sortIndex = 0),
                ),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertEquals("folder-work", snapshot.folderIds["conv-1"])
        assertEquals(null, snapshot.folderIds["conv-2"])
        assertEquals(setOf("conv-2"), snapshot.pinnedIds)
        assertEquals(setOf("conv-2"), snapshot.completionMarkerIds)
        assertEquals(listOf("folder-work"), snapshot.folders.map { it.id })
        assertEquals("工作", snapshot.folders.single().name)
    }

    @Test
    fun saveAndLoadPreservesContextCompactedMarker() {
        val marker = ContextCompactedMessageUi(
            id = "compacted-1",
            compactedCount = 6,
            summary = "用户要查 Actions，已经推送成功。",
            compressorLabel = "魚 · grok-4.6",
            baselineTokens = 1800,
            resumeRound = 2,
            preservedUsage = ConversationTokenUsageUi(
                inputTokens = 2200,
                outputTokens = 90,
                cachedTokens = 700,
            ),
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-compact",
                conversationsById = mapOf(
                    "conv-compact" to AgentChatHomeUiState(
                        messages = listOf(
                            UserMessageUi(id = "u1", content = "旧消息"),
                            marker,
                            UserMessageUi(id = "u2", content = "继续"),
                        ),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                        history = listOf(
                            io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                                role = "user", content = "旧消息", turnId = "1",
                            ),
                            io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                                role = "user", content = "继续", turnId = "2",
                            ),
                        ),
                    ),
                ),
                titles = mapOf("conv-compact" to "压缩"),
                updatedAt = mapOf("conv-compact" to 2L),
            )
        }
        val restored = AgentConversationStore.load(context)
            .conversationsById.getValue("conv-compact").messages
        assertEquals(listOf("旧消息", "继续"), restored.filterIsInstance<UserMessageUi>().map { it.content })
        assertEquals("compacted-1", restored[1].id)
        val loaded = restored[1] as ContextCompactedMessageUi
        assertEquals(6, loaded.compactedCount)
        assertEquals("用户要查 Actions，已经推送成功。", loaded.summary)
        assertEquals("魚 · grok-4.6", loaded.compressorLabel)
        assertEquals(1800, loaded.baselineTokens)
        assertEquals(2, loaded.resumeRound)
        assertEquals(2200L, loaded.preservedUsage.inputTokens)
        assertEquals(90L, loaded.preservedUsage.outputTokens)
        assertEquals(700L, loaded.preservedUsage.cachedTokens)
    }

    @Test
    fun saveAndLoadPreservesAssistantBinding() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-assistant",
                conversationsById = mapOf(
                    "conv-assistant" to AgentChatHomeUiState(
                        messages = emptyList(),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                        assistantId = "assistant-bound",
                    ),
                ),
                titles = mapOf("conv-assistant" to "Bound"),
                updatedAt = mapOf("conv-assistant" to 1L),
            )
        }
        val restored = AgentConversationStore.load(context)
        assertEquals("assistant-bound", restored.conversationsById.getValue("conv-assistant").assistantId)
    }

}
