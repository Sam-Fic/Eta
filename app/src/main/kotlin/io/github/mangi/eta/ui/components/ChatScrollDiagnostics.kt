package io.github.mangi.eta.ui.components

import android.os.Trace
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

internal const val CHAT_SCROLL_TRACE_POLL_MS = 500L
internal const val CHAT_SCROLL_TRACE_MAX_ROWS = 8
internal const val CHAT_SCROLL_TRACE_MAX_IDENTITIES = 256

/**
 * Polls the platform trace gate, not frames. While paused there is no polling;
 * while disabled the unchanged Boolean does not request recompositions. Call
 * once at the list owner and pass the result to the list and row probes.
 * Platform trace transitions can take one polling interval to reach this gate.
 */
@Composable
internal fun rememberChatScrollTraceEnabled(): Boolean {
    val allowed = StreamDiagnosticControl.allowed.also { StreamDiagnosticControl.changes.intValue }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val enabled = remember(lifecycle) { mutableStateOf(false) }
    LaunchedEffect(lifecycle, allowed) {
        if (!allowed) { enabled.value = false; return@LaunchedEffect }
        try {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    while (true) {
                        enabled.value = StreamDiagnosticControl.allowed && Trace.isEnabled()
                        delay(CHAT_SCROLL_TRACE_POLL_MS)
                    }
                } finally {
                    enabled.value = false
                }
            }
        } finally {
            enabled.value = false
        }
    }
    return enabled.value
}

/**
 * Independent of streaming, FrameMetrics and Looper diagnostics. Exactly one
 * list-level snapshotFlow exists while enabled and RESUMED. It reads at most
 * eight visible geometries; no row collector, frame clock or rendering modifier.
 *
 * v0/v1 are the first two entries in visibleItemsInfo (including sticky items,
 * if any), NOT a claim that either entry is the state's firstVisibleItemIndex.
 * Their index/offset/size/id plus the state anchor and viewport distinguish
 * layout-height changes from translation. No derived scroll delta is emitted.
 * The returned plain object shares this list instance with owner point markers;
 * it is not Compose State and does not expose geometry to the content.
 */
@Composable
internal fun ChatScrollMonitor(state: LazyListState, enabled: Boolean): ChatScrollListTraceState {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val trace = remember {
        ChatScrollListTraceState(chatScrollTraceInstances.incrementAndGet(), AndroidChatScrollTraceSink)
    }
    val instance = trace.instance
    LaunchedEffect(state, enabled, lifecycle) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val writer = ChatScrollGeometryWriter(chatScrollTraceIds, AndroidChatScrollTraceSink)
            AndroidChatScrollTraceSink.section("chat.list.attach i=$instance")
            AndroidChatScrollTraceSink.counter("chat.list.active", 1L)
            try {
                snapshotFlow {
                    val layout = state.layoutInfo
                    ChatScrollGeometry(
                        firstIndex = state.firstVisibleItemIndex,
                        firstOffset = state.firstVisibleItemScrollOffset,
                        viewportStart = layout.viewportStartOffset,
                        viewportEnd = layout.viewportEndOffset,
                        totalCount = layout.totalItemsCount,
                        canScrollForward = state.canScrollForward,
                        canScrollBackward = state.canScrollBackward,
                        isScrollInProgress = state.isScrollInProgress,
                        visibleCount = layout.visibleItemsInfo.size,
                        rows = layout.visibleItemsInfo.take(CHAT_SCROLL_TRACE_MAX_ROWS).map {
                            ChatScrollRowGeometry(it.key, it.index, it.offset, it.size)
                        },
                    )
                }.collect { writer.record(enabled = true, instance = instance, geometry = it) }
            } finally {
                AndroidChatScrollTraceSink.counter("chat.list.active", 0L)
                AndroidChatScrollTraceSink.section("chat.list.detach i=$instance")
            }
        }
    }
    return trace
}

/**
 * Call directly from AgentConversationMessages, not from a child composable.
 * execute is a point at an actual owner-body invocation, not body duration or
 * compiler-generated composable tracing. commit is a SideEffect point after a
 * successful owner invocation; neither marker measures composition/render time.
 */
internal fun traceChatListOwnerExecution(trace: ChatScrollListTraceState, enabled: Boolean) {
    trace.execute(StreamDiagnosticControl.allowed && (enabled || Trace.isEnabled()))
}

/** Call from the owner's SideEffect with its already-read Boolean values. */
internal fun traceChatListOwnerCommit(
    trace: ChatScrollListTraceState,
    enabled: Boolean,
    isUserDragging: Boolean,
    isUserScrolling: Boolean,
    isBottomSettling: Boolean,
    keepBottomAnchored: Boolean,
    shouldClipTail: Boolean,
    shouldFollowBottom: Boolean,
    navigationActive: Boolean,
) {
    trace.commit(
        StreamDiagnosticControl.allowed && (enabled || Trace.isEnabled()),
        isUserDragging, isUserScrolling, isBottomSettling, keepBottomAnchored,
        shouldClipTail, shouldFollowBottom, navigationActive,
    )
}

/**
 * Plain remembered bookkeeping, never snapshot State. All output is anonymous
 * list instance + Booleans. A disabled commit resets the baseline so a newly
 * enabled capture receives an initial state; unchanged enabled commits emit no
 * state marker. No collector, clock, content key or text is required.
 * State labels map to the commit arguments: drag/scroll/settle/anchor/clip/follow/nav.
 */
internal class ChatScrollListTraceState(
    val instance: Long,
    private val sink: ChatScrollTraceSink,
) {
    private var previousFlags: Int? = null

    fun execute(enabled: Boolean) {
        if (!enabled) return
        sink.section("chat.list.owner.execute i=$instance")
    }

    fun commit(
        enabled: Boolean,
        isUserDragging: Boolean,
        isUserScrolling: Boolean,
        isBottomSettling: Boolean,
        keepBottomAnchored: Boolean,
        shouldClipTail: Boolean,
        shouldFollowBottom: Boolean,
        navigationActive: Boolean,
    ) {
        if (!enabled) {
            previousFlags = null
            return
        }
        sink.section("chat.list.owner.commit i=$instance")
        val drag = if (isUserDragging) 1 else 0
        val scroll = if (isUserScrolling) 1 else 0
        val settle = if (isBottomSettling) 1 else 0
        val anchor = if (keepBottomAnchored) 1 else 0
        val clip = if (shouldClipTail) 1 else 0
        val follow = if (shouldFollowBottom) 1 else 0
        val nav = if (navigationActive) 1 else 0
        val flags = drag or (scroll shl 1) or (settle shl 2) or (anchor shl 3) or
            (clip shl 4) or (follow shl 5) or (nav shl 6)
        if (previousFlags == flags) return
        previousFlags = flags
        sink.section(
            "chat.list.state i=$instance drag=$drag scroll=$scroll settle=$settle " +
                "anchor=$anchor clip=$clip follow=$follow nav=$nav",
        )
    }
}

/**
 * Keep this call unconditional in the keyed row, outside any enabled branch.
 * Neither remember nor DisposableEffect is keyed on the diagnostic gate.
 * The probe adds no rendering container and cannot recreate the actual content.
 *
 * start = first committed invocation of a new remembered probe, observed live;
 * attach = tracing became enabled on a probe that had already committed;
 * commit = a SideEffect commit of this probe's invocation, NOT a render timing;
 * dispose = disposal of the remembered probe while last observed enabled.
 * Lazy-list reuse or retention can keep an instance alive: identity r and
 * remembered instance i are deliberately separate. Skipped parent content has
 * no invocation, so commits are not a count of all layout/draw work.
 */
@Composable
@NonSkippableComposable
internal fun ChatRowTrace(rowKey: String, rowType: String, enabled: Boolean) {
    val instance = remember {
        ChatScrollRowTraceState(
            chatScrollTraceInstances.incrementAndGet(),
            chatScrollTraceIds,
            AndroidChatScrollTraceSink,
        )
    }
    DisposableEffect(instance) {
        onDispose { instance.dispose() }
    }
    SideEffect { instance.commit(enabled, rowKey, rowType) }
}

/** A point marker at the user toggle, with no probe attachment or logging. */
internal fun traceChatToggle(kind: String, expanded: Boolean) {
    if (!StreamDiagnosticControl.allowed || !Trace.isEnabled()) return
    emitChatScrollToggle(true, kind, expanded, AndroidChatScrollTraceSink)
}

/** No key hashes or key text leave this bounded, process-local LRU.
 * IDs are never recycled. A key gets a NEW identity after eviction; correlation
 * is guaranteed only while resident, not across eviction or process restart.
 * String row keys and LazyListItemInfo.key share this same mapping.
 * Non-string/default Compose keys are supported without calling toString().
 */
internal class ChatScrollAnonymousIds(
    private val capacity: Int = CHAT_SCROLL_TRACE_MAX_IDENTITIES,
) {
    init {
        require(capacity in 1..CHAT_SCROLL_TRACE_MAX_IDENTITIES)
    }

    private val entries = LinkedHashMap<Any, Long>(capacity, 0.75f, true)
    private var nextId = 0L

    val size: Int
        @Synchronized get() = entries.size

    @Synchronized
    fun idFor(key: Any): Long {
        entries[key]?.let { return it }
        if (entries.size == capacity) {
            val iterator = entries.entries.iterator()
            iterator.next()
            iterator.remove()
        }
        val id = ++nextId
        entries[key] = id
        return id
    }
}

/** An injectable numeric/point-only sink keeps pure tests off android.os.Trace. */
internal interface ChatScrollTraceSink {
    fun counter(name: String, value: Long)
    fun section(name: String)
}

private object AndroidChatScrollTraceSink : ChatScrollTraceSink {
    override fun counter(name: String, value: Long) {
        if (!StreamDiagnosticControl.allowed || !Trace.isEnabled()) return
        Trace.setCounter(name, value)
    }

    override fun section(name: String) {
        if (!StreamDiagnosticControl.allowed || !Trace.isEnabled()) return
        // Check once before begin; keep begin/end paired if OFF changes concurrently.
        // A short point marker, not an elapsed row-composition measurement.
        Trace.beginSection(name)
        Trace.endSection()
    }
}

private val chatScrollTraceIds = ChatScrollAnonymousIds()
private val chatScrollTraceInstances = AtomicLong()

internal fun chatScrollTraceType(type: String): String = when (type) {
    "message", "user", "agent", "thinking", "tool", "work",
    "work-header", "work-tool", "work-thinking", "work-question", "work-summary" -> type
    else -> "other"
}

internal fun emitChatScrollToggle(
    enabled: Boolean,
    kind: String,
    expanded: Boolean,
    sink: ChatScrollTraceSink,
) {
    if (!enabled) return
    val type = chatScrollTraceType(kind)
    sink.section("chat.toggle k=$type expanded=${if (expanded) 1 else 0}")
}

internal class ChatScrollRowTraceState(
    private val instance: Long,
    private val ids: ChatScrollAnonymousIds,
    private val sink: ChatScrollTraceSink,
) {
    private var hasCommitted = false
    private var attached = false
    private var lastId = 0L
    private var lastType = "other"

    fun commit(enabled: Boolean, key: String, type: String) {
        val firstCommit = !hasCommitted
        hasCommitted = true
        if (!enabled) {
            attached = false
            return
        }
        lastId = ids.idFor(key)
        lastType = chatScrollTraceType(type)
        if (!attached) {
            val event = if (firstCommit) "start" else "attach"
            sink.section("chat.row.$event t=$lastType r=$lastId i=$instance")
            attached = true
        }
        sink.section("chat.row.commit t=$lastType r=$lastId i=$instance")
    }

    fun dispose() {
        if (!attached) return
        sink.section("chat.row.dispose t=$lastType r=$lastId i=$instance")
        attached = false
    }
}

/** Raw keys are used for equality/anonymous lookup only, never for output. */
internal data class ChatScrollRowGeometry(
    val key: Any,
    val index: Int,
    val offset: Int,
    val size: Int,
)

internal data class ChatScrollGeometry(
    val firstIndex: Int,
    val firstOffset: Int,
    val viewportStart: Int,
    val viewportEnd: Int,
    val totalCount: Int,
    val canScrollForward: Boolean,
    val canScrollBackward: Boolean,
    val isScrollInProgress: Boolean,
    val visibleCount: Int,
    val rows: List<ChatScrollRowGeometry>,
)

/**
 * Fixed counter names bound track cardinality, even across many row instances.
 * sample is written LAST as a batch delimiter; instance identifies the list.
 * Multiple concurrently mounted lists share these tracks and must be separated
 * by instance/sample batches, not treated as one continuous geometry series.
 * Missing slots are cleared (id=0, index=-1), avoiding stale second anchors.
 * Enabled tracing has observer/allocation overhead and 45 counter writes per
 * changed sample; it is diagnostic instrumentation, not a performance fix.
 */
internal class ChatScrollGeometryWriter(
    private val ids: ChatScrollAnonymousIds,
    private val sink: ChatScrollTraceSink,
) {
    private var previous: ChatScrollGeometry? = null
    private var previousInstance: Long? = null
    private var sample = 0L

    fun record(enabled: Boolean, instance: Long, geometry: ChatScrollGeometry) {
        if (!enabled) {
            previous = null
            previousInstance = null
            return
        }
        val bounded = geometry.copy(rows = geometry.rows.take(CHAT_SCROLL_TRACE_MAX_ROWS))
        if (previous == bounded && previousInstance == instance) return
        previous = bounded
        previousInstance = instance
        sink.counter("chat.list.instance", instance)
        sink.counter("chat.list.first_index", bounded.firstIndex.toLong())
        sink.counter("chat.list.first_offset", bounded.firstOffset.toLong())
        sink.counter("chat.list.viewport_start", bounded.viewportStart.toLong())
        sink.counter("chat.list.viewport_end", bounded.viewportEnd.toLong())
        sink.counter("chat.list.total_count", bounded.totalCount.toLong())
        sink.counter("chat.list.can_forward", if (bounded.canScrollForward) 1L else 0L)
        sink.counter("chat.list.can_backward", if (bounded.canScrollBackward) 1L else 0L)
        sink.counter("chat.list.scroll_in_progress", if (bounded.isScrollInProgress) 1L else 0L)
        sink.counter("chat.list.visible_count", bounded.visibleCount.toLong())
        sink.counter("chat.list.sampled_count", bounded.rows.size.toLong())
        sink.counter("chat.list.omitted_count", (bounded.visibleCount - bounded.rows.size).coerceAtLeast(0).toLong())
        repeat(CHAT_SCROLL_TRACE_MAX_ROWS) { slot ->
            val row = bounded.rows.getOrNull(slot)
            // This interpolation and all anonymous mapping are behind the gate.
            val prefix = "chat.list.v$slot"
            sink.counter("$prefix.id", row?.let { ids.idFor(it.key) } ?: 0L)
            sink.counter("$prefix.index", row?.index?.toLong() ?: -1L)
            sink.counter("$prefix.offset", row?.offset?.toLong() ?: 0L)
            sink.counter("$prefix.size", row?.size?.toLong() ?: 0L)
        }
        sink.counter("chat.list.sample", ++sample)
    }
}
