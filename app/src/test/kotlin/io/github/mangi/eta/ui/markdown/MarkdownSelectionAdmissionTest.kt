package io.github.mangi.eta.ui.markdown

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.mangi.eta.ui.haptics.SelectionTestMagnifier
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h900dp-mdpi", shadows = [SelectionTestMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownSelectionAdmissionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accessibilityChangeDuringNativeRegistrationRestartsPreparation() {
        val accessibility = mutableStateOf(false)
        val switched = mutableStateOf(false)
        val viewport = MarkdownDrawViewport()
        val selection = SelectionState()
        val document = DocumentGfmParserSession().parse("First paragraph.\n\nLast paragraph.", true).document
        compose.setContent {
            MiuixTheme {
                SideEffect { viewport.updateEnabled(true) }
                CompositionLocalProvider(LocalMarkdownDrawViewport provides viewport,
                    LocalMarkdownAccessibilityEnabled provides accessibility.value) {
                    Box(Modifier.size(300.dp, 100.dp).onGloballyPositioned(viewport::updateCoordinates)) {
                        MarkdownSelectionContainer(false, selection) {
                            val admission = checkNotNull(LocalMarkdownLayoutAdmission.current)
                            val pendingNative = admission.nativeSelection && admission.revision > 0 &&
                                admission.placedRevision != admission.revision
                            SideEffect {
                                if (pendingNative && !switched.value) {
                                    switched.value = true
                                    accessibility.value = true
                                }
                            }
                            MarkdownContent(document, rememberMarkdownStyle(MarkdownTone.Answer),
                                Modifier.wrapContentHeight(Alignment.Top, unbounded = true))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("全文选择").performClick()
        compose.waitUntil(5_000) { switched.value }
        compose.waitForIdle()
        compose.runOnIdle { accessibility.value = false }
        compose.waitUntil(5_000) { selection.selectedTexts.size == 2 }
        compose.onNodeWithText("返回浏览").assertExists()
    }

    @Test fun explicitSelectionRestoresFullNativeTextAndKeepsBodyMounted() {
        val viewport = MarkdownDrawViewport()
        val selection = SelectionState()
        val accessibility = mutableStateOf(false)
        val paragraphs = (0..19).map { "Paragraph $it keeps every word." }
        val document = DocumentGfmParserSession().parse(paragraphs.joinToString("\n\n"), true).document
        var mounts = 0
        compose.setContent {
            MiuixTheme {
                SideEffect { viewport.updateEnabled(true) }
                CompositionLocalProvider(LocalMarkdownDrawViewport provides viewport,
                    LocalMarkdownAccessibilityEnabled provides accessibility.value) {
                    Box(Modifier.size(300.dp, 140.dp).onGloballyPositioned(viewport::updateCoordinates)) {
                        MarkdownSelectionContainer(eagerOnly = false, selection = selection) {
                            DisposableEffect(Unit) { mounts++; onDispose { mounts-- } }
                            MarkdownContent(document, rememberMarkdownStyle(MarkdownTone.Answer),
                                Modifier.wrapContentHeight(Alignment.Top, unbounded = true))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("全文选择").performClick()
        compose.waitUntil(5_000) { selection.selectedTexts.size == paragraphs.size }
        compose.runOnIdle {
            assertEquals(paragraphs.joinToString("\n"), selection.selectedTexts.joinToString("\n") { it.text })
            assertEquals(1, mounts)
        }
        compose.onNodeWithText("返回浏览").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(selection.selectedTexts.isEmpty())
            accessibility.value = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(paragraphs, selection.getSelectableTexts().map { it.text })
            assertEquals(1, mounts)
            selection.selectAll()
            accessibility.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("leaving accessibility must retain an active selection", paragraphs.size, selection.selectedTexts.size)
            selection.clear()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(selection.getSelectableTexts().isEmpty()) }
    }
}
