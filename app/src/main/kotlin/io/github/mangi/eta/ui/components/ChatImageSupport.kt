package io.github.mangi.eta.ui.components

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.mangi.eta.agent.media.AgentChatImageCache
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.terminal.LinuxGuestPathResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Request
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

internal class LoadedChatImage(
    val bytes: ByteArray,
    val mimeType: String,
    val bitmap: ImageBitmap,
)

internal class ChatImagePayload(
    val bytes: ByteArray,
    val mimeType: String,
)

internal object ChatImageBytes {
    private const val DISPLAY_MAX_EDGE = 4096
    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val READ_TIMEOUT_MS = 20_000L

    private val httpClient by lazy {
        AgentHttpClient.client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    fun load(context: Context, source: String): LoadedChatImage? {
        val payload = readBytes(context, source.trim()) ?: return null
        val bitmap = decodeDisplayBitmap(payload.bytes, DISPLAY_MAX_EDGE) ?: return null
        return LoadedChatImage(
            bytes = payload.bytes,
            mimeType = payload.mimeType,
            bitmap = bitmap,
        )
    }

    internal fun readBytes(context: Context, source: String): ChatImagePayload? {
        if (source.isBlank()) return null
        return when {
            source.startsWith("data:image/", ignoreCase = true) -> {
                val bytes = AgentChatImageCache.decodeImageBytes(source) ?: return null
                if (bytes.isEmpty() || bytes.size > MAX_AGENT_IMAGE_BYTES) return null
                ChatImagePayload(bytes, chatImageMimeType(source))
            }
            source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) -> download(source)
            source.startsWith("content://", ignoreCase = true) -> {
                readUri(context, Uri.parse(source))
            }
            source.startsWith("file://", ignoreCase = true) -> {
                val path = Uri.parse(source).path ?: return null
                readFile(chatHostPath(context, path))
            }
            source.startsWith("minis://", ignoreCase = true) ->
                readFile(chatHostPath(context, source))
            source.startsWith("/") -> readFile(chatHostPath(context, source))
            // 相对路径（无 scheme）按 Linux 工作区根解析；模型常把工具产物写成
            // 相对 /workspace 的文件名，写绝对路径时与 read_image 走同一映射。
            else -> {
                val workspace = LinuxGuestPathResolver.workspaceHostForApp(context)
                readFile(File(workspace, source).path)
                    ?: readFile(File(workspace, source.removePrefix("workspace/")).path)
            }
        }
    }

    /** AI 回复里的 /workspace、/var/minis、minis:// 是 Linux 侧路径；与 read_image
     *  同用 LinuxGuestPathResolver 折算到宿主，普通 Android 绝对路径原样返回。 */
    private fun chatHostPath(context: Context, path: String): String =
        LinuxGuestPathResolver.resolveForApp(context, path)

    private fun download(url: String): ChatImagePayload? = runCatching {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val declared = response.body.contentLength()
            if (declared > MAX_AGENT_IMAGE_BYTES) return@use null
            val bytes = readBounded(response.body.byteStream()) ?: return@use null
            val mime = response.header("Content-Type")
                ?.substringBefore(";")
                ?.trim()
                ?.takeIf { it.startsWith("image/", ignoreCase = true) }
                ?: sniffMimeType(bytes)
            ChatImagePayload(bytes, mime)
        }
    }.getOrNull()

    private fun readUri(context: Context, uri: Uri): ChatImagePayload? {
        val mime = context.contentResolver.getType(uri)
            ?.takeIf { it.startsWith("image/", ignoreCase = true) }
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.let(::readBounded)
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_AGENT_IMAGE_BYTES) return null
        return ChatImagePayload(bytes, mime ?: sniffMimeType(bytes))
    }

    private fun readFile(path: String): ChatImagePayload? {
        val file = File(path)
        if (!file.isFile) return null
        if (file.length() <= 0L || file.length() > MAX_AGENT_IMAGE_BYTES) return null
        val bytes = runCatching { readBounded(file.inputStream()) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return ChatImagePayload(bytes, sniffMimeType(bytes))
    }

    private fun readBounded(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        stream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_AGENT_IMAGE_BYTES) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}

internal object ChatImageGallery {
    private const val IMAGE_DIR = "Pictures/Eta"
    private const val VIDEO_DIR = "Movies/Eta"

    fun save(context: Context, bytes: ByteArray, mimeType: String, displayName: String = ""): Uri? {
        if (bytes.isEmpty()) return null
        val mime = mimeType.ifBlank { sniffMimeType(bytes) }
        // 优先用调用方传入的原始文件名；为空（如纯 data: 生成图）才退回生成名，不擅自重命名。
        val name = displayName.ifBlank { chatImageFileName(mime) }
        return writePending(
            context = context,
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            displayName = name,
            mimeType = mime,
            relativePath = IMAGE_DIR,
            bytes = bytes,
        )
    }

    fun saveVideo(context: Context, source: String): Uri? {
        val file = AgentVideoCodec.fileFromSource(source) ?: return null
        if (!file.isFile || file.length() <= 0L) return null
        val mime = AgentVideoCodec.sniffFile(file)
            ?: AgentVideoCodec.previewFromFile(file, source)?.mimeType
            ?: "video/mp4"
        // 保存到相册保留原始文件名（file.name），不重命名为 Eta-时间戳。
        return writePending(
            context = context,
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            displayName = file.name,
            mimeType = mime,
            relativePath = VIDEO_DIR,
            bytes = file.readBytes(),
        )
    }

    private fun writePending(
        context: Context,
        collection: Uri,
        displayName: String,
        mimeType: String,
        relativePath: String,
        bytes: ByteArray,
    ): Uri? {
        if (bytes.isEmpty()) return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: return@runCatching null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}

internal fun chatVideoFileName(mimeType: String, displayName: String = ""): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "Eta-$stamp.${AgentVideoCodec.extensionForMime(mimeType, displayName)}"
}

internal fun markdownImageDestination(content: String, node: ASTNode): String? {
    val destination = findAstChild(node, MarkdownElementTypes.LINK_DESTINATION)
        ?: findAstChild(node, MarkdownElementTypes.AUTOLINK)
        ?: return null
    return destination.getUnescapedTextInNode(content)
        .trim()
        .trim('<', '>')
        .trim()
        .takeIf { it.isNotBlank() }
}

internal fun collectMarkdownImageNodes(node: ASTNode): List<ASTNode> {
    val out = mutableListOf<ASTNode>()
    fun walk(current: ASTNode) {
        if (current.type == MarkdownElementTypes.IMAGE) {
            out += current
            return
        }
        current.children.forEach(::walk)
    }
    walk(node)
    return out
}


internal fun collectMarkdownImageSources(content: String): List<String> {
    // All Markdown image forms (inline and reference) require this opener.
    if (!content.contains("![")) return emptyList()
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    return collectMarkdownImageNodes(root)
        .mapNotNull { markdownImageDestination(content, it) }
        .filter(::isDirectChatImageSource)
}

internal fun resolveChatImageSource(
    content: String,
    node: ASTNode,
    sourceOverride: String? = null,
): String? {
    sourceOverride?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    markdownImageDestination(content, node)?.let { return it }
    return content.trim().takeIf(::isDirectChatImageSource)
}

internal fun isDirectChatImageSource(value: String): Boolean {
    val source = value.trim()
    return source.startsWith("data:image/", ignoreCase = true) ||
        source.startsWith("http://", ignoreCase = true) ||
        source.startsWith("https://", ignoreCase = true) ||
        source.startsWith("content://", ignoreCase = true) ||
        source.startsWith("file://", ignoreCase = true) ||
        source.startsWith("minis://", ignoreCase = true) ||
        (source.startsWith("/") && source.contains('.')) ||
        // 相对路径按 Linux 工作区根解析；要求像文件名（含扩展名、无空格），
        // 避免把纯文本片段当成图片源收进画廊。
        (source.contains('.') && !source.contains(':') && !source.contains(' '))
}

internal fun chatImageMimeType(source: String): String {
    val trimmed = source.trim()
    if (trimmed.startsWith("data:", ignoreCase = true)) {
        val mime = trimmed.removePrefix("data:").substringBefore(";").trim()
        if (mime.startsWith("image/", ignoreCase = true)) return mime.lowercase(Locale.US)
    }
    return "image/jpeg"
}

internal fun chatImageFileExtension(mimeType: String): String = when (mimeType.lowercase(Locale.US)) {
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/jpeg", "image/jpg" -> "jpg"
    else -> "jpg"
}

internal fun chatImageFileName(mimeType: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "Eta-$stamp.${chatImageFileExtension(mimeType)}"
}

internal fun sniffMimeType(bytes: ByteArray): String {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return options.outMimeType?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
}

internal fun decodeDisplayBitmap(
    bytes: ByteArray,
    maxEdge: Int = 4096,
): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxEdge) {
        sample *= 2
    }
    val bitmap = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        },
    ) ?: return null
    bitmap.density = Bitmap.DENSITY_NONE
    return bitmap.asImageBitmap()
}

private fun findAstChild(node: ASTNode, type: IElementType): ASTNode? {
    if (node.type == type) return node
    node.children.forEach { child ->
        findAstChild(child, type)?.let { return it }
    }
    return null
}
