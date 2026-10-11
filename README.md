# Eta · 小米 / 澎湃 OS 超级岛适配（HyperOS Focus Island）

本补丁为 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 增加小米 / 红米「超级岛」适配：
当 Agent 运行时，把思考 / 操作工具 / 生成回答 / 暂停 / 完成 / 失败等状态同步到
HyperOS 状态栏胶囊与展开大岛，实现与 NexioSchedule 等系统级应用一致的系统岛体验。

> 适配思路逆向自 [HaoZai000/NexioSchedule](https://github.com/HaoZai000/NexioSchedule)，
> 协议为 MIUI 私有「焦点通知」，与 Android 16 Live Updates、Eta 自绘胶囊浮层互不干扰、可并存。

---

## 1. 适配原理

超级岛**不是** Android 公共通知 API，也不是 Android 16 的 Live Updates（实况通知）。
MIUI / HyperOS 的 SystemUI 会扫描应用的焦点通知：只要一条普通通知携带两个私有 extra，
焦点服务就会把它渲染成「状态栏胶囊（小岛）+ 展开大岛」：

| extra | 内容 |
| --- | --- |
| `miui.focus.param` | `param_v2` JSON 字符串：胶囊 / 大岛的文案、图标、计时器、互动元素布局 |
| `miui.focus.pics` | Bundle，内含 `miui.focus.pic_small` / `pic_small_dark` 等图标 Parcelable |

「业务」侧需要在每次状态变化时把参数写成新的 JSON 重新 `notify`：
- `sequence` 必须单调递增，保证多次更新不乱序；
- `updatable=true`，允许同一条通知原地刷新；
- 切换状态组（运行 → 暂停 → 完成）前先 `cancel` 兄弟通知，避免焦点服务把同 ID 的静默替换吞掉。

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
| `app/src/main/kotlin/io/github/mangi/eta/agent/island/IslandNotificationHelper.kt` | 全部超级岛逻辑：设备支持检测、通知频道、`param_v2` 构造、通知发送、XMSF bypass、节流、级联取消、竞态防护 |

### 修改

| 文件 | 改动 |
| --- | --- |
| `app/src/main/kotlin/io/github/mangi/eta/config/Prefs.kt` | 新增开关 `ISLAND_ENABLED = "island_enabled"`（默认 **false**，加入 `BOOLEAN_DEFAULTS` 与 `LOCAL_AGENT_KEYS`） |
| `app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeService.kt` | ① `state.value` setter 调用 `onRunStateChanged`（与执行通知同源同点）；② `onDestroy()` 与 `dismissAndStop()` 调用 `dismiss` 清理 |
| `app/src/main/kotlin/io/github/mangi/eta/ui/SettingsScreen.kt` | 「通用」组末尾新增「超级岛」`SwitchPref`（新增 `Notifications` 图标导入） |
| `app/src/main/res/values/strings.xml`、`values-b+zh+Hans/strings.xml`、`values-b+zh+Hant/strings.xml` | 新增 `island_enabled_title` / `island_enabled_summary` / `island_channel_name` / `island_channel_description` 四组文案（中简 / 中繁 / En） |

**无新增依赖、无新增权限、无 Manifest 改动**。依赖复用：`hidden-api-bypass`（反射私有属性）、
`material-icons-extended`（设置图标）、`org.json`（Android 内置）。minSdk 33 满足全部 API 要求。

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

## 4. 协议细节（`param_v2`）

Eta 场景无倒计时，因此统一使用**静态态**：`islandFirstFloat=true` + `enableFloat=false`——
只呈现状态栏胶囊与展开岛，**不弹悬浮窗**，避免与 Eta 自绘的胶囊浮层视觉重叠。

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| `business` | `eta_agent` | 业务标识，SystemUI 聚合 / 折叠按此区分 |
| `protocol` | `1` | 协议版本 |
| `sequence` | 单调递增 | 保证更新不乱序 |
| `updatable` | `true` | 允许原地刷新 |
| `islandFirstFloat` / `enableFloat` | `true` / `false` | 静态态，无悬浮窗 |
| `aodTitle` | `Eta` | 息屏显示标题 |
| `baseInfo` | type=2，title="Eta"、content=状态文字 | 基础信息区 |
| `hintInfo` | type=2，title=状态文字、content=副文案、`timerType=0` 全零 | 提示区（无计时器） |
| `param_island.islandProperty` | `1` | 岛属性 |
| `param_island.islandTimeout` | `3600` | 岛最长展示秒数 |
| `param_island.bigIslandArea` | `templateNo=2`，imageTextInfoLeft=应用名，textInfo=标题/内容 | 展开大岛模板 |
| `param_island.smallIslandArea` | 使用 `miui.focus.pic_small(_dark)` | 胶囊小图标 |

通知 ID 映射（同组覆盖、切换组先 cancel 兄弟，同 NexioSchedule `siblingIdsFor`）：

| 状态组 | 通知 ID |
| --- | --- |
| 运行中（RUNNING） | 1201 |
| 已暂停（PAUSED） | 1202 |
| 完成 / 失败（FINAL） | 1203 |

频道：`eta_island`，`IMPORTANCE_HIGH` + `setBypassDnd(true)`（免打扰不屏蔽岛）。

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
- 第三方应用完整上岛需要 root（XMSF 断网窗口）；无 root 时通知可能被降级为普通通知，
  不会崩也不会阻塞原功能。

---

## 6. 验证与局限

- 已做静态核对：接入点、依赖、资源、XML 合法性、节流与竞态路径均已逐行验证；
- **未做实机验证**（沙箱无设备）。以下风险需在目标机型实测：
  1. `persist.sys.feature.island` 为 MIUI 私有属性，名称随 ROM 变化；检测失败即整体禁用，不影响其它功能；
  2. `param_v2` 字段结构基于 NexioSchedule 逆向结果，不同 HyperOS 版本可能有微调（小岛图标模板、
     `templateNo` 等）；若目标机型只出普通通知，优先检查日志 `Island feature not detected` / bypass 降级；
  3. `oem_out` / `oem_in` 链名为 netd OEM_DENY 的业界通用名，但定制 ROM 可能改名；
     iptables 命令失败会自动降级直发，不影响通知本体；
  4. 若小米后续收紧焦点通知白名单，即使断网窗口生效也可能拒绝第三方上岛，届时应改用系统签名 / 模块方案。

## 7. 关闭方式

- 设置页关闭「超级岛」开关后，`onRunStateChanged` 直接 `dismiss`，不再发布任何岛通知；
- 卸载 / 移除补丁文件即完全还原，不影响 Eta 原有的自绘胶囊浮层与 Android 16 Live Updates 通知。