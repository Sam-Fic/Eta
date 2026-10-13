package io.github.mangi.eta.ui

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.discreteSliderIndexForTap
import io.github.mangi.eta.ui.haptics.HapticIntensity
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider as M3Slider
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.rememberSliderState

@Composable
internal fun HapticsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = remember { Prefs.localAgentPreferences() }
    var touchEnabled by remember {
        mutableStateOf(Prefs.isEnabled(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK))
    }
    var messageGenerationEnabled by remember {
        mutableStateOf(Prefs.isEnabled(Prefs.Keys.HAPTIC_MESSAGE_GENERATION))
    }
    var backgroundEnabled by remember {
        mutableStateOf(Prefs.isEnabled(Prefs.Keys.HAPTIC_BACKGROUND))
    }
    var backgroundReasoningEnabled by remember {
        mutableStateOf(Prefs.isEnabled(Prefs.Keys.HAPTIC_BACKGROUND_REASONING))
    }
    var backgroundExpanded by remember { mutableStateOf(false) }
    var intensity by remember { mutableStateOf(TouchHaptics.currentIntensity()) }
    var showIntensityDialog by remember { mutableStateOf(false) }

    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            when (key) {
                Prefs.Keys.HAPTIC_TOUCH_FEEDBACK -> {
                    touchEnabled = changed.getBoolean(
                        key,
                        Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key),
                    )
                }
                Prefs.Keys.HAPTIC_MESSAGE_GENERATION -> {
                    messageGenerationEnabled = changed.getBoolean(
                        key,
                        Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key),
                    )
                }
                Prefs.Keys.HAPTIC_BACKGROUND -> {
                    backgroundEnabled = changed.getBoolean(
                        key,
                        Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key),
                    )
                }
                Prefs.Keys.HAPTIC_BACKGROUND_REASONING -> {
                    backgroundReasoningEnabled = changed.getBoolean(
                        key,
                        Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key),
                    )
                }
                Prefs.Keys.HAPTIC_INTENSITY -> {
                    TouchHaptics.reloadIntensity()
                    intensity = TouchHaptics.currentIntensity()
                }
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun write(key: String, value: Boolean): Boolean {
        val target = prefs ?: return false
        return runCatching { target.edit().putBoolean(key, value).commit() }.getOrDefault(false)
    }

    fun onToggle(key: String, value: Boolean, current: Boolean): Boolean {
        if (current) TouchHaptics.click(view)
        if (!write(key, value)) {
            Toast.makeText(
                context.applicationContext,
                context.getString(R.string.settings_write_failed),
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }
        if (!current && value) TouchHaptics.click(view)
        return true
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.haptics_title),
        onBack = onBack,
    ) {
        item(key = "haptics_switches") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.haptics_touch_feedback),
                    summary = if (touchEnabled) {
                        stringResource(intensity.labelRes)
                    } else {
                        stringResource(R.string.haptics_touch_feedback_summary)
                    },
                    onClick = { showIntensityDialog = true },
                    holdDownState = showIntensityDialog,
                    startAction = { PreferenceIcon(icon = Icons.Rounded.Vibration) },
                    endActions = {
                        Icon(
                            imageVector = if (showIntensityDialog) {
                                Icons.Rounded.ExpandMore
                            } else {
                                Icons.Rounded.ChevronRight
                            },
                            contentDescription = stringResource(
                                if (showIntensityDialog) {
                                    R.string.haptics_intensity_collapse
                                } else {
                                    R.string.haptics_intensity_expand
                                },
                            ),
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .padding(end = 8.dp)
                                .size(16.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                        Switch(
                            checked = touchEnabled,
                            onCheckedChange = { value ->
                                if (onToggle(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK, value, touchEnabled)) {
                                    touchEnabled = value
                                }
                            },
                        )
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.haptics_message_generation),
                    summary = stringResource(R.string.haptics_message_generation_summary),
                    checked = touchEnabled && messageGenerationEnabled,
                    onCheckedChange = { value ->
                        if (onToggle(
                                Prefs.Keys.HAPTIC_MESSAGE_GENERATION,
                                value,
                                touchEnabled && messageGenerationEnabled,
                            )
                        ) {
                            messageGenerationEnabled = value
                        }
                    },
                    startAction = {
                        PreferenceIcon(icon = Icons.Rounded.GraphicEq, enabled = touchEnabled)
                    },
                    enabled = touchEnabled,
                )
                val generationOn = touchEnabled && messageGenerationEnabled
                val backgroundOn = generationOn && backgroundEnabled
                val showBackgroundOptions = backgroundOn && backgroundExpanded
                BasicComponent(
                    title = stringResource(R.string.haptics_background),
                    summary = stringResource(
                        when {
                            !backgroundOn -> R.string.haptics_background_summary_off
                            backgroundReasoningEnabled -> R.string.haptics_background_summary_reasoning
                            else -> R.string.haptics_background_summary_tools
                        },
                    ),
                    onClick = {
                        if (backgroundOn) {
                            TouchHaptics.click(view)
                            backgroundExpanded = !backgroundExpanded
                        } else if (onToggle(Prefs.Keys.HAPTIC_BACKGROUND, true, false)) {
                            backgroundEnabled = true
                            backgroundExpanded = true
                        }
                    },
                    holdDownState = showBackgroundOptions,
                    startAction = {
                        PreferenceIcon(icon = Icons.Rounded.Layers, enabled = generationOn)
                    },
                    endActions = {
                        if (backgroundOn) {
                            Icon(
                                imageVector = if (backgroundExpanded) {
                                    Icons.Rounded.ExpandMore
                                } else {
                                    Icons.Rounded.ChevronRight
                                },
                                contentDescription = stringResource(
                                    if (backgroundExpanded) {
                                        R.string.haptics_background_collapse
                                    } else {
                                        R.string.haptics_background_expand
                                    },
                                ),
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .padding(end = 8.dp)
                                    .size(16.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                        Switch(
                            checked = backgroundOn,
                            onCheckedChange = { value ->
                                if (onToggle(Prefs.Keys.HAPTIC_BACKGROUND, value, backgroundOn)) {
                                    backgroundEnabled = value
                                    backgroundExpanded = value
                                }
                            },
                            enabled = generationOn,
                        )
                    },
                    bottomAction = if (showBackgroundOptions) {
                        {
                            SwitchPreference(
                                title = stringResource(R.string.haptics_background_reasoning),
                                summary = stringResource(R.string.haptics_background_reasoning_summary),
                                checked = backgroundReasoningEnabled,
                                onCheckedChange = { value ->
                                    if (onToggle(
                                            Prefs.Keys.HAPTIC_BACKGROUND_REASONING,
                                            value,
                                            backgroundReasoningEnabled,
                                        )
                                    ) {
                                        backgroundReasoningEnabled = value
                                    }
                                },
                                insideMargin = PaddingValues(0.dp),
                            )
                        }
                    } else {
                        null
                    },
                    enabled = generationOn,
                )
            }
        }
    }

    HapticIntensityPickerDialog(
        show = showIntensityDialog,
        intensity = intensity,
        onDismiss = { showIntensityDialog = false },
        onPreview = { next -> TouchHaptics.previewClick(view, next) },
        onIntensityChange = { next ->
            if (next == intensity) return@HapticIntensityPickerDialog
            if (!TouchHaptics.setIntensity(next)) {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                return@HapticIntensityPickerDialog
            }
            intensity = next
        },
    )
}

@Composable
private fun HapticIntensityPickerDialog(
    show: Boolean,
    intensity: HapticIntensity,
    onDismiss: () -> Unit,
    onPreview: (HapticIntensity) -> Unit,
    onIntensityChange: (HapticIntensity) -> Unit,
) {
    val options = HapticIntensity.entries
    val selectedIndex = options.indexOf(intensity).coerceAtLeast(0)
    var sliderValue by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var lastHapticIndex by remember { mutableIntStateOf(selectedIndex) }
    val latestIntensity by rememberUpdatedState(intensity)
    val latestOnPreview by rememberUpdatedState(onPreview)
    val latestOnIntensityChange by rememberUpdatedState(onIntensityChange)
    LaunchedEffect(show) {
        if (show) {
            sliderValue = selectedIndex.toFloat()
            lastHapticIndex = selectedIndex
        }
    }
    val previewIndex = sliderValue.roundToInt().coerceIn(0, options.lastIndex)
    val preview = options[previewIndex]
    val maxIndex = (options.size - 1).toFloat().coerceAtLeast(0f)

    EtaDialog(
        show = show,
        title = stringResource(R.string.haptics_intensity_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isMaterial3Theme()) {
                Icon(
                    imageVector = Icons.Rounded.Vibration,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                M3Text(
                    text = stringResource(preview.labelRes),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                // 官方离散滑块：steps 由档位数推导，吸附、拖拽命中与无障碍全部交给官方组件，
                // 不再叠加自绘的指针命中层。
                val sliderState = rememberSliderState(
                    value = sliderValue.coerceIn(0f, maxIndex),
                    steps = (options.size - 2).coerceAtLeast(0),
                    trackRange = 0f..maxIndex,
                )
                M3Slider(
                    state = sliderState,
                    onValueChange = { value ->
                        sliderValue = value
                        val index = value.roundToInt().coerceIn(0, options.lastIndex)
                        if (index != lastHapticIndex) {
                            lastHapticIndex = index
                            latestOnPreview(options[index])
                            if (options[index] != latestIntensity) {
                                latestOnIntensityChange(options[index])
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Vibration,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(32.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(preview.labelRes),
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = sliderValue.coerceIn(0f, maxIndex),
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        valueRange = 0f..maxIndex,
                        steps = (options.size - 2).coerceAtLeast(0),
                        showKeyPoints = true,
                        keyPoints = options.indices.map { it.toFloat() },
                        magnetThreshold = 0.18f,
                        hapticEffect = SliderDefaults.SliderHapticEffect.None,
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(options.size) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    val width = size.width.toFloat().coerceAtLeast(1f)
                                    var current = discreteSliderIndexForTap(
                                        down.position.x,
                                        width,
                                        options.size,
                                    )
                                    sliderValue = current.toFloat()
                                    lastHapticIndex = current
                                    latestOnPreview(options[current])
                                    if (options[current] != latestIntensity) {
                                        latestOnIntensityChange(options[current])
                                    }
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        val index = discreteSliderIndexForTap(
                                            change.position.x,
                                            width,
                                            options.size,
                                        )
                                        if (index != current) {
                                            current = index
                                            sliderValue = current.toFloat()
                                            if (current != lastHapticIndex) {
                                                lastHapticIndex = current
                                                latestOnPreview(options[current])
                                                if (options[current] != latestIntensity) {
                                                    latestOnIntensityChange(options[current])
                                                }
                                            }
                                        }
                                        change.consume()
                                        if (!event.changes.any { it.pressed }) break
                                    }
                                    val coerced = current.coerceIn(0, options.lastIndex)
                                    sliderValue = coerced.toFloat()
                                    lastHapticIndex = coerced
                                    val next = options[coerced]
                                    if (next != latestIntensity) latestOnIntensityChange(next)
                                }
                            },
                    )
                }
            }
        }
    }
}
