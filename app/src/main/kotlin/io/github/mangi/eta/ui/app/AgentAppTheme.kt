package io.github.mangi.eta.ui.app

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeFamily
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.haptics.ApplyTouchHapticFeedbackEnabled
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.platformDynamicColors

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentAppTheme(
    appearance: AppearanceSettings,
    applyInterfaceScale: Boolean,
    onResolvedDarkModeChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val isDark = when (appearance.themeMode) {
        AppearanceThemeMode.SYSTEM -> systemDark
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    val colorSchemeMode = when (appearance.themeMode) {
        AppearanceThemeMode.LIGHT -> ColorSchemeMode.MonetLight
        AppearanceThemeMode.DARK -> ColorSchemeMode.MonetDark
        AppearanceThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
    }
    val keyColor = if (appearance.accentColor == AppearanceAccentColor.SYSTEM) {
        platformDynamicColors(isDark).primary
    } else {
        appearance.accentColor.seedColor()
    }
    val controller = remember(
        colorSchemeMode,
        keyColor.value,
        appearance.paletteStyle,
        isDark,
    ) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = keyColor,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = appearance.paletteStyle.toMiuixPaletteStyle(),
            isDark = isDark,
        )
    }
    val colors = controller.currentColors()
    val themedColors = remember(colors, isDark, appearance.pureBlackEnabled) {
        if (appearance.pureBlackEnabled && isDark) {
            colors.copy(
                background = Color.Black,
                surface = Color.Black,
            )
        } else {
            colors
        }
    }

    LaunchedEffect(isDark) { onResolvedDarkModeChange(isDark) }

    MiuixTheme(colors = themedColors) {
        val platformDensity = LocalDensity.current
        val appDensity = remember(platformDensity, appearance.interfaceScale, applyInterfaceScale) {
            if (applyInterfaceScale) {
                Density(
                    density = platformDensity.density * appearance.interfaceScale,
                    fontScale = platformDensity.fontScale,
                )
            } else {
                platformDensity
            }
        }
        val miuixColors = MiuixTheme.colorScheme
        val materialColors = if (isDark) {
            darkColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onSurfaceSecondary,
                error = miuixColors.error,
                onError = miuixColors.onError,
                errorContainer = miuixColors.errorContainer,
                onErrorContainer = miuixColors.onErrorContainer,
                outline = miuixColors.outline,
                outlineVariant = miuixColors.outline,
                surfaceTint = miuixColors.primary,
                surfaceContainer = miuixColors.surfaceContainer,
                surfaceContainerHigh = miuixColors.surfaceContainerHigh,
                surfaceContainerHighest = miuixColors.surfaceContainerHigh,
                surfaceContainerLow = miuixColors.surface,
                surfaceContainerLowest = miuixColors.surface,
                // KernelSU 式三层色阶：页面背景 = surfaceContainer，分段条目 = surfaceBright。
                surfaceBright = miuixColors.surfaceContainerHighest,
                surfaceDim = miuixColors.surface,
            )
        } else {
            lightColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onSurfaceSecondary,
                error = miuixColors.error,
                onError = miuixColors.onError,
                errorContainer = miuixColors.errorContainer,
                onErrorContainer = miuixColors.onErrorContainer,
                outline = miuixColors.outline,
                outlineVariant = miuixColors.outline,
                surfaceTint = miuixColors.primary,
                surfaceContainer = miuixColors.surfaceContainer,
                surfaceContainerHigh = miuixColors.surfaceContainerHigh,
                surfaceContainerHighest = miuixColors.surfaceContainerHigh,
                surfaceContainerLow = miuixColors.surface,
                surfaceContainerLowest = miuixColors.surface,
                // KernelSU 式三层色阶：页面背景 = surfaceContainer，分段条目 = surfaceBright。
                surfaceBright = miuixColors.surfaceContainerHighest,
                surfaceDim = miuixColors.surface,
            )
        }

        // M3 Expressive：只用官方系统动态取色（minSdk 34 ≥ API 31，恒可用），不再保留写死的备用配色。
        val expressiveColors = remember(context, isDark, appearance.pureBlackEnabled) {
            val base = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (isDark && appearance.pureBlackEnabled) {
                base.copy(background = Color.Black, surface = Color.Black)
            } else {
                base
            }
        }

        CompositionLocalProvider(
            LocalAppearanceSettings provides appearance,
            LocalThemeFamily provides appearance.themeFamily,
            // Material 3 模式使用不透明的标准表面，不启用毛玻璃顶栏。
            LocalBlurEnabled provides (
                appearance.blurEnabled &&
                    appearance.themeFamily != AppearanceThemeFamily.MATERIAL3_EXPRESSIVE
                ),
            LocalTopBarBlurStyle provides appearance.topBarBlurStyle,
            LocalPlatformDensity provides platformDensity,
            LocalDensity provides appDensity,
            // Material3 的 Icon/Text 默认取 LocalContentColor，而 MiuixTheme 不提供它，
            // 会让这些组件退回 Compose 的默认黑色、深色模式下不跟随主题。
            // 这里用当前主题族的前景色兜底；外层容器（按钮、菜单项等）仍会提供自己的 contentColor。
            LocalContentColor provides if (
                appearance.themeFamily == AppearanceThemeFamily.MATERIAL3_EXPRESSIVE
            ) {
                expressiveColors.onSurface
            } else {
                miuixColors.onSurface
            },
        ) {
            ApplyTouchHapticFeedbackEnabled()
            if (appearance.themeFamily == AppearanceThemeFamily.MATERIAL3_EXPRESSIVE) {
                // 官方 Compose Material 3 Expressive 主题：Expressive 动效 + 官方形状/排版。
                // 颜色使用官方系统动态取色。
                // motionScheme 取官方 MaterialExpressiveTheme 默认的 MotionScheme.expressive()，
                // shapes 不覆盖，直接沿用官方形状体系（4/8/12/16/28 + increased 20/32/48）。
                MaterialExpressiveTheme(
                    colorScheme = expressiveColors,
                    motionScheme = MotionScheme.expressive(),
                ) {
                    content()
                }
            } else {
                // 默认 MIUI X：由 MiuixTheme 提供组件外观。
                // 此处的 MaterialTheme 仅向 markdown-renderer-m3 提供与 Miuix 一致的颜色上下文。
                MaterialTheme(
                    colorScheme = materialColors,
                ) {
                    content()
                }
            }
        }
    }
}

private fun AppearancePaletteStyle.toMiuixPaletteStyle(): ThemePaletteStyle = when (this) {
    AppearancePaletteStyle.TONAL_SPOT -> ThemePaletteStyle.TonalSpot
    AppearancePaletteStyle.NEUTRAL -> ThemePaletteStyle.Neutral
    AppearancePaletteStyle.VIBRANT -> ThemePaletteStyle.Vibrant
    AppearancePaletteStyle.EXPRESSIVE -> ThemePaletteStyle.Expressive
    AppearancePaletteStyle.RAINBOW -> ThemePaletteStyle.Rainbow
    AppearancePaletteStyle.FRUIT_SALAD -> ThemePaletteStyle.FruitSalad
    AppearancePaletteStyle.MONOCHROME -> ThemePaletteStyle.Monochrome
    AppearancePaletteStyle.FIDELITY -> ThemePaletteStyle.Fidelity
    AppearancePaletteStyle.CONTENT -> ThemePaletteStyle.Content
}

private fun AppearanceAccentColor.seedColor(): Color = when (this) {
    AppearanceAccentColor.SYSTEM, AppearanceAccentColor.BLUE -> Color(0xFF3482FF)
    AppearanceAccentColor.PURPLE -> Color(0xFF6750A4)
    AppearanceAccentColor.PINK -> Color(0xFFB0006D)
    AppearanceAccentColor.RED -> Color(0xFFBA1A1A)
    AppearanceAccentColor.ORANGE -> Color(0xFFB65D00)
    AppearanceAccentColor.YELLOW -> Color(0xFF7D5700)
    AppearanceAccentColor.GREEN -> Color(0xFF006D3B)
    AppearanceAccentColor.TEAL -> Color(0xFF006A6A)
}
