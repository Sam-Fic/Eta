package io.github.mangi.eta.ui.screens.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.ported.ui.chat.StandardChatSheet
import io.github.mangi.eta.agent.browser.ported.ui.theme.OpenMinisBrowserTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.ported.browser.BrowserTabPool
import io.github.mangi.eta.agent.browser.ported.ui.browser.BrowserSheet
import kotlinx.coroutines.CancellationException

/** Full multi-tab browser UI; the pool is the same one used by browser_use. */
@Composable
internal fun AgentBrowserScreen(onDismiss: () -> Unit, conversationId: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = remember { Any() }
    var pool by remember { mutableStateOf<BrowserTabPool?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { AgentBrowserSession.releaseUserControl(owner) }
    }
    LaunchedEffect(context.applicationContext) {
        try {
            pool = AgentBrowserSession.acquireUserControl(context.applicationContext, owner, conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    // Only inherit light/dark selection; use upstream colors, shapes and typography.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    OpenMinisBrowserTheme(darkTheme = dark) {
        val ready = pool
        if (ready != null) {
            BrowserSheet(tabPool = ready, onDismiss = onDismiss)
        } else {
            StandardChatSheet(title = stringResource(R.string.om_browser_title), onDismiss = onDismiss) {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (failed) Text(stringResource(R.string.browser_initialization_failed))
                    else CircularProgressIndicator()
                }
            }
        }
    }
}
