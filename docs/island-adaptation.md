# Eta · 小米 / 澎湃 OS 超级岛适配（HyperOS Focus Island）

本补丁为 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 增加小米 / 红米「超级岛」适配：
当 Agent 运行时，把思考 / 操作工具 / 生成回答 / 暂停 / 完成 / 失败等状态同步到
HyperOS 状态栏胶囊与展开大岛，实现与 NexioSchedule 等系统级应用一致的系统岛体验。

> 协议为 MIUI 私有「焦点通知」，与 Android 16 Live Updates、Eta 自绘胶囊浮层互不干扰、可并存。
> `param_v2` 载荷与图片 Bundle 由 [HyperIsland-ToolKit](https://github.com/D4vidDf/HyperIsland-ToolKit)
> 按官方模板构建，不再手写 JSON；无白名单时的投递思路参考
> [HaoZai000/NexioSchedule](https://github.com/HaoZai000/NexioSchedule)。

---

## 1. 适配原理

超级岛**不是** Android 公共通知 API，也不是 Android 16 的 Live Updates（实况通知）。
MIUI / HyperOS 的 SystemUI 会扫描应用的焦点通知：只要一条普通通知携带两个私有 extra，
焦点服务就会把它渲染成「状态栏胶囊（小岛）+ 展开大岛」：

| extra | 内容 |
| --- | --- |
| `miui.focus.param` | `param_v2` JSON 字符串：胶囊 / 大岛的文案、图标、计时器、互动元素布局 |
| `miui.focus.pics` | Bundle，内含 `miui.focus.pic_<key>` 图标 Parcelable |

「业务」侧需要在每次状态变化时把参数重新写入通知并 `notify`：更新同 ID 通知即原地刷新。
切换状态组（运行 → 暂停 → 完成）前先 `cancel` 兄弟通知，避免焦点服务把同 ID 的静默替换吞掉。

### 载荷交给 HyperIsland-ToolKit

`param_v2` 的字段结构、模板组合、`miui.focus.pic_` 前缀规则都属于未公开协议，手写 JSON 很容易
在不同 HyperOS 版本上失配。因此载荷生成统一交给库：

```kotlin
HyperIslandNotification.Builder(context, BUSINESS_TAG, statusText) // ticker = 状态栏文案
    .addPicture(HyperPicture(APP_ICON_KEY, appIcon))              // 先注册图片，再用 key 引用
    // 展开态：模版2 = 文本组件2 + 识别图形组件1
    .setBaseInfo(type = 2, title = statusText, content = detail, …)
    .setPicInfo(type = 1, picKey = APP_ICON_KEY)
    // 摘要态：小岛图标 + 大岛「A 区图文组件1 + B 区文本组件」
    .setSmallIsland(APP_ICON_KEY)
    .setBigIslandInfo(
        left = ImageTextInfoLeft(type = 1, picInfo = …, textInfo = TextInfo(shortText)),
        centerText = toolName?.let { TextInfo(it) },
    )
    .setIslandFirstFloat(false).setEnableFloat(false)              // 首次/更新都不自动展开
    .setTimeout(NOTIFICATION_TIMEOUT_MINUTES)                      // 分钟
    .setIslandConfig(priority = 2, timeout = ISLAND_TIMEOUT_SECONDS, dismissible = false)

val extras = island.buildResourceBundle()                        // 图片 Bundle → addExtras
…
notification.extras.putString("miui.focus.param", island.buildJsonParam())
```

库的两个细节被 Eta 显式处理：

- **空 Bundle 回退**：`buildResourceBundle()` 在「非小米设备 / 未通过 `canShowFocus` 校验」时
  返回空 Bundle。Eta 会在 root 断网窗口下给未授权设备投递，此时按同一命名约定
  （`miui.focus.pic_<key>`）自备应用图标，避免岛上无图。
- **图片 key 前缀**：`setSmallIsland` / `setBigIslandInfo` 内部会补 `miui.focus.pic_`，
  传原始 key；`PicInfo` 也只能传原始 key，否则会被重复加前缀。

### 与小米官方规范的对应关系

依据小米澎湃 OS 开发者平台的《超级岛 - 开发指南 / 摘要态设计规范 / 展开态设计规范 / 常见 Q&A》
与《小米超级岛通知模板库》，本适配刻意踩在规范内：

| 规范要点 | 本实现 |
| --- | --- |
| 岛分**摘要态**（`param_island`）与**展开态**（`param_v2` 顶层模板），字段完全不同 | 摘要态用大/小岛字段，展开态用 `baseInfo` + `picInfo` |
| **模板不可自行拼接**，只能从官方模板里选 | 摘要态选模版1/2（图文组件1 +〔文本组件｜空〕）；展开态选模版2（文本组件2 + 识别图形组件1） |
| `param_v2.timeout` 单位 **分钟**（默认 720） | `NOTIFICATION_TIMEOUT_MINUTES = 720` |
| `param_island.islandTimeout` 单位 **秒**（默认 3600） | `ISLAND_TIMEOUT_SECONDS = 3600` |
| `islandFirstFloat`：true 时首次以**展开态**出现 | `false`：首次即摘要态，展开只由用户点按触发 |
| `enableFloat`：更新时是否自动展开（默认 false） | `false` |
| `islandProperty`：1=信息展示为主 | 库固定 1 |
| `protocol` 与设备 `notification_focus_protocol` 一致 | 库固定 3，与 OS3 设备实测值 3 相符 |
| 大岛文案「建议总字数不超过 4 个中文字」 | 大岛只放短标签（思考中 / 执行中 / 已完成…）与工具名，整段推理走展开态 |
| `miui.focus.param` **≤ 3072 字节** | 展开态详情截断 120 字符，超限时打日志（`Island param exceeds …`） |
| `param_island`、`bigIslandArea`、`smallIslandArea` 必选 | 三者都填充 |


### 白名单问题

焦点服务只对系统级 / 小米白名单应用完整生效；第三方应用直接发送时，通知可能被降级为
普通通知。NexioSchedule 的解法是：`notify` 前通过 Shizuku 短暂断开小米服务框架
`com.xiaomi.xmsf` 的联网（白名单查询走 xmsf，断网会让校验失效），发完立即恢复。

Eta 没有 Shizuku 依赖，本补丁改用 **root + netfilter** 实现同一窗口：

```
iptables -t mangle -N oem_out        # 对应 netd OEM_DENY 出向链（幂等，存在则忽略）
iptables -t mangle -C oem_out -m owner --uid-owner <xmsf_uid> -j DROP || \
iptables -t mangle -I oem_out 1 -m owner --uid-owner <xmsf_uid> -j DROP
iptables -t mangle -N oem_in
iptables -t mangle -C oem_in -m owner --uid-owner <xmsf_uid> -j DROP || \
iptables -t mangle -I oem_in 1 -m owner --uid-owner <xmsf_uid> -j DROP
```

- `-C` 幂等检查、`-I` 插入、`-D` 恢复，命令以 `echo ETA_XMSF_BLOCK_OK / UNBLOCK_OK` 验证结果；
- 断网窗口固定 100ms，所有 bypass 操作经 `Mutex` 串行，`finally` 保证恢复；
- **root 不可用、命令失败或链名不匹配时自动降级为直接发送**，不影响通知本体。

---

## 2. 文件变更

### 新增

| 文件 | 说明 |
| --- | --- |
| `app/src/main/kotlin/io/github/mangi/eta/agent/island/IslandNotificationHelper.kt` | 全部超级岛逻辑：设备支持检测、通知频道、库载荷构建、通知发送、XMSF bypass、节流、级联取消、竞态防护 |

### 修改

| 文件 | 改动 |
| --- | --- |
| `gradle/libs.versions.toml`、`app/build.gradle.kts` | 新增依赖 `io.github.d4viddf:hyperisland_kit:0.4.5`（Apache-2.0） |
| `app/src/main/kotlin/io/github/mangi/eta/config/Prefs.kt` | 新增开关 `ISLAND_ENABLED = "island_enabled"`（默认 **false**，加入 `BOOLEAN_DEFAULTS` 与 `LOCAL_AGENT_KEYS`） |
| `app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeService.kt` | ① `state.value` setter 调用 `onRunStateChanged`（与执行通知同源同点）；② `onDestroy()` 与 `dismissAndStop()` 调用 `dismiss` 清理 |
| `app/src/main/kotlin/io/github/mangi/eta/ui/SettingsScreen.kt` | 「通用」组末尾新增「超级岛」`SwitchPref`（新增 `Notifications` 图标导入） |
| `app/src/main/res/values/strings.xml`、`values-b+zh+Hans/strings.xml`、`values-b+zh+Hant/strings.xml` | 新增 `island_enabled_title` / `island_enabled_summary` / `island_channel_name` / `island_channel_description` 四组文案（中简 / 中繁 / En） |

**无新增权限、无 Manifest 改动**。依赖复用：`hidden-api-bypass`（反射读私有属性）、
`material-icons-extended`（设置图标）、`hyperisland_kit`（载荷构建，自身依赖 `androidx.core-ktx`
与 `kotlinx-serialization-json`）。minSdk 33 满足全部 API 要求（库 minSdk 26）。

---

## 3. 接入点

### 3.1 状态发布 —— `AgentRuntimeService.state.value` setter（唯一状态入口）

```kotlin
set(next) {
    holder.value = next
    AgentExecutionService.updateRunStatus(
        this@AgentRuntimeService,
        next.status.takeIf { activeSession?.isTerminal == false },
    )
    IslandNotificationHelper.onRunStateChanged(this@AgentRuntimeService, next)
}
```

- `startRun` 重置 Initial、`handleAcceptedRunEvent` 流式 applyEvent、`requestStop/Pause/Resume/Supplement`
  直接 `copy(...)`、`enterFinalState` 全部经过该 setter，因此一处接入即可覆盖所有状态切换；
- 与 `AgentExecutionService.updateRunStatus` 共用同一节流点：**只有「状态组 | 状态文字」变化才发布**，
  流式的 `thought` / `detailText` 更新不会刷爆通知；
- 终端态（完成 / 失败）也会发布「完成 / 失败」胶囊，点按打开 Eta 会话页。

### 3.2 清理 —— 两个出口

- `dismissAndStop()`：结果卡片关闭 / 无前台工具直接结束 run 时，取消全部岛通知并停服务；
- `onDestroy()`：服务销毁兜底清理。

两点都通过 `IslandNotificationHelper.dismiss(context)` 执行（`cancel` 全部 1201/1202/1203）。

### 3.3 防竞态

`enterFinalState` 无工具路径中「setter 发布完成态」与「dismissAndStop」几乎同时发生。
`dismiss()` 递增内部 `dismissGeneration`，发布协程在真正 `notify` 前核对快照，
避免完成态通知在清理之后才落地。即使竞态窗口存在，也绝不会出现「服务已停、岛通知还挂着」。

---

## 4. 内容与行为映射

### 4.1 状态 → 岛内容

摘要态（常驻，`param_island`）与展开态（点开的卡片，`param_v2` 顶层）用两套不同的模板：

| 位置 | 状态 | 取值 |
| --- | --- | --- |
| `ticker`（状态栏文案） | 全部 | `state.status.localizedText()`，如「生成回答中」 |
| **摘要态** 小岛 `smallIslandArea` | 全部 | `miui.focus.pic_eta_app_icon`（Eta 启动图标，正方形） |
| **摘要态** 大岛 A 区（图文组件1） | 全部 | 应用图标 + 大字 `islandShortText()`：思考中 / 执行中 / 整理回答 / 已完成 / 已暂停 / 失败…（≤4 个中文字） |
| **摘要态** 大岛 B 区（文本组件） | 仅正在执行工具 | 工具名（如「点击元素」「读取文件」），其它状态留空退回模版1 |
| **展开态**（文本组件2） | 全部 | 主要文本1 = `localizedText()` 完整状态；次要文本1 = `thought` / `detailText`（截断 120 字符） |
| **展开态**（识别图形组件1） | 全部 | `picInfo(type=1)`：应用图标（官方要求 ≥96×96px） |

行为开关（官方语义，别弄反）：

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| `islandFirstFloat` | `false` | true = 首次以**展开态**弹出；Eta 要首屏就是摘要态 |
| `enableFloat` | `false` | 更新时不自动展开为卡片 |
| `islandPriority` / `islandTimeout` | `2` / `3600` 秒 | 任务结束时由 `dismiss` 显式收起，超时只作兜底 |
| `dismissIsland` | `false` | Agent 运行中不允许把岛划走 |
| `timeout`（通知） | `720` 分钟 | 官方默认值 |

颜色沿用「浅色 `#111111` / `#333333`，深色 `#ffffff` / `#cccccc`」的一套，与截图中的观感一致。

### 4.2 通知 ID 映射

同组覆盖、切换组先 cancel 兄弟（同 NexioSchedule `siblingIdsFor`）：

| 状态组 | 通知 ID |
| --- | --- |
| 运行中（RUNNING） | 1201 |
| 已暂停（PAUSED） | 1202 |
| 完成 / 失败（FINAL） | 1203 |

频道：`eta_island`，`IMPORTANCE_HIGH` + `setBypassDnd(true)`（免打扰不屏蔽岛）。

### 4.3 设备支持检测

两条独立通道**取或**，结果缓存：

1. Eta 自己反射读 `persist.sys.feature.island`（走 `HiddenApiBypass`，不受非 SDK 接口限制）；
2. 库的 `HyperIslandNotification.isSupported()`：小米设备 + `canShowFocus` 焦点通知权限 + 同一属性。

只看白名单权限会漏判「有岛但未授权」的设备，而 Eta 正是用 root 断网窗口给这类设备投递，
所以取或；两条通道的判定结果都会写进日志（`Island support detected (property=… kit=…)`），
便于排查「不出岛」是设备不支持还是被降级。

---

## 5. 使用方式

1. 将补丁文件合入 Eta 工程对应路径，`assemble` 构建安装；
2. 打开 **Eta 设置 → 通用**，开启「超级岛 / Super Island」开关（默认关闭，不改变现有体验）；
3. 触发一次 Agent 运行：思考 / 操作工具 / 生成回答会以小岛胶囊呈现在摄像头挖孔区域，
   点按展开大岛，再点按打开 Eta 会话；
4. 暂停 / 继续 / 完成 / 失败均实时同步；任务结束或关闭结果卡片后自动收起。

**前置条件**

- 设备支持超级岛（MIUI 14 / HyperOS，属性 `persist.sys.feature.island=true`，运行时自动检测，不支持则静默不发送）；
- 应用有通知权限（执行通知已在 Eta 中申请）；
- 第三方应用完整上岛需要 root（XMSF 断网窗口），且需在 KernelSU / Magisk 中给 Eta 授予 root；
  无 root 时通知可能被降级为普通通知，不会崩也不会阻塞原功能。

---

## 6. 验证与局限

- 规范侧：字段、单位、模板组合逐项对照小米开发者平台《开发指南》《摘要态设计规范》
  《展开态设计规范》《常见 Q&A》与《超级岛通知模板库》（见 §1「与小米官方规范的对应关系」）；
- 库侧：`param_v2` 序列化结构与模板字段组合来自 HyperIsland-ToolKit 官方 demo 中已上机验证的用法
  （天气 / 账单 / 打车一类「状态 + 说明」模板）；
- Eta 侧：已核对接入点、节流与竞态路径、依赖与资源、XMSF 降级分支，并完成构建与安装；
  目标机（小米 HyperOS V816 / OS3，`notification_focus_protocol=3`，`persist.sys.feature.island=1`，
  KernelSU root）条件满足，与库固定输出的 `protocol: 3` 一致；
- **岛的实际呈现仍需在真机触发一次 Agent 运行确认**（沙箱只到构建、安装与设备条件核对）。
  以下风险点需实测：
  1. `persist.sys.feature.island` 为 MIUI 私有属性，名称随 ROM 变化；检测失败即整体禁用，不影响其它功能；
  2. 库内模板字段随 HyperOS 版本可能有微调；若只出普通通知，先看日志里 bypass 是否降级，
     再看 `Island support detected` 的 `property` / `kit` 两个值；
  3. `oem_out` / `oem_in` 链名为 netd OEM_DENY 的业界通用名，但定制 ROM 可能改名；
     iptables 命令失败会自动降级直发，不影响通知本体；
  4. 若小米后续收紧焦点通知白名单，即使断网窗口生效也可能拒绝第三方上岛，届时应改用系统签名 / 模块方案。

## 7. 关闭方式

- 设置页关闭「超级岛」开关后，`onRunStateChanged` 直接 `dismiss`，不再发布任何岛通知；
- 卸载 / 移除补丁文件与 `hyperisland_kit` 依赖即完全还原，不影响 Eta 原有的自绘胶囊浮层与
  Android 16 Live Updates 通知。
