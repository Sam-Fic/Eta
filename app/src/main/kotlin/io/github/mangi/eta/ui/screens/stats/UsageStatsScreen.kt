package io.github.mangi.eta.ui.screens.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.ModelUsageModelUi
import io.github.mangi.eta.data.repository.ModelUsageSnapshot
import io.github.mangi.eta.data.repository.UsageStatsRepository
import io.github.mangi.eta.data.repository.UsageStatsSnapshot
import io.github.mangi.eta.data.repository.formatStatCount
import io.github.mangi.eta.data.repository.formatTokenCount
import io.github.mangi.eta.data.repository.heatmapAlpha
import io.github.mangi.eta.data.repository.heatmapQuartiles
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.haptics.TouchHaptics
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.material3.Text as M3Text

private enum class UsageStatsTab { Overview, Models }

@Composable
internal fun UsageStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf(UsageStatsSnapshot(isLoading = true)) }
    var selectedTab by remember { mutableStateOf(UsageStatsTab.Overview) }

    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) {
            runCatching { UsageStatsRepository.load(context) }
                .getOrElse { UsageStatsSnapshot(isLoading = false) }
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.stats_page_title),
        onBack = onBack,
    ) {
        item(key = "tabs") {
            UsageStatsTabs(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            )
        }
        if (stats.isLoading) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isMaterial3Theme()) {
                        CircularProgressIndicator()
                    } else {
                        InfiniteProgressIndicator()
                    }
                }
            }
        } else if (selectedTab == UsageStatsTab.Models) {
            item(key = "model-usage") {
                ModelUsagePane(
                    usage = stats.modelUsage,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                )
            }
        } else {
            item(key = "heatmap") {
                EtaCard(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatsTitle(text = stringResource(R.string.stats_page_heatmap_title))
                        ChatHeatmap(conversationsPerDay = stats.conversationsPerDay)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatsLabel(
                                text = stringResource(R.string.stats_page_heatmap_less),
                                tier = StatsTextTier.Legend,
                                maxLines = 1,
                            )
                            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                                HeatmapCell(alpha = alpha, sizeDp = 10)
                            }
                            StatsLabel(
                                text = stringResource(R.string.stats_page_heatmap_more),
                                tier = StatsTextTier.Legend,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            item(key = "grid") {
                StatsGrid(
                    stats = stats,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

/**
 * 概览 / 模型视图切换。
 *
 * Material 3 走官方单选分段按钮 [SingleChoiceSegmentedButtonRow] + [SegmentedButton]
 * （形状取官方 [SegmentedButtonDefaults]，配色/行高/字阶均使用官方默认值）。
 */
@Composable
private fun UsageStatsTabs(
    selected: UsageStatsTab,
    onSelect: (UsageStatsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        stringResource(R.string.stats_tab_overview) to UsageStatsTab.Overview,
        stringResource(R.string.stats_tab_models) to UsageStatsTab.Models,
    )
    if (isMaterial3Theme()) {
        SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, (label, tab) ->
                SegmentedButton(
                    selected = selected == tab,
                    onClick = { onSelect(tab) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                ) {
                    M3Text(text = label)
                }
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { (label, tab) ->
                UsageTabButton(
                    label = label,
                    selected = selected == tab,
                    onClick = { onSelect(tab) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.UsageTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primary else colors.primaryContainer)
            .clickable {
                TouchHaptics.click(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = if (selected) colors.onPrimary else colors.onPrimaryContainer,
        )
    }
}

@Composable
private fun StatsTitle(text: String) {
    if (isMaterial3Theme()) {
        M3Text(text = text, style = MaterialTheme.typography.titleMedium)
    } else {
        Text(text = text, style = MiuixTheme.textStyles.headline1)
    }
}

/** 说明文字档位：图例 / 次要说明 / 主要数值。 */
private enum class StatsTextTier { Legend, Secondary, Primary }

/**
 * 统计页的说明文字。
 *
 * Material 3 走官方字阶（labelMedium / bodyMedium / bodyLarge）与官方颜色角色；
 * miuix 主题族保留原来的 footnote2 / body2 / body1 字阶，不共用一套。
 */
@Composable
private fun StatsLabel(
    text: String,
    modifier: Modifier = Modifier,
    tier: StatsTextTier = StatsTextTier.Secondary,
    maxLines: Int = Int.MAX_VALUE,
) {
    if (isMaterial3Theme()) {
        M3Text(
            text = text,
            modifier = modifier,
            style = when (tier) {
                StatsTextTier.Legend -> MaterialTheme.typography.labelMedium
                StatsTextTier.Secondary -> MaterialTheme.typography.bodyMedium
                StatsTextTier.Primary -> MaterialTheme.typography.bodyLarge
            },
            color = when (tier) {
                StatsTextTier.Legend -> MaterialTheme.colorScheme.onSurfaceVariant
                StatsTextTier.Secondary -> MaterialTheme.colorScheme.onSurfaceVariant
                StatsTextTier.Primary -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Text(
            text = text,
            modifier = modifier,
            style = when (tier) {
                StatsTextTier.Legend -> MiuixTheme.textStyles.footnote2
                StatsTextTier.Secondary -> MiuixTheme.textStyles.body2
                StatsTextTier.Primary -> MiuixTheme.textStyles.body1
            },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ModelUsagePane(
    usage: ModelUsageSnapshot,
    modifier: Modifier = Modifier,
) {
    var expandedModels by remember { mutableStateOf(emptySet<String>()) }
    var startBound by remember { mutableStateOf(UsageTimeBound()) }
    var endBound by remember { mutableStateOf(UsageTimeBound()) }
    val filtered = remember(usage, startBound, endBound) {
        usage.filtered(startBound.toMillis(endOfBound = false), endBound.toMillis(endOfBound = true))
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModelUsageFilterCard(
            start = startBound,
            end = endBound,
            onStartChange = { startBound = it },
            onEndChange = { endBound = it },
            onClear = {
                startBound = UsageTimeBound()
                endBound = UsageTimeBound()
            },
        )
        EtaCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatsTitle(text = stringResource(R.string.stats_model_total_title))
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_token_total),
                    value = formatTokenCount(filtered.totalTokens),
                )
                ModelMetricRow(
                    // The value excludes cache reads and writes. The hit rate divides by the
                    // full prompt, so it is not labelled "input".
                    label = stringResource(R.string.stats_page_fresh_input_tokens),
                    value = formatTokenCount(filtered.totalFreshInputTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokenCount(filtered.totalOutputTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = formatTokenCount(filtered.totalCachedTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_cache_creation_tokens),
                    value = formatTokenCount(filtered.totalCacheCreationTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_cache_hit_rate),
                    value = formatCacheHitRate(filtered.totalCachedTokens, filtered.totalInputTokens),
                )
            }
        }
        if (filtered.providers.isEmpty()) {
            StatsLabel(
                text = stringResource(
                    if (startBound.isSet || endBound.isSet) {
                        R.string.stats_model_filter_empty
                    } else {
                        R.string.stats_model_empty
                    },
                ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        } else {
            filtered.providers.forEach { provider ->
                EtaCard {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isMaterial3Theme()) {
                                M3Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Text(
                                    text = provider.name,
                                    style = MiuixTheme.textStyles.title3,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        provider.models.forEach { model ->
                            val key = "${provider.id}/${model.id}"
                            val expanded = key in expandedModels
                            ModelUsageRow(
                                model = model,
                                expanded = expanded,
                                onToggle = {
                                    expandedModels = if (expanded) {
                                        expandedModels - key
                                    } else {
                                        expandedModels + key
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class UsageBoundSide { Start, End }

@Composable
private fun ModelUsageFilterCard(
    start: UsageTimeBound,
    end: UsageTimeBound,
    onStartChange: (UsageTimeBound) -> Unit,
    onEndChange: (UsageTimeBound) -> Unit,
    onClear: () -> Unit,
) {
    val view = LocalView.current
    var expanded by remember { mutableStateOf(false) }
    var pickerSide by remember { mutableStateOf<UsageBoundSide?>(null) }
    var selectedPreset by remember { mutableStateOf<UsageFilterPreset?>(null) }
    val matchedPreset = remember(start, end, selectedPreset) {
        matchingUsageFilterPreset(start, end, LocalDate.now(), preferred = selectedPreset)
    }
    val summary = filterSummaryText(start, end, matchedPreset)
        ?: stringResource(R.string.stats_model_filter_collapsed)
    val titleText = stringResource(R.string.stats_model_filter_title)
    val expandIcon = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight
    EtaCard {
        Column {
            if (isMaterial3Theme()) {
                // M3：官方可点击 ListItem 承担展开/收起，替掉「Row + clickable(无涟漪)」。
                val supportingText = summary
                val supporting: (@Composable () -> Unit)? = if (supportingText != null) {
                    {
                        M3Text(
                            text = supportingText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                } else {
                    null
                }
                ListItem(
                    onClick = {
                        TouchHaptics.click(view)
                        expanded = !expanded
                    },
                    modifier = Modifier.fillMaxWidth(),
                    content = { M3Text(text = titleText, style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = supporting,
                    trailingContent = {
                        Icon(
                            imageVector = expandIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            TouchHaptics.click(view)
                            expanded = !expanded
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = titleText,
                        style = MiuixTheme.textStyles.headline1,
                    )
                    Text(
                        text = summary.orEmpty(),
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 1,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp, end = 4.dp),
                    )
                    Icon(
                        imageVector = expandIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UsageBoundRow(
                        label = stringResource(R.string.stats_model_filter_start),
                        bound = start,
                        onClick = {
                            TouchHaptics.click(view)
                            pickerSide = UsageBoundSide.Start
                        },
                    )
                    UsageBoundRow(
                        label = stringResource(R.string.stats_model_filter_end),
                        bound = end,
                        onClick = {
                            TouchHaptics.click(view)
                            pickerSide = UsageBoundSide.End
                        },
                    )
                    UsageFilterPresetRow(
                        selected = matchedPreset,
                        onSelect = { preset ->
                            TouchHaptics.click(view)
                            selectedPreset = preset
                            val (from, to) = usageFilterPresetRange(preset, LocalDate.now())
                            onStartChange(UsageTimeBound.from(from))
                            onEndChange(UsageTimeBound.from(to))
                        },
                    )
                    if (start.isSet || end.isSet) {
                        if (isMaterial3Theme()) {
                            M3TextButton(
                                onClick = {
                                    TouchHaptics.click(view)
                                    selectedPreset = null
                                    onClear()
                                },
                            ) {
                                M3Text(text = stringResource(R.string.stats_model_filter_clear))
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.stats_model_filter_clear),
                                style = MiuixTheme.textStyles.body2,
                                modifier = Modifier
                                    .clickable {
                                        TouchHaptics.click(view)
                                        selectedPreset = null
                                        onClear()
                                    }
                                    .padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    UsageDateTimePickerDialog(
        show = pickerSide != null,
        title = stringResource(R.string.stats_model_filter_datetime_title),
        current = if (pickerSide == UsageBoundSide.End) end else start,
        endOfBound = pickerSide == UsageBoundSide.End,
        onDismiss = { pickerSide = null },
        onConfirm = { picked ->
            selectedPreset = null
            if (pickerSide == UsageBoundSide.End) onEndChange(picked) else onStartChange(picked)
            pickerSide = null
        },
        onClear = {
            selectedPreset = null
            if (pickerSide == UsageBoundSide.End) onEndChange(UsageTimeBound()) else onStartChange(UsageTimeBound())
            pickerSide = null
        },
    )
}

@Composable
private fun UsageBoundRow(
    label: String,
    bound: UsageTimeBound,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsLabel(text = label)
        UsageBoundChip(
            text = formatUsageDate(bound)
                ?: stringResource(R.string.stats_model_filter_datetime_placeholder),
            modifier = Modifier.fillMaxWidth(),
            fullWidth = true,
            onClick = onClick,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageFilterPresetRow(
    selected: UsageFilterPreset?,
    onSelect: (UsageFilterPreset) -> Unit,
) {
    val items = listOf(
        UsageFilterPreset.Today to R.string.stats_model_filter_preset_today,
        UsageFilterPreset.Last7Days to R.string.stats_model_filter_preset_7d,
        UsageFilterPreset.ThisWeek to R.string.stats_model_filter_preset_week,
        UsageFilterPreset.Last30Days to R.string.stats_model_filter_preset_30d,
        UsageFilterPreset.ThisMonth to R.string.stats_model_filter_preset_month,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (preset, labelRes) ->
            UsageBoundChip(
                text = stringResource(labelRes),
                selected = selected == preset,
                onClick = { onSelect(preset) },
            )
        }
    }
}

/**
 * 时间筛选胶囊。
 *
 * Material 3 走官方 [FilterChip]（自带选中态、涟漪与语义，形状/配色/字阶均为官方默认值），
 * 替掉「Box + clip + background + 无涟漪 clickable」；MIUI X 保持原有绘制。
 */
@Composable
private fun UsageBoundChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    fullWidth: Boolean = false,
    onClick: () -> Unit,
) {
    if (isMaterial3Theme()) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { M3Text(text = text) },
            modifier = if (fullWidth) modifier.fillMaxWidth() else modifier,
        )
        return
    }
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primary else colors.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = if (fullWidth) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = if (selected) colors.onPrimary else colors.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

private fun formatUsageDate(bound: UsageTimeBound): String? {
    val date = bound.date ?: return null
    return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

@Composable
private fun filterSummaryText(
    start: UsageTimeBound,
    end: UsageTimeBound,
    preset: UsageFilterPreset?,
): String? {
    val presetRes = when (preset) {
        UsageFilterPreset.Today -> R.string.stats_model_filter_preset_today
        UsageFilterPreset.Last7Days -> R.string.stats_model_filter_preset_7d
        UsageFilterPreset.ThisWeek -> R.string.stats_model_filter_preset_week
        UsageFilterPreset.Last30Days -> R.string.stats_model_filter_preset_30d
        UsageFilterPreset.ThisMonth -> R.string.stats_model_filter_preset_month
        null -> null
    }
    if (presetRes != null) return stringResource(presetRes)
    val startText = formatUsageDate(start)
    val endText = formatUsageDate(end)
    return when {
        startText != null && endText != null -> "$startText – $endText"
        startText != null -> startText
        endText != null -> endText
        else -> null
    }
}

/** 单个模型的折叠行：M3 用官方可点击 [ListItem]，MIUI X 保持原有可点击行。 */
@Composable
private fun ModelUsageRow(
    model: ModelUsageModelUi,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val view = LocalView.current
    Column {
        if (isMaterial3Theme()) {
            ListItem(
                onClick = {
                    TouchHaptics.click(view)
                    onToggle()
                },
                modifier = Modifier.fillMaxWidth(),
                content = {
                    M3Text(text = model.displayName, style = MaterialTheme.typography.bodyLarge)
                },
                trailingContent = {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        TouchHaptics.click(view)
                        onToggle()
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.displayName,
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_token_total),
                    value = formatTokenCount(model.totalTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_fresh_input_tokens),
                    value = formatTokenCount(model.freshInputTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokenCount(model.outputTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = formatTokenCount(model.cachedTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_cache_creation_tokens),
                    value = formatTokenCount(model.cacheCreationTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_cache_hit_rate),
                    value = formatCacheHitRate(model.cachedTokens, model.inputTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_daily_avg),
                    value = formatTokenCount(model.dailyAverageTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_conversation_avg),
                    value = formatTokenCount(model.conversationAverageTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_conversations),
                    value = formatStatCount(model.conversationCount.toLong()),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_active_days),
                    value = formatStatCount(model.activeDays.toLong()),
                )
            }
        }
    }
}

@Composable
private fun ModelMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatsLabel(text = label)
        StatsLabel(text = value, tier = StatsTextTier.Primary, maxLines = 1)
    }
}

@Composable
private fun ChatHeatmap(conversationsPerDay: Map<LocalDate, Int>) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)
    val numWeeks = 53
    val (q1, q2, q3) = heatmapQuartiles(conversationsPerDay.values)
    val cellSize = 11.dp
    val cellSpacing = 2.dp
    val monthLabelHeight = 14.dp
    val dowLabels = listOf(
        "",
        stringResource(R.string.stats_page_dow_mon),
        "",
        stringResource(R.string.stats_page_dow_wed),
        "",
        stringResource(R.string.stats_page_dow_fri),
        "",
    )
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)
    val onSurfaceVariant = if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            modifier = Modifier.width(12.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            fontSize = 8.sp,
                            color = onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            Text(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                fontSize = 9.sp,
                                color = onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacing)) {
                        for (dow in 0..6) {
                            val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                            val isFuture = date.isAfter(today)
                            val count = if (isFuture) 0 else (conversationsPerDay[date] ?: 0)
                            HeatmapCell(
                                alpha = heatmapAlpha(count, q1, q2, q3, isFuture),
                                sizeDp = cellSize.value.toInt(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 热力图色块：非交互数据可视化，颜色只取主题角色。 */
@Composable
private fun HeatmapCell(alpha: Float, sizeDp: Int) {
    val surfaceVariant: Color
    val primary: Color
    if (isMaterial3Theme()) {
        surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
        primary = MaterialTheme.colorScheme.primary
    } else {
        surfaceVariant = MiuixTheme.colorScheme.surfaceVariant
        primary = MiuixTheme.colorScheme.primary
    }
    val color = when {
        alpha < 0f -> surfaceVariant.copy(alpha = 0.3f)
        alpha == 0f -> surfaceVariant
        else -> primary.copy(alpha = alpha)
    }
    // M3 走官方形状令牌里最小的一档；miuix 主题族保留原来的 2dp。
    val cellShape: Shape =
        if (isMaterial3Theme()) MaterialTheme.shapes.extraSmall else RoundedCornerShape(2.dp)
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(cellShape)
            .background(color),
    )
}

@Composable
private fun StatsGrid(stats: UsageStatsSnapshot, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Forum,
                label = stringResource(R.string.stats_page_total_conversations),
                value = formatCurrentLifetime(
                    current = formatStatCount(stats.currentConversations.toLong()),
                    lifetime = formatStatCount(stats.lifetimeConversations.toLong()),
                ),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ChatBubbleOutline,
                label = stringResource(R.string.stats_page_total_messages),
                value = formatCurrentLifetime(
                    current = formatStatCount(stats.currentMessages.toLong()),
                    lifetime = formatStatCount(stats.lifetimeMessages.toLong()),
                ),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Keyboard,
                label = stringResource(R.string.stats_page_fresh_input_tokens),
                value = formatCurrentLifetime(
                    current = formatTokenCount(stats.currentFreshInputTokens),
                    lifetime = formatTokenCount(stats.lifetimeFreshInputTokens),
                ),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Rounded.Notes,
                label = stringResource(R.string.stats_page_output_tokens),
                value = formatCurrentLifetime(
                    current = formatTokenCount(stats.currentOutputTokens),
                    lifetime = formatTokenCount(stats.lifetimeOutputTokens),
                ),
            )
        }
        if (stats.lifetimeCachedTokens > 0) {
            StatCard(
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Bolt,
                label = stringResource(R.string.stats_page_cached_tokens),
                value = formatCurrentLifetime(
                    current = formatTokenCount(stats.currentCachedTokens),
                    lifetime = formatTokenCount(stats.lifetimeCachedTokens),
                ),
            )
        }
        StatCard(
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Rounded.Add,
            label = stringResource(R.string.stats_page_cache_creation_tokens),
            value = formatCurrentLifetime(
                current = formatTokenCount(stats.currentCacheCreationTokens),
                lifetime = formatTokenCount(stats.lifetimeCacheCreationTokens),
            ),
        )
        StatCard(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            icon = Icons.Rounded.RocketLaunch,
            label = stringResource(R.string.stats_page_launch_count),
            value = formatStatCount(stats.launchCount.toLong()),
        )
    }
}

private fun formatCurrentLifetime(current: String, lifetime: String): String =
    if (current == lifetime) current else "$current / $lifetime"

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    EtaCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isMaterial3Theme()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp),
            )
            StatsTitle(text = value)
            StatsLabel(text = label)
        }
    }
}
