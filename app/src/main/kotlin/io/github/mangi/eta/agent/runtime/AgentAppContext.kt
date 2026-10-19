package io.github.mangi.eta.agent.runtime

import android.content.Context

internal object AgentAppContext {
    fun resolve(): Context? {
        val activityThreadClass = runCatching {
            Class.forName("android.app.ActivityThread")
        }.getOrNull()
        runCatching {
            activityThreadClass
                ?.getDeclaredMethod("currentApplication")
                ?.apply { isAccessible = true }
                ?.invoke(null) as? Context
        }.getOrNull()?.let { return it.applicationContext ?: it }

        runCatching {
            Class.forName("android.app.AppGlobals")
                .getDeclaredMethod("getInitialApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
        }.getOrNull()?.let { return it.applicationContext ?: it }

        return null
    }
}

/**
 * 用户可见文案的统一取值：生产环境按应用语言读字符串资源；
 * 纯 JVM 单测没有 Context，回退到调用方提供的中文基准文案（与 BreenoHooks 的既有回退先例一致）。
 */
internal fun localizedText(resId: Int, fallback: String, vararg args: Any): String =
    runCatching { AgentAppContext.resolve()?.getString(resId, *args) }.getOrNull()
        ?: if (args.isEmpty()) fallback else String.format(fallback, *args)
