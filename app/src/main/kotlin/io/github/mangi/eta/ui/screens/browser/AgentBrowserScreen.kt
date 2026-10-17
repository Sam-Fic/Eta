package io.github.mangi.eta.ui.screens.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.ui.components.ContainedMorphLoadingIndicator
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
    val ready = pool
    if (ready != null) {
        // 浏览器面板整体走应用自身的 M3 Expressive 主题（官方组件 + 主题令牌）。
        BrowserSheet(tabPool = ready, onDismiss = onDismiss, modifier = modifier)
    } else {
        BrowserLoadingSheet(failed = failed, onDismiss = onDismiss, modifier = modifier)
    }
}

/**
 * 面板未就绪时的占位：官方 [ModalBottomSheet] + 官方进度环 / 文案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserLoadingSheet(
    failed: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BrowserLoadingSheetHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (failed) {
                Text(
                    text = stringResource(R.string.browser_initialization_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            } else {
                // C 类块级加载：M3E ContainedLoadingIndicator 的同款 morph 动画
                // （与工作区/文件页空态一致），封装自带实验性 OptIn。
                ContainedMorphLoadingIndicator()
            }
        }
    }
}

/** 占位面板高度：只放一个进度环 / 一行文案，不需要占满屏幕。 */
private val BrowserLoadingSheetHeight = 240.dp
