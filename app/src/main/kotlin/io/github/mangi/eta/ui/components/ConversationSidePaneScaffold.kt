package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import io.github.mangi.eta.ui.components.streamDiagnosticDraw
import io.github.mangi.eta.ui.components.streamDiagnosticMeasure
import io.github.mangi.eta.ui.components.streamDiagnosticPlacement
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.searchResults
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.unit.DpOffset
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val DrawerMenuItemModifier = Modifier.height(40.dp)
private val DrawerMenuItemPadding = PaddingValues(horizontal = 12.dp)

private object DrawerMetrics {
    val PaneMaxWidth = 320.dp
    val PaneWidthFraction = 0.78f
    val DrawerCornerRadius = 24.dp
    const val OpenDurationMs = 220
    const val CloseDurationMs = 200
    val PaneHorizontalPadding = 16.dp
    val TopInset = 8.dp
    val AfterSearch = 12.dp
    val SearchIconSize = 18.dp
    val SearchCornerRadius = 12.dp
    val SearchVerticalPadding = 10.dp
    val ChipGap = 8.dp
    val AfterChips = 10.dp
    val ListBottomPadding = 12.dp
    val BottomInset = 10.dp
    val ActionIconSize = 20.dp
    val CircleButtonPadding = 10.dp
    val SectionTopPadding = 10.dp
    val SectionBottomPadding = 6.dp
    val RowMinHeight = 44.dp
    val RowGap = 2.dp
    val RowCornerRadius = 16.dp
    val RowHorizontalPadding = 14.dp
    val RowVerticalPadding = 10.dp
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 8.dp
    val EmptyVerticalPadding = 28.dp
    val DockTopGap = 8.dp
    val AssistantBarHeight = 48.dp
}

@Composable
fun ConversationSidePaneScaffold(
    state: ConversationPaneUiState,
    visible: Boolean,
    backHandlerEnabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onConversationExport: (ConversationSummaryUi) -> Unit = {},
    onNewConversation: () -> Unit,
    onOpenManageChats: () -> Unit = {},
    onSelectFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sceneLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val drawerState = rememberDrawerState(
        initialValue = if (visible) DrawerValue.Open else DrawerValue.Closed,
    )
    remember(drawerState) {
        drawerState.applyEtaDrawerMotion()
        true
    }
    val drawerScope = rememberCoroutineScope()
    val currentVisible by rememberUpdatedState(visible)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var drawerBlocksIme by remember { mutableStateOf(visible) }

    fun launchAfterDrawerClosed(action: () -> Unit) {
        drawerScope.launch {
            if (drawerState.targetValue != DrawerValue.Closed ||
                drawerState.currentValue != DrawerValue.Closed
            ) {
                drawerState.snapTo(DrawerValue.Closed)
            }
            action()
        }
    }

    LaunchedEffect(visible) {
        val target = if (visible) DrawerValue.Open else DrawerValue.Closed
        if (drawerState.targetValue == target) return@LaunchedEffect
        if (!visible && drawerState.currentValue == DrawerValue.Open) {
            // 从底部按钮切页：抽屉是完全打开的，瞬时合上再让新页入场。
            // 手势收回时 currentValue 会先变成 Closed，这里不会走到。
            drawerState.snapTo(target)
            return@LaunchedEffect
        }
        @Suppress("DEPRECATION")
        drawerState.animateTo(
            targetValue = target,
            anim = if (visible) DrawerOpenMotion else DrawerCloseMotion,
        )
    }

    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue to drawerState.isAnimationRunning }
            .collectLatest { (value, running) ->
                // 快滑时 currentValue 会在 settle 开始就切换。立刻改 visible 会让
                // 聊天页在惯性动画中间整页重组，看起来就是顿一下。
                if (running) return@collectLatest
                if (value == DrawerValue.Open) {
                    if (!currentVisible) currentOnOpen()
                } else if (currentVisible) {
                    currentOnDismiss()
                }
            }
    }

    LaunchedEffect(visible) {
        drawerBlocksIme = visible
    }

    LaunchedEffect(drawerState, visible, focusManager, keyboard, view) {
        snapshotFlow {
            visible ||
                drawerState.targetValue == DrawerValue.Open ||
                drawerState.currentValue == DrawerValue.Open
        }.collect { shouldHide ->
            if (shouldHide) hideChatInputIme(focusManager, keyboard, view)
        }
    }

    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = visible &&
            backHandlerEnabled &&
            sceneLifecycleState == Lifecycle.State.RESUMED,
        onBackCompleted = { currentOnDismiss() },
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Material 3 Expressive 的侧边栏是全屏态：铺满宽度、直角；MIUI X 保持原来的抽屉宽度与圆角。
        val material3Drawer = isMaterial3Theme()
        val paneWidth = if (material3Drawer) {
            maxWidth
        } else {
            minOf(maxWidth * DrawerMetrics.PaneWidthFraction, DrawerMetrics.PaneMaxWidth)
        }
        val drawerShape = AbsoluteRoundedCornerShape(
            topLeft = 0.dp,
            topRight = if (material3Drawer) 0.dp else DrawerMetrics.DrawerCornerRadius,
            bottomRight = if (material3Drawer) 0.dp else DrawerMetrics.DrawerCornerRadius,
            bottomLeft = 0.dp,
        )
        CompositionLocalProvider(LocalConversationDrawerBlocksIme provides drawerBlocksIme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = backHandlerEnabled,
            // M3 用官方遮罩令牌；miuix 主题族沿用原来的压暗色。
            scrimColor = if (material3Drawer) {
                DrawerDefaults.scrimColor
            } else {
                MiuixTheme.colorScheme.windowDimming
            },
            modifier = Modifier.fillMaxSize(),
            drawerContent = {
                ConversationPanePanel(
                    state = state,
                    width = paneWidth,
                    drawerShape = drawerShape,
                    enableSearchFocus = visible &&
                        drawerState.currentValue == DrawerValue.Open &&
                        !drawerState.isAnimationRunning,
                    onSearchChange = onSearchChange,
                    onConversationSelected = onConversationSelected,
                    onConversationRename = onConversationRename,
                    onConversationDelete = onConversationDelete,
                    onMoveConversationToFolder = onMoveConversationToFolder,
                    onNewConversation = onNewConversation,
                    onConversationTogglePin = onConversationTogglePin,
                    onConversationExport = onConversationExport,
                    onOpenManageChats = { launchAfterDrawerClosed(onOpenManageChats) },
                    onSelectFolder = onSelectFolder,
                    onCreateFolder = onCreateFolder,
                    onRenameFolder = onRenameFolder,
                    onDeleteFolder = onDeleteFolder,
                    selectedAssistantId = selectedAssistantId,
                    onSelectAssistant = onSelectAssistant,
                    onEditAssistant = { id -> launchAfterDrawerClosed { onEditAssistant(id) } },
                    onOpenAssistants = { launchAfterDrawerClosed(onOpenAssistants) },
                    onOpenSettings = { launchAfterDrawerClosed(onOpenSettings) },
                    onOpenModelProviders = { launchAfterDrawerClosed(onOpenModelProviders) },
                    onOpenUsageStats = { launchAfterDrawerClosed(onOpenUsageStats) },
                    onOpenSkills = { launchAfterDrawerClosed(onOpenSkills) },
                    onOpenPermissions = { launchAfterDrawerClosed(onOpenPermissions) },
                    modifier = Modifier.fillMaxHeight(),
                )
            },
        ) {
            content()
        }
        }
    }
}

@Composable
private fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    drawerShape: AbsoluteRoundedCornerShape,
    enableSearchFocus: Boolean,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onConversationExport: (ConversationSummaryUi) -> Unit = {},
    onNewConversation: () -> Unit,
    onOpenManageChats: () -> Unit = {},
    onSelectFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = state.searchQuery.trim()
    val visibleConversations = remember(state.conversations, query) {
        state.searchResults()
    }
    val groups = remember(visibleConversations) { visibleConversations.groupForDrawer() }
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId.ifBlank { repositoryActiveId }
    val activeAssistant = remember(profiles, activeId) {
        profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }
    if (isMaterial3Theme()) {
        Material3ConversationPane(
            state = state,
            width = width,
            drawerShape = drawerShape,
            onSearchChange = onSearchChange,
            onConversationSelected = onConversationSelected,
            onConversationRename = onConversationRename,
            onConversationDelete = onConversationDelete,
            onMoveConversationToFolder = onMoveConversationToFolder,
            onConversationTogglePin = onConversationTogglePin,
            onConversationExport = onConversationExport,
            onNewConversation = onNewConversation,
            onOpenManageChats = onOpenManageChats,
            onSelectFolder = onSelectFolder,
            onCreateFolder = onCreateFolder,
            onRenameFolder = onRenameFolder,
            onDeleteFolder = onDeleteFolder,
            onOpenSettings = onOpenSettings,
            onOpenModelProviders = onOpenModelProviders,
            onOpenUsageStats = onOpenUsageStats,
            onOpenSkills = onOpenSkills,
            onOpenPermissions = onOpenPermissions,
            modifier = modifier,
        )
        return
    }
    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .graphicsLayer {
                clip = true
                shape = drawerShape
            },
        shape = drawerShape,
        color = MiuixTheme.colorScheme.surface,
        contentColor = MiuixTheme.colorScheme.onSurface,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                // 搜索框在顶部，键盘不必把底部 dock 和会话输入一起顶上来。
                .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
                .consumeWindowInsets(WindowInsets.ime)
                .padding(horizontal = DrawerMetrics.PaneHorizontalPadding),
        ) {
            Spacer(modifier = Modifier.height(DrawerMetrics.TopInset))
            PaneSearchRow(
                query = state.searchQuery,
                onSearchChange = onSearchChange,
                enableSearchFocus = enableSearchFocus,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.AfterSearch))
            PaneDrawerActions(
                onNewConversation = onNewConversation,
                onOpenManageChats = onOpenManageChats,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.AfterSearch))
            PaneFolderBar(
                folders = state.folders,
                selectedFolderId = state.selectedFolderId,
                onSelectFolder = onSelectFolder,
                onCreateFolder = onCreateFolder,
                onRenameFolder = onRenameFolder,
                onDeleteFolder = onDeleteFolder,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.AfterChips))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .streamDiagnosticMeasure("drawer.lazy.measure")
                    .streamDiagnosticPlacement("drawer.lazy.place")
                    .streamDiagnosticDraw("drawer.lazy.draw")
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(bottom = DrawerMetrics.ListBottomPadding),
                verticalArrangement = Arrangement.spacedBy(DrawerMetrics.RowGap),
                overscrollEffect = null,
            ) {
                if (visibleConversations.isEmpty()) {
                    item(contentType = "drawer-empty") {
                        EmptyConversations(isSearching = query.isNotBlank())
                    }
                } else {
                    groups.forEach { group ->
                        item(key = group.key, contentType = "drawer-section") {
                            ConversationSectionHeader(group = group)
                        }
                        items(
                            items = group.items,
                            key = { it.id },
                            contentType = { "drawer-conversation" },
                        ) { conversation ->
                            ConversationTextRow(
                                conversation = conversation,
                                selected = conversation.id == state.selectedConversationId,
                                onClick = { onConversationSelected(conversation.id) },
                                onRename = { onConversationRename(conversation) },
                                onDelete = { onConversationDelete(conversation) },
                                onMoveToFolder = { onMoveConversationToFolder(conversation) },
                                onTogglePin = { onConversationTogglePin(conversation) },
                                onExport = { onConversationExport(conversation) },
                            )
                        }
                    }
                }
            }
            PaneAssistantBar(
                name = activeAssistant?.name?.ifBlank { stringResource(R.string.app_name) }
                    ?: stringResource(R.string.app_name),
                onClick = onOpenAssistants,
                avatar = {
                    AssistantAvatar(
                        assistant = activeAssistant,
                        size = 32.dp,
                    )
                },
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.DockTopGap))
            PaneDock(
                onOpenPermissions = onOpenPermissions,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenUsageStats = onOpenUsageStats,
                onOpenSkills = onOpenSkills,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.BottomInset))
        }
    }

}

@Composable
private fun PaneSearchRow(
    query: String,
    onSearchChange: (String) -> Unit,
    enableSearchFocus: Boolean,
) {
    val textStyle = MiuixTheme.textStyles.body2.merge(
        TextStyle(
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
        ),
    )
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var searchActive by remember { mutableStateOf(false) }
    LaunchedEffect(enableSearchFocus) {
        if (!enableSearchFocus) {
            searchActive = false
            focusManager.clearFocus(force = true)
        }
    }
    LaunchedEffect(searchActive, enableSearchFocus) {
        if (searchActive && enableSearchFocus) {
            showChatInputIme(searchFocusRequester, keyboard, view)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DrawerMetrics.SearchCornerRadius))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(
                enabled = enableSearchFocus && !searchActive,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                searchActive = true
            }
            .padding(horizontal = 12.dp, vertical = DrawerMetrics.SearchVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(DrawerMetrics.SearchIconSize),
            tint = MiuixTheme.colorScheme.onSurface,
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.conversation_search_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onSearchChange,
                enabled = enableSearchFocus && searchActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester)
                    .focusProperties { canFocus = enableSearchFocus && searchActive },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun PaneDrawerActions(
    onNewConversation: () -> Unit,
    onOpenManageChats: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DrawerMetrics.ChipGap),
    ) {
        DrawerActionRow(
            label = stringResource(R.string.drawer_new_conversation),
            icon = Icons.AutoMirrored.Rounded.Chat,
            onClick = onNewConversation,
        )
        DrawerActionRow(
            label = stringResource(R.string.drawer_manage_chats),
            icon = Icons.Rounded.History,
            onClick = onOpenManageChats,
        )
    }
}

@Composable
private fun DrawerActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DrawerMetrics.SearchCornerRadius))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(onClick) {
                detectTapGestures(onTap = {
                    TouchHaptics.click(view)
                    onClick()
                })
            }
            .padding(horizontal = 12.dp, vertical = DrawerMetrics.SearchVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PaneFolderBar(
    folders: List<ConversationFolderUi>,
    selectedFolderId: String?,
    onSelectFolder: (String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    var createFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<ConversationFolderUi?>(null) }
    var folderToDelete by remember { mutableStateOf<ConversationFolderUi?>(null) }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DrawerMetrics.ChipGap),
    ) {
        item(key = "unfiled") {
            DrawerChip(
                label = stringResource(R.string.drawer_chats_chip),
                selected = selectedFolderId == null,
                icon = null,
                onClick = { onSelectFolder(null) },
            )
        }
        items(folders, key = { it.id }) { folder ->
            var showMenu by remember(folder.id) { mutableStateOf(false) }
            Box {
                DrawerChip(
                    label = folder.name,
                    selected = selectedFolderId == folder.id,
                    icon = Icons.Rounded.Folder,
                    onClick = { onSelectFolder(folder.id) },
                    onLongClick = { showMenu = true },
                )
                EtaDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    offset = DpOffset(x = 0.dp, y = 8.dp),
                ) {
                    DropdownMenuItem(
                        modifier = DrawerMenuItemModifier,
                        contentPadding = DrawerMenuItemPadding,
                        text = { Text(stringResource(R.string.action_rename)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                            )
                        },
                        onClick = {
                            showMenu = false
                            folderToRename = folder
                        },
                    )
                    DropdownMenuItem(
                        modifier = DrawerMenuItemModifier,
                        contentPadding = DrawerMenuItemPadding,
                        text = {
                            Text(
                                text = stringResource(R.string.action_delete),
                                color = MiuixTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                                tint = MiuixTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            folderToDelete = folder
                        },
                    )
                }
            }
        }
        item(key = "new-folder") {
            DrawerChip(
                label = stringResource(R.string.drawer_new_folder),
                selected = false,
                icon = Icons.Rounded.CreateNewFolder,
                onClick = { createFolder = true },
            )
        }
    }

    if (createFolder) {
        FolderNameDialog(
            title = stringResource(R.string.drawer_create_folder_title),
            initialName = "",
            onDismiss = { createFolder = false },
            onConfirm = { name ->
                onCreateFolder(name)
                createFolder = false
            },
        )
    }
    folderToRename?.let { folder ->
        FolderNameDialog(
            title = stringResource(R.string.drawer_rename_folder_title),
            initialName = folder.name,
            onDismiss = { folderToRename = null },
            onConfirm = { name ->
                onRenameFolder(folder.id, name)
                folderToRename = null
            },
        )
    }
    folderToDelete?.let { folder ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.drawer_delete_folder_title),
            summary = stringResource(R.string.drawer_delete_folder_message),
            onDismissRequest = { folderToDelete = null },
        ) {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { folderToDelete = null },
                onConfirm = {
                    onDeleteFolder(folder.id)
                    folderToDelete = null
                },
            )
        }
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    EtaDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.drawer_folder_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            EtaDialogActions(
                confirmText = stringResource(R.string.action_save),
                confirmEnabled = name.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = { onConfirm(name) },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * Material 3 Expressive 会话抽屉：搜索栏、相连按钮组、日期分组标题、会话列表、底部工具组。
 */
@Composable
private fun Material3ConversationPane(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    drawerShape: AbsoluteRoundedCornerShape,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onNewConversation: () -> Unit,
    onOpenManageChats: () -> Unit,
    onSelectFolder: (String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = state.searchQuery.trim()
    val visibleConversations = remember(state.conversations, query) { state.searchResults() }
    val groups = remember(visibleConversations) { visibleConversations.groupForDrawer() }
    val folders = state.folders
    var createFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<ConversationFolderUi?>(null) }
    var folderToDelete by remember { mutableStateOf<ConversationFolderUi?>(null) }

    M3Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .graphicsLayer {
                clip = true
                shape = drawerShape
            },
        shape = drawerShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
                .consumeWindowInsets(WindowInsets.ime)
                .padding(horizontal = DrawerMetrics.PaneHorizontalPadding),
        ) {
            Spacer(modifier = Modifier.height(DrawerMetrics.TopInset))
            // 抽屉打开时不抢焦点：只有用户主动点进搜索框才聚焦，避免一开抽屉就弹出输入法。
            // 抽屉内容每次打开都会重新进入组合，这里的 effect 会在打开时清掉任何自动落下的焦点。
            val drawerFocusManager = LocalFocusManager.current
            LaunchedEffect(Unit) {
                drawerFocusManager.clearFocus(force = true)
            }
            Material3PaneSearchField(
                query = state.searchQuery,
                onSearchChange = onSearchChange,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 第一排（自带一行）：新建对话 / 管理聊天
            val actionCount = 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                // 官方相连按钮组间距：ButtonGroupDefaults.ConnectedSpaceBetween = 2dp
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                Material3DrawerChip(
                    text = stringResource(R.string.drawer_new_conversation),
                    position = drawerGroupPosition(0, actionCount),
                    selected = false,
                    leadingIcon = Icons.Rounded.AddComment,
                    onClick = onNewConversation,
                    modifier = Modifier.weight(1f),
                )
                Material3DrawerChip(
                    text = stringResource(R.string.drawer_manage_chats),
                    position = drawerGroupPosition(1, actionCount),
                    selected = false,
                    leadingIcon = Icons.AutoMirrored.Rounded.ManageSearch,
                    onClick = onOpenManageChats,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 第二排（可横向滚动）：聊天 / [文件夹…] / 新建文件夹
            val chipCount = 1 + folders.size + 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                // 官方相连按钮组间距：ButtonGroupDefaults.ConnectedSpaceBetween = 2dp
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                var chipIndex = 0
                Material3DrawerChip(
                    text = stringResource(R.string.drawer_chats_chip),
                    position = drawerGroupPosition(chipIndex++, chipCount),
                    selected = state.selectedFolderId == null,
                    leadingIcon = null,
                    onClick = { onSelectFolder(null) },
                )
                folders.forEach { folder ->
                    var showMenu by remember(folder.id) { mutableStateOf(false) }
                    Box {
                        Material3DrawerFolderChip(
                            text = folder.name,
                            position = drawerGroupPosition(chipIndex++, chipCount),
                            selected = state.selectedFolderId == folder.id,
                            onClick = { onSelectFolder(folder.id) },
                            onLongClick = { showMenu = true },
                        )
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DrawerActionMenuItem(
                                index = 0,
                                count = 2,
                                text = stringResource(R.string.action_rename),
                                icon = Icons.Rounded.Edit,
                                onClick = {
                                    showMenu = false
                                    folderToRename = folder
                                },
                            )
                            DrawerActionMenuItem(
                                index = 1,
                                count = 2,
                                text = stringResource(R.string.action_delete),
                                icon = Icons.Rounded.Delete,
                                onClick = {
                                    showMenu = false
                                    folderToDelete = folder
                                },
                            )
                        }
                    }
                }
                Material3DrawerChip(
                    text = stringResource(R.string.drawer_new_folder),
                    position = drawerGroupPosition(chipIndex, chipCount),
                    selected = false,
                    leadingIcon = Icons.Rounded.CreateNewFolder,
                    onClick = { createFolder = true },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .overScrollVertical(),
                contentPadding = PaddingValues(bottom = DrawerMetrics.ListBottomPadding),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                overscrollEffect = null,
            ) {
                if (visibleConversations.isEmpty()) {
                    item(contentType = "drawer-empty") {
                        Material3EmptyConversations(isSearching = query.isNotBlank())
                    }
                } else {
                    groups.forEach { group ->
                        item(key = group.key, contentType = "drawer-section") {
                            Material3DrawerSectionHeader(
                                label = group.localizedLabel(),
                                count = group.items.size,
                            )
                        }
                        items(
                            items = group.items,
                            key = { it.id },
                            contentType = { "drawer-conversation" },
                        ) { conversation ->
                            Material3ConversationRow(
                                conversation = conversation,
                                selected = conversation.id == state.selectedConversationId,
                                onClick = { onConversationSelected(conversation.id) },
                                onRename = { onConversationRename(conversation) },
                                onDelete = { onConversationDelete(conversation) },
                                onMoveToFolder = { onMoveConversationToFolder(conversation) },
                                onTogglePin = { onConversationTogglePin(conversation) },
                                onExport = { onConversationExport(conversation) },
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // 底部：四个工具按钮（官方相连图标按钮组）+ 设置
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                Material3ConnectedIconButton(
                    icon = Icons.Rounded.HealthAndSafety,
                    contentDescription = stringResource(R.string.ui_permission_health_3048bb),
                    onClick = onOpenPermissions,
                    position = DrawerGroupPosition.Leading,
                )
                Material3ConnectedIconButton(
                    icon = Icons.Rounded.Memory,
                    contentDescription = stringResource(R.string.conversation_dock_models),
                    onClick = onOpenModelProviders,
                    position = DrawerGroupPosition.Middle,
                )
                Material3ConnectedIconButton(
                    icon = Icons.Rounded.Extension,
                    contentDescription = stringResource(R.string.route_skills),
                    onClick = onOpenSkills,
                    position = DrawerGroupPosition.Middle,
                )
                Material3ConnectedIconButton(
                    icon = Icons.Rounded.BarChart,
                    contentDescription = stringResource(R.string.stats_page_title),
                    onClick = onOpenUsageStats,
                    position = DrawerGroupPosition.Trailing,
                )
                Spacer(modifier = Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.route_settings),
                        modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                    )
                }
            }
            Spacer(modifier = Modifier.height(DrawerMetrics.BottomInset))
        }
    }

    if (createFolder) {
        FolderNameDialog(
            title = stringResource(R.string.drawer_create_folder_title),
            initialName = "",
            onDismiss = { createFolder = false },
            onConfirm = { name ->
                onCreateFolder(name)
                createFolder = false
            },
        )
    }
    folderToRename?.let { folder ->
        FolderNameDialog(
            title = stringResource(R.string.drawer_rename_folder_title),
            initialName = folder.name,
            onDismiss = { folderToRename = null },
            onConfirm = { name ->
                onRenameFolder(folder.id, name)
                folderToRename = null
            },
        )
    }
    folderToDelete?.let { folder ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.drawer_delete_folder_title),
            summary = stringResource(R.string.drawer_delete_folder_message),
            onDismissRequest = { folderToDelete = null },
        ) {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { folderToDelete = null },
                onConfirm = {
                    onDeleteFolder(folder.id)
                    folderToDelete = null
                },
            )
        }
    }
}

/**
 * M3 抽屉：会话搜索框。
 *
 * 官方 `TextField` + 官方 Expressive 圆角令牌（`TextFieldDefaults.roundedShape`），
 * 容器色、文字色、行高、内容内边距全部使用官方默认值，不自绘容器。
 * 清除按钮用官方 `IconButton`（原实现只画了一个不可点的关闭图标）。
 */
@Composable
private fun Material3PaneSearchField(
    query: String,
    onSearchChange: (String) -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onSearchChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { M3Text(text = stringResource(R.string.conversation_search_hint)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onSearchChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                    )
                }
            }
        },
        singleLine = true,
        shape = TextFieldDefaults.roundedShape,
    )
}

/** M3 抽屉：相连按钮组里的位置，决定官方相连形状哪一侧保持完全圆角。 */
private enum class DrawerGroupPosition { Single, Leading, Middle, Trailing }

private fun drawerGroupPosition(index: Int, count: Int): DrawerGroupPosition = when {
    count <= 1 -> DrawerGroupPosition.Single
    index <= 0 -> DrawerGroupPosition.Leading
    index >= count - 1 -> DrawerGroupPosition.Trailing
    else -> DrawerGroupPosition.Middle
}

/**
 * 官方相连按钮组形状（静止 + 按压），全部取自 `ButtonGroupDefaults`：
 * 外侧 `CornerFull`、内侧 8dp，按压时内圆角收到 4dp。
 */
@Composable
private fun connectedButtonShapes(position: DrawerGroupPosition): ButtonShapes = when (position) {
    DrawerGroupPosition.Single -> ButtonDefaults.shapes()
    DrawerGroupPosition.Leading -> ButtonDefaults.shapes(
        shape = ButtonGroupDefaults.connectedLeadingButtonShape,
        pressedShape = ButtonGroupDefaults.connectedLeadingButtonPressShape,
    )
    DrawerGroupPosition.Middle -> ButtonDefaults.shapes(
        shape = ButtonGroupDefaults.connectedMiddleButtonShapes().shape,
        pressedShape = ButtonGroupDefaults.connectedMiddleButtonPressShape,
    )
    DrawerGroupPosition.Trailing -> ButtonDefaults.shapes(
        shape = ButtonGroupDefaults.connectedTrailingButtonShape,
        pressedShape = ButtonGroupDefaults.connectedTrailingButtonPressShape,
    )
}

/** 官方相连图标按钮形状，与文字按钮组共用同一套 `ButtonGroupDefaults` 形状令牌。 */
@Composable
private fun connectedIconButtonShapes(position: DrawerGroupPosition): IconButtonShapes {
    val shapes = connectedButtonShapes(position)
    return IconButtonShapes(shape = shapes.shape, pressedShape = shapes.pressedShape)
}

/**
 * M3 抽屉：相连按钮组里的文字按钮。
 *
 * 官方 `Button`（选中）/ `FilledTonalButton`（未选中），高度、内容内边距、图标尺寸、
 * 字阶与按压形状全部取自 `ButtonDefaults` / `ButtonGroupDefaults`，不自定义。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Material3DrawerChip(
    text: String,
    position: DrawerGroupPosition,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val shapes = connectedButtonShapes(position)
    // 高度用官方 Small 按钮默认值（ButtonDefaults.MinHeight）：不覆盖高度，
    // 让官方 Button 的 defaultMinSize 直接落在 Small 档，与主页 / 输入栏按钮同档。
    val chipModifier = modifier
    val content: @Composable RowScope.() -> Unit = {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
        }
        M3Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (selected) {
        Button(
            onClick = onClick,
            shapes = shapes,
            modifier = chipModifier,
            content = content,
        )
    } else {
        FilledTonalButton(
            onClick = onClick,
            shapes = shapes,
            modifier = chipModifier,
            content = content,
        )
    }
}

/**
 * M3 抽屉：文件夹按钮（带长按菜单）。
 *
 * 容器外观由官方 `Surface` 提供（形状 / 容器色 / 内容色取自 `ButtonDefaults` 的
 * 按钮配色与 `ButtonGroupDefaults` 的相连形状，按压时切到官方按压形状）；
 * 手势层用官方 `Modifier.combinedClickable` + 官方 `ripple()`，以保留长按弹出
 * 重命名 / 删除菜单的既有交互。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Material3DrawerFolderChip(
    text: String,
    position: DrawerGroupPosition,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shapes = connectedButtonShapes(position)
    val colors = if (selected) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    M3Surface(
        modifier = Modifier
            .heightIn(min = ButtonDefaults.MinHeight)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(
                    color = colors.contentColor,
                    focusRingShape = shapes.shape,
                ),
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = if (pressed) shapes.pressedShape else shapes.shape,
        color = colors.containerColor,
        contentColor = colors.contentColor,
    ) {
        Row(
            modifier = Modifier.padding(
                ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight, hasStartIcon = true),
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing),
        ) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            M3Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * M3 抽屉：底部工具行里的相连图标按钮。
 *
 * 官方 `FilledTonalIconButton` + 官方 `IconButtonShapes`，
 * 容器尺寸取 `IconButtonDefaults.smallContainerSize()`，图标取 `smallIconSize`。
 */
@Composable
private fun Material3ConnectedIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    position: DrawerGroupPosition,
) {
    FilledTonalIconButton(
        onClick = onClick,
        shapes = connectedIconButtonShapes(position),
        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
}

/** 官方下拉菜单项：形状取 `MenuDefaults.itemShape`，行高 / 字阶 / 颜色全用官方默认值。 */
@Composable
private fun DrawerActionMenuItem(
    index: Int,
    count: Int,
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { M3Text(text = text) },
        shape = MenuDefaults.itemShape(index = index, count = count).shape,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        },
        onClick = onClick,
    )
}

/**
 * M3 抽屉的日期分组标题：左标题、右数量（官方 `Badge`）。
 * 内边距复用官方 `ListItemDefaults.ContentPadding`，与会话行文字左边缘对齐。
 */
@Composable
private fun Material3DrawerSectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(ListItemDefaults.ContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
M3Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // 计数只是辅助信息：用官方 surfaceVariant / onSurfaceVariant 中性色，不用 Badge 默认的强调色。
        Badge(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            M3Text(text = count.toString())
        }
    }
}

/** M3 抽屉空态：官方 Text + 官方排版 / 颜色角色。 */
@Composable
private fun Material3EmptyConversations(isSearching: Boolean) {
    M3Text(
        text = stringResource(
            if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(ListItemDefaults.ContentPadding),
    )
}

/**
 * M3 抽屉：会话行。
 *
 * 官方 `ListItem`（单选语义 + `onLongClick`）+ 官方 `DropdownMenu` / `DropdownMenuItem`，
 * 行高 / 内边距 / 字阶 / 配色 / 形状全部走官方默认值。
 */
@Composable
private fun Material3ConversationRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: () -> Unit,
    onTogglePin: () -> Unit,
    onExport: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            selected = selected,
            onClick = onClick,
            onLongClick = { showActionMenu = true },
            modifier = Modifier.fillMaxWidth(),
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DrawerMetrics.ActiveDotGap),
                ) {
                    if (conversation.isActiveRun || conversation.hasCompletionMarker) {
                        Box(
                            modifier = Modifier
                                .size(DrawerMetrics.ActiveDotSize)
                                .clip(CircleShape)
                                .then(
                                    if (conversation.isActiveRun) {
                                        Modifier.background(MaterialTheme.colorScheme.primary)
                                    } else {
                                        Modifier.border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                        )
                                    },
                                ),
                        )
                    }
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = stringResource(R.string.conversation_pin),
                        )
                    }
                }
            },
        ) {
            M3Text(
                text = conversation.title.ifBlank { conversation.preview },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        DropdownMenu(
            expanded = showActionMenu,
            onDismissRequest = { showActionMenu = false },
        ) {
            DrawerActionMenuItem(
                index = 0,
                count = 5,
                text = stringResource(R.string.action_rename),
                icon = Icons.Rounded.Edit,
                onClick = {
                    showActionMenu = false
                    onRename()
                },
            )
            DrawerActionMenuItem(
                index = 1,
                count = 5,
                text = stringResource(
                    if (conversation.isPinned) R.string.conversation_unpin
                    else R.string.conversation_pin,
                ),
                icon = Icons.Rounded.PushPin,
                onClick = {
                    showActionMenu = false
                    onTogglePin()
                },
            )
            DrawerActionMenuItem(
                index = 2,
                count = 5,
                text = stringResource(R.string.conversation_export),
                icon = Icons.Rounded.Download,
                onClick = {
                    showActionMenu = false
                    onExport()
                },
            )
            DrawerActionMenuItem(
                index = 3,
                count = 5,
                text = stringResource(R.string.drawer_move_to_folder),
                icon = Icons.Rounded.Folder,
                onClick = {
                    showActionMenu = false
                    onMoveToFolder()
                },
            )
            DrawerActionMenuItem(
                index = 4,
                count = 5,
                text = stringResource(R.string.action_delete),
                icon = Icons.Rounded.Delete,
                onClick = {
                    showActionMenu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun DrawerChip(
    label: String,
    selected: Boolean,
    icon: ImageVector?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val background = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    val view = LocalView.current
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(background)
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = {
                        TouchHaptics.click(view)
                        onClick()
                    },
                    onLongPress = {
                        if (onLongClick != null) {
                            TouchHaptics.longPress(view)
                            onLongClick()
                        }
                    },
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor,
            )
        }
        Text(
            text = label,
            color = contentColor,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
) {
    Text(
        text = group.localizedLabel(),
        color = MiuixTheme.colorScheme.primary,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = DrawerMetrics.RowHorizontalPadding,
                top = DrawerMetrics.SectionTopPadding,
                bottom = DrawerMetrics.SectionBottomPadding,
            ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTextRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: () -> Unit,
    onTogglePin: () -> Unit,
    onExport: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    val view = LocalView.current

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.RowMinHeight)
                .clip(RoundedCornerShape(DrawerMetrics.RowCornerRadius))
                .background(
                    if (selected) {
                        MiuixTheme.colorScheme.surfaceContainerHigh
                    } else {
                        Color.Transparent
                    },
                )
                .combinedClickable(
                    hapticFeedbackEnabled = false,
                    onClick = {
                        TouchHaptics.click(view)
                        onClick()
                    },
                    onLongClick = {
                        TouchHaptics.longPress(view)
                        showActionMenu = true
                    },
                )
                .padding(
                    horizontal = DrawerMetrics.RowHorizontalPadding,
                    vertical = DrawerMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = conversation.title.ifBlank { conversation.preview },
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (conversation.isActiveRun || conversation.hasCompletionMarker) {
                Box(
                    modifier = Modifier
                        .padding(start = if (conversation.isPinned) 0.dp else DrawerMetrics.ActiveDotGap)
                        .size(DrawerMetrics.ActiveDotSize)
                        .clip(CircleShape)
                        .then(
                            if (conversation.isActiveRun) {
                                Modifier.background(MiuixTheme.colorScheme.primary)
                            } else {
                                Modifier.border(1.dp, MiuixTheme.colorScheme.primary, CircleShape)
                            },
                        ),
                )
            }
            if (conversation.isPinned) {
                Icon(
                    imageVector = Icons.Rounded.PushPin,
                    contentDescription = null,
                    modifier = Modifier.padding(
                        start = if (conversation.isActiveRun || conversation.hasCompletionMarker) DrawerMetrics.ActiveDotGap else 0.dp,
                    ).size(16.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }

        EtaDropdownMenu(
            expanded = showActionMenu,
            onDismissRequest = { showActionMenu = false },
            offset = DpOffset(x = 8.dp, y = 8.dp),
            alignEnd = true,
        ) {
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(stringResource(R.string.action_rename)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = {
                    Text(
                        stringResource(
                            if (conversation.isPinned) R.string.conversation_unpin
                            else R.string.conversation_pin,
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(stringResource(R.string.conversation_export)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onExport()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(stringResource(R.string.drawer_move_to_folder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onMoveToFolder()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(text = stringResource(R.string.action_delete), color = MiuixTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                        tint = MiuixTheme.colorScheme.error,
                    )
                },
                onClick = {
                    showActionMenu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean) {
    Text(
        text = stringResource(
            if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(
            horizontal = DrawerMetrics.RowHorizontalPadding,
            vertical = DrawerMetrics.EmptyVerticalPadding,
        ),
    )
}

@Composable
private fun PaneAssistantBar(
    name: String,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DrawerMetrics.AssistantBarHeight)
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                TouchHaptics.click(view)
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        avatar()
    }
}

@Composable
private fun PaneDock(
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrawerCircleButton(
            icon = Icons.Rounded.HealthAndSafety,
            label = stringResource(R.string.ui_permission_health_3048bb),
            onClick = onOpenPermissions,
        )
        Spacer(modifier = Modifier.width(10.dp))
        DrawerCircleButton(
            icon = Icons.Rounded.Memory,
            label = stringResource(R.string.conversation_dock_models),
            onClick = onOpenModelProviders,
        )
        Spacer(modifier = Modifier.width(10.dp))
        DrawerCircleButton(
            icon = Icons.Rounded.Extension,
            label = stringResource(R.string.route_skills),
            onClick = onOpenSkills,
        )
        Spacer(modifier = Modifier.width(10.dp))
        DrawerCircleButton(
            icon = Icons.Rounded.BarChart,
            label = stringResource(R.string.stats_page_title),
            onClick = onOpenUsageStats,
        )
        Spacer(modifier = Modifier.weight(1f))
        DrawerCircleButton(
            icon = Icons.Rounded.Settings,
            label = stringResource(R.string.route_settings),
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun DrawerCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primaryContainer)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                TouchHaptics.click(view)
                onClick()
            }
            .padding(DrawerMetrics.CircleButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(DrawerMetrics.ActionIconSize),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private val DrawerMotionEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

private val DrawerOpenMotion: FiniteAnimationSpec<Float> = tween(
    durationMillis = DrawerMetrics.OpenDurationMs,
    easing = DrawerMotionEasing,
)

private val DrawerCloseMotion: FiniteAnimationSpec<Float> = tween(
    durationMillis = DrawerMetrics.CloseDurationMs,
    easing = DrawerMotionEasing,
)

private object DrawerMotionSetters {
    private val drawerStateClass = DrawerState::class.java
    private val openSetter = method("setOpenDrawerMotionSpec\$material3")
    private val closeSetter = method("setCloseDrawerMotionSpec\$material3")

    private fun method(name: String) = runCatching {
        drawerStateClass.getMethod(name, FiniteAnimationSpec::class.java)
    }.getOrNull()

    fun apply(state: DrawerState) {
        // 只改菜单按钮触发的开合。手势松手后的 settle 沿用 Material3 默认弹簧，
        // 才能吃进快滑速度；短 tween 会把惯性掐成匀速，看起来就是顿一下。
        openSetter?.invoke(state, DrawerOpenMotion)
        closeSetter?.invoke(state, DrawerCloseMotion)
    }
}

private fun DrawerState.applyEtaDrawerMotion() {
    DrawerMotionSetters.apply(this)
}

internal data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
) {
    val key: String get() = "section-$section"
}

internal sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
}

internal fun List<ConversationSummaryUi>.groupForDrawer(): List<ConversationDrawerGroup> {
    // A cached label or a differently ordered input can put the same section in disjoint
    // runs. Merge all of them, keeping the first section occurrence and row order stable.
    val groups = linkedMapOf<ConversationDrawerSection, MutableList<ConversationSummaryUi>>()
    for (conversation in this) {
        groups.getOrPut(conversation.drawerSection()) { mutableListOf() } += conversation
    }
    return groups.map { (section, items) -> ConversationDrawerGroup(section, items.toList()) }
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isToday -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}
