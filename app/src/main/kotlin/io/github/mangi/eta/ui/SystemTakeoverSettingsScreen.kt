package io.github.mangi.eta.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.EtaVoiceInteractionService
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.systemizer.GoogleAppSystemizerInstaller
import io.github.mangi.eta.systemizer.RootManager
import io.github.mangi.eta.systemizer.SystemizerInstallResult
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.app.EnhancementSettingsHistory
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import io.github.mangi.eta.ui.components.WindowSpinnerPreference
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统助手接管子页：原先散在设置主页的四个 hook 分组（系统助手接管 / 厂商助手兼容 /
 * Gemini / 一圈即搜）在此按原顺序呈现，主页只留一个入口行。
 *
 * 主页那些需要 hook 进程状态的副作用（RemotePreferences 绑定、连接历史、能力探测、
 * 长按电源键目标、助手活跃状态与系统化安装）一并搬到这里，可见性门控与主页保持一致。
 */
@Composable
internal fun SystemTakeoverSettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val capabilities = rememberDeviceCapabilities()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasConnectedFramework by remember { mutableStateOf(enhancementHistory.hasConnected) }
    var hasUsedSystemizer by remember { mutableStateOf(enhancementHistory.hasUsedSystemizer) }
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    val initialPreferences = remember {
        val service = EtaApp.serviceInstance
        val initialPrefs = Prefs.remotePreferencesForUi(service)
        InitialServicePreferenceSnapshot(service, initialPrefs)
    }
    var prefs by remember { mutableStateOf(initialPreferences.initialValueForUi()) }
    DisposableEffect(Unit) {
        // Only the immediate notification may reuse the successful initial read.
        // A failed initial read retries, and every subsequent service event refreshes.
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = initialPreferences.resolve(service) { currentService ->
                    Prefs.remotePreferencesForUi(currentService)
                }
                prefs?.let { connected ->
                    enhancementHistory.captureConnected(connected)
                    hasConnectedFramework = true
                }
                Prefs.reconcileAgentPreferences(service)
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { EtaApp.removeServiceStateListener(listener) }
    }

    // Eta 系统助手是否已接管：从系统设置返回时（ON_RESUME）刷新。
    var etaAssistantActive by remember {
        mutableStateOf(StreamPerformanceDiagnostics.measure("settings.permission.assistant.initial") { isEtaAssistantActive(context) })
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                etaAssistantActive =
                    StreamPerformanceDiagnostics.measure("settings.permission.assistant.resume") { isEtaAssistantActive(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val openAssistantSettings: () -> Unit = {
        val failed = runCatching {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isFailure
        if (failed) {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // 长按电源键目标：跟随 hook 侧写入的值刷新（远程提交成功后才改 UI 状态）。
    val powerAssistantTargets = PowerAssistantTarget.entries
    var powerAssistantTarget by remember(prefs) {
        mutableStateOf(prefs?.let(Prefs::powerAssistantTarget) ?: enhancementHistory.powerTarget())
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET ||
                key == Prefs.Keys.POWER_KEY_TAKEOVER
            ) {
                powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val powerAssistantItems = powerAssistantTargets.map { it.displayName(context) }

    EtaScaffoldPage(
        title = stringResource(R.string.ui_system_assistant_takes_over_f46043),
        onBack = onBack,
    ) {
        // ── 系统助手接管 ──────────────────────────────────────────────
        settingsGroup(
            title = { stringResource(R.string.ui_system_assistant_takes_over_f46043) },
            sectionModifier = Modifier,
        ) {
            row {
                EtaClickableRow(
                    title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                    summary = stringResource(
                        if (etaAssistantActive) {
                            R.string.settings_default_assistant_active
                        } else {
                            R.string.settings_select_default_assistant
                        },
                    ),
                    leading = {
                        PreferenceIcon(
                            icon = Icons.Rounded.AutoAwesome,
                        )
                    },
                    onClick = openAssistantSettings,
                )
            }

            if (prefs != null || hasConnectedFramework) {
                row {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                        summary = stringResource(R.string.ui_valid_only_for_gemini_and_eta_d5b63d),
                        key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                        icon = Icons.Rounded.Settings,
                    )
                }

                row {
                    WindowSpinnerPreference(
                        title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                        items = powerAssistantItems,
                        selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                        onSelectedIndexChange = { index ->
                            val target = powerAssistantTargets.getOrNull(index)
                            ?: return@WindowSpinnerPreference
                            val targetPrefs = prefs ?: return@WindowSpinnerPreference
                            if (putStringSync(
                                    prefs = targetPrefs,
                                    key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                                    value = target.persistedValue,
                                )
                            ) {
                                powerAssistantTarget = target
                                enhancementHistory.recordCommittedTarget(target)
                            } else {
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_write_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.PowerSettingsNew,
                                enabled = prefs != null,
                            )
                        },
                        enabled = prefs != null,
                    )
                }
            }
        }

        if (prefs != null || hasConnectedFramework) {
            // ── 厂商助手兼容入口 ──────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.ui_xiaobu_xiaoai_compatible_entrance_ae918a) },
                sectionModifier = Modifier,
            ) {
                row {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_enable_vendor_assistant_custom_models_c8e465),
                        key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                        icon = Icons.Rounded.Memory,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_only_take_over_with_agent_prefix_d17556),
                        key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                        icon = Icons.Rounded.Code,
                    )
                }
            }
        }

        if (prefs != null || hasConnectedFramework || capabilities.root.isGranted || hasUsedSystemizer) {
            // ── Gemini ─────────────────────────────────────────────────
            settingsGroup(
                title = { "Gemini" },
                sectionModifier = Modifier,
            ) {
                if (prefs != null || hasConnectedFramework) {
                    row {
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_maintain_hey_google_detection_after_screen_rest_9d6877),
                            key = Prefs.Keys.HOTWORD_SELF_HEAL,
                            icon = Icons.Rounded.Hearing,
                        )
                    }

                    row {
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_lock_screen_evokes_automatic_voice_input_1cde18),
                            key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                            icon = Icons.Rounded.Lock,
                        )
                    }

                    row {
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_bright_screen_evokes_automatic_voice_input_4358fe),
                            key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                            icon = Icons.Rounded.Mic,
                        )
                    }
                }

                if (capabilities.root.isGranted || hasUsedSystemizer) {
                    row {
                        EtaClickableRow(
                            title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
                            leading = {
                                PreferenceIcon(
                                    icon = Icons.Rounded.Inventory,
                                )
                            },
                            summary = if (capabilities.root.isGranted) null else stringResource(R.string.capability_root_required),
                            enabled = !installingSystemizer,
                            holdDownState = showSystemizerDialog,
                            onClick = {
                                if (!capabilities.root.isGranted) {
                                    onNavigate(AppRoute.SystemEnhance)
                                } else if (!installingSystemizer) {
                                    showSystemizerDialog = true
                                }
                            },
                        )
                    }
                }
            }
        }

        if (prefs != null || hasConnectedFramework) {
            // ── 一圈即搜 ────────────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.ui_search_in_one_turn_179584) },
                sectionModifier = Modifier,
            ) {
                row {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_long_press_on_the_gesture_bar_triggers_a_circle_to_s_b80117),
                        key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                        icon = Icons.Rounded.SwipeUp,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_long_press_with_two_fingers_to_trigger_a_circle_sear_ab597a),
                        key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                        icon = Icons.Rounded.TouchApp,
                    )
                }
            }
        }
    }

    SystemizerConfirmDialog(
        show = showSystemizerDialog,
        installing = installingSystemizer,
        onDismissRequest = {
            if (!installingSystemizer) {
                showSystemizerDialog = false
            }
        },
        onConfirm = {
            if (installingSystemizer) return@SystemizerConfirmDialog
            if (!capabilities.root.isGranted) {
                showSystemizerDialog = false
                onNavigate(AppRoute.SystemEnhance)
                return@SystemizerConfirmDialog
            }
            enhancementHistory.recordSystemizerUse()
            hasUsedSystemizer = true
            showSystemizerDialog = false
            installingSystemizer = true
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    GoogleAppSystemizerInstaller(context.applicationContext).install()
                }
                installingSystemizer = false
                Toast.makeText(
                    context.applicationContext,
                    result.toToastMessage(context),
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
    )
}

// ── 系统化确认对话框 ─────────────────────────────────────────────────────────

@Composable
private fun SystemizerConfirmDialog(
    show: Boolean,
    installing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    EtaDialog(
        show = show,
        title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
        summary = stringResource(R.string.ui_system_applications_have_voice_wake_up_permissions_f_0190f2),
        onDismissRequest = onDismissRequest,
        actions = {
            EtaDialogActions(
                confirmText = if (installing) {
                    stringResource(R.string.status_processing)
                } else {
                    stringResource(R.string.action_confirm)
                },
                cancelEnabled = !installing,
                confirmEnabled = !installing,
                onCancel = onDismissRequest,
                onConfirm = onConfirm,
            )
        },
    ) {
    }
}

private fun isEtaAssistantActive(context: Context): Boolean =
    VoiceInteractionService.isActiveService(
        context,
        ComponentName(context, EtaVoiceInteractionService::class.java),
    )

private fun SystemizerInstallResult.toToastMessage(context: Context): String =
    when (this) {
        SystemizerInstallResult.AlreadySystemized -> context.getString(R.string.systemizer_already_system)
        SystemizerInstallResult.GoogleAppMissing -> context.getString(R.string.systemizer_google_missing)
        SystemizerInstallResult.UnsupportedRootManager -> context.getString(R.string.systemizer_root_manager_missing)
        SystemizerInstallResult.KernelSuMetamoduleMissing -> context.getString(R.string.systemizer_metamodule_missing)
        is SystemizerInstallResult.RootPermissionUnavailable -> when (rootManager) {
            RootManager.KERNEL_SU -> context.getString(R.string.systemizer_grant_kernelsu)
            RootManager.MAGISK -> context.getString(R.string.systemizer_grant_magisk)
            RootManager.UNSUPPORTED -> context.getString(R.string.systemizer_root_denied)
        }
        is SystemizerInstallResult.InstalledRebootRequired -> context.getString(R.string.systemizer_installed)
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message：$it" }
            ?: message
    }
