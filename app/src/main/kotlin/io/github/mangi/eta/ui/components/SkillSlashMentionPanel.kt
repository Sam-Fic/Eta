package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.model.SkillSlashMention
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as M3Text
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SkillSlashMentionPanel(
    skills: List<SkillIndexEntry>,
    query: String?,
    selectedIds: Set<String>,
    onSelect: (SkillIndexEntry) -> Unit,
) {
    if (query == null) return
    val candidates = SkillSlashMention.candidates(skills, query, selectedIds)
    if (isMaterial3Theme()) {
        // M3 Expressive：官方 Surface 承担面板底板（官方 shapes.medium + surfaceContainerHigh），
        // 候选行改用官方可交互 ListItem——容器形状、内容间距、涟漪、Role 与禁用处理全部由官方组件提供，
        // 排版只用 MaterialTheme.typography，文字色只用官方 onSurface / onSurfaceVariant 角色。
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            val itemColors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            if (candidates.isEmpty()) {
                // 空态同样用官方 ListItem 承载，内边距取官方 ListItemDefaults.ContentPadding。
                ListItem(
                    colors = itemColors,
                    content = {
                        M3Text(
                            text = stringResource(R.string.ssm_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(candidates, key = { it.id }) { skill ->
                        ListItem(
                            onClick = { onSelect(skill) },
                            colors = itemColors,
                            content = {
                                M3Text(
                                    text = skill.name.ifBlank { skill.id },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                M3Text(
                                    text = "/skill:${skill.id} · ${skill.description.ifBlank { stringResource(R.string.ssm_no_description) }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (candidates.isEmpty()) {
            Text(
                text = stringResource(R.string.ssm_no_match),
                modifier = Modifier.padding(12.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(candidates, key = { it.id }) { skill ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(skill) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = skill.name.ifBlank { skill.id },
                            style = MiuixTheme.textStyles.body1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "/skill:${skill.id} · ${skill.description.ifBlank { stringResource(R.string.ssm_no_description) }}",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
