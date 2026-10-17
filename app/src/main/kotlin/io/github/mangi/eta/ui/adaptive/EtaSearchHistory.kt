package io.github.mangi.eta.ui.adaptive

import android.content.Context

/**
 * 独立搜索页的查询历史：SharedPreferences 按 [key] 分域存储（助手 / 供应商 / 设置各一份）。
 *
 * 最近在前、去重、上限 [MAX_ENTRIES] 条；单条以换行分隔拼成一个字符串存取，
 * 录入时把查询词里的换行剥掉（搜索框是单行输入，正常到不了这里，防御一下）。
 * 读取走 SharedPreferences 的内存缓存，空态直接在组合期读取即可，无需自建状态。
 */
internal object EtaSearchHistory {
    private const val FILE = "eta_search_history"
    private const val MAX_ENTRIES = 10
    private const val SEPARATOR = "\n"

    fun load(context: Context, key: String): List<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /** 记录一次搜索：去重置顶，超出上限截断。空查询忽略。 */
    fun add(context: Context, key: String, query: String) {
        val needle = query.replace(SEPARATOR, " ").trim()
        if (needle.isEmpty()) return
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val updated = (listOf(needle) + load(context, key)).distinct().take(MAX_ENTRIES)
        prefs.edit().putString(key, updated.joinToString(SEPARATOR)).apply()
    }

    fun clear(context: Context, key: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().remove(key).apply()
    }
}
