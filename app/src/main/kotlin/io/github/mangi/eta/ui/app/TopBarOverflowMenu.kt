package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.config.InteractiveModePreference
import io.github.mangi.eta.ui.CompressConversationDialog
import io.github.mangi.eta.ui.components.EtaDropdownMenu
import io.github.mangi.eta.ui.components.rememberEtaMenuState
import io.github.mangi.eta.ui.components.rememberInteractiveModeEnabled
import io.github.mangi.eta.ui.haptics.TouchHaptics

private val TopBarMenuIconSize = 24.dp
private val CompactMenuItemModifier = Modifier.height(40.dp)
private val CompactMenuItemPadding = PaddingValues(horizontal = 12.dp)

/**
 * 顶栏溢出菜单（more 图标 + 菜单）。
 *
 * 菜单项本身由 [TopBarMenuItems] 提供，首页的 M3 Expressive 拆分按钮菜单复用同一套条目。
 */
@Composable
internal fun TopBarOverflowMenu(
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenVoiceSettings: () -> Unit = {},
    autoCompressEnabled: Boolean,
    isCompressingContext: Boolean = false,
    onToggleAutoCompress: (Boolean) -> Unit,
    onCompressConversation: (
        providerId: String?,
        modelId: String?,
        onFinished: (Boolean) -> Unit,
    ) -> Unit = { _, _, done -> done(false) },
    onOpenSearchHistory: () -> Unit = {},
    onOpenTokenUsage: () -> Unit = {},
    onOpenSubAgentStatus: () -> Unit = {},
    canDeleteCurrentConversation: Boolean = false,
    onDeleteCurrentConversation: () -> Unit = {},
) {
    val menuState = rememberEtaMenuState()
    val view = LocalView.current

    Box {
        IconButton(
            onClick = {
                TouchHaptics.click(view)
                onRefreshKimiWeb()
                menuState.onAnchorClick()
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.action_more),
            )
        }

        EtaDropdownMenu(
            expanded = menuState.expanded,
            onDismissRequest = menuState::dismiss,
            offset = DpOffset(x = (-2).dp, y = 10.dp),
            alignEnd = true,
        ) {
            TopBarMenuItems(
                dismiss = menuState::dismiss,
                onOpenTerminal = onOpenTerminal,
                onLaunchKimiWeb = onLaunchKimiWeb,
                kimiWebLabel = kimiWebLabel,
                canStopKimiWeb = canStopKimiWeb,
                onStopKimiWeb = onStopKimiWeb,
                onOpenBrowser = onOpenBrowser,
                onOpenWorkspace = onOpenWorkspace,
                onOpenVoiceSettings = onOpenVoiceSettings,
                autoCompressEnabled = autoCompressEnabled,
                isCompressingContext = isCompressingContext,
                onToggleAutoCompress = onToggleAutoCompress,
                onCompressConversation = onCompressConversation,
                onOpenSearchHistory = onOpenSearchHistory,
                onOpenTokenUsage = onOpenTokenUsage,
                onOpenSubAgentStatus = onOpenSubAgentStatus,
                canDeleteCurrentConversation = canDeleteCurrentConversation,
                onDeleteCurrentConversation = onDeleteCurrentConversation,
            )
        }
    }
}

/**
 * 顶栏菜单项集合（不含触发按钮与菜单容器），供普通溢出菜单与首页 M3 拆分按钮共用。
 *
 * @param dismiss 关闭外层菜单容器。
 */
@Composable
internal fun TopBarMenuItems(
    dismiss: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    autoCompressEnabled: Boolean,
    isCompressingContext: Boolean = false,
    onToggleAutoCompress: (Boolean) -> Unit,
    onCompressConversation: (
        providerId: String?,
        modelId: String?,
        onFinished: (Boolean) -> Unit,
    ) -> Unit = { _, _, done -> done(false) },
    onOpenSearchHistory: () -> Unit = {},
    onOpenTokenUsage: () -> Unit = {},
    onOpenSubAgentStatus: () -> Unit = {},
    canDeleteCurrentConversation: Boolean = false,
    onDeleteCurrentConversation: () -> Unit = {},
) {
    val interactiveModePreference = remember { InteractiveModePreference() }
    val interactiveModeEnabled by rememberInteractiveModeEnabled(interactiveModePreference)
    val view = LocalView.current
    var showCompressDialog by remember { mutableStateOf(false) }

    DropdownMenuItem(
        modifier = CompactMenuItemModifier,
        contentPadding = CompactMenuItemPadding,
        text = { Text(stringResource(R.string.action_search_history)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        onClick = { TouchHaptics.click(view); dismiss(); onOpenSearchHistory() },
    )
    // 语音设置快捷入口：识别 / 朗读 / 语音对话都在一页，顶栏一步直达，不必翻设置列表。
    DropdownMenuItem(
        modifier = CompactMenuItemModifier,
        contentPadding = CompactMenuItemPadding,
        text = { Text(stringResource(R.string.action_voice_settings)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.RecordVoiceOver,
                contentDescription = null,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        onClick = { TouchHaptics.click(view); dismiss(); onOpenVoiceSettings() },
    )
    MenuSectionDivider()
    DropdownMenuItem(
        modifier = CompactMenuItemModifier.testTag("top-bar-interactive-mode-row"),
        contentPadding = CompactMenuItemPadding,
        text = { Text(stringResource(R.string.action_interactive_mode)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        trailingIcon = {
            Switch(
                checked = interactiveModeEnabled,
                onCheckedChange = { enabled ->
                    TouchHaptics.click(view)
                    interactiveModePreference.setEnabled(enabled)
                },
                modifier = Modifier.scale(0.72f).testTag("top-bar-interactive-mode-switch"),
            )
        },
        onClick = {
            TouchHaptics.click(view)
            interactiveModePreference.setEnabled(!interactiveModeEnabled)
        },
    )
    MenuSectionDivider()
    DropdownMenuItem(
        modifier = CompactMenuItemModifier,
        contentPadding = CompactMenuItemPadding,
        text = { Text(stringResource(R.string.action_open_terminal)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Terminal,
                contentDescription = null,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        onClick = { TouchHaptics.click(view); dismiss(); onOpenTerminal() },
    )
    DropdownMenuItem(
        modifier = CompactMenuItemModifier,
        contentPadding = CompactMenuItemPadding,
        text = { Text(stringResource(R.string.action_open_browser)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        onClick = { TouchHaptics.click(view); dismiss(); onOpenBrowser() },
    )
    DropdownMenuItem(
        modifier = CompactMenuItemModifier,
        contentPadding = CompactMenuItemPadding,
        text = { Text(stringResource(R.string.action_browse_workspace_files)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        onClick = { TouchHaptics.click(view); dismiss(); onOpenWorkspace() },
    )
    DropdownMenuItem(
        modifier = CompactMenuItemModifier,
        contentPadding = CompactMenuItemPadding,
        text = { Text(stringResource(R.string.action_sub_agent_status)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.AccountTree,
                contentDescription = null,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        onClick = { TouchHaptics.click(view); dismiss(); onOpenSubAgentStatus() },
    )
    DropdownMenuItem(
        modifier = CompactMenuItemModifier,
        contentPadding = CompactMenuItemPadding,
        text = { Text(kimiWebLabel) },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_kimi_code),
                contentDescription = null,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        onClick = { TouchHaptics.click(view); dismiss(); onLaunchKimiWeb() },
    )
    MenuSectionDivider()
    DropdownMenuItem(
        modifier = CompactMenuItemModifier,
        contentPadding = CompactMenuItemPadding,
        text = { Text(stringResource(R.string.action_token_usage)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.DataUsage,
                contentDescription = null,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        onClick = { TouchHaptics.click(view); dismiss(); onOpenTokenUsage() },
    )
    DropdownMenuItem(
        modifier = CompactMenuItemModifier,
        contentPadding = CompactMenuItemPadding,
        enabled = !isCompressingContext,
        text = { Text(stringResource(R.string.action_compress_conversation)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Summarize,
                contentDescription = null,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        onClick = {
            TouchHaptics.click(view)
            dismiss()
            showCompressDialog = true
        },
    )
    DropdownMenuItem(
        modifier = CompactMenuItemModifier,
        contentPadding = CompactMenuItemPadding,
        text = { Text(stringResource(R.string.action_auto_compress_context)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Compress,
                contentDescription = null,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        trailingIcon = {
            Switch(
                checked = autoCompressEnabled,
                onCheckedChange = null,
                modifier = Modifier.scale(0.72f),
            )
        },
        onClick = {
            TouchHaptics.click(view)
            onToggleAutoCompress(!autoCompressEnabled)
        },
    )
    MenuSectionDivider()
    DropdownMenuItem(
        modifier = CompactMenuItemModifier,
        contentPadding = CompactMenuItemPadding,
        enabled = canDeleteCurrentConversation,
        text = {
            Text(
                stringResource(R.string.action_delete_current_conversation),
                color = MaterialTheme.colorScheme.error,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(TopBarMenuIconSize),
            )
        },
        onClick = { TouchHaptics.click(view); dismiss(); onDeleteCurrentConversation() },
    )
    if (canStopKimiWeb) {
        MenuSectionDivider()
        DropdownMenuItem(
            modifier = CompactMenuItemModifier,
            contentPadding = CompactMenuItemPadding,
            text = { Text(stringResource(R.string.capability_kimi_stop)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(TopBarMenuIconSize),
                )
            },
            onClick = { TouchHaptics.click(view); dismiss(); onStopKimiWeb() },
        )
    }

    CompressConversationDialog(
        show = showCompressDialog,
        isCompressing = isCompressingContext,
        onDismiss = { showCompressDialog = false },
        onConfirm = onCompressConversation,
    )
}

@Composable
private fun MenuSectionDivider() {
    // 官方 HorizontalDivider 默认取 outlineVariant，这里保持官方默认，不再自绘透明度。
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
