package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootSu
import io.github.mangi.eta.agent.media.AgentChatImageCache
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.terminal.AndroidBusyBox
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.datastore.EtaSettingsBackup
import io.github.mangi.eta.data.datastore.SettingsDataStore
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationFolderEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.McpServerEntity
import io.github.mangi.eta.data.db.ProviderEntity
import io.github.mangi.eta.data.db.ProviderModelEntity
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import io.github.mangi.eta.data.db.SkillRegistryEntity
import io.github.mangi.eta.data.model.AssistantPrompt
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Eta用户数据备份。schema 1 只有对话、提供商和记忆；schema 2 补上助手、技能、MCP、设置、附件和可选 Linux 环境。 */
@Serializable
internal data class EtaBackupDocument(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val providers: List<EtaBackupProvider> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<ConversationMessageEntity> = emptyList(),
    val contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
    val conversationState: ConversationStateEntity? = null,
    val folders: List<ConversationFolderEntity> = emptyList(),
    val memoryMd: String = "",
    val assistantMemories: Map<String, String> = emptyMap(),
    val assistants: AssistantBackupSnapshot? = null,
    val assistantAvatars: Map<String, String> = emptyMap(),
    val skillRegistry: List<SkillRegistryEntity> = emptyList(),
    val skillFiles: Map<String, String> = emptyMap(),
    val mcpServers: List<McpServerEntity> = emptyList(),
    val mcpTokens: Map<String, String> = emptyMap(),
    val settings: EtaSettingsBackup? = null,
    val includeLinuxEnvironment: Boolean = false,
    val linuxWorkspaceIncluded: Boolean = false,
    val attachmentCount: Int = 0,
    val importedFileCount: Int = 0,
    val agentPreferences: Map<String, String> = emptyMap(),
    val messagesExternal: Boolean = false,
    val messageCount: Int = 0,
) {
    companion object {
        const val FORMAT = "eta-backup"
        const val SCHEMA_VERSION = 4
        const val MIN_SUPPORTED_SCHEMA = 1
        const val MANIFEST_NAME = "eta-backup.json"
    }
}

@Serializable
internal data class EtaBackupProvider(
    val provider: ProviderEntity,
    val models: List<ProviderModelEntity> = emptyList(),
)

@Serializable
internal data class EtaConversationExport(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val conversation: ConversationEntity,
    val messages: List<ConversationMessageEntity> = emptyList(),
    val contextCheckpoint: ConversationContextCheckpointEntity? = null,
    val attachmentCount: Int = 0,
    val attachments: List<ConversationArchiveAttachment> = emptyList(),
    val subAgentConfigJson: String? = null,
    val subAgentConfigGeneration: String? = null,
) {
    companion object {
        const val FORMAT = "eta-conversation"
        const val SCHEMA_VERSION = 3
        const val MANIFEST_NAME = "eta-conversation.json"
    }
}

internal data class EtaBackupSummary(
    val providerCount: Int,
    val modelCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
    val memoryBytes: Int,
    val assistantCount: Int = 0,
    val mcpCount: Int = 0,
    val skillCount: Int = 0,
    val attachmentCount: Int = 0,
    val includedLinuxEnvironment: Boolean = false,
)

internal data class EtaBackupExportOptions(
    val includeLinuxEnvironment: Boolean = false,
)

internal class EtaBackupException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object EtaBackupRepository {
    private val operationMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    fun linuxEnvironmentBytes(context: Context): Long {
        val appContext = context.applicationContext
        val walked = LinuxDistribution.entries.sumOf { distribution ->
            directorySize(LinuxEnvironmentPaths.environmentDir(appContext, distribution))
        }
        if (walked > 0L) return walked
        if (!RootAccess.isGranted) return 0L
        return LinuxDistribution.entries.sumOf { distribution ->
            duBytes(LinuxEnvironmentPaths.environmentDir(appContext, distribution))
        }
    }

    suspend fun export(
        context: Context,
        output: OutputStream,
        options: EtaBackupExportOptions = EtaBackupExportOptions(),
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            AgentExecutionService.beginBackupMaintenance()
            try {
                val appContext = context.applicationContext
                val payloads = File(appContext.cacheDir, "daiyu-backup-conversations")
                payloads.deleteRecursively()
                BackupDurability.mkdirs(payloads)
                val document = try {
                    EtaDatabase.get(appContext).withTransaction {
                        val meta = snapshot(appContext, options)
                        val count = writeConversationPayloads(appContext, payloads)
                        require(count == meta.messageCount) { "会话消息在导出时发生变化" }
                        meta
                    }
                } catch (failure: Throwable) {
                    payloads.deleteRecursively()
                    throw failure
                }
                try {
                ZipOutputStream(output).use { zip ->
                    val writer = BackupZipWriter(zip)
                    zip.setLevel(if (options.includeLinuxEnvironment) 1 else 6)
                    val manifest = json.encodeToString(document)
                    require(manifest.toByteArray().size <= BackupArchiveSafety.MANIFEST_LIMIT) { "备份清单超过大小限制" }
                    writer.text(EtaBackupDocument.MANIFEST_NAME, manifest)
                    payloads.listFiles()?.sortedBy { it.name }?.forEach { file ->
                        writer.file("conversations/${file.name}", file)
                    }
                    writer.directory("attachments/chat-images/", File(appContext.cacheDir, AgentChatImageCache.CACHE_DIRECTORY))
                    writer.directory(
                        "attachments/imports/",
                        File(TerminalPrivateStorage.workspace(appContext.filesDir), "imports"),
                    )
                    writer.directory(
                        "linux/workspace/",
                        TerminalPrivateStorage.workspace(appContext.filesDir),
                        skipNames = setOf("imports", "mounts"),
                    )
                    if (options.includeLinuxEnvironment) {
                        exportLinuxEnvironments(appContext, writer)
                    }
                    zip.finish()
                }
                document.toBackupSummary()
                } finally {
                    payloads.deleteRecursively()
                }
            } finally {
                AgentExecutionService.endBackupMaintenance()
            }
        }
    }

    suspend fun exportConversation(
        context: Context,
        conversationId: String,
        output: OutputStream,
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            AgentExecutionService.beginBackupMaintenance()
            try {
                val appContext = context.applicationContext
                val snapshot = EtaDatabase.get(appContext).withTransaction { conversationSnapshot(appContext, conversationId) }
                val prepared = ConversationArchiveMedia.prepare(appContext, snapshot)
                val document = prepared.document
                ZipOutputStream(output).use { zip ->
                    val writer = BackupZipWriter(zip)
                    writer.text(EtaConversationExport.MANIFEST_NAME, json.encodeToString(document))
                    document.attachments.forEach { attachment ->
                        val file = prepared.files.getValue(attachment.entry)
                        require(file.length() == attachment.size) { "附件在导出时发生变化" }
                        writer.file(attachment.entry, file, attachment.sha256)
                    }
                    zip.finish()
                }
                document.toConversationSummary()
            } finally {
                AgentExecutionService.endBackupMaintenance()
            }
        }
    }

    suspend fun import(context: Context, input: InputStream): EtaBackupSummary =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                AgentExecutionService.beginBackupMaintenance()
                try {
                    val appContext = context.applicationContext
                    recoverInterruptedImport(appContext, maintenanceAlreadyHeld = true)
                    val operation = File(appContext.filesDir, "backup-restore")
                    BackupDurability.mkdirs(operation)
                    var mayRetire = false
                    try {
                        val archive = File(operation, "input")
                        archive.outputStream().use {
                            BackupArchiveSafety.copyLimited(input, it, BackupArchiveSafety.TOTAL_LIMIT, operation)
                        }
                        val header = archive.inputStream().use { it.readNBytes(2) }
                        require(header.isNotEmpty()) { "备份文件为空" }
                        val files = if (header.contentEquals(byteArrayOf(0x50, 0x4b))) {
                            BackupArchiveSafety.stageZip(archive, File(operation, "staged"))
                        } else {
                            require(archive.length() <= BackupArchiveSafety.MANIFEST_LIMIT) { "备份清单过大" }
                            mapOf(manifestName(archive.readText()) to archive)
                        }
                        val document = files[EtaBackupDocument.MANIFEST_NAME]?.let {
                            attachExternalMessages(decodeDocument(it.readText()).also(::validate), files.filterKeys(::isConversationPayload).values)
                        }
                        val conversation = files[EtaConversationExport.MANIFEST_NAME]?.let {
                            decodeConversation(it.readText())
                        }
                        if (conversation != null) {
                            // Gate generation and validate current archives before any owner, files or Room write.
                            val config = BackupSubAgentConfig.archiveForImport(conversation.schemaVersion,
                                conversation.subAgentConfigJson, ConversationSubAgentPreferences(),
                                generation = conversation.subAgentConfigGeneration)
                            val plan = ConversationArchiveImport.prepare(appContext, conversation, files)
                            val newId = plan.document.conversation.id
                            check(EtaDatabase.get(appContext).conversationDao().conversationEntity(newId) == null) { "新会话 ID 冲突，未开始导入" }
                            val prefs = requireNotNull(Prefs.localAgentPreferences()) { "Agent preferences 未初始化" }
                            check(!BackupSubAgentConfig.hasOwner(prefs, newId)) { "新会话 owner 冲突，未开始导入" }
                            withContext(NonCancellable) {
                                durableText(File(operation, "new-conversation-id"), newId)
                                val journal = BackupRestoreJournal(operation)
                                journal.begin(plan.files.keys.toList())
                                val owner = BackupConversationOwnerImport.plan(operation, newId, config)
                                try {
                                    owner.begin(appContext)
                                    EtaDatabase.get(appContext).withTransaction {
                                        plan.files.forEach { (target, source) -> journal.replace(target, source) }
                                        val imported = plan.document
                                        EtaDatabase.get(appContext).conversationDao().importAsNewConversation(
                                            imported.conversation, imported.messages, imported.contextCheckpoint,
                                        )
                                    }
                                } catch (failure: Throwable) {
                                    try {
                                        // Room has rolled back; undo only the owner installed by THIS import.
                                        BackupConversationOwnerImport.recover(appContext, operation, committed = false)
                                        journal.rollback()
                                        journal.commit()
                                        mayRetire = true
                                    } catch (rollbackFailure: Throwable) {
                                        failure.addSuppressed(rollbackFailure)
                                        throw EtaBackupException("会话导入失败且回滚未完成，日志已保留，请重启应用完成恢复。", failure)
                                    }
                                    throw failure
                                }
                                // Marker failure must keep maintenance active until startup recovery.
                                journal.commit()
                                BackupConversationOwnerImport.recover(appContext, operation, committed = true)
                                mayRetire = true
                            }
                            return@withLock plan.document.toConversationSummary()
                        }
                        val planned = linkedMapOf<File, File>()
                        val linuxTars = mutableListOf<Triple<String, String, File>>()
                        files.forEach { (name, source) ->
                            val target = when {
                                name == EtaBackupDocument.MANIFEST_NAME || name == EtaConversationExport.MANIFEST_NAME -> null
                                isConversationPayload(name) -> null
                                name.startsWith("attachments/chat-images/") -> BackupArchiveSafety.target(
                                    File(appContext.cacheDir, AgentChatImageCache.CACHE_DIRECTORY), name.removePrefix("attachments/chat-images/"))
                                name.startsWith("attachments/imports/") -> BackupArchiveSafety.target(
                                    File(TerminalPrivateStorage.workspace(appContext.filesDir), "imports"), name.removePrefix("attachments/imports/"))
                                name.startsWith("linux/workspace/") -> {
                                    val relative = name.removePrefix("linux/workspace/")
                                    require(relative.substringBefore('/') !in setOf("imports", "mounts")) { "工作区归档包含受保护挂载" }
                                    BackupArchiveSafety.target(TerminalPrivateStorage.workspace(appContext.filesDir), relative)
                                }
                                BackupArchiveSafety.LINUX_ENVIRONMENT_TAR.matches(name) -> {
                                    val parts = name.removePrefix("linux/environments/").removeSuffix(".tar").split('/')
                                    require(parts.size == 2) { "Linux 环境归档路径无效" }
                                    linuxTars += Triple(parts[0], parts[1], source)
                                    null
                                }
                                else -> throw EtaBackupException("备份存在未知条目：$name")
                            }
                            if (target != null) {
                                require(!planned.containsKey(target)) { "多个归档条目指向同一文件" }
                                planned[target] = source
                            }
                        }
                        val destinations = planned.keys.map { it.toPath() }.toSet()
                        require(destinations.none { path ->
                            generateSequence(path.parent) { it.parent }.any { it in destinations }
                        }) { "归档文件与目录路径冲突" }
                        val daemons = io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor.defaultRecordsFile(appContext)
                        if (daemons.exists()) {
                            val records = daemons.inputStream().use { BackupArchiveSafety.readText(it, 1024 * 1024) }
                            require(org.json.JSONArray(records).length() == 0) { "请先停止并移除后台守护任务，再导入备份" }
                        }
                        val previousPayloads = File(operation, "previous-conversations")
                        previousPayloads.deleteRecursively()
                        BackupDurability.mkdirs(previousPayloads)
                        val old = EtaDatabase.get(appContext).withTransaction {
                            val meta = snapshot(appContext, EtaBackupExportOptions())
                            val count = writeConversationPayloads(appContext, previousPayloads)
                            require(count == meta.messageCount) { "回滚快照中的消息数量不一致，未开始恢复" }
                            meta
                        }
                        val oldJson = json.encodeToString(old)
                        require(oldJson.toByteArray().size <= BackupArchiveSafety.MANIFEST_LIMIT) { "回滚快照过大，未开始恢复" }
                        durableText(File(operation, "previous.json"), oldJson)
                        val journal = BackupRestoreJournal(operation)
                        journal.begin(planned.keys.toList())
                        try {
                            EtaDatabase.get(appContext).withTransaction {
                                planned.forEach { (target, source) -> journal.replace(target, source) }
                                if (document != null) {
                                    restoreMetadata(appContext, document)
                                    rewriteRestoredPaths(appContext, document)
                                }
                            }
                            if (document?.includeLinuxEnvironment == true) {
                                linuxTars.forEach { (backend, distribution, archive) ->
                                    archive.inputStream().use { restoreLinuxTar(appContext, it, backend, distribution) }
                                }
                            }
                        } catch (failure: Throwable) {
                            withContext(NonCancellable) {
                                try {
                                    journal.rollback()
                                    restoreMetadata(appContext, attachExternalMessages(old, previousPayloads.listFiles()?.toList().orEmpty()), reconcile = false, exactPreferences = true)
                                } catch (rollbackFailure: Throwable) {
                                    failure.addSuppressed(rollbackFailure)
                                    throw EtaBackupException("恢复失败且回滚尚未完成，恢复日志已保留。请重启应用完成恢复。", failure)
                                }
                                journal.commit()
                                mayRetire = true
                            }
                            throw failure
                        }
                        // Commit-marker failure is not an apply failure: do not start rollback after
                        // publishing a marker which startup could legitimately interpret as committed.
                        journal.commit()
                        mayRetire = true
                        if (document != null) document.toBackupSummary() else requireNotNull(conversation).toConversationSummary()
                    } finally {
                        if (mayRetire || !BackupRestoreJournal.hasJournal(operation)) {
                            BackupDurability.retire(operation).deleteRecursively()
                        }
                    }
                } finally {
                    val operation = File(context.filesDir, "backup-restore")
                    // A failed rollback keeps new execution blocked until startup recovery succeeds.
                    if (!operation.exists()) {
                        AgentExecutionService.endBackupMaintenance()
                    }
                }
            }
        }

    /** Fence actual run admission before examining any journal: logs alone do not block new history. */
    suspend fun recoverInterruptedImport(context: Context, maintenanceAlreadyHeld: Boolean = false) {
        val operation = File(context.filesDir, "backup-restore")
        if (!operation.exists()) return
        if (!maintenanceAlreadyHeld) AgentExecutionService.beginBackupMaintenance()
        // On failure the fence stays up; the caller must not admit work before a later successful recovery.
        if (BackupRestoreJournal.hasJournal(operation) && !BackupRestoreJournal.isCommitted(operation)) {
            val idFile = File(operation, "new-conversation-id")
            if (idFile.exists() || File(idFile.path + ".bak").exists()) {
                val id = android.util.AtomicFile(idFile).openRead().use { BackupArchiveSafety.readText(it, 128) }
                require(Regex("conv-[0-9a-f-]{36}").matches(id)) { "会话恢复日志 ID 无效" }
                // This ID was freshly allocated for this import only.
                EtaDatabase.get(context).conversationDao().deleteImportedConversation(id)
                BackupConversationOwnerImport.recover(context, operation, committed = false)
                BackupRestoreJournal(operation).rollback()
            } else {
                val old = android.util.AtomicFile(File(operation, "previous.json")).openRead().use {
                    decodeDocument(BackupArchiveSafety.readText(it))
                }
                val previousPayloads = File(operation, "previous-conversations").listFiles()?.toList().orEmpty()
                BackupRestoreJournal(operation).rollback()
                restoreMetadata(context, attachExternalMessages(old, previousPayloads), reconcile = false, exactPreferences = true)
            }
        }
        if (BackupRestoreJournal.hasJournal(operation) && BackupRestoreJournal.isCommitted(operation)) {
            BackupConversationOwnerImport.recover(context, operation, committed = true)
        }
        // The atomic retirement removes the journal from the recovery namespace before releasing admission.
        BackupDurability.retire(operation).deleteRecursively()
        if (!maintenanceAlreadyHeld) AgentExecutionService.endBackupMaintenance()
    }

    suspend fun inspect(input: InputStream): EtaBackupSummary = withContext(Dispatchers.IO) {
        // Preview is bounded and streamed; import always repeats full CRC/central-directory validation.
        val body = java.io.PushbackInputStream(input, 2)
        val header = body.readNBytes(2)
        require(header.isNotEmpty()) { "备份文件为空" }
        body.unread(header)
        if (header.contentEquals(byteArrayOf(0x50, 0x4b))) {
            var summary: EtaBackupSummary? = null
            var total = 0L
            val names = hashSetOf<String>()
            ZipInputStream(body).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(names.size < BackupArchiveSafety.ENTRY_LIMIT) { "备份条目过多" }
                    val name = BackupArchiveSafety.relativePath(entry.name)
                    require(names.add(name)) { "备份存在重复条目" }
                    if (name == EtaBackupDocument.MANIFEST_NAME || name == EtaConversationExport.MANIFEST_NAME) {
                        require(summary == null) { "备份包含多个清单" }
                        val raw = BackupArchiveSafety.readText(zip)
                        total += raw.toByteArray().size
                        require(total <= BackupArchiveSafety.TOTAL_LIMIT) { "备份总量超过限制" }
                        summary = if (name == EtaBackupDocument.MANIFEST_NAME) {
                            decodeDocument(raw).also(::validate).toBackupSummary()
                        } else decodeConversation(raw).toConversationSummary()
                    } else {
                        total += BackupArchiveSafety.copyLimited(zip, OutputStream.nullOutputStream(), BackupArchiveSafety.TOTAL_LIMIT - total)
                    }
                }
            }
            requireNotNull(summary) { "备份文件缺少清单" }
        } else {
            val raw = BackupArchiveSafety.readText(body)
            if (manifestName(raw) == EtaConversationExport.MANIFEST_NAME) decodeConversation(raw).toConversationSummary()
            else decodeDocument(raw).also(::validate).toBackupSummary()
        }
    }

    private fun manifestName(raw: String): String =
        if (runCatching { org.json.JSONObject(raw).optString("format") }.getOrNull() == EtaConversationExport.FORMAT)
            EtaConversationExport.MANIFEST_NAME else EtaBackupDocument.MANIFEST_NAME


    private suspend fun writeConversationPayloads(context: Context, directory: File): Int {
        val dao = EtaDatabase.get(context).conversationDao()
        var count = 0
        dao.conversationEntities().forEachIndexed { index, conversation ->
            val exported = EtaConversationExport(
                exportedAt = System.currentTimeMillis(),
                conversation = conversation,
                messages = dao.messagesForConversation(conversation.id),
                contextCheckpoint = dao.contextCheckpoint(conversation.id),
            )
            val text = json.encodeToString(exported)
            require(text.toByteArray().size.toLong() <= BackupArchiveSafety.MANIFEST_LIMIT) {
                "会话「${conversation.title.ifBlank { conversation.id }.take(40)}」超过单个备份条目上限，请先减少该会话内容"
            }
            File(directory, conversationPayloadFileName(conversation.id, index)).writeText(text)
            count += exported.messages.size
        }
        return count
    }

    private fun conversationPayloadFileName(id: String, index: Int): String {
        val safe = id.length <= 180 && id.matches(Regex("[A-Za-z0-9_.-]+")) && id != "." && id != ".."
        return if (safe) "$id.json" else "c$index.json"
    }

    private fun isConversationPayload(name: String): Boolean =
        name.startsWith("conversations/") && name.endsWith(".json") && name.count { it == '/' } == 1

    private fun attachExternalMessages(document: EtaBackupDocument, payloads: Collection<File>): EtaBackupDocument {
        if (!document.messagesExternal) return document
        val messages = mutableListOf<io.github.mangi.eta.data.db.ConversationMessageEntity>()
        val checkpoints = mutableListOf<io.github.mangi.eta.data.db.ConversationContextCheckpointEntity>()
        val seen = mutableSetOf<String>()
        payloads.filter { it.isFile && it.name.endsWith(".json") }.forEach { file ->
            val exported = decodeConversation(file.readText())
            require(seen.add(exported.conversation.id)) { "备份中的会话重复" }
            messages += exported.messages
            exported.contextCheckpoint?.let { checkpoints += it }
        }
        require(seen == document.conversations.map { it.id }.toSet()) { "备份中的会话消息不完整" }
        require(messages.size == document.messageCount) { "备份中的消息数量不一致" }
        return document.copy(messages = messages, contextCheckpoints = checkpoints)
    }

    private suspend fun snapshot(
        context: Context,
        options: EtaBackupExportOptions,
    ): EtaBackupDocument {
        val database = EtaDatabase.get(context)
        val providers = database.providerDao().providers().map { provider ->
            EtaBackupProvider(provider = provider.provider, models = provider.models)
        }
        val conversations = database.conversationDao()
        val settings = SettingsDataStore.backupSnapshot()
        val mcpServers = database.mcpServerDao().servers()
        val chatImages = File(context.cacheDir, AgentChatImageCache.CACHE_DIRECTORY)
        val imports = File(TerminalPrivateStorage.workspace(context.filesDir), "imports")
        return EtaBackupDocument(
            exportedAt = System.currentTimeMillis(),
            providers = providers,
            selectedProviderId = settings.selectedProviderId,
            selectedModelId = settings.selectedModelId,
            conversations = conversations.conversationEntities(),
            messagesExternal = true,
            messageCount = conversations.storedMessageCount(),
            conversationState = conversations.state(),
            folders = conversations.folders(),
            memoryMd = AgentMemoryRepository.snapshot(AssistantPrompt.DEFAULT_ID).content,
            assistantMemories = AgentMemoryRepository.exportAll(),
            assistants = if (AssistantRepository.isReady()) AssistantRepository.exportSnapshot() else null,
            assistantAvatars = if (AssistantRepository.isReady()) encodeFiles(AssistantRepository.exportAvatars()) else emptyMap(),
            skillRegistry = database.skillDao().registryEntries(),
            skillFiles = encodeFiles(SkillRuntime.exportUserSkills(context)),
            mcpServers = mcpServers,
            mcpTokens = McpSecretStore(context).exportTokens(mcpServers.map { it.id }),
            settings = settings,
            includeLinuxEnvironment = options.includeLinuxEnvironment,
            linuxWorkspaceIncluded = true,
            attachmentCount = countFiles(chatImages),
            importedFileCount = countFiles(imports),
            agentPreferences = Prefs.exportAgentPreferences(),
        )
    }

    private suspend fun conversationSnapshot(
        context: Context,
        conversationId: String,
    ): EtaConversationExport {
        val database = EtaDatabase.get(context)
        BackupDatabaseBudget.validate(database.openHelper.readableDatabase, conversationId)
        val dao = database.conversationDao()
        val conversation = dao.conversationEntity(conversationId)
            ?: throw EtaBackupException("会话不存在")
        val messages = dao.messagesForConversation(conversationId)
        val checkpoint = dao.contextCheckpoint(conversationId)
        return EtaConversationExport(
            exportedAt = System.currentTimeMillis(),
            conversation = conversation,
            messages = messages,
            contextCheckpoint = checkpoint,
            subAgentConfigJson = BackupSubAgentConfig.archiveForExport(conversationId, ConversationSubAgentPreferences()),
            subAgentConfigGeneration = "1",
            attachmentCount = 0,
        )
    }

    private suspend fun restoreMetadata(
        context: Context, document: EtaBackupDocument, reconcile: Boolean = true, exactPreferences: Boolean = false,
    ) {
        val subAgentStore = ConversationSubAgentPreferences()
        // Prepare/validate before touching Room, settings or local preferences. The undo path must
        // remain exact: it is not a second external restore and must never run reset or migrations.
        val preferencesToRestore = when {
            exactPreferences -> document.agentPreferences
            document.schemaVersion >= 2 && document.agentPreferences.isNotEmpty() ->
                BackupSubAgentConfig.preferencesForRestore(
                    document.agentPreferences, Prefs.exportAgentPreferences(), subAgentStore,
                )
            else -> null
        }
        val database = EtaDatabase.get(context)
        database.withTransaction {
            database.providerDao().replaceAll(
                document.providers.map { provider ->
                    ProviderWithModelsSeed(provider = provider.provider, models = provider.models)
                },
            )
            database.conversationDao().replaceAll(
                conversations = document.conversations,
                messages = document.messages,
                contextCheckpoints = document.contextCheckpoints,
                state = document.conversationState,
            )
            database.conversationDao().replaceFolders(document.folders)
            if (document.schemaVersion >= 2) {
                database.mcpServerDao().replaceAll(document.mcpServers)
                database.skillDao().replaceRegistry(document.skillRegistry)
            }
        }
        AgentMemoryRepository.importAll(document.assistantMemories, document.memoryMd)
        if (document.schemaVersion >= 2) {
            if (AssistantRepository.isReady()) {
                document.assistants?.let(AssistantRepository::importSnapshot)
                AssistantRepository.importAvatars(decodeFiles(document.assistantAvatars))
            }
            SkillRuntime.importUserSkills(context, decodeFiles(document.skillFiles))
            McpSecretStore(context).replaceAll(document.mcpTokens)
            document.settings?.let { SettingsDataStore.restoreBackup(it) }
                ?: SettingsDataStore.setSelection(document.selectedProviderId, document.selectedModelId)
        } else {
            SettingsDataStore.setSelection(document.selectedProviderId, document.selectedModelId)
        }
        // Old archives without this field retain current prefs. Our own undo snapshot is exact,
        // including the absence of a marker; no post-write migrations may alter rollback originals.
        preferencesToRestore?.let { values ->
            if (exactPreferences) {
                BackupSubAgentConfig.restoreExactPreferences(
                    values, requireNotNull(Prefs.localAgentPreferences()) { "Agent preferences 未初始化" },
                )
            } else {
                Prefs.restoreAgentPreferences(values)
            }
            subAgentStore.refreshAfterRestore()
        }
        LinuxEnvironmentSettingsRepository.initialize(context)
        if (reconcile) {
            ProviderRepository.ensureBuiltInsMerged()
            ProviderRepository.repairSelection()
        }
    }

    private suspend fun rewriteRestoredPaths(context: Context, document: EtaBackupDocument) {
        val rewrite: (String) -> String = { BackupAttachmentPaths.rewriteJson(it, context) }
        val conversations = document.conversations.map { it.copy(historyJson = rewrite(it.historyJson)) }
        val messages = document.messages.map { it.copy(imagesJson = BackupAttachmentPaths.rewriteImages(it.imagesJson, context)) }
        val checkpoints = document.contextCheckpoints.map { it.copy(historyJson = rewrite(it.historyJson)) }
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = conversations,
            messages = messages,
            contextCheckpoints = checkpoints,
            state = document.conversationState,
        )
        EtaDatabase.get(context).conversationDao().replaceFolders(document.folders)
    }

    private fun decodeDocument(raw: String): EtaBackupDocument =
        runCatching { json.decodeFromString<EtaBackupDocument>(raw) }.getOrElse { failure ->
            throw EtaBackupException("备份文件格式无效", failure)
        }.let { document ->
            if (document.schemaVersion >= 3) document else document.copy(
                providers = document.providers.map { provider -> provider.copy(
                    models = provider.models.map { model -> model.copy(visionOverride = model.visionOverride ?: model.attachment) },
                ) },
            )
        }

    private fun decodeConversation(raw: String): EtaConversationExport {
        val exported = runCatching { json.decodeFromString<EtaConversationExport>(raw) }.getOrElse { failure ->
            throw EtaBackupException("会话备份文件格式无效", failure)
        }
        if (exported.format != EtaConversationExport.FORMAT) {
            throw EtaBackupException("这不是 Eta 会话备份")
        }
        require(exported.schemaVersion in 1..EtaConversationExport.SCHEMA_VERSION) { "不支持的会话备份版本" }
        require(exported.messages.all { it.conversationId == exported.conversation.id }) { "会话消息引用无效" }
        require(exported.messages.map { it.id }.distinct().size == exported.messages.size) { "会话消息 ID 重复" }
        require(exported.contextCheckpoint == null || exported.contextCheckpoint.conversationId == exported.conversation.id) { "会话检查点引用无效" }
        require(exported.conversation.id.length <= 200 &&
            exported.conversation.id.matches(Regex("[A-Za-z0-9_.-]+")) && exported.conversation.id !in setOf(".", "..")) { "会话 ID 无效" }
        if (exported.conversation.id.isBlank()) {
            throw EtaBackupException("会话备份缺少会话 ID")
        }
        return exported
    }

    private fun validate(document: EtaBackupDocument) {
        if (document.format != EtaBackupDocument.FORMAT) {
            throw EtaBackupException("这不是 Eta 备份文件")
        }
        if (document.schemaVersion !in EtaBackupDocument.MIN_SUPPORTED_SCHEMA..EtaBackupDocument.SCHEMA_VERSION) {
            throw EtaBackupException("不支持的 Eta 备份版本：${document.schemaVersion}")
        }
        listOf(document.skillFiles, document.assistantAvatars).forEach { files ->
            require(files.size <= 10_000) { "备份嵌入文件过多" }
            var decodedBytes = 0L
            files.forEach { (name, encoded) ->
                BackupArchiveSafety.relativePath(name)
                require(encoded.length <= 8 * 1024 * 1024) { "备份嵌入文件过大" }
                decodedBytes += Base64.decode(encoded, Base64.NO_WRAP).size
                require(decodedBytes <= BackupArchiveSafety.MANIFEST_LIMIT) { "备份嵌入文件总量过大" }
            }
        }
        require(document.assistantAvatars.keys.none { '/' in it }) { "头像文件名无效" }
        require(document.assistantMemories.values.all { it.toByteArray().size <= 1024 * 1024 }) { "助手记忆超过限制" }
        require(document.contextCheckpoints.all { checkpoint -> document.conversations.any { it.id == checkpoint.conversationId } }) { "检查点缺少所属会话" }
        val modelIds = document.providers.flatMap { it.models }.map { it.id }
        require(modelIds.size == modelIds.toSet().size && modelIds.none(String::isBlank)) { "模型 ID 无效或重复" }
        require(document.providers.all { provider -> provider.models.all { it.providerId == provider.provider.id } }) { "模型提供商引用无效" }
        require(document.messages.map { it.id }.distinct().size == document.messages.size) { "消息 ID 重复" }
        val providerIds = document.providers.map { it.provider.id }
        if (providerIds.size != providerIds.toSet().size || providerIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的模型提供商存在重复或无效 ID")
        }
        val conversationIds = document.conversations.map { it.id }
        if (conversationIds.size != conversationIds.toSet().size || conversationIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的会话存在重复或无效 ID")
        }
        if (document.messages.any { it.conversationId !in conversationIds }) {
            throw EtaBackupException("备份中的消息缺少所属会话")
        }
        if (document.memoryMd.toByteArray().size > 1024 * 1024) {
            throw EtaBackupException("MEMORY.md 超过 1 MiB 限制")
        }
        document.assistants?.profiles?.let { profiles ->
            val ids = profiles.map { io.github.mangi.eta.data.model.AssistantStorage.id(it.id) }
            if (ids.size != ids.toSet().size || ids.any(String::isBlank)) {
                throw EtaBackupException("备份中的助手存在重复或无效 ID")
            }
        }
        document.assistantMemories.keys.forEach { io.github.mangi.eta.data.model.AssistantStorage.id(it) }
        BackupSubAgentConfig.validateExternalPreferences(document.agentPreferences, ConversationSubAgentPreferences())
        val mcpIds = document.mcpServers.map { it.id }
        if (mcpIds.size != mcpIds.toSet().size || mcpIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的 MCP 服务器存在重复或无效 ID")
        }
    }

    private fun EtaBackupDocument.toBackupSummary(): EtaBackupSummary = EtaBackupSummary(
        providerCount = providers.size,
        modelCount = providers.sumOf { it.models.size },
        conversationCount = conversations.size,
        messageCount = if (messagesExternal) messageCount else messages.size,
        memoryBytes = memoryMd.toByteArray().size,
        assistantCount = assistants?.profiles?.size ?: 0,
        mcpCount = mcpServers.size,
        skillCount = skillFiles.keys.map { it.substringBefore('/') }.filter { it.isNotBlank() }.toSet().size,
        attachmentCount = attachmentCount + importedFileCount,
        includedLinuxEnvironment = includeLinuxEnvironment,
    )

    private fun EtaConversationExport.toConversationSummary(): EtaBackupSummary = EtaBackupSummary(
        providerCount = 0,
        modelCount = 0,
        conversationCount = 1,
        messageCount = messages.size,
        memoryBytes = 0,
        attachmentCount = attachmentCount,
    )
}

private val LINUX_TAR_SKIP = setOf("proc", "sys", "dev", "run", "tmp")

private fun exportLinuxEnvironments(context: Context, writer: BackupZipWriter) {
    LinuxDistribution.entries.forEach { distribution ->
        val envDir = LinuxEnvironmentPaths.environmentDir(context, distribution)
        if (!envDir.exists()) return@forEach
        val backend = LinuxEnvironmentPaths.backendOf(
            LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath,
        )
        val entryName = "linux/environments/${backend.wireName}/${distribution.wireName}.tar"
        val packed = writer.stream(entryName) { output ->
            if (backend == LinuxExecutionBackend.PROOT && canWalk(envDir)) {
                writeTar(envDir, output)
            } else {
                streamBusyBoxTar(envDir, output)
            }
        }
        if (packed <= 0L) {
            throw EtaBackupException("无法打包 Linux 环境：${distribution.wireName}")
        }
    }
}

private fun restoreLinuxTar(
    context: Context,
    input: java.io.InputStream,
    backendName: String,
    distributionName: String,
) {
    val backend = LinuxExecutionBackend.entries.firstOrNull { it.wireName == backendName }
        ?: throw EtaBackupException("备份中的 Linux 后端无效：$backendName")
    val distribution = LinuxDistribution.entries.firstOrNull { it.wireName == distributionName }
        ?: throw EtaBackupException("备份中的 Linux 发行版无效：$distributionName")
    val destination = LinuxEnvironmentPaths.environmentDir(context, distribution, backend)
    destination.parentFile?.mkdirs()
    if (canWrite(destination.parentFile ?: destination) && (backend == LinuxExecutionBackend.PROOT || canWalk(destination))) {
        if (destination.exists() && !destination.deleteRecursively()) {
            deleteTreeAsRoot(destination)
        }
        destination.mkdirs()
        extractTarStream(input, destination)
        return
    }
    if (!RootAccess.isGranted) {
        throw EtaBackupException("导入完整 Linux 环境需要 Root")
    }
    val tarFile = File(context.cacheDir, "eta-linux-restore-${distribution.wireName}-${System.nanoTime()}.tar")
    try {
        tarFile.outputStream().buffered().use { input.copyTo(it) }
        if (tarFile.length() <= 0L) {
            throw EtaBackupException("备份中的 Linux 环境是空的")
        }
        if (!extractTarFileAsRoot(tarFile, destination)) {
            throw EtaBackupException("无法把 Linux 环境安装到 ${destination.absolutePath}")
        }
    } finally {
        tarFile.delete()
    }
}

private fun skipLinuxPath(relative: String): Boolean {
    val parts = relative.split('/')
    return parts.size >= 2 && parts[0] == "rootfs" && parts[1] in LINUX_TAR_SKIP
}

private fun writeTar(source: File, output: java.io.OutputStream) {
    TarArchiveOutputStream(output).apply {
        setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
    }.let { tar ->
        source.walkTopDown().onEnter { dir ->
            !skipLinuxPath(dir.relativeTo(source).invariantSeparatorsPath)
        }.forEach { file ->
            val relative = file.relativeTo(source).invariantSeparatorsPath.ifBlank { "." }
            if (relative == "." || skipLinuxPath(relative)) return@forEach
            val entry = TarArchiveEntry(file, relative)
            tar.putArchiveEntry(entry)
            if (file.isFile) file.inputStream().use { it.copyTo(tar) }
            tar.closeArchiveEntry()
        }
        tar.finish()
    }
}

private fun extractTarStream(input: java.io.InputStream, destination: File) {
    val root = destination.canonicalFile
    val tar = TarArchiveInputStream(input)
    while (true) {
        val entry = tar.nextEntry ?: break
        val relative = normalizeTarPath(entry.name) ?: continue
        val target = File(root, relative).canonicalFile
        if (!target.path.startsWith(root.path + File.separator) && target != root) {
            throw EtaBackupException("Linux 归档路径越界")
        }
        when {
            entry.isDirectory -> target.mkdirs()
            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                Files.deleteIfExists(target.toPath())
                runCatching {
                    Files.createSymbolicLink(target.toPath(), java.nio.file.Path.of(entry.linkName))
                }.getOrElse {
                    target.writeText("")
                }
            }
            entry.isFile -> {
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it) }
                if (entry.mode and 0b001001001 != 0) target.setExecutable(true, false)
            }
        }
    }
}

private fun streamBusyBoxTar(source: File, output: java.io.OutputStream) {
    if (!RootAccess.isGranted) {
        throw EtaBackupException("打包完整 Linux 环境需要 Root")
    }
    val script = AndroidBusyBox.discoveryScript() +
        "; [ -n \"${'$'}eta_busybox\" ] || exit 127; " +
        "\"${'$'}eta_busybox\" tar -C " + shellQuote(source.absolutePath) +
        " --exclude=rootfs/proc --exclude=rootfs/sys --exclude=rootfs/dev --exclude=rootfs/run --exclude=rootfs/tmp" +
        " -cf - ."
    val process = RootSu.process(script).redirectErrorStream(false).start()
    val stderr = StringBuilder()
    val reader = Thread {
        runCatching { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
    }.apply { isDaemon = true; start() }
    try {
        process.inputStream.copyTo(output)
        val finished = process.waitFor(20, TimeUnit.MINUTES)
        reader.join(5_000)
        val code = if (finished) process.exitValue() else -1
        if (!finished || code != 0) {
            AndroidAgentLogger.warn(
                "Backup linux tar failed: source=${source.absolutePath} exit=$code stderr=${stderr.toString().take(500)}",
            )
            throw EtaBackupException(
                "无法打包 Linux 环境（退出码 $code）${stderr.toString().trim().take(180).let { if (it.isBlank()) "" else "：$it" }}",
            )
        }
    } finally {
        runCatching { process.destroyForcibly() }
    }
}

private fun extractTarFileAsRoot(archive: File, destination: File): Boolean {
    destination.parentFile?.mkdirs()
    val script = AndroidBusyBox.discoveryScript() +
        "; [ -n \"${'$'}eta_busybox\" ] || exit 127; " +
        "\"${'$'}eta_busybox\" mkdir -p -- " + shellQuote(destination.parentFile?.absolutePath.orEmpty()) + "; " +
        "\"${'$'}eta_busybox\" rm -rf -- " + shellQuote(destination.absolutePath) + "; " +
        "\"${'$'}eta_busybox\" mkdir -p -- " + shellQuote(destination.absolutePath) + "; " +
        "\"${'$'}eta_busybox\" tar -C " + shellQuote(destination.absolutePath) + " -xf " + shellQuote(archive.absolutePath)
    return runRoot(script, timeoutMinutes = 20)
}

private fun normalizeTarPath(raw: String): String? {
    val relative = raw.trim().trimStart('/').replace('\\', '/')
    if (relative.isBlank() || relative == ".") return null
    val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) throw EtaBackupException("Linux 归档路径越界")
    return segments.joinToString("/")
}

private fun deleteTreeAsRoot(target: File): Boolean {
    if (!target.exists()) return true
    return runRoot("rm -rf -- ${shellQuote(target.absolutePath)}", timeoutMinutes = 5)
}

private fun runRoot(command: String, timeoutMinutes: Long): Boolean {
    if (!RootAccess.isGranted) return false
    val process = runCatching { RootSu.process(command).redirectErrorStream(true).start() }.getOrNull() ?: return false
    return try {
        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } finally {
        runCatching { process.destroy() }
    }
}

private fun canWalk(root: File): Boolean = root.canRead() && (root.listFiles()?.firstOrNull()?.canRead() != false)

private fun canWrite(root: File): Boolean = root.parentFile?.canWrite() == true && (!root.exists() || root.canWrite())

private fun encodeFiles(files: Map<String, ByteArray>): Map<String, String> =
    files.mapValues { (_, bytes) -> Base64.encodeToString(bytes, Base64.NO_WRAP) }

private fun decodeFiles(files: Map<String, String>): Map<String, ByteArray> =
    files.mapValues { (_, encoded) -> Base64.decode(encoded, Base64.NO_WRAP) }

private fun duBytes(root: File): Long {
    if (!root.exists() || !RootAccess.isGranted) return 0L
    val process = runCatching {
        RootSu.process("du -sb -- ${shellQuote(root.absolutePath)}").redirectErrorStream(true).start()
    }.getOrNull() ?: return 0L
    return try {
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            0L
        } else {
            process.inputStream.bufferedReader().readText().trim().substringBefore('\t').toLongOrNull() ?: 0L
        }
    } finally {
        runCatching { process.destroy() }
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun directorySize(root: File): Long {
    if (!root.exists() || !root.canRead()) return 0L
    return runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
}

private fun countFiles(root: File): Int {
    if (!root.exists()) return 0
    return runCatching { root.walkTopDown().count { it.isFile } }.getOrDefault(0)
}
