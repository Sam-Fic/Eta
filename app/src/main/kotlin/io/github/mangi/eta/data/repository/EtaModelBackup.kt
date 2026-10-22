package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.media.index.EmbeddingModelCatalog
import io.github.mangi.eta.agent.media.index.EmbeddingModelStore
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.SpeechModelCatalog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 已下载模型的单独备份。
 *
 * 模型文件位于 `noBackupFilesDir`，卸载即丢失；这里把它们按 `models/<kind>/<id>/<revision>/<file>`
 * 归档，既可并入数据备份，也可单独导出/恢复。恢复时先整包校验再落盘，避免半份模型覆盖现有文件。
 *
 * 除语音/向量清单外，还会自动发现 `noBackupFilesDir/<kind>/<id>/<revision>/` 布局下的任意完整模型
 * 目录（如后续新增的动作模型），导出时现场计算哈希；不符合该三级布局的条目（例如 `vd-web-pairing`
 * 配对数据）不会被打包。新增端侧模型无需修改备份代码。
 */
@Serializable
internal data class EtaModelBackupDocument(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val models: List<EtaModelBackupModel> = emptyList(),
) {
    companion object {
        const val FORMAT = "eta-model-backup"
        const val SCHEMA_VERSION = 1
        const val MANIFEST_NAME = "eta-models.json"
        const val PREFIX = "models/"
    }
}

/** 一个模型变体（同一 id 的不同 revision 视为不同模型）。 */
@Serializable
internal data class EtaModelBackupModel(
    val kind: String,
    val id: String,
    val revision: String,
    val files: List<EtaModelBackupFile> = emptyList(),
)

@Serializable
internal data class EtaModelBackupFile(
    val name: String,
    val bytes: Long,
    /** 上游未发布哈希的文本文件为 null，恢复时只校验长度。 */
    val sha256: String? = null,
)

internal data class EtaModelBackupSummary(
    val modelCount: Int,
    val fileCount: Int,
    val bytes: Long,
) {
    companion object {
        val EMPTY = EtaModelBackupSummary(modelCount = 0, fileCount = 0, bytes = 0)
    }
}

/** 导出时的模型来源：目录 + 清单元数据，测试可构造小文件替代真实大模型。 */
internal class EtaModelBackupSource(
    val kind: String,
    val id: String,
    val revision: String,
    val directory: File,
    val files: List<EtaModelBackupFile>,
)

internal object EtaModelBackup {
    const val KIND_EMBEDDING = "embedding"
    const val KIND_SPEECH = "speech"

    private val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]{1,200}")
    private val SHA256 = Regex("[0-9a-f]{64}")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    /** 已下载且文件齐全的模型（只按长度判断，完整哈希由归档写入时校验）。 */
    fun downloadedSources(context: Context): List<EtaModelBackupSource> {
        val sources = mutableListOf<EtaModelBackupSource>()
        EmbeddingModelCatalog.models.forEach { model ->
            val directory = EmbeddingModelStore.directory(context, model)
            val files = model.assets.map { EtaModelBackupFile(it.name, it.bytes, it.sha256) }
            if (complete(directory, files)) {
                sources += EtaModelBackupSource(KIND_EMBEDDING, model.id, model.revision, directory, files)
            }
        }
        SpeechModelCatalog.models.forEach { model ->
            val directory = OfflineSpeechPack.directory(context, model)
            val files = model.assets.map { EtaModelBackupFile(it.name, it.bytes, it.sha256) }
            if (complete(directory, files)) {
                sources += EtaModelBackupSource(KIND_SPEECH, model.id, model.revision, directory, files)
            }
        }
        return sources + discoveredSources(context, sources)
    }

    /**
     * 清单之外的自动发现：扫描 `noBackupFilesDir/<kind>/<id>/<revision>/` 三级布局下含普通文件的目录。
     * 新增端侧模型（如动作模型）只要按该布局落盘就会被备份，无需登记清单；哈希在导出时现场计算，
     * 恢复后由文件校验保证一致。半下载目录靠“存在临时文件或全部零字节”识别并跳过。
     */
    private fun discoveredSources(context: Context, listed: List<EtaModelBackupSource>): List<EtaModelBackupSource> {
        val root = context.applicationContext.noBackupFilesDir
        if (!root.isDirectory) return emptyList()
        val known = listed.mapTo(hashSetOf()) { "${it.kind}/${it.id}/${it.revision}" }
        val discovered = mutableListOf<EtaModelBackupSource>()
        root.childDirectories().forEach { kindDir ->
            if (!safeSegment(kindDir.name)) return@forEach
            kindDir.childDirectories().forEach { idDir ->
                if (!safeSegment(idDir.name)) return@forEach
                idDir.childDirectories().forEach { revisionDir ->
                    if (!safeSegment(revisionDir.name)) return@forEach
                    val path = "${kindDir.name}/${idDir.name}/${revisionDir.name}"
                    if (path in known) return@forEach
                    val children = revisionDir.listFiles()?.toList() ?: return@forEach
                    if (children.none { it.isFile }) return@forEach
                    if (children.any { it.name.endsWith(".part") || it.name.endsWith(".tmp") }) return@forEach
                    val files = children.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                        .sortedBy { it.name }.map { file ->
                        EtaModelBackupFile(file.name, file.length(), runCatching { BackupDurability.digest(file) }.getOrNull())
                    }
                    if (files.any { it.bytes > 0 }) {
                        discovered += EtaModelBackupSource(kindDir.name, idDir.name, revisionDir.name, revisionDir, files)
                    }
                }
            }
        }
        return discovered.sortedBy { "${it.kind}/${it.id}/${it.revision}" }
    }

    private fun File.childDirectories(): List<File> =
        listFiles()?.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }?.sortedBy { it.name }?.toList()
            ?: emptyList()

    fun downloaded(context: Context): EtaModelBackupSummary =
        summary(downloadedSources(context).map(::modelRecord))

    suspend fun export(context: Context, output: OutputStream): EtaModelBackupSummary {
        val sources = downloadedSources(context)
        require(sources.isNotEmpty()) { "没有可备份的模型，请先在语音或媒体索引设置中下载" }
        val document = document(sources)
        ZipOutputStream(output).use { zip ->
            // 模型文件已接近不可压缩，低压缩级别避免长时间占用 CPU。
            zip.setLevel(1)
            val writer = BackupZipWriter(zip)
            writer.text(EtaModelBackupDocument.MANIFEST_NAME, json.encodeToString(document))
            writeFiles(writer, sources)
            zip.finish()
        }
        return document.summary()
    }

    fun importArchive(context: Context, input: InputStream): EtaModelBackupSummary {
        val app = context.applicationContext
        val operation = File(app.filesDir, "model-restore")
        operation.deleteRecursively()
        BackupDurability.mkdirs(operation)
        try {
            val archive = File(operation, "input.zip")
            archive.outputStream().use {
                BackupArchiveSafety.copyLimited(input, it, BackupArchiveSafety.TOTAL_LIMIT, operation)
            }
            val header = archive.inputStream().use { it.readNBytes(2) }
            require(header.contentEquals(byteArrayOf(0x50, 0x4b))) { "这不是 ZIP 模型备份" }
            java.util.zip.ZipFile(archive).use { zip ->
                require(zip.getEntry(EtaBackupDocument.MANIFEST_NAME) == null &&
                    zip.getEntry(EtaConversationExport.MANIFEST_NAME) == null) {
                    "这是数据备份，请使用「导入数据」"
                }
            }
            val files = BackupArchiveSafety.stageZip(archive, File(operation, "staged"))
            require(files.keys.none {
                it == EtaBackupDocument.MANIFEST_NAME || it == EtaConversationExport.MANIFEST_NAME
            }) { "这是数据备份，请使用「导入数据」" }
            val manifest = files[EtaModelBackupDocument.MANIFEST_NAME]
                ?: throw EtaBackupException("模型备份文件缺少清单")
            val document = decode(manifest.readText())
            validate(document)
            require(document.models.isNotEmpty()) { "模型备份中没有模型" }
            val summary = verify(files, document)
            install(app, files, document)
            return summary
        } finally {
            operation.deleteRecursively()
        }
    }

    fun document(sources: List<EtaModelBackupSource>): EtaModelBackupDocument =
        EtaModelBackupDocument(
            exportedAt = System.currentTimeMillis(),
            models = sources.map(::modelRecord),
        )

    fun writeFiles(writer: BackupZipWriter, sources: List<EtaModelBackupSource>) {
        sources.forEach { source ->
            source.files.forEach { file ->
                writer.file(
                    entryName(source.kind, source.id, source.revision, file.name),
                    File(source.directory, file.name),
                    file.sha256,
                )
            }
        }
    }

    fun validate(document: EtaModelBackupDocument) {
        if (document.format != EtaModelBackupDocument.FORMAT) {
            throw EtaBackupException("这不是 Eta 模型备份")
        }
        if (document.schemaVersion !in 1..EtaModelBackupDocument.SCHEMA_VERSION) {
            throw EtaBackupException("不支持的模型备份版本：${document.schemaVersion}")
        }
        require(document.models.size <= 64) { "模型备份包含的模型过多" }
        val keys = hashSetOf<Triple<String, String, String>>()
        var total = 0L
        document.models.forEach { model ->
            require(safeSegment(model.kind)) { "模型类型无效：${model.kind}" }
            require(safeSegment(model.id)) { "模型 ID 无效" }
            require(safeSegment(model.revision)) { "模型版本无效" }
            require(keys.add(Triple(model.kind, model.id, model.revision))) { "模型备份中的模型重复" }
            require(model.files.isNotEmpty() && model.files.size <= 1024) { "模型文件数量无效" }
            val names = hashSetOf<String>()
            model.files.forEach { file ->
                require(safeSegment(file.name)) { "模型文件名无效" }
                require(names.add(file.name)) { "模型文件重复：${file.name}" }
                require(file.bytes >= 0) { "模型文件大小无效" }
                require(file.sha256 == null || SHA256.matches(file.sha256)) { "模型文件哈希无效" }
                total += file.bytes
                require(total <= BackupArchiveSafety.TOTAL_LIMIT) { "模型备份超过大小限制" }
            }
        }
    }

    fun expectedEntries(document: EtaModelBackupDocument): Set<String> =
        document.models.flatMapTo(hashSetOf()) { model ->
            model.files.map { file -> entryName(model.kind, model.id, model.revision, file.name) }
        }

    /** 整包校验完成前不覆盖任何现有模型。 */
    fun verify(files: Map<String, File>, document: EtaModelBackupDocument): EtaModelBackupSummary {
        val expected = expectedEntries(document)
        val actual = files.keys.filterTo(hashSetOf()) { it.startsWith(EtaModelBackupDocument.PREFIX) }
        require(actual == expected) { "模型备份条目与清单不一致" }
        var fileCount = 0
        var bytes = 0L
        document.models.forEach { model ->
            model.files.forEach { file ->
                val source = requireNotNull(files[entryName(model.kind, model.id, model.revision, file.name)]) {
                    "模型备份缺少文件：${file.name}"
                }
                require(source.isFile && !Files.isSymbolicLink(source.toPath())) { "模型备份文件无效：${file.name}" }
                require(source.length() == file.bytes) { "模型文件大小与清单不一致：${file.name}" }
                file.sha256?.let { expectedHash ->
                    require(BackupDurability.digest(source) == expectedHash) { "模型文件校验失败：${file.name}" }
                }
                fileCount++
                bytes += file.bytes
            }
        }
        return EtaModelBackupSummary(document.models.size, fileCount, bytes)
    }

    /** 校验通过后原子替换目标文件；对应类型的模型仓库重新校验磁盘上的模型。 */
    fun install(context: Context, files: Map<String, File>, document: EtaModelBackupDocument) {
        val root = context.applicationContext.noBackupFilesDir
        document.models.forEach { model ->
            model.files.forEach { file ->
                val source = requireNotNull(files[entryName(model.kind, model.id, model.revision, file.name)])
                val target = BackupArchiveSafety.target(
                    root, "${model.kind}/${model.id}/${model.revision}/${file.name}",
                )
                BackupDurability.mkdirs(requireNotNull(target.parentFile))
                BackupDurability.move(source, target)
            }
        }
        val kinds = document.models.mapTo(hashSetOf()) { it.kind }
        // 只刷新真正被写入的类型；未知类型（如动作模型）由各自的存储在下次使用时读取，无需刷新。
        if (KIND_EMBEDDING in kinds) EmbeddingModelStore.refresh(context)
        if (KIND_SPEECH in kinds) OfflineSpeechPack.refresh(context)
    }

    fun entryName(kind: String, id: String, revision: String, name: String): String =
        "${EtaModelBackupDocument.PREFIX}$kind/$id/$revision/$name"

    private fun decode(raw: String): EtaModelBackupDocument =
        runCatching { json.decodeFromString<EtaModelBackupDocument>(raw) }.getOrElse { failure ->
            throw EtaBackupException("模型备份文件格式无效", failure)
        }

    private fun modelRecord(source: EtaModelBackupSource) = EtaModelBackupModel(
        kind = source.kind,
        id = source.id,
        revision = source.revision,
        files = source.files,
    )

    private fun summary(models: List<EtaModelBackupModel>): EtaModelBackupSummary =
        EtaModelBackupSummary(
            modelCount = models.size,
            fileCount = models.sumOf { it.files.size },
            bytes = models.sumOf { model -> model.files.sumOf { it.bytes } },
        )

    private fun EtaModelBackupDocument.summary(): EtaModelBackupSummary = summary(models)

    private fun complete(directory: File, files: List<EtaModelBackupFile>): Boolean =
        files.isNotEmpty() && files.all { file ->
            val target = File(directory, file.name)
            target.isFile && !Files.isSymbolicLink(target.toPath()) && target.length() == file.bytes
        }

    private fun safeSegment(value: String): Boolean =
        value != "." && value != ".." && SAFE_SEGMENT.matches(value)
}
