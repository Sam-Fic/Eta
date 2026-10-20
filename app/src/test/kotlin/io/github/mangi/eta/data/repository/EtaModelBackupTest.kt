package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.media.index.EmbeddingModelStore
import io.github.mangi.eta.agent.media.index.MediaIndexTestFixtures
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class EtaModelBackupTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.noBackupFilesDir.deleteRecursively()
        File(context.filesDir, "model-restore").deleteRecursively()
        // 两个模型仓库是进程内单例，跨用例残留会污染恢复后的状态；本用例从默认状态起步。
        MediaIndexTestFixtures.resetModelStore()
    }

    private fun digest(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    private fun source(
        root: File,
        kind: String,
        id: String,
        revision: String,
        files: Map<String, Pair<ByteArray, String?>>,
    ): EtaModelBackupSource {
        val directory = File(root, "$kind-$id-$revision").apply { mkdirs() }
        files.forEach { (name, pair) -> File(directory, name).writeBytes(pair.first) }
        return EtaModelBackupSource(
            kind, id, revision, directory,
            files.map { EtaModelBackupFile(it.key, it.value.first.size.toLong(), it.value.second) },
        )
    }

    private fun archive(document: EtaModelBackupDocument, sources: List<EtaModelBackupSource>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val writer = BackupZipWriter(zip)
            writer.text(EtaModelBackupDocument.MANIFEST_NAME, Json.encodeToString(EtaModelBackupDocument.serializer(), document))
            EtaModelBackup.writeFiles(writer, sources)
        }
        return output.toByteArray()
    }

    @Test fun roundTripRestoresModelsIntoNoBackupFilesDir() {
        val weights = "weights-0123456789".toByteArray()
        val vocab = "vocab\tline\n".toByteArray()
        val speech = byteArrayOf(0, 1, 2, 3, -1, -2)
        val sources = listOf(
            source(folder.root, EtaModelBackup.KIND_EMBEDDING, "test-embedding", "rev1",
                mapOf("model.litertlm" to (weights to digest(weights)), "vocab.txt" to (vocab to null))),
            source(folder.root, EtaModelBackup.KIND_SPEECH, "test-speech", "rev2",
                mapOf("pack.ncnn" to (speech to digest(speech)))),
        )
        val bytes = archive(EtaModelBackup.document(sources), sources)

        val summary = EtaModelBackup.importArchive(context, ByteArrayInputStream(bytes))

        // 恢复后两个模型仓库被标记为重新校验：即时状态是内部瞬态（取决于排队协程的执行时机），
        // 只断言复核结束后的稳定态——默认目录下没有真实模型，因此不应处于就绪态。
        shadowOf(context.mainLooper).idle()
        assertFalse(EmbeddingModelStore.state.value.ready)

        assertEquals(2, summary.modelCount)
        assertEquals(3, summary.fileCount)
        assertEquals((weights.size + vocab.size + speech.size).toLong(), summary.bytes)
        val embeddingRoot = File(context.noBackupFilesDir, "embedding/test-embedding/rev1")
        assertEquals(weights.contentToString(), File(embeddingRoot, "model.litertlm").readBytes().contentToString())
        assertEquals(vocab.contentToString(), File(embeddingRoot, "vocab.txt").readBytes().contentToString())
        assertEquals(speech.contentToString(),
            File(context.noBackupFilesDir, "speech/test-speech/rev2/pack.ncnn").readBytes().contentToString())
    }

    @Test fun hashMismatchLeavesExistingModelUntouched() {
        val existing = File(context.noBackupFilesDir, "embedding/m/r/m.bin")
        existing.parentFile.mkdirs()
        existing.writeText("original")
        val manifest = buildJsonObject {
            put("format", EtaModelBackupDocument.FORMAT)
            put("schemaVersion", 1)
            put("exportedAt", 1L)
            putJsonArray("models") {
                add(buildJsonObject {
                    put("kind", "embedding")
                    put("id", "m")
                    put("revision", "r")
                    putJsonArray("files") {
                        add(buildJsonObject {
                            put("name", "m.bin")
                            put("bytes", 9L)
                            put("sha256", digest("expected".toByteArray()))
                        })
                    }
                })
            }
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(EtaModelBackupDocument.MANIFEST_NAME))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(EtaModelBackup.entryName("embedding", "m", "r", "m.bin")))
            zip.write("corrupted".toByteArray())
            zip.closeEntry()
        }

        val failure = runCatching { EtaModelBackup.importArchive(context, ByteArrayInputStream(output.toByteArray())) }
        assertTrue(failure.isFailure)
        assertEquals("original", existing.readText())
    }

    @Test fun dataBackupArchiveIsRejectedWithPointerToDataImport() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(EtaBackupDocument.MANIFEST_NAME))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        val failure = runCatching { EtaModelBackup.importArchive(context, ByteArrayInputStream(output.toByteArray())) }
        val message = requireNotNull(failure.exceptionOrNull()?.message)
        assertTrue(message.contains("数据备份"))
    }

    @Test fun validateRejectsUntrustedDocuments() {
        val base = EtaModelBackupModel(
            kind = EtaModelBackup.KIND_EMBEDDING, id = "id", revision = "rev",
            files = listOf(EtaModelBackupFile("m.bin", 1, digest("x".toByteArray()))),
        )
        fun document(vararg models: EtaModelBackupModel, format: String = EtaModelBackupDocument.FORMAT) =
            EtaModelBackupDocument(exportedAt = 1L, models = models.toList(), format = format)

        assertTrue(runCatching { EtaModelBackup.validate(document(base)) }.isSuccess)
        assertTrue(runCatching { EtaModelBackup.validate(document(base, format = "eta-backup")) }.isFailure)
        assertTrue(runCatching { EtaModelBackup.validate(document(base.copy(kind = "vector"))) }.isFailure)
        assertTrue(runCatching { EtaModelBackup.validate(document(base.copy(id = "../evil"))) }.isFailure)
        assertTrue(runCatching { EtaModelBackup.validate(document(base, base)) }.isFailure)
        assertTrue(runCatching { EtaModelBackup.validate(document(base.copy(files = listOf(
            base.files.single().copy(sha256 = "not-a-hash"))))) }.isFailure)
        assertTrue(runCatching { EtaModelBackup.validate(document(base.copy(
            files = listOf(EtaModelBackupFile("m.bin", 1, digest("x".toByteArray())),
                EtaModelBackupFile("m.bin", 1, null))))) }.isFailure)
        assertTrue(runCatching { EtaModelBackup.validate(document(base.copy(files = emptyList()))) }.isFailure)
    }

    @Test fun verifyRejectsEntriesNotCoveredByManifest() {
        val document = EtaModelBackupDocument(
            exportedAt = 1L,
            models = listOf(EtaModelBackupModel(
                kind = EtaModelBackup.KIND_EMBEDDING, id = "id", revision = "rev",
                files = listOf(EtaModelBackupFile("m.bin", 1, null)))),
        )
        val extra = EtaModelBackup.entryName("embedding", "id", "rev", "extra.bin")
        val staged = mapOf(
            EtaModelBackup.entryName("embedding", "id", "rev", "m.bin") to
                folder.newFile("m.bin").apply { writeBytes(byteArrayOf(0)) },
            extra to folder.newFile("extra.bin"),
        )
        assertTrue(runCatching { EtaModelBackup.verify(staged, document) }.isFailure)
        assertTrue(runCatching { EtaModelBackup.verify(staged.filterKeys { it != extra }, document) }.isSuccess)
    }
}
