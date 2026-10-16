package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton

@Composable
internal fun ItemDescriptionDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    summary: String? = null,
) {
    EtaDialog(
        show = true,
        title = title,
        summary = summary?.takeIf(String::isNotBlank),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EtaText(
                text = description,
                style = EtaTextStyle.Secondary,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            )
            // 官方文字按钮：形状、行高与配色均取官方默认值。
            M3TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                M3Text(text = stringResource(R.string.action_close))
            }
        }
    }
}
