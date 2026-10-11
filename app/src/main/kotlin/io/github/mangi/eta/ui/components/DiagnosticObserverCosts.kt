package io.github.mangi.eta.ui.components

/** Fixed numeric observer buckets. Deliberately never calls measure/trace/record. */
internal class DiagnosticObserverCosts(private val clock: () -> Long = System::nanoTime) {
    enum class Phase { Enter, Finish, Protect, Snapshot, Output, CallbackLag }
    private val count = LongArray(Phase.entries.size)
    private val total = LongArray(Phase.entries.size)
    private val max = LongArray(Phase.entries.size)
    @Synchronized fun add(phase: Phase, ns: Long) {
        val i = phase.ordinal
        val value = ns.coerceAtLeast(0)
        count[i]++; total[i] += value; max[i] = maxOf(max[i], value)
    }
    fun <T> observe(phase: Phase, block: () -> T): T {
        val start = clock()
        try { return block() } finally { add(phase, clock() - start) }
    }
    fun summary(): List<String> {
        val counts = LongArray(count.size)
        val totals = LongArray(total.size)
        val maxima = LongArray(max.size)
        synchronized(this) {
            count.copyInto(counts)
            total.copyInto(totals)
            max.copyInto(maxima)
        }
        return Phase.entries.map { phase ->
            val i = phase.ordinal
            "observerPhase=${phase.name} count=${counts[i]} totalNs=${totals[i]} maxNs=${maxima[i]} " +
                "accounting=observerWallNotCpu includesLockWait=true recursiveMeasurement=false scope=sessionCumulative"
        }
    }
}

/** Bounded mapping; Java thread ID is NOT a scheduler tid. Unknown is -1, never guessed. */
internal class DiagnosticThreadIds(private val capacity: Int = 128) {
    private val tids = HashMap<Long, Int>()
    var saturated = 0L
        private set
    @Synchronized fun register(javaThreadId: Long, osTid: Int) {
        if (javaThreadId in tids) return
        if (tids.size == capacity) { saturated++; return }
        tids[javaThreadId] = osTid.takeIf { it > 0 } ?: -1
    }
    @Synchronized fun osTid(javaThreadId: Long): Int = tids[javaThreadId] ?: -1
    @Synchronized fun fields(): String = "threadIdCapacity=$capacity threadIdSaturated=$saturated"
}
