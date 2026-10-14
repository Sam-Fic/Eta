package io.github.mangi.eta.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppUpdateOffer
import io.github.mangi.eta.data.repository.AppUpdateRepository
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.components.AppUpdateDialog
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.HapticBasicComponent
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.PaddingValues
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * 关于页（KernelSU `AboutMaterial` 对应物）：
 * 80dp 白底圆角图标 + 应用名 + 版本号的居中 hero，下方为分段链接列表。
 */
@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val currentVersion = remember { AppUpdateRepository.currentVersionName(context) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateOffer by remember { mutableStateOf<AppUpdateOffer?>(null) }
    var relayStationExpanded by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }

    EtaScaffoldPage(
        title = stringResource(R.string.ui_about_bed172),
        onBack = onBack,
    ) {
        item(key = "about_hero") {
            AboutHero(versionName = currentVersion)
        }
        item(key = "about_links") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            ) {
                item {
                    ArrowPreference(
                        title = stringResource(R.string.update_check_title),
                        summary = if (checkingUpdate) {
                            stringResource(R.string.update_checking)
                        } else {
                            stringResource(
                                R.string.update_current_version,
                                currentVersion.ifBlank { stringResource(R.string.update_unknown_version) },
                            )
                        },
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.CloudDownload,
                            )
                        },
                        onClick = {
                            if (checkingUpdate) return@ArrowPreference
                            checkingUpdate = true
                            coroutineScope.launch {
                                val result = AppUpdateRepository.checkForUpdate(context, force = true)
                                checkingUpdate = false
                                result.fold(
                                    onSuccess = { offer ->
                                        if (offer == null) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.update_latest),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } else {
                                            updateOffer = offer
                                        }
                                    },
                                    onFailure = { failure ->
                                        Toast.makeText(
                                            context,
                                            failure.message?.takeIf { it.isNotBlank() }
                                                ?: context.getString(R.string.update_check_failed),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            }
                        },
                    )
                }

                item {
                    ArrowPreference(
                        title = stringResource(R.string.about_telegram_channel),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Campaign,
                            )
                        },
                        endActions = {
                            SettingsStatusText(text = "Telegram")
                        },
                        onClick = {
                            openExternalUrl(
                                context,
                                "https://t.me/+6JhB2iRjjDExYWM1",
                                context.getString(R.string.about_telegram_open_failed),
                            )
                        },
                    )
                }

                item {
                    ArrowPreference(
                        title = stringResource(R.string.about_qq_group),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Groups,
                            )
                        },
                        endActions = {
                            SettingsStatusText(text = "QQ")
                        },
                        onClick = {
                            val opened = runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=735910197&card_type=group&source=qrcode"),
                                    )
                                )
                                true
                            }.getOrDefault(false)
                            if (!opened) {
                                openExternalUrl(
                                    context,
                                    "https://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=QZmmrmSQqSAyI8Mo5NHAMc9l6TejcBrw&authKey=mQrh5avqMDc09GunNdtCmuN5tk9UynOekkjHxfuKPjhN4ELWBMJN5edaYw51UXbQ&noverify=0&group_code=735910197",
                                    context.getString(R.string.about_qq_group_open_failed),
                                )
                            }
                        },
                    )
                }

                item {
                    ArrowPreference(
                        title = stringResource(R.string.ui_source_code_740296),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Code,
                            )
                        },
                        endActions = {
                            SettingsStatusText(text = "GitHub")
                        },
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/y2485871697/Eta"),
                            )
                            context.startActivity(intent)
                        },
                    )
                }

                item {
                    HapticBasicComponent(
                        title = stringResource(R.string.about_relay_station),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Language,
                            )
                        },
                        endActions = {
                            Icon(
                                imageVector = if (relayStationExpanded) {
                                    Icons.Rounded.ExpandMore
                                } else {
                                    Icons.Rounded.ChevronRight
                                },
                                contentDescription = null,
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .padding(end = 8.dp)
                                    .size(16.dp),
                                tint = if (isMaterial3Theme()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                },
                            )
                        },
                        holdDownState = relayStationExpanded,
                        onClick = { relayStationExpanded = !relayStationExpanded },
                        bottomAction = if (relayStationExpanded) {
                            {
                                Column {
                                    ArrowPreference(
                                        title = stringResource(R.string.about_relay_station_st_api),
                                        insideMargin = PaddingValues(0.dp),
                                        onClick = {
                                            openExternalUrl(
                                                context,
                                                "https://st.123336.xyz/join/ZKjSNbdYUh6RbF5_Kdxjrp-_AoAVcMH9",
                                                context.getString(R.string.about_relay_station_open_failed),
                                            )
                                        },
                                    )
                                    ArrowPreference(
                                        title = stringResource(R.string.about_relay_station_moyu),
                                        insideMargin = PaddingValues(0.dp),
                                        onClick = {
                                            openExternalUrl(
                                                context,
                                                "https://www.u354483.nyat.app:35119/register?aff=5U8U292F5RG6",
                                                context.getString(R.string.about_relay_station_open_failed),
                                            )
                                        },
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }

                item {
                    ArrowPreference(
                        title = stringResource(R.string.about_donate),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Restaurant,
                            )
                        },
                        onClick = { showDonateDialog = true },
                    )
                }
            }
        }
    }

    if (showDonateDialog) {
        DonateQrDialog(onDismiss = { showDonateDialog = false })
    }

    AppUpdateDialog(
        offer = updateOffer,
        currentVersion = currentVersion,
        onDismiss = { updateOffer = null },
    )
}

/**
 * 关于页 hero：80dp 白底 16dp 圆角图标 + 应用名 + 版本号（KernelSU `AboutMaterial` 布局）。
 */
@Composable
private fun AboutHero(versionName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .clip(MaterialTheme.shapes.large)
                .background(Color.White),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isMaterial3Theme()) {
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = stringResource(R.string.app_name),
                fontWeight = FontWeight.Medium,
                fontSize = MaterialTheme.typography.headlineMedium.fontSize,
            )
            Text(
                text = versionName,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            )
        } else {
            MiuixText(
                modifier = Modifier.padding(top = 12.dp),
                text = stringResource(R.string.app_name),
                style = MiuixTheme.textStyles.title3,
            )
            MiuixText(
                text = versionName,
                style = MiuixTheme.textStyles.body2,
            )
        }
    }
}

@Composable
private fun DonateQrDialog(onDismiss: () -> Unit) {
    var wechatSelected by remember { mutableStateOf(true) }
    val view = LocalView.current
    EtaDialog(
        show = true,
        title = stringResource(R.string.about_donate),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val wechatLabel = stringResource(R.string.about_donate_wechat)
            val alipayLabel = stringResource(R.string.about_donate_alipay)
            if (isMaterial3Theme()) {
                // 官方标签栏：指示器动画、容器配色与字阶全部由 Material 3 提供。
                PrimaryTabRow(
                    selectedTabIndex = if (wechatSelected) 0 else 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Tab(
                        selected = wechatSelected,
                        onClick = {
                            TouchHaptics.click(view)
                            wechatSelected = true
                        },
                        text = { Text(wechatLabel) },
                    )
                    Tab(
                        selected = !wechatSelected,
                        onClick = {
                            TouchHaptics.click(view)
                            wechatSelected = false
                        },
                        text = { Text(alipayLabel) },
                    )
                }
            } else {
                TabRow(
                    tabs = listOf(wechatLabel, alipayLabel),
                    selectedTabIndex = if (wechatSelected) 0 else 1,
                    onTabSelected = {
                        TouchHaptics.click(view)
                        wechatSelected = it == 0
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
            }
            Image(
                painter = painterResource(
                    if (wechatSelected) R.drawable.donate_wechat else R.drawable.donate_alipay,
                ),
                contentDescription = if (wechatSelected) {
                    stringResource(R.string.about_donate_wechat)
                } else {
                    stringResource(R.string.about_donate_alipay)
                },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
    }
}

private fun openExternalUrl(context: Context, url: String, failureMessage: String) {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse(url),
    )
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
    }
}
