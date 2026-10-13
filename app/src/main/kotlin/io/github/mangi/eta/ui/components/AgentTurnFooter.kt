package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.etaPrimary
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.markdown.NumericCitationMarkup
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import kotlinx.coroutines.delay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox as M3TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Footer icon button.
 *
 * miuix 分支保留原实现：它复刻 miuix IconButton 的外观与行为，却不去建那层无条件离屏 squircle
 * 遮罩（透明背景时该遮罩只裁剪不可见像素，却让每个节点每帧多一次离屏合成）。
 * 命中区域、role、可用性与图标不变；禁用按钮仍是 clickable-disabled 且不变暗。
 *
 * Material 3 Expressive 分支改用官方 `IconButton`：容器尺寸取官方
 * `IconButtonDefaults.extraSmallContainerSize()`，形状、涟漪与最小可点区域由官方组件提供。
 */
@Composable
private fun FooterIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (isMaterial3Theme()) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
            enabled = enabled,
            content = content,
        )
        return
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 30.dp, minHeight = 30.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * 官方 M3 Expressive 提示气泡：`TooltipBox` + `PlainTooltip` + `rememberTooltipState`，
 * 位置与配色全部取官方 `TooltipDefaults`，不自定义尺寸或颜色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FooterTooltipBox(
    text: String,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!isMaterial3Theme()) {
        TooltipBox(text = text, enabled = enabled) { content() }
        return
    }
    val state = rememberTooltipState()
    M3TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { M3Text(text = text, style = MaterialTheme.typography.labelMedium) } },
        state = state,
        enableUserInput = enabled,
        content = content,
    )
}

/** Renders the original projection owner; never manufactures a callback message. */
@Composable
internal fun AgentTurnFooter(
    message: AgentChatMessageUi,
    actions: ChatMessageActions,
    revealPending: Boolean,
    messageActionsEnabled: Boolean,
    branchEnabled: Boolean,
    speechPreface: String = "",
    isRunActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (revealPending || isRunActive) return
    val content = when (message) {
        is AgentMessageUi -> {
            if (message.isStreaming || message.content.isBlank()) return
            message.content
        }
        is io.github.mangi.eta.ui.model.ErrorReconnectMessageUi -> errorReconnectLabel(message)
        is SystemNoticeMessageUi -> {
            val label = stringResource(when (message.code) {
                SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                SystemNoticeCode.Completed -> R.string.system_notice_completed
            })
            if (message.code == SystemNoticeCode.RuntimeFailed) label
            else listOfNotNull(label, message.detail?.takeIf(String::isNotBlank)).joinToString("\n\n")
        }
        else -> return
    }
    AgentMessageActionRow(
        messageId = message.id,
        content = content,
        allowSpeech = message is AgentMessageUi,
        speechPreface = speechPreface,
        generatedAtMillis = (message as? AgentMessageUi)?.generatedAtMillis,
        showMessageActions = true,
        messageActionsEnabled = messageActionsEnabled,
        branchEnabled = branchEnabled,
        onDelete = { actions.onDeleteMessage(message.id) },
        onRegenerate = { actions.onRegenerateMessage(message.id) },
        onBranch = { actions.onBranchMessage(message.id) },
        modifier = modifier
            .testTag("turn-footer:${message.id}")
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Shared by detached turn footers and standalone message rendering. */
@Composable
internal fun AgentMessageActionRow(
    messageId: String,
    content: String,
    allowSpeech: Boolean,
    speechPreface: String,
    generatedAtMillis: Long?,
    showMessageActions: Boolean,
    messageActionsEnabled: Boolean,
    branchEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
    modifier: Modifier = Modifier,
    showCopyAction: Boolean = true,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val view = LocalView.current
    var copied by remember(messageId) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    val speechContent = remember(speechPreface, content) {
        listOf(NumericCitationMarkup.strip(speechPreface).trim(), NumericCitationMarkup.strip(content))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }
    val material3 = isMaterial3Theme()
    // 跨主题族取色：M3 走官方 primary / onSurfaceVariant 角色，miuix 保持原有取值。
    val accentColor = etaPrimary()
    val mutedColor = if (material3) MaterialTheme.colorScheme.onSurfaceVariant
        else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
    // 图标尺寸取官方小号图标按钮的图标令牌（extraSmallIconSize = 20dp）。
    val actionIconSize = IconButtonDefaults.extraSmallIconSize
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCopyAction) FooterIconButton(
            onClick = {
                TouchHaptics.click(view)
                @Suppress("DEPRECATION")
                clipboardManager.setText(AnnotatedString(content))
                copied = true
            },
            enabled = true,
        ) {
            Icon(
                imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = stringResource(if (copied) R.string.copy_copied else R.string.copy_answer),
                modifier = Modifier.size(if (material3) actionIconSize else 16.dp),
                tint = if (copied) accentColor else mutedColor,
            )
        }
        if (allowSpeech) SpeechPlaybackButton(messageId, speechContent)
        if (showMessageActions) {
            FooterTooltipBox(stringResource(R.string.ui_branch_conversation), branchEnabled) {
                FooterIconButton(
                    onClick = { TouchHaptics.click(view); onBranch() },
                    enabled = branchEnabled,
                        ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.CallSplit,
                        contentDescription = stringResource(R.string.ui_branch_conversation),
                        modifier = Modifier.size(if (material3) actionIconSize else 16.dp),
                        tint = mutedColor,
                    )
                }
            }
            FooterTooltipBox(stringResource(R.string.ui_regenerate_2e1905), messageActionsEnabled) {
                FooterIconButton(
                    onClick = { TouchHaptics.click(view); onRegenerate() },
                    enabled = messageActionsEnabled,
                        ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.ui_regenerate_reply_84a7d9),
                        modifier = Modifier.size(if (material3) actionIconSize else 16.dp),
                        tint = mutedColor,
                    )
                }
            }
            FooterTooltipBox(stringResource(R.string.ui_delete_3755f5), messageActionsEnabled) {
                FooterIconButton(
                    onClick = { TouchHaptics.click(view); onDelete() },
                    enabled = messageActionsEnabled,
                        ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.ui_delete_this_conversation_3f351b),
                        modifier = Modifier.size(if (material3) actionIconSize else 16.dp),
                        tint = mutedColor,
                    )
                }
            }
        }
        if (LocalAppearanceSettings.current.messageTimestampsEnabled) {
            val timestamp = generatedAtMillis?.takeIf { it > 0L }
            if (timestamp != null) {
                val zone = java.time.ZoneId.systemDefault()
                val label = remember(timestamp, zone) { formatMessageTimestamp(timestamp, zone) }
                if (material3) {
                    M3Text(
                        text = label,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
