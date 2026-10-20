// Adapted from OpenMinis/OpenMinis @ 4ef29002e88db1e20e462ec2ff46916e8a7dcb45.
// SPDX-License-Identifier: GPL-3.0-only
// Local/private integration; see third_party/openminis-browser/NOTICE.md.
package io.github.mangi.eta.agent.browser.ported.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.ported.browser.BrowserHistoryStore
import io.github.mangi.eta.agent.browser.ported.browser.BrowserTabPool
import io.github.mangi.eta.agent.browser.ported.browser.UserAgentProfile
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.EtaMorphChipShape
import io.github.mangi.eta.ui.adaptive.etaConnectedButtonShapes
import io.github.mangi.eta.ui.adaptive.etaConnectedPosition
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.launch

/** 与 [BrowserTabPool] 的最大标签数一致（页面里「+」的禁用条件）。 */
private const val MAX_TABS = 3

/** 标签按钮的内容内边距：左 12dp 给标题留呼吸，右 4dp 让关闭钮贴近胶囊边缘。 */
private val TabChipContentPadding = PaddingValues(start = 12.dp, end = 4.dp)

/** 标签关闭钮的触控尺寸（圆形涟漪）。 */
private val TabCloseTouchSize = 32.dp

/** 标签关闭钮的图标尺寸。 */
private val TabCloseIconSize = 16.dp

/**
 * 标签过渡用的「收拢到底」份额（取极小正数而非 0）：`weight` 只在大于 0 时才参与
 * 「按权重比例分配剩余空间」，份额恰好为 0 的子项会被当成非权重子项、按内容自然宽度
 * （文案 + 关闭钮）占位，于是过渡的首帧 / 末帧会闪一下原始宽度。
 */
private const val CollapsedTabFraction = 0.001f

/**
 * 标签在标签栏里的宽度过渡：`Steady` 直接落位，`Entering` 从 0 权重展开（新增标签），
 * `Exiting` 从 1f 权重收拢到 0（关闭后留下的残影），三者互斥。
 */
private enum class TabChipTransition { Steady, Entering, Exiting }

/**
 * 正在退场的标签「残影」：标签关闭后池里已经没有这条记录（manager 也已 destroy），
 * 但标签栏还需要一个占位条目把「收拢到 0」的动画播完，因此保留关闭瞬间的
 * 文案 / 所处下标 / 选中态快照。
 */
private data class ExitingBrowserTab(
    val id: Int,
    val label: String,
    val index: Int,
    val selected: Boolean,
)

/**
 * 浏览器各子面板共用的页头：官方 [TopAppBar]（左侧关闭/返回 + 右侧动作槽）。
 *
 * 容器色与浏览器页面背景的 `surfaceContainerLow` 一致，顶栏与页面不出缝。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserSheetTopBar(
    title: String,
    onDismiss: () -> Unit,
    navigationIcon: ImageVector = Icons.Rounded.Close,
    navigationContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val resolvedNavigationContentDescription =
        navigationContentDescription ?: stringResource(R.string.om_standard_sheet_close)
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = navigationIcon,
                    contentDescription = resolvedNavigationContentDescription,
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )
}

/** 空状态：官方空态排版（居中占位图标 + 标题 + 可选说明），与页内搜索页一致。 */
@Composable
internal fun BrowserSheetEmptyState(
    title: String,
    summary: String? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Public,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 浏览器页面（M3 Expressive 重设计版）。
 *
 * 独立全屏页面：官方 [Scaffold] 作骨架、[TopAppBar] 作页头、
 * [Button] / [FilledTonalButton] 组成的官方相连按钮组作标签栏、[OutlinedTextField] 作地址栏、
 * 官方 M3E [HorizontalFloatingToolbar]（内容为项目 M3E 套件 [EtaIconButton]）作底部工具栏。
 * 颜色一律走 `MaterialTheme.colorScheme` 角色，不写死色值。
 */
@Composable
fun BrowserPage(
    tabPool: BrowserTabPool,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs by tabPool.tabs.collectAsState()
    val selectedTabId by tabPool.selectedTabId.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val selectedTab = tabs.find { it.id == selectedTabId }
    val currentURL = selectedTab?.manager?.currentURL?.collectAsState()?.value ?: ""
    val pageTitle = selectedTab?.manager?.pageTitle?.collectAsState()?.value ?: ""
    val isLoading = selectedTab?.manager?.isLoading?.collectAsState()?.value ?: false
    val canGoBack = selectedTab?.manager?.canGoBack?.collectAsState()?.value ?: false
    val canGoForward = selectedTab?.manager?.canGoForward?.collectAsState()?.value ?: false
    val isAgentBusy = tabPool.isAgentBusy
    val userAgentProfile = tabPool.currentUserAgentProfile.collectAsState().value

    var urlInput by remember(currentURL) { mutableStateOf(currentURL) }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // [T-android-browser-download-ux] Downloads panel + badge state.
    var showDownloads by remember { mutableStateOf(false) }
    val downloadEntries by tabPool.downloads.collectAsState()
    val activeDownload by tabPool.activeDownload.collectAsState()

    // Mirror iOS onAppear: ensure at least one tab exists when the page opens.
    // Callers should also call `ensureTabForUI()` before navigating so the
    // first composition sees a non-empty tab list; this is a defensive fallback.
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) tabPool.ensureTabForUI()
    }

    // 新增标签的展开动画需要知道「谁是这一次新增的」：首帧就存在的标签直接落位，
    // 之后多出来的标签才播放入场（1 个标签 → 2 个标签时，新标签从 0 宽度展开、
    // 已有标签按相连按钮组的权重比例同步收窄）。
    val knownTabIds = remember { mutableStateOf(tabs.map { it.id }.toSet()) }
    LaunchedEffect(tabs.map { it.id }) {
        knownTabIds.value = tabs.map { it.id }.toSet()
    }

    // 关闭标签的收拢动画需要一个「残影」条目（见 [ExitingBrowserTab]）：关闭动作会同步
    // 通知标签池并把标签从 `tabs` 里摘掉，残影在同一帧接替它的位置、按同样的权重起播，
    // 因此宽度不会跳变；动画播完由 `onExitFinished` 摘除。
    val exitingTabs = remember { mutableStateListOf<ExitingBrowserTab>() }

    BackHandler(enabled = showSettings) { showSettings = false }
    Box(modifier = modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            BrowserSheetTopBar(
                title = pageTitle.ifEmpty { stringResource(R.string.om_browser_title) },
                onDismiss = onBack,
                navigationIcon = Icons.AutoMirrored.Rounded.ArrowBack,
                navigationContentDescription = stringResource(R.string.action_back),
                actions = {
                    IconButton(onClick = { showHistory = true }, enabled = !isAgentBusy) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = stringResource(R.string.om_browser_history_action),
                        )
                    }
                    // UA-profile icon doubles as the entry point to settings.
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = when (userAgentProfile) {
                                UserAgentProfile.MOBILE_CHROME -> Icons.Rounded.PhoneAndroid
                                UserAgentProfile.DESKTOP_CHROME -> Icons.Rounded.Computer
                                UserAgentProfile.CUSTOM -> Icons.Rounded.Edit
                            },
                            contentDescription = stringResource(R.string.om_browser_settings_title),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // ── Download progress banner ──
                activeDownload?.let { dl ->
                    BrowserDownloadBanner(
                        filename = dl.filename,
                        progress = dl.progress,
                    )
                }

                // ── Bottom toolbar（官方 M3E 浮动工具栏）──
                BrowserNavToolbar(
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    isLoading = isLoading,
                    isAgentBusy = isAgentBusy,
                    // [T-android-browser-download-ux] Downloads entry: shows while
                    // the session has ANY download records, disappears when the
                    // user clears the last one; the badge counts in-flight +
                    // unviewed terminal entries and hides at 0 (iOS v2 spec).
                    showDownloads = downloadEntries.isNotEmpty(),
                    downloadBadgeCount = tabPool.downloadBadgeCount(downloadEntries),
                    onBack = { selectedTab?.manager?.goBack() },
                    onForward = { selectedTab?.manager?.goForward() },
                    onReload = { selectedTab?.manager?.reload() },
                    onStop = { selectedTab?.manager?.stopLoading() },
                    onShowDownloads = { showDownloads = true },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 标签栏：相连按钮组（与侧边抽屉「新建对话 / 管理聊天」同款按钮组）——
            // 每个标签自绘形状，选中时用 M3E Morph 在「连体形状」与「全圆角胶囊」之间渐变、
            // 按压时按官方 Button 的按压形状收内侧圆角，容器色同步 secondaryContainer → primary；
            // 行尾「+」是同一组里的末位官方 FilledTonalButton，满 MAX_TABS 时自动禁用。
            // 标签按 1f 权重等分整行，少量标签不再挤在左侧留白；新增标签从 0 权重展开、
            // 关闭后的残影从 1f 权重收拢，展开/收拢期间同一行其它标签按权重比例被同步挤压
            // （权重动画即等分宽度动画）。标签与残影全部退净后仍留一份同权重占位，
            // 所以行尾「+」自始至终停在原位、不会弹回左侧。
            val tabIds = tabs.mapTo(mutableSetOf()) { it.id }
            // 同 id 的真实标签还在 `tabs` 里时先不渲染残影，避免短时间内出现两个同样的标签。
            val visibleExitingTabs = exitingTabs.filter { it.id !in tabIds }
            val tabGroupCount = tabs.size + visibleExitingTabs.size + 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                // 相连按钮组里的位次：残影与真实标签共用一个计数，插回原位置后各段形状不变。
                var chipIndex = 0
                tabs.forEachIndexed { index, tab ->
                    // 残影插回它关闭时所处的位置（排在当前该下标的标签之前）。
                    // 以标签 id 作为组合身份：中途关闭标签时下标会整体前移，
                    // 没有 key 的话过渡进度会被错位复用。
                    visibleExitingTabs.forEach { ghost ->
                        if (ghost.index == index) {
                            key(ghost.id) {
                                ExitingTabChip(
                                    ghost = ghost,
                                    index = chipIndex++,
                                    count = tabGroupCount,
                                    enabled = !isAgentBusy,
                                    onExited = { exitingTabs.remove(ghost) },
                                )
                            }
                        }
                    }
                    key(tab.id) {
                        val title = tab.manager.pageTitle.collectAsState().value
                        val domain = tab.manager.currentURL.collectAsState().value
                            .let { url -> try { java.net.URI(url).host } catch (_: Exception) { null } }
                        val label = title.ifEmpty { domain ?: "Tab ${tab.id}" }
                        BrowserTabChip(
                            label = label,
                            index = chipIndex++,
                            count = tabGroupCount,
                            selected = tab.id == selectedTabId,
                            enabled = !isAgentBusy,
                            transition = if (tab.id in knownTabIds.value) {
                                TabChipTransition.Steady
                            } else {
                                TabChipTransition.Entering
                            },
                            onSelect = { tabPool.selectTab(tab.id) },
                            onClose = {
                                // 关闭瞬间把标签的样子记成残影，供退场动画继续渲染。
                                if (exitingTabs.none { it.id == tab.id }) {
                                    exitingTabs += ExitingBrowserTab(
                                        id = tab.id,
                                        label = label,
                                        index = index,
                                        selected = tab.id == selectedTabId,
                                    )
                                }
                                scope.launch { tabPool.closeTabFromUI(tab.id) }
                            },
                        )
                    }
                }
                // 关闭时位于末位的残影（下标已越界）统一排在所有标签之后、「+」之前。
                visibleExitingTabs.forEach { ghost ->
                    if (ghost.index >= tabs.size) {
                        key(ghost.id) {
                            ExitingTabChip(
                                ghost = ghost,
                                index = chipIndex++,
                                count = tabGroupCount,
                                enabled = !isAgentBusy,
                                onExited = { exitingTabs.remove(ghost) },
                            )
                        }
                    }
                }
                // 标签与残影都退净后保留一份等分权重占位：行尾「+」停在原位、不弹回左侧。
                if (tabGroupCount == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
                BrowserNewTabButton(
                    index = tabGroupCount - 1,
                    count = tabGroupCount,
                    enabled = tabs.size < MAX_TABS && !isAgentBusy,
                    onClick = { scope.launch { tabPool.newTabFromUI() } },
                )
            }

            // 地址栏：官方 OutlinedTextField（占位符 / 前导图标 / 加载时尾部停止钮）。
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                enabled = !isAgentBusy,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text(text = stringResource(R.string.om_browser_search_placeholder))
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Language,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon = if (isLoading) {
                    {
                        IconButton(onClick = { selectedTab?.manager?.stopLoading() }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.om_browser_stop),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val trimmed = urlInput.trim()
                    if (trimmed.isNotEmpty()) {
                        val normalized = trimmed
                        selectedTab?.manager?.loadURL(normalized)
                        urlInput = normalized
                    }
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }),
            )

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }

            // ── WebView + overlays ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (selectedTab != null && currentURL.isNotEmpty()) {
                    // Only mount the WebView once there's something to display.
                    // Mounting an empty WebView (no URL loaded) can corrupt the
                    // OpenGL swap behavior for the hosting surface — sibling
                    // Compose UI (top bar, tab row, address bar) draws blank
                    // white on the first-open case until the WebView has real
                    // content. Keep it out of the hierarchy until the user
                    // navigates somewhere.
                    BrowserWebView(
                        webView = selectedTab.manager.webView,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    BrowserSheetEmptyState(
                        title = if (selectedTab == null) {
                            stringResource(R.string.om_browser_no_tab_open)
                        } else {
                            stringResource(R.string.om_browser_enter_url_hint)
                        },
                    )
                }

                if (isAgentBusy) {
                    AgentBrowsingOverlay(
                        onTakeover = {
                            io.github.mangi.eta.agent.browser.AgentBrowserSession.interruptAgentAction()
                        },
                    )
                }
            }
        }
    }

        if (showSettings) {
            BrowserSettingsPage(
                tabPool = tabPool,
                onDismiss = { showSettings = false },
            )
        }
    }

    if (showDownloads) {
        BrowserDownloadsSheet(
            tabPool = tabPool,
            onDismiss = { showDownloads = false },
        )
    }

    if (showHistory) {
        BrowserHistorySheet(
            historyStore = BrowserHistoryStore.getInstance(context),
            // [T-android-browser-history-no-tab] C1: with no tabs open,
            // selectedTab is null and the old safe-call silently dropped the
            // navigation. Create a tab first (same path as the "+" button);
            // if the pool refuses (MAX_TABS), just dismiss the sheet.
            onNavigate = { url ->
                scope.launch {
                    val tab = selectedTab ?: tabPool.newTabFromUI()
                    tab?.manager?.loadURL(url)
                    showHistory = false
                }
            },
            onDismiss = { showHistory = false },
        )
    }

}

/**
 * 标签栏里的单个标签：自绘 Morph 形状（未选中连体形状 ↔ 选中胶囊 ↔ 按压形状）的文字标签。
 *
 * 形状几何由 [EtaMorphChipShape] 提供（外侧全圆角、内侧取官方相连按钮组的 8dp）；
 * 选中态用 `MaterialTheme.motionScheme.defaultSpatialSpec` 在两端形状之间渐变（M3E Morph 形状动效），
 * 按压时用 `fastSpatialSpec` 收内侧圆角到官方按压档 4dp（即官方 Button 的按压形状）。
 * 容器 / 内容色按官方档位同步渐变（未选中 secondaryContainer → 选中 primary，禁用取官方禁用档位）。
 * 内容排版、关闭钮与触觉反馈与改造前一致。
 *
 * 宽度过渡（[transition]）：新增标签从 0 权重展开到 1f、关闭后的残影从 1f 收拢到 0，
 * 同一行其余标签由相连按钮组的权重比例自动推导出新宽度，因此「1 个标签 → 2 个标签」
 * 与「2 个标签 → 1 个标签」都是一次连贯的收窄 / 展开，不会瞬移；展开途中内容随进度淡入，
 * 容器色由形状裁剪自然铺开，收拢途中内容随进度淡出。
 *
 * 例外是「这一行只剩它自己」（[count] == 2，即整个标签栏只有这一个标签）：权重按比例
 * 分配剩余空间，独占时无论权重多小都会占满整行，展开 / 收拢都动不了，所以额外补一份
 * 互补权重的占位来承接腾出的宽度（见下方 `Spacer`）——这样从 0 个标签加出第一个标签时
 * 它是从 0 展开的，关掉最后一个标签时也能真的收拢到 0，且行尾「+」全程停在原位。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.BrowserTabChip(
    label: String,
    index: Int,
    count: Int,
    selected: Boolean,
    enabled: Boolean,
    transition: TabChipTransition,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    /** [TabChipTransition.Exiting] 收拢动画播完时回调：由上层摘除退场残影。 */
    onExitFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val motionScheme = MaterialTheme.motionScheme
    val position = etaConnectedPosition(index, count)
    // 宽度份额：展开 = 0 → 1f、收拢 = 1f → 0、稳态直接落位（1f）。权重即相连按钮组里的
    // 「等分份额」，对它做插值 ⇒ 本标签变化的同一行其余标签被等比挤压 / 撑开，
    // 整体是一次连贯的宽度重排（M3E 默认空间规格）。
    // 方向只取首帧：入场标签的 `transition` 会在上层把「已知标签」归位后变成 Steady，
    // 若拿 `transition` 当 key，那次归位会把进行中的动画取消掉、标签卡在半宽。
    val transitionDirection = remember { transition }
    val revealFraction = remember {
        Animatable(
            if (transitionDirection == TabChipTransition.Entering) CollapsedTabFraction else 1f,
        )
    }
    LaunchedEffect(Unit) {
        when (transitionDirection) {
            TabChipTransition.Steady -> Unit
            TabChipTransition.Entering -> revealFraction.animateTo(1f, motionScheme.defaultSpatialSpec<Float>())
            TabChipTransition.Exiting -> {
                revealFraction.animateTo(CollapsedTabFraction, motionScheme.defaultSpatialSpec<Float>())
                onExitFinished()
            }
        }
    }
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motionScheme.defaultSpatialSpec<Float>(),
        label = "BrowserTabSelection",
    )
    // 未选中 = 官方 FilledTonalButton 档（secondaryContainer）、选中 = 官方 Button 档（primary），
    // 禁用档位两者相同，故禁用时不再区分选中态。
    val tonalColors = ButtonDefaults.filledTonalButtonColors()
    val filledColors = ButtonDefaults.buttonColors()
    val containerColor by animateColorAsState(
        targetValue = when {
            !enabled -> tonalColors.disabledContainerColor
            selected -> filledColors.containerColor
            else -> tonalColors.containerColor
        },
        animationSpec = motionScheme.defaultEffectsSpec<Color>(),
        label = "BrowserTabContainerColor",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> tonalColors.disabledContentColor
            selected -> filledColors.contentColor
            else -> tonalColors.contentColor
        },
        animationSpec = motionScheme.defaultEffectsSpec<Color>(),
        label = "BrowserTabContentColor",
    )
    val interactionSource = remember { MutableInteractionSource() }
    // 按压沿用官方 Button 的按压形状（内侧圆角收到 4dp），用官方「快速」空间规格跟手。
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressProgress by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0f,
        animationSpec = motionScheme.fastSpatialSpec<Float>(),
        label = "BrowserTabPress",
    )
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier = modifier
                // 宽度份额：过渡进度即相连按钮组里的等分份额，动画期间整行宽度按比例重排。
                .weight(revealFraction.value)
                .heightIn(min = ButtonDefaults.MinHeight)
                .clip(
                    EtaMorphChipShape(
                        selectionProgress = selectionProgress,
                        pressProgress = pressProgress,
                        position = position,
                    ),
                )
                .background(containerColor)
                // 文字 / 关闭钮随过渡淡入淡出；容器色由外层 `background` 先画，不受这层透明度影响，
                // 因此标签是「底先铺开、内容再显现」／「内容先隐去、底再收拢」。
                .graphicsLayer { alpha = revealFraction.value }
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    enabled = enabled,
                    role = Role.Tab,
                    onClick = {
                        TouchHaptics.click(view)
                        onSelect()
                    },
                )
                .padding(TabChipContentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 关闭钮：32dp 圆形触控 + 官方 16dp 叉号，颜色随上方 contentColor
            // （primary / secondaryContainer 上各自的对照色），不用再单独指定色值。
            if (enabled) {
                Box(
                    modifier = Modifier
                        .size(TabCloseTouchSize)
                        .clip(CircleShape)
                        .clickable {
                            TouchHaptics.click(view)
                            onClose()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.om_browser_close_tab),
                        modifier = Modifier.size(TabCloseIconSize),
                    )
                }
            }
        }
    }

    // 整行只剩这一个标签时（[count] == 2）补一份互补权重的占位：`weight` 按权重比例分配
    // 剩余空间，独占时无论权重多小都会占满整行——展开 / 收拢都动不了。补上它（本标签份额
    // 与占位份额之和恒为 1f）后，这一个标签也能真的从 0 展开、收拢到 0，且行尾「+」全程
    // 停在原位。稳态时占位权重为 0，只是让过渡前后这一行的子项数一致（少一份间隙的跳变）。
    if (count == 2) {
        Spacer(Modifier.weight(1f - revealFraction.value))
    }
}

/**
 * 退场残影：标签关闭后接替它位置的占位标签，外观沿用关闭瞬间的快照（文案 / 位次 / 选中态 / 启用档位），
 * 与普通标签同形同色，只是权重从 1f 收拢到 0、内容随进度淡出，收拢完由 `onExited` 摘除。
 *
 * 两个回调留空：残影存在期间标签已经不在池里，点它不应触发选中或再次关闭。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.ExitingTabChip(
    ghost: ExitingBrowserTab,
    index: Int,
    count: Int,
    enabled: Boolean,
    onExited: () -> Unit,
) {
    BrowserTabChip(
        label = ghost.label,
        index = index,
        count = count,
        selected = ghost.selected,
        enabled = enabled,
        transition = TabChipTransition.Exiting,
        onSelect = {},
        onClose = {},
        onExitFinished = onExited,
    )
}

/**
 * 标签组末位的「新建标签」按钮：与标签同一相连按钮组、同高（[ButtonDefaults.MinHeight]）的方形按钮，
 * 满 [MAX_TABS] 时自动禁用。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BrowserNewTabButton(
    index: Int,
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    FilledTonalButton(
        onClick = {
            TouchHaptics.click(view)
            onClick()
        },
        shapes = etaConnectedButtonShapes(etaConnectedPosition(index, count)),
        modifier = Modifier.size(ButtonDefaults.MinHeight),
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = stringResource(R.string.om_browser_new_tab),
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
    }
}

/** 下载进度条：官方 [Surface] 容器 + 官方 [LinearProgressIndicator]。 */
@Composable
private fun BrowserDownloadBanner(
    filename: String,
    progress: Float,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = filename,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress >= 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp),
                )
            }
        }
    }
}

/**
 * 底部工具栏：官方 M3E [HorizontalFloatingToolbar]（药丸容器 + 官方容器色 / 海拔令牌），
 * 取代贴边的裸按钮行与其上方分隔线；`expanded = true` 常驻展开，内容为居中的图标按钮组，
 * 工具栏整体浮在页面底部（左右 12dp、下方 8dp + 系统导航条留白）。
 *
 * 下载入口用官方 [BadgedBox] 承载角标；有下载记录时才出现。
 */
@Composable
private fun BrowserNavToolbar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    isLoading: Boolean,
    isAgentBusy: Boolean,
    showDownloads: Boolean,
    downloadBadgeCount: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onShowDownloads: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            // 官方 minimumInteractiveBalancedPadding 只在两侧都没有 leading/trailing 内容时才生效：
            // 它会把内容里的 MinimumInteractive*AlignmentLine（含位置偏移的大值）当成补偿内边距，
            // 把整条工具栏撑高。本行内容布局完全显式（固定间距，整体居中），显式传空的 leadingContent
            // 让这组补偿归零（官方参数语义，非 hack）。
            leadingContent = {},
        ) {
            // 动作组：固定 8dp 间距的一行图标按钮（EtaIconButton 的 `Standard` 档裸图标）。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EtaIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.om_browser_nav_back),
                    onClick = {
                        TouchHaptics.click(view)
                        onBack()
                    },
                    enabled = canGoBack && !isAgentBusy,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                EtaIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = stringResource(R.string.om_browser_nav_forward),
                    onClick = {
                        TouchHaptics.click(view)
                        onForward()
                    },
                    enabled = canGoForward && !isAgentBusy,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                if (showDownloads) {
                    BadgedBox(
                        badge = {
                            if (downloadBadgeCount > 0) {
                                Badge { Text(text = "$downloadBadgeCount") }
                            }
                        },
                    ) {
                        EtaIconButton(
                            icon = Icons.Rounded.Download,
                            contentDescription = stringResource(R.string.om_browser_downloads_title),
                            onClick = {
                                TouchHaptics.click(view)
                                onShowDownloads()
                            },
                            size = EtaIconSize.S,
                            style = EtaButtonStyle.Standard,
                        )
                    }
                }
                if (isLoading) {
                    EtaIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.om_browser_stop),
                        onClick = {
                            TouchHaptics.click(view)
                            onStop()
                        },
                        enabled = !isAgentBusy,
                        size = EtaIconSize.S,
                        style = EtaButtonStyle.Standard,
                    )
                } else {
                    EtaIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.om_browser_reload),
                        onClick = {
                            TouchHaptics.click(view)
                            onReload()
                        },
                        enabled = !isAgentBusy,
                        size = EtaIconSize.S,
                        style = EtaButtonStyle.Standard,
                    )
                }
            }
        }
    }
}

/** 呼吸灯遮罩：代理正在操作浏览器时盖在 WebView 上，颜色全部走官方反色角色。 */
@Composable
private fun AgentBrowsingOverlay(onTakeover: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val breathingAlpha = transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "breathingAlpha",
    )
    // Read the pulsing State inside the layer rather than destructuring it here. Reading
    // the value in composition would recompose the whole overlay — dim layer, label, and
    // the takeover button — on every frame of a 1.5s loop, for one 8dp dot.

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim)
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = CircleShape,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = breathingAlpha.value }
                        .background(MaterialTheme.colorScheme.inversePrimary, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.om_browser_minis_browsing),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(14.dp)
                        .background(
                            MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f),
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.om_browser_takeover),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inversePrimary,
                    modifier = Modifier.clickable(onClick = onTakeover),
                )
            }
        }
    }
}
