package io.github.mangi.eta.ui.markdown

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import androidx.core.content.FileProvider
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.BoundedFileCopy
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.LinuxGuestPathResolver
import io.github.mangi.eta.agent.terminal.shellQuote
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun chatLocalFilePath(link: String): String? {
    val value = link.trim()
    if (value.startsWith("/") && !value.startsWith("//")) return value.takeUnless { '\u0000' in it }
    val uri = Uri.parse(value)
    if (uri.scheme != "file" || !uri.host.isNullOrEmpty() && uri.host != "localhost") return null
    return uri.path?.takeIf { it.startsWith("/") && '\u0000' !in it }
}

internal fun chatFileMime(name: String): String = when (File(name).extension.lowercase()) {
    "apk" -> "application/vnd.android.package-archive"
    "md", "log", "kt", "py" -> "text/plain"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(name).extension.lowercase()) ?: "application/octet-stream"
}

/** Share a bounded snapshot, never grant access to a broad external-storage provider root. */
internal fun stageChatFile(context: Context, requested: String): File {
    val mapped = LinuxGuestPathResolver.resolveForApp(context, requested)
    val source = File(mapped).canonicalFile
    val roots = listOf(File("/storage/emulated/0"), File("/sdcard"), LinuxGuestPathResolver.workspaceHostForApp(context))
        .map { it.canonicalPath }
    require(roots.any { source.path.startsWith("$it/") }) { context.getString(R.string.cfl_outside_roots) }
    val directory = File(context.cacheDir, "chat-files").apply { mkdirs() }
    val folder = File(directory, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
    val destination = File(folder, source.name)
    try {
        if (source.isFile && source.canRead()) {
            source.inputStream().use { input -> destination.outputStream().use { BoundedFileCopy.copy(input, it, MAX_CHAT_FILE_BYTES) } }
        } else {
            check(RootAccess.isGranted) { context.getString(R.string.cfl_not_found_or_permission) }
            // Resolve symlinks in the same privileged context that reads the file.
            val cases = roots.joinToString("|") { shellQuote("$it/") + "*" }
            val command = "p=\$(realpath -- ${shellQuote(source.path)}) || exit 1; " +
                "case \"\$p\" in $cases) ;; *) exit 1;; esac; " +
                "[ -f \"\$p\" ] && [ \$(stat -c %s -- \"\$p\") -le $MAX_CHAT_FILE_BYTES ] || exit 1; " +
                "head -c ${MAX_CHAT_FILE_BYTES + 1} -- \"\$p\" > ${shellQuote(destination.path)}"
            check(destination.createNewFile()) { context.getString(R.string.cfl_cannot_create_copy) }
            BoundedRootCommandExecutor(AndroidAgentLogger).use { root ->
                check(root.execute(command, timeoutMillis = 15_000, maxOutputBytes = 1024).ok) { context.getString(R.string.cfl_unreadable_oversize) }
            }
            check(destination.length() <= MAX_CHAT_FILE_BYTES) { context.getString(R.string.cfl_oversize) }
        }
        return destination
    } catch (e: Exception) {
        destination.delete()
        folder.delete()
        throw e
    }
}

@Composable
internal fun rememberChatUriHandler(parent: UriHandler): UriHandler {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, parent, scope) {
        object : UriHandler {
            override fun openUri(uri: String) {
                scope.launch {
                    try {
                        val path = chatLocalFilePath(uri)
                        if (path == null) {
                            require(Uri.parse(uri).scheme !in listOf(null, "file")) { context.getString(R.string.cfl_unsupported_link) }
                            parent.openUri(uri)
                        } else {
                            val file = withContext(Dispatchers.IO) { stageChatFile(context, path) }
                            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(contentUri, chatFileMime(file.name))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                clipData = android.content.ClipData.newRawUri(file.name, contentUri)
                            })
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.cfl_open_failed, e.message ?: context.getString(R.string.cfl_no_app)), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

private const val MAX_CHAT_FILE_BYTES = 64L * 1024 * 1024
