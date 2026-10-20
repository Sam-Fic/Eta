package io.github.mangi.eta.data.repository

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Stage and verify all bytes before touching live data. */
internal object BackupArchiveSafety {
    const val MANIFEST_LIMIT = 32L * 1024 * 1024
    const val TOTAL_LIMIT = 32L * 1024 * 1024 * 1024
    const val ENTRY_LIMIT = 100_000
    const val RESERVE_BYTES = 128L * 1024 * 1024
    val LINUX_ENVIRONMENT_TAR = Regex("""^linux/environments/[^/]+/[^/]+\.tar$""")

    fun relativePath(raw: String): String {
        require(raw.isNotBlank() && raw.length <= 4096 && !raw.startsWith('/') &&
            !raw.contains('\\') && !raw.contains('\u0000') && !raw.contains(':')) { "归档路径无效" }
        val name = raw.removeSuffix("/")
        require(name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "归档路径越界" }
        return name
    }

    fun target(root: File, relative: String): File {
        // root is supplied by the app, not the archive. Canonicalize platform data-dir aliases;
        // reject symlinks below that trusted root, rather than rejecting Android's own ancestors.
        val base = root.canonicalFile.toPath()
        val path = base.resolve(relativePath(relative)).normalize()
        require(path.startsWith(base) && path != base) { "归档路径越界" }
        var current = path
        while (current != base) {
            require(!Files.isSymbolicLink(current)) { "恢复目标经过符号链接" }
            current = requireNotNull(current.parent)
        }
        require(!Files.isSymbolicLink(root.toPath())) { "恢复根目录是符号链接" }
        return path.toFile()
    }

    fun copyLimited(input: InputStream, output: OutputStream, limit: Long, disk: File? = null, crc: CRC32? = null): Long {
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return size
            if (n == 0) continue
            require(n.toLong() <= limit - size) { "备份超过大小限制" }
            if (disk != null) require(disk.usableSpace >= RESERVE_BYTES + n) { "恢复暂存空间不足" }
            output.write(buffer, 0, n)
            crc?.update(buffer, 0, n)
            size += n
        }
    }

    fun readText(input: InputStream, limit: Long = MANIFEST_LIMIT): String {
        val bytes = java.io.ByteArrayOutputStream()
        copyLimited(input, bytes, limit)
        return bytes.toString(Charsets.UTF_8.name())
    }

    /** ZipFile requires a central directory; explicit CRC verifies stored entries too. */
    fun stageZip(archive: File, directory: File): Map<String, File> {
        require(directory.mkdirs() || directory.isDirectory)
        val files = linkedMapOf<String, File>()
        val names = hashSetOf<String>()
        var total = 0L
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(names.size < ENTRY_LIMIT) { "备份条目过多" }
                val name = relativePath(entry.name)
                require(names.add(name)) { "备份存在重复条目：$name" }
                if (entry.isDirectory) continue
                require(name == "eta-backup.json" || name == "eta-conversation.json" || name == "eta-models.json" ||
                    name.startsWith("conversations/") && name.endsWith(".json") ||
                    name.startsWith("attachments/chat-images/") || name.startsWith("attachments/imports/") ||
                    name.startsWith("linux/workspace/") ||
                    name == "media-index/records.jsonl" || name.startsWith("media-index/thumbs/") ||
                    name.startsWith("models/") ||
                    LINUX_ENVIRONMENT_TAR.matches(name)) { "不支持的备份条目：$name" }
                val file = target(directory, name)
                require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                val limit = if (!name.contains('/') || name.startsWith("conversations/")) MANIFEST_LIMIT else TOTAL_LIMIT
                val crc = CRC32()
                val size = file.outputStream().use { output -> zip.getInputStream(entry).use { input ->
                    copyLimited(input, output, minOf(limit, TOTAL_LIMIT - total), directory, crc)
                } }
                require(size == entry.size && crc.value == entry.crc) { "备份条目损坏：$name" }
                total += size
                files[name] = file
            }
        }
        require(files.keys.count { it == "eta-backup.json" || it == "eta-conversation.json" || it == "eta-models.json" } == 1) {
            "备份必须且只能包含一个清单"
        }
        return files
    }
}
