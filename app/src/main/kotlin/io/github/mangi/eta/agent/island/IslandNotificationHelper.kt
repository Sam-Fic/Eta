package io.github.mangi.eta.agent.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.islandShortText
import io.github.mangi.eta.agent.overlay.islandToolName
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 小米 / 红米超级岛（HyperOS Focus Island）适配。
 *
 * 超级岛不是 Android 公共通知 API，而是 MIUI 私有的「焦点通知」协议：一条普通通知携带
 * `extra: miui.focus.param`（param_v2 JSON）与 `extra: miui.focus.pics`（图标集合）后，
 * SystemUI 的焦点服务会把它渲染成状态栏胶囊与展开大岛。
 *
 * param_v2 与图片 Bundle 交给 [HyperIslandNotification]（HyperIsland-ToolKit）按官方模板
 * 构建，避免手写 JSON 的字段结构与模板编号随 HyperOS 版本失配；Eta 这一侧保留设备支持
 * 检测、状态节流、状态组通知 ID、竞态防护，以及无白名单时用 root 断网窗口投递的 XMSF bypass。
 *
 * 岛有两个状态，字段与模板完全不同，不能混用：
 * - **摘要态**：常驻的挖孔区胶囊（小岛）与左右分区的大岛，字段在 `param_island` 下；
 * - **展开态**：点按岛后弹出的卡片，字段是 `param_v2` 顶层模板（`baseInfo`、`picInfo`…）；
 * 两者的组合都必须从官方模板里挑，规范明确「模板不可自行拼接」。开关只影响摘要态常驻展示，
 * 展开态由用户点按触发。
 *
 * 本 helper 与 Eta 自绘的状态胶囊浮层完全独立；开关 `island_enabled` 只控制是否向
 * 超级岛发布运行状态，不影响原有浮层与 Android 16 Live Updates 通知。
 */
internal object IslandNotificationHelper {
    private const val CHANNEL_ID = "eta_island"
    private const val BUSINESS_TAG = "eta_agent"

    /** param_v2 与图片集合在通知 extras 中的私有键。 */
    private const val PARAM_EXTRA_KEY = "miui.focus.param"
    private const val PICS_EXTRA_KEY = "miui.focus.pics"
    private const val PICS_KEY_PREFIX = "miui.focus.pic_"

    /** 胶囊与大岛共用的图标 key；库写入 extra 时会自行补 `miui.focus.pic_` 前缀。 */
    private const val APP_ICON_KEY = "eta_app_icon"

    /** 岛屿优先级；任务结束时由 [dismiss] 显式收起，超时只作为兜底。 */
    private const val ISLAND_PRIORITY = 2

    // 单位以小米《超级岛开发指南》为准：param_v2.timeout 是**分钟**（默认 720），
    // param_island.islandTimeout 是**秒**（默认 3600）。
    private const val NOTIFICATION_TIMEOUT_MINUTES = 720L
    private const val ISLAND_TIMEOUT_SECONDS = 3600

    /** 官方限制：`miui.focus.param` 不超过 3072 字节。 */
    private const val PARAM_MAX_BYTES = 3072

    /** 展开态次要文本的截断长度，避免流式详情把 param 撑过 3072 字节上限。 */
    private const val DETAIL_MAX_CHARS = 120

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

    /** 设备是否支持超级岛（MIUI/HyperOS 私有属性或工具库判定）；结果缓存。 */
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
        // 大岛 A / B 区只放短文案（规范建议左右各不超过 4 个中文字），整段推理走展开态。
        val shortText = state.status.islandShortText(context.resources)
        val toolName = state.status.islandToolName(context.resources)
        val detail = (
            state.thought.takeIf { it.isNotBlank() }
                ?: state.detailText.takeIf { it.isNotBlank() }
                ?: ""
            ).take(DETAIL_MAX_CHARS)
        val generation = dismissGeneration
        scope.launch {
            sendIslandNotification(
                context = context,
                notificationId = group.id,
                statusText = statusText,
                shortText = shortText,
                toolName = toolName,
                detail = detail,
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

    /**
     * 判断设备是否支持超级岛。两条独立通道取或：
     * - Eta 自己反射读 MIUI 私有属性 `persist.sys.feature.island`（走 HiddenApiBypass，不受
     *   非 SDK 接口限制）；
     * - 工具库的 [HyperIslandNotification.isSupported]（小米设备 + `canShowFocus` 焦点通知
     *   权限 + 同一属性）。
     *
     * 单看白名单权限会漏判「有岛但未授权」的设备，而 Eta 正是用 root 断网窗口给这类设备
     * 投递，所以两者取或；结果缓存，避免每次状态变化都反射与 IPC。
     */
    fun isIslandSupported(context: Context): Boolean {
        supported?.let { return it }
        val property = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            HiddenApiBypass.invoke(clazz, null, "getBoolean", "persist.sys.feature.island", false) as Boolean
        }.getOrDefault(false)
        val kit = runCatching { HyperIslandNotification.isSupported(context) }.getOrDefault(false)
        val detected = property || kit
        supported = detected
        if (detected) {
            AndroidAgentLogger.info("Island support detected (property=$property kit=$kit)")
        } else {
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

    /**
     * 发送一条岛通知：普通通知 + miui.focus 私有 extras。JSON 必须在 build() 之后再写。
     *
     * 模板按官方《超级岛通知模板库》选择，不自创组合（规范明确「模板不可自行拼接」）：
     * - **摘要态**（大岛，摄像头左右分 A / B 区）：模版2 = A 区图文组件1
     *   （`imageTextInfoLeft` type=1）+ B 区文本组件（`bigIslandArea.textInfo`）；
     *   没有工具名时退回模版1（图文组件1 + 空）。
     * - **展开态**（点开的卡片）：模版2 = 文本组件2（`baseInfo` type=2）+
     *   识别图形组件1（顶层 `picInfo` type=1）。
     */
    private fun sendIslandNotification(
        context: Context,
        notificationId: Int,
        statusText: String,
        shortText: String,
        toolName: String?,
        detail: String,
        generation: Long = 0L,
    ) {
        ensureChannel(context)
        val appIcon = Icon.createWithResource(context, R.mipmap.ic_launcher)
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 展开态与通知栏正文同一份文案：状态 + 详情，详情为空时退回短状态。
        val body = detail.ifBlank { shortText }

        val island = HyperIslandNotification.Builder(context, BUSINESS_TAG, statusText)
            .setLogEnabled(false)
            .addPicture(HyperPicture(APP_ICON_KEY, appIcon))
            // 展开态：文本组件2 —— 主要文本1 + 次要文本1（两个字段都是必传）
            .setBaseInfo(
                type = 2,
                title = statusText,
                content = body,
                showDivider = true,
                showContentDivider = false,
                colorTitle = "#111111",
                colorTitleDark = "#ffffff",
                colorContent = "#333333",
                colorContentDark = "#cccccc",
            )
            // 展开态：识别图形组件1（type=1 取应用图标，官方要求 ≥96×96px，系统按 1/4 圆角处理）
            .setPicInfo(type = 1, picKey = APP_ICON_KEY)
            // 摘要态小岛：图标组件（缺失时系统按「A 区图标 → 应用图标」兜底）
            .setSmallIsland(APP_ICON_KEY)
            // 摘要态大岛：A 区图文组件1（图标 + 大字）+ B 区文本组件（工具名，可选）
            .setBigIslandInfo(
                left = ImageTextInfoLeft(
                    type = 1,
                    picInfo = PicInfo(type = 1, pic = APP_ICON_KEY),
                    textInfo = TextInfo(title = shortText),
                ),
                centerText = toolName?.let { TextInfo(title = it) },
            )
            // 首次以**摘要态**出现、更新时也不自动展开（官方语义：islandFirstFloat=true 会以
            // 展开态弹出）。展开态只由用户点按触发，既不与 Eta 自绘胶囊重叠，也不会被流式
            // 文案反复弹卡。
            .setIslandFirstFloat(false)
            .setEnableFloat(false)
            .setShowNotification(true)
            .setTimeout(NOTIFICATION_TIMEOUT_MINUTES)
            .setIslandConfig(
                priority = ISLAND_PRIORITY,
                timeout = ISLAND_TIMEOUT_SECONDS,
                dismissible = false,
            )

        val extras = island.buildResourceBundle()
        if (extras.isEmpty) {
            // 库在「非小米设备 / 未通过 canShowFocus 校验」时返回空 Bundle。Eta 会在 root
            // 断网窗口下给未授权设备投递，此时按库的同一命名约定自备图标，避免岛上无图。
            extras.putBundle(
                PICS_EXTRA_KEY,
                Bundle().apply { putParcelable(PICS_KEY_PREFIX + APP_ICON_KEY, appIcon) },
            )
        }

        val paramJson = island.buildJsonParam()
        val paramBytes = paramJson.toByteArray(Charsets.UTF_8).size
        if (paramBytes > PARAM_MAX_BYTES) {
            AndroidAgentLogger.warn("Island param exceeds $PARAM_MAX_BYTES bytes: $paramBytes")
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(appIcon)
            .setContentTitle(statusText)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .addExtras(extras)
            .build()
        notification.extras.putString(PARAM_EXTRA_KEY, paramJson)

        sendWithBypass(context, notificationId, notification, generation)
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
