package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.adaptive.EtaTextButton as AdaptiveEtaTextButton

/**
 * 主操作按钮的包内转发。
 *
 * 实现只有一个真身（[io.github.mangi.eta.ui.adaptive.EtaBasics] 里的 `EtaTextButton`）；
 * 这里只是把同一个签名暴露到 `ui.components` 路径下，让两种 import 都能用，
 * 避免同一个组件在两处各写一份实现后行为漂移。
 */
@Composable
fun EtaTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    AdaptiveEtaTextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    )
}
