package io.github.mangi.eta.ui

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.haptics.HapticIntensity
import io.github.mangi.eta.ui.haptics.TouchHaptics

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
    var intensity by remember { mutableStateOf(TouchHaptics.currentIntensity()) }

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

    EtaScaffoldPage(
        title = stringResource(R.string.haptics_title),
        onBack = onBack,
    ) {
        item(key = "haptics_switches") {
            // 与兄弟设置页一致：全部走自适应共享行组件（EtaSwitchRow / EtaSegmentedDropdownRow），
            // 两种主题各自落到官方 M3 分段行或 miuix 行；条件子项用 item(visible) 注册，
            // 展开与收起走分段动画，首末圆角自动交接。
            EtaSegmentedColumn(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp)) {
                item(key = "touch_feedback") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_touch_feedback),
                        summary = stringResource(R.string.haptics_touch_feedback_summary),
                        checked = touchEnabled,
                        onCheckedChange = { value ->
                            if (onToggle(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK, value, touchEnabled)) {
                                touchEnabled = value
                            }
                        },
                    )
                }
                item(key = "haptics_intensity", visible = touchEnabled) {
                    // 振动幅度：KernelSU `SegmentedDropdownItem` 式下拉行，点选即预览一次振动。
                    val intensities = HapticIntensity.entries
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.haptics_intensity_title),
                        items = intensities.map { stringResource(it.labelRes) },
                        selectedIndex = intensities.indexOf(intensity).coerceAtLeast(0),
                        onItemSelected = { index ->
                            val next = intensities[index]
                            TouchHaptics.previewClick(view, next)
                            if (next != intensity && !TouchHaptics.setIntensity(next)) {
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_write_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
                item(key = "message_generation") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_message_generation),
                        summary = stringResource(R.string.haptics_message_generation_summary),
                        checked = touchEnabled && messageGenerationEnabled,
                        enabled = touchEnabled,
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
                    )
                }
                item(key = "haptics_background") {
                    val generationOn = touchEnabled && messageGenerationEnabled
                    val backgroundOn = generationOn && backgroundEnabled
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_background),
                        summary = stringResource(
                            when {
                                !backgroundOn -> R.string.haptics_background_summary_off
                                backgroundReasoningEnabled -> R.string.haptics_background_summary_reasoning
                                else -> R.string.haptics_background_summary_tools
                            },
                        ),
                        checked = backgroundOn,
                        enabled = generationOn,
                        onCheckedChange = { value ->
                            if (onToggle(Prefs.Keys.HAPTIC_BACKGROUND, value, backgroundOn)) {
                                backgroundEnabled = value
                            }
                        },
                    )
                }
                item(
                    key = "haptics_background_reasoning",
                    visible = touchEnabled && messageGenerationEnabled && backgroundEnabled,
                ) {
                    EtaSwitchRow(
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
                    )
                }
            }
        }
    }
}
