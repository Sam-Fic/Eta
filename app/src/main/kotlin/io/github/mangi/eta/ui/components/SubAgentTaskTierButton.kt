package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.ui.haptics.TouchHaptics

/** 统一风格的选择控件：官方 ListItem 承载容器、内外边距、排版、涟漪与禁用态。 */
@Composable
internal fun SubAgentChoiceField(
    label: String,
    value: String,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 官方 ListItem(onClick) 承担容器、内外边距（ListItemDefaults.ContentPadding）、
    // 主/辅助文本排版、涟漪与禁用态；形状/配色/海拔全部用官方默认值，不传 shape/colors。
    // 注意：可交互重载的正文槽位参数名是 `content`（不是 `headlineContent`，
    // 后者只存在于无 onClick 的纯展示重载）。
    // 不额外挂 semantics contentDescription：ListItem 内部已是 semantics(mergeDescendants = true)，
    // ContentDescription(父) 与 Text(子) 是不同 SemanticsPropertyKey，TalkBack 会两串都念。
    // description = "设置" + label，与子文本里 label 那一行重叠；去掉重叠段只剩一个「设置」，
    // 单独作为播报没有任何信息量，所以整条都不挂，档位以官方合并的正文为准。
    ListItem(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        content = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = { Icon(Icons.Rounded.ExpandMore, contentDescription = null) },
    )
}

/** 紧凑菜单：宽度贴合最长标签、带描边、无投影。 */
@Composable
internal fun SubAgentDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 官方 DropdownMenu 默认容器/形状/海拔/描边（MenuDefaults），不覆盖任何一项。
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
    ) {
        Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 96.dp, max = 220.dp), content = content)
    }
}

/** 选中态是官方 SelectableDropdownMenuItem 的背景 + 无障碍单选语义，不画对勾图标。 */
@Composable
internal fun SubAgentSelectionItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    // 官方 SelectableDropdownMenuItem 承担选中背景、涟漪、Role.RadioButton 语义与按压形变；
    // 前导槽位留空，选中态不画对勾图标。
    SelectableDropdownMenuItem(
        selected = selected,
        onClick = { TouchHaptics.click(view); onClick() },
        shapes = MenuDefaults.itemShapes(),
        // 空的前导槽位：只保留官方选中背景与单选语义，不画对勾图标。
        selectedLeadingIcon = {},
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
internal fun SubAgentTaskTierButton(
    label: String,
    tier: SubAgentTaskTier?,
    enabled: Boolean,
    onTierSelected: (SubAgentTaskTier) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val view = LocalView.current
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSelection by rememberUpdatedState(onTierSelected)
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(modifier) {
        if (compact) {
            // 官方 AssistChip 承担容器高度、形状、描边、涟漪、标签排版与禁用态；
            // 尾部 chevron 走官方 trailingIcon 槽位，图标尺寸用官方 AssistChipDefaults.IconSize。
            AssistChip(
                onClick = { if (latestEnabled) { TouchHaptics.click(view); expanded = !expanded } },
                modifier = Modifier
                    .widthIn(max = 108.dp)
                    // 触摸下限交给官方 Modifier.minimumInteractiveComponentSize()（读 LocalMinimumInteractiveComponentSize，默认 48.dp）。
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = context.getString(R.string.stt_desc_set_for, label) },
                enabled = enabled,
                label = {
                    Text(
                        text = tier?.label ?: stringResource(R.string.stt_set_short),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = { Icon(Icons.Rounded.ExpandMore, contentDescription = null) },
            )
        } else SubAgentChoiceField(label, tier?.label ?: stringResource(R.string.stt_unset), enabled, if (label == "任务分工") stringResource(R.string.stt_set_task_tier) else stringResource(R.string.stt_desc_set_for, label),
            onClick = { if (latestEnabled) { TouchHaptics.click(view); expanded = !expanded } })
        if (enabled) SubAgentDropdownMenu(
            expanded, { expanded = false }, Modifier.selectableGroup(),
            offset = if (compact) DpOffset(0.dp, (-(32 * SubAgentTaskTier.entries.size + 10)).dp) else DpOffset.Zero,
        ) {
            SubAgentTaskTier.entries.forEach { option ->
                SubAgentSelectionItem(option.label, option == tier) {
                    if (latestEnabled) {
                        latestSelection(option)
                        expanded = false
                    }
                }
            }
        }
    }
}
