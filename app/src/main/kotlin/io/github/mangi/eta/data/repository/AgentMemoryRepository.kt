package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

internal data class AgentMemorySnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val lineCount: Int,
)

internal data class AgentMemoryReadResult(
    val snapshot: AgentMemorySnapshot,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val hasMore: Boolean,
    val matchedLines: Int,
    /** 按标题读取时回显命中的标题（含 `#` 前缀），便于调用方确认取到的是哪一节。 */
    val section: String? = null,
)

internal sealed interface AgentMemoryMutation {
    val revision: String

    data class ReplaceRange(
        override val revision: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
    ) : AgentMemoryMutation

    /** 按标题整节替换；content 为空表示删除该节。 */
    data class ReplaceSection(
        override val revision: String,
        val section: String,
        val content: String,
    ) : AgentMemoryMutation

    data class Append(
        override val revision: String,
        val content: String,
    ) : AgentMemoryMutation

    data class Clear(
        override val revision: String,
    ) : AgentMemoryMutation
}

internal sealed interface AgentMemoryWriteResult {
    /**
     * changed=false 表示写入后内容与之前逐字节一致（例如 append 了空内容），
     * 让调用方能把"真的写了"与"什么都没发生"区分开，而不是都看到 ok=true。
     */
    data class Success(
        val snapshot: AgentMemorySnapshot,
        val changed: Boolean = true,
    ) : AgentMemoryWriteResult

    data class Conflict(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
}

internal class AgentMemoryException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** 单一 MEMORY.md 的有界、原子文件存储。 */
internal class AgentMemoryStore(
    rootDir: File,
) {
    private val memoryDir = File(rootDir, DIRECTORY_NAME)
    private val atomicFile = AtomicFile(File(memoryDir, FILE_NAME))
    private val lock = Any()

    fun snapshot(): AgentMemorySnapshot = synchronized(lock) {
        snapshotLocked()
    }

    fun read(
        query: String? = null,
        startLine: Int = DEFAULT_START_LINE,
        maxChars: Int = DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult = synchronized(lock) {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        if (query.isNullOrBlank()) {
            page(snapshot, startLine, boundedChars)
        } else {
            search(snapshot, query.trim(), boundedChars)
        }
    }

    /**
     * 按标题读取整节。标题匹配忽略大小写与 `#` 前缀；匹配不到或匹配到多节时抛出带可用标题清单的错误，
     * 避免调用方拿着"空结果"却不知道为什么。
     */
    fun readSection(
        section: String,
        maxChars: Int = DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult = synchronized(lock) {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        val lines = snapshot.content.memoryLines()
        val match = resolveSection(lines, section)
        renderRange(snapshot, match.heading.startIndex, match.heading.endIndex, boundedChars, match.heading.raw)
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (mutation.revision != current.revision) {
            return@synchronized AgentMemoryWriteResult.Conflict(current)
        }
        val updated = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> replaceRange(current, mutation)
            is AgentMemoryMutation.ReplaceSection -> replaceSection(current, mutation)
            is AgentMemoryMutation.Append -> append(current, mutation.content)
            is AgentMemoryMutation.Clear -> ""
        }
        writeLocked(updated)
        AgentMemoryWriteResult.Success(snapshotOf(updated), changed = updated != current.content)
    }

    fun replaceAll(content: String): AgentMemorySnapshot = synchronized(lock) {
        writeLocked(content)
        snapshotOf(content)
    }

    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (current.revision != revision) return@synchronized AgentMemoryWriteResult.Conflict(current)
        writeLocked(content)
        AgentMemoryWriteResult.Success(snapshotOf(content), changed = content != current.content)
    }

    private fun snapshotLocked(): AgentMemorySnapshot {
        val file = atomicFile.baseFile
        if (!file.exists()) return snapshotOf("")
        val bytes = try {
            atomicFile.openRead().use { it.readBytes() }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆文件",
                cause = throwable,
            )
        }
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件超过 1 MiB 安全上限",
            )
        }
        val content = bytes.toString(Charsets.UTF_8)
        return snapshotOf(content, bytes)
    }

    private fun writeLocked(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件不能超过 1 MiB UTF-8 字节",
            )
        }
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法创建记忆目录",
            )
        }
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法开始写入记忆文件",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法保存记忆文件",
                cause = throwable,
            )
        }
    }

    private fun replaceRange(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceRange,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        if (
            mutation.startLine < 1 ||
            mutation.endLine < mutation.startLine ||
            mutation.endLine > lines.size
        ) {
            throw AgentMemoryException(
                code = "MEMORY_RANGE_INVALID",
                message = "替换行范围无效（start/end 需 1 ≤ start ≤ end ≤ ${lines.size}）。" +
                    "请先用 memory_get(section=…) 或 memory_get(start_line=1, max_chars=32000) 预览实际行号后再试；" +
                    "若只想改整块内容，改用 mode=\"replace_section\" + section 更稳，不必手算行号。",
            )
        }
        val replacement = mutation.content.memoryLines()
        lines.subList(mutation.startLine - 1, mutation.endLine).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(mutation.startLine - 1, replacement)
        }
        return lines.joinToString("\n")
    }

    private fun replaceSection(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceSection,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        val resolved = resolveSection(lines, mutation.section)
        val heading = resolved.heading
        val replacement = mutation.content.memoryLines()
        // 空 content = 删除整节（文档化的用法），所以护栏只在"有内容却没带一级标题"时生效。
        if (heading.level == 1 && replacement.isNotEmpty() && replacement.none { headingLevel(it) == 1 }) {
            throw AgentMemoryException(
                code = "MEMORY_SECTION_HEADING_REQUIRED",
                message = "替换一级章节时，content 必须以同行的一级标题开头（例如 \"${heading.raw}\"）；" +
                    "只替换正文请改用 mode=\"replace_range\"，删除整节请传空 content。",
            )
        }
        lines.subList(heading.startIndex, heading.endIndex + 1).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(heading.startIndex, replacement)
        }
        return lines.joinToString("\n")
    }

    private fun append(snapshot: AgentMemorySnapshot, content: String): String {
        if (content.isEmpty()) return snapshot.content
        val incomingLines = content.memoryLines()
        val incomingLevelOne = incomingLines.filter { headingLevel(it) == 1 }.map { it.trimEnd() }
        if (incomingLevelOne.isNotEmpty()) {
            val existing = snapshot.content.memoryLines()
                .filter { headingLevel(it) == 1 }
                .map { it.trimEnd() }
                .toSet()
            val duplicated = incomingLevelOne.filter { it in existing }
            if (duplicated.isNotEmpty()) {
                throw AgentMemoryException(
                    code = "MEMORY_DUPLICATE_HEADING",
                    message = "追加内容包含已存在的一级标题：${duplicated.joinToString("、")}。" +
                        "请改用 mode=\"replace_section\" 替换该章节，或换一个未使用过的标题。" +
                        "（重复的 \"$CORE_HEADING\" 会让新增内容被排除在自动注入的核心记忆之外。）",
                )
            }
        }
        if (snapshot.content.isEmpty()) return content
        return snapshot.content.trimEnd('\n') + "\n" + content
    }

    private fun page(
        snapshot: AgentMemorySnapshot,
        requestedStartLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val startIndex = (requestedStartLine - 1).coerceIn(0, lines.size)
        if (startIndex >= lines.size) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val output = StringBuilder()
        var endIndex = startIndex
        while (endIndex < lines.size) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(safeTake(rendered, maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = startIndex + 1,
            endLine = endIndex,
            hasMore = endIndex < lines.size,
            matchedLines = endIndex - startIndex,
        )
    }

    private fun renderRange(
        snapshot: AgentMemorySnapshot,
        fromIndex: Int,
        toIndex: Int,
        maxChars: Int,
        section: String?,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (fromIndex > toIndex || fromIndex !in lines.indices || toIndex !in lines.indices) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0, section)
        }
        val output = StringBuilder()
        var endIndex = fromIndex
        while (endIndex <= toIndex) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(safeTake(rendered, maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = fromIndex + 1,
            endLine = endIndex,
            hasMore = endIndex <= toIndex,
            matchedLines = toIndex - fromIndex + 1,
            section = section,
        )
    }

    private fun search(
        snapshot: AgentMemorySnapshot,
        query: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        val matched = lines.indices.filter { index ->
            lines[index].contains(query, ignoreCase = true)
        }
        val all = headings(lines)
        val included = linkedSetOf<Int>()
        matched.forEach { index ->
            val owner = all.lastOrNull { index >= it.startIndex && index <= it.endIndex }
            val expandable = owner != null && (owner.endIndex - owner.startIndex + 1) <= MAX_SECTION_EXPANSION_LINES
            val range = if (owner != null && expandable) {
                owner.startIndex..owner.endIndex
            } else {
                (index - SEARCH_WINDOW_LINES).coerceAtLeast(0)..(index + SEARCH_WINDOW_LINES).coerceAtMost(lines.lastIndex)
            }
            range.forEach { candidate ->
                if (candidate in lines.indices) included += candidate
            }
        }
        val output = StringBuilder()
        var lastIncluded: Int? = null
        var renderedCount = 0
        for (index in included) {
            val rendered = "${index + 1}: ${lines[index]}"
            val gap = when {
                output.isEmpty() -> ""
                lastIncluded != null && index > lastIncluded + 1 -> "\n…\n"
                else -> "\n"
            }
            if (output.isNotEmpty() && output.length + gap.length + rendered.length > maxChars) break
            output.append(gap).append(safeTake(rendered, maxChars - output.length - gap.length))
            lastIncluded = index
            renderedCount++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = included.firstOrNull()?.plus(1),
            endLine = lastIncluded?.plus(1),
            hasMore = renderedCount < included.size,
            matchedLines = matched.size,
        )
    }

    private data class MemoryHeading(
        val startIndex: Int,
        val endIndex: Int,
        val level: Int,
        val raw: String,
    ) {
        val title: String get() = headingTitle(raw)
    }

    private data class ResolvedSection(val heading: MemoryHeading)

    /** 按标题层级切出所有章节（`#` 的区间到下一个同级或更高级标题为止）。 */
    private fun headings(lines: List<String>): List<MemoryHeading> {
        val result = mutableListOf<MemoryHeading>()
        var index = 0
        while (index < lines.size) {
            val level = headingLevel(lines[index])
            if (level == 0) {
                index++
                continue
            }
            var end = index + 1
            while (end < lines.size) {
                val next = headingLevel(lines[end])
                if (next in 1..level) break
                end++
            }
            result += MemoryHeading(index, end - 1, level, lines[index])
            index++
        }
        return result
    }

    private fun resolveSection(lines: List<String>, section: String): ResolvedSection {
        val target = headingTitle(section.trim())
        if (target.isEmpty()) {
            throw AgentMemoryException(
                code = "MEMORY_SECTION_INVALID",
                message = "section 不能为空；可用标题：\n${availableHeadings(lines)}",
            )
        }
        val all = headings(lines)
        if (all.isEmpty()) {
            throw AgentMemoryException(
                code = "MEMORY_SECTION_NOT_FOUND",
                message = "记忆文件里没有任何 Markdown 标题。",
            )
        }
        val exact = all.filter { it.title.equals(target, ignoreCase = true) }
        val candidates = exact.ifEmpty { all.filter { it.title.contains(target, ignoreCase = true) } }
        return when (candidates.size) {
            0 -> throw AgentMemoryException(
                code = "MEMORY_SECTION_NOT_FOUND",
                message = "未找到标题包含「$section」的章节；可用标题：\n${availableHeadings(lines)}",
            )
            1 -> ResolvedSection(candidates.first())
            else -> throw AgentMemoryException(
                code = "MEMORY_SECTION_AMBIGUOUS",
                message = "「$section」匹配到 ${candidates.size} 个章节，请用更完整的标题：\n" +
                    candidates.joinToString("\n") { "  ${it.raw.trimEnd()}（第 ${it.startIndex + 1} 行）" },
            )
        }
    }

    private fun availableHeadings(lines: List<String>): String =
        lines.withIndex()
            .filter { headingLevel(it.value) > 0 }
            .take(MAX_HEADING_LIST)
            .joinToString("\n") { "  第 ${it.index + 1} 行  ${it.value.trimEnd()}" }
            .ifEmpty { "  （无标题）" }

    private fun snapshotOf(
        content: String,
        bytes: ByteArray = content.toByteArray(Charsets.UTF_8),
    ): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = sha256(bytes),
        byteSize = bytes.size,
        lineCount = content.memoryLines().size,
    )

    private fun String.memoryLines(): List<String> =
        if (isEmpty()) emptyList() else split('\n')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        const val DEFAULT_READ_CHARS = 12_000
        const val MAX_READ_CHARS = 32_000
        const val MIN_READ_CHARS = 1
        const val MAX_WRITE_CONTENT_CHARS = 3_500

        /** `mode="clear"` 必须回传此串，避免一次误调用清空全部记忆。 */
        const val CLEAR_CONFIRMATION = "DELETE_ALL"

        private const val DIRECTORY_NAME = "memory"
        private const val FILE_NAME = "MEMORY.md"
        private const val DEFAULT_START_LINE = 1
        private const val SEARCH_WINDOW_LINES = 6
        private const val MAX_SECTION_EXPANSION_LINES = 24
        private const val MAX_HEADING_LIST = 40
        private const val CORE_HEADING = "# 核心记忆"
    }
}

internal object AgentMemoryRepository {
    @Volatile
    private lateinit var store: AgentMemoryStore

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = AgentMemoryStore(context.applicationContext.filesDir)
        }
    }

    fun snapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    fun read(
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.read(query, startLine, maxChars)
    }

    fun readSection(
        section: String,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.readSection(section, maxChars)
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult {
        ensureInitialized()
        return store.mutate(mutation)
    }

    fun replaceAll(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    fun enabledFlow(): Flow<Boolean> = SettingsDataStore.memoryEnabledFlow()

    suspend fun isEnabled(): Boolean = SettingsDataStore.settings().memoryEnabled

    suspend fun setEnabled(enabled: Boolean) = SettingsDataStore.setMemoryEnabled(enabled)

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "AgentMemoryRepository.init(context) must be called in Application.onCreate()"
        }
    }
}

/** 标题层级：返回 `#` 的个数（1..6），不是标题或 `#` 后无空格时返回 0。 */
private fun headingLevel(line: String): Int {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("#")) return 0
    val hashes = trimmed.takeWhile { it == '#' }.length
    if (hashes !in 1..6) return 0
    if (trimmed.length <= hashes || trimmed[hashes] != ' ') return 0
    return hashes
}

/** 去掉 `#` 前缀与首尾空白后的标题文本。 */
private fun headingTitle(line: String): String =
    line.trimStart().trimStart('#').trim()

/** 不在截断点切断 UTF-16 代理对（emoji 等）。 */
private fun safeTake(text: String, limit: Int): String {
    if (limit <= 0) return ""
    if (text.length <= limit) return text
    val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
    return text.substring(0, end)
}
