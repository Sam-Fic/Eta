package io.github.mangi.eta.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.app.SearchHistoryDialog
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.components.streamDiagnosticDraw
import io.github.mangi.eta.ui.components.streamDiagnosticMeasure
import io.github.mangi.eta.ui.components.streamDiagnosticPlacement
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.MessageSearchHit
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog


/** 管理页列表顺序。已有会话保持原位；新会话按当前列表里的相对顺序插到最前。置顶变化才整表重排。 */
internal fun stableManageChatOrder(
    previous: List<String>,
    current: List<String>,
    reorder: Boolean = false,
): List<String> {
    if (previous.isEmpty() || reorder) return current
    val present = current.toSet()
    val kept = previous.filter { it in present }
    val keptSet = kept.toSet()
    return current.filter { it !in keptSet } + kept
}

@Composable
internal fun ManageChatsScreen(
    conversations: List<ConversationSummaryUi>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteConversation: (ConversationSummaryUi) -> Unit,
    onDeleteAll: () -> Unit,
    onSearchHistory: suspend (String) -> List<MessageSearchHit> = { emptyList() },
    onOpenHistoryHit: (MessageSearchHit) -> Unit = {},
) {
    var showDeleteAll by remember { mutableStateOf(false) }
    var showSearchHistory by remember { mutableStateOf(false) }
    // 多个会话同时在跑时，更新时间会不断把它们换到前面。管理页按打开时的顺序钉住，
    // 只在原地刷新标题和状态，滑动删除才点得中。新出现的会话插到最前，关掉页面再打开才重排。
    // The producer replaces immutable summary snapshots; row data always comes from the latest one.
    val currentIds = remember(conversations) { conversations.map { it.id } }
    val pinnedIds = remember(conversations) {
        conversations.asSequence().filter { it.isPinned }.map { it.id }.toSet()
    }
    var orderIds by remember { mutableStateOf(currentIds) }
    var pinnedSnapshot by remember { mutableStateOf(pinnedIds) }
    val pinnedChanged = pinnedIds != pinnedSnapshot
    val nextOrder = remember(orderIds, currentIds, pinnedChanged) {
        stableManageChatOrder(orderIds, currentIds, pinnedChanged)
    }
    SideEffect {
        if (nextOrder != orderIds || pinnedChanged) {
            orderIds = nextOrder
            pinnedSnapshot = pinnedIds
        }
    }
    val byId = remember(conversations) { conversations.associateBy { it.id } }
    val ordered = remember(nextOrder, byId) { nextOrder.mapNotNull { byId[it] } }

    EtaScaffoldPage(
        title = stringResource(R.string.history_page_title),
        onBack = onBack,
        listModifier = Modifier.streamDiagnosticMeasure("manage.lazy.measure").streamDiagnosticPlacement("manage.lazy.place").streamDiagnosticDraw("manage.lazy.draw"),
        actions = {
            TopBarIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = stringResource(R.string.action_search_history),
                onClick = { showSearchHistory = true },
            )
            TopBarIconButton(
                icon = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.history_page_delete_all),
                destructive = true,
                enabled = conversations.isNotEmpty(),
                onClick = { if (conversations.isNotEmpty()) showDeleteAll = true },
            )
        },
    ) {
        if (ordered.isEmpty()) {
            item(key = "empty") {
                EtaText(
                    text = stringResource(R.string.history_page_empty),
                    style = EtaTextStyle.Body,
                    secondary = true,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                )
            }
        } else {
            items(ordered, key = { it.id }, contentType = { "conversation" }) { conversation ->
                SwipeableManageChatRow(
                    conversation = conversation,
                    onClick = { onOpenConversation(conversation.id) },
                    onTogglePin = { onTogglePin(conversation.id) },
                    onDelete = { onDeleteConversation(conversation) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = null,
                        // M3 Expressive：淡出/位移动效统一取官方 motionScheme 规格；
                        // miuix 主题族保留原来的 180ms。
                        fadeOutSpec = if (isMaterial3Theme()) {
                            MaterialTheme.motionScheme.defaultEffectsSpec()
                        } else {
                            tween(180)
                        },
                        placementSpec = null,
                    ),
                )
            }
        }
    }

    SearchHistoryDialog(
        show = showSearchHistory,
        onDismiss = { showSearchHistory = false },
        onSearch = onSearchHistory,
        onOpenHit = { hit ->
            showSearchHistory = false
            onOpenHistoryHit(hit)
        },
    )

    if (showDeleteAll) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.history_page_delete_all_title),
            summary = stringResource(R.string.history_page_delete_all_message),
            onDismissRequest = { showDeleteAll = false },
        ) {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { showDeleteAll = false },
                onConfirm = {
                    onDeleteAll()
                    showDeleteAll = false
                },
            )
        }
    }
}

/**
 * 顶栏图标按钮。M3 用官方图标按钮（容器尺寸取 `IconButtonDefaults` 官方令牌，
 * 破坏性动作用 `IconButtonDefaults.iconButtonColors` 搭配 error 角色色）。
 */
@Composable
private fun TopBarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    if (isMaterial3Theme()) {
        val colors = if (destructive) {
            androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            )
        } else {
            androidx.compose.material3.IconButtonDefaults.iconButtonColors()
        }
        androidx.compose.material3.IconButton(
            onClick = {
                TouchHaptics.click(view)
                onClick()
            },
            enabled = enabled,
            colors = colors,
        ) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
        return
    }
    IconButton(onClick = {
        TouchHaptics.click(view)
        onClick()
    }, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (destructive) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SwipeableManageChatRow(
    conversation: ConversationSummaryUi,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val deleteDistance = with(density) { 96.dp.toPx() }

    if (isMaterial3Theme()) {
        // 官方滑动删除：SwipeToDismissBox + rememberSwipeToDismissBoxState，
        // 只允许 EndToStart（向左）触发删除，阈值与回落动效由官方 AnimateTo/Snap 规格决定。
        val dismissState = rememberSwipeToDismissBoxState(
            positionalThreshold = { totalDistance -> totalDistance * 0.5f },
        )
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            },
            modifier = modifier,
        ) {
            ManageChatRow(
                conversation = conversation,
                onClick = onClick,
                onTogglePin = onTogglePin,
            )
        }
        LaunchedEffect(dismissState.currentValue) {
            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                TouchHaptics.gestureThreshold(view)
                onDelete()
            }
        }
        return
    }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var collapsing by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    var crossedDeleteThreshold by remember { mutableStateOf(false) }

    LaunchedEffect(offsetX, deleteDistance) {
        val crossed = offsetX <= -deleteDistance
        if (crossed && !crossedDeleteThreshold) {
            crossedDeleteThreshold = true
            TouchHaptics.gestureThreshold(view)
        } else if (!crossed) {
            crossedDeleteThreshold = false
        }
    }

    val currentOnDelete by rememberUpdatedState(onDelete)
    LaunchedEffect(collapsing) {
        if (!collapsing || deleted) return@LaunchedEffect
        delay(300)
        deleted = true
        currentOnDelete()
    }

    AnimatedVisibility(
        visible = !collapsing,
        modifier = modifier,
        exit = fadeOut(animationSpec = tween(160)) + shrinkVertically(
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top,
        ),
    ) {
        Box(
            modifier = Modifier
                .manageChatDeleteDrag(
                    onMove = { offsetX = it },
                    onEnd = { total ->
                        if (total <= -deleteDistance) collapsing = true else offsetX = 0f
                    },
                )
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MiuixTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MiuixTheme.colorScheme.onErrorContainer,
                )
            }
            Box(modifier = Modifier.fillMaxWidth().offset { IntOffset(offsetX.roundToInt(), 0) }) {
                ManageChatRow(
                    conversation = conversation,
                    onClick = onClick,
                    onTogglePin = onTogglePin,
                )
            }
        }
    }
}

@Composable
private fun ManageChatRow(
    conversation: ConversationSummaryUi,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    if (isMaterial3Theme()) {
        // 官方可交互 ListItem：整行点按 + 自带涟漪/语义；置顶用官方 FilledIconToggleButton。
        ListItem(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            content = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    M3Text(
                        text = conversation.title.ifBlank { conversation.preview },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.isActiveRun || conversation.hasCompletionMarker) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .then(
                                    if (conversation.isActiveRun) {
                                        Modifier.background(MaterialTheme.colorScheme.primary)
                                    } else {
                                        Modifier.border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape,
                                        )
                                    },
                                ),
                        )
                    }
                }
            },
            supportingContent = {
                M3Text(
                    text = conversation.timeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                androidx.compose.material3.IconToggleButton(
                    checked = conversation.isPinned,
                    onCheckedChange = { onTogglePin() },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = stringResource(
                            if (conversation.isPinned) {
                                R.string.conversation_unpin
                            } else {
                                R.string.conversation_pin
                            },
                        ),
                    )
                }
            },
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = conversation.title.ifBlank { conversation.preview },
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conversation.isActiveRun || conversation.hasCompletionMarker) {
                    Box(
                        modifier = Modifier.size(6.dp).clip(CircleShape).then(
                            if (conversation.isActiveRun) {
                                Modifier.background(MiuixTheme.colorScheme.primary)
                            } else {
                                Modifier.border(1.dp, MiuixTheme.colorScheme.primary, CircleShape)
                            },
                        ),
                    )
                }
            }
            Text(
                text = conversation.timeLabel,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                imageVector = Icons.Rounded.PushPin,
                contentDescription = stringResource(
                    if (conversation.isPinned) {
                        R.string.conversation_unpin
                    } else {
                        R.string.conversation_pin
                    },
                ),
                modifier = Modifier.size(20.dp),
                tint = if (conversation.isPinned) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }
    }
}

/**
 * Claim only a clearly leftward delete drag. Consuming any other direction also
 * consumes it for the list and the page-back parent, so vertical and rightward
 * gestures stay untouched.
 */
private fun Modifier.manageChatDeleteDrag(
    onMove: (Float) -> Unit,
    onEnd: (Float) -> Unit,
): Modifier = pointerInput(onMove, onEnd) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var deleting = false
        var totalX = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.position - down.position
            if (!deleting) {
                val moved = kotlin.math.abs(delta.x) > slop || kotlin.math.abs(delta.y) > slop
                if (!moved) continue
                deleting = delta.x < -slop && kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)
                if (!deleting) break
            }
            change.consume()
            totalX = delta.x.coerceAtMost(0f)
            onMove(totalX)
        }
        if (deleting) onEnd(totalX)
    }
}
