package io.github.mangi.eta.ui.pages.providers

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.providerBrandLogoRes as sharedProviderBrandLogoRes
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as M3Text
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference

/** 分组标题 + 卡片的标准组合，Provider 相关页面统一使用。 */
@Composable
internal fun ProviderSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (title != null) {
            EtaSectionTitle(text = title)
        }
        // EtaCard 在 Material 3 下就是官方 Card：容器 surfaceContainerHighest、形状 shapes.medium。
        EtaCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            content()
        }
    }
}

/** 厂商品牌原色图标；资源已经包含适合圆形裁剪的背景与安全区。 */
@Composable
internal fun ProviderBrandIcon(
    sourceType: String,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(sourceType) ?: return
    ProviderBrandImage(logo = logo, modifier = modifier)
}

@Composable
private fun ProviderBrandImage(
    @DrawableRes logo: Int,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .padding(end = 8.dp)
            .size(24.dp)
            .clip(CircleShape),
    )
}

@DrawableRes
internal fun providerBrandLogoRes(provider: ProviderSetting): Int? =
    sharedProviderBrandLogoRes(provider)

@DrawableRes
internal fun providerBrandLogoRes(sourceType: String): Int? =
    sharedProviderBrandLogoRes(sourceType)

/** 已知厂商使用品牌图标，未知来源继续按协议类型使用通用图标。 */
@Composable
internal fun ProviderIcon(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(provider)
    if (logo != null) {
        ProviderBrandImage(logo = logo, modifier = modifier)
        return
    }

    val fallbackIcon = if (provider is CustomProviderSetting) Icons.Rounded.Dns else Icons.Rounded.Language
    if (isMaterial3Theme()) {
        // M3：官方 Icon + 官方 onSurfaceVariant 角色色 + 官方图标尺寸令牌；
        // PreferenceIcon 读的是 MiuixTheme 颜色，M3 下不能复用。
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .padding(end = 8.dp)
                .size(IconButtonDefaults.smallIconSize),
        )
        return
    }

    PreferenceIcon(
        icon = fallbackIcon,
        modifier = modifier,
    )
}

internal enum class TagChipTone { Normal, Emphasized }

/**
 * 小胶囊标签，用于能力标签与状态标记。
 *
 * Material 3 下改为官方 [Surface] + 官方形状令牌（`shapes.extraSmall`）与 `labelSmall` 字阶，
 * 不再手写圆角与容器色；MIUI X 保持原有绘制。
 */
@Composable
internal fun TagChip(
    text: String,
    tone: TagChipTone = TagChipTone.Normal,
) {
    if (isMaterial3Theme()) {
        val background: Color
        val foreground: Color
        when (tone) {
            TagChipTone.Normal -> {
                background = MaterialTheme.colorScheme.secondaryContainer
                foreground = MaterialTheme.colorScheme.onSecondaryContainer
            }
            TagChipTone.Emphasized -> {
                background = MaterialTheme.colorScheme.primaryContainer
                foreground = MaterialTheme.colorScheme.onPrimaryContainer
            }
        }
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = background,
            contentColor = foreground,
        ) {
            M3Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                // 内边距取官方 Chip 内容内边距令牌，不再手写 8dp/2dp。
                modifier = Modifier.padding(AssistChipDefaults.ContentPadding),
            )
        }
        return
    }
    val background: Color
    val foreground: Color
    when (tone) {
        TagChipTone.Normal -> {
            background = MiuixTheme.colorScheme.secondaryContainer
            foreground = MiuixTheme.colorScheme.onSecondaryContainer
        }
        TagChipTone.Emphasized -> {
            background = MiuixTheme.colorScheme.primaryContainer
            foreground = MiuixTheme.colorScheme.onPrimaryContainer
        }
    }
    Surface(
        color = background,
        contentColor = foreground,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 供应商页共用的自适应小组件（仅本包内使用）
// ---------------------------------------------------------------------------

/** 顶栏/行尾图标按钮：M3 用官方 [M3IconButton]，MIUI X 保持原有图标按钮。 */
@Composable
internal fun ProviderIconAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    if (isMaterial3Theme()) {
        M3IconButton(onClick = onClick, enabled = enabled) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    } else {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}

/** 主操作按钮：M3 用官方 [Button]（不覆盖形状与配色），MIUI X 保持原有主色文字按钮。 */
@Composable
internal fun ProviderPrimaryAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (isMaterial3Theme()) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) {
            M3Text(text = text)
        }
    } else {
        TextButton(
            text = text,
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/** 破坏性操作按钮：M3 用官方 [Button] + 官方 error 角色色，MIUI X 保持原有红色文字按钮。 */
@Composable
internal fun ProviderDestructiveAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (isMaterial3Theme()) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = M3ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            M3Text(text = text)
        }
    } else {
        TextButton(
            text = text,
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = ButtonDefaults.textButtonColorsPrimary(
                color = MiuixTheme.colorScheme.error,
                textColor = MiuixTheme.colorScheme.onError,
            ),
        )
    }
}

/** 可点击设置行：M3 用官方 [ListItem]（onClick 重载自带涟漪与语义），MIUI X 保持原有 [ArrowPreference]。 */
@Composable
internal fun ProviderClickableRow(
    title: String,
    summary: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    if (isMaterial3Theme()) {
        val supportingText = summary
        val supporting: (@Composable () -> Unit)? = if (supportingText != null) {
            {
                M3Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        }
        ListItem(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            leadingContent = leading,
            content = { M3Text(text = title, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = supporting,
        )
    } else {
        ArrowPreference(
            title = title,
            summary = summary,
            enabled = enabled,
            startAction = leading,
            onClick = onClick,
        )
    }
}

/** 纯展示设置行：M3 用官方 [ListItem]，MIUI X 保持原有 [BasicComponent]。 */
@Composable
internal fun ProviderNoticeRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    if (isMaterial3Theme()) {
        val supportingText = summary
        val supporting: (@Composable () -> Unit)? = if (supportingText != null) {
            {
                M3Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        }
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            leadingContent = leading,
            content = { M3Text(text = title, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = supporting,
        )
    } else {
        BasicComponent(
            title = title,
            summary = summary,
            startAction = leading,
            enabled = enabled,
        )
    }
}

/** 展开/收起行：M3 用官方 [ListItem] + 旋转箭头（动画曲线取官方 motionScheme），MIUI X 保持原有 BasicComponent。 */
@Composable
internal fun ProviderExpandableRow(
    title: String,
    summary: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val target = if (expanded) 180f else 0f
    val rotation by if (isMaterial3Theme()) {
        animateFloatAsState(
            targetValue = target,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(),
            label = "provider-expand-chevron",
        )
    } else {
        animateFloatAsState(targetValue = target, label = "provider-expand-chevron")
    }
    if (isMaterial3Theme()) {
        val supportingText = summary
        val supporting: (@Composable () -> Unit)? = if (supportingText != null) {
            {
                M3Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        }
        ListItem(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.fillMaxWidth(),
            content = { M3Text(text = title, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = supporting,
            trailingContent = {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                )
            },
        )
    } else {
        BasicComponent(
            title = title,
            summary = summary,
            endActions = {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.rotate(rotation),
                )
            },
            onClick = { onExpandedChange(!expanded) },
        )
    }
}

/** 强调色的「新增」行：M3 用官方 [ListItem]（主色标题 + 加号图标），MIUI X 保持原有主色 BasicComponent。 */
@Composable
internal fun ProviderAddRow(
    text: String,
    onClick: () -> Unit,
) {
    if (isMaterial3Theme()) {
        ListItem(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            leadingContent = {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            content = {
                M3Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
    } else {
        BasicComponent(
            title = text,
            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.primary),
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            onClick = onClick,
        )
    }
}

/** 下拉选择行：M3 用官方 [ExposedDropdownMenuBox] + [DropdownMenuItem]，MIUI X 保持原有 Spinner 偏好。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderDropdownRow(
    title: String,
    summary: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    enabled: Boolean = true,
) {
    if (isMaterial3Theme()) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            ProviderDropdownAnchorRow(
                title = title,
                summary = summary,
                enabled = enabled,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                items.forEachIndexed { index, label ->
                    // 官方可选项菜单：形状按位置取官方 MenuDefaults.itemShape，选中态用官方对勾。
                    SelectableDropdownMenuItem(
                        selected = index == selectedIndex,
                        onClick = {
                            expanded = false
                            onSelectedIndexChange(index)
                        },
                        text = { M3Text(text = label) },
                        shapes = MenuDefaults.itemShape(index = index, count = items.size),
                        selectedLeadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        }
        return
    }
    WindowSpinnerPreference(
        items = items.map { DropdownItem(text = it) },
        selectedIndex = selectedIndex,
        title = title,
        summary = summary,
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ExposedDropdownMenuBoxScope.ProviderDropdownAnchorRow(
    title: String,
    summary: String,
    enabled: Boolean,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        enabled = enabled,
        content = { M3Text(text = title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            M3Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** 单行文本输入：M3 用官方 [OutlinedTextField]，MIUI X 保持原有 [TextField]。 */
@Composable
internal fun ProviderField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    if (isMaterial3Theme()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            label = { M3Text(text = label) },
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
        )
    } else {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = modifier.fillMaxWidth(),
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
        )
    }
}

/** 分隔线：M3 用官方 [HorizontalDivider]，MIUI X 保持原有分隔线。 */
@Composable
internal fun ProviderDivider(modifier: Modifier = Modifier) {
    if (isMaterial3Theme()) {
        androidx.compose.material3.HorizontalDivider(modifier = modifier)
    } else {
        top.yukonga.miuix.kmp.basic.HorizontalDivider(modifier = modifier)
    }
}

/** 搜索框：M3 用官方 [OutlinedTextField] + 搜索图标，MIUI X 保持原有 [InputField]。 */@Composable
internal fun ProviderSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    if (isMaterial3Theme()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { M3Text(text = placeholder) },
            leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
        )
    } else {
        InputField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            label = placeholder,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
