package io.github.mangi.eta.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode

/** 用标准 activity-alias 切换桌面图标。vivo 启动器认这个，不依赖厂商私有接口。 */
internal object LauncherIconSync {
    private const val PREFS = "launcher_icon"
    private const val KEY_RES = "res"
    private const val PREFIX = "launcher_"

    val swatches: IntArray = intArrayOf(
        0xFFF27A1A.toInt(),
        0xFF7B61FF.toInt(),
        0xFFF26D9A.toInt(),
        0xFF3DDC84.toInt(),
        0xFFF5C542.toInt(),
        0xFF2491FF.toInt(),
        0xFFF6F7F9.toInt(),
        0xFF1C1C1E.toInt(),
    )

    fun nearest(color: Int): Int {
        val rgb = color and 0xFFFFFF
        return swatches.minBy { swatch ->
            val other = swatch and 0xFFFFFF
            val dr = ((rgb shr 16) and 0xFF) - ((other shr 16) and 0xFF)
            val dg = ((rgb shr 8) and 0xFF) - ((other shr 8) and 0xFF)
            val db = (rgb and 0xFF) - (other and 0xFF)
            dr * dr + dg * dg + db * db
        }
    }

    fun resourceName(background: Int): String {
        val bg = nearest(background) and 0xFFFFFF
        return PREFIX + "%06x".format(bg)
    }

    fun apply(context: Context, settings: AppearanceSettings) {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val darkUi = when (settings.themeMode) {
            AppearanceThemeMode.DARK -> true
            AppearanceThemeMode.LIGHT -> false
            AppearanceThemeMode.SYSTEM -> night
        }
        val background = if (darkUi) settings.iconDarkColor else settings.iconLightColor
        val bg = nearest(background) and 0xFFFFFF
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RES, PREFIX + "%06x".format(bg)).apply()
        val packageName = context.packageName
        val target = ComponentName(packageName, componentName(packageName, bg))
        val pm = context.packageManager
        val changed = !isLauncherEnabled(pm, target)
        if (!changed) {
            io.github.mangi.eta.agent.runtime.AgentExecutionService.refreshIcon()
            return
        }
        pm.setComponentEnabledSetting(
            target,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        for (swatchBackground in swatches) {
            val component = ComponentName(
                packageName,
                componentName(packageName, swatchBackground and 0xFFFFFF),
            )
            if (component == target) continue
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        io.github.mangi.eta.agent.runtime.AgentExecutionService.refreshIcon()
    }

    private fun componentName(packageName: String, rgb: Int): String =
        "$packageName.icon.L_%06X".format(rgb)

    fun currentIconResName(context: Context): String? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RES, null)
            ?: return null
        // 旧版本资源名是「背景_前景」两段式，前景段已废弃：只取背景段，避免历史值解析不到资源。
        val bg = stored.removePrefix(PREFIX).substringBefore('_')
        if (bg.length != 6) return null
        return PREFIX + bg
    }

    private fun isLauncherEnabled(pm: PackageManager, component: ComponentName): Boolean {
        val state = pm.getComponentEnabledSetting(component)
        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return false
        // 别名在 manifest 里默认 enabled=false，系统清单状态（DEFAULT）时只有浅色默认图标生效。
        return component.className.endsWith("L_F6F7F9")
    }
}
