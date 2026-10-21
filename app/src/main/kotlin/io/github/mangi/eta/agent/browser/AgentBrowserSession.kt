package io.github.mangi.eta.agent.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.util.Base64
import io.github.mangi.eta.agent.browser.ported.browser.BrowserActionInput
import io.github.mangi.eta.agent.browser.ported.browser.BrowserActionResult
import io.github.mangi.eta.agent.browser.ported.browser.BrowserTabPool
import io.github.mangi.eta.agent.browser.ported.browser.UserAgentProfile
import io.github.mangi.eta.agent.terminal.LinuxGuestPathResolver
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class BrowserSessionSnapshot(
    val tabId: Int? = null,
    val tabCount: Int = 0,
    val available: Boolean = false,
    val url: String = "",
    val displayUrl: String = "",
    val host: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val isPageVisible: Boolean = false,
    val hasCommittedPage: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null,
    val isUserControlling: Boolean = false,
    val lastAgentRunId: String? = null,
    val lastAgentToolCallId: String? = null,
    val desktopMode: Boolean = true,
    val userAgent: String = BrowserUserAgent.DEFAULT.wireName,
)

internal data class BrowserImage(
    val dataUrl: String,
    val mimeType: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
)

internal data class BrowserToolResult(
    val content: String,
    val images: List<BrowserImage> = emptyList(),
)

/** Host adapter: UI and tools use exactly the same OpenMinis-derived tab pool. */
internal object AgentBrowserSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private const val GLOBAL_TAB_BUDGET = 6

    private val gate = Any()
    private val jobs = mutableMapOf<String, Deferred<BrowserToolResult>>()
    private val jobRunIds = mutableMapOf<String, String?>()
    private var userOwner: Any? = null
    private var userConversationId: String? = null
    @Volatile private var context: Context? = null
    private val pools = mutableMapOf<String, BrowserTabPool>()
    private var pool: BrowserTabPool? = null
    private var prefsReady = false
    private var snapshotLoopStarted = false
    @Volatile var isUserControlling: Boolean = false
        private set
    private var lastRunId: String? = null
    private var lastCallId: String? = null
    private val mutableSnapshots = MutableStateFlow(BrowserSessionSnapshot())
    val snapshots: StateFlow<BrowserSessionSnapshot> = mutableSnapshots.asStateFlow()

    fun isControlling(conversationId: String): Boolean = userConversationId == conversationId

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    private fun conversationKey(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        val safe = trimmed.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return safe.ifBlank { "daiyu-draft" }
    }

    private fun ensurePrefs(app: Context) {
        if (prefsReady) return
        val prefs = app.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        if (!prefs.contains("user_agent_profile")) {
            val old = BrowserUserAgent.load()
            prefs.edit().putString("user_agent_profile", if (old.desktop) "DESKTOP_CHROME" else "MOBILE_CHROME").apply()
        }
        prefsReady = true
    }

    private fun evictIdlePools(except: String) {
        fun total() = pools.values.sumOf { it.tabs.value.size }
        if (total() < GLOBAL_TAB_BUDGET) return
        val victims = pools.filter { (id, browser) ->
            id != except && id != userConversationId && browser.tabs.value.none { it.inUse }
        }.keys.toList()
        for (id in victims) {
            if (total() < GLOBAL_TAB_BUDGET) break
            pools.remove(id)?.destroy()
        }
    }

    private suspend fun ensurePool(conversationId: String?): BrowserTabPool = withContext(Dispatchers.Main.immediate) {
        val key = conversationKey(conversationId)
        evictIdlePools(key)
        pools[key]?.also { pool = it } ?: run {
            val app = requireNotNull(context)
            ensurePrefs(app)
            BrowserTabPool(app).also { created ->
                pools[key] = created
                pool = created
                created.setSession(key)
                if (!snapshotLoopStarted) {
                    snapshotLoopStarted = true
                    scope.launch {
                        while (isActive) {
                            publishSnapshot()
                            delay(if (pools.values.any { it.isAgentBusy } || isUserControlling) 250 else 1000)
                        }
                    }
                }
            }
        }
    }

    /** 只接管当前会话的标签，不停掉其他会话正在加载的页面。 */
    suspend fun acquireUserControl(context: Context, owner: Any, conversationId: String? = null): BrowserTabPool {
        initialize(context)
        val key = conversationKey(conversationId)
        val pending = synchronized(gate) {
            userOwner = owner
            userConversationId = key
            isUserControlling = true
            jobs.remove(key)?.also { jobRunIds.remove(key) }
        }
        pending?.cancel()
        pending?.join()
        synchronized(gate) {
            if (userOwner !== owner) throw CancellationException("Browser page disposed")
        }
        return try {
            withContext(Dispatchers.Main.immediate) {
                ensurePool(key).also { browser ->
                    browser.tabs.value.forEach { it.manager.stopLoading() }
                    browser.releaseAllTabs()
                    browser.ensureTabForUI()
                    publishSnapshot()
                }
            }
        } catch (t: Throwable) {
            // 初始化失败必须立刻把「用户接管」还回去：否则浏览器工具会一直收到
            // USER_CONTROL_ACTIVE（用户明明没在浏览器页里），而且用户再点
            // 「打开浏览器」也会因为所有权被占而拿不到会话。
            releaseOwnership(owner)
            publishSnapshot()
            throw t
        }
    }

    /** 同步归还所有权（失败路径用，不能等协程）。 */
    private fun releaseOwnership(owner: Any) {
        synchronized(gate) {
            if (userOwner !== owner) return
            userOwner = null
            userConversationId = null
            isUserControlling = false
        }
    }

    fun releaseUserControl(owner: Any) {
        val key = synchronized(gate) {
            if (userOwner !== owner) return
            userOwner = null
            userConversationId
        }
        scope.launch {
            yield()
            synchronized(gate) { if (userOwner != null) return@launch }
            key?.let { pools[it] }?.let { browser ->
                val (width, height) = browser.resolvedViewportSize()
                browser.tabs.value.forEach { tab ->
                    if (tab.manager.webView.parent == null) tab.manager.applyViewport(width, height)
                }
            }
            synchronized(gate) {
                if (userOwner == null) {
                    userConversationId = null
                    isUserControlling = false
                }
            }
            publishSnapshot()
        }
    }

    private fun publishSnapshot() {
        val browser = pool ?: return
        val manager = browser.activeManager
        val url = manager?.currentURL?.value.orEmpty()
        val loading = manager?.isLoading?.value == true
        val profile = browser.currentUserAgentProfile.value
        mutableSnapshots.value = BrowserSessionSnapshot(
            tabId = browser.selectedTabId.value.takeIf { manager != null },
            tabCount = browser.tabs.value.size,
            available = manager != null && url.isNotBlank(),
            url = url, displayUrl = url, host = Uri.parse(url).host.orEmpty(),
            title = manager?.pageTitle?.value.orEmpty().take(160),
            isLoading = loading, isPageVisible = !loading && url.isNotBlank(),
            hasCommittedPage = url.isNotBlank(), progress = if (loading) 0 else 100,
            canGoBack = manager?.canGoBack?.value == true,
            canGoForward = manager?.canGoForward?.value == true,
            isUserControlling = isUserControlling,
            lastAgentRunId = lastRunId, lastAgentToolCallId = lastCallId,
            desktopMode = profile == UserAgentProfile.DESKTOP_CHROME,
            userAgent = profile.value,
        )
    }

    fun execute(
        context: Context,
        args: JSONObject,
        runId: String?,
        toolCallId: String?,
        conversationId: String? = null,
    ): BrowserToolResult {
        initialize(context)
        val action = args.optString("action").trim().lowercase()
        if (Looper.myLooper() == Looper.getMainLooper()) return error(action, "MAIN_THREAD_CALL", "浏览器工具不能阻塞主线程")
        val key = conversationKey(conversationId)
        val job = synchronized(gate) {
            if (userConversationId == key) return error(action, "USER_CONTROL_ACTIVE", "用户正在接管这个会话的浏览器，请等待用户离开浏览器页面")
            val current = jobs[key]
            if (current != null && !current.isCompleted) return error(action, "BROWSER_BUSY", "这个会话的浏览器正在执行另一项操作，请等待完成")
            scope.async(start = CoroutineStart.LAZY) {
                if (userConversationId == key) return@async error(action, "USER_CONTROL_ACTIVE", "用户正在接管这个会话的浏览器")
                lastRunId = runId
                lastCallId = toolCallId
                val normalized = JSONObject(args.toString()).put("action", action)
                val input = BrowserActionInput.parse(normalized.toString())
                    ?: return@async error(action, "INVALID_ARGUMENT", "浏览器参数或 action 无效")
                val browser = ensurePool(key)
                try {
                    val timeout = normalized.optLong("timeout_ms", normalized.optLong("timeout", 30000L)).coerceIn(500, 60000)
                    val result = withTimeout(if (action == "navigate" || action == "wait_for_selector") timeout + 1000 else 90000L) {
                        browser.execute(input, singleTab = true)
                    }
                    toToolResult(action, args, result, browser)
                } finally {
                    if (!isActive) {
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            browser.tabs.value.forEach { it.manager.stopLoading() }
                            browser.releaseAllTabs()
                        }
                    }
                    publishSnapshot()
                }
            }.also {
                jobs[key] = it
                jobRunIds[key] = runId
            }
        }
        return try {
            runBlocking { job.await() }
        } catch (_: TimeoutCancellationException) {
            error(action, "BROWSER_TIMEOUT", "浏览器操作超时；有副作用的操作结果未确认，请先观察页面")
        } catch (_: CancellationException) {
            error(action, "CANCELLED", "浏览器操作已中断，结果未确认，请先观察页面")
        } catch (_: Exception) {
            error(action, "BROWSER_ERROR", "浏览器操作失败，请检查页面后重试")
        } finally {
            synchronized(gate) {
                if (jobs[key] === job) {
                    jobs.remove(key)
                    jobRunIds.remove(key)
                }
            }
        }
    }

    fun interruptAgentAction(runId: String? = null) {
        synchronized(gate) {
            if (runId == null) {
                jobs.values.forEach { it.cancel() }
            } else {
                jobRunIds.entries.firstOrNull { it.value == runId }?.let { jobs[it.key]?.cancel() }
            }
        }
    }

    internal suspend fun toToolResult(action: String, args: JSONObject, result: BrowserActionResult, browser: BrowserTabPool): BrowserToolResult {
        val body = if (action in setOf("get_text", "get_readable", "get_cookies")) {
            runCatching { JSONObject(result.text.substringBeforeLast("\n  tab_id:", result.text)) }.getOrNull()
        } else null
        val envelope = body ?: JSONObject().put("text", result.text)
        val target = result.tabId?.let { id -> browser.tabs.value.firstOrNull { it.id == id }?.manager }
            ?: browser.activeManager
        envelope.put("ok", result.success).put("tool", "browser_use").put("action", action)
            .put("status", if (result.success) "ok" else "error")
            .put("tab_id", result.tabId ?: JSONObject.NULL)
            .put("url", result.pageURL ?: target?.currentURL?.value.orEmpty()).put("content_source", "web_page")
        val images = withContext(Dispatchers.IO) {
            if (action == "screenshot" && !args.optBoolean("read_image", true)) emptyList()
            else loadImage(result)?.let(::listOf).orEmpty()
        }
        if (images.isNotEmpty()) envelope.put("snapshot_attached", true)
        result.imageFilePath?.let { envelope.put("image_path", it) }
        result.fetchedFileData?.let { data ->
            require(data.size <= 8 * 1024 * 1024) { "Download exceeds limit" }
            val file = withContext(Dispatchers.IO) {
                val dir = File(LinuxGuestPathResolver.resolveForApp(requireNotNull(context), "/var/minis/browser"))
                check(dir.isDirectory || dir.mkdirs())
                File.createTempFile("fetch_", "." + File(result.fetchedFileName.orEmpty()).extension.take(12).ifBlank { "bin" }, dir)
                    .also { it.writeBytes(data) }
            }
            envelope.put("path", file.absolutePath).put("bytes", data.size)
                .put("minis_path", "/var/minis/browser/${file.name}")
        }
        return BrowserToolResult(BrowserPayloadLimiter.serialize(envelope), images)
    }

    private fun loadImage(result: BrowserActionResult): BrowserImage? {
        val data = result.imageFilePath?.let { path ->
            val file = File(path)
            if (file.isFile && file.length() <= 8 * 1024 * 1024) file.readBytes() else null
        } ?: result.base64Image?.takeIf { it.length <= 12 * 1024 * 1024 }?.let { Base64.decode(it, Base64.DEFAULT) }
            ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return BrowserImage("data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP),
            "image/jpeg", data.size, options.outWidth, options.outHeight)
    }

    fun capturePreview(): BrowserImage? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        return runCatching {
            runBlocking {
                withTimeoutOrNull(2000) {
                    withContext(Dispatchers.Main) {
                        val bitmap = pool?.activeManager?.captureLiveSnapshot() ?: return@withContext null
                        try {
                            val out = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
                            val data = out.toByteArray()
                            BrowserImage("data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP),
                                "image/jpeg", data.size, bitmap.width, bitmap.height)
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }.getOrNull()
    }

    private fun error(action: String, code: String, message: String) = BrowserToolResult(
        JSONObject().put("ok", false).put("tool", "browser_use").put("action", action)
            .put("status", "error").put("code", code).put("message", message).toString(),
    )
}
