package io.github.mangi.eta.ui.components

import android.app.Application
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubAgentModelGestureTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: RecordingView
    private var originalTouch = true
    private val builtInHaptics = mutableListOf<HapticFeedbackType>()
    private var clicks = 0
    private var holds = 0

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        originalTouch = Prefs.isEnabled(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK)
        Prefs.putBoolean(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK, true)
        view = RecordingView(context)
        // Compose 1.13 ripple walks LocalView up to the nearest ViewGroup; attach a parent.
        android.widget.FrameLayout(context).addView(view)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompositionLocalProvider(LocalView provides view, LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        builtInHaptics += hapticFeedbackType
                    }
                }) {
                    ChatInputNonFocusableIconButton(
                        onClick = { clicks++ }, onLongClick = { holds++ }, contentDescription = "model",
                    ) { androidx.compose.material3.Text("model") }
                }
            }
        }
    }
    @After fun restorePreferences() {
        Prefs.putBoolean(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK, originalTouch)
    }
    @Test fun clickAndLongPressHaveSeparateCallbacksAndSingleHaptics() {
        compose.onNodeWithContentDescription("model").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(0, holds)
            assertEquals(listOf(HapticFeedbackConstants.CONTEXT_CLICK), view.feedback)
        }
        compose.onNodeWithContentDescription("model").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(1, holds)
            assertEquals(listOf(HapticFeedbackConstants.CONTEXT_CLICK, HapticFeedbackConstants.LONG_PRESS), view.feedback)
            assertEquals(emptyList<HapticFeedbackType>(), builtInHaptics)
        }
    }
    private class RecordingView(context: Context) : View(context) {
        val feedback = mutableListOf<Int>()
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            feedback += feedbackConstant
            return true
        }
    }
}
