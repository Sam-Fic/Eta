package io.github.mangi.eta.agent.tool

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * 定位本机系统时钟应用：免 Root 通过标准 AlarmClock intent 解析设备实际使用的时钟应用，
 * 之后按「解析结果优先、常见时钟包名兜底」的顺序探测时钟数据库位置。
 *
 * 不针对任何厂商写死路径——ColorOS、AOSP、Samsung 等都能命中，前提是时钟应用
 * 使用标准的 alarms.db 结构（AOSP 系时钟应用均如此）。
 */
internal class ClockAppLocator(private val context: Context) {

    /** 本机可用时钟应用，按优先级排序：标准 intent 解析到的在前，内置候选兜底在后。 */
    fun preferredPackages(): List<String> {
        val resolved = mutableListOf<String>()
        val packageManager = context.packageManager
        for (action in RESOLVER_ACTIONS) {
            runCatching { packageManager.queryIntentActivities(Intent(action), 0) }.getOrNull()
                ?.forEach { info ->
                    info.activityInfo?.packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
                        if (pkg !in resolved) resolved += pkg
                    }
                }
        }
        return (resolved + KNOWN_CLOCK_PACKAGES).distinct()
    }

    /** 时钟数据库候选路径；探测按顺序进行，取第一个真实存在的文件。 */
    fun databaseCandidates(userId: Int): List<String> = preferredPackages().flatMap { pkg ->
        listOf(
            "/data/user_de/$userId/$pkg/databases/alarms.db",
            "/data/user/$userId/$pkg/databases/alarms.db",
            "/data/data/$pkg/databases/alarms.db",
        )
    }

    private companion object {
        // 标准闹钟/计时器 intent：设备上真正响应它们的时钟应用即系统时钟实现。
        val RESOLVER_ACTIONS = listOf(
            AlarmClock.ACTION_SHOW_ALARMS,
            AlarmClock.ACTION_SET_ALARM,
            AlarmClock.ACTION_SET_TIMER,
        )
        // intent 解析失败（包可见性受限等）时的常见时钟应用兜底，AOSP 系在前。
        val KNOWN_CLOCK_PACKAGES = listOf(
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.coloros.alarmclock",
            "com.oneplus.deskclock",
            "com.sec.android.app.clockpackage",
            "com.android.alarmclock",
        )
    }
}
