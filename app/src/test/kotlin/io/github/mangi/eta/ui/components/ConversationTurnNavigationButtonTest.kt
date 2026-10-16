package io.github.mangi.eta.ui.components

import android.app.Application
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationTurnNavigationButtonTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: RecordingView
    private var originalTouch = true
    private val builtInHaptics = mutableListOf<HapticFeedbackType>()
    private val direction = mutableStateOf(ConversationNavigationDirection.Down)
    private val steps = mutableListOf<ConversationNavigationDirection>()
    private val edges = mutableListOf<ConversationNavigationDirection>()

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        originalTouch = Prefs.isEnabled(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK)
        Prefs.putBoolean(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK, true)
        view = RecordingView(context)
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                CompositionLocalProvider(LocalView provides view, LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        builtInHaptics += hapticFeedbackType
                    }
                }) {
                    ConversationTurnNavigationButton(
                        direction = direction.value, visible = true,
                        onStep = { steps += direction.value }, onEdge = { edges += direction.value },
                    )
                }
            }
        }
    }
    @After fun restorePreferences() {
        Prefs.putBoolean(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK, originalTouch)
    }
    @Test fun downClickHasExactlyOneHapticAndOneStep() {
        button(R.string.chat_next_user_message).performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(ConversationNavigationDirection.Down), steps)
            assertEquals(emptyList<ConversationNavigationDirection>(), edges)
            assertEquals(listOf(HapticFeedbackConstants.CONTEXT_CLICK), view.feedback)
            assertEquals(emptyList<HapticFeedbackType>(), builtInHaptics)
        }
    }
    @Test fun downLongPressHasExactlyOneHapticAndNoClickOnRelease() {
        button(R.string.chat_next_user_message).performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(emptyList<ConversationNavigationDirection>(), steps)
            assertEquals(listOf(ConversationNavigationDirection.Down), edges)
            assertEquals(listOf(HapticFeedbackConstants.LONG_PRESS), view.feedback)
            assertEquals(emptyList<HapticFeedbackType>(), builtInHaptics)
        }
    }
    @Test fun upClickAndLongPressEachHaveOneHaptic() {
        compose.runOnIdle { direction.value = ConversationNavigationDirection.Up }
        button(R.string.chat_previous_user_message).performTouchInput { click() }
        button(R.string.chat_previous_user_message).performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(listOf(ConversationNavigationDirection.Up), steps)
            assertEquals(listOf(ConversationNavigationDirection.Up), edges)
            assertEquals(listOf(HapticFeedbackConstants.CONTEXT_CLICK, HapticFeedbackConstants.LONG_PRESS), view.feedback)
            assertEquals(emptyList<HapticFeedbackType>(), builtInHaptics)
        }
    }
    private fun button(label: Int) = compose.onNodeWithContentDescription(view.context.getString(label))
    private class RecordingView(context: Context) : View(context) {
        val feedback = mutableListOf<Int>()
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            feedback += feedbackConstant
            return true
        }
    }
}
