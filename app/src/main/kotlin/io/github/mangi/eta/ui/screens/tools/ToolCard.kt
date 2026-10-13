package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.etaOnSurfaceVariant
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.actualToolName
import io.github.mangi.eta.ui.model.toolCardAction
import io.github.mangi.eta.ui.model.toolCardRequirement
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.Text as M3Text
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun ToolCard(
    tool: ToolItemUi,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDescription by remember(tool.id) { mutableStateOf(false) }
    val description = if (!rootGranted && tool.id == "terminal") {
        stringResource(R.string.capability_terminal_ordinary_summary)
    } else {
        tool.summary
    }
    val requirementText = toolRequirementText(tool.id, rootGranted, capabilities)
    val action = toolCardAction(tool.id, capabilities)
    val actionText = when (action) {
        AgentToolsAction.OpenBrowser -> stringResource(R.string.action_open_browser)
        AgentToolsAction.OpenPermissions -> stringResource(R.string.tools_manage_permissions)
        AgentToolsAction.OpenEnhancements -> stringResource(R.string.tools_view_enhancements)
        else -> stringResource(R.string.ui_view_description)
    }
    val secondaryColor = etaOnSurfaceVariant()
    val accentColor = if (isMaterial3Theme()) {
        androidx.compose.material3.MaterialTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.primary
    }
    val titleText = tool.title
    val bodyText = description
    val requirement = requirementText
    val actionLabel = actionText
    val infoDescription = stringResource(R.string.ui_description_named, tool.title)
    val toolIcon = iconForTool(tool.id)
    val openInfo = { showDescription = true }
    val cardBody: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isMaterial3Theme()) {
                Icon(
                    imageVector = toolIcon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Icon(
                    imageVector = toolIcon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceContainer,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (action != null) {
                if (isMaterial3Theme()) {
                    M3IconButton(onClick = openInfo) {
                        Icon(imageVector = Icons.Rounded.Info, contentDescription = infoDescription)
                    }
                } else {
                    IconButton(onClick = openInfo) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = infoDescription,
                            modifier = Modifier.size(18.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (isMaterial3Theme()) {
            M3Text(
                text = titleText,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            M3Text(
                text = bodyText,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = secondaryColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            requirement?.let {
                Spacer(modifier = Modifier.height(8.dp))
                EtaText(text = it, style = EtaTextStyle.Footnote, color = secondaryColor)
            }
        } else {
            Text(
                text = titleText,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = bodyText,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            requirement?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = actionLabel,
                style = MiuixTheme.textStyles.footnote1,
                color = if (action != null) accentColor else secondaryColor,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accentColor,
                )
            }
        }
    }

    // M3 下改用官方可点击 Card（自带涟漪、语义与按压形变），容器色与形状全部走官方默认值。
    if (isMaterial3Theme()) {
        M3Card(
            onClick = { if (action != null) onAction(action) else showDescription = true },
            modifier = modifier.heightIn(min = 136.dp),
        ) {
            cardBody()
        }
    } else {
        Card(
            modifier = modifier.heightIn(min = 136.dp),
            insideMargin = PaddingValues(16.dp),
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surfaceContainer,
                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            ),
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true,
            onClick = {
                if (action != null) onAction(action) else showDescription = true
            },
        ) {
            cardBody()
        }
    }
    if (showDescription) {
        ItemDescriptionDialog(
            title = tool.title,
            description = listOfNotNull(description, requirementText).joinToString("\n\n"),
            onDismiss = { showDescription = false },
        )
    }
}

@Composable
private fun toolRequirementText(id: String, rootGranted: Boolean, capabilities: AgentToolCapabilities): String? {
    val requirement = toolCardRequirement(id)
    val unavailableCode = capabilities.unavailableCode(actualToolName(id))
    return when {
        !rootGranted && requirement.rootRequirement == RootRequirement.REQUIRED -> stringResource(R.string.capability_root_required)
        !capabilities.accessibilityAvailable && requirement.accessibility -> stringResource(R.string.capability_accessibility_required)
        unavailableCode == "NOTIFICATION_ACCESS_REQUIRED" -> stringResource(R.string.capability_notification_access_required)
        unavailableCode == "APP_USAGE_ACCESS_REQUIRED" -> stringResource(R.string.capability_usage_access_required)
        unavailableCode == "LOCATION_PERMISSION_REQUIRED" -> stringResource(R.string.capability_location_access_required)
        requirement.colorOs -> stringResource(R.string.capability_coloros_required)
        !rootGranted && requirement.rootRequirement == RootRequirement.PARTIAL -> stringResource(R.string.capability_root_partial)
        else -> null
    }
}
