package io.github.mangi.eta.ui.components

/**
 * A popup entry belongs to one real configuration owner (including the unique draft key).
 * Reopening invalidates previously captured callbacks even when the owner is the same.
 *
 * 该工具类原先供「会话协作」整页与模型选择按钮做回调围栏；整页删除后生产代码不再使用，
 * 仅编辑器 / 弹窗所有权契约测试仍以它构造「票据失效」场景，故随测试保留。
 */
internal class OwnerBoundPopup<Owner>(val owner: Owner) {
    private var active: Any? = null
    fun open(): Any = Any().also { active = it }
    fun dismiss() { active = null }
    fun isCurrent(ticket: Any?, current: OwnerBoundPopup<Owner>): Boolean =
        ticket != null && current === this && active === ticket
    fun dispatch(ticket: Any?, current: OwnerBoundPopup<Owner>, action: () -> Unit): Boolean {
        if (!isCurrent(ticket, current)) return false
        action()
        return true
    }
}
