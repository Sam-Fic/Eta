package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

const val MIN_INTERFACE_SCALE = 0.8f
const val MAX_INTERFACE_SCALE = 1.1f
const val DEFAULT_INTERFACE_SCALE = 1f

@Serializable
data class AppearanceSettings(
    val themeMode: AppearanceThemeMode = AppearanceThemeMode.LIGHT,
    val themeFamily: AppearanceThemeFamily = AppearanceThemeFamily.MIUI_X,
    val monetEnabled: Boolean = true,
    val paletteStyle: AppearancePaletteStyle = AppearancePaletteStyle.NEUTRAL,
    val accentColor: AppearanceAccentColor = AppearanceAccentColor.SYSTEM,
    val pureBlackEnabled: Boolean = false,
    val blurEnabled: Boolean = true,
    val topBarBlurStyle: AppearanceTopBarBlurStyle = AppearanceTopBarBlurStyle.GAUSSIAN,
    val swipeDismissEnabled: Boolean = true,
    val predictiveBackEnabled: Boolean = false,
    val interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
    val morphLoadingIndicator: Boolean = true,
    val morphLoadingBeforeResponseOnly: Boolean = false,
    val messageTimestampsEnabled: Boolean = false,
    val iconLightColor: Int = DEFAULT_ICON_LIGHT,
    val iconDarkColor: Int = DEFAULT_ICON_DARK,
    val iconCatColor: Int = DEFAULT_ICON_CAT,
) {
    fun normalized(): AppearanceSettings = copy(
        monetEnabled = true,
        interfaceScale = normalizeInterfaceScale(interfaceScale),
        iconLightColor = iconLightColor or 0xFF000000.toInt(),
        iconDarkColor = iconDarkColor or 0xFF000000.toInt(),
        iconCatColor = iconCatColor or 0xFF000000.toInt(),
    )

    companion object {
        const val DEFAULT_ICON_LIGHT = 0xFFFFFFFF.toInt()
        const val DEFAULT_ICON_DARK = 0xFF1C1C1E.toInt()
        const val DEFAULT_ICON_CAT = 0xFF2491FF.toInt()
    }
}

@Serializable
enum class AppearanceThemeMode(val persistedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceThemeMode =
            entries.firstOrNull { it.persistedValue == value } ?: LIGHT
    }
}

/**
 * UI 组件体系（主题族）。
 *
 * - [MIUI_X]：使用 miuix（`top.yukonga.miuix.kmp`）组件，即当前默认实现。
 * - [MATERIAL3_EXPRESSIVE]：使用标准 Compose Material 3 Expressive 组件重写后的界面。
 *
 * 两套实现共存，可在「设置 → 外观与主题」里实时切换。
 */
@Serializable
enum class AppearanceThemeFamily(val persistedValue: String) {
    MIUI_X("miui_x"),
    MATERIAL3_EXPRESSIVE("material3_expressive");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceThemeFamily =
            entries.firstOrNull { it.persistedValue == value } ?: MIUI_X
    }
}

@Serializable
enum class AppearancePaletteStyle(val persistedValue: String) {
    TONAL_SPOT("tonal_spot"),
    NEUTRAL("neutral"),
    VIBRANT("vibrant"),
    EXPRESSIVE("expressive"),
    RAINBOW("rainbow"),
    FRUIT_SALAD("fruit_salad"),
    MONOCHROME("monochrome"),
    FIDELITY("fidelity"),
    CONTENT("content");

    companion object {
        fun fromPersistedValue(value: String?): AppearancePaletteStyle =
            entries.firstOrNull { it.persistedValue == value } ?: NEUTRAL
    }
}

@Serializable
enum class AppearanceAccentColor(val persistedValue: String) {
    SYSTEM("system"),
    BLUE("blue"),
    PURPLE("purple"),
    PINK("pink"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    TEAL("teal");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceAccentColor =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

@Serializable
enum class AppearanceTopBarBlurStyle(val persistedValue: String) {
    GAUSSIAN("gaussian"),
    PROGRESSIVE("progressive");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceTopBarBlurStyle =
            entries.firstOrNull { it.persistedValue == value } ?: GAUSSIAN
    }
}

fun normalizeInterfaceScale(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE)
    } else {
        DEFAULT_INTERFACE_SCALE
    }
