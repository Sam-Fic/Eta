package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.CardDefaults as M3CardDefaults
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider as M3Slider
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.SwitchDefaults as M3SwitchDefaults
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.SliderDefaults as MiuixSliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle as MiuixSmallTitle
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 分组小标题。 */
@Composable
fun EtaSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (isMaterial3Theme()) {
        // KernelSU 式组标题：titleSmallEmphasized + primary，起点与分段列表条目对齐（16dp）。
        M3Text(
            text = text,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
    } else {
        MiuixSmallTitle(text = text, modifier = modifier)
    }
}

/** 承载一组设置项的卡片容器（真卡片外观；分段列表容器见 EtaSegmentedList）。 */
@Composable
fun EtaCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (isMaterial3Theme()) {
        // 官方 Card 默认值：容器 surfaceContainerHighest、形状 shapes.medium，不再覆盖。
        M3Card(modifier = modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                content()
            }
        }
    } else {
        MiuixCard(modifier = modifier) {
            content()
        }
    }
}

/** 文本层级。 */
enum class EtaTextStyle { Title, Body, Secondary, Footnote }

/** 自适应文本。 */
@Composable
fun EtaText(
    text: String,
    modifier: Modifier = Modifier,
    style: EtaTextStyle = EtaTextStyle.Body,
    secondary: Boolean = false,
    color: Color? = null,
    textAlign: TextAlign? = null,
) {
    val effective = if (secondary) EtaTextStyle.Secondary else style
    if (isMaterial3Theme()) {
        val textStyle = when (effective) {
            EtaTextStyle.Title -> MaterialTheme.typography.titleMedium
            EtaTextStyle.Body -> MaterialTheme.typography.bodyLarge
            EtaTextStyle.Secondary -> MaterialTheme.typography.bodyMedium
            EtaTextStyle.Footnote -> MaterialTheme.typography.bodySmall
        }
        val defaultColor = when (effective) {
            EtaTextStyle.Title, EtaTextStyle.Body -> MaterialTheme.colorScheme.onSurface
            EtaTextStyle.Secondary, EtaTextStyle.Footnote -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        M3Text(
            text = text,
            modifier = modifier,
            style = textStyle,
            color = color ?: defaultColor,
            textAlign = textAlign,
        )
    } else {
        val textStyle = when (effective) {
            EtaTextStyle.Title -> MiuixTheme.textStyles.headline2
            EtaTextStyle.Body -> MiuixTheme.textStyles.body1
            EtaTextStyle.Secondary -> MiuixTheme.textStyles.body2
            EtaTextStyle.Footnote -> MiuixTheme.textStyles.footnote1
        }
        val defaultColor = when (effective) {
            EtaTextStyle.Title, EtaTextStyle.Body -> MiuixTheme.colorScheme.onSurface
            EtaTextStyle.Secondary, EtaTextStyle.Footnote ->
                MiuixTheme.colorScheme.onSurfaceVariantSummary
        }
        MiuixText(
            text = text,
            modifier = modifier,
            style = textStyle,
            color = color ?: defaultColor,
            textAlign = textAlign,
        )
    }
}

/** 自适应开关。 */
@Composable
fun EtaSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (isMaterial3Theme()) {
        // KernelSU 式 Expressive 开关：拇指常驻状态图标（选中 Check / 关闭 Close）
        // + 完整禁用态配色，见 EtaButtons.kt。
        EtaExpressiveSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    } else {
        MiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }
}

/** 自适应滑块。[keyPoints] 仅 MIUI X 生效（吸附关键点）。 */
@Composable
fun EtaSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyPoints: List<Float>? = null,
    magnetThreshold: Float = 0.01f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    if (isMaterial3Theme()) {
        // 官方新版 Slider API：SliderState + onValueChange（旧的 value 重载已废弃）。
        val state = rememberSliderState(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            trackRange = valueRange,
        )
        LaunchedEffect(value, valueRange) {
            val coerced = value.coerceIn(valueRange.start, valueRange.endInclusive)
            if (state.value != coerced) state.value = coerced
        }
        M3Slider(
            state = state,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
    } else {
        MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            showKeyPoints = keyPoints != null,
            keyPoints = keyPoints ?: emptyList(),
            magnetThreshold = if (keyPoints != null) magnetThreshold else 0f,
            hapticEffect = MiuixSliderDefaults.SliderHapticEffect.Step,
        )
    }
}

/** 自适应主操作按钮。 */
@Composable
fun EtaTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (isMaterial3Theme()) {
        // 官方按钮默认形状为胶囊（shape style "full"），不额外覆盖。
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = M3ButtonDefaults.buttonColors(),
        ) {
            M3Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        MiuixTextButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = MiuixButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/** 状态圆点：8dp，[active] 为真取 primary，否则取弱化的描边色（未就绪/未授权）。 */
@Composable
fun EtaStatusDot(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (isMaterial3Theme()) {
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    } else {
        if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions
    }
    Box(modifier = modifier.size(EtaStatusDotSize).background(color, CircleShape))
}

private val EtaStatusDotSize = 8.dp
