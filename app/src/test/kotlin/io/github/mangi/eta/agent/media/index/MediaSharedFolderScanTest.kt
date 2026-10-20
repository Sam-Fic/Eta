package io.github.mangi.eta.agent.media.index

import android.content.Context
import io.github.mangi.eta.data.db.MediaKind
import io.github.mangi.eta.data.db.MediaSourceEntity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 共享文件夹与 SAF 扫描源的「发现」单测。
 *
 * 共享文件夹走本地目录（SharedFolderMount.sourcePath），纯 JVM 可测：
 * - sourceKey 取绝对路径、kind/mime 判定、filePath 落本地路径；
 * - 隐藏文件/目录、小于 8KB、.nomedia 目录、未知类型的过滤。
 * SAF tree 依赖 DocumentsProvider 遍历，无法纯 JVM 覆盖；
 * 这里只验证「无持久化授权时静默返回空清单」的降级路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaSharedFolderScanTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "eta-shared-scan-${System.nanoTime()}")
        check(root.mkdirs()) { "创建共享目录失败：$root" }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun sharedSource(): MediaSourceEntity = MediaSourceEntity(
        id = "${MediaSourceScanner.TYPE_SHARED_FOLDER}:share",
        type = MediaSourceScanner.TYPE_SHARED_FOLDER,
        uri = root.absolutePath,
        label = "共享文件夹：share",
        enabled = true,
    )

    /** 写一个扫描候选文件：默认 8KB（等于 MIN_SIZE_BYTES 边界，应被收录）。 */
    private fun write(name: String, bytes: Int = 8_192): File {
        val file = File(root, name)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes) { index -> (index % 251).toByte() })
        return file
    }

    @Test
    fun sharedFolderScanYieldsPathKeyedCandidates() {
        val image = write("photo.png")
        val note = write("sub/note.txt")
        val candidates = MediaSourceScanner.scan(context, sharedSource())
        assertEquals(
            setOf(image.absolutePath, note.absolutePath),
            candidates.mapTo(mutableSetOf()) { it.sourceKey },
        )
        val photo = candidates.first { it.sourceKey == image.absolutePath }
        assertEquals(MediaKind.IMAGE, photo.kind)
        assertEquals("image/png", photo.mime)
        assertEquals(image.length(), photo.sizeBytes)
        assertEquals(image.absolutePath, photo.filePath)
        // 本地文件候选不带 contentUri。
        assertEquals(null, photo.contentUri)
        val text = candidates.first { it.sourceKey == note.absolutePath }
        assertEquals(MediaKind.DOCUMENT, text.kind)
        assertEquals("text/plain", text.mime)
        // 预估与扫描同口径。
        val estimate = MediaSourceScanner.estimate(context, sharedSource())
        assertEquals(2, estimate.count)
        assertEquals(image.length() + note.length(), estimate.bytes)
    }

    @Test
    fun sharedFolderScanFiltersHiddenTinyNomediaAndUnknownEntries() {
        val kept = write("keep.txt")
        write(".hidden.txt")
        write("tiny.png", bytes = 4 * 1024)
        write("blob.bin")
        write(".git/inner.txt")
        write("nomedia-dir/inner.txt")
        write("nomedia-dir/.nomedia", bytes = 16)
        val candidates = MediaSourceScanner.scan(context, sharedSource())
        assertEquals(listOf(kept.absolutePath), candidates.map { it.sourceKey })
    }

    @Test
    fun sharedFolderScanHandlesMissingDirectoryWithoutThrowing() {
        val gone = sharedSource().copy(uri = File(root, "not-there").absolutePath)
        assertEquals(emptyList<MediaSourceScanner.Candidate>(), MediaSourceScanner.scan(context, gone))
    }

    @Test
    fun safTreeWithoutPersistedPermissionIsSkippedSilently() {
        val source = MediaSourceEntity(
            id = "${MediaSourceScanner.TYPE_SAF_TREE}:deadbeef0000",
            type = MediaSourceScanner.TYPE_SAF_TREE,
            uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            label = "Documents",
            enabled = true,
        )
        assertFalse(MediaSourceScanner.hasPersistedPermission(context, source.uri))
        // 授权丢失时返回空清单而不是抛异常，索引循环可继续走其他源。
        assertEquals(emptyList<MediaSourceScanner.Candidate>(), MediaSourceScanner.scan(context, source))
    }
}
