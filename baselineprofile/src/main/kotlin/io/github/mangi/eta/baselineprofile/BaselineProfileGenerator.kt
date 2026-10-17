package io.github.mangi.eta.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test

/**
 * 基准配置文件生成器：把应用最常滑动的界面纳入 AOT 编译范围。
 *
 * Compose 的列表滚动性能对运行早期的 JIT / 类加载非常敏感；官方建议为
 * 关键用户历程（CUJ）生成 Baseline Profile，可显著降低滑动掉帧。
 *
 * 生成（需连接实体机或模拟器，且本地存在 keystore.properties）：
 *
 * ```
 * ./gradlew :app:generateBaselineProfile
 * ```
 *
 * 注意：
 * - 生成的配置只会打进 release 构建；debug 构建不受影响。
 * - 各 CUJ 的界面查找均为空安全，首次运行的引导页 / 弹窗不会让生成崩溃，
 *   但会减少该次采样覆盖；稳定复现时请先手动完成初始化（选好模型供应商等）。
 */
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    /** 冷启动 + 首页（会话列表）首帧。 */
    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        startActivityAndWait()
        device.waitForIdle()
    }

    /** 会话消息列表（AgentChatBody 的 LazyColumn）上下滚动。 */
    @Test
    fun conversationScroll() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = false,
    ) {
        startActivityAndWait()
        device.waitForIdle()
        scrollList(iterations = 4)
    }

    /** 抽屉会话列表滚动 + 设置页列表滚动。 */
    @Test
    fun settingsScroll() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = false,
    ) {
        startActivityAndWait()
        device.waitForIdle()

        // 打开会话抽屉，滚动抽屉内的会话列表。
        device.findObject(By.desc(MENU_CONTENT_DESCRIPTION))?.click()
        device.waitForIdle()
        scrollList(iterations = 2)

        // 进入设置页，滚动设置页列表（LargeTopAppBar 折叠路径一并覆盖）。
        device.findObject(By.desc(SETTINGS_CONTENT_DESCRIPTION))?.click()
        device.waitForIdle()
        scrollList(iterations = 4)
    }

    /** 在屏幕中部反复上滑 / 下滑：滚动当前可见的任意列表。 */
    private fun MacrobenchmarkScope.scrollList(iterations: Int) {
        val width = device.displayWidth
        val height = device.displayHeight
        val top = (height * 0.35f).toInt()
        val bottom = (height * 0.75f).toInt()
        val centerX = width / 2
        repeat(iterations) {
            device.swipe(centerX, bottom, centerX, top, SWIPE_STEPS)
            device.waitForIdle()
        }
        repeat(iterations) {
            device.swipe(centerX, top, centerX, bottom, SWIPE_STEPS)
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "io.github.mangi.eta"
        const val SWIPE_STEPS = 24
        const val MENU_CONTENT_DESCRIPTION = "Conversation history"
        const val SETTINGS_CONTENT_DESCRIPTION = "Settings"
    }
}
