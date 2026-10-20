package io.github.mangi.eta.agent.media.index

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.mangi.eta.agent.media.AgentChatImageCache
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.data.db.MediaKind
import io.github.mangi.eta.data.db.MediaSourceEntity
import java.io.File

/**
 * 按扫描源产出候选媒体清单。只做「发现」，不做抽取与嵌入（见 [MediaExtraction] / [MediaIndexer]）。
 *
 * 覆盖五类源：系统媒体库（图片/视频/音频）、会话附件缓存、终端工作区文件、
 * SAF 自选文件夹（DocumentsContract 遍历 tree URI）、Linux 共享文件夹（挂载源目录）。
 */
internal object MediaSourceScanner {
    const val TYPE_MEDIASTORE = "mediastore"
    const val TYPE_ATTACHMENT_CACHE = "attachment-cache"
    const val TYPE_WORKSPACE = "workspace"
    const val TYPE_SAF_TREE = "saf-tree"
    const val TYPE_SHARED_FOLDER = "shared-folder"

    /** 低于该字节数的文件按噪声（缩略图、图标等）跳过，避免污染检索结果。 */
    private const val MIN_SIZE_BYTES = 8L * 1024L

    /** 目录遍历深度上限，防止病态目录树拖垮索引。 */
    private const val MAX_WALK_DEPTH = 12

    /** 系统约定的「不进媒体扫描」标记文件。 */
    private const val NOMEDIA_NAME = ".nomedia"

    data class Candidate(
        val sourceKey: String,
        val kind: MediaKind,
        val displayName: String,
        val mime: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
        val contentUri: String? = null,
        val filePath: String? = null,
        val durationMs: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    data class Estimate(val count: Int, val bytes: Long)

    /** 确认弹窗展示的推荐范围；A/B 默认开启（无限权或推荐），工作区默认关闭。 */
    fun recommendedSources(): List<MediaSourceEntity> = listOf(
        MediaSourceEntity(
            id = TYPE_MEDIASTORE,
            type = TYPE_MEDIASTORE,
            uri = MediaStore.Files.getContentUri("external").toString(),
            label = "系统媒体库（图片/视频/音频）",
            enabled = true,
        ),
        MediaSourceEntity(
            id = TYPE_ATTACHMENT_CACHE,
            type = TYPE_ATTACHMENT_CACHE,
            uri = "",
            label = "会话附件缓存（chat-images / imports）",
            enabled = true,
        ),
        MediaSourceEntity(
            id = TYPE_WORKSPACE,
            type = TYPE_WORKSPACE,
            uri = "",
            label = "终端工作区文件",
            enabled = false,
        ),
    )

    /** 预估条目数与体积，供确认弹窗展示；实现上按同一过滤规则快速统计。 */
    fun estimate(context: Context, source: MediaSourceEntity): Estimate {
        val candidates = scan(context, source)
        return Estimate(count = candidates.size, bytes = candidates.sumOf { it.sizeBytes })
    }

    fun scan(context: Context, source: MediaSourceEntity): List<Candidate> = when (source.type) {
        TYPE_MEDIASTORE -> scanMediaStore(context)
        TYPE_ATTACHMENT_CACHE -> scanAttachmentCache(context)
        TYPE_WORKSPACE -> scanWorkspace(context)
        TYPE_SAF_TREE -> scanSafTree(context, source)
        TYPE_SHARED_FOLDER -> scanSharedFolder(source)
        else -> emptyList()
    }

    /** MediaStore：图片 + 视频 + 音频（§7 A 系统媒体库含音频）。 */
    private fun scanMediaStore(context: Context): List<Candidate> {
        val result = mutableListOf<Candidate>()
        result += queryMediaStore(
            context,
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            kind = MediaKind.IMAGE,
            idPrefix = "content://media/external/images/media",
        )
        result += queryMediaStore(
            context,
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            kind = MediaKind.VIDEO,
            idPrefix = "content://media/external/video/media",
        )
        result += queryMediaStore(
            context,
            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            kind = MediaKind.AUDIO,
            idPrefix = "content://media/external/audio/media",
        )
        return result
    }

    private fun queryMediaStore(
        context: Context,
        uri: Uri,
        kind: MediaKind,
        idPrefix: String,
    ): List<Candidate> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
        )
        val result = mutableListOf<Candidate>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val widthIndex = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightIndex = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val durationIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val size = cursor.getLong(sizeIndex)
                if (size < MIN_SIZE_BYTES) continue
                val name = cursor.getString(nameIndex).orEmpty().ifEmpty { "$id" }
                if (name.startsWith(".")) continue
                val mime = cursor.getString(mimeIndex).orEmpty().ifEmpty { mimeForName(name).orEmpty() }
                if (mime.isEmpty()) continue
                val modifiedSeconds = cursor.getLong(modifiedIndex)
                result += Candidate(
                    sourceKey = "$idPrefix/$id",
                    kind = kind,
                    displayName = name,
                    mime = mime,
                    sizeBytes = size,
                    modifiedAt = modifiedSeconds * 1000L,
                    contentUri = "$idPrefix/$id",
                    // 时长只对有时长语义的类型生效：视频与音频（图片读出来恒为 0）。
                    durationMs = if (durationIndex >= 0 && (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO)) {
                        cursor.getLong(durationIndex).takeIf { it > 0 }
                    } else {
                        null
                    },
                    width = if (widthIndex >= 0) cursor.getInt(widthIndex).takeIf { it > 0 } else null,
                    height = if (heightIndex >= 0) cursor.getInt(heightIndex).takeIf { it > 0 } else null,
                )
            }
        }
        return result
    }

    /** 会话附件缓存：App 私有，无限权；同时覆盖 workspace/imports 的导入文件。 */
    private fun scanAttachmentCache(context: Context): List<Candidate> {
        val roots = listOf(
            File(context.cacheDir, AgentChatImageCache.CACHE_DIRECTORY),
            File(TerminalPrivateStorage.workspace(context.filesDir), "imports"),
        )
        return roots.flatMap { walkFiles(it) }
    }

    /** 终端工作区文件：排除 imports（归附件缓存）与 mounts（归共享文件夹）。 */
    private fun scanWorkspace(context: Context): List<Candidate> {
        val root = TerminalPrivateStorage.workspace(context.filesDir)
        return walkFiles(root, excludedTopLevel = setOf("imports", "mounts"))
    }

    /**
     * Linux 共享文件夹：[MediaSourceEntity.uri] 即 SharedFolderMount.sourcePath（Android 侧真实目录），
     * 复用 [walkFiles] 的隐藏/极小文件/.nomedia 过滤，sourceKey 取绝对路径。
     */
    private fun scanSharedFolder(source: MediaSourceEntity): List<Candidate> {
        if (source.uri.isBlank()) return emptyList()
        return walkFiles(File(source.uri))
    }

    /**
     * SAF 自选文件夹：[MediaSourceEntity.uri] 存持久化的 tree URI，按 DocumentsContract 递归遍历。
     * 授权丢失、URI 非法或查询失败一律静默返回空清单（不抛异常），设置页据此提示重新授权。
     */
    private fun scanSafTree(context: Context, source: MediaSourceEntity): List<Candidate> {
        val treeUri = runCatching { Uri.parse(source.uri) }.getOrNull() ?: return emptyList()
        if (!hasPersistedPermission(context, source.uri)) return emptyList()
        val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return emptyList()
        val result = mutableListOf<Candidate>()
        walkSafDirectory(context, treeUri, rootDocumentId, depth = 0, result = result)
        return result
    }

    /** tree URI 是否仍有持久化读权限；供设置页展示「需重新授权」。 */
    internal fun hasPersistedPermission(context: Context, treeUriString: String): Boolean {
        val target = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions
            .any { it.isReadPermission && it.uri == target }
    }

    private fun walkSafDirectory(
        context: Context,
        treeUri: Uri,
        documentId: String,
        depth: Int,
        result: MutableList<Candidate>,
    ) {
        if (depth > MAX_WALK_DEPTH) return
        val children = querySafChildren(context, treeUri, documentId) ?: return
        // .nomedia 按系统约定整目录跳过（与 walkFiles 一致）。
        if (children.any { it.name == NOMEDIA_NAME }) return
        children.forEach { child ->
            if (child.name.startsWith(".")) return@forEach
            if (child.isDirectory) {
                walkSafDirectory(context, treeUri, child.documentId, depth + 1, result)
            } else {
                val kind = kindForMime(child.mime) ?: kindForName(child.name) ?: return@forEach
                if (child.sizeBytes < MIN_SIZE_BYTES) return@forEach
                // sourceKey = tree URI + document id 的稳定组合（即 document URI），跨重启可重关联。
                val uriString = DocumentsContract
                    .buildDocumentUriUsingTree(treeUri, child.documentId)
                    .toString()
                result += Candidate(
                    sourceKey = uriString,
                    kind = kind,
                    displayName = child.name,
                    mime = child.mime.ifBlank { mimeForName(child.name) ?: "application/octet-stream" },
                    sizeBytes = child.sizeBytes,
                    modifiedAt = child.modifiedAt,
                    contentUri = uriString,
                )
            }
        }
    }

    /** SAF 子文档的扫描所需列。 */
    private data class SafEntry(
        val documentId: String,
        val name: String,
        val mime: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
    ) {
        val isDirectory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun querySafChildren(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String,
    ): List<SafEntry>? = runCatching {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val entries = mutableListOf<SafEntry>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex).orEmpty()
                val name = cursor.getString(nameIndex).orEmpty()
                if (documentId.isEmpty() || name.isEmpty()) continue
                entries += SafEntry(
                    documentId = documentId,
                    name = name,
                    mime = cursor.getString(mimeIndex).orEmpty(),
                    // SIZE/LAST_MODIFIED 可能缺省：按 0 处理（SIZE 缺省会落到最小尺寸过滤）。
                    sizeBytes = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L,
                    modifiedAt = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L,
                )
            }
        }
        entries
    }.getOrNull()

    private fun walkFiles(
        root: File,
        depth: Int = 0,
        excludedTopLevel: Set<String> = emptySet(),
    ): List<Candidate> {
        if (depth > MAX_WALK_DEPTH || !root.isDirectory) return emptyList()
        val result = mutableListOf<Candidate>()
        root.listFiles()?.forEach { file ->
            if (file.name.startsWith(".")) return@forEach
            if (depth == 0 && file.name in excludedTopLevel) return@forEach
            if (file.isDirectory) {
                // .nomedia 目录按系统约定不进入媒体扫描，这里同样跳过。
                if (File(file, NOMEDIA_NAME).exists()) return@forEach
                result += walkFiles(file, depth + 1)
            } else {
                val kind = kindForName(file.name) ?: return@forEach
                val size = file.length()
                if (size < MIN_SIZE_BYTES) return@forEach
                result += Candidate(
                    sourceKey = file.absolutePath,
                    kind = kind,
                    displayName = file.name,
                    mime = mimeForName(file.name) ?: "application/octet-stream",
                    sizeBytes = size,
                    modifiedAt = file.lastModified(),
                    filePath = file.absolutePath,
                )
            }
        }
        return result
    }

    /**
     * SAF 遍历按 MIME 判类型（名称扩展名只作回落）。
     * PDF（页图嵌入）与 DOCX（zip 抽正文）已有对应抽取通道，判为 DOCUMENT；其余二进制文档仍不收。
     */
    private fun kindForMime(mime: String): MediaKind? = when {
        mime.startsWith("image/") -> MediaKind.IMAGE
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        mime.startsWith("text/") || mime in TEXT_DOCUMENT_MIMES -> MediaKind.DOCUMENT
        else -> null
    }

    /** MIME 判定里的「已知文档类型」：可直读 UTF-8 文本的格式 + 已有抽取通道的 PDF/DOCX。 */
    private val TEXT_DOCUMENT_MIMES = setOf(
        "application/json",
        "application/xml",
        "application/yaml",
        "application/javascript",
        "application/x-sh",
        "application/sql",
        MediaExtraction.PDF_MIME,
        MediaExtraction.DOCX_MIME,
    )

    internal fun kindForName(name: String): MediaKind? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> MediaKind.IMAGE
            "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v" -> MediaKind.VIDEO
            "mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "amr" -> MediaKind.AUDIO
            "txt", "md", "json", "csv", "log", "xml", "yaml", "yml", "kt", "java", "py", "sh",
            "pdf", "docx" ->
                MediaKind.DOCUMENT
            else -> null
        }
    }

    internal fun mimeForName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "amr" -> "audio/amr"
            "txt", "log" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "xml" -> "text/xml"
            "yaml", "yml" -> "text/yaml"
            "pdf" -> MediaExtraction.PDF_MIME
            "docx" -> MediaExtraction.DOCX_MIME
            else -> null
        }
    }
}
