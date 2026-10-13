package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val EtaInputCornerRadius = 12.dp

@Composable
internal fun etaInputContainerColor(): Color {
    val colors = MiuixTheme.colorScheme
    return lerp(colors.surface, colors.surfaceContainerHigh, 0.35f)
}

/**
 * 圆角填充式表单输入框。
 *
 * Material 3 下直接使用官方 `OutlinedTextField` 默认形态（官方圆角/配色/涟漪/字阶）；
 * MIUI X 下保留原来的柔和填充风格。
 */
@Composable
internal fun EtaFormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    if (isMaterial3Theme()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = hint },
            enabled = enabled,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else maxLines,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            label = { Text(text = hint) },
        )
        return
    }

    val colors = MiuixTheme.colorScheme
    val container = etaInputContainerColor()
    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
        WithoutPressRipple {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else minLines,
                maxLines = if (singleLine) 1 else maxLines,
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = hint },
                shape = RoundedCornerShape(EtaInputCornerRadius),
                textStyle = MiuixTheme.textStyles.body1,
                label = { Text(hint) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = container,
                    unfocusedContainerColor = container,
                    disabledContainerColor = container,
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    disabledTextColor = colors.onSurface.copy(alpha = 0.38f),
                    cursorColor = colors.primary,
                    focusedBorderColor = colors.primary.copy(alpha = 0.65f),
                    unfocusedBorderColor = colors.outline.copy(alpha = 0.30f),
                    disabledBorderColor = colors.outline.copy(alpha = 0.15f),
                    focusedLabelColor = colors.primary,
                    unfocusedLabelColor = colors.onSurfaceVariantSummary,
                    disabledLabelColor = colors.onSurfaceVariantSummary.copy(alpha = 0.38f),
                ),
            )
        }
    }
}
