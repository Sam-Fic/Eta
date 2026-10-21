package io.github.mangi.eta.ui.screens.browser

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import io.github.mangi.eta.agent.browser.ported.browser.BrowserTabPool
import io.github.mangi.eta.agent.browser.ported.ui.browser.BrowserPage
import io.github.mangi.eta.agent.browser.ported.ui.browser.BrowserSheetTopBar
import io.github.mangi.eta.ui.components.ContainedMorphLoadingIndicator
import kotlinx.coroutines.CancellationException

/** 浏览器页面的日志标签：初始化失败时记录原始异常，便于定位闪退原因。 */
private const val TAG = "AgentBrowserScreen"

/** Full multi-tab browser page; the pool is the same one used by browser_use. */
@Composable
internal fun AgentBrowserScreen(onBack: () -> Unit, conversationId: String? = null, modifier: Modifier = Modifier) {
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
        } catch (t: Throwable) {
            // 兜底：WebView / 渲染器初始化失败在部分机型上抛的是 Error（OutOfMemoryError、
            // UnsatisfiedLinkError 之类），只接 Exception 会让异常逃出 effect 直接崩应用。
            // 这里统一兜住并展示失败页，同时把原始异常写进日志便于定位。
            // 注意：「点打开浏览器立刻闪退」的真正根因在标签栏的 Modifier.weight(0f)
            // （见 BrowserTabWeight.kt），这里是初始化失败时的降级兜底。
            Log.e(TAG, "browser initialization failed", t)
            failed = true
        }
    }
    val ready = pool
    if (ready != null) {
        // 浏览器页面整体走应用自身的 M3 Expressive 主题（官方组件 + 主题令牌）。
        BrowserPage(tabPool = ready, onBack = onBack, modifier = modifier)
    } else {
        BrowserLoadingPage(failed = failed, onBack = onBack, modifier = modifier)
    }
}

/**
 * 页面未就绪时的占位：与浏览器页同款页头 + 官方进度环 / 文案。
 */
@Composable
private fun BrowserLoadingPage(
    failed: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            BrowserSheetTopBar(
                title = stringResource(R.string.om_browser_title),
                onDismiss = onBack,
                navigationIcon = Icons.AutoMirrored.Rounded.ArrowBack,
                navigationContentDescription = stringResource(R.string.action_back),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
