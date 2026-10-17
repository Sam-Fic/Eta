package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M3 的 PopupPositionProvider 只提供 anchorBounds/windowSize/popupContentSize，
 * 没有 miuix 的 alignment 与 popupMargin，所以这里的断言按当前实现直接推导。
 */
class InputPopupPositionProviderTest {
    @Test
    fun calculatePosition_alignsToWindowEndAboveInputContainer() {
        val result = InputPopupPositionProvider(
            inputContainerTopPx = 1_800,
            windowHorizontalInsetPx = 40,
        ).calculatePosition(
            anchorBounds = IntRect(left = 400, top = 1_850, right = 500, bottom = 1_900),
            windowSize = IntSize(width = 1_080, height = 2_200),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(width = 300, height = 600),
        )

        // x 贴窗口末端内边距：1080 - 300 - 40；y 贴在输入容器上沿：1800 - 600。
        assertEquals(740, result.x)
        assertEquals(1_200, result.y)
    }

    @Test
    fun calculatePosition_clampsToWindowSafeBounds() {
        val result = InputPopupPositionProvider(inputContainerTopPx = 300).calculatePosition(
            anchorBounds = IntRect(left = -50, top = 320, right = 30, bottom = 360),
            windowSize = IntSize(width = 1_032, height = 2_128),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(width = 400, height = 500),
        )

        // 锚点越界（左侧 < 0）、内容高于输入容器上沿，都收敛到窗口原点。
        assertEquals(0, result.x)
        assertEquals(0, result.y)
    }
}
