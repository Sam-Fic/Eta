package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.EtaSplitButton
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.MessageSearchHit

/**
 * 首页顶栏（Material 3 Expressive）。
 *
 * 一行三件套：菜单色调图标按钮、会话标题色调拆分按钮（右侧箭头展开原应用的溢出菜单）、
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
) {
    val view = LocalView.current
    Surface(color = MaterialTheme.colorScheme.surface) {
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
                style = EtaButtonStyle.Tonal,
            )
            // 会话名按钮在两侧图标之间的剩余空间里居中（与官方 CenterAlignedTopAppBar 的标题布局一致）。
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EtaSplitButton(
                    modifier = Modifier,
                    height = SplitButtonDefaults.SmallContainerHeight,
                    startContent = {
                        Text(
                            text = title,
                            // 内容色由官方按钮组件（tonal → onSecondaryContainer）提供。
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onStartClick = {
                        TouchHaptics.click(view)
                        onRenameCurrentConversation()
                    },
                    endIcon = Icons.Rounded.KeyboardArrowDown,
                    endContentDescription = stringResource(R.string.action_more),
                    style = EtaButtonStyle.Tonal,
                    menu = { dismiss ->
                        TopBarMenuItems(
                            dismiss = dismiss,
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
                        )
                    },
                )
            }
            EtaIconButton(
                icon = Icons.Rounded.AddComment,
                contentDescription = stringResource(R.string.action_new_conversation),
                onClick = {
                    TouchHaptics.click(view)
                    onNewConversation()
                },
                size = EtaIconSize.S,
                style = EtaButtonStyle.Tonal,
            )
        }
    }
}
