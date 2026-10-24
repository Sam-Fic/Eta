package io.github.mangi.eta.agent.tool

import android.content.Context
import android.content.Intent
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import android.provider.Settings
import io.github.mangi.eta.agent.device.DeviceToolContract
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 个人热点：状态读取走 TetheringManager 回调；开启/关闭需要系统特权，
 * 普通应用被拒时打开热点设置面板让用户手动完成，不伪装成已生效。
 *
 * TetheringManager 这整套（含 TetheringInterface / TetheringRequest / 各类回调）
 * 都是 API 36 才有的，而 minSdk 是 34：低版本必须整条路短接，不能只把写操作
 * 包在版本判断里，否则读路径一样会在旧系统上找不到类。
 */
internal class HotspotControls(private val context: Context) {
    fun read(): JSONObject {
        // 版本判断必须是 Build.VERSION.SDK_INT 的裸形式：
        // lint 的 NewApi 只认这个形状，包成辅助函数再靠返回值推断它认不出来。
        if (Build.VERSION.SDK_INT < 36) {
            return DeviceToolContract.failure(
                "HOTSPOT_UNAVAILABLE",
                "当前系统版本不支持读取热点状态（需 Android 16 及以上）",
            )
        }
        val manager = context.getSystemService(TetheringManager::class.java)
            ?: return DeviceToolContract.failure("HOTSPOT_UNAVAILABLE", "热点服务不可用")
        return try {
            val state = observe(manager, 2_000L) ?: return DeviceToolContract.failure(
                "HOTSPOT_STATE_UNAVAILABLE",
                "系统没有在限定时间内返回热点状态；请稍后重试或到设置中查看",
            )
            JSONObject().put("ok", true).put("tool", "get_hotspot").put("enabled", state)
        } catch (_: SecurityException) {
            DeviceToolContract.failure("HOTSPOT_ACCESS_DENIED", "系统拒绝读取热点状态")
        } catch (_: Exception) {
            DeviceToolContract.failure("HOTSPOT_STATE_UNAVAILABLE", "无法读取热点状态")
        }
    }

    fun set(enabled: Boolean): JSONObject {
        // 低版本连读都读不了（TetheringManager 整体 API 36），直接走设置面板。
        if (Build.VERSION.SDK_INT < 36) return openPanel(enabled)
        val manager = context.getSystemService(TetheringManager::class.java)
            ?: return openPanel(enabled)
        val before = runCatching { observe(manager, 2_000L) }.getOrNull()
        if (before == enabled) {
            return DeviceMutationResult.observed(
                "set_hotspot",
                DeviceToolContract.appUserId(context),
                "device",
                before,
                before,
                enabled,
                true,
            )
        }
        if (Build.VERSION.SDK_INT >= 36) {
            val accepted = runCatching { requestTethering(manager, enabled) }.getOrNull()
            if (accepted != null) {
                val after = runCatching { observe(manager, 5_000L) }.getOrNull()
                val result = DeviceMutationResult.observed(
                    "set_hotspot",
                    DeviceToolContract.appUserId(context),
                    "device",
                    before,
                    after,
                    enabled,
                    accepted,
                )
                if (result.optBoolean("ok")) return result
            }
        }
        return openPanel(enabled)
    }

    private fun openPanel(enabled: Boolean): JSONObject {
        val launched = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        return if (launched) {
            JSONObject()
                .put("ok", false)
                .put("code", "HOTSPOT_PANEL_FALLBACK")
                .put("tool", "set_hotspot")
                .put("message", "普通应用没有修改热点的系统特权，已打开无线设置；请在面板中${if (enabled) "开启" else "关闭"}热点。不要重复调用本工具")
        } else {
            DeviceToolContract.failure(
                "HOTSPOT_PERMISSION_REQUIRED",
                "没有修改热点的系统特权，也无法打开设置面板；请手动在系统设置中操作",
            )
        }
    }

    // 下面两个方法直接摸 API 36 的类型（TetheringEventCallback / TetheringRequest 等），
    // 两个调用点都已经过了 Build.VERSION.SDK_INT 的版本门禁，所以用 @RequiresApi 把这条
    // 约束交给 lint 静态执行，而不是靠读代码的人自己追。
    @androidx.annotation.RequiresApi(36)
    private fun observe(manager: TetheringManager, timeoutMs: Long): Boolean? {
        val executor = Executor { it.run() }
        val state = AtomicReference<Boolean?>(null)
        val latch = CountDownLatch(1)
        val callback =
            object : TetheringManager.TetheringEventCallback {
                override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                    state.set(interfaces.any { it.type == TetheringManager.TETHERING_WIFI })
                    latch.countDown()
                }
            }
        manager.registerTetheringEventCallback(executor, callback)
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            state.get()
        } finally {
            manager.unregisterTetheringEventCallback(callback)
        }
    }

    @androidx.annotation.RequiresApi(36)
    private fun requestTethering(manager: TetheringManager, enabled: Boolean): Boolean? {
        val executor = Executor { it.run() }
        val done = CountDownLatch(1)
        val accepted = AtomicReference<Boolean?>(null)
        val request = TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build()
        if (enabled) {
            manager.startTethering(
                request,
                executor,
                object : TetheringManager.StartTetheringCallback {
                    override fun onTetheringStarted() {
                        accepted.set(true)
                        done.countDown()
                    }

                    override fun onTetheringFailed(error: Int) {
                        accepted.set(false)
                        done.countDown()
                    }
                },
            )
        } else {
            manager.stopTethering(
                request,
                executor,
                object : TetheringManager.StopTetheringCallback {
                    override fun onStopTetheringSucceeded() {
                        accepted.set(true)
                        done.countDown()
                    }

                    override fun onStopTetheringFailed(error: Int) {
                        accepted.set(false)
                        done.countDown()
                    }
                },
            )
        }
        done.await(5, TimeUnit.SECONDS)
        return accepted.get()
    }
}
