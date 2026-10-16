package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.EtaTextField
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaLabelStyle
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.unit.Density
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
    // 手势松手后的合拢速度下限（dp/s）。低于它算「拖着不放」，按位置判；高于它算甩，
    // 直接朝甩的方向定锚，速度越快越早到位——这是「跟手」的关键。
    // 官方是 400dp/s，偏低：轻轻一拨就按位置判，容易出现已经甩很远却弹回去的割裂感。
    const val SettleVelocityThresholdDp = 720
    // 位置阈值占全程的比例。官方 0.5 意味着拖过一半才松手。
    // 抽屉是「越长越全」的连续面板，稍微一拉就有反馈更符合预期。
    const val SettlePositionalThreshold = 0.35f
    // 惯性衰减的摩擦系数。只影响快滑时的「借惯性飞多远」：
    // 数值越小衰减越快、飞得越近；越大越接近「滑脱手」。0.86f 接近 Android View 系统的摩擦。
    const val SettleFriction = 0.86f
    // 惯性投影的收敛速度（dp/s）。官方要求 >0，取极小值让惯性尽量用满。
    const val SettleDecayVelocityThreshold = 0.01f
    // 临界阻尼比（无回弹）与刚度，决定 settle 的「重量感」。
    const val SettleDampingRatio = 1.0f
    const val SettleStiffness = 420f
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
    // 不能用 remember(drawerState) 只跑一次：DismissibleNavigationDrawer 内部有个
    // SideEffect 会把自己的 open/close/anchored spec 覆写回官方值，我们得排在它之后
    // 再补一次。SideEffect 每次成功组合后都会跑，正好覆盖这个顺序。
    SideEffect {
        drawerState.applyEtaDrawerMotion()
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

    // 选中会话即收起侧栏回到聊天主页，与「新建对话」/历史搜索命中的行为一致。
    // 走后缀动画而不是 launchAfterDrawerClosed 的 snapTo：和手滑关闭同一条缓动，
    // 观感一致；这里不涉及导航入栈，不会出现底部按钮切页那种整页重组卡顿。
    val selectConversationAndClosePane: (String) -> Unit = { conversationId ->
        onConversationSelected(conversationId)
        drawerScope.launch {
            if (drawerState.targetValue != DrawerValue.Closed ||
                drawerState.currentValue != DrawerValue.Closed
            ) {
                @Suppress("DEPRECATION")
                drawerState.animateTo(
                    targetValue = DrawerValue.Closed,
                    anim = DrawerCloseMotion,
                )
            }
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
        // 两者都走「平移推动」：侧栏把页面推走，而不是盖在页面上再压暗。
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
        // 拖过「过半」那条线时给一次轻触反馈。抽屉只有开/合两个锚点，手指感受不到
        // 卡位，加一道振动手感才知道自己已经决定了松手后的归宿。
        // 只在真的越过瞬间触发一次（拿上一帧的 bool 做边沿检测），来回蹭不会连续震。
        // offset 语义：闭合锚点是「-面板宽度」，打开锚点是 0，
        // 所以「越过过半」等价于 |offset| < 面板宽度 / 2。
        LaunchedEffect(drawerState, paneWidth, view) {
            val density = LocalDensity.current
            val halfPanePx = with(density) { paneWidth.toPx() / 2f }
            var wasPastCommitLine by mutableStateOf(false)
            snapshotFlow {
                val offset = drawerState.currentOffset
                if (offset.isNaN()) false else abs(offset) < halfPanePx
            }.collectLatest { pastLine ->
                if (pastLine != wasPastCommitLine) {
                    wasPastCommitLine = pastLine
                    if (pastLine && !drawerState.isAnimationRunning) {
                        TouchHaptics.gestureThreshold(view)
                    }
                }
            }
        }
        CompositionLocalProvider(LocalConversationDrawerBlocksIme provides drawerBlocksIme) {
        // 平推（非覆盖）：侧栏占据左侧 paneWidth，聊天页整体右移同样的距离，不加遮罩、不压暗。
        // 手势收敛与锚点仍交给 Material3 的 Dismissible 实现，只是内容被推走而不是被盖住。
        DismissibleNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = backHandlerEnabled,
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
                    onConversationSelected = selectConversationAndClosePane,
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
            enableSearchFocus = enableSearchFocus,
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
    val textStyle = etaTextStyle(EtaType.Secondary).merge(
        TextStyle(
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = etaTextStyle(EtaType.Secondary).fontSize,
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
                    style = etaTextStyle(EtaType.Secondary),
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
            // 标签档：与主页顶栏会话名同号（Secondary + Medium）。
            style = etaLabelStyle(),
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
    // 打开即聚焦并唤起输入法；键盘 Done 等价于「保存」（空名时不响应）。
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    EtaDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column {
            EtaTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.drawer_folder_name),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboard?.hide()
                        if (name.isNotBlank()) onConfirm(name)
                    },
                ),
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
    enableSearchFocus: Boolean,
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
        // 与 M3 页面背景（壳层 Scaffold 的 surfaceContainer）同色，抽屉盖在页面上不再有色差。
        color = MaterialTheme.colorScheme.surfaceContainer,
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
            Material3PaneSearchField(
                query = state.searchQuery,
                onSearchChange = onSearchChange,
                enableSearchFocus = enableSearchFocus,
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

            // 第二排：左侧可横向滚动的「聊天 / [文件夹…]」，右侧固定「新建文件夹」。
            val chipCount = 1 + folders.size
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
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
                }
                Spacer(modifier = Modifier.width(ButtonGroupDefaults.ConnectedSpaceBetween))
                // 独立胶囊按钮（DrawerGroupPosition.Single）：始终贴右，不随文件夹列表滚动。
                Material3DrawerChip(
                    text = stringResource(R.string.drawer_new_folder),
                    position = DrawerGroupPosition.Single,
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
                overscrollEffect = null,
            ) {
                if (visibleConversations.isEmpty()) {
                    item(contentType = "drawer-empty") {
                        Material3EmptyConversations(isSearching = query.isNotBlank())
                    }
                } else {
                    groups.forEachIndexed { groupIndex, group ->
                        val isLastGroup = groupIndex == groups.lastIndex
                        item(key = group.key, contentType = "drawer-section") {
                            Material3DrawerSectionHeader(
                                label = group.localizedLabel(),
                                count = group.items.size,
                            )
                        }
                        itemsIndexed(
                            items = group.items,
                            key = { _, conversation -> conversation.id },
                            contentType = { _, _ -> "drawer-conversation" },
                        ) { index, conversation ->
                            Material3ConversationRow(
                                conversation = conversation,
                                index = index,
                                count = group.items.size,
                                isLastGroup = isLastGroup,
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
 * 官方 `TextField`，胶囊形（`CircleShape`），隐藏下划线指示条；
 * 容器色、文字色、行高、内容内边距全部使用官方默认值，不自绘容器。
 * 清除按钮用官方 `IconButton`（原实现只画了一个不可点的关闭图标）。
 *
 * 焦点门控与 miuix 的 `PaneSearchRow` 一致：官方 `DismissibleNavigationDrawer` 一打开就会
 * 把键盘焦点交给抽屉内第一个可聚焦元素（见 material3 `NavigationDrawer.kt` 的
 * `focusRequester.requestFocus()`），搜索框若可聚焦就会立刻弹出输入法。
 * 因此未激活前设 `canFocus = false`，由透明层接管点击，点进去才聚焦并唤起键盘。
 */
@Composable
private fun Material3PaneSearchField(
    query: String,
    onSearchChange: (String) -> Unit,
    enableSearchFocus: Boolean,
) {
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var searchActive by remember { mutableStateOf(false) }
    LaunchedEffect(enableSearchFocus) {
        if (!enableSearchFocus) {
            searchActive = false
            focusManager.clearFocus(force = true)
        }
    }
    LaunchedEffect(searchActive, enableSearchFocus) {
        if (searchActive && enableSearchFocus) {
            showChatInputIme(focusRequester, keyboard, view)
        }
    }
    Box {
        TextField(
            value = query,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusProperties { canFocus = enableSearchFocus && searchActive },
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
            // 胶囊形，并去掉填充式输入框默认的下划线指示条。
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        if (!searchActive) {
            // 未激活时输入框不可聚焦，用透明层接管点击；点一下才真正聚焦并唤起键盘。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        enabled = enableSearchFocus,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { searchActive = true },
            )
        }
    }
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
    // 未选中态的自定义容器色；不传时保持官方 FilledTonalButton 默认（secondaryContainer）。
    containerColor: Color? = null,
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
            colors = if (containerColor != null) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = containerColor,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
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
 *
 * 节奏与对齐都照搬设置页的 [EtaSegmentedColumn]：
 * - 上一组末行已补 [EtaSegmentedGroupSpacing]（13dp）组间距，标题自身不再加上边距；
 * - 标题到本组首行的间距用 [EtaSegmentedTitleSpacing]（8dp）；
 * - 标题左边缘贴分段卡片左边缘（与设置页组标题一致，抽屉外层已有 16dp 页边距）；
 *   右侧计数留 16dp，与本组行的尾部图标列对齐，避免顶到卡片圆角。
 */
@Composable
private fun Material3DrawerSectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 16.dp, bottom = EtaSegmentedTitleSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        M3Text(
            text = label,
            style = etaTextStyle(EtaType.Caption, emphasized = true),
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
        style = etaTextStyle(EtaType.Secondary),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(ListItemDefaults.ContentPadding),
    )
}

/**
 * M3 抽屉：会话行。
 *
 * 与设置页（错误重连等）同款分段列表样式：[EtaSegmentedItem] 按组内 index/count 注入
 * 首/末圆角，[EtaSegmentedRow] 走官方 `SegmentedListItem`（`surfaceBright` 容器、
 * 选中态 tertiaryContainer），组内行距 2dp、组末补 13dp 组间距（末组末行除外，
 * 由列表 contentPadding 收尾）；行首不加 radio 图标。
 * 长按菜单仍用官方 `DropdownMenu` / `DropdownMenuItem`。
 */
@Composable
private fun Material3ConversationRow(
    conversation: ConversationSummaryUi,
    index: Int,
    count: Int,
    isLastGroup: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: () -> Unit,
    onTogglePin: () -> Unit,
    onExport: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    EtaSegmentedItem(index = index, count = count) {
        // 组内行距走官方分段缝隙（2dp），组末行补足分组间距（末组由列表底部留白收尾）；
        // 缝隙放在外层，长按菜单的锚点仍紧贴本行。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    bottom = if (index == count - 1) {
                        if (isLastGroup) 0.dp else EtaSegmentedGroupSpacing
                    } else {
                        EtaSegmentedGap
                    },
                ),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                EtaSegmentedRow(
                    selected = selected,
                    // 选中行用 tertiaryContainer（与页面按钮的强调档一致），不覆盖时走官方默认。
                    selectedContainerColor = if (selected) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        null
                    },
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
                                                Modifier.background(
                                                    MaterialTheme.colorScheme.primary,
                                                )
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
            style = etaTextStyle(EtaType.Caption),
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
        style = etaTextStyle(EtaType.Caption),
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
                style = etaTextStyle(EtaType.Body),
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
        style = etaTextStyle(EtaType.Secondary),
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
            style = etaTextStyle(EtaType.Body),
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

/** 松手后的收敛动画：临界阻尼 + 适度刚度，无回弹、不拖沓。 */
private val DrawerSettleSpec: FiniteAnimationSpec<Float> = spring(
    dampingRatio = DrawerMetrics.SettleDampingRatio,
    stiffness = DrawerMetrics.SettleStiffness,
)

/**
 * 抽屉动效调参（Material3 `DismissibleNavigationDrawer` + `AnchoredDraggableState`）。
 *
 * 实测 alpha29 的链路（都对着 sources/javap 核过）：
 * - 手指拖拽：`AnchoredDraggableNode.drag()` → `newOffsetForDelta`，offset 被钳在
 *   闭合锚点（-面板宽度）和打开锚点（0）之间。
 * - 松手：`onDragStopped` → `fling(velocity)`。`DrawerState` 用的是带
 *   positionalThreshold/velocityThreshold/snapAnimationSpec/decayAnimationSpec 的重载构造，
 *   所以 `usePreModifierChangeBehavior == true`，走的是 `state.settle(velocity)` 这条老路。
 * - `settle` → `computeTarget(positionalThreshold, velocityThreshold)` 定目标锚点 →
 *   `animateToWithDecay(snapAnimationSpec, decayAnimationSpec)` 收敛。
 *
 * 关键点：这四个字段都在 `DrawerState` 那个 private final 的 `anchoredDraggableState` 上，
 * 官方没有公开入口改，只能反射；而且它们是 **settle 时现读的**，改完立刻对下一次手势生效。
 * （不要改成 `Modifier.anchoredDraggable(flingBehavior = ...)`：那只有在
 * `usePreModifierChangeBehavior == false` 时才走得到，抽屉用不上。）
 *
 * `DismissibleNavigationDrawer` 每次成功组合还会用 `SideEffect` 把自己的
 * open/close/anchored 三个 spec 覆写回官方值，所以我们也得在每次组合后补一次，
 * 顺序排到那个 SideEffect 之后。
 *
 * 每一步都单独 `runCatching`：任何一项反射失败就跳过该项，整体退回官方行为，不会崩。
 */
private object DrawerMotionSetters {
    private val drawerStateClass = DrawerState::class.java
    private val anchoredClass = runCatching {
        Class.forName("androidx.compose.foundation.gestures.AnchoredDraggableState")
    }.getOrNull()
    private val function1Class = runCatching {
        Class.forName("kotlin.jvm.functions.Function1")
    }.getOrNull()
    private val function0Class = runCatching {
        Class.forName("kotlin.jvm.functions.Function0")
    }.getOrNull()

    private val openSetter = method(drawerStateClass, "setOpenDrawerMotionSpec\$material3")
    private val closeSetter = method(drawerStateClass, "setCloseDrawerMotionSpec\$material3")
    private val anchoredSetter = method(drawerStateClass, "setAnchoredDraggableMotionSpec\$material3")
    private val densityGetter = runCatching {
        drawerStateClass.getMethod("getDensity\$androidx_compose_material3_material3")
    }.getOrNull()
    private val anchoredField = runCatching {
        drawerStateClass.getDeclaredField("anchoredDraggableState").apply { isAccessible = true }
    }.getOrNull()

    private fun method(
        target: Class<*>,
        name: String,
        arg: Class<*> = FiniteAnimationSpec::class.java,
    ) = runCatching { target.getMethod(name, arg) }.getOrNull()

    // 阈值和规格的 setter 在 AnchoredDraggableState 上，不在 DrawerState 上，别找错类。
    // 参数类型也得对上：阈值是 Function1/Function0，规格是 AnimationSpec/DecayAnimationSpec。
    private val positionalSetter by lazy {
        anchoredClass?.let { method(it, "setPositionalThreshold\$foundation", function1Class) }
    }
    private val velocitySetter by lazy {
        anchoredClass?.let { method(it, "setVelocityThreshold\$foundation", function0Class) }
    }
    private val snapSetter by lazy {
        anchoredClass?.let {
            method(
                it,
                "setSnapAnimationSpec\$foundation",
                Class.forName("androidx.compose.animation.core.AnimationSpec"),
            )
        }
    }
    private val decaySetter by lazy {
        anchoredClass?.let {
            method(
                it,
                "setDecayAnimationSpec\$foundation",
                Class.forName("androidx.compose.animation.core.DecayAnimationSpec"),
            )
        }
    }

    fun apply(state: DrawerState) {
        // 程序化开合（菜单按钮、选中会话）沿用原来的 tween，观感不变。
        openSetter?.invoke(state, DrawerOpenMotion)
        closeSetter?.invoke(state, DrawerCloseMotion)
        // anchoredDraggableMotionSpec 是 snapTo/settle 这条程序化路径的兜底规格。
        // 手势松手走的是 state.settle(velocity)（见类注释），读的是下面四个字段，
        // 不吃这个；但为了 snapTo 和 settle(animationSpec) 这两个入口也一致，一并给上。
        anchoredSetter?.invoke(state, DrawerSettleSpec)

        val anchored = anchoredField?.get(state) ?: return
        runCatching {
            positionalSetter?.invoke(anchored, positionalThresholdProxy())
            velocitySetter?.invoke(anchored, velocityThresholdProxy(state))
            snapSetter?.invoke(anchored, DrawerSettleSpec)
            decaySetter?.invoke(anchored, decaySpec())
        }
    }

    /**
     * `positionalThreshold: (Float) -> Float`，入参是两个锚点的距离。
     * 比例越小越「一拉就开」，越大越「拖过半才开」。
     */
    private fun positionalThresholdProxy(): Any {
        val cls = function1Class ?: return Unit
        return java.lang.reflect.Proxy.newProxyInstance(
            cls.classLoader,
            arrayOf(cls),
        ) { _, method, args ->
            when (method.name) {
                "invoke" -> {
                    val distance = args?.firstOrNull() as? Float ?: 0f
                    distance * DrawerMetrics.SettlePositionalThreshold
                }
                else -> null
            }
        }
    }

    /**
     * `velocityThreshold: () -> Float`，松手速度超过它就直接按甩的方向定锚，
     * 不再看位置。越大越「必须甩得够快」。
     */
    private fun velocityThresholdProxy(state: DrawerState): Any {
        val cls = function0Class ?: return Unit
        val thresholdPx = runCatching {
            val density = densityGetter?.invoke(state) as? Density
            density?.let { with(it) { DrawerMetrics.SettleVelocityThresholdDp.dp.toPx() } }
        }.getOrNull() ?: return Unit
        return java.lang.reflect.Proxy.newProxyInstance(
            cls.classLoader,
            arrayOf(cls),
        ) { _, method, _ ->
            when (method.name) {
                "invoke" -> thresholdPx
                else -> null
            }
        }
    }

    /**
     * `decayAnimationSpec` 决定快滑时能「借惯性飞多远」。官方把 decay 设成 NoOp，
     * 速度完全不被投影利用。这里给一个指数衰减，快滑就能顺着惯性多滑一段。
     * `absVelocityThreshold` 是官方要求 >0 的收敛速度，取一个极小值，
     * 让惯性尽量用满、不要过早判定结束。
     */
    private fun decaySpec(): Any = exponentialDecay(
        frictionMultiplier = DrawerMetrics.SettleFriction,
        absVelocityThreshold = DrawerMetrics.SettleDecayVelocityThreshold,
    )
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
