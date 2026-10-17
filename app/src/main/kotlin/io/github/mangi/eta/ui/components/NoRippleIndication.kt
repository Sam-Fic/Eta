package io.github.mangi.eta.ui.components

import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable

/**
 * 设置列表用的包裹层。官方 M3 组件自带按压涟漪与形状形变，不再抑制涟漪，这里直接透传内容。
 */
@Composable
internal fun WithoutPressRipple(content: @Composable () -> Unit) {
    content()
}

/**
 * 给 `CompositionLocalProvider(LocalRippleConfiguration provides …)` 用的取值。
 *
 * 官方 `Ripple.kt` 文档写明：向 `LocalRippleConfiguration` 提供 `null` 会**彻底关闭**涟漪，
 * 官方组件（Button / IconButton / ListItem …）的按压反馈会全部消失。因此把当前环境里的官方
 * 默认值原样传回去。
 *
 * 注意：必须在 `CompositionLocalProvider` 的**参数位置**调用，此时读到的
 * `LocalRippleConfiguration.current` 仍是外层（未被本次 provides 覆盖）的官方默认值。
 */
@Composable
internal fun etaRippleConfiguration(): RippleConfiguration? = LocalRippleConfiguration.current
