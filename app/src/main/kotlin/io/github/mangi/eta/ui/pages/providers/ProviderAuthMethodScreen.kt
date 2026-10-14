package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderAuthMode
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType

@Composable
internal fun ProviderAuthMethodScreen(
    providerType: NewProviderType,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    EtaScaffoldPage(
        title = stringResource(R.string.provider_auth_method_title),
        onBack = onBack,
    ) {
        item(key = "methods") {
            // KernelSU 式分段列：每个认证方式独立分段（首末大圆角 + 2dp 缝隙），
            // 行带尾部箭头（M3 = 官方 SegmentedListItem，MIUI X = ArrowPreference）。
            EtaSegmentedColumn(
                modifier = ProviderSectionGroupPadding,
                title = stringResource(R.string.provider_auth_method_choose),
            ) {
                item {
                    EtaClickableRow(
                        title = stringResource(R.string.provider_auth_api_key),
                        summary = stringResource(R.string.provider_auth_api_key_summary),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Key) },
                        onClick = {
                            onNavigate(
                                AppRoute.ModelProviderNew(
                                    providerType = providerType,
                                    authMode = ProviderAuthMode.API_KEY,
                                ),
                            )
                        },
                    )
                }
                item {
                    EtaClickableRow(
                        title = stringResource(R.string.provider_auth_oauth),
                        summary = stringResource(R.string.provider_auth_oauth_summary),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Person) },
                        onClick = {
                            onNavigate(
                                AppRoute.ModelProviderNew(
                                    providerType = providerType,
                                    authMode = ProviderAuthMode.OAUTH,
                                ),
                            )
                        },
                    )
                }
            }
        }
        item(key = "note") {
            EtaText(
                text = stringResource(R.string.provider_auth_method_note),
                style = EtaTextStyle.Footnote,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
