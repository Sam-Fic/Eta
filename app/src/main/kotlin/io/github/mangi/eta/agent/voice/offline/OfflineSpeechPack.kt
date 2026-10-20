package io.github.mangi.eta.agent.voice.offline

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One app-process download. Private no-backup storage; neither audio nor credentials uploaded. */
internal object OfflineSpeechPack {
    data class State(
        val checking: Boolean = true,
        val ready: Boolean = false,
        val enabled: Boolean = false,
        val downloading: Boolean = false,
        val downloadedBytes: Long = 0,
        val error: Boolean = false,
        val model: SpeechModelCatalog.Model = SpeechModelCatalog.defaultModel,
        val source: SpeechModelCatalog.Source = SpeechModelCatalog.defaultModel.sources.first(),
    ) {
        val totalBytes: Long get() = model.totalBytes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var initialized = false
    private var download: Job? = null
    private fun prefs(context: Context) = context.getSharedPreferences("offline_speech", Context.MODE_PRIVATE)

    /** Per-model directory: `<modelId>/<revision>` under private no-backup storage. */
    fun directory(context: Context, model: SpeechModelCatalog.Model = mutable.value.model) =
        File(context.noBackupFilesDir, "speech/${model.id}/${model.revision}")

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            val stored = prefs(app)
            val model = SpeechModelCatalog.model(stored.getString("model", null))
            val source = model.source(SpeechModelCatalog.SourceId.from(stored.getString("source", null)))
            val ready = withContext(Dispatchers.IO) { verify(directory(app, model), model) }
            val enabled = ready && stored.getBoolean("enabled", false)
            if (!ready) stored.edit().putBoolean("enabled", false).apply()
            mutable.value = State(checking = false, ready = ready, enabled = enabled, model = model, source = source)
        }
    }

    /** 模型文件被备份恢复后重新校验；取消进行中的下载，避免旧任务发布过期状态。 */
    @Synchronized fun refresh(context: Context) {
        download?.cancel()
        download = null
        initialized = false
        mutable.value = mutable.value.copy(
            checking = true,
            downloading = false,
            downloadedBytes = 0,
            error = false,
        )
        initialize(context)
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && !SpeechInputPolicy.canEnable(mutable.value.ready, mutable.value.checking, mutable.value.downloading)) return false
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        mutable.value = mutable.value.copy(enabled = enabled)
        return true
    }

    /** Switching model or source re-verifies the newly selected pair; an active download is refused. */
    fun select(context: Context, modelId: String, sourceId: SpeechModelCatalog.SourceId) {
        if (mutable.value.downloading) return
        val model = SpeechModelCatalog.model(modelId)
        val source = model.source(sourceId)
        if (model.id == mutable.value.model.id && source.id == mutable.value.source.id) return
        val app = context.applicationContext
        prefs(app).edit().putString("model", model.id).putString("source", source.id.wire).apply()
        mutable.value = State(checking = true, model = model, source = source)
        scope.launch {
            val ready = withContext(Dispatchers.IO) { verify(directory(app, model), model) }
            // A newer selection may have landed while hashing; never publish stale readiness.
            if (mutable.value.model.id != model.id || mutable.value.source.id != source.id) return@launch
            // 「已启用」隐含「已就绪」：切到未下载的模型时要同步收回启用标记，与启动校验一致。
            val enabled = ready && prefs(app).getBoolean("enabled", false)
            if (!ready) prefs(app).edit().putBoolean("enabled", false).apply()
            mutable.value = mutable.value.copy(checking = false, ready = ready, enabled = enabled, error = false)
        }
    }

    fun cancelDownload() { download?.cancel() }

    fun download(context: Context) {
        val snapshot = mutable.value
        if (snapshot.checking || snapshot.downloading || snapshot.ready) return
        val app = context.applicationContext
        val model = snapshot.model
        val source = snapshot.source
        mutable.value = snapshot.copy(downloading = true, downloadedBytes = 0, error = false)
        download = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dir = directory(app, model).apply { mkdirs() }
                    var done = 0L
                    for (asset in model.assets) {
                        ensureActive()
                        val target = File(dir, asset.name)
                        if (!valid(target, asset)) {
                            val partial = File(dir, asset.name + ".part")
                            val connection = URI(source.baseUrl + asset.name).toURL()
                                .openConnection() as HttpURLConnection
                            connection.connectTimeout = 15_000
                            connection.readTimeout = 15_000
                            connection.setRequestProperty("Accept-Encoding", "identity")
                            try {
                                check(connection.responseCode == 200) { "Model download HTTP failure" }
                                check(connection.url.protocol == "https") { "Insecure model redirect" }
                                var received = 0L
                                var lastProgress = 0L
                                connection.inputStream.use { input ->
                                    partial.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            received += count
                                            check(received <= asset.bytes) { "Model file exceeds manifest size" }
                                            output.write(buffer, 0, count)
                                            if (received - lastProgress >= 256 * 1024) {
                                                lastProgress = received
                                                withContext(Dispatchers.Main) {
                                                    mutable.value = mutable.value.copy(downloadedBytes = done + received)
                                                }
                                            }
                                        }
                                    }
                                }
                                check(valid(partial, asset)) { "Model checksum mismatch" }
                                ensureActive()
                                check(partial.renameTo(target)) { "Cannot commit verified model" }
                            } finally {
                                connection.disconnect()
                                partial.delete()
                            }
                        }
                        done += asset.bytes
                        withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(downloadedBytes = done) }
                    }
                }
                // Downloading does not silently enable microphone functionality.
                mutable.value = mutable.value.copy(checking = false, ready = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = true)
            } finally {
                mutable.value = mutable.value.copy(downloading = false)
            }
        }
    }

    internal fun valid(file: File, asset: SpeechModelCatalog.Asset): Boolean = runCatching {
        if (!file.isFile || file.length() != asset.bytes) return false
        // Text files upstream publishes no hash for are length-checked only; weight files always hash.
        val expected = asset.sha256 ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == expected
    }.getOrDefault(false)

    private fun verify(dir: File, model: SpeechModelCatalog.Model) =
        model.assets.all { valid(File(dir, it.name), it) }
}
