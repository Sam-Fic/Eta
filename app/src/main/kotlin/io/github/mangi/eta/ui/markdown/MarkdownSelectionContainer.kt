package io.github.mangi.eta.ui.markdown

import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.ui.haptics.HapticSelectionContainer
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

// Outside the chat viewport retain the original fully accessible, selectable document.
internal val LocalMarkdownAccessibilityEnabled = compositionLocalOf { true }

@Composable
internal fun MarkdownAccessibilityProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    fun requiresFullDocument(): Boolean = manager == null || manager.isTouchExplorationEnabled ||
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
    var enabled by remember(manager) { mutableStateOf(requiresFullDocument()) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { enabled = requiresFullDocument() }
        val exploration = AccessibilityManager.TouchExplorationStateChangeListener { enabled = requiresFullDocument() }
        val services = AccessibilityManager.AccessibilityServicesStateChangeListener { enabled = requiresFullDocument() }
        manager?.addAccessibilityStateChangeListener(listener)
        manager?.addTouchExplorationStateChangeListener(exploration)
        manager?.addAccessibilityServicesStateChangeListener(context.mainExecutor, services)
        enabled = requiresFullDocument()
        onDispose {
            manager?.removeAccessibilityStateChangeListener(listener)
            manager?.removeTouchExplorationStateChangeListener(exploration)
            manager?.removeAccessibilityServicesStateChangeListener(services)
        }
    }
    CompositionLocalProvider(LocalMarkdownAccessibilityEnabled provides enabled, content = content)
}

/** Explicit selection admission: native selection never sees a partially placed document. */
@Composable
internal fun MarkdownSelectionContainer(
    eagerOnly: Boolean,
    selection: SelectionState = rememberSelectionState(),
    content: @Composable () -> Unit,
) {
    val accessibility = LocalMarkdownAccessibilityEnabled.current
    val viewport = LocalMarkdownDrawViewport.current
    val forced = eagerOnly || accessibility || viewport == null
    val admission = remember { MarkdownLayoutAdmission() }
    var selecting by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var pointerPressed by remember { mutableStateOf(false) }
    val preparingNow by rememberUpdatedState(preparing)
    val latestContent by rememberUpdatedState(content)
    // Moving the body between registrar scopes retains the parser/reveal/composition identities.
    val body = remember { movableContentOf { latestContent() } }

    LaunchedEffect(forced, selecting) {
        if (forced || selecting) {
            if (!admission.nativeSelection || preparing) {
                preparing = true
                admission.request(eager = true, nativeSelection = false)
                val revision = admission.revision
                snapshotFlow { admission.placedRevision }.first { it == revision }
                admission.request(eager = true, nativeSelection = true)
                val nativeRevision = admission.revision
                snapshotFlow { admission.placedRevision }.first { it == nativeRevision }
                snapshotFlow { pointerPressed }.first { !it }
                preparing = false
            }
            if (selecting && !forced) selection.selectAll()
        } else {
            // A live answer may finish while the user is holding a native selection.
            snapshotFlow { !pointerPressed && selection.selectedTexts.isEmpty() }.first { it }
            preparing = false
            admission.request(eager = false, nativeSelection = false)
        }
    }
    Column {
        if (!forced) {
            Text(
                text = if (selecting) {
                    if (!preparing) "返回浏览" else "正在准备全文选择…"
                } else "全文选择",
                modifier = Modifier.clickable {
                    if (selecting) selection.clear()
                    selecting = !selecting
                }.padding(vertical = 6.dp),
            )
        }
        CompositionLocalProvider(LocalMarkdownLayoutAdmission provides admission) {
            HapticSelectionContainer(selectionState = selection, modifier = Modifier
                .onPreviewKeyEvent { preparingNow }
                .then(if (preparing) Modifier.clearAndSetSemantics { } else Modifier)
                .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pointerPressed = true
                    var blocked = preparingNow
                    if (blocked) down.consume()
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            blocked = blocked || preparingNow
                            if (blocked) event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } finally { pointerPressed = false }
                }
            }) {
                if (admission.nativeSelection) body()
                else DisableSelection { body() }
            }
        }
    }
}
