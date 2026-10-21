package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

const val MIN_INTERFACE_SCALE = 0.8f
const val MAX_INTERFACE_SCALE = 1.1f
const val DEFAULT_INTERFACE_SCALE = 1f

@Serializable
data class AppearanceSettings(
    val themeMode: AppearanceThemeMode = AppearanceThemeMode.LIGHT,
    val monetEnabled: Boolean = true,
    val paletteStyle: AppearancePaletteStyle = AppearancePaletteStyle.NEUTRAL,
    val accentColor: AppearanceAccentColor = AppearanceAccentColor.SYSTEM,
    val pureBlackEnabled: Boolean = false,
    val blurEnabled: Boolean = true,
    val topBarBlurStyle: AppearanceTopBarBlurStyle = AppearanceTopBarBlurStyle.GAUSSIAN,
    val predictiveBackEnabled: Boolean = false,
    val interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
    val morphLoadingIndicator: Boolean = true,
    val morphLoadingBeforeResponseOnly: Boolean = false,
    val messageTimestampsEnabled: Boolean = false,
    /**
     * 本轮已经给出正文回答、且生成停下之后，把这一轮跑完的思考与工具步骤整组收进「已完成 N 个步骤」。
     *
     * 关掉它回到旧行为：只有全部步骤都已结束才收起，中途被重连/中断卡成「运行中」的那一组会一直摊开。
     */
    val foldWorkProcessOnAnswer: Boolean = true,
    val iconLightColor: Int = DEFAULT_ICON_LIGHT,
    val iconDarkColor: Int = DEFAULT_ICON_DARK,
) {
    fun normalized(): AppearanceSettings = copy(
        monetEnabled = true,
        interfaceScale = normalizeInterfaceScale(interfaceScale),
        iconLightColor = iconLightColor or 0xFF000000.toInt(),
        iconDarkColor = iconDarkColor or 0xFF000000.toInt(),
    )

    companion object {
        const val DEFAULT_ICON_LIGHT = 0xFFFFFFFF.toInt()
        const val DEFAULT_ICON_DARK = 0xFF1C1C1E.toInt()
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
