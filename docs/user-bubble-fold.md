# 用户气泡长正文折叠

## 问题

用户粘贴一篇长文（日志、代码、整段需求）时，`UserMessageBubble` 会把正文完整铺开。
一条消息能把整屏聊天区占满，之后自己刚发的内容和 Agent 的回复都被挤出可视区，
跟底滚动还会被这条消息反复顶动。

## 行为

- **何时折**：正文按气泡自身的可用宽度排版后超过 6 行才折；1–6 行的普通消息完全不受影响。
- **折成什么样**：只显示前 6 行，底部 40dp 用渐隐带化进气泡底色（不是省略号——
  长消息末尾本来就没有「该被省略」的内容），气泡内右下角给出「展开全文」。
- **展开之后**：不限行显示全文，同一位置变成「收起」，再点回到 6 行。
- **只裁显示**：`UserMessageUi.content` 一个字节都不动。复制、编辑、发给模型的仍是完整正文。

## 为什么行数在组合期算，而不是等 `onTextLayout` 回填

`onTextLayout` 回填会让长消息先以**全文高度**出现一帧，再把整列顶上去、下一帧缩回来。
在跟底滚动期间这就是一次可见的跳动外加一次列表高度抖动。

所以判定改用 `TextMeasurer.measure(maxLines = 7)`：只排到第 7 行就够回答「会不会超过 6 行」，
超长正文不会被整段排版一遍，而且首帧直接就是收起态。量尺的字阶和可用宽度与真正的
`Text` 完全同源（`UserBubbleTextPadding` 被预算与 `padding` 共用，两处不会各写一份）。

## 渐隐带为什么先做 `compositeOver`

气泡底是 `primary` 18% 的半透明色。渐隐带如果直接叠同色，底部会变成「18% 叠 18%」，
在气泡下沿留下一道比周围更深、且有明确起点的色带。先用 `compositeOver` 把半透明底合成到
页面底色（`surfaceContainer`，与 `AgentAppShell` 的 Scaffold 同色）上再渐隐，末端与气泡
其余部分逐像素一致。

开发期用抓图核对过这一点：折叠态沿高度方向采样，气泡底色的 ARGB 全程一致。

## 交互

「展开 / 收起」是官方 `TextButton`（自带最小触控高度、涟漪与主色标签）。
展开前先调 `LocalChatAnchorRelease` 解除跟底锚定——否则新增的高度会被
`AgentChatBody` 在静止线上的裁剪吃掉，尾部看不见。

展开态按消息 id `rememberSaveable`，滚动回收或旋屏后仍是用户选的那一档。

## 契约

- `app/src/test/kotlin/.../UserBubbleFoldTest.kt`：真实 `ChatMessageItem` 的折叠/展开高度、
  短消息不出现折叠入口、6/7 行边界、折叠态与展开态的 `maxLines`。
- `app/src/test/python/test_retest_boundaries_contract.py`：钉住「行数只由组合期预算决定，
  不许出现 `onTextLayout =` 回填」，以及既有的诊断标签与缓存口径。
