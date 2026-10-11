package io.github.mangi.eta.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Trace
import android.os.Debug
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import android.view.FrameMetrics
import android.util.Printer
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.core.AppFileLogger
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.github.mangi.eta.BuildConfig

/** Fixed-size aggregate; values are counts/lengths only, never message text or IDs. */
internal class StreamTimingStats {
    var count = 0L
    var totalNs = 0L
    var maxNs = 0L
    var valueSum = 0L
    var valueMax = 0L
    val buckets = LongArray(5)
    val fineBuckets = LongArray(6)
    fun add(ns: Long, value: Long) {
        val duration = ns.coerceAtLeast(0)
        count++
        totalNs += duration
        maxNs = maxOf(maxNs, duration)
        valueSum += value.coerceAtLeast(0)
        valueMax = maxOf(valueMax, value)
        buckets[when {
            duration <= 16_000_000 -> 0
            duration <= 32_000_000 -> 1
            duration <= 50_000_000 -> 2
            duration <= 100_000_000 -> 3
            else -> 4
        }]++
        fineBuckets[when {
            duration <= 8_333_333 -> 0
            duration <= 16_666_667 -> 1
            duration <= 33_333_333 -> 2
            duration <= 50_000_000 -> 3
            duration <= 100_000_000 -> 4
            else -> 5
        }]++
    }
    fun summary(): String = "n=$count avgUs=${totalNs / count.coerceAtLeast(1) / 1000} maxUs=${maxNs / 1000} " +
        "b16_32_50_100_over=${buckets.joinToString(",")} b8p33_16p67_33p33_50_100_over=${fineBuckets.joinToString(",")} valueSum=$valueSum valueMax=$valueMax"
}

/** Bounded, allocation-light output queue used only by the optional diagnostic sink. */
internal class BoundedDiagnosticOutputQueue(
    private val capacity: Int = DIAGNOSTIC_OUTPUT_CAPACITY,
    private val essentialCapacity: Int = 2048,
) {
    private data class Entry(val line: String, val essential: Boolean)
    private val pending = ArrayDeque<Entry>(capacity)
    private var details = 0
    private var essentials = 0

    @Volatile
    var dropped: Long = 0
        private set

    @Volatile var essentialDropped: Long = 0
        private set

    @Synchronized
    fun offer(line: String, essential: Boolean = false): Boolean {
        if (if (essential) essentials >= essentialCapacity else details >= capacity) {
            dropped++
            if (essential) essentialDropped++
            return false
        }
        pending.addLast(Entry(line, essential))
        if (essential) essentials++ else details++
        return true
    }

    private fun removeFirst(): String {
        val entry = pending.removeFirst()
        if (entry.essential) essentials-- else details--
        return entry.line
    }

    @Synchronized
    fun drain(limit: Int): List<String> {
        val result = ArrayList<String>(minOf(limit, pending.size))
        repeat(minOf(limit, pending.size)) { result += removeFirst() }
        return result
    }

    @Synchronized
    fun drainAll(): List<String> {
        val result = ArrayList<String>(pending.size)
        while (pending.isNotEmpty()) result += removeFirst()
        return result
    }

    @Synchronized
    fun isEmpty(): Boolean = pending.isEmpty()
}

/**
 * Diagnostic output is intentionally decoupled from frame callbacks and the
 * diagnostic aggregation worker. The queue is bounded; only diagnostic lines
 * can be dropped when a logger is slower than the producer, never UI events.
 */
internal class AsyncDiagnosticOutput(
    private val enabled: () -> Boolean = { true },
    private val sink: (String) -> Unit = AppFileLogger::diagnosticInfo,
) {
    private val queue = BoundedDiagnosticOutputQueue()
    private val thread = HandlerThread("Eta-StreamDiagOutput").apply { start() }
    private val handler = Handler(thread.looper)
    private val writeLock = Any()
    private var drainScheduled = false
    @Volatile private var closed = false
    private var asyncMode = enabled()
    private var closeResult: Boolean? = null
    private val failedLines = java.util.concurrent.atomic.AtomicLong()

    val dropped: Long get() = queue.dropped
    val essentialDropped: Long get() = queue.essentialDropped
    val failed: Long get() = failedLines.get()

    private fun writeLine(line: String) {
        try {
            sink(line)
        } catch (_: Exception) {
            failedLines.incrementAndGet()
        }
    }

    fun write(line: String) {
        synchronized(writeLock) {
            if (closed) return
            val useAsync = enabled()
            if (asyncMode && !useAsync && !flush()) {
                enqueue(line)
                return
            }
            asyncMode = useAsync
            if (asyncMode) enqueue(line) else writeLine(line)
        }
    }

    private fun enqueue(line: String) {
        synchronized(this) {
            if (closed || !queue.offer(line, AppFileLogger.isDiagnosticSummary(line))) return
            if (!drainScheduled) {
                drainScheduled = true
                handler.postDelayed(::drainBatch, DIAGNOSTIC_OUTPUT_BATCH_DELAY_MS)
            }
        }
    }

    private fun drainBatch() {
        queue.drain(DIAGNOSTIC_OUTPUT_BATCH_SIZE).forEach(::writeLine)
        synchronized(this) {
            if (queue.isEmpty()) {
                drainScheduled = false
            } else {
                handler.postDelayed(::drainBatch, DIAGNOSTIC_OUTPUT_BATCH_DELAY_MS)
            }
        }
    }

    fun flush(timeoutMs: Long = DIAGNOSTIC_OUTPUT_FLUSH_TIMEOUT_MS): Boolean {
        val latch = CountDownLatch(1)
        handler.post {
            queue.drainAll().forEach(::writeLine)
            synchronized(this) { drainScheduled = false }
            latch.countDown()
        }
        return runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    fun close(): Boolean = synchronized(writeLock) {
        closeResult?.let { return it }
        closed = true
        val flushed = flush()
        thread.quitSafely()
        (flushed && queue.isEmpty() && failedLines.get() == 0L).also { closeResult = it }
    }
}

/**
 * 诊断会话期间主线程上较慢的非帧消息.帧回调本身由 FrameMetrics 逐帧记录，这里只计数。
 * printer 只在主线程被调用；读取在诊断线程，存取环形缓冲时加锁。
 * 只保留 Handler/回调类名和耗时，不保留消息正文或 ID。
 */
/** New stages are registered here, never inferred from callback names or payloads. */
internal object StreamDiagnosticGapLabels {
    val stages: Set<String> = setOf(
        "main.uninstrumented", "main.nonReveal", "main.beforeFirst", "main.afterLast", "chat.content.commit", "list.measure", "list.place", "row.measure", "row.place", "row.draw", "settings.section.measure", "settings.section.draw",
    )
}

/** Main-thread schedstat. First field is on-CPU, second is run-queue wait. */
internal class MainThreadSchedstat(
    private val path: () -> String = { "/proc/self/task/${android.os.Process.myTid()}/schedstat" },
) : AutoCloseable {
    private var file: java.io.RandomAccessFile? = null
    private var failed = false
    // Three unsigned decimal counters fit in 63 bytes. Reuse a bounded buffer: readLine()
    // performs a separate native read for every byte on Android, twice per Looper dispatch.
    private val buffer = ByteArray(128)

    fun sample(): LongArray? {
        if (failed) return null
        return try {
            val opened = file ?: java.io.RandomAccessFile(path(), "r").also { file = it }
            opened.seek(0)
            parseMainThreadSchedstat(buffer, opened.read(buffer))
        } catch (_: Exception) {
            close()
            null
        }
    }

    override fun close() {
        failed = true
        val opened = file
        file = null
        runCatching { opened?.close() }
    }
}

/** Only accept two complete nonnegative counters; a short/malformed read stays unknown. */
internal fun parseMainThreadSchedstat(bytes: ByteArray, length: Int): LongArray? {
    if (length <= 0 || length > bytes.size) return null
    var offset = 0
    var first = 0L
    var second = 0L
    repeat(2) { field ->
        while (offset < length && bytes[offset].toInt() in 9..32) offset++
        val start = offset
        var value = 0L
        while (offset < length) {
            val digit = bytes[offset].toInt() - '0'.code
            if (digit !in 0..9) break
            if (value > (Long.MAX_VALUE - digit) / 10) return null
            value = value * 10 + digit
            offset++
        }
        // schedstat separates the second counter from a third one. Without a delimiter
        // we cannot distinguish a complete value from a short read, so never invent it.
        if (offset == start || offset == length || bytes[offset].toInt() !in 9..32) return null
        if (field == 0) first = value else second = value
    }
    return longArrayOf(first, second)
}

internal class MainThreadMessageLog(private val capacity: Int = MAIN_LOG_CAPACITY,
    private val onMessage: ((Long, Long, Boolean, Long, Long, Long, Long) -> Unit)? = null,
    private val cpuClock: (() -> Long)? = null,
    private val schedstat: (() -> LongArray?)? = null) {
    @Volatile var overwritten = 0L
        private set
    @Volatile var outputTruncated = 0L
        private set
    private val starts = LongArray(capacity)
    private val ends = LongArray(capacity)
    private val cpus = LongArray(capacity)
    private var openCpuNs = -1L
    private var openSchedRunNs = -1L
    private var openSchedRunnableNs = -1L
    private val schedRuns = LongArray(capacity) { -1L }
    private val schedRunnables = LongArray(capacity) { -1L }
    private val names = arrayOfNulls<String>(capacity)
    private var next = 0
    private var size = 0
    private var messageStartNs = 0L
    private var partialMessage: DiagnosticMainMessageRecord? = null
    /** Main-thread stop envelope: CPU is unknown because this dispatch never finished. */
    internal fun closeOpen(cutoffNs: Long) {
        if (!messageOpen) return
        val wall = (cutoffNs - messageStartNs).coerceAtLeast(0)
        partialMessage = DiagnosticMainMessageRecord(messageStartNs, cutoffNs,
            messageLine?.contains(CHOREOGRAPHER_FRAME_RECEIVER) == true,
            openCoveredNs.coerceIn(0, wall), openRevealNs.coerceIn(0, wall), -1, partial = true)
        messageOpen = false; messageLine = null
    }
    // 用显式标记而不是 0 表示“有起点”：System.nanoTime() 的原点是任意的。
    private var messageOpen = false
    private var messageLine: String? = null
    // 当前这条消息里被 measure 包住的主线程耗时，以及其中最长的那一段。
    private var openCoveredNs = 0L
    private var openRevealNs = 0L
    private var openTopStage: String? = null
    private var openTopNs = 0L
    private var openFirstBeginNs = 0L
    private var openLastEndNs = 0L
    private var openMeasured = false
    private val covered = LongArray(capacity)
    private val reveals = LongArray(capacity)
    private val isFrames = BooleanArray(capacity)
    private val tops = arrayOfNulls<String>(capacity)
    private val topStages = arrayOfNulls<String>(capacity)
    private val beforeFirst = LongArray(capacity) { -1L }
    private val afterLast = LongArray(capacity) { -1L }
    @Volatile var frameMessages = 0L
        private set
    @Volatile var otherMessages = 0L
        private set

    val printer = Printer { line -> onLine(line, System.nanoTime()) }

    internal fun onLine(line: String, now: Long) {
        if (line.startsWith(">>>>>")) {
            messageStartNs = now
            openCpuNs = cpuClock?.invoke() ?: -1L
            val startedSched = schedstat?.invoke()
            openSchedRunNs = startedSched?.getOrNull(0) ?: -1L
            openSchedRunnableNs = startedSched?.getOrNull(1) ?: -1L
            messageOpen = true
            messageLine = line
            openCoveredNs = 0L
            openRevealNs = 0L
            openTopStage = null
            openTopNs = 0L
            openFirstBeginNs = 0L
            openLastEndNs = 0L
            openMeasured = false
            return
        }
        if (!line.startsWith("<<<<<")) return
        val started = messageStartNs
        val open = messageOpen
        val name = messageLine
        messageOpen = false
        messageLine = null
        // 装上 printer 时正在处理的那条消息没有起点，忽略。
        if (!open || name == null) return
        val endedCpuNs = cpuClock?.invoke() ?: -1L
        val cpuNs = if (openCpuNs >= 0 && endedCpuNs >= openCpuNs) endedCpuNs - openCpuNs else -1L
        val endedSched = schedstat?.invoke()
        val schedRunNs = if (openSchedRunNs >= 0 && endedSched != null && endedSched[0] >= openSchedRunNs) {
            endedSched[0] - openSchedRunNs
        } else -1L
        val schedRunnableNs = if (openSchedRunnableNs >= 0 && endedSched != null && endedSched[1] >= openSchedRunnableNs) {
            endedSched[1] - openSchedRunnableNs
        } else -1L
        val isFrame = name.contains(CHOREOGRAPHER_FRAME_RECEIVER)
        if (isFrame) frameMessages++ else otherMessages++
        val duration = (now - started).coerceAtLeast(0)
        val coveredNs = openCoveredNs.coerceIn(0, duration)
        val revealNs = openRevealNs.coerceIn(0, coveredNs)
        val beforeFirstNs = if (openMeasured) (openFirstBeginNs - started).coerceAtLeast(0) else -1L
        val afterLastNs = if (openMeasured) (now - openLastEndNs).coerceAtLeast(0) else -1L
        onMessage?.invoke(started, now, isFrame, coveredNs, revealNs, beforeFirstNs, afterLastNs)
        if (duration < SLOW_MAIN_MESSAGE_NS) return
        synchronized(this) {
            starts[next] = started
            ends[next] = now
            cpus[next] = cpuNs
            schedRuns[next] = schedRunNs
            schedRunnables[next] = schedRunnableNs
            names[next] = name
            covered[next] = coveredNs
            reveals[next] = revealNs
            isFrames[next] = isFrame
            tops[next] = openTopStage?.let { "$it:${openTopNs / 1000}" }
            topStages[next] = openTopStage
            beforeFirst[next] = if (openMeasured) (openFirstBeginNs - started).coerceAtLeast(0) else -1L
            afterLast[next] = if (openMeasured) (now - openLastEndNs).coerceAtLeast(0) else -1L
            next = (next + 1) % capacity
            if (size < capacity) size++ else overwritten++
        }
    }

    /** 主线程上最外层 measure 结束时调用，计入当前这条消息。 */
    internal fun addCovered(stage: String, beginNs: Long, endNs: Long, ns: Long) {
        if (!messageOpen) return
        if (!openMeasured || beginNs < openFirstBeginNs) openFirstBeginNs = beginNs
        if (!openMeasured || endNs > openLastEndNs) openLastEndNs = endNs
        openMeasured = true
        openCoveredNs += ns
        if (ns > openTopNs) {
            openTopNs = ns
            openTopStage = stage
        }
    }

    /** Called once per outermost reveal scope, even when nested inside another measured stage. */
    internal fun addReveal(ns: Long) {
        if (messageOpen) openRevealNs += ns.coerceAtLeast(0)
    }

    /** Numeric-only slow dispatch envelopes; no Handler/Runnable name enters v2 output. */
    @Synchronized fun timingsBetween(fromNs: Long, toNs: Long): List<DiagnosticMainMessageRecord> {
        val out = ArrayList<DiagnosticMainMessageRecord>()
        val first = (next - size + capacity) % capacity
        for (i in 0 until size) {
            val slot = (first + i) % capacity
            if (ends[slot] <= fromNs || starts[slot] >= toNs) continue
            out += DiagnosticMainMessageRecord(starts[slot], ends[slot], isFrames[slot],
                covered[slot], reveals[slot], cpus[slot],
                schedRunNs = schedRuns[slot], schedRunnableNs = schedRunnables[slot],
                topStage = topStages[slot],
                beforeFirstNs = beforeFirst[slot], afterLastNs = afterLast[slot])
        }
        partialMessage?.takeIf { it.endNs >= fromNs && it.beginNs <= toNs }?.let { out += it }
        return out
    }

    /** 与 [fromNs, toNs] 有重叠的慢消息，时间相对 originNs。 */
    fun between(fromNs: Long, toNs: Long, originNs: Long, limit: Int): List<String> {
        val retained = limit.coerceIn(0, capacity)
        val snapshotStarts = LongArray(retained)
        val snapshotEnds = LongArray(retained)
        val snapshotCovered = LongArray(retained)
        val snapshotNames = arrayOfNulls<String>(retained)
        val snapshotTops = arrayOfNulls<String>(retained)
        var count = 0
        synchronized(this) {
            val first = (next - size + capacity) % capacity
            for (i in 0 until size) {
                val slot = (first + i) % capacity
                if (ends[slot] < fromNs || starts[slot] > toNs) continue
                if (count >= retained) { outputTruncated++; continue }
                snapshotStarts[count] = starts[slot]
                snapshotEnds[count] = ends[slot]
                snapshotCovered[count] = covered[slot]
                snapshotNames[count] = names[slot]
                snapshotTops[count] = tops[slot]
                count++
            }
        }
        // Formatting must not hold the ring lock needed by the main Looper writer.
        return List(count) { i ->
            var line = "atMs=${(snapshotStarts[i] - originNs) / 1_000_000} " +
                "durUs=${(snapshotEnds[i] - snapshotStarts[i]) / 1000} " +
                "msg=${toggleProbeMessageName(snapshotNames[i].orEmpty())}"
            if (snapshotCovered[i] > 0) {
                line += " coveredUs=${snapshotCovered[i] / 1000} top=${snapshotTops[i]}"
            }
            line
        }
    }
}

/**
 * 点开工具、推理或工作过程后的记录窗口：逐帧 FrameMetrics、每帧列表状态（跟底、上提、暂停上提、
 * 哨兵位置）、被点开那一项的可见高度与内容高度、测量与绘制耗时、分帧组合进度和跟底每步位移。
 * 同一窗口既用来看展开动画的形状，也用来看掉帧落在哪一段。
 */
internal class ToggleProbe(
    val kind: String,
    val expanded: Boolean,
    val token: Int,
    val generation: Int,
    val startNs: Long,
) {
    private val frames = ArrayList<String>(TOGGLE_PROBE_FRAMES)
    private val events = ArrayList<String>()
    private var lastSample: String? = null
    var droppedEvents = 0
        private set
    var maxFrameUs = 0L
        private set
    var missedFrames = 0
        private set
    @Volatile var finished = false

    /** 返回 true 表示窗口已满，应当收尾。 */
    @Synchronized fun addFrame(line: String, totalUs: Long = 0, missed: Boolean = false): Boolean {
        frames += line
        if (totalUs > maxFrameUs) maxFrameUs = totalUs
        if (missed) missedFrames++
        return frames.size >= TOGGLE_PROBE_FRAMES
    }

    @Synchronized fun addEvent(atNs: Long, tag: String, detail: String) {
        if (events.size >= TOGGLE_PROBE_MAX_EVENTS) {
            droppedEvents++
            return
        }
        events += "ev sinceTapMs=${sinceTapMs(atNs)} tag=$tag $detail"
    }

    /** 列表状态没变的帧不重复记。返回是否记下。 */
    @Synchronized fun addSample(frameNs: Long, state: String): Boolean {
        if (state == lastSample) return false
        lastSample = state
        if (events.size >= TOGGLE_PROBE_MAX_EVENTS) {
            droppedEvents++
            return false
        }
        events += "ev sinceTapMs=${sinceTapMs(frameNs)} tag=list $state"
        return true
    }

    private fun sinceTapMs(ns: Long): String {
        val tenths = (ns - startNs) / 100_000
        return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
    }

    fun expired(now: Long): Boolean = now - startNs >= TOGGLE_PROBE_MAX_NS

    @Synchronized fun report(sessionId: String, mainMessages: List<String>): List<String> {
        val prefix = "StreamDiag id=$sessionId toggle=$kind expanded=$expanded gen=$generation"
        return buildList {
            add(
                "$prefix frames=${frames.size} maxFrameUs=$maxFrameUs missed=$missedFrames " +
                    "events=${events.size} droppedEvents=$droppedEvents slowMessages=${mainMessages.size}"
            )
            frames.forEachIndexed { index, frame -> add("$prefix frame=$index $frame") }
            events.forEach { add("$prefix $it") }
            mainMessages.forEach { add("$prefix main $it") }
        }
    }
}

/** 取 Looper 日志里的 Handler 类名和回调类名，去掉对象哈希与 what 值以外的内容。 */
internal fun toggleProbeMessageName(line: String): String {
    val handler = line.substringAfter("(", "").substringBefore(")", "")
    val callback = line.substringAfter("} ", "").substringBefore(": ").substringBefore("@")
    // 回调名来自 Runnable.toString()，自定义 toString 可能带字段值，只留开头的类名。
    return "${toggleProbeClassName(handler)}/${toggleProbeClassName(callback)}".take(160)
}

/** 只留开头由字母、数字和 `_.$` 组成的类名部分。 */
internal fun toggleProbeClassName(raw: String): String = raw.takeWhile { it.isLetterOrDigit() || it in "_.$" }

// 120Hz 下约 1.2 秒，盖住 180ms 展开动画、跟底追赶和暂停上提的最长 1.5 秒里的大部分。
internal const val TOGGLE_PROBE_FRAMES = 150
internal const val TOGGLE_PROBE_MAX_NS = 1_250_000_000L
// 每帧最多约 6 条（两层测量、两层绘制、列表、跟底），150 帧再留余量。
internal const val TOGGLE_PROBE_MAX_EVENTS = 1_200
internal const val TOGGLE_PROBE_MAX_MESSAGES = 60
internal const val SLOW_MAIN_MESSAGE_NS = 4_000_000L
internal const val MAIN_LOG_CAPACITY = 256
internal const val DIAGNOSTIC_OUTPUT_CAPACITY = 2048
internal const val DIAGNOSTIC_OUTPUT_BATCH_SIZE = 64
internal const val DIAGNOSTIC_OUTPUT_BATCH_DELAY_MS = 20L
internal const val DIAGNOSTIC_OUTPUT_FLUSH_TIMEOUT_MS = 500L
private const val CHOREOGRAPHER_FRAME_RECEIVER = "Choreographer\$FrameDisplayEventReceiver"
// 点击前一帧也收进来，看点击之前主线程是否已经在忙。
private const val FRAME_PROBE_LEAD_NS = 17_000_000L
// 点击窗口以外超过这个值的帧单独记一条，每个五秒报告窗口最多 SPIKE_MAX_PER_WINDOW 条。
internal const val SPIKE_FRAME_NS = 33_000_000L
internal const val UNKNOWN_DELAY_DETAIL_NS = 8_000_000L
private const val SPIKE_MAX_PER_WINDOW = 40
private const val SPIKE_LOOKBACK_NS = 200_000_000L
internal const val NOTE_MAX_PER_SESSION = 120
internal const val SLOW_STAGE_NS = 16_000_000L
internal const val STREAM_DIAGNOSTIC_STAGE_LIMIT = 512
private val diagnosticVersionPattern = Regex("[0-9]{1,4}(?:\\.[0-9]{1,4}){1,3}")
internal fun diagnosticVersionName(version: String?): String =
    version?.takeIf { diagnosticVersionPattern.matches(it) } ?: "unknown"

/** One visible chat window. Logging is on its worker; hot paths only update bounded counters. */
internal object StreamPerformanceDiagnostics {
    internal class Session(private val clock: () -> Long = System::nanoTime) {
        val id = UUID.randomUUID().toString().take(8)
        val started = clock()
        private var stats = linkedMapOf<String, StreamTimingStats>()
        val serial = sessionSerial.incrementAndGet()
        val tokens = AnonymousDiagnosticTokens()
        val eventLinks = DiagnosticEventLinks()
        val rowTokens = DiagnosticRowTokens()
        val listSamples = DiagnosticListSamples()
        val details = BoundedDiagnosticDetails(started, admissionLock = this)
        val sequences = AtomicLong()
        val observerCosts = DiagnosticObserverCosts()
        val threadIds = DiagnosticThreadIds()
        @Volatile var stopCutoffNs: Long? = null
            private set
        private var callbackRejected = 0L
        private var openAtStop = 0L
        private var openIdsAtStop: List<Long> = emptyList()
        private val openIds = linkedSetOf<Long>()
        private var openIdDropped = 0L
        @Synchronized fun stopAdmission(): Long {
            return stopCutoffNs ?: clock().also {
                stopCutoffNs = it; openAtStop = openSpans; openIdsAtStop = openIds.toList()
            }
        }
        @Synchronized fun admitFrame(intendedNs: Long, loggingAllowed: Boolean = true): Boolean {
            if (!loggingAllowed || closed || stopCutoffNs?.let { intendedNs > it } == true) { callbackRejected++; return false }
            return true
        }
        @Synchronized fun pendingSpans(): Long = openSpans
        var timeline: FramePageTimeline? = null
        private var invalidLabels = 0L
        private var pageStats = linkedMapOf<Pair<Int, String>, StreamTimingStats>()
        @Volatile var closed = false
            private set
        private var openSpans = 0L
        private var closedRejectedRecords = 0L
        private var lateSpans = 0L
        private val notes = DiagnosticNoteBudget(NOTE_MAX_PER_SESSION)
        @Synchronized fun reserveNote(): Int? = if (closed) null else notes.reserve()
        @Synchronized fun rejectLabel() {
            if (closed) closedRejectedRecords++ else invalidLabels++
        }
        @Synchronized fun recordPage(page: Int, stage: String, ns: Long, value: Long) {
            if (closed) { closedRejectedRecords++; return }
            addPage(page, stage, ns, value)
        }
        private fun addPage(page: Int, stage: String, ns: Long, value: Long) {
            val key = page to stage
            val stat = pageStats[key]
            if (stat != null) stat.add(ns, value)
            else if (pageStats.size < 512) pageStats[key] = StreamTimingStats().also { it.add(ns, value) }
            else droppedStageRecords++
        }
        private var lastDeltaNs = 0L
        private var droppedStageRecords = 0L
        @Synchronized fun record(stage: String, ns: Long, value: Long) {
            if (closed) { closedRejectedRecords++; return }
            if (stage == "ui.delta.received") {
                val now = clock()
                if (lastDeltaNs != 0L) add("ui.delta.gap", now - lastDeltaNs, 0)
                lastDeltaNs = now
            }
            add(stage, ns, value)
        }
        @Synchronized fun recordMetric(page: Int, stage: String, ns: Long, value: Long) {
            if (closed) { closedRejectedRecords++; return }
            add(stage, ns, value); addPage(page, stage, ns, value)
        }
        @Synchronized fun beginSpan(): Long? {
            if (closed || stopCutoffNs != null) { closedRejectedRecords++; return null }
            openSpans++
            val id = sequences.incrementAndGet()
            if (openIds.size < 512) openIds.add(id) else openIdDropped++
            return id
        }
        /** One admission for aggregate + page + detail, including completion after a periodic cutoff. */
        @Synchronized fun finishSpan(
            stage: String, span: Long, parent: Long, beginNs: Long, endNs: Long, thread: Long, main: Boolean,
            attribution: StreamDiagnosticAttribution?, page: Int, pageEnd: Int, aggregatePage: Int, value: Long,
        ) {
            openSpans--
            openIds.remove(span)
            if (closed) { lateSpans++; closedRejectedRecords++; return }
            if (stopCutoffNs?.let { endNs > it } == true) lateSpans++
            val elapsed = endNs - beginNs
            record(stage, elapsed, value); addPage(aggregatePage, stage, elapsed, value)
            details.span(stage, span, parent, beginNs, endNs, thread, main, attribution, page, pageEnd, value)
        }
        // Caller holds the shared admission lock. Known stages still update after saturation.
        private fun add(stage: String, ns: Long, value: Long) {
            val existing = stats[stage]
            if (existing != null) existing.add(ns, value)
            else if (stats.size < STREAM_DIAGNOSTIC_STAGE_LIMIT) stats[stage] = StreamTimingStats().also { it.add(ns, value) }
            else droppedStageRecords++
        }
        /** The clock is read INSIDE the shared lock. Returned stats are detached, never mutated again. */
        @Synchronized fun snapshot(final: Boolean): SessionSnapshot {
            val raw = details.snapshot(stopCutoffNs ?: clock(), final)
            if (final) closed = true
            val result = SessionSnapshot(raw, stats, pageStats, droppedStageRecords, invalidLabels,
                openSpans, closedRejectedRecords, lateSpans, notes.dropped,
                openIds.toList(), openIdDropped, callbackRejected, stopCutoffNs, openAtStop, openIdsAtStop)
            stats = linkedMapOf(); pageStats = linkedMapOf(); droppedStageRecords = 0; invalidLabels = 0
            return result
        }
        fun report(final: Boolean): List<String> = snapshot(final).report(id, started, final)
        @Synchronized fun closedCounts(): Pair<Long, Long> = closedRejectedRecords to lateSpans
    }

    internal data class SessionSnapshot(
        val rawDetails: DiagnosticRawDetailSnapshot,
        val stats: Map<String, StreamTimingStats>, val pages: Map<Pair<Int, String>, StreamTimingStats>,
        val dropped: Long, val invalid: Long, val openSpans: Long,
        val closedRejectedRecords: Long, val lateSpans: Long, val noteDropped: Long,
        val openSpanIds: List<Long>, val openIdDropped: Long, val callbackRejected: Long, val cutoffNs: Long?,
        val openAtStop: Long, val openIdsAtStop: List<Long>,
    ) {
        /** Formatting and output must only be called after releasing the session lock. */
        fun report(id: String, started: Long, final: Boolean): List<String> {
            val prefix = "StreamDiag id=$id final=$final elapsedMs=${(rawDetails.toNs-started)/1_000_000} scope=window " +
                "windowStartNs=${rawDetails.fromNs} windowEndNs=${rawDetails.toNs} boundary=admissionSnapshot"
            return buildList {
                stats.forEach { (stage, stat) -> add("$prefix stage=$stage ${stat.summary()}") }
                pages.forEach { (key, stat) -> add("$prefix page=${FrameDiagnosticPage.entries[key.first].name} stage=${key.second} ${stat.summary()}") }
                if (dropped > 0) add("$prefix stage=diagnostic.stageOverflow droppedRecords=$dropped")
                if (invalid > 0) add("$prefix rejectedLabels=$invalid")
                if (isEmpty()) add("$prefix empty=1")
            }
        }
    }

    @Volatile private var active: Session? = null
    // Compose observable only on attach/detach, not on any record/frame.
    val sessionGeneration = mutableLongStateOf(0L)
    internal fun publishSession(session: Session?) { active = session; sessionGeneration.longValue++ }
    internal data class StopResult(val session: Long, val finalFlushed: Boolean, val reason: String)
    @Volatile var lastStop: CompletableFuture<StopResult>? = null
        private set
    private var activeStop: (() -> CompletableFuture<StopResult>)? = null
    /** Main-thread request; completion is worker-side. Never await/sleep on the UI thread. */
    fun requestSafeStop(): CompletableFuture<StopResult>? {
        check(Looper.myLooper() === Looper.getMainLooper())
        return activeStop?.invoke() ?: lastStop
    }
    @Volatile private var probe: ToggleProbe? = null
    private var probeReporter: ((ToggleProbe) -> Unit)? = null
    private var probeTimeoutHandler: Handler? = null
    @Volatile private var mainLog: MainThreadMessageLog? = null
    @Volatile private var noteSink: ((String, () -> String) -> Unit)? = null

    /** Capture exactly once. Closing / disabling after this point cannot redirect a write. */
    private fun enabledSession(): Session? {
        val session = active ?: return null
        return session.takeIf { StreamDiagnosticControl.allowed && AppFileLogger.isEnabled() && !it.closed && it.stopCutoffNs == null }
    }

    /** 当前是否有诊断会话（日志已开启且界面在前台）。 */
    val enabled: Boolean get() = enabledSession() != null

    /** Generation-safe token for bounded recorders, null for disabled or closed sessions. */
    fun currentSessionToken(): Long? = enabledSession()?.serial

    /**
     * 写一条单独的诊断行，每个会话最多 [NOTE_MAX_PER_SESSION] 条。没有会话时不拼字符串。
     * 调用方自己控制频率，只在状态变化时调用。
     */
    fun note(tag: String, detail: () -> String) {
        val sink = noteSink ?: return
        sink(tag, detail)
    }
    private var nextToken = 0
    private var generation = 0

    /** 当前是否有点击窗口在记录；热路径先查这个，避免没在记的时候拼字符串。 */
    val probing: Boolean get() = enabled && probe?.finished == false

    /**
     * 每次点击加一。列表用 snapshotFlow 订阅它来启动逐帧采样，
     * 不在组合里读，避免点击那一帧让整页会话重组。
     */
    val probeRequests = mutableIntStateOf(0)

    /**
     * 在主线程的点击回调里调用。系统追踪开启时先记录轻量点击标记；
     * 没有前台诊断会话时仍返回 0，不启动重型探针。
     * 上一次点击的窗口还没收完就再点，先把上一次的结果写出来。
     * 返回这次点击的 token，被点开的那一项用它把自己的高度和组合进度记进同一个窗口。
     */
    fun markToggle(kind: String, expanded: Boolean): Int {
        // The point marker also works in idle chats; it does not attach the stream monitor.
        traceChatToggle(kind, expanded)
        if (!enabled) return 0
        val reporter = probeReporter ?: return 0
        probe?.let { finishProbe(it, reporter) }
        val next = synchronized(this) {
            nextToken = if (nextToken == Int.MAX_VALUE) 1 else nextToken + 1
            ToggleProbe(kind, expanded, nextToken, generation, System.nanoTime()).also { probe = it }
        }
        // 点击后没有新帧也要收尾。
        probeTimeoutHandler?.postDelayed({ finishProbe(next, reporter) }, TOGGLE_PROBE_MAX_NS / 1_000_000 + 100)
        probeRequests.intValue++
        return next.token
    }

    /** 被点开那一项或列表记一条事件。token 不是当前窗口时丢弃。 */
    fun probeEvent(token: Int, tag: String, detail: String) {
        val target = probe ?: return
        if (target.finished || token == 0 || target.token != token) return
        target.addEvent(System.nanoTime(), tag, detail)
    }

    /** 不属于某一项的事件（跟底、暂停上提、分帧组合），记进当前窗口。 */
    fun probeNote(tag: String, detail: () -> String) {
        val target = probe ?: return
        if (target.finished) return
        target.addEvent(System.nanoTime(), tag, detail())
    }

    /** 列表每帧的状态，没变就不记。 */
    fun probeListSample(frameNs: Long, state: () -> String) {
        val target = probe ?: return
        if (target.finished) return
        target.addSample(frameNs, state())
    }

    private fun finishProbe(target: ToggleProbe, reporter: (ToggleProbe) -> Unit) {
        synchronized(this) {
            if (target.finished) return
            target.finished = true
            if (probe === target) probe = null
        }
        reporter(target)
    }

    private val sessionSerial = AtomicLong()
    private val context = DiagnosticThreadContext()
    private val cachedOsTid = ThreadLocal.withInitial { runCatching { android.os.Process.myTid() }.getOrDefault(-1) }

    fun captureAttribution(): StreamDiagnosticAttribution? {
        val session = enabledSession() ?: return null
        return context.capture()?.takeIf { it.session == session.serial }
    }

    /** Existing business identity in, anonymous context out. Never persist raw identity. */
    fun attribution(runId: String? = null, conversationId: String? = null, selected: Boolean? = null,
        kind: String = "unknown", replay: Boolean = false, event: Long = 0): StreamDiagnosticAttribution? {
        val session = enabledSession() ?: return null
        return attributionFor(session, runId, conversationId, selected, kind, replay, event)
    }

    private fun attributionFor(session: Session, runId: String? = null, conversationId: String? = null,
        selected: Boolean? = null, kind: String = "unknown", replay: Boolean = false,
        event: Long = 0): StreamDiagnosticAttribution =
        StreamDiagnosticAttribution(session.serial, session.tokens.token(runId), session.tokens.token(conversationId),
            when (selected) { true -> DiagnosticVisibility.Selected; false -> DiagnosticVisibility.Hidden; null -> DiagnosticVisibility.Unknown },
            StreamDiagnosticLabels.kind(kind), replay, event)

    fun newEventAttribution(runId: String?, replay: Boolean): StreamDiagnosticAttribution? {
        val session = enabledSession() ?: return null
        return attributionFor(session, runId, replay = replay, event = session.sequences.incrementAndGet())
    }

    fun eventAttribution(event: Any, runId: String?, conversationId: String?, selected: Boolean?,
        kind: String, replay: Boolean): StreamDiagnosticAttribution? {
        val session = enabledSession() ?: return null
        val linked = session.eventLinks.find(event)
        val current = context.capture()?.takeIf { it.session == session.serial }
        val owner = attributionFor(session, runId, conversationId, selected, kind, replay)
        val base = linked ?: current?.takeIf { it.event != 0L }
            ?: owner.copy(event = session.sequences.incrementAndGet())
        return base.copy(run = owner.run.takeIf { it != 0 } ?: base.run, conversation = owner.conversation.takeIf { it != 0 } ?: base.conversation,
            visibility = if (selected == null) base.visibility else owner.visibility,
            kind = owner.kind, replay = replay).also { session.eventLinks.bind(event, it) }
    }

    fun bindEvent(event: Any, attribution: StreamDiagnosticAttribution?) {
        val session = enabledSession() ?: return
        if (attribution?.session == session.serial) session.eventLinks.bind(event, attribution)
    }

    fun <T> withAttribution(attribution: StreamDiagnosticAttribution?, block: () -> T): T {
        val session = enabledSession() ?: return block()
        if (attribution == null || attribution.session != session.serial) return block()
        val parent = context.current()?.span ?: attribution.sourceSpan
        return context.with(DiagnosticSpanContext(attribution, parent,
            insideReveal = context.current()?.insideReveal ?: false,
            insideMeasure = context.current()?.insideMeasure ?: false), block)
    }

    /** Render pseudonyms are separate from the run/conversation token budget. No raw key is emitted. */
    fun listAttribution(listId: Long): StreamDiagnosticAttribution? {
        val session = enabledSession() ?: return null
        return StreamDiagnosticAttribution(session.serial, list = listId)
    }

    fun rowAttribution(list: Long, key: Any, type: String): StreamDiagnosticAttribution? {
        val session = enabledSession() ?: return null
        return StreamDiagnosticAttribution(session.serial, list = list, row = session.rowTokens.token(key),
            rowType = diagnosticRowType(type))
    }

    fun blockAttribution(row: StreamDiagnosticAttribution?, index: Int, type: String, chars: Int): StreamDiagnosticAttribution? {
        val session = enabledSession() ?: return null
        if (row == null || row.session != session.serial) return null
        return row.copy(block = index.coerceAtLeast(0), blockType = diagnosticBlockType(type), blockChars = chars.coerceAtLeast(0))
    }

    /** Overlay render identity without inventing an event-to-frame causal link or replacing a live parent span. */
    fun <T> withRenderAttribution(attribution: StreamDiagnosticAttribution?, block: () -> T): T {
        val session = enabledSession() ?: return block()
        if (attribution == null || attribution.session != session.serial) return block()
        val inherited = context.current()?.attribution?.takeIf { it.session == session.serial }
        val merged = inherited?.copy(list = attribution.list, row = attribution.row, rowType = attribution.rowType,
            block = attribution.block, blockType = attribution.blockType, blockChars = attribution.blockChars,
            component = attribution.component) ?: attribution
        return withAttribution(merged, block)
    }

    fun componentAttribution(component: String): StreamDiagnosticAttribution? {
        val session = enabledSession() ?: return null
        return StreamDiagnosticAttribution(session.serial, component = diagnosticComponentType(component))
    }

    fun recordListGeometry(list: Long, state: androidx.compose.foundation.lazy.LazyListState, messageCount: Int) {
        val session = enabledSession() ?: return
        val info = state.layoutInfo
        val rows = info.visibleItemsInfo.take(FRAME_LIST_MAX_ROWS).map {
            DiagnosticListRow(session.rowTokens.token(it.key), it.index, it.offset, it.size)
        }
        val now = System.nanoTime()
        val source = session.timeline?.attribute(now, now)
        session.listSamples.add(DiagnosticListSnapshot(now, list, messageCount, info.totalItemsCount,
            state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, info.viewportStartOffset,
            info.viewportEndOffset, info.visibleItemsInfo.size, rows,
            (source?.start ?: FrameDiagnosticPage.Unknown).ordinal, source?.startSegment ?: 0))
    }

    fun record(stage: String, ns: Long = 0, value: Long = 0) {
        val session = enabledSession() ?: return
        val label = StreamDiagnosticLabels.canonicalStage(stage) ?: run { session.rejectLabel(); return }
        session.record(label, ns, value)
    }

    /** A stale recorder must never flush into the next foreground generation. */
    fun recordForSession(expectedSession: Long, stage: String, ns: Long = 0, value: Long = 0) {
        val session = enabledSession() ?: return
        if (session.serial != expectedSession) return
        val label = StreamDiagnosticLabels.canonicalStage(stage) ?: run { session.rejectLabel(); return }
        session.record(label, ns, value)
    }

    fun <T> measureDetail(stage: String, value: Long = 0, block: () -> T): T = measure(stage, value, block)

    fun <T> measure(stage: String, value: Long = 0, block: () -> T): T {
        val session = enabledSession() ?: return block()
        val label = StreamDiagnosticLabels.canonicalStage(stage) ?: run { session.rejectLabel(); return block() }
        val previous = context.current()
        val span = session.observerCosts.observe(DiagnosticObserverCosts.Phase.Enter) {
            session.threadIds.register(Thread.currentThread().id, cachedOsTid.get())
            session.beginSpan()
        } ?: return block()
        val parent = previous?.span ?: 0L
        val attr = previous?.attribution?.takeIf { it.session == session.serial }
        val onMain = Looper.myLooper() === Looper.getMainLooper()
        val started = System.nanoTime()
        Trace.beginSection("Eta.$label")
        val revealScope = label.startsWith("reveal.")
        try { return context.with(DiagnosticSpanContext(attr, span,
            insideReveal = revealScope || previous?.insideReveal == true, insideMeasure = true), block) } finally {
            Trace.endSection()
            val ended = System.nanoTime()
            val elapsed = ended - started
            val page = session.timeline?.attribute(started, ended)
            val aggregatePage = page?.aggregatePage ?: FrameDiagnosticPage.Unknown
            session.observerCosts.observe(DiagnosticObserverCosts.Phase.Finish) {
                session.finishSpan(label, span, parent, started, ended, Thread.currentThread().id, onMain, attr,
                    (page?.start ?: FrameDiagnosticPage.Unknown).ordinal, (page?.end ?: FrameDiagnosticPage.Unknown).ordinal,
                    aggregatePage.ordinal, value)
            }
            if (onMain && enabledSession() === session) {
                // Only synchronous outermost scopes count; nested inclusive spans are not added twice.
                if (previous?.insideMeasure != true) mainLog?.addCovered(label, started, ended, elapsed)
                if (revealScope && previous?.insideReveal != true) mainLog?.addReveal(elapsed)
            }
        }
    }

    fun attach(window: Window, pages: FramePageTimeline): () -> Unit {
        val session = Session().also { it.timeline = pages }
        publishSession(session)
        val completion = CompletableFuture<StopResult>()
        val loggerRejectedStart = AppFileLogger.diagnosticRejected.get()
        val loggerFailedStart = AppFileLogger.diagnosticFailed.get()
        val thread = HandlerThread("Eta-StreamDiag").apply { start() }
        val handler = Handler(thread.looper)
        val output = AsyncDiagnosticOutput(enabled = { StreamDiagnosticControl.asyncOutputEnabled })
        // 上一个会话留下的窗口先收掉。
        probe?.let { previous -> probeReporter?.let { finishProbe(previous, it) } }
        // 整个诊断会话都记主线程慢消息：点击窗口和窗口外的尖峰都要能对上当时主线程在干什么。
        // 这是项目里唯一设置 Looper 日志的地方；会话结束时恢复为 null。
        val mainThreadSchedstat = MainThreadSchedstat()
        val log = MainThreadMessageLog(onMessage = { begin, end, frame, covered, reveal, before, after ->
            if (!session.closed && AppFileLogger.isEnabled()) {
                session.record(if (frame) "main.doFrame" else "main.message", end - begin, 0)
                // These are subsets of dispatch wall time, not extra frame components or CPU time.
                session.record("main.uninstrumented", (end - begin - covered).coerceAtLeast(0), 0)
                session.record("main.nonReveal", (end - begin - reveal).coerceAtLeast(0), 0)
                if (frame && before >= 0 && after >= 0) {
                    session.record("main.beforeFirst", before, 0)
                    session.record("main.afterLast", after, 0)
                }
            }
        }, cpuClock = { Debug.threadCpuTimeNanos() }, schedstat = { mainThreadSchedstat.sample() })
        mainLog = log
        Looper.getMainLooper().setMessageLogging(Printer { line ->
            if (active === session && enabled) log.onLine(line, System.nanoTime())
        })
        synchronized(this) { generation++ }
        val sessionGeneration = generation
        val reportProbe: (ToggleProbe) -> Unit = { target ->
            handler.post {
                runCatching {
                    val lead = target.startNs - FRAME_PROBE_LEAD_NS
                    val messages = log.between(lead, System.nanoTime(), target.startNs, TOGGLE_PROBE_MAX_MESSAGES)
                    target.report(session.id, messages).forEach(output::write)
                }
            }
        }
        probeReporter = reportProbe
        probeTimeoutHandler = handler
        noteSink = fun(tag: String, detail: () -> String) {
            if (enabledSession() !== session) return
            val reserved = session.reserveNote() ?: return
            val line = "$tag ${detail()}"
            handler.post {
                runCatching {
                    output.write("StreamDiag id=${session.id} gen=$sessionGeneration note=$reserved $line")
                }
            }
        }
        val packageName = window.context.packageName
        // Package lookup and all formatting happen once on the diagnostic worker.
        var packageIdentity = "package=$packageName versionCode=unknown versionName=unknown buildType=${BuildConfig.BUILD_TYPE} gitSha=${BuildConfig.GIT_SHA}"
        handler.post {
            runCatching {
                val info = window.context.packageManager.getPackageInfo(packageName, 0)
                val version = diagnosticVersionName(info.versionName)
                packageIdentity = "package=$packageName versionCode=${info.longVersionCode} versionName=$version buildType=${BuildConfig.BUILD_TYPE} gitSha=${BuildConfig.GIT_SHA}"
            }
        }
        var previousGc = emptyMap<String, Long>()
        var previousFrameMessages = 0L
        var previousOtherMessages = 0L
        fun emit(final: Boolean, closedSnapshot: SessionSnapshot? = null): Boolean {
            val outputStarted = System.nanoTime()
            val result = runCatching {
                val snapshot = closedSnapshot ?: run {
                    val runtime = Runtime.getRuntime()
                    session.record("heap.usedBytes", 0, runtime.totalMemory() - runtime.freeMemory())
                    session.record("main.frameMessages", 0, log.frameMessages - previousFrameMessages)
                    session.record("main.otherMessages", 0, log.otherMessages - previousOtherMessages)
                    previousFrameMessages = log.frameMessages; previousOtherMessages = log.otherMessages
                    if (session.closed) return false
                    session.observerCosts.observe(DiagnosticObserverCosts.Phase.Snapshot) {
                        session.snapshot(final)
                    }
                }
                val detail = snapshot.rawDetails.select()
                Trace.beginSection("Eta.diagnostic.clockSync")
                val now = System.nanoTime()
                val anchorBootNs = SystemClock.elapsedRealtimeNanos()
                val anchorUptimeMs = SystemClock.uptimeMillis()
                val anchorUncertaintyNs = System.nanoTime() - now
                Trace.endSection()
                val prefix = "StreamDiag id=${session.id} windowStartNs=${detail.fromNs} windowEndNs=${detail.toNs} " +
                    "boundary=admissionSnapshot final=$final"
                val windowLine = "$prefix v=2 type=window anchorNanoNs=$now uptimeMs=$anchorUptimeMs " +
                    "elapsedRealtimeNs=$anchorBootNs anchorUncertaintyNs=$anchorUncertaintyNs " +
                    "osPid=${android.os.Process.myPid()} javaThreadId=${Thread.currentThread().id} osTid=${cachedOsTid.get()} $packageIdentity " +
                    "duration=inclusive heap=proxyNotAllocationStack gcTime=runtimeCounterNotPause " +
                    "spanCapacity=2048 slowBudget=512 frameBudget=120 ringOverwritten=${detail.overwritten} " +
                    "protectedSpanCapacity=$FRAME_PROTECTED_SPAN_CAPACITY protectedBudgetDropped=${detail.protectedBudgetDropped} " +
                    "frameCaptureTruncated=${detail.frameCaptureTruncated} previousWindowSpans=${detail.previousWindowSpans} " +
                    "rowTokenSaturated=${session.rowTokens.saturated} listSampleOverwritten=${session.listSamples.overwritten} " +
                    "slowBudgetDropped=${detail.slowBudgetDropped} frameBudgetDropped=${detail.frameBudgetDropped} frameBudgetEvicted=${detail.frameBudgetEvicted} " +
                    "spanOutputTruncated=${detail.spanOutputTruncated} tokenSaturated=${session.tokens.saturated} ${session.threadIds.fields()} " +
                    "eventLinksOverwritten=${session.eventLinks.overwritten} mainRingOverwritten=${log.overwritten} " +
                    "mainOutputTruncated=${log.outputTruncated} noteBudgetDropped=${snapshot.noteDropped} essentialOutputDropped=${output.essentialDropped} " +
                    "admission=${if (final) "closed" else "open"} openSpansAtCutoff=${snapshot.openSpans} " +
                    "closedRejectedRecords=${snapshot.closedRejectedRecords} lateSpans=${snapshot.lateSpans} " +
                    "callbackRejected=${snapshot.callbackRejected} openAtStop=${snapshot.openAtStop} openIdDropped=${snapshot.openIdDropped} " +
                    "stopCutoffNs=${snapshot.cutoffNs ?: -1} partial=${snapshot.openAtStop > 0 || snapshot.openSpans > 0} " +
                    "lateAfterFinal=notTracked completeCpu=notClaimed"
                output.write(windowLine)
                snapshot.openIdsAtStop.forEach { spanId ->
                    output.write("$prefix v=2 type=openSpan span=$spanId partial=true atCutoff=true " +
                        "stillOpenAtFinal=${spanId in snapshot.openSpanIds} cpuNs=unknown")
                }
                session.observerCosts.summary().forEach {
                    output.write("$prefix v=2 type=observerCost $it")
                }
                val runtimeStats = runCatching { Debug.getRuntimeStats() }.getOrNull()
                val gcKeys = listOf("art.gc.gc-count", "art.gc.gc-time", "art.gc.bytes-allocated", "art.gc.bytes-freed",
                    "art.gc.blocking-gc-count", "art.gc.blocking-gc-time")
                val currentGc = linkedMapOf<String, Long>()
                for (key in gcKeys) {
                    val value = runtimeStats?.get(key)?.toLongOrNull()
                    if (value == null) output.write("$prefix v=2 type=runtime runtimeCounter=$key supported=false")
                    else {
                        currentGc[key] = value
                        val previous = previousGc[key]
                        val delta = if (previous != null && value >= previous) (value - previous).toString() else "unknown"
                        output.write("$prefix v=2 type=runtime runtimeCounter=$key supported=true cumulative=$value delta=$delta")
                    }
                }
                previousGc = currentGc
                snapshot.report(session.id, session.started, final).forEach(output::write)
                detail.spans.forEach { span ->
                    val a = span.attribution
                    output.write("$prefix v=2 type=span span=${span.span} parent=${span.parent} stage=${span.stage} " +
                        "beginNs=${span.beginNs} endNs=${span.endNs} thread=${span.thread} javaThreadId=${span.thread} " +
                        "osTid=${session.threadIds.osTid(span.thread)} osTidUnknown=-1 main=${span.main} " +
                        "partialAtCutoff=${snapshot.cutoffNs?.let { span.endNs > it } == true} " +
                        "duration=inclusive value=${span.value} runToken=${a?.run ?: 0} conversationToken=${a?.conversation ?: 0} " +
                        "visibility=${a?.visibility ?: DiagnosticVisibility.Unknown} kind=${a?.kind ?: "unknown"} " +
                        "replay=${a?.replay ?: false} eventSeq=${a?.event ?: 0} sourceSpan=${a?.sourceSpan ?: 0} " +
                        "page=${FrameDiagnosticPage.entries[span.page].name} pageEnd=${FrameDiagnosticPage.entries[span.pageEnd].name} " +
                        "${diagnosticRenderFields(a)}")
                }
                log.timingsBetween(detail.fromNs, detail.toNs).forEach { message ->
                    output.write("$prefix v=2 type=mainMessage beginNs=${message.beginNs} endNs=${message.endNs} " +
                        "frameDispatch=${message.frameDispatch} partial=${message.partial} coveredNs=${message.coveredNs} revealNs=${message.revealNs} " +
                        "uninstrumentedNs=${message.uninstrumentedNs} nonRevealNs=${message.nonRevealNs} " +
                        "beforeFirstNs=${message.beforeFirstNs} afterLastNs=${message.afterLastNs} " +
                        "cpuNs=${message.cpuNs} wallMinusCpuNs=${if (message.cpuNs >= 0) (message.endNs - message.beginNs - message.cpuNs).coerceAtLeast(0) else -1} " +
                        "${message.schedstatFields()} " +
                        "cpuAccounting=threadCpuCounterNotBlockedDiagnosis accounting=dispatchSubsetsNotAdditive")
                }
                detail.frames.forEachIndexed { index, frame ->
                    val page = FramePageAttribution(FrameDiagnosticPage.entries[frame.page], FrameDiagnosticPage.entries[frame.pageEnd], frame.changed)
                    output.write("$prefix v=2 type=frame abnormalFrame=$index ${page.fields()} intendedVsyncNs=${frame.intendedNs} " +
                        "vsyncNs=${frame.vsyncNs} totalNs=${frame.totalNs} deadlineNs=${frame.deadlineNs} deadlineMiss=${frame.missed} " +
                        "firstDraw=${frame.firstDraw} partialAtCutoff=${snapshot.cutoffNs?.let { frame.intendedNs + frame.totalNs > it } == true} pageSegment=${frame.pageSegment} metricsDropped=${frame.metricsDropped} unknownNs=${frame.unknownNs} inputNs=${frame.inputNs} animationNs=${frame.animationNs} " +
                        "layoutNs=${frame.layoutNs} drawNs=${frame.drawNs} syncNs=${frame.syncNs} commandNs=${frame.commandNs} " +
                        "swapNs=${frame.swapNs} gpuNs=${frame.gpuNs} unaccountedNs=${frame.unaccountedNs} " +
                        "overlapNs=${frame.overlapNs} vsyncLateNs=${frame.vsyncLateNs} " +
                        "detailCaptured=${frame.detailCaptured} accounting=frameMetricsResidualNotAdditive")
                    if (!frame.detailCaptured) {
                        output.write("$prefix v=2 type=frameCorrelation abnormalFrame=$index " +
                            "detailCaptured=false capture=notSampled evidenceIncomplete=true evidenceComplete=notClaimed")
                        return@forEachIndexed
                    }
                    val messages = log.between(frame.intendedNs - SPIKE_LOOKBACK_NS, frame.intendedNs + frame.totalNs, frame.intendedNs, 12)
                    messages.forEach { output.write("$prefix abnormalFrame=$index main $it") }
                    frame.mainMessages.take(24).forEach { message ->
                        val overlap = diagnosticOverlapNs(message.beginNs, message.endNs, frame.intendedNs, frame.intendedNs + frame.totalNs)
                        output.write("$prefix v=2 type=frameMainMessage abnormalFrame=$index " +
                            "beginNs=${message.beginNs} endNs=${message.endNs} overlapNs=$overlap " +
                            "relation=${if (overlap > 0) "overlap" else "preceding"} frameDispatch=${message.frameDispatch} " +
                            "coveredNs=${message.coveredNs} uninstrumentedNs=${message.uninstrumentedNs} " +
                            "topStage=${message.topStage ?: "none"} " +
                            "beforeFirstNs=${message.beforeFirstNs} afterLastNs=${message.afterLastNs} " +
                            "cpuNs=${message.cpuNs} wallMinusCpuNs=${if (message.cpuNs >= 0) (message.endNs - message.beginNs - message.cpuNs).coerceAtLeast(0) else -1} " +
                            "${message.schedstatFields()} " +
                            "accounting=dispatchWallNotFrameParts cpuAccounting=threadCpuCounterNotBlockedDiagnosis " +
                            "omitted=${(frame.mainMessages.size - 24).coerceAtLeast(0)}")
                    }
                    val correlation = correlateDiagnosticFrame(frame, detail.spans)
                    val incomplete = diagnosticFrameEvidenceIncomplete(frame, detail, snapshot.openSpans)
                    output.write("$prefix v=2 type=frameCorrelation abnormalFrame=$index " +
                        "intendedVsyncNs=${frame.intendedNs} frameTotalNs=${frame.totalNs} " +
                        "matched=${correlation.totalOverlaps} emitted=${correlation.overlaps.size} omitted=${correlation.omitted} " +
                        "lookbackMatched=${correlation.totalPreceding} lookbackEmitted=${correlation.preceding.size} lookbackOmitted=${correlation.precedingOmitted} " +
                        "sourceWindowLoss=${frame.sourceWindowLoss} sourceWindowUnknown=${frame.sourceWindowUnknown} " +
                        "mainSpanUnionNs=${correlation.overlapUnionNs} frameWallOutsideSpansNs=${correlation.frameWallOutsideSpansNs} " +
                        "evidenceIncomplete=$incomplete coverage=instrumentedCompletedSpansOnly evidenceComplete=notClaimed zeroMatch=notProofOfNoMainWork " +
                        "capture=anomalyCallbackAndAdmissionSnapshot rule=mainSpanOverlapNotCausality accounting=wallUnionNotCpuOrFrameParts")
                    correlation.overlaps.forEach { span ->
                        output.write("$prefix v=2 type=spanOverlap abnormalFrame=$index span=${span.span} parent=${span.parent} " +
                            "stage=${span.stage} beginNs=${span.beginNs} endNs=${span.endNs} " +
                            "overlapNs=${diagnosticOverlapNs(span.beginNs, span.endNs, frame.intendedNs, frame.intendedNs + frame.totalNs)} " +
                            "durationNs=${span.endNs - span.beginNs} selfUpperBoundNs=${diagnosticSelfUpperBoundNs(span, detail.spans)} " +
                            "selfAccounting=directChildUnionUpperBoundIfMissingChildren duration=inclusiveNotAdditive value=${span.value} " +
                            "eventSeq=${span.attribution?.event ?: 0} runToken=${span.attribution?.run ?: 0} " +
                            "conversationToken=${span.attribution?.conversation ?: 0} ${diagnosticRenderFields(span.attribution)}")
                    }
                    correlation.preceding.forEach { span ->
                        output.write("$prefix v=2 type=frameLookback abnormalFrame=$index span=${span.span} parent=${span.parent} " +
                            "stage=${span.stage} beginNs=${span.beginNs} endNs=${span.endNs} durationNs=${span.endNs - span.beginNs} " +
                            "lookbackNs=$FRAME_CORRELATION_LOOKBACK_NS relation=precedingNotFrameOverlap ${diagnosticRenderFields(span.attribution)}")
                    }
                    frame.listSnapshot?.let { sample ->
                        val age = frame.intendedNs + frame.totalNs - sample.atNs
                        output.write("$prefix v=2 type=frameList abnormalFrame=$index listToken=${sample.list} " +
                            "sampleNs=${sample.atNs} ageAtFrameEndNs=$age sourcePage=${FrameDiagnosticPage.entries[sample.page].name} sourceSegment=${sample.segment} " +
                            "relation=sourceMatchedObservedPostLayoutNotExactFrame visibility=layoutSlotsNotClippedPixels " +
                            "messageCount=${sample.messageCount} totalRows=${sample.totalCount} firstIndex=${sample.firstIndex} " +
                            "firstOffset=${sample.firstOffset} viewportStart=${sample.viewportStart} viewportEnd=${sample.viewportEnd} " +
                            "visibleCount=${sample.visibleCount} emittedRows=${sample.rows.size} omittedRows=${sample.visibleCount - sample.rows.size}")
                        sample.rows.forEach { row ->
                            output.write("$prefix v=2 type=frameListRow abnormalFrame=$index listToken=${sample.list} " +
                                "sampleNs=${sample.atNs} rowToken=${row.token} index=${row.index} offset=${row.offset} size=${row.size}")
                        }
                    } ?: output.write("$prefix v=2 type=frameList abnormalFrame=$index available=false")
                }
                true
            }.getOrDefault(false)
            session.observerCosts.add(DiagnosticObserverCosts.Phase.Output, System.nanoTime() - outputStarted)
            return result
        }
        val periodic = object : Runnable {
            override fun run() {
                if (session.closed || session.stopCutoffNs != null) return
                emit(false)
                if (!session.closed && session.stopCutoffNs == null) handler.postDelayed(this, 5000)
            }
        }
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
            val delivered = System.nanoTime()
            val intendedFrame = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            if (session.admitFrame(intendedFrame, AppFileLogger.isEnabled())) {
                val firstDraw = frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
                val total = frame.getMetric(FrameMetrics.TOTAL_DURATION)
                val deadline = frame.getMetric(FrameMetrics.DEADLINE)
                val unknown = frame.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
                val input = frame.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
                val animation = frame.getMetric(FrameMetrics.ANIMATION_DURATION)
                val layout = frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                val draw = frame.getMetric(FrameMetrics.DRAW_DURATION)
                val sync = frame.getMetric(FrameMetrics.SYNC_DURATION)
                val command = frame.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
                val swap = frame.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
                val gpu = frame.getMetric(FrameMetrics.GPU_DURATION)
                val missed = deadline > 0 && total > deadline
                val intended = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                val page = pages.attributeFrame(intended, total)
                // All metric stages have a timestamp-resolved page segment, including transitions.
                fun metric(stage: String, ns: Long, value: Long) {
                    session.recordMetric(page.aggregatePage.ordinal, stage, ns, value)
                }
                session.observerCosts.add(DiagnosticObserverCosts.Phase.CallbackLag, delivered - (intended + total))
                val severe = total >= SPIKE_FRAME_NS || unknown >= UNKNOWN_DELAY_DETAIL_NS
                if ((firstDraw || missed || severe) && session.details.reserveFrame(severe)) {
                    val record = DiagnosticFrameRecord(intended, frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP),
                        total, deadline, page.start.ordinal, page.end.ordinal, page.changed, dropped,
                        unknown, input, animation, layout, draw, sync, command, swap, gpu,
                        firstDraw = firstDraw, pageSegment = page.startSegment)
                    // One bounded capture per retained frame, never for normal or budget-rejected frames.
                    // Include retention, source matching and dispatch capture in non-recursive observer cost.
                    if (!firstDraw && !severe) {
                        session.details.frame(record.copy(detailCaptured = false, sourceWindowUnknown = true))
                    } else session.observerCosts.observe(DiagnosticObserverCosts.Phase.Protect) {
                        val evidence = session.details.protectFrame(record)
                        val listSnapshot = if (page.start == FrameDiagnosticPage.Chat || page.start == FrameDiagnosticPage.Home)
                            session.listSamples.forFrame(record, evidence) else null
                        val mainMessages = log.timingsBetween(intended - FRAME_CORRELATION_LOOKBACK_NS, intended + total)
                            .sortedByDescending { diagnosticOverlapNs(it.beginNs, it.endNs, intended, intended + total) }
                        session.details.frame(record.copy(listSnapshot = listSnapshot, mainMessages = mainMessages,
                            sourceWindowLoss = evidence.sourceWindowLoss, sourceWindowUnknown = evidence.sourceWindowUnknown))
                    }
                }
                // Capture first-draw evidence without changing legacy steady-frame/probe statistics.
                if (firstDraw) {
                    metric("frame.firstDraw", total, if (missed) 1 else 0)
                    return@OnFrameMetricsAvailableListener
                }
                session.record(page.aggregatePage.frameStage, total, if (missed) 1 else 0)
                metric("frame.total", total, if (missed) 1 else 0)
                metric("frame.steady", total, if (missed) 1 else 0)
                metric("frame.layout", layout, 0)
                metric("frame.draw", draw, 0)
                metric("frame.sync", sync, 0)
                metric("frame.gpu", gpu, 0)
                metric("frame.input", input, 0)
                metric("frame.unknown", unknown, 0)
                metric("frame.animation", animation, 0)
                metric("frame.command", command, 0)
                metric("frame.swap", swap, 0)
                val gap = frameUnaccountedNs(total, unknown, input, animation, layout, draw, sync, command, swap)
                if (gap >= 0) metric("frame.unaccounted", gap, 0)
                else metric("frame.overlap", -gap, 1)
                val late = frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP) - intended
                metric("frame.vsyncLate", late.coerceAtLeast(0), if (late > 8_333_333L) 1 else 0)
                metric("frame.metricsDropped", 0, dropped.toLong())
                metric("frame.deadline", deadline, 0)
                val target = probe
                val inWindow = target != null && !target.finished && intended >= target.startNs - FRAME_PROBE_LEAD_NS
                if (inWindow) {
                    val parts = "${page.fields()} totalUs=${total / 1000} deadlineUs=${deadline / 1000} " +
                        "miss=${if (missed) 1 else 0} " +
                        "unknownUs=${unknown / 1000} inputUs=${input / 1000} " +
                        "animUs=${animation / 1000} layoutUs=${layout / 1000} drawUs=${draw / 1000} " +
                        "syncUs=${sync / 1000} cmdUs=${command / 1000} gpuUs=${gpu / 1000} " +
                        "vsyncLateUs=${late.coerceAtLeast(0) / 1000}"
                    val tenths = (intended - target!!.startNs) / 100_000
                    val line = "sinceTapMs=${tenths / 10}.${kotlin.math.abs(tenths % 10)} $parts"
                    if (target.addFrame(line, total / 1000, missed) || target.expired(System.nanoTime())) {
                        finishProbe(target, reportProbe)
                    }
                } else {
                    if (target != null && target.expired(System.nanoTime())) finishProbe(target, reportProbe)
                    // Detailed anomalies (including <33ms deadline misses) are reserved above and formatted at emit.
                }
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, handler)
        handler.post {
            output.write(
                "StreamDiag id=${session.id} start=1 gen=$sessionGeneration intervalMs=5000 " +
                    "frameValue=deadlineMiss histogramMs=16,32,50,100 " +
                    "probeFrames=$TOGGLE_PROBE_FRAMES probeMaxMs=${TOGGLE_PROBE_MAX_NS / 1_000_000} " +
                    "spikeMs=${SPIKE_FRAME_NS / 1_000_000}"
            )
            handler.postDelayed(periodic, 5000)
        }
        var stopRequested = false // main-thread owned, idempotent
        val stop: () -> CompletableFuture<StopResult> = {
            if (!stopRequested) {
                stopRequested = true
                val cutoff = session.stopAdmission() // fixed before removing observers
                window.removeOnFrameMetricsAvailableListener(listener)
                if (active === session) {
                    probe?.let { finishProbe(it, reportProbe) }
                    publishSession(null)
                    activeStop = null
                    probeReporter = null; probeTimeoutHandler = null; noteSink = null
                }
                if (mainLog === log) {
                    log.closeOpen(cutoff) // partial dispatch, cpuNs=-1; never fabricate its end CPU
                    Looper.getMainLooper().setMessageLogging(null)
                    mainLog = null
                }
                mainThreadSchedstat.close()
                handler.removeCallbacks(periodic)
                lastStop = completion
                // Drain queued pre-cutoff callbacks on their ORIGINAL worker/session. This is a
                // bounded grace, not proof that Android delivered every generated frame.
                val drainUntil = System.nanoTime() + 250_000_000L
                val finish = object : Runnable {
                    override fun run() {
                        if (System.nanoTime() < drainUntil) { handler.postDelayed(this, 10); return }
                        val emitted = emit(true)
                        session.observerCosts.summary().forEach {
                            output.write("StreamDiag id=${session.id} v=2 type=observerCost final=true " +
                                "windowStartNs=$cutoff windowEndNs=$cutoff boundary=admissionSnapshot $it")
                        }
                        val outputFlushed = output.close()
                        val outputDropped = output.dropped
                        val outputFailed = output.failed
                        val rejected = AppFileLogger.diagnosticRejected.get() - loggerRejectedStart
                        val failed = AppFileLogger.diagnosticFailed.get() - loggerFailedStart
                        val finalAppendFlushed = AppFileLogger.diagnosticCompletion(
                            "StreamDiag id=${session.id} v=2 type=finalCompletion final=true windowStartNs=$cutoff " +
                                "windowEndNs=$cutoff boundary=admissionSnapshot cutoffNs=$cutoff " +
                                "loggerRejected=$rejected loggerFailed=$failed outputDroppedLines=$outputDropped essentialOutputDropped=${output.essentialDropped} " +
                                "outputFailedLines=$outputFailed " +
                                "outputFlushed=$outputFlushed drainGraceMs=250 " +
                                "completion=appendAndFlush privacyGate=honored evidenceComplete=notClaimed")
                        // A successful footer alone must not hide any earlier rejected/failed output.
                        val flushed = emitted && outputFlushed && outputDropped == 0L &&
                            outputFailed == 0L && rejected == 0L && failed == 0L && finalAppendFlushed
                        completion.complete(StopResult(session.serial, flushed,
                            if (flushed) "finalAppendFlushedNotFsync" else "loggerClosedOrOutputFailed"))
                        thread.quitSafely()
                    }
                }
                handler.post(finish)
            }
            completion
        }
        activeStop = stop
        return { stop(); Unit }
    }
}

/** 被点开那一项持有的当前点击 token；修饰符在测量和绘制时读它，不触发重组。 */
internal class ToggleProbeRef {
    var token = 0
}

/**
 * 记录被点开那一项某一层的高度变化、测量耗时和绘制耗时。只在这一项的点击窗口里记，
 * 其余时间直接透传。放在 AnimatedVisibility 上看到的是动画中的可见高度，放在内容上是内容本身的高度。
 */
internal fun Modifier.toggleProbe(ref: ToggleProbeRef, part: String): Modifier = this
    .layout { measurable, constraints ->
        val token = ref.token
        if (token == 0 || !StreamPerformanceDiagnostics.probing) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val started = System.nanoTime()
        val placeable = measurable.measure(constraints)
        val elapsed = System.nanoTime() - started
        StreamPerformanceDiagnostics.probeEvent(
            token,
            "measure",
            "part=$part h=${placeable.height} us=${elapsed / 1000}",
        )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    .drawWithContent {
        val token = ref.token
        if (token == 0 || !StreamPerformanceDiagnostics.probing) {
            drawContent()
            return@drawWithContent
        }
        val started = System.nanoTime()
        drawContent()
        val elapsed = System.nanoTime() - started
        StreamPerformanceDiagnostics.probeEvent(
            token,
            "draw",
            "part=$part h=${size.height.toInt()} us=${elapsed / 1000}",
        )
    }

/** TOTAL_DURATION minus the eight non-overlapping FrameMetrics parts. GPU is excluded; it overlaps command/swap. */
internal fun frameUnaccountedNs(
    total: Long,
    unknown: Long,
    input: Long,
    animation: Long,
    layout: Long,
    draw: Long,
    sync: Long,
    command: Long,
    swap: Long,
): Long = total - (unknown + input + animation + layout + draw + sync + command + swap)

/** One foreground-window monitor at the app root, including non-chat pages. */
@Composable
internal fun StreamPerformanceMonitor(loggingEnabled: Boolean, page: FrameDiagnosticPage) {
    val diagnosticAllowed = ObserveStreamDiagnosticControl()
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val pages = remember(view, loggingEnabled, resumed, diagnosticAllowed) { FramePageTimeline() }
    SideEffect {
        if (loggingEnabled && diagnosticAllowed && resumed) pages.mark(page, System.nanoTime())
    }
    DisposableEffect(view, loggingEnabled, resumed, pages, diagnosticAllowed) {
        val window = view.context.findStreamActivity()?.window
        val detach = if (loggingEnabled && diagnosticAllowed && resumed && window != null) {
            StreamPerformanceDiagnostics.attach(window, pages)
        } else null
        onDispose { detach?.invoke() }
    }
}

private fun Context.findStreamActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findStreamActivity() else null
    else -> null
}
