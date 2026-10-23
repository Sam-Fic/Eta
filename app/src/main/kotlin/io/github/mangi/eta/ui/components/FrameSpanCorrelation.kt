package io.github.mangi.eta.ui.components

internal const val FRAME_CORRELATION_LOOKBACK_NS = 200_000_000L
internal const val FRAME_CAPTURE_MAX_SPANS = 512
internal const val FRAME_PROTECTED_SPAN_CAPACITY = 2048
internal const val FRAME_CORRELATION_MAX_SPANS = 48
internal const val FRAME_LIST_MAX_ROWS = 8

/** Wall-time intersections, never CPU attribution or a proof of causality. Intervals are half-open. */
internal fun diagnosticOverlapNs(begin: Long, end: Long, from: Long, to: Long): Long =
    (minOf(end, to) - maxOf(begin, from)).coerceAtLeast(0)

internal fun diagnosticUnionNs(intervals: List<Pair<Long, Long>>, from: Long, to: Long): Long {
    val clipped = intervals.map { maxOf(it.first, from) to minOf(it.second, to) }
        .filter { it.second > it.first }.sortedBy { it.first }
    var total = 0L
    var start = 0L
    var end = 0L
    var open = false
    for ((a, b) in clipped) {
        if (!open) { start = a; end = b; open = true }
        else if (a <= end) end = maxOf(end, b)
        else { total += end - start; start = a; end = b }
    }
    return total + if (open) end - start else 0
}

internal data class DiagnosticFrameCorrelation(
    val overlaps: List<DiagnosticSpanRecord>, val preceding: List<DiagnosticSpanRecord>,
    val totalOverlaps: Int, val overlapUnionNs: Long, val frameWallOutsideSpansNs: Long,
    val totalPreceding: Int,
) {
    val omitted: Int get() = totalOverlaps - overlaps.size
    val precedingOmitted: Int get() = totalPreceding - preceding.size
}

/**
 * Single callback capture of all retained current + previous-window candidates (bounded by their rings).
 * List-source matching must use this complete set, not the separately capped protected/output spans.
 */
internal data class DiagnosticFrameEvidence(
    val spans: List<DiagnosticSpanRecord>,
    val sourceWindowLoss: Boolean = false,
    val sourceWindowUnknown: Boolean = false,
)

internal fun diagnosticFrameEvidenceIncomplete(frame: DiagnosticFrameRecord, detail: DiagnosticDetailSnapshot,
    openSpans: Long): Boolean = !frame.detailCaptured || frame.sourceWindowLoss || frame.sourceWindowUnknown ||
    detail.overwritten > 0 || detail.slowBudgetDropped > 0 || detail.spanOutputTruncated > 0 ||
    detail.protectedBudgetDropped > 0 || detail.frameCaptureTruncated > 0 || openSpans > 0

internal fun correlateDiagnosticFrame(frame: DiagnosticFrameRecord, spans: List<DiagnosticSpanRecord>,
    limit: Int = FRAME_CORRELATION_MAX_SPANS): DiagnosticFrameCorrelation {
    require(limit > 0)
    val from = frame.intendedNs
    val to = from + frame.totalNs
    val main = spans.filter { it.main }.distinctBy { it.span }
    val matching = main.filter { diagnosticOverlapNs(it.beginNs, it.endNs, from, to) > 0 }
        .sortedWith(compareByDescending<DiagnosticSpanRecord> { diagnosticOverlapNs(it.beginNs, it.endNs, from, to) }
            .thenBy { it.beginNs }.thenBy { it.span })
    val preceding = main.filter { it.endNs <= from &&
        diagnosticOverlapNs(it.beginNs, it.endNs, from - FRAME_CORRELATION_LOOKBACK_NS, from) > 0 }
        .sortedByDescending { it.endNs }
    val union = diagnosticUnionNs(matching.map { it.beginNs to it.endNs }, from, to)
    return DiagnosticFrameCorrelation(matching.take(limit), preceding.take(8), matching.size, union,
        (frame.totalNs - union).coerceAtLeast(0), preceding.size)
}

/** A diagnostic upper bound if any direct child was not retained. Inclusive spans must not be summed. */
internal fun diagnosticSelfUpperBoundNs(span: DiagnosticSpanRecord, spans: List<DiagnosticSpanRecord>): Long =
    (span.endNs - span.beginNs - diagnosticUnionNs(spans.filter { it.parent == span.span && it.thread == span.thread }
        .map { it.beginNs to it.endNs }, span.beginNs, span.endNs)).coerceAtLeast(0)

/** Independent primitive slow ring. Copies happen under admission lock; records are built only on worker. */
internal class DiagnosticSpanColumns(private val capacity: Int) {
    private val stages = arrayOfNulls<String>(capacity)
    private val ids = LongArray(capacity)
    private val parents = LongArray(capacity)
    private val begins = LongArray(capacity)
    private val ends = LongArray(capacity)
    private val threads = LongArray(capacity)
    private val mains = BooleanArray(capacity)
    private val attrs = arrayOfNulls<StreamDiagnosticAttribution>(capacity)
    private val pages = IntArray(capacity)
    private val pageEnds = IntArray(capacity)
    private val values = LongArray(capacity)
    private var size = 0
    fun add(stage: String, id: Long, parent: Long, begin: Long, end: Long, thread: Long, main: Boolean,
        attr: StreamDiagnosticAttribution?, page: Int, pageEnd: Int, value: Long) {
        check(size < capacity)
        val i = size++
        stages[i] = stage; ids[i] = id; parents[i] = parent; begins[i] = begin; ends[i] = end
        threads[i] = thread; mains[i] = main; attrs[i] = attr; pages[i] = page; pageEnds[i] = pageEnd; values[i] = value
    }
    fun detached(): DiagnosticSpanColumns = DiagnosticSpanColumns(size).also { out ->
        stages.copyInto(out.stages, endIndex = size); ids.copyInto(out.ids, endIndex = size)
        parents.copyInto(out.parents, endIndex = size); begins.copyInto(out.begins, endIndex = size)
        ends.copyInto(out.ends, endIndex = size); threads.copyInto(out.threads, endIndex = size)
        mains.copyInto(out.mains, endIndex = size); attrs.copyInto(out.attrs, endIndex = size)
        pages.copyInto(out.pages, endIndex = size); pageEnds.copyInto(out.pageEnds, endIndex = size)
        values.copyInto(out.values, endIndex = size); out.size = size
    }
    fun records(): List<DiagnosticSpanRecord> = (0 until size).map { i ->
        DiagnosticSpanRecord(requireNotNull(stages[i]), ids[i], parents[i], begins[i], ends[i], threads[i], mains[i],
            attrs[i], pages[i], pageEnds[i], values[i])
    }
    /** Detached snapshot only: skip out-of-range/non-main rows before allocating records. */
    fun recordsInRange(from: Long, to: Long): List<DiagnosticSpanRecord> = buildList {
        for (i in 0 until this@DiagnosticSpanColumns.size) {
            if (!mains[i] || diagnosticOverlapNs(begins[i], ends[i], from, to) <= 0) continue
            add(DiagnosticSpanRecord(requireNotNull(stages[i]), ids[i], parents[i], begins[i], ends[i],
                threads[i], mains[i], attrs[i], pages[i], pageEnds[i], values[i]))
        }
    }

    fun reset() { stages.fill(null); attrs.fill(null); size = 0 }
}

/** Raw keys are bounded in memory for equality only. Tokens never recycle within one foreground session. */
internal class DiagnosticRowTokens(private val capacity: Int = 1024) {
    private val keys = HashMap<Any, Int>()
    var saturated = 0L
        private set
    init { require(capacity > 0) }
    @Synchronized fun token(key: Any): Int {
        keys[key]?.let { return it }
        if (keys.size == capacity) { saturated++; return 0 }
        return (keys.size + 1).also { keys[key] = it }
    }
}

internal fun diagnosticRowType(raw: String): String = when (raw) {
    "user", "agent", "thinking", "tool", "message", "work-header", "work-tool", "work-thinking",
    "work-question", "work-summary" -> raw
    else -> "unknown"
}

internal fun diagnosticBlockType(raw: String): String = when (raw) {
    "PARAGRAPH" -> "paragraph"
    "ATX_1", "ATX_2", "ATX_3", "ATX_4", "ATX_5", "ATX_6", "SETEXT_1", "SETEXT_2" -> "heading"
    "ORDERED_LIST", "UNORDERED_LIST" -> "list"
    "BLOCK_QUOTE" -> "quote"
    "CODE_BLOCK", "CODE_FENCE" -> "code"
    "TABLE" -> "table"
    "HTML_BLOCK" -> "html"
    "IMAGE" -> "image"
    "HORIZONTAL_RULE" -> "rule"
    else -> "other"
}

internal data class DiagnosticListRow(val token: Int, val index: Int, val offset: Int, val size: Int)
internal data class DiagnosticListSnapshot(val atNs: Long, val list: Long, val messageCount: Int,
    val totalCount: Int, val firstIndex: Int, val firstOffset: Int, val viewportStart: Int, val viewportEnd: Int,
    val visibleCount: Int, val rows: List<DiagnosticListRow>, val page: Int = 0, val segment: Long = 0)

/** Post-layout samples are not a claim of the exact geometry measured by the frame. Staleness is printed. */
internal class DiagnosticListSamples(private val capacity: Int = 128) {
    private val ring = arrayOfNulls<DiagnosticListSnapshot>(capacity)
    private var next = 0
    var overwritten = 0L
        private set
    @Synchronized fun add(sample: DiagnosticListSnapshot) {
        if (ring[next] != null) overwritten++
        ring[next] = sample; next = (next + 1) % capacity
    }
    fun forFrame(frame: DiagnosticFrameRecord, evidence: DiagnosticFrameEvidence): DiagnosticListSnapshot? {
        if (frame.changed || frame.page == 0 || frame.pageSegment == 0L ||
            frame.sourceWindowLoss || frame.sourceWindowUnknown ||
            evidence.sourceWindowLoss || evidence.sourceWindowUnknown) return null
        val detached = synchronized(this) { ring.copyOf() }
        val candidates = detached.filterNotNull().filter {
            it.atNs <= frame.intendedNs + frame.totalNs && it.page == frame.page && it.segment == frame.pageSegment
        }
        // Full captured scopes take precedence; never infer uniqueness from protected/output truncation.
        val listSpans = evidence.spans.filter { it.stage == "list.measure" || it.stage == "list.place" }
            .filter { diagnosticOverlapNs(it.beginNs, it.endNs, frame.intendedNs, frame.intendedNs + frame.totalNs) > 0 }
        if (listSpans.any { (it.attribution?.list ?: 0L) == 0L }) return null
        val matched = listSpans.mapNotNull { it.attribution?.list }.distinct()
        if (matched.size > 1) return null
        val sources = candidates.map { it.list }.distinct()
        val list = matched.singleOrNull() ?: sources.singleOrNull() ?: return null
        return candidates.filter { it.list == list }.maxByOrNull { it.atNs }
    }
}
