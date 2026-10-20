package io.github.mangi.eta.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow

/**
 * 回收站留存天数：决定软删除的对话在回收站里保留多久，过期后启动或打开回收站时自动彻底清理。
 *
 * 存储格式与 eta_set_settings 的 CHOICE 一致（"7"/"30"/"90"/"never"），所以这里的
 * 改动与模型改设置完全等价。
 */
@Composable
internal fun TrashRetentionPreference() {
    val values = listOf("7", "30", "90", "never")
    val labels = listOf(
        stringResource(R.string.settings_trash_retention_days_value, 7),
        stringResource(R.string.settings_trash_retention_days_value, 30),
        stringResource(R.string.settings_trash_retention_days_value, 90),
        stringResource(R.string.settings_trash_retention_never),
    )
    val stored = Prefs.getString(Prefs.Keys.TRASH_RETENTION_DAYS, "30")
    val selectedIndex = values.indexOf(stored).takeIf { it >= 0 } ?: 1
    EtaSegmentedDropdownRow(
        title = stringResource(R.string.settings_trash_retention_title),
        items = labels,
        selectedIndex = selectedIndex,
        onItemSelected = { index ->
            if (index in values.indices) {
                Prefs.putString(Prefs.Keys.TRASH_RETENTION_DAYS, values[index])
            }
        },
        icon = Icons.Rounded.DeleteSweep,
    )
}
