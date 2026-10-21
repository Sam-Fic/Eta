package io.github.mangi.eta.agent.media.index

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

/**
 * 媒体语义索引的自动更新：两个独立开关，可只开其一、也可同时开。
 *
 * - 启动后自动更新：应用启动（完整运行时初始化后，[onAppLaunch]）跑一轮增量索引；
 * - 定时自动更新：[schedule] 排 AlarmManager 周期闹钟，到点由 [MediaIndexAutoUpdateReceiver] 跑一轮。
 *
 * 两条路径统一走 [runAuto] 守卫（[dueReason]，纯函数可单测）：模型未下载 / 索引进行中 /
 * 防抖窗口内（距上次自动索引启动不足 [DEBOUNCE_WINDOW_MS]）都静默跳过。
 * **自动更新固定 [AUTO_MODE]（低耗后台档）**，不跟随用户手选的 [IndexMode]。
 *
 * 存储与 [EmbeddingModelStore.indexMode] 同一 SharedPreferences（`embedding_model`），
 * 闹钟风格沿用 [io.github.mangi.eta.ui.components.ScheduledSendAlarm]（非精确闹钟 + PendingIntent）；
 * AlarmManager 闹钟重启后丢失，[onAppLaunch] 每次启动都重排兜底，开机广播（BOOT_COMPLETED）再补一次。
 */
internal object MediaIndexAutoUpdate {
    /** 自动更新固定档位：低耗后台，不跟随用户手选档位（[IndexMode] 只影响手动索引/检索）。 */
    val AUTO_MODE: IndexMode = IndexMode.POWER_SAVER

    /** 防抖窗口（毫秒）：距上次自动索引启动不足该时长时跳过，避免启动/闹钟抖动反复跑索引。 */
    const val DEBOUNCE_WINDOW_MS = 30 * 60 * 1000L

    /** 定时档位允许值（小时）：6 / 24 / 72 / 168（每 6 小时 / 每天 / 每 3 天 / 每周）。 */
    private val ALLOWED_INTERVAL_HOURS = setOf(6, 24, 72, 168)

    /** 自动更新跳过原因（[dueReason] 返回值）。 */
    enum class SkipReason {
        /** 嵌入模型未下载（或字节数不符），索引起不来，跳过。 */
        MODEL_NOT_DOWNLOADED,

        /** 已有一轮索引在跑，跳过。 */
        INDEXING,

        /** 防抖：距上次自动索引启动不足 [DEBOUNCE_WINDOW_MS]，跳过。 */
        DEBOUNCED,
    }

    /** 纯函数：定时档位解析；非法值一律回落 0（关闭），与 `dim` 的偏好损坏口径一致。 */
    fun parseIntervalHours(stored: Int): Int = if (stored in ALLOWED_INTERVAL_HOURS) stored else 0

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("embedding_model", Context.MODE_PRIVATE)

    /** 启动后自动更新开关；默认关（false）。 */
    fun autoIndexOnLaunch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ON_LAUNCH, false)

    fun setAutoIndexOnLaunch(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON_LAUNCH, enabled).apply()
    }

    /** 定时自动更新档位（小时）；0 = 关闭（默认），非法存储值回落 0。 */
    fun autoIndexIntervalHours(context: Context): Int =
        parseIntervalHours(prefs(context).getInt(KEY_INTERVAL_HOURS, 0))

    fun setAutoIndexIntervalHours(context: Context, hours: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL_HOURS, parseIntervalHours(hours)).apply()
    }

    /** 上次自动索引启动时间戳（毫秒）；0 = 从未。防抖判据。 */
    fun lastAutoAtMillis(context: Context): Long =
        prefs(context).getLong(KEY_LAST_AUTO_AT, 0L)

    fun setLastAutoAtMillis(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_LAST_AUTO_AT, millis).apply()
    }

    /**
     * 应用启动（完整运行时初始化后）调用：启动开关开则跑一轮 [runAuto]；
     * 无论开关状态都 [schedule] 重排定时闹钟——闹钟重启后丢失，靠启动重排兜底。
     */
    fun onAppLaunch(context: Context) {
        if (autoIndexOnLaunch(context)) runAuto(context)
        schedule(context)
    }

    /**
     * 定时自动更新闹钟：`interval > 0` 用 [AlarmManager.setInexactRepeating]（RTC_WAKEUP，
     * 首跳 = now + interval，与 [io.github.mangi.eta.ui.components.ScheduledSendAlarm] 同一
     * PendingIntent 风格，非精确闹钟无需精确闹钟权限）；`= 0` 则取消既有闹钟。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)
        val intervalHours = autoIndexIntervalHours(app)
        if (intervalHours <= 0) {
            alarmManager.cancel(pending)
            return
        }
        val intervalMs = intervalHours * 60L * 60L * 1000L
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + intervalMs,
            intervalMs,
            pending,
        )
    }

    /**
     * 跑一轮自动索引：[dueReason] 守卫通过后以固定 [AUTO_MODE]（低耗后台）启动
     * [MediaIndexService] 并记时间戳（防抖判据）；任一守卫命中则静默跳过。
     */
    fun runAuto(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val reason = dueReason(
            modelDownloaded = EmbeddingModelStore.modelDownloaded(app),
            indexing = MediaIndexer.isRunning(),
            nowMillis = now,
            lastAutoAtMillis = lastAutoAtMillis(app),
        )
        if (reason != null) return
        runCatching { MediaIndexService.start(app, mode = AUTO_MODE) }
            .onSuccess { setLastAutoAtMillis(app, now) }
            .onFailure { AndroidAgentLogger.warn("Auto media index start failed: type=${it.safeLogType()}") }
    }

    /**
     * 纯函数：自动更新守卫。null = 可以跑；否则为跳过原因，
     * 判定优先级：模型未下载 > 索引进行中 > 防抖窗口内。
     */
    fun dueReason(
        modelDownloaded: Boolean,
        indexing: Boolean,
        nowMillis: Long,
        lastAutoAtMillis: Long,
    ): SkipReason? = when {
        !modelDownloaded -> SkipReason.MODEL_NOT_DOWNLOADED
        indexing -> SkipReason.INDEXING
        // 从未跑过（0）不防抖；窗口内（含边界前一毫秒）一律跳过。
        lastAutoAtMillis > 0L && nowMillis - lastAutoAtMillis < DEBOUNCE_WINDOW_MS -> SkipReason.DEBOUNCED
        else -> null
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MediaIndexAutoUpdateReceiver::class.java).setAction(ACTION_AUTO)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 自动更新闹钟的自定义 action（显式组件广播，receiver exported=false 也可送达）。 */
    const val ACTION_AUTO = "io.github.mangi.eta.action.MEDIA_INDEX_AUTO_UPDATE"

    private const val KEY_ON_LAUNCH = "auto_index_on_launch"
    private const val KEY_INTERVAL_HOURS = "auto_index_interval_hours"
    private const val KEY_LAST_AUTO_AT = "auto_index_last_at"

    /** PendingIntent requestCode 固定常量：同一闹钟反复重排/取消共用同一 token。 */
    private const val REQUEST_CODE = 1109
}

/**
 * 自动更新的广播入口（manifest 注册，exported=false）：
 * 定时闹钟到点 → [MediaIndexAutoUpdate.runAuto] 跑一轮；
 * 开机（BOOT_COMPLETED）→ [MediaIndexAutoUpdate.schedule] 补排周期闹钟（AlarmManager 闹钟重启后丢失）。
 */
internal class MediaIndexAutoUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> MediaIndexAutoUpdate.schedule(context)
            MediaIndexAutoUpdate.ACTION_AUTO -> MediaIndexAutoUpdate.runAuto(context)
        }
    }
}
