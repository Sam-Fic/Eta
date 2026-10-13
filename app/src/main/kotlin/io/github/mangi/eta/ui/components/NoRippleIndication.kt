package io.github.mangi.eta.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme

/** 设置列表用：去掉 Material 默认的按压涟漪和阴影。 */
internal object NoRippleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0
}

/**
 * 关闭按压涟漪（MIUI X 的列表观感）。
 *
 * Material 3 主题下**不生效**：官方组件自带按压涟漪与形状形变，抑制掉会让按钮看起来
 * 没有点按反馈。这里统一做主题判断，调用方不必各自判断。
 */
@Composable
internal fun WithoutPressRipple(content: @Composable () -> Unit) {
    if (isMaterial3Theme()) {
        content()
        return
    }
    CompositionLocalProvider(LocalIndication provides NoRippleIndication, content = content)
}

/**
 * 给 `CompositionLocalProvider(LocalRippleConfiguration provides …)` 用的取值。
 *
 * 官方 `Ripple.kt` 文档写明：向 `LocalRippleConfiguration` 提供 `null` 会**彻底关闭**涟漪，
 * 官方组件（Button / IconButton / ListItem …）的按压反馈会全部消失。
 * 因此 MIUI X 下才关掉；Material 3 下把当前环境里的官方默认值原样传回去。
 *
 * 注意：必须在 `CompositionLocalProvider` 的**参数位置**调用，此时读到的
 * `LocalRippleConfiguration.current` 仍是外层（未被本次 provides 覆盖）的官方默认值。
 */
@Composable
internal fun etaRippleConfiguration(): RippleConfiguration? =
    if (isMaterial3Theme()) LocalRippleConfiguration.current else null
