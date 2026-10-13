package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.config.InteractiveModePreference
import io.github.mangi.eta.config.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopBarOverflowMenuInteractiveModeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var previousMode: AgentTaskSurfaceMode
    private lateinit var preferences: SharedPreferences
    private var previousInteractiveValue: Any? = null
    private var autoCompressChanges = 0
    private var terminalOpens = 0

    @Before fun seedBackgroundSetting() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        preferences = requireNotNull(Prefs.localAgentPreferences())
        previousMode = AgentTaskSurface.stored()
        previousInteractiveValue = preferences.all[InteractiveModePreference.PREF_KEY]
        preferences.edit().remove(InteractiveModePreference.PREF_KEY).commit()
        AgentTaskSurface.save(AgentTaskSurfaceMode.BACKGROUND)
    }

    @After fun restoreSetting() {
        AgentTaskSurface.save(previousMode)
        val key = InteractiveModePreference.PREF_KEY
        val editor = preferences.edit()
        when (val value = previousInteractiveValue) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
        editor.commit()
    }

    @Test fun backgroundSettingIsNotOverwrittenByCompositionOrOpeningTheMenu() {
        render()
        assertStored(AgentTaskSurfaceMode.BACKGROUND)
        openMenu()
        row().assertIsDisplayed().assertHasClickAction()
        switch().assertIsDisplayed().assertHasClickAction().assertIsOff()
        compose.onNodeWithText(label(R.string.action_interactive_mode)).assertIsDisplayed()
        assertStored(AgentTaskSurfaceMode.BACKGROUND)
        compose.runOnIdle { assertFalse(preferences.contains(InteractiveModePreference.PREF_KEY)) }
    }

    @Test fun askLocationDoesNotEnableOrInitializeTheIndependentSwitch() {
        AgentTaskSurface.save(AgentTaskSurfaceMode.ASK)
        render()
        openMenu()
        switch().assertIsOff()
        assertStored(AgentTaskSurfaceMode.ASK)
        compose.runOnIdle { assertFalse(preferences.contains(InteractiveModePreference.PREF_KEY)) }
    }

    @Test fun persistedIndependentTrueIsUsedOnFirstCompositionWithoutChangingForeground() {
        AgentTaskSurface.save(AgentTaskSurfaceMode.FOREGROUND)
        preferences.edit().putBoolean(InteractiveModePreference.PREF_KEY, true).commit()
        render()
        openMenu()
        switch().assertIsOn()
        assertEnabled(true)
        assertStored(AgentTaskSurfaceMode.FOREGROUND)
    }

    @Test fun rowAndSwitchToggleIndependentlyOfForegroundWithoutDismissing() =
        exerciseRealTouches(AgentTaskSurfaceMode.FOREGROUND)

    @Test fun rowAndSwitchToggleIndependentlyOfBackgroundWithoutDismissing() =
        exerciseRealTouches(AgentTaskSurfaceMode.BACKGROUND)

    @Test fun rowAndSwitchToggleIndependentlyOfAskWithoutDismissing() =
        exerciseRealTouches(AgentTaskSurfaceMode.ASK)

    private fun exerciseRealTouches(mode: AgentTaskSurfaceMode) {
        AgentTaskSurface.save(mode)
        render()
        openMenu()
        switch().assertIsOff()
        // Hit the row away from its independently clickable trailing switch.
        row().performTouchInput { click(Offset(width * 0.35f, center.y)) }
        assertStored(mode)
        assertEnabled(true)
        switch().assertIsOn()
        assertMenuStillOpen()

        switch().performTouchInput { click() }
        assertStored(mode)
        assertEnabled(false)
        switch().assertIsOff()
        assertMenuStillOpen()

        // Repeat touches after the exit lifetime, including each control in both directions.
        switch().performTouchInput { click() }
        assertStored(mode)
        assertEnabled(true)
        switch().assertIsOn()
        assertMenuStillOpen()
        row().performTouchInput { click(Offset(width * 0.35f, center.y)) }
        assertStored(mode)
        assertEnabled(false)
        switch().assertIsOff()
        assertMenuStillOpen()
        compose.runOnIdle {
            assertEquals(0, autoCompressChanges)
            assertEquals(0, terminalOpens)
        }
    }

    @Test fun settingsLocationChangesNeverChangeTheSwitchButIndependentWritesRefreshIt() {
        render()
        // A settings-page write before the popup is mounted must not turn the switch on.
        compose.runOnIdle { AgentTaskSurface.save(AgentTaskSurfaceMode.ASK) }
        openMenu()
        switch().assertIsOff()
        assertStored(AgentTaskSurfaceMode.ASK)
        AgentTaskSurfaceMode.entries.forEach { mode ->
            compose.runOnIdle { AgentTaskSurface.save(mode) }
            switch().assertIsOff()
            assertEnabled(false)
            assertStored(mode)
            assertMenuStillOpen()
        }

        // The independently observed key, not the settings location, changes the UI.
        compose.runOnIdle { preferences.edit().putBoolean(InteractiveModePreference.PREF_KEY, true).commit() }
        switch().assertIsOn()
        assertEnabled(true)
        AgentTaskSurfaceMode.entries.forEach { mode ->
            compose.runOnIdle { AgentTaskSurface.save(mode) }
            switch().assertIsOn()
            assertEnabled(true)
            assertStored(mode)
            assertMenuStillOpen()
        }
        compose.runOnIdle { preferences.edit().putBoolean(InteractiveModePreference.PREF_KEY, false).commit() }
        switch().assertIsOff()
        assertEnabled(false)
        assertStored(AgentTaskSurfaceMode.BACKGROUND)
        assertMenuStillOpen()
    }

    private fun render() {
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.align(Alignment.TopEnd)) {
                            TopBarOverflowMenu(
                                onOpenTerminal = { terminalOpens++ },
                                onLaunchKimiWeb = {},
                                kimiWebLabel = "Kimi Web",
                                canStopKimiWeb = false,
                                onStopKimiWeb = {},
                                onRefreshKimiWeb = {},
                                onOpenBrowser = {},
                                onOpenWorkspace = {},
                                autoCompressEnabled = false,
                                onToggleAutoCompress = { autoCompressChanges++ },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(label(R.string.action_more))
            .performTouchInput { click() }
        row().assertIsDisplayed()
    }

    private fun assertMenuStillOpen() {
        // Longer than the menu's 160 ms retained exit lifetime.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        row().assertIsDisplayed()
        switch().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.action_search_history)).assertIsDisplayed()
    }

    private fun assertStored(mode: AgentTaskSurfaceMode) {
        compose.runOnIdle { assertEquals(mode, AgentTaskSurface.stored()) }
    }

    private fun assertEnabled(enabled: Boolean) {
        compose.runOnIdle {
            assertEquals(enabled, InteractiveModePreference().enabled)
            assertEquals(enabled, InteractiveModePreference.read(preferences))
        }
    }

    private fun row() = compose.onNodeWithTag("top-bar-interactive-mode-row")
    private fun switch() = compose.onNodeWithTag("top-bar-interactive-mode-switch", useUnmergedTree = true)
    private fun label(id: Int) = RuntimeEnvironment.getApplication().getString(id)
}
