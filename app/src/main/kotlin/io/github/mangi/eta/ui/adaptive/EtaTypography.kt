package io.github.mangi.eta.ui.adaptive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主页与会话历史侧栏共用的统一语义字阶。
 *
 * 只统一“语义档位”，不强行让两个主题族的像素值完全相等：每个档位分别映射到
 * Material 3 官方 `typography` 与 miuix `textStyles` 中语义最接近的一档，
 * 因此同一处文字在两种主题下观感一致，又都仍是各自主题的官方字阶。
 *
 * 档位与典型用途：
 * - [EtaType.Hero]      空状态主标语
 * - [EtaType.Title]     弹窗 / 抽屉大标题（模型选择器、推理力度面板）
 * - [EtaType.Body]      正文（markdown 正文、输入文本、用户气泡）
 * - [EtaType.Secondary] 次级正文（思考、附件、建议、次级说明）
 * - [EtaType.Caption]   说明（工具 Chip、分隔标签、尾部元信息）
 * - [EtaType.Micro]     极小注（徽标、上下文统计）
 *
 * 字重与字体只作为修饰叠加：[emphasized] 用于卡片 / 区块标题，[monospace] 用于代码。
 * 字号本身不再在各处硬编码。
 *
 * 与 [EtaTextStyle] 的关系：那套是设置 / 终端等「表单型」界面用的四档（Title/Body/
 * Secondary/Footnote），本枚举只服务主页与历史侧栏。两者在 Body / Secondary / Caption
 * 上取值一致；本枚举额外提供 [EtaType.Hero]（空状态主标语）与 [EtaType.Micro]（徽标），
 * 而 [EtaType.Title] 指「弹窗 / 抽屉大标题」，比 [EtaTextStyle.Title] 更大；顶栏会话名
 * 与侧栏按钮文案同档，走 [etaLabelStyle]。
 */
internal enum class EtaType { Hero, Title, Body, Secondary, Caption, Micro }

/** 取某个语义档的 [TextStyle]；详见 [EtaType]。 */
@Composable
internal fun etaTextStyle(
    type: EtaType,
    emphasized: Boolean = false,
    monospace: Boolean = false,
): TextStyle {
    val base = if (isMaterial3Theme()) {
        when (type) {
            EtaType.Hero -> MaterialTheme.typography.headlineSmall
            EtaType.Title -> MaterialTheme.typography.titleLarge
            EtaType.Body -> MaterialTheme.typography.bodyLarge
            EtaType.Secondary -> MaterialTheme.typography.bodyMedium
            EtaType.Caption -> MaterialTheme.typography.bodySmall
            EtaType.Micro -> MaterialTheme.typography.labelSmall
        }
    } else {
        when (type) {
            EtaType.Hero -> MiuixTheme.textStyles.title2
            EtaType.Title -> MiuixTheme.textStyles.title3
            EtaType.Body -> MiuixTheme.textStyles.body1
            EtaType.Secondary -> MiuixTheme.textStyles.body2
            EtaType.Caption -> MiuixTheme.textStyles.footnote1
            EtaType.Micro -> MiuixTheme.textStyles.footnote2
        }
    }
    var style = base
    if (emphasized) style = style.copy(fontWeight = FontWeight.SemiBold)
    if (monospace) style = style.copy(fontFamily = FontFamily.Monospace)
    return style
}

/**
 * 标签档：[EtaType.Secondary] 字号 + Medium 字重（两个主题都是 14sp / Medium）。
 *
 * 这是「按钮 / 顶栏标题」这类短标签的档位：侧栏的「新建对话」「管理聊天」与主页顶栏的
 * 会话名共用它，所以顶栏标题不再走更大的 [EtaType.Title]。
 */
@Composable
internal fun etaLabelStyle(): TextStyle =
    etaTextStyle(EtaType.Secondary).copy(fontWeight = FontWeight.Medium)
