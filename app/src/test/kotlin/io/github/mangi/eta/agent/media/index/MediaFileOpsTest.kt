package io.github.mangi.eta.agent.media.index

import android.content.Context
import io.github.mangi.eta.agent.media.index.MediaFileOps.MediaDir
import io.github.mangi.eta.agent.media.index.MediaFileOps.MediaTarget
import io.github.mangi.eta.agent.media.index.MediaFileOps.OpResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 文件操作层单测：普通 File 分支的移动/复制、重名自动后缀、目标目录缺失与源缺失错误码，
 * 以及 SAF / MediaStore 分支里可抽出的纯函数（[MediaDir.parse] / [MediaTarget.parse] /
 * `uniqueName`）。
 *
 * MediaStore 与 SAF 的 provider 行为依赖真实系统服务，单测只覆盖调度前的解析与命名；
 * 端到端行为由 GUI / AI 工具在设备上验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaFileOpsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    // ---------------------------------------------------------------- 纯函数

    @Test
    fun uniqueNameKeepsFreeNameAndAppendsSuffixOnConflict() {
        assertEquals("a.jpg", MediaFileOps.uniqueName("a.jpg", emptySet()))
        assertEquals("a (1).jpg", MediaFileOps.uniqueName("a.jpg", setOf("a.jpg")))
        // 逐个递增直到空位，绝不覆盖已有文件。
        assertEquals(
            "a (2).jpg",
            MediaFileOps.uniqueName("a.jpg", setOf("a.jpg", "a (1).jpg")),
        )
        // 无扩展名整体视为主名。
        assertEquals("README (1)", MediaFileOps.uniqueName("README", setOf("README")))
        // 隐藏文件 `.nomedia` 的末尾点不算扩展名。
        assertEquals(".nomedia (1)", MediaFileOps.uniqueName(".nomedia", setOf(".nomedia")))
    }

    @Test
    fun mediaDirParseAcceptsTreeUriAndAbsolutePathOnly() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
        assertEquals(tree, MediaDir.parse(tree)?.treeUri)
        assertEquals("/storage/emulated/0/DCIM", MediaDir.parse(" /storage/emulated/0/DCIM ")?.path)
        // 相对路径既不是 SAF tree 也不是绝对路径，视为非法。
        assertEquals(null, MediaDir.parse("DCIM/Camera"))
        assertEquals(null, MediaDir.parse("   "))
    }

    @Test
    fun mediaTargetParseRecoversMissingUriOrPathFromSourceKey() {
        val uriKey = "content://media/external/images/media/42"
        assertEquals(uriKey, MediaTarget.parse(uriKey)?.uri)
        val pathKey = "/storage/emulated/0/DCIM/a.jpg"
        assertEquals(pathKey, MediaTarget.parse(pathKey)?.path)
        // 显式字段优先于 sourceKey 形态。
        val explicit = MediaTarget.parse("source-key", uri = uriKey, path = pathKey)
        assertEquals(uriKey, explicit?.uri)
        assertEquals(pathKey, explicit?.path)
        // sourceKey 形态无法自解释时仍保留 key，供索引清理使用。
        val opaque = MediaTarget.parse("workspace:doc-1")
        assertNotNull(opaque)
        assertEquals(null, opaque?.uri)
        assertEquals(null, opaque?.path)
    }

    // ---------------------------------------------------------------- 普通 File：复制

    @Test
    fun copyFileAddsSuffixAndNeverOverwritesExistingFile() {
        val source = temp.newFile("photo.jpg").apply { writeText("new-content") }
        val targetDir = temp.newFolder("target")
        val existing = File(targetDir, "photo.jpg").apply { writeText("old-content") }

        val result = MediaFileOps.copyFile(context, source, MediaDir(path = targetDir.absolutePath))

        assertTrue(result is OpResult.Ok)
        val newPath = (result as OpResult.Ok).newPath
        assertEquals(File(targetDir, "photo (1).jpg").absolutePath, newPath)
        // 两侧内容都保持各自原样：同名文件绝不覆盖。
        assertEquals("old-content", existing.readText())
        assertEquals("new-content", File(newPath!!).readText())
        assertTrue(source.exists())
    }

    @Test
    fun copyFileCreatesMissingTargetDirectory() {
        val source = temp.newFile("note.txt").apply { writeText("hello") }
        val targetDir = File(temp.root, "missing/nested")

        val result = MediaFileOps.copyFile(context, source, MediaDir(path = targetDir.absolutePath))

        assertTrue(result is OpResult.Ok)
        assertTrue(targetDir.isDirectory)
        assertEquals("hello", File(targetDir, "note.txt").readText())
    }

    @Test
    fun copyFileToFileTargetReportsTargetNotDirectory() {
        val source = temp.newFile("a.txt").apply { writeText("x") }
        val notDir = temp.newFile("plain-file")

        val result = MediaFileOps.copyFile(context, source, MediaDir(path = notDir.absolutePath))

        assertTrue(result is OpResult.Fail)
        assertEquals(MediaFileOps.ErrorCode.TARGET_NOT_DIRECTORY, (result as OpResult.Fail).code)
    }

    @Test
    fun copyFileWithMissingSourceReportsSourceMissing() {
        val targetDir = temp.newFolder("target")
        val missing = File(temp.root, "missing.txt")

        val result = MediaFileOps.copyFile(context, missing, MediaDir(path = targetDir.absolutePath))

        assertTrue(result is OpResult.Fail)
        assertEquals(MediaFileOps.ErrorCode.SOURCE_MISSING, (result as OpResult.Fail).code)
    }

    // ---------------------------------------------------------------- 普通 File：移动

    @Test
    fun moveFileRelocatesAndRemovesSource() {
        val sourceDir = temp.newFolder("from")
        val targetDir = temp.newFolder("to")
        val source = File(sourceDir, "clip.mp4").apply { writeText("frames") }

        val result = MediaFileOps.moveFile(context, source, MediaDir(path = targetDir.absolutePath))

        assertTrue(result is OpResult.Ok)
        assertEquals(File(targetDir, "clip.mp4").absolutePath, (result as OpResult.Ok).newPath)
        assertFalse(source.exists())
        assertEquals("frames", File(targetDir, "clip.mp4").readText())
    }

    @Test
    fun moveFileIntoMissingDirectoryCreatesIt() {
        val source = temp.newFile("doc.pdf").apply { writeText("pages") }
        val targetDir = File(temp.root, "created/now")

        val result = MediaFileOps.moveFile(context, source, MediaDir(path = targetDir.absolutePath))

        assertTrue(result is OpResult.Ok)
        assertTrue(targetDir.isDirectory)
        assertFalse(source.exists())
        assertEquals("pages", File(targetDir, "doc.pdf").readText())
    }

    @Test
    fun moveFileWithinSameDirectoryKeepsSingleFile() {
        val dir = temp.newFolder("same")
        val source = File(dir, "one.txt").apply { writeText("1") }

        val result = MediaFileOps.moveFile(context, source, MediaDir(path = dir.absolutePath))

        assertTrue(result is OpResult.Ok)
        assertEquals(source.absolutePath, (result as OpResult.Ok).newPath)
        assertTrue(source.exists())
        // 未额外生成「one (1).txt」。
        assertEquals(listOf("one.txt"), dir.list().orEmpty().toList())
    }

    @Test
    fun moveFileWithMissingSourceReportsSourceMissing() {
        val targetDir = temp.newFolder("target")

        val result = MediaFileOps.moveFile(context, File(temp.root, "nope.bin"), MediaDir(path = targetDir.absolutePath))

        assertTrue(result is OpResult.Fail)
        assertEquals(MediaFileOps.ErrorCode.SOURCE_MISSING, (result as OpResult.Fail).code)
    }

    @Test
    fun moveFileWithoutTargetReportsTargetRequired() {
        val source = temp.newFile("a.txt").apply { writeText("x") }

        // 两个字段都缺省的 MediaDir：调用方没拿到目标文件夹时给明确错误码。
        val result = MediaFileOps.moveFile(context, source, MediaDir())

        assertTrue(result is OpResult.Fail)
        assertEquals(MediaFileOps.ErrorCode.TARGET_REQUIRED, (result as OpResult.Fail).code)
    }
}
