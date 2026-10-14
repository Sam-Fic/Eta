package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.agent.pet.WHALE_MAID_MAX_SATIETY
import io.github.mangi.eta.agent.pet.WhaleMaidController
import io.github.mangi.eta.agent.pet.WhaleMaidSatietyBand
import io.github.mangi.eta.agent.pet.WhaleMaidSnapshot
import io.github.mangi.eta.agent.pet.WhaleMaidStore
import io.github.mangi.eta.agent.pet.whaleMaidSatietyBand
import io.github.mangi.eta.agent.pet.whaleMaidSatietyPercent
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSlider
import io.github.mangi.eta.ui.adaptive.EtaStatusDot
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlin.math.roundToInt

/** 体型调节范围，与 `WhaleMaidStore.setScale` 的钳制区间保持一致。 */
private val WhaleMaidScaleRange = 0.5f..1.8f

/**
 * 鲸鱼女仆设置页。
 *
 * 信息架构：状态 hero（启用状态 + 当前状态 + 饱腹度）→「基础」（开关、体型、全局显示与悬浮窗授权）
 * →「说话」（任务后发言、指定模型、手账入口）。
 *
 * 所有设置项都从 [WhaleMaidStore] 的实时快照驱动，宠物在悬浮窗里进食后本页可以同步刷新。
 */
@Composable
internal fun WhaleMaidSettingsScreen(
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(WhaleMaidStore.snapshot(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var selection by remember { mutableStateOf(WhaleMaidStore.modelSelection(context)) }
    var picker by remember { mutableStateOf(false) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val models = remember(providers, selection) {
        val all = AgentModelPickerProjector.project(providers, selection.providerId, selection.modelId)
        all.copy(
            providerGroups = all.providerGroups.map { group ->
                group.copy(models = group.models.filter { !it.supportsImageGeneration && !it.supportsVideoGeneration })
            }.filter { it.models.isNotEmpty() },
            selectedModel = all.selectedModel?.takeIf { !it.supportsImageGeneration && !it.supportsVideoGeneration },
        )
    }

    DisposableEffect(Unit) {
        val listener: (WhaleMaidSnapshot) -> Unit = { snapshot = it }
        WhaleMaidStore.addListener(listener)
        onDispose { WhaleMaidStore.removeListener(listener) }
    }

    // 悬浮窗权限在系统设置页授予，回到本页时需要重新确认，否则授权行不会消失。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    EtaScaffoldPage(title = stringResource(R.string.whale_maid_title), onBack = onBack) {
        item(key = "status-card") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                item {
                    EtaSegmentedItemContainer {
                        WhaleMaidStatusBody(snapshot)
                    }
                }
            }
        }

        item(key = "basic") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
                title = stringResource(R.string.whale_maid_group_basic),
            ) {
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.whale_maid_title),
                        checked = snapshot.enabled,
                        leading = { Icon(Icons.Rounded.Pets, null) },
                        onCheckedChange = { WhaleMaidController.setEnabled(context, it) },
                    )
                }
                item {
                    WhaleMaidScaleRow(
                        scale = snapshot.scale,
                        onScaleChange = { WhaleMaidController.setScale(context, it) },
                    )
                }
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.whale_maid_global),
                        checked = snapshot.globalVisible,
                        enabled = overlayGranted,
                        leading = { Icon(Icons.Rounded.Layers, null) },
                        onCheckedChange = { WhaleMaidController.setGlobalVisible(context, it) },
                    )
                }
                // 未授权时常驻；授权后自动消失，不再在开关回调里突兀跳转系统设置。
                item(visible = !overlayGranted) {
                    EtaClickableRow(
                        title = stringResource(R.string.ui_floating_window_permissions_076b77),
                        summary = stringResource(R.string.whale_maid_overlay_summary),
                        leading = { Icon(Icons.Rounded.Lock, null) },
                        onClick = { launchOverlayPermissionSettings(context) },
                    )
                }
            }
        }

        item(key = "speech") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
                title = stringResource(R.string.whale_maid_group_speech),
            ) {
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.whale_maid_work_speech),
                        checked = snapshot.workSpeechEnabled,
                        leading = { Icon(Icons.Rounded.RecordVoiceOver, null) },
                        onCheckedChange = { WhaleMaidController.setWorkSpeechEnabled(context, it) },
                    )
                }
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.whale_maid_custom_model),
                        checked = selection.custom,
                        leading = { Icon(Icons.Rounded.Tune, null) },
                        onCheckedChange = { value ->
                            selection = selection.copy(custom = value)
                            WhaleMaidController.setModelSelection(
                                context,
                                value,
                                selection.providerId,
                                selection.modelId,
                            )
                        },
                    )
                }
                item(visible = selection.custom) {
                    EtaClickableRow(
                        title = stringResource(R.string.whale_maid_select_model),
                        summary = models.selectedModel?.let { "${it.providerName} / ${it.displayName}" },
                        leading = { Icon(Icons.Rounded.SmartToy, null) },
                        onClick = { picker = true },
                    )
                }
                item {
                    EtaClickableRow(
                        title = stringResource(R.string.whale_maid_memories),
                        summary = snapshot.memories.firstOrNull()?.speech
                            ?: stringResource(R.string.whale_maid_empty),
                        leading = { Icon(Icons.Rounded.History, null) },
                        onClick = { onNavigate(AppRoute.WhaleMaidMemories) },
                    )
                }
            }
        }
    }

    TtsModelPickerDialog(
        state = models,
        show = picker,
        onDismiss = { picker = false },
        onModelSelected = { providerId, modelId ->
            selection = ModelFeatureSelection(true, providerId, modelId)
            WhaleMaidController.setModelSelection(context, true, providerId, modelId)
            picker = false
        },
        title = stringResource(R.string.whale_maid_select_model),
    )
}

/**
 * 状态 hero：状态点 + 名称 + 当前状态（停用 / 思考中 / 饱腹档位），下面接饱腹度进度。
 */
@Composable
private fun WhaleMaidStatusBody(snapshot: WhaleMaidSnapshot) {
    val percent = whaleMaidSatietyPercent(snapshot.satiety)
    val bandText = whaleMaidSatietyBandText(whaleMaidSatietyBand(snapshot.satiety))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EtaStatusDot(active = snapshot.enabled)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                EtaText(text = stringResource(R.string.whale_maid_title), style = EtaTextStyle.Title)
                EtaText(
                    text = when {
                        !snapshot.enabled -> stringResource(R.string.whale_maid_disabled)
                        snapshot.thinking -> stringResource(R.string.whale_maid_thinking)
                        else -> bandText
                    },
                    style = EtaTextStyle.Footnote,
                    secondary = true,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            EtaText(
                text = "${snapshot.satiety} / $WHALE_MAID_MAX_SATIETY ($percent%)",
                style = EtaTextStyle.Footnote,
                secondary = true,
            )
        }
    }
}

/** 体型行：标题在左、当前百分比在右，滑块独占一行。 */
@Composable
private fun WhaleMaidScaleRow(
    scale: Float,
    onScaleChange: (Float) -> Unit,
) {
    EtaSegmentedItemContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Straighten, null)
                EtaText(
                    text = stringResource(R.string.whale_maid_scale),
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .weight(1f),
                    style = EtaTextStyle.Body,
                )
                EtaText(
                    text = "${(scale * 100).roundToInt()}%",
                    style = EtaTextStyle.Secondary,
                )
            }
            EtaSlider(
                value = scale,
                onValueChange = onScaleChange,
                valueRange = WhaleMaidScaleRange,
            )
        }
    }
}

@Composable
private fun whaleMaidSatietyBandText(band: WhaleMaidSatietyBand): String = stringResource(
    when (band) {
        WhaleMaidSatietyBand.STARVING -> R.string.whale_maid_satiety_starving
        WhaleMaidSatietyBand.HUNGRY -> R.string.whale_maid_satiety_hungry
        WhaleMaidSatietyBand.PECKISH -> R.string.whale_maid_satiety_peckish
        WhaleMaidSatietyBand.FULL -> R.string.whale_maid_satiety_full
        WhaleMaidSatietyBand.STUFFED -> R.string.whale_maid_satiety_stuffed
    },
)

private fun launchOverlayPermissionSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ),
    )
}
