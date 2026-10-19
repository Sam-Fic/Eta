// Adapted from OpenMinis/OpenMinis @ 4ef29002e88db1e20e462ec2ff46916e8a7dcb45.
// SPDX-License-Identifier: GPL-3.0-only
// Local/private integration; see third_party/openminis-browser/NOTICE.md.
package io.github.mangi.eta.agent.browser.ported.ui.browser

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
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

/** 浏览器面板占屏幕高度的比例（与上游 iOS 的 large detent 一致）。 */
private const val BrowserSheetHeightFraction = 0.9f

/** 与 [BrowserTabPool] 的最大标签数一致（面板里「+」的禁用条件）。 */
private const val MAX_TABS = 3

/** 标签按钮的内容内边距：左 12dp 给标题留呼吸，右 4dp 让关闭钮贴近胶囊边缘。 */
private val TabChipContentPadding = PaddingValues(start = 12.dp, end = 4.dp)

/** 标签关闭钮的触控尺寸（圆形涟漪）。 */
private val TabCloseTouchSize = 32.dp

/** 标签关闭钮的图标尺寸。 */
private val TabCloseIconSize = 16.dp

/**
 * 浏览器各子面板共用的页头：官方 [TopAppBar]（左侧关闭 + 右侧动作槽）。
 *
 * 容器色与 [ModalBottomSheet] 的 `surfaceContainerLow` 一致，顶栏与页面不出缝。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserSheetTopBar(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
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
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.om_standard_sheet_close),
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
 * 浏览器面板（M3 Expressive 重设计版）。
 *
 * 全部使用官方标准组件：官方 [ModalBottomSheet] 作外壳、[TopAppBar] 作页头、
 * [Button] / [FilledTonalButton] 组成的官方相连按钮组作标签栏、[OutlinedTextField] 作地址栏、
 * [IconButton]（项目 M3E 套件 [EtaIconButton]）作底部工具栏。
 * 颜色一律走 `MaterialTheme.colorScheme` 角色，不写死色值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSheet(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
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

    // Mirror iOS onAppear: ensure at least one tab exists when the sheet opens.
    // Callers should also call `ensureTabForUI()` before flipping the sheet
    // visible so the first composition sees a non-empty tab list; this is a
    // defensive fallback.
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) tabPool.ensureTabForUI()
    }

    // Prevent the bottom sheet from swallowing WebView scroll gestures.
    // Returning the full delta as "consumed" on pre-scroll keeps the sheet's
    // nested-scroll dispatcher from dragging the sheet down when the user
    // scrolls inside a webpage.
    val webViewScrollGuard = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
        }
    }

    val configuration = LocalConfiguration.current
    val sheetHeight = (configuration.screenHeightDp * BrowserSheetHeightFraction).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight),
        ) {
            BrowserSheetTopBar(
                title = pageTitle.ifEmpty { stringResource(R.string.om_browser_title) },
                onDismiss = onDismiss,
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
            // 标签栏：相连按钮组（与侧边抽屉「新建对话 / 管理聊天」同款按钮组）——
            // 每个标签自绘形状，选中时用 M3E Morph 在「连体形状」与「全圆角胶囊」之间渐变、
            // 按压时按官方 Button 的按压形状收内侧圆角，容器色同步 secondaryContainer → primary；
            // 行尾「+」是同一组里的末位官方 FilledTonalButton，满 MAX_TABS 时自动禁用。
            // 标签按 1f 权重等分整行，少量标签不再挤在左侧留白。
            val tabGroupCount = tabs.size + 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                tabs.forEachIndexed { index, tab ->
                    val title = tab.manager.pageTitle.collectAsState().value
                    val domain = tab.manager.currentURL.collectAsState().value
                        .let { url -> try { java.net.URI(url).host } catch (_: Exception) { null } }
                    BrowserTabChip(
                        label = title.ifEmpty { domain ?: "Tab ${tab.id}" },
                        index = index,
                        count = tabGroupCount,
                        selected = tab.id == selectedTabId,
                        enabled = !isAgentBusy,
                        onSelect = { tabPool.selectTab(tab.id) },
                        onClose = { scope.launch { tabPool.closeTabFromUI(tab.id) } },
                        modifier = Modifier.weight(1f),
                    )
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
                    .fillMaxWidth()
                    .nestedScroll(webViewScrollGuard),
            ) {
                if (selectedTab != null && currentURL.isNotEmpty()) {
                    // Only mount the WebView once there's something to display.
                    // Mounting an empty WebView (no URL loaded) corrupts the
                    // OpenGL swap behavior for the hosting ModalBottomSheet —
                    // sibling Compose UI (top bar, tab row, address bar) draws
                    // blank white on the first-open case until the WebView
                    // has real content. Keep it out of the hierarchy until
                    // the user navigates somewhere.
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

            // ── Download progress banner ──
            activeDownload?.let { dl ->
                BrowserDownloadBanner(
                    filename = dl.filename,
                    progress = dl.progress,
                )
            }

            // ── Bottom toolbar ──
            HorizontalDivider()
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

    if (showSettings) {
        BrowserSettingsSheet(
            tabPool = tabPool,
            onDismiss = { showSettings = false },
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
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BrowserTabChip(
    label: String,
    index: Int,
    count: Int,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val motionScheme = MaterialTheme.motionScheme
    val position = etaConnectedPosition(index, count)
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
                .heightIn(min = ButtonDefaults.MinHeight)
                .clip(
                    EtaMorphChipShape(
                        selectionProgress = selectionProgress,
                        pressProgress = pressProgress,
                        position = position,
                    ),
                )
                .background(containerColor)
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
 * 底部工具栏：官方 [IconButton]（项目 M3E 套件的 `S` 档令牌）。
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
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
