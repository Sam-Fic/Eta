package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.SmallTitle
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (isMaterial3Theme()) {
        // 与 EtaSectionTitle 一致：KernelSU 式组标题，起点 16dp 与分段列表条目对齐。
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
    } else {
        SmallTitle(
            text = text,
            modifier = modifier,
        )
    }
}
