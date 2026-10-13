package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LinuxEnvironmentStatusCard(
    title: String,
    mode: String,
    summary: String,
    busy: Boolean,
    message: String?,
    actionText: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    EtaCard(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EtaText(text = title, style = EtaTextStyle.Title)
                EtaText(text = mode, style = EtaTextStyle.Footnote, secondary = true)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) TerminalBusyIndicator(size = 20.dp)
                EtaText(
                    text = summary,
                    style = EtaTextStyle.Body,
                    secondary = true,
                    modifier = Modifier.weight(1f),
                )
            }
            message?.let {
                EtaText(text = it, style = EtaTextStyle.Footnote, secondary = true)
            }
            actionText?.let {
                EtaTextButton(
                    text = it,
                    enabled = actionEnabled,
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun LinuxEnvironmentConfiguration(
    distribution: LinuxDistribution,
    backend: LinuxExecutionBackend,
    rootGranted: Boolean,
    enabled: Boolean,
    onDistributionSelected: (LinuxDistribution) -> Unit,
    onBackendSelected: (LinuxExecutionBackend) -> Unit,
) {
    val distributions = LinuxDistribution.entries
    val backends = listOf(LinuxExecutionBackend.PROOT, LinuxExecutionBackend.CHROOT)
    val backendSummaries = backends.map {
        stringResource(
            when (it) {
                LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot_summary
                LinuxExecutionBackend.CHROOT -> R.string.capability_linux_chroot_summary
            },
        )
    }
    val backendLabels = backends.map { it.displayName() }

    if (isMaterial3Theme()) {
        // M3：官方 Card + 官方 ExposedDropdownMenuBox 列表项（见 EtaDropdownRow），
        // 选中项的副标题即当前取值的说明。
        EtaCard(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            EtaDropdownRow(
                title = stringResource(R.string.linux_distribution_title),
                summary = distribution.displayName(),
                items = distributions.map { it.displayName() },
                selectedIndex = distributions.indexOf(distribution),
                enabled = enabled,
                onSelectedIndexChange = { onDistributionSelected(distributions[it]) },
            )
            if (rootGranted || backend == LinuxExecutionBackend.CHROOT) {
                EtaDropdownRow(
                    title = stringResource(R.string.capability_linux_backend),
                    summary = backendLabels[
                        backends.indexOf(backend).coerceAtLeast(0)
                    ] + " · " + backendSummaries[
                        backends.indexOf(backend).coerceAtLeast(0)
                    ],
                    items = backendLabels,
                    selectedIndex = backends.indexOf(backend),
                    enabled = enabled,
                    onSelectedIndexChange = { onBackendSelected(backends[it]) },
                )
            } else {
                EtaClickableRow(
                    title = stringResource(R.string.capability_linux_backend),
                    summary = backend.displayName(),
                    onClick = null,
                    showChevron = false,
                )
            }
        }
        return
    }

    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        WindowSpinnerPreference(
            title = stringResource(R.string.linux_distribution_title),
            items = distributions.map {
                DropdownItem(
                    text = it.displayName(),
                    summary = stringResource(
                        when (it) {
                            LinuxDistribution.ALPINE -> R.string.linux_distribution_alpine_summary
                            LinuxDistribution.DEBIAN -> R.string.linux_distribution_debian_summary
                        },
                    ),
                )
            },
            selectedIndex = distributions.indexOf(distribution),
            enabled = enabled,
            onSelectedIndexChange = { onDistributionSelected(distributions[it]) },
        )
        if (rootGranted || backend == LinuxExecutionBackend.CHROOT) {
            WindowSpinnerPreference(
                title = stringResource(R.string.capability_linux_backend),
                items = backends.map {
                    DropdownItem(
                        text = it.displayName(),
                        summary = stringResource(
                            when (it) {
                                LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot_summary
                                LinuxExecutionBackend.CHROOT -> R.string.capability_linux_chroot_summary
                            },
                        ),
                        enabled = it == LinuxExecutionBackend.PROOT || rootGranted,
                    )
                },
                selectedIndex = backends.indexOf(backend),
                enabled = enabled,
                onSelectedIndexChange = { onBackendSelected(backends[it]) },
            )
        } else {
            BasicComponent(
                title = stringResource(R.string.capability_linux_backend),
                endActions = {
                    Text(
                        text = backend.displayName(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
            )
        }
    }
}

@Composable
internal fun LinuxDistribution.displayName(): String = stringResource(
    when (this) {
        LinuxDistribution.ALPINE -> R.string.linux_distribution_alpine
        LinuxDistribution.DEBIAN -> R.string.linux_distribution_debian
    },
)

@Composable
internal fun LinuxExecutionBackend.displayName(): String = stringResource(
    when (this) {
        LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot
        LinuxExecutionBackend.CHROOT -> R.string.capability_linux_chroot
    },
)
