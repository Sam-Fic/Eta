package io.github.mangi.eta.agent.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 小米 / 红米超级岛（HyperOS Focus Island）适配。
 *
 * 思路与 [HaoZai000/NexioSchedule](https://github.com/HaoZai000/NexioSchedule) 一致：
 * 超级岛不是 Android 公共通知 API，而是 MIUI 私有的「焦点通知」协议。发送一条带
 * `extra: miui.focus.param`（param_v2 JSON）与 `extra: miui.focus.pics`（图标集合）的
 * 普通通知，SystemUI 的焦点服务识别后就会把该通知渲染为状态栏胶囊 / 展开大岛。
 *
 * 本 helper 与 Eta 自绘的状态胶囊浮层完全独立；开关 `island_enabled` 只控制是否向
 * 超级岛发布运行状态，不影响原有浮层与 Android 16 Live Updates 通知。
 */
internal object IslandNotificationHelper {
    private const val CHANNEL_ID = "eta_island"
    private const val BUSINESS_TAG = "eta_agent"

    // 不同状态组使用不同通知 ID：同 ID 的静默替换在岛框架下可能不刷新内容，切换
    // 状态组前先 cancel 兄弟 ID（与 NexioSchedule 的 siblingIdsFor 级联取消一致）。
    private const val RUNNING_ISLAND_ID = 1201
    private const val PAUSED_ISLAND_ID = 1202
    private const val FINAL_ISLAND_ID = 1203

    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val XMSF_BYPASS_DELAY_MS = 100L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 串行化 XMSF 断网上送，避免并发把小米服务框架的网络状态弄乱。 */
    private val bypassMutex = Mutex()
    /** param_v2.sequence 单调递增，保证多次更新不乱序。 */
    private val sequenceCounter = AtomicLong(System.currentTimeMillis() / 1000)

    /** 设备是否声明支持超级岛（MIUI/HyperOS 私有属性）。 */
    @Volatile
    private var supported: Boolean? = null

    /** 上次发布的状态签名；只有「状态组 | 状态文字」变化才重新发布，避免流式详情刷爆通知。 */
    @Volatile
    private var lastSignature: String? = null

    /**
     * 每次 [dismiss] 递增；发布协程在真正 notify 前核对，避免「setter 发布完成态 →
     * 立即 dismissAndStop」竞态导致完成态通知在清理之后才落地。
     */
    @Volatile
    private var dismissGeneration = 0L

    private enum class IslandGroup(val id: Int) {
        RUNNING(RUNNING_ISLAND_ID),
        PAUSED(PAUSED_ISLAND_ID),
        FINAL(FINAL_ISLAND_ID),
        ;
    }

    /** 镜像 AgentOverlayPhase，避开对 Compose 状态的直接强依赖。 */
    private fun groupFor(phase: AgentOverlayPhase): IslandGroup = when (phase) {
        AgentOverlayPhase.RUNNING -> IslandGroup.RUNNING
        AgentOverlayPhase.PAUSED -> IslandGroup.PAUSED
        AgentOverlayPhase.FINISHED, AgentOverlayPhase.FAILED -> IslandGroup.FINAL
    }

    private fun allIds(): IntArray = intArrayOf(RUNNING_ISLAND_ID, PAUSED_ISLAND_ID, FINAL_ISLAND_ID)

    private fun siblingIds(group: IslandGroup): IntArray = when (group) {
        IslandGroup.RUNNING -> intArrayOf(PAUSED_ISLAND_ID, FINAL_ISLAND_ID)
        IslandGroup.PAUSED -> intArrayOf(RUNNING_ISLAND_ID, FINAL_ISLAND_ID)
        IslandGroup.FINAL -> intArrayOf(RUNNING_ISLAND_ID, PAUSED_ISLAND_ID)
    }

    /**
     * 运行状态变化入口。由 [io.github.mangi.eta.agent.runtime.AgentRuntimeService] 的
     * state setter 在主线程调用（与 [io.github.mangi.eta.agent.runtime.AgentExecutionService]
     * 的更新共点），内部自行节流并调度到 IO 线程发布。
     */
    fun onRunStateChanged(context: Context, state: AgentOverlayState) {
        if (!isEnabled(context)) {
            dismiss(context)
            return
        }
        val group = groupFor(state.phase)
        val statusText = state.status.localizedText(context.resources)
        val signature = "${group.id}|$statusText"
        val previous = lastSignature
        if (previous == signature) return
        val switchedGroup = previous != null && previous.substringBefore('|') != "${group.id}"
        if (switchedGroup) {
            siblingIds(group).forEach { id ->
                context.getSystemService(NotificationManager::class.java).cancel(id)
            }
        }
        lastSignature = signature
        val content = state.thought.takeIf { it.isNotBlank() }
            ?: state.detailText.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.app_name)
        val generation = dismissGeneration
        scope.launch {
            sendIslandNotification(
                context = context,
                notificationId = group.id,
                title = statusText,
                content = content,
                generation = generation,
            )
        }
    }

    /** 收起全部岛通知；服务销毁 / 结果卡片关闭时调用。 */
    fun dismiss(context: Context) {
        dismissGeneration++
        lastSignature = null
        val manager = context.getSystemService(NotificationManager::class.java)
        allIds().forEach { id -> manager.cancel(id) }
    }

    private fun isEnabled(context: Context): Boolean =
        Prefs.isEnabled(Prefs.Keys.ISLAND_ENABLED) && isIslandSupported(context)

    /** 反射读取 MIUI 私有属性，判断设备是否支持超级岛；结果缓存，避免每次状态变化反射。 */
    fun isIslandSupported(context: Context): Boolean {
        supported?.let { return it }
        val detected = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                clazz, null, "getBoolean", "persist.sys.feature.island", false,
            ) as Boolean
        }.getOrDefault(false)
        supported = detected
        if (!detected) {
            AndroidAgentLogger.info("Island feature not detected; miui.focus notifications are skipped")
        }
        return detected
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.island_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.island_channel_description)
                setShowBadge(true)
                // 与公共执行频道同理：不放开的话「免打扰」会把超级岛一并屏蔽。
                setBypassDnd(true)
            },
        )
    }

    /** 发送一条岛通知：普通通知 + miui.focus 私有 extras。必须在 build() 之后再写 param。 */
    private fun sendIslandNotification(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        glow: Boolean = false,
        generation: Long = 0L,
    ) {
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)

        val pics = Bundle().apply {
            putParcelable("miui.focus.pic_app_icon", Icon.createWithResource(context, R.mipmap.ic_launcher))
            putParcelable("miui.focus.pic_app_icon_dark", Icon.createWithResource(context, R.mipmap.ic_launcher))
            putParcelable("miui.focus.pic_small", Icon.createWithResource(context, R.mipmap.ic_launcher))
            putParcelable("miui.focus.pic_small_dark", Icon.createWithResource(context, R.mipmap.ic_launcher))
        }
        builder.addExtras(Bundle().apply { putBundle("miui.focus.pics", pics) })

        val notification = builder.build()
        notification.extras.putString("miui.focus.param", buildIslandParamsJson(context, title, content, glow))

        sendWithBypass(context, notificationId, notification, generation)
    }

    /**
     * 构建 param_v2 JSON。Eta 的 Agent 状态是实时语义（思考 / 执行工具 / 生成回答…），
     * 没有倒计时，因此统一使用静态态（islandFirstFloat=true + enableFloat=false）：
     * 只以状态栏胶囊 + 展开岛呈现，不额外弹悬浮窗，避免与 Eta 自绘的胶囊浮层视觉重叠。
     */
    private fun buildIslandParamsJson(
        context: Context,
        title: String,
        content: String,
        glow: Boolean,
    ): String {
        val appName = context.getString(R.string.app_name)
        JSONObject().apply {
            put("param_v2", JSONObject().apply {
                put("business", BUSINESS_TAG)
                put("protocol", 1)
                put("islandFirstFloat", true)
                put("enableFloat", false)
                put("updatable", true)
                put("outEffectSrc", if (glow) "outer_glow" else "")
                put("reopen", "reopen")
                put("sequence", sequenceCounter.incrementAndGet())
                put("aodTitle", appName)

                put("baseInfo", JSONObject().apply {
                    put("type", 2)
                    put("title", appName)
                    put("content", title)
                    put("subTitle", "")
                    put("extraTitle", "")
                    put("specialTitle", "")
                    put("subContent", "")
                    put("picFunction", "")
                    put("showDivider", true)
                    put("showContentDivider", false)
                    put("colorTitle", "#111111")
                    put("colorTitleDark", "#ffffff")
                    put("colorContent", "#333333")
                    put("colorContentDark", "#cccccc")
                })

                put("picInfo", JSONObject().apply {
                    put("type", 1)
                    put("pic", "")
                })

                put("hintInfo", JSONObject().apply {
                    put("type", 2)
                    put("title", title)
                    put("content", content)
                    put("timerInfo", JSONObject().apply {
                        put("timerType", 0)
                        put("timerWhen", 0)
                        put("timerTotal", 0)
                        put("timerSystemCurrent", 0)
                    })
                    put("subContent", "")
                    put("subTitle", content)
                    put("colorContent", "#666666")
                    put("colorContentDark", "#aaaaaa")
                    put("colorTitle", "#222222")
                    put("colorTitleDark", "#eeeeee")
                    put("colorSubContent", "#666666")
                    put("colorSubContentDark", "#aaaaaa")
                    put("colorSubTitle", "#222222")
                    put("colorSubTitleDark", "#eeeeee")
                })

                put("param_island", JSONObject().apply {
                    put("islandProperty", 1)
                    put("islandTimeout", 3600)

                    put("bigIslandArea", JSONObject().apply {
                        put("templateNo", 2)
                        put("imageTextInfoLeft", JSONObject().apply {
                            put("type", 1)
                            put("textInfo", JSONObject().apply {
                                put("title", appName)
                                put("content", "")
                                put("showHighlightColor", false)
                                put("narrowFont", false)
                            })
                        })
                        put("textInfo", JSONObject().apply {
                            put("frontTitle", "")
                            put("title", title)
                            put("content", content)
                            put("showHighlightColor", false)
                            put("narrowFont", false)
                        })
                    })

                    put("smallIslandArea", JSONObject().apply {
                        put("picInfo", JSONObject().apply {
                            put("type", 1)
                            put("pic", "miui.focus.pic_small")
                            put("picDark", "miui.focus.pic_small_dark")
                        })
                    })
                })
            })
        }.let { return it.toString() }
    }

    /**
     * 非系统白名单应用直接发岛通知时，焦点服务可能把它的通知降级为普通通知。
     * NexioSchedule 的做法是短暂断开 com.xiaomi.xmsf 的联网（白名单查询走 xmsf），
     * 让校验失效后再 notify，随后立即恢复。Eta 没有 Shizuku，改用 root 权限通过
     * netfilter 防火墙链实现同样的「断 100ms」窗口；root 不可用或命令失败时自动
     * 降级为直接发送，不影响通知本体。
     */
    private fun sendWithBypass(
        context: Context,
        notificationId: Int,
        notification: Notification,
        generation: Long,
    ) {
        scope.launch {
            bypassMutex.withLock {
                var blocked = false
                if (RootAccess.isGranted) {
                    blocked = setXmsfBlocked(context, blocked = true)
                }
                try {
                    sendNotificationDirect(context, notificationId, notification, generation)
                    if (blocked) delay(XMSF_BYPASS_DELAY_MS)
                } finally {
                    if (blocked) {
                        setXmsfBlocked(context, blocked = false)
                    }
                }
            }
        }
    }

    private fun sendNotificationDirect(
        context: Context,
        notificationId: Int,
        notification: Notification,
        generation: Long,
    ) {
        if (dismissGeneration != generation) return
        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    /**
     * 通过 root 修改 mangle 表 oem_out/oem_in 链（对应 Android netd 的 OEM_DENY 防火墙
     * 链，即 IConnectivityManager.setFirewallChainEnabled(9) / setUidFirewallRule(9, uid, 2)
     * 的 netfilter 等价物），按 uid 放行/丢弃 com.xiaomi.xmsf 的流量。
     *
     * 注意：部分定制 ROM 的链名可能不同；规则是否真正生效由返回的 ok 判定，任何失败都
     * 只意味着跳过 bypass 直接发送，不会影响通知本身。
     */
    private fun setXmsfBlocked(context: Context, blocked: Boolean): Boolean {
        val uid = runCatching {
            context.packageManager.getPackageUid(XMSF_PACKAGE, 0)
        }.getOrNull() ?: return false
        val command = if (blocked) {
            // 先确保链存在（已存在时 -N 失败被吞），再用 -C 幂等检查规则、-I 插入 DROP
            "iptables -t mangle -N oem_out 2>/dev/null; " +
                "iptables -t mangle -C oem_out -m owner --uid-owner $uid -j DROP 2>/dev/null || " +
                "iptables -t mangle -I oem_out 1 -m owner --uid-owner $uid -j DROP; " +
                "iptables -t mangle -N oem_in 2>/dev/null; " +
                "iptables -t mangle -C oem_in -m owner --uid-owner $uid -j DROP 2>/dev/null || " +
                "iptables -t mangle -I oem_in 1 -m owner --uid-owner $uid -j DROP; " +
                "echo ETA_XMSF_BLOCK_OK"
        } else {
            "iptables -t mangle -D oem_out -m owner --uid-owner $uid -j DROP 2>/dev/null; " +
                "iptables -t mangle -D oem_in -m owner --uid-owner $uid -j DROP 2>/dev/null; " +
                "echo ETA_XMSF_UNBLOCK_OK"
        }
        return try {
            val result = BoundedRootCommandExecutor(AndroidAgentLogger).use {
                it.execute(command, timeoutMillis = 8_000L, maxOutputBytes = 4 * 1024)
            }
            val ok = result.ok && (
                if (blocked) result.stdout.contains("ETA_XMSF_BLOCK_OK")
                else result.stdout.contains("ETA_XMSF_UNBLOCK_OK")
                )
            if (!ok) {
                AndroidAgentLogger.warn("Island XMSF bypass skipped: blocked=$blocked exit=${result.exitCode}")
            }
            ok
        } catch (failure: Exception) {
            AndroidAgentLogger.warn("Island XMSF bypass failed: type=${failure::class.java.simpleName}")
            false
        }
    }
}