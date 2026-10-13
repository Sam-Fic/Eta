package io.github.mangi.eta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeFamily
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.MAX_INTERFACE_SCALE
import io.github.mangi.eta.data.model.MIN_INTERFACE_SCALE
import io.github.mangi.eta.data.model.normalizeInterfaceScale
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.EtaSlider
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTextField
import io.github.mangi.eta.ui.adaptive.etaOnSurfaceVariant
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import kotlin.math.roundToInt

@Composable
internal fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val appearance = LocalAppearanceSettings.current
    val coroutineScope = rememberCoroutineScope()
    var scaleDraft by remember(appearance.interfaceScale) {
        mutableFloatStateOf(appearance.interfaceScale * 100f)
    }
    var showScaleDialog by remember { mutableStateOf(false) }
    var scaleInput by remember { mutableStateOf("") }
    var morphLoadingExpanded by remember { mutableStateOf(false) }
    var iconExpanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    val context = LocalContext.current
    val blurSupported = isRuntimeShaderSupported()

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        coroutineScope.launch {
            AppearanceSettingsRepository.update(transform)
        }
    }

    fun confirmIcon(light: Int, dark: Int, cat: Int) {
        coroutineScope.launch {
            AppearanceSettingsRepository.update { current ->
                current.copy(iconLightColor = light, iconDarkColor = dark, iconCatColor = cat)
            }
            val latest = AppearanceSettingsRepository.settings()
            LauncherIconSync.apply(context.applicationContext, latest)
        }
    }

    fun commitScale(percent: Float) {
        val scale = normalizeInterfaceScale(percent.roundToInt() / 100f)
        scaleDraft = scale * 100f
        update { current -> current.copy(interfaceScale = scale) }
    }

    val themeFamilies = AppearanceThemeFamily.entries
    val themeFamilyLabels = listOf(
        stringResource(R.string.appearance_theme_family_miui),
        stringResource(R.string.appearance_theme_family_material3),
    )
    val themeModes = AppearanceThemeMode.entries
    val themeModeLabels = listOf(
        stringResource(R.string.appearance_theme_system),
        stringResource(R.string.appearance_theme_light),
        stringResource(R.string.appearance_theme_dark),
    )
    val paletteStyles = AppearancePaletteStyle.entries
    val paletteLabels = listOf(
        stringResource(R.string.appearance_palette_tonal_spot),
        stringResource(R.string.appearance_palette_neutral),
        stringResource(R.string.appearance_palette_vibrant),
        stringResource(R.string.appearance_palette_expressive),
        stringResource(R.string.appearance_palette_rainbow),
        stringResource(R.string.appearance_palette_fruit_salad),
        stringResource(R.string.appearance_palette_monochrome),
        stringResource(R.string.appearance_palette_fidelity),
        stringResource(R.string.appearance_palette_content),
    )
    val accentColors = AppearanceAccentColor.entries
    val accentLabels = listOf(
        stringResource(R.string.appearance_accent_system),
        stringResource(R.string.appearance_accent_blue),
        stringResource(R.string.appearance_accent_purple),
        stringResource(R.string.appearance_accent_pink),
        stringResource(R.string.appearance_accent_red),
        stringResource(R.string.appearance_accent_orange),
        stringResource(R.string.appearance_accent_yellow),
        stringResource(R.string.appearance_accent_green),
        stringResource(R.string.appearance_accent_teal),
    )
    val blurStyles = AppearanceTopBarBlurStyle.entries
    val blurStyleLabels = listOf(
        stringResource(R.string.appearance_blur_style_gaussian),
        stringResource(R.string.appearance_blur_style_progressive),
    )
    val blurStyleSummaries = listOf(
        stringResource(R.string.appearance_blur_style_gaussian_summary),
        stringResource(R.string.appearance_blur_style_progressive_summary),
    )

    EtaScaffoldPage(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
    ) {
        item(key = "appearance_color_title") {
            EtaSectionTitle(text = stringResource(R.string.appearance_group_color))
        }
        item(key = "appearance_color_card") {
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                EtaDropdownRow(
                    title = stringResource(R.string.appearance_theme_family),
                    summary = themeFamilyLabels[appearance.themeFamily.ordinal],
                    items = themeFamilyLabels,
                    selectedIndex = appearance.themeFamily.ordinal,
                    onSelectedIndexChange = { index ->
                        themeFamilies.getOrNull(index)?.let { family ->
                            update { current -> current.copy(themeFamily = family) }
                        }
                    },
                )
                EtaDropdownRow(
                    title = stringResource(R.string.appearance_theme_mode),
                    summary = themeModeLabels[appearance.themeMode.ordinal],
                    items = themeModeLabels,
                    selectedIndex = appearance.themeMode.ordinal,
                    onSelectedIndexChange = { index ->
                        themeModes.getOrNull(index)?.let { mode ->
                            update { current -> current.copy(themeMode = mode) }
                        }
                    },
                )
                EtaDropdownRow(
                    title = stringResource(R.string.appearance_palette_style),
                    summary = paletteLabels[appearance.paletteStyle.ordinal],
                    items = paletteLabels,
                    selectedIndex = appearance.paletteStyle.ordinal,
                    onSelectedIndexChange = { index ->
                        paletteStyles.getOrNull(index)?.let { style ->
                            update { current -> current.copy(paletteStyle = style) }
                        }
                    },
                )
                EtaDropdownRow(
                    title = stringResource(R.string.appearance_accent_color),
                    summary = accentLabels[appearance.accentColor.ordinal],
                    items = accentLabels,
                    selectedIndex = appearance.accentColor.ordinal,
                    onSelectedIndexChange = { index ->
                        accentColors.getOrNull(index)?.let { accent ->
                            update { current -> current.copy(accentColor = accent) }
                        }
                    },
                )
                EtaSwitchRow(
                    title = stringResource(R.string.appearance_pure_black),
                    summary = stringResource(R.string.appearance_pure_black_summary),
                    checked = appearance.pureBlackEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(pureBlackEnabled = enabled) }
                    },
                )
                EtaClickableRow(
                    title = stringResource(R.string.appearance_icon),
                    summary = stringResource(R.string.appearance_icon_summary),
                    showChevron = false,
                    onClick = {
                        TouchHaptics.click(view)
                        iconExpanded = !iconExpanded
                    },
                    holdDownState = iconExpanded,
                    endActions = {
                        Icon(
                            imageVector = if (iconExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.CenterVertically),
                            tint = etaOnSurfaceVariant(),
                        )
                    },
                    bottomAction = if (iconExpanded) {
                        {
                            IconAppearanceEditor(
                                light = appearance.iconLightColor,
                                dark = appearance.iconDarkColor,
                                cat = appearance.iconCatColor,
                                onConfirm = ::confirmIcon,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        item(key = "appearance_interface_title") {
            EtaSectionTitle(text = stringResource(R.string.appearance_group_interface))
        }
        item(key = "appearance_interface_card") {
            EtaCard(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                EtaSwitchRow(
                    title = stringResource(R.string.appearance_message_timestamps),
                    summary = stringResource(R.string.appearance_message_timestamps_summary),
                    checked = appearance.messageTimestampsEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(messageTimestampsEnabled = enabled) }
                    },
                )
                // Material 3 模式使用不透明表面，毛玻璃相关设置不适用，直接隐藏。
                if (!isMaterial3Theme()) {
                    EtaSwitchRow(
                        title = stringResource(R.string.appearance_blur),
                        summary = stringResource(R.string.appearance_blur_summary),
                        checked = appearance.blurEnabled && blurSupported,
                        onCheckedChange = { enabled ->
                            update { current -> current.copy(blurEnabled = enabled) }
                        },
                        enabled = blurSupported,
                    )
                    AnimatedVisibility(
                        visible = appearance.blurEnabled && blurSupported,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        EtaDropdownRow(
                            title = stringResource(R.string.appearance_blur_style),
                            summary = blurStyleSummaries[appearance.topBarBlurStyle.ordinal],
                            items = blurStyleLabels,
                            selectedIndex = appearance.topBarBlurStyle.ordinal,
                            onSelectedIndexChange = { index ->
                                blurStyles.getOrNull(index)?.let { style ->
                                    update { current -> current.copy(topBarBlurStyle = style) }
                                }
                            },
                        )
                    }
                }
                EtaClickableRow(
                    title = stringResource(R.string.appearance_morph_loading),
                    summary = when {
                        !appearance.morphLoadingIndicator ->
                            stringResource(R.string.appearance_morph_loading_summary)
                        appearance.morphLoadingBeforeResponseOnly ->
                            stringResource(R.string.appearance_morph_loading_before_response)
                        else ->
                            stringResource(R.string.appearance_morph_loading_during_generation)
                    },
                    showChevron = false,
                    onClick = {
                        if (appearance.morphLoadingIndicator) {
                            morphLoadingExpanded = !morphLoadingExpanded
                        } else {
                            update { current -> current.copy(morphLoadingIndicator = true) }
                            morphLoadingExpanded = true
                        }
                    },
                    holdDownState = appearance.morphLoadingIndicator && morphLoadingExpanded,
                    endActions = {
                        if (appearance.morphLoadingIndicator) {
                            Icon(
                                imageVector = if (morphLoadingExpanded) {
                                    Icons.Rounded.ExpandMore
                                } else {
                                    Icons.Rounded.ChevronRight
                                },
                                contentDescription = stringResource(
                                    if (morphLoadingExpanded) {
                                        R.string.appearance_morph_loading_collapse
                                    } else {
                                        R.string.appearance_morph_loading_expand
                                    },
                                ),
                                modifier = Modifier.align(Alignment.CenterVertically),
                                tint = etaOnSurfaceVariant(),
                            )
                        }
                        EtaSwitch(
                            checked = appearance.morphLoadingIndicator,
                            onCheckedChange = { enabled ->
                                update { current -> current.copy(morphLoadingIndicator = enabled) }
                                morphLoadingExpanded = enabled
                            },
                        )
                    },
                    bottomAction = if (appearance.morphLoadingIndicator && morphLoadingExpanded) {
                        {
                            EtaSwitchRow(
                                title = stringResource(R.string.appearance_morph_loading_before_response),
                                summary = stringResource(R.string.appearance_morph_loading_before_response_summary),
                                checked = appearance.morphLoadingBeforeResponseOnly,
                                onCheckedChange = { enabled ->
                                    update { current ->
                                        current.copy(morphLoadingBeforeResponseOnly = enabled)
                                    }
                                },
                                dense = true,
                            )
                        }
                    } else {
                        null
                    },
                )
                EtaSwitchRow(
                    title = stringResource(R.string.appearance_swipe_dismiss),
                    summary = stringResource(R.string.appearance_swipe_dismiss_summary),
                    checked = appearance.swipeDismissEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(swipeDismissEnabled = enabled) }
                    },
                )
                EtaSwitchRow(
                    title = stringResource(R.string.appearance_predictive_back),
                    summary = stringResource(R.string.appearance_predictive_back_summary),
                    checked = appearance.predictiveBackEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(predictiveBackEnabled = enabled) }
                    },
                )
                EtaClickableRow(
                    title = stringResource(R.string.appearance_interface_scale),
                    summary = stringResource(R.string.appearance_interface_scale_summary),
                    onClick = {
                        scaleInput = scaleDraft.roundToInt().toString()
                        showScaleDialog = true
                    },
                    holdDownState = showScaleDialog,
                    endActions = {
                        EtaText(text = "${scaleDraft.roundToInt()}%", secondary = true)
                    },
                    bottomAction = {
                        EtaSlider(
                            value = scaleDraft.coerceIn(
                                MIN_INTERFACE_SCALE * 100f,
                                MAX_INTERFACE_SCALE * 100f,
                            ),
                            onValueChange = { scaleDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            valueRange = (MIN_INTERFACE_SCALE * 100f)..(MAX_INTERFACE_SCALE * 100f),
                            keyPoints = listOf(80f, 90f, 100f, 110f),
                            onValueChangeFinished = { commitScale(scaleDraft) },
                        )
                    },
                )
            }
        }
    }

    val parsedScale = scaleInput.toIntOrNull()
    EtaDialog(
        show = showScaleDialog,
        title = stringResource(R.string.appearance_interface_scale_dialog_title),
        summary = stringResource(R.string.appearance_interface_scale_dialog_summary),
        onDismissRequest = { showScaleDialog = false },
    ) {
        Column {
            EtaTextField(
                value = scaleInput,
                onValueChange = { value -> scaleInput = value.filter(Char::isDigit).take(3) },
                label = stringResource(R.string.appearance_interface_scale_input_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            EtaDialogActions(
                confirmText = stringResource(R.string.action_confirm),
                confirmEnabled = parsedScale != null && parsedScale in 80..110,
                onCancel = { showScaleDialog = false },
                onConfirm = {
                    parsedScale?.let { commitScale(it.toFloat()) }
                    showScaleDialog = false
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

private val iconSwatches = listOf(
    0xFFF27A1A, 0xFF7B61FF, 0xFFF26D9A, 0xFF3DDC84, 0xFFF5C542, 0xFF2491FF,
    0xFFF6F7F9, 0xFF1C1C1E,
).map { it.toInt() }

@Composable
private fun IconAppearanceEditor(
    light: Int,
    dark: Int,
    cat: Int,
    onConfirm: (Int, Int, Int) -> Unit,
) {
    var draftLight by remember(light) { androidx.compose.runtime.mutableIntStateOf(light) }
    var draftDark by remember(dark) { androidx.compose.runtime.mutableIntStateOf(dark) }
    var draftCat by remember(cat) { androidx.compose.runtime.mutableIntStateOf(cat) }
    val dirty = draftLight != light || draftDark != dark || draftCat != cat
    val view = LocalView.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LauncherIconPreview(background = draftLight, cat = draftCat)
            LauncherIconPreview(background = draftDark, cat = draftCat)
        }
        IconColorChoices(stringResource(R.string.appearance_icon_light), draftLight) { draftLight = it }
        IconColorChoices(stringResource(R.string.appearance_icon_dark), draftDark) { draftDark = it }
        IconColorChoices(stringResource(R.string.appearance_icon_cat), draftCat) { draftCat = it }
        EtaTextButton(
            text = stringResource(R.string.appearance_icon_apply),
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                TouchHaptics.click(view)
                onConfirm(draftLight, draftDark, draftCat)
            },
        )
    }
}

@Composable
private fun IconColorChoices(title: String, selected: Int, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EtaText(text = title, secondary = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            iconSwatches.forEach { color ->
                if (isMaterial3Theme()) {
                    // M3 Expressive 没有取色器/色板组件：色板用官方 IconToggleButton 承载，
                    // 选中语义、涟漪、容器形状与容器尺寸全部取 IconButtonDefaults 默认值，
                    // 颜色本体作为图标 tint 呈现；不再 Box + clip(CircleShape) + background + clickable 拼装。
                    IconToggleButton(
                        checked = color == selected,
                        onCheckedChange = {
                            TouchHaptics.click(view)
                            onSelect(color)
                        },
                    ) {
                        Icon(
                            Icons.Rounded.Circle,
                            // 色板本身没有文案，M3 也没有取色器组件，这里用色值本身作无障碍名称。
                            // 必须给：IconToggleButton 经 Surface 的 childSemantics() 合并子节点，
                            // 若这里是 contentDescription = null，8 个色块会变成「无名按钮」。
                            contentDescription = "颜色 #%08X".format(color),
                            tint = Color(color),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(if (color == selected) 28.dp else 24.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .clickable {
                                TouchHaptics.click(view)
                                onSelect(color)
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun LauncherIconPreview(
    background: Int,
    cat: Int,
) {
    val context = LocalContext.current
    val name = LauncherIconSync.resourceName(background, cat)
    val id = context.resources.getIdentifier(name, "drawable", context.packageName)
    Box(
        modifier = Modifier
            .size(56.dp)
            // 官方形状令牌（medium = 12dp），不写死圆角。
            .clip(MaterialTheme.shapes.medium),
    ) {
        if (id != 0) {
            Image(
                painter = painterResource(id),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}
