package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaConnectedGroupRow
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.etaLabelStyle
import io.github.mangi.eta.ui.components.ChatInputNonFocusableIconButton
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.MessageSearchHit

/**
 * 首页顶栏（Material 3 Expressive）。
 *
 * 一行三件套：菜单色调图标按钮、会话名相连按钮组（左段点击重命名、右段箭头展开溢出菜单）、
 * 新建对话色调图标按钮（横向拉伸占满剩余宽度）。
 */
@Composable
internal fun Material3HomeTopBar(
    title: String,
    onOpenConversationPane: () -> Unit,
    onRenameCurrentConversation: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenWorkspace: () -> Unit,
    autoCompressEnabled: Boolean,
    isCompressingContext: Boolean,
    onToggleAutoCompress: (Boolean) -> Unit,
    onCompressConversation: (
        providerId: String?,
        modelId: String?,
        onFinished: (Boolean) -> Unit,
    ) -> Unit,
    onSearchHistory: suspend (String) -> List<MessageSearchHit>,
    onOpenHistoryHit: (MessageSearchHit) -> Unit,
    tokenUsage: ConversationTokenUsageUi,
    subAgentStatuses: List<SubAgentContextStats>,
    canDeleteCurrentConversation: Boolean = false,
    onDeleteCurrentConversation: () -> Unit = {},
) {
    val view = LocalView.current
    // 不加 Surface 底板：顶栏直接透在页面背景上，与下方内容保持同一底色。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.statusBars.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EtaIconButton(
            icon = Icons.Rounded.Menu,
            contentDescription = stringResource(R.string.action_conversation_history),
            onClick = {
                TouchHaptics.click(view)
                onOpenConversationPane()
            },
            size = EtaIconSize.S,
            style = EtaButtonStyle.TonalEmphasis,
        )
        // 会话名按钮在两侧图标之间的剩余空间里居中（与官方 CenterAlignedTopAppBar 的标题布局一致）。
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // 官方相连按钮组：左段点击重命名会话，右段箭头展开溢出菜单。
            var menuExpanded by remember { mutableStateOf(false) }
            val arrowRotation by animateFloatAsState(
                targetValue = if (menuExpanded) 180f else 0f,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(),
                label = "m3-topbar-menu-arrow",
            )
            EtaConnectedGroupRow {
                ToggleButton(
                    checked = false,
                    onCheckedChange = {
                        TouchHaptics.click(view)
                        onRenameCurrentConversation()
                    },
                    // weight(fill=false)：Row 先量尾段箭头按钮的固定宽度，剩余宽度才轮到
                    // 标题按钮；否则长标题会吃满整行，把箭头按钮挤出可见区域。
                    modifier = Modifier.weight(1f, fill = false),
                    buttonSize = ToggleButtonSize.Small,
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    colors = ToggleButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Text(
                        text = title,
                        // 内容色由官方 ToggleButton 提供；字号与侧栏「新建对话 / 管理聊天」
                        // 按钮文案同号（Secondary + Medium）。
                        style = etaLabelStyle(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 弹窗锚点必须落在箭头按钮自己的 Box 上：Popup 以组合位置的布局节点为锚，
                // 若挂在整行居中的标题 Box 上，菜单会掉到屏幕左侧（汉堡按钮下方）。
                // 官方 DropdownMenu 的对齐策略是 start-to-start 优先、放不下自动退到
                // end-to-end，锚在 40dp 的箭头按钮上时右缘自然与按钮右缘对齐。
                Box {
                    ChatInputNonFocusableIconButton(
                        onClick = { menuExpanded = true },
                        contentDescription = stringResource(R.string.action_more),
                        connectedShapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        containerSize = IconButtonDefaults.smallContainerSize().width,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier
                                .size(IconButtonDefaults.smallIconSize)
                                .graphicsLayer { rotationZ = arrowRotation },
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        TopBarMenuItems(
                            dismiss = { menuExpanded = false },
                            onOpenTerminal = onOpenTerminal,
                            onLaunchKimiWeb = onLaunchKimiWeb,
                            kimiWebLabel = kimiWebLabel,
                            canStopKimiWeb = canStopKimiWeb,
                            onStopKimiWeb = onStopKimiWeb,
                            onOpenBrowser = onOpenBrowser,
                            onOpenWorkspace = onOpenWorkspace,
                            autoCompressEnabled = autoCompressEnabled,
                            isCompressingContext = isCompressingContext,
                            onToggleAutoCompress = onToggleAutoCompress,
                            onCompressConversation = onCompressConversation,
                            onSearchHistory = onSearchHistory,
                            onOpenHistoryHit = onOpenHistoryHit,
                            tokenUsage = tokenUsage,
                            subAgentStatuses = subAgentStatuses,
                            canDeleteCurrentConversation = canDeleteCurrentConversation,
                            onDeleteCurrentConversation = onDeleteCurrentConversation,
                        )
                    }
                }
            }
        }
        EtaIconButton(
            icon = Icons.Rounded.AddComment,
            contentDescription = stringResource(R.string.action_new_conversation),
            onClick = {
                TouchHaptics.click(view)
                onNewConversation()
            },
            size = EtaIconSize.S,
            style = EtaButtonStyle.TonalEmphasis,
        )
    }
}
