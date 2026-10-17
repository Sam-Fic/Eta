package io.github.mangi.eta.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.haptics.ApplyTouchHapticFeedbackEnabled
import io.github.mangi.eta.ui.haptics.ProvideEtaHapticFeedback

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentAppTheme(
    appearance: AppearanceSettings,
    applyInterfaceScale: Boolean,
    onResolvedDarkModeChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (appearance.themeMode) {
        AppearanceThemeMode.SYSTEM -> systemDark
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    val colorScheme = rememberEtaColorScheme(
        accentColor = appearance.accentColor,
        paletteStyle = appearance.paletteStyle,
        isDark = isDark,
        pureBlackEnabled = appearance.pureBlackEnabled,
    )

    LaunchedEffect(isDark) { onResolvedDarkModeChange(isDark) }

    // 官方 Compose Material 3 Expressive 主题：Expressive 动效 + 官方形状/排版。
    // 颜色来自「外观与主题」的强调色与配色风格（Spec2025 取色链）。
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
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
        CompositionLocalProvider(
            LocalAppearanceSettings provides appearance,
            LocalBlurEnabled provides appearance.blurEnabled,
            LocalTopBarBlurStyle provides appearance.topBarBlurStyle,
            LocalPlatformDensity provides platformDensity,
            LocalDensity provides appDensity,
            // Material3 的 Icon/Text 默认取 LocalContentColor，需显式提供，否则既不跟随主题，
            // 深色模式下还会退回 Compose 的默认黑色。
            LocalContentColor provides colorScheme.onSurface,
        ) {
            ApplyTouchHapticFeedbackEnabled()
            ProvideEtaHapticFeedback {
                content()
            }
        }
    }
}
