package io.github.mangi.eta.ui.pages.providers

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.BalanceOption
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderBalanceFetcher
import io.github.mangi.eta.data.repository.ProviderBalanceState
import io.github.mangi.eta.data.repository.formatBalanceDisplay
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text

/**
 * 从 UI 触发的余额刷新需要一个长生命周期协程作用域：composition 随页面销毁会取消在途请求。
 * 这里沿 Context 包裹链向上寻找宿主 Activity 的 LifecycleOwner，拿它的 lifecycleScope。
 */
internal fun Context.activityLifecycleOwnerOrNull(): LifecycleOwner? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is LifecycleOwner) return current
        current = current.baseContext
    }
    return current as? LifecycleOwner
}

/** 下拉框顺序与预设一一对应。 */
private val BalancePresets = listOf(
    BalanceOption.PRESET_CUSTOM,
    BalanceOption.PRESET_NEW_API,
    BalanceOption.PRESET_SUB2API,
    BalanceOption.PRESET_DEEPSEEK,
)

@Composable
internal fun ProviderBalanceOptionFields(
    balanceOption: BalanceOption,
    onBalanceOptionChange: (BalanceOption) -> Unit,
    provider: ProviderSetting? = null,
) {
    var expanded by remember { mutableStateOf(balanceOption.enabled) }
    var locallyEnabled by remember { mutableStateOf(balanceOption.enabled) }
    val context = LocalContext.current
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var accessTokenVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resolved = balanceOption.copy(enabled = locallyEnabled || balanceOption.enabled).resolved()
    val isNewApi = resolved.preset == BalanceOption.PRESET_NEW_API
    val switchOn = locallyEnabled || balanceOption.enabled
    val onSwitchChange: (Boolean) -> Unit = { value ->
        locallyEnabled = value
        onBalanceOptionChange(balanceOption.copy(enabled = value))
        if (value) expanded = true
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        val headerTitle = stringResource(R.string.ui_balance_info_title)
        val headerSummary = stringResource(R.string.ui_balance_info_summary)
        if (isMaterial3Theme()) {
            // M3：官方可点击 ListItem 承担展开/收起，开关使用 Expressive 勾选样式。
            ListItem(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                content = { M3Text(text = headerTitle, style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    M3Text(
                        text = headerSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    EtaSwitch(checked = switchOn, onCheckedChange = onSwitchChange)
                },
            )
        } else {
            BasicComponent(
                title = headerTitle,
                summary = headerSummary,
                onClick = { expanded = !expanded },
                endActions = {
                    MiuixSwitch(
                        checked = switchOn,
                        onCheckedChange = onSwitchChange,
                    )
                },
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                val presetIndex = BalancePresets.indexOf(resolved.preset).coerceAtLeast(0)
                ProviderDropdownRow(
                    title = stringResource(R.string.ui_balance_preset),
                    summary = stringResource(
                        when (BalancePresets[presetIndex]) {
                            BalanceOption.PRESET_NEW_API -> R.string.ui_balance_preset_new_api_summary
                            BalanceOption.PRESET_SUB2API -> R.string.ui_balance_preset_sub2api_summary
                            BalanceOption.PRESET_DEEPSEEK -> R.string.ui_balance_preset_deepseek_summary
                            else -> R.string.ui_balance_preset_custom_summary
                        },
                    ),
                    items = listOf(
                        stringResource(R.string.ui_balance_preset_custom),
                        stringResource(R.string.ui_balance_preset_new_api),
                        stringResource(R.string.ui_balance_preset_sub2api),
                        stringResource(R.string.ui_balance_preset_deepseek),
                    ),
                    selectedIndex = presetIndex,
                    onSelectedIndexChange = { selectedIndex ->
                        onBalanceOptionChange(
                            BalanceOption.applyPreset(
                                BalancePresets.getOrElse(selectedIndex) { BalanceOption.PRESET_CUSTOM },
                                balanceOption.copy(enabled = switchOn),
                            ),
                        )
                    },
                )
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    if (isNewApi) {
                        ProviderField(
                            value = balanceOption.accessToken,
                            onValueChange = {
                                onBalanceOptionChange(balanceOption.copy(accessToken = it, enabled = switchOn))
                            },
                            label = stringResource(R.string.ui_balance_access_token),
                            visualTransformation = if (accessTokenVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                ProviderIconAction(
                                    icon = if (accessTokenVisible) {
                                        Icons.Rounded.Visibility
                                    } else {
                                        Icons.Rounded.VisibilityOff
                                    },
                                    contentDescription = if (accessTokenVisible) {
                                        context.getString(R.string.page_hide_bb0e7e)
                                    } else {
                                        context.getString(R.string.page_show_71b677)
                                    },
                                    onClick = { accessTokenVisible = !accessTokenVisible },
                                )
                            },
                        )
                        BalanceHint(
                            text = stringResource(R.string.ui_balance_access_token_summary),
                            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                        )
                    } else {
                        ProviderField(
                            value = balanceOption.apiPath,
                            onValueChange = {
                                onBalanceOptionChange(balanceOption.copy(apiPath = it, enabled = switchOn))
                            },
                            label = stringResource(R.string.ui_balance_api_path),
                        )
                        ProviderField(
                            value = balanceOption.resultPath,
                            onValueChange = {
                                onBalanceOptionChange(balanceOption.copy(resultPath = it, enabled = switchOn))
                            },
                            label = stringResource(R.string.ui_balance_result_path),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    val onTestBalance: () -> Unit = {
                        val currentProvider = provider
                        if (currentProvider != null) scope.launch {
                            isTesting = true
                            testResult = null
                            val option = balanceOption.copy(enabled = true).resolved()
                            testResult = if (option.apiPath.isBlank() || option.resultPath.isBlank()) {
                                context.getString(R.string.ui_balance_test_missing_fields)
                            } else {
                                ProviderBalanceFetcher.fetch(currentProvider, option)
                                    .fold(
                                        onSuccess = { formatBalanceDisplay(it) },
                                        onFailure = { it.message ?: it.toString() },
                                    )
                            }
                            isTesting = false
                        }
                    }
                    ProviderPrimaryAction(
                        text = if (isTesting) {
                            context.getString(R.string.page_testing_f43705)
                        } else {
                            stringResource(R.string.ui_test_balance)
                        },
                        onClick = onTestBalance,
                        enabled = switchOn && !isTesting && provider != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    testResult?.let {
                        ProviderBalanceAmount(
                            amount = it,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceHint(text: String, modifier: Modifier = Modifier) {
    if (isMaterial3Theme()) {
        M3Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    } else {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            modifier = modifier,
        )
    }
}

@Composable
internal fun ProviderBalanceAmount(
    amount: String,
    modifier: Modifier = Modifier,
) {
    val contentColor: Color = if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Rounded.Savings,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(12.dp),
            tint = contentColor,
        )
        if (isMaterial3Theme()) {
            M3Text(
                text = amount,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = amount,
                style = MiuixTheme.textStyles.footnote1,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 只有拿到过成功金额才占用空间。
 *
 * 余额刷新在后台静默进行：既没有“刷新中”，也不显示“已刷新”之类的成功提示，
 * 无缓存金额的失败同样不再插入提示文本，避免刷新过程在顶部栏与模型选择器里闪烁。
 */
internal fun hasBalanceIndicatorContent(state: ProviderBalanceState?): Boolean =
    state?.amount != null

/**
 * 顶部栏与模型选择器共用的余额只读指示器。
 *
 * - 只展示最后一次成功金额；刷新继续在后台更新数字，但不再显示刷新中/已刷新等提示。
 * - 金额与图标默认使用主题中性文字色，缓存过期或刷新失败不改变颜色。
 * - 只读：不响应点击，也不弹出余额详情。
 * - [animateChanges] 为 true 时（仅顶部栏），余额变化会播放一次动画。
 */
@Composable
internal fun ProviderBalanceIndicator(
    state: ProviderBalanceState?,
    modifier: Modifier = Modifier,
    animateChanges: Boolean = false,
) {
    if (state == null) return
    val amount = state.amount ?: return
    if (animateChanges) {
        AnimatedProviderBalanceIndicator(state = state, amount = amount, modifier = modifier)
        return
    }
    val amountColor: Color = if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Rounded.Savings,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(12.dp),
            tint = amountColor,
        )
        if (isMaterial3Theme()) {
            M3Text(
                text = amount,
                style = MaterialTheme.typography.labelSmall,
                color = amountColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = amount,
                style = MiuixTheme.textStyles.footnote1,
                color = amountColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
