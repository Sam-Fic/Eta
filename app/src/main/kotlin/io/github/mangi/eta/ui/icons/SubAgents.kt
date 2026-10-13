package io.github.mangi.eta.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 子代理 / 任务分派树图标。
 *
 * 原先这里是手绘的 24dp 矢量（一个协调者分叉到两个机器人），现已改用官方 Material 图标
 * [Icons.Rounded.AccountTree]：官方 AccountTree 同样是"层级分派树"语义（节点向子节点分叉），
 * 与子代理协调者分派任务的含义一致，且项目内已多处使用该图标（子代理预设卡片、权限概览等），
 * 视觉语言统一。
 *
 * tint 由调用方的 `Icon(tint = …)` 提供——官方图标自带 `tint` 默认值（取 LocalContentColor），
 * 因此这里不再硬编码 SolidColor(Color.Black)。
 */
internal val SubAgents: ImageVector
    get() = Icons.Rounded.AccountTree
