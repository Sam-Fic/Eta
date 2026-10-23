package io.github.mangi.eta.ui.components

import java.security.MessageDigest
import java.security.SecureRandom
import java.lang.ref.WeakReference

/** All collector/trace labels are compiled-in, not inferred from payloads or class names. */
internal object StreamDiagnosticLabels {
    val stages: Set<String> = setOf(
        "ui.enqueue", "ui.runEvent", "ui.replay", "ui.flush", "ui.flushDelay", "ui.delta.received", "ui.delta.gap",
        "ui.flush.blockSwitch", "ui.flush.nonDelta", "ui.flush.timer",
        "ui.messages.apply", "ui.messages.transform", "ui.messages.normalize", "ui.messages.publish",
        "ui.conversation.route", "ui.conversation.owner", "ui.conversation.waiting", "ui.conversation.publish", "ui.summaries.refresh",
        "ipc.delta.delay", "ipc.client.receive", "ipc.attach.receive",
        "ipc.client.decode.live", "ipc.client.callback.live", "ipc.attach.decode.live", "ipc.attach.decode.replay",
        "ipc.attach.callback.live", "ipc.attach.callback.replay", "ipc.attach.callback.replayBatch",
        "runtime.checkpoint.accept", "runtime.checkpoint.append", "runtime.checkpoint.flush.size", "runtime.checkpoint.flush.timer",
        "runtime.checkpoint.flush.boundary", "runtime.checkpoint.flush.seal",
        "chat.compose", "chat.compose.elapsed", "chat.input.compose", "timeline.project", "timeline.prefaces", "gallery.scan", "gallery.parse", "gallery.hit", "gallery.skip",
        "markdown.target", "markdown.coalesced", "markdown.queueWait", "markdown.parse", "markdown.superseded",
        "markdown.publishBlock", "markdown.targetToPublish", "markdown.publish", "markdown.layout", "markdown.blockDraw",
        "reveal.frameGap", "reveal.step", "reveal.remeasure",
        "scroll.state", "tail.clippedPx", "tail.breachPx", "follow.initialSnap", "follow.decision", "follow.frameGap",
        "follow.scroll", "follow.step", "follow.cancelled", "follow.viewportRecovery",
        "heap.usedBytes", "main.frameMessages", "main.otherMessages", "main.message", "main.doFrame",
        "frame.total", "frame.layout", "frame.draw", "frame.sync", "frame.gpu", "frame.input", "frame.unknown",
        "frame.animation", "frame.command", "frame.swap", "frame.unaccounted", "frame.overlap", "frame.vsyncLate",
        "frame.metricsDropped", "frame.deadline", "frame.firstDraw", "frame.steady", "diagnostic.clockSync",
        "persistence.write", "persistence.read", "persistence.serialize", "persistence.queueWait",
        "render.measure", "render.draw", "render.compose",
        "markdown.prepared.annotated", "markdown.prepared.raw", "markdown.annotated.build", "markdown.annotated.cell", "markdown.annotated.raw", "markdown.citation.strip",
        "markdown.hidden.childHeight", "markdown.hidden.measure", "markdown.hidden.reportHeight",
        "markdown.stable.draw", "markdown.stable.measure", "markdown.tail.draw", "markdown.tail.measure",
        "reveal.drawContent", "reveal.graphemes.append", "reveal.graphemes.cacheHit", "reveal.graphemes.rebuild",
        "reveal.layout.update", "reveal.measure", "reveal.paths.append", "reveal.paths.cacheHit",
        "reveal.paths.nextGrapheme", "reveal.paths.rebuild", "reveal.record.cacheHit", "reveal.record.update", "reveal.saveLayer",
        "runtime.checkpoint.buffer.chars", "runtime.checkpoint.buffer.events", "runtime.checkpoint.buffer.residency",
        "runtime.checkpoint.encode", "runtime.checkpoint.lockWait", "runtime.checkpoint.merge", "runtime.checkpoint.write",
        "settings.commitTail", "settings.composition", "settings.editEntryWait", "settings.root.draw", "settings.root.measure", "settings.transform",
        "settings.topbar.measure", "settings.lazy.measure", "settings.lazy.place", "settings.lazy.draw", "manage.lazy.measure", "manage.lazy.place", "manage.lazy.draw", "drawer.lazy.measure", "drawer.lazy.place", "drawer.lazy.draw",
        "settings.permission.overlay.initial", "settings.permission.overlay.resume",
        "settings.permission.accessibility.initial", "settings.permission.accessibility.resume",
        "settings.permission.protection.initial", "settings.permission.protection.resume",
        "settings.permission.assistant.initial", "settings.permission.assistant.resume",
        "settings.prefs.initial", "settings.prefs.refresh", "settings.prefs.capture", "settings.prefs.reconcile", "settings.service.subscribe",
        "render.userPrompt.parse", "render.userBubble.compose", "render.userBubble.measure", "render.userBubble.draw", "render.userText.measure", "render.userText.draw",
        "usage.commitTail", "usage.editEntryWait", "usage.ledger.encodeEvents", "usage.ledger.serialize", "usage.ledger.update",
        "usage.load.dao.conversations", "usage.load.dao.liveIds", "usage.load.dao.messages", "usage.load.dao.perDay",
        "usage.load.decode", "usage.lockWait", "usage.transform",
    )
    val kinds: Set<String> = setOf(
        "unknown", "replayBatch", "start.text", "start.thinking", "start.toolCall", "delta.text", "delta.thinking", "delta.toolCall",
        "end.text", "end.thinking", "end.toolCall", "runStarted", "roundStarted", "modelRetry", "reconnect", "providerRequest",
        "providerResponse", "assistantReceived", "childContext", "usage.projected", "usage.receipt", "supplement", "toolStarted",
        "toolFinished", "hostedToolStarted", "hostedToolFinished", "toolImages", "compactWaiting", "compactStarted", "compacted",
        "runFinished", "runFailed", "questionRequested", "questionResolved",
    )
    fun kind(label: String): String = if (label in kinds) label else "unknown"
    private val families = setOf("render", "markdown", "reveal", "settings", "persistence", "usage", "datastore", "citation")
    /** Unknown suffixes collapse to a fixed label; they are NEVER retained verbatim. */
    fun canonicalStage(label: String): String? {
        if (stage(label)) return label
        val family = label.substringBefore('.')
        if (family in families) return "$family.unknown"
        if (label.startsWith("runtime.checkpoint.")) return "runtime.checkpoint.unknown"
        return null
    }
    fun stage(label: String): Boolean = label in stages || label in StreamDiagnosticGapLabels.stages ||
        (label.startsWith("ui.event.") && label.removePrefix("ui.event.") in kinds) ||
        label in pageStages
    // Keep this pure Kotlin: compiled static route names, never arbitrary frame.page.* input.
    private val pageStages = ("Unknown Transition BrowserOverlay ConversationDrawer Home Chat Browser Terminal Tools " +
        "AgentTaskPreference VirtualDisplayRecovery Haptics Skills Permissions SystemEnhance Settings SpeechSettings TtsSettings " +
        "VoiceSettings AuxiliaryVision TitleModel ErrorReconnectSettings SubAgents VoiceModeSettings AppearanceSettings DataBackup " +
        "Memory LinuxEnvironment SharedFolders Workspace LinuxFiles ModelProviders McpServers ModelProviderDetail " +
        "ModelProviderAuthMethod ModelProviderNew ContextCompression Assistants AssistantEdit UsageStats ManageChats McpServerDetail")
        .split(' ').map { "frame.page.$it" }.toSet()
}

/** Pure Kotlin, process/session-local pseudonyms. Only salted fingerprints are retained. */
internal class AnonymousDiagnosticTokens(private val capacity: Int = 256) {
    private val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val fingerprints = arrayOfNulls<ByteArray>(capacity)
    // Bounded in-memory cache; never traversed by a formatter or exposed in snapshots.
    private val identities = arrayOfNulls<String>(capacity)
    private val digest = MessageDigest.getInstance("SHA-256")
    private var size = 0
    var saturated = 0L
        private set
    init { require(capacity > 0) }

    @Synchronized fun token(identity: String?): Int {
        if (identity == null) return 0
        for (i in 0 until size) if (identities[i] == identity) return i + 1
        if (size == capacity) { saturated++; return 0 }
        digest.update(salt)
        val fingerprint = digest.digest(identity.toByteArray(Charsets.UTF_8))
        for (i in 0 until size) if (fingerprints[i]!!.contentEquals(fingerprint)) return i + 1
        fingerprints[size] = fingerprint
        identities[size] = identity
        return ++size
    }
}

internal enum class DiagnosticVisibility { Unknown, Selected, Hidden }

/** No business identity or payload. Safe to explicitly pass to existing background work. */
internal data class StreamDiagnosticAttribution(
    val session: Long,
    val run: Int = 0,
    val conversation: Int = 0,
    val visibility: DiagnosticVisibility = DiagnosticVisibility.Unknown,
    val kind: String = "unknown",
    val replay: Boolean = false,
    val event: Long = 0,
    val sourceSpan: Long = 0,
    val list: Long = 0,
    val row: Int = 0,
    val rowType: String = "unknown",
    val block: Int = -1,
    val blockType: String = "unknown",
    val blockChars: Int = 0,
    val component: String = "unknown",
)

internal data class DiagnosticSpanContext(
    val attribution: StreamDiagnosticAttribution?,
    val span: Long = 0,
    val insideReveal: Boolean = false,
    val insideMeasure: Boolean = false,
)

/** Synchronous scopes only: never leave this installed across a coroutine suspension. */
internal class DiagnosticThreadContext {
    private val local = ThreadLocal<DiagnosticSpanContext?>()
    fun current(): DiagnosticSpanContext? = local.get()
    fun capture(): StreamDiagnosticAttribution? = current()?.let { context ->
        context.attribution?.copy(sourceSpan = context.span.takeIf { it != 0L } ?: context.attribution.sourceSpan)
    }
    fun <T> with(context: DiagnosticSpanContext?, block: () -> T): T {
        val previous = local.get()
        local.set(context)
        try { return block() } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }
}

internal data class DiagnosticSpanRecord(
    val stage: String, val span: Long, val parent: Long,
    val beginNs: Long, val endNs: Long, val thread: Long, val main: Boolean,
    val attribution: StreamDiagnosticAttribution?, val page: Int, val pageEnd: Int, val value: Long,
)

/** Dispatch wall time, not CPU time or a FrameTimeline frame. Subsets must not be added. */
internal data class DiagnosticMainMessageRecord(
    val beginNs: Long, val endNs: Long, val frameDispatch: Boolean,
    val coveredNs: Long, val revealNs: Long, val cpuNs: Long = -1, val partial: Boolean = false,
    val schedRunNs: Long = -1, val schedRunnableNs: Long = -1,
    val topStage: String? = null,
    val beforeFirstNs: Long = -1, val afterLastNs: Long = -1,
) {
    val uninstrumentedNs: Long get() = endNs - beginNs - coveredNs
    val nonRevealNs: Long get() = endNs - beginNs - revealNs
    /** Run-queue wait is not sleep. blockedNs is wall minus on-CPU minus run-queue. */
    fun schedstatFields(): String {
        val wall = (endNs - beginNs).coerceAtLeast(0)
        val blocked = if (schedRunNs < 0 || schedRunnableNs < 0) -1L
            else (wall - schedRunNs - schedRunnableNs).coerceAtLeast(0)
        return "schedRunNs=$schedRunNs schedRunnableNs=$schedRunnableNs blockedNs=$blocked schedstat=runQueueSeparateFromBlocked"
    }
}

internal data class DiagnosticFrameRecord(
    val intendedNs: Long, val vsyncNs: Long, val totalNs: Long, val deadlineNs: Long,
    val page: Int, val pageEnd: Int, val changed: Boolean, val metricsDropped: Int,
    val unknownNs: Long, val inputNs: Long, val animationNs: Long, val layoutNs: Long,
    val drawNs: Long, val syncNs: Long, val commandNs: Long, val swapNs: Long, val gpuNs: Long,
    val listSnapshot: DiagnosticListSnapshot? = null,
    val mainMessages: List<DiagnosticMainMessageRecord> = emptyList(),
    val firstDraw: Boolean = false,
    val pageSegment: Long = 0,
    val sourceWindowLoss: Boolean = false,
    val sourceWindowUnknown: Boolean = false,
    val detailCaptured: Boolean = true,
) {
    val severe: Boolean get() = totalNs >= 33_000_000L || unknownNs >= 8_000_000L
    val missed: Boolean get() = deadlineNs > 0 && totalNs > deadlineNs
    // Signed residual of FrameMetrics components; GPU overlaps command/swap and is excluded.
    private val residualNs: Long get() = totalNs -
        (unknownNs + inputNs + animationNs + layoutNs + drawNs + syncNs + commandNs + swapNs)
    val unaccountedNs: Long get() = residualNs.coerceAtLeast(0)
    val overlapNs: Long get() = (-residualNs).coerceAtLeast(0)
    // Vsync lateness overlaps unknown delay: it is NOT an additional frame component.
    val vsyncLateNs: Long get() = (vsyncNs - intendedNs).coerceAtLeast(0)
}

internal data class DiagnosticDetailSnapshot(
    val fromNs: Long, val toNs: Long,
    val spans: List<DiagnosticSpanRecord>, val frames: List<DiagnosticFrameRecord>,
    val overwritten: Long, val slowBudgetDropped: Long, val frameBudgetDropped: Long,
    val spanOutputTruncated: Long,
    val protectedBudgetDropped: Long = 0,
    val frameCaptureTruncated: Long = 0,
    val previousWindowSpans: Long = 0,
    val frameBudgetEvicted: Long = 0,
)

/** Primitive columns detached under the admission lock; selection and record construction are outside it. */
internal class DiagnosticRawDetailSnapshot internal constructor(
    val fromNs: Long, val toNs: Long,
    private val stages: Array<String?>, private val spans: LongArray, private val parents: LongArray,
    private val begins: LongArray, private val ends: LongArray, private val threads: LongArray,
    private val mains: BooleanArray, private val attrs: Array<StreamDiagnosticAttribution?>,
    private val pages: IntArray, private val pageEnds: IntArray, private val values: LongArray,
    private val frames: Array<DiagnosticFrameRecord?>,
    private val overwritten: Long, private val slowDropped: Long, private val frameDropped: Long,
    private val slowLimit: Int, private val slowNs: Long, private val first: Int, private val size: Int,
    private val slowColumns: DiagnosticSpanColumns? = null,
    private val protectedSpans: Array<DiagnosticSpanRecord?> = emptyArray(),
    private val protectedDropped: Long = 0,
    private val captureTruncated: Long = 0,
    private val previousWindowSpans: Long = 0,
    private val frameEvicted: Long = 0,
) {
    private fun candidates(includeProtected: Boolean): List<DiagnosticSpanRecord> {
        val out = linkedMapOf<Long, DiagnosticSpanRecord>()
        // Protected records were copied at the anomalous FrameMetrics callback, not at emit.
        if (includeProtected) protectedSpans.filterNotNull().forEach { out[it.span] = it }
        slowColumns?.records()?.forEach { out[it.span] = it }
        for (i in 0 until size) {
            val slot = (first + i) % stages.size
            out.putIfAbsent(spans[slot], DiagnosticSpanRecord(
                requireNotNull(stages[slot]), spans[slot], parents[slot], begins[slot], ends[slot], threads[slot],
                mains[slot], attrs[slot], pages[slot], pageEnds[slot], values[slot],
            ))
        }
        return out.values.toList()
    }

    fun recentForFrame(frame: DiagnosticFrameRecord): List<DiagnosticSpanRecord> {
        val from = frame.intendedNs - FRAME_CORRELATION_LOOKBACK_NS
        val to = frame.intendedNs + frame.totalNs
        val out = linkedMapOf<Long, DiagnosticSpanRecord>()
        slowColumns?.recordsInRange(from, to)?.forEach { out[it.span] = it }
        for (i in 0 until size) {
            val slot = (first + i) % stages.size
            if (!mains[slot] || diagnosticOverlapNs(begins[slot], ends[slot], from, to) <= 0 ||
                out.containsKey(spans[slot])) continue
            out[spans[slot]] = DiagnosticSpanRecord(
                requireNotNull(stages[slot]), spans[slot], parents[slot], begins[slot], ends[slot], threads[slot],
                mains[slot], attrs[slot], pages[slot], pageEnds[slot], values[slot],
            )
        }
        return out.values.toList()
    }

    /** Lost timestamps are unavailable: conservatively propagate loss in any inspected admission generation. */
    fun sourceWindowLoss(): Boolean = overwritten > 0 || slowDropped > 0

    fun select(): DiagnosticDetailSnapshot {
        val frameSnapshot = frames.filterNotNull()
        val detailedFrames = frameSnapshot.filter { it.detailCaptured }
        val eligible = candidates(includeProtected = true).filter { span ->
            span.endNs - span.beginNs >= slowNs || detailedFrames.any { frame ->
                span.main && diagnosticOverlapNs(span.beginNs, span.endNs,
                    frame.intendedNs - FRAME_CORRELATION_LOOKBACK_NS, frame.intendedNs + frame.totalNs) > 0
            }
        }
        // Actual frame overlaps precede lookback-only and unrelated slow work. Stable ties keep admission order.
        val prioritized = eligible.sortedBy { span ->
            if (span.main && detailedFrames.any { diagnosticOverlapNs(span.beginNs, span.endNs,
                    it.intendedNs, it.intendedNs + it.totalNs) > 0 }) 0 else 1
        }
        val out = prioritized.take(slowLimit)
        return DiagnosticDetailSnapshot(fromNs, toNs, out, frameSnapshot,
            overwritten, slowDropped, frameDropped, (prioritized.size - out.size).toLong(),
            protectedDropped, captureTruncated, previousWindowSpans, frameEvicted)
    }
}

/**
 * Preallocated ring. Windows contain records admitted before the snapshot lock cutoff,
 * not records filtered by completion timestamps. Late timestamps are retained unchanged.
 * The shared session lock only copies bounded raw columns and resets admission budgets.
 */
internal class BoundedDiagnosticDetails(
    private val startedNs: Long,
    private val capacity: Int = 2048,
    private val slowLimit: Int = 512,
    private val frameLimit: Int = 120,
    private val slowNs: Long = 4_000_000L,
    private val admissionLock: Any = Any(),
) {
    private val stages = arrayOfNulls<String>(capacity)
    private val spans = LongArray(capacity)
    private val parents = LongArray(capacity)
    private val begins = LongArray(capacity)
    private val ends = LongArray(capacity)
    private val threads = LongArray(capacity)
    private val values = LongArray(capacity)
    private val mains = BooleanArray(capacity)
    private val attrs = arrayOfNulls<StreamDiagnosticAttribution>(capacity)
    private val pages = IntArray(capacity)
    private val pageEnds = IntArray(capacity)
    private var next = 0
    private var size = 0
    private var fromNs = startedNs
    private var overwritten = 0L
    private var slowAccepted = 0
    private var slowDropped = 0L
    private var frameDropped = 0L
    // Fixed-size, bounded objects on the worker only (FrameMetrics callback).
    private val frames = arrayOfNulls<DiagnosticFrameRecord>(frameLimit)
    private var frameSize = 0
    // Separate primitive retention prevents a flood of short spans overwriting all slow evidence.
    private var slowColumns = DiagnosticSpanColumns(slowLimit)
    private val protectedSpans = arrayOfNulls<DiagnosticSpanRecord>(FRAME_PROTECTED_SPAN_CAPACITY)
    private val protectedIds = HashSet<Long>()
    private var protectedSize = 0
    private var protectedDropped = 0L
    private var captureTruncated = 0L
    private var previousWindowSpans = 0L
    private var previousWindow: DiagnosticRawDetailSnapshot? = null
    init { require(capacity > 0 && slowLimit > 0 && frameLimit > 0) }

    @Volatile var closed = false
        private set
    var closedRejectedSpans = 0L
        private set
    var closedRejectedFrames = 0L
        private set

    fun span(
        stage: String, span: Long, parent: Long, beginNs: Long, endNs: Long,
        thread: Long, main: Boolean, attribution: StreamDiagnosticAttribution?, page: Int, pageEnd: Int, value: Long = 0,
    ): Unit = synchronized(admissionLock) {
        if (closed) { closedRejectedSpans++; return }
        require(endNs >= beginNs)
        if (endNs - beginNs >= slowNs) {
            if (slowAccepted >= slowLimit) slowDropped++
            else {
                slowAccepted++
                slowColumns.add(stage, span, parent, beginNs, endNs, thread, main, attribution, page, pageEnd, value)
            }
            // Exhausting independent slow retention must NEVER reject ordinary ring admission.
        }
        if (size == capacity) overwritten++ else size++
        stages[next] = stage; spans[next] = span; parents[next] = parent
        begins[next] = beginNs; ends[next] = endNs; threads[next] = thread; mains[next] = main
        attrs[next] = attribution; pages[next] = page; pageEnds[next] = pageEnd; values[next] = value
        next = (next + 1) % capacity
    }

    private var frameReserved = 0
    private var lightReserved = 0
    private val lightLimit = (frameLimit - maxOf(1, frameLimit / 4)).coerceAtLeast(0)
    private var frameEvicted = 0L
    /** Reserve at least a quarter exclusively for severe evidence; severe can replace light evidence. */
    fun reserveFrame(severe: Boolean = true): Boolean = synchronized(admissionLock) {
        if (closed) { closedRejectedFrames++; return false }
        if (!severe && lightReserved >= lightLimit) { frameDropped++; return false }
        if (maxOf(frameReserved, frameSize) >= frameLimit) {
            if (severe && (0 until frameSize).any { frames[it]?.severe == false }) return true
            frameDropped++; return false
        }
        frameReserved++
        if (!severe) lightReserved++
        true
    }
    fun frame(frame: DiagnosticFrameRecord): Unit = synchronized(admissionLock) {
        if (closed) { closedRejectedFrames++; return }
        if (frameSize >= frameLimit) {
            val replace = if (frame.severe) (0 until frameSize).firstOrNull { frames[it]?.severe == false } else null
            if (replace == null) { frameDropped++; return }
            frames[replace] = frame; frameEvicted++
            return
        }
        frames[frameSize++] = frame
    }

    /** Worker-only, budget-admitted frame capture, reused for retention and list-source matching. */
    fun protectFrame(frame: DiagnosticFrameRecord): DiagnosticFrameEvidence {
        val pair = synchronized(admissionLock) {
            if (closed) return DiagnosticFrameEvidence(emptyList(), sourceWindowUnknown = true)
            recentSnapshot(frame.intendedNs + frame.totalNs) to previousWindow
        }
        val current = pair.first.recentForFrame(frame)
        val previous = pair.second?.recentForFrame(frame).orEmpty()
        val all = (current + previous).distinctBy { it.span }.sortedByDescending {
            diagnosticOverlapNs(it.beginNs, it.endNs, frame.intendedNs, frame.intendedNs + frame.totalNs)
        }
        val selected = all.take(FRAME_CAPTURE_MAX_SPANS)
        val previousIds = previous.map { it.span }.toHashSet()
        val selectedFromPrevious = selected.count { it.span in previousIds }
        // Only one detached generation is retained. Older evidence is unknown, never assumed complete.
        val earliest = pair.second?.fromNs ?: pair.first.fromNs
        // Protection/output budgets may omit a second source; source matching must see all candidates.
        val evidence = DiagnosticFrameEvidence(all,
            sourceWindowLoss = pair.first.sourceWindowLoss() || pair.second?.sourceWindowLoss() == true,
            sourceWindowUnknown = frame.intendedNs - FRAME_CORRELATION_LOOKBACK_NS < earliest && earliest > startedNs)
        synchronized(admissionLock) {
            if (closed) return DiagnosticFrameEvidence(emptyList(), sourceWindowUnknown = true)
            captureTruncated += all.size - selected.size
            previousWindowSpans += selectedFromPrevious
            selected.forEach { span ->
                if (span.span !in protectedIds) {
                    if (protectedSize == protectedSpans.size) protectedDropped++
                    else { protectedSpans[protectedSize++] = span; protectedIds.add(span.span) }
                }
            }
        }
        return evidence
    }

    private fun recentSnapshot(toNs: Long): DiagnosticRawDetailSnapshot {
        // Until full, valid slots are [0, size); once full, size == capacity. Never copy unused columns.
        val rawStages = arrayOfNulls<String>(size)
        stages.copyInto(rawStages, endIndex = size)
        return DiagnosticRawDetailSnapshot(fromNs, maxOf(fromNs, toNs),
            rawStages, spans.copyOf(size), parents.copyOf(size), begins.copyOf(size), ends.copyOf(size), threads.copyOf(size),
            mains.copyOf(size), attrs.copyOf(size), pages.copyOf(size), pageEnds.copyOf(size), values.copyOf(size), emptyArray(),
            overwritten, slowDropped, frameDropped, slowLimit, slowNs, (next - size + capacity) % capacity, size,
            slowColumns.detached())
    }

    /** No overlap scanning, span construction, or formatting while holding this lock. */
    fun snapshot(toNs: Long, final: Boolean = false): DiagnosticRawDetailSnapshot = synchronized(admissionLock) {
        require(toNs >= fromNs)
        val raw = DiagnosticRawDetailSnapshot(fromNs, toNs,
            stages.copyOf(), spans.copyOf(), parents.copyOf(), begins.copyOf(), ends.copyOf(), threads.copyOf(),
            mains.copyOf(), attrs.copyOf(), pages.copyOf(), pageEnds.copyOf(), values.copyOf(), frames.copyOf(frameSize),
            overwritten, slowDropped, frameDropped, slowLimit, slowNs, (next - size + capacity) % capacity, size,
            slowColumns.detached(), protectedSpans.copyOf(protectedSize), protectedDropped, captureTruncated, previousWindowSpans, frameEvicted)
        previousWindow = raw // One detached generation only; raw snapshots never reference each other.
        stages.fill(null); attrs.fill(null); frames.fill(null)
        fromNs = toNs; size = 0; next = 0; overwritten = 0
        slowAccepted = 0; slowDropped = 0; frameDropped = 0; frameSize = 0; frameReserved = 0; lightReserved = 0; frameEvicted = 0
        slowColumns.reset(); protectedSpans.fill(null); protectedIds.clear(); protectedSize = 0
        protectedDropped = 0; captureTruncated = 0; previousWindowSpans = 0
        if (final) previousWindow = null
        if (final) closed = true
        raw
    }

    fun drain(toNs: Long): DiagnosticDetailSnapshot = snapshot(toNs).select()
}

/** Reserve before evaluating a note's detail lambda; counters stay bounded even after exhaustion. */
internal class DiagnosticNoteBudget(private val limit: Int) {
    private var accepted = 0
    var dropped = 0L
        private set
    init { require(limit > 0) }
    @Synchronized fun reserve(): Int? {
        if (accepted >= limit) { dropped++; return null }
        return ++accepted
    }
}

/** Bounded identity hand-off for replay; saturation is explicit, never holds an entire history. */
internal class DiagnosticEventLinks(private val capacity: Int = 512) {
    private val objects = arrayOfNulls<WeakReference<Any>>(capacity)
    private val attrs = arrayOfNulls<StreamDiagnosticAttribution>(capacity)
    private var next = 0
    private var size = 0
    var overwritten = 0L
        private set
    @Synchronized fun bind(event: Any, attribution: StreamDiagnosticAttribution) {
        for (i in objects.indices) if (objects[i]?.get() === event) { attrs[i] = attribution; return }
        if (size == capacity) overwritten++ else size++
        objects[next] = WeakReference(event); attrs[next] = attribution
        next = (next + 1) % capacity
    }
    @Synchronized fun find(event: Any): StreamDiagnosticAttribution? {
        for (i in objects.indices) if (objects[i]?.get() === event) return attrs[i]
        return null
    }
}
