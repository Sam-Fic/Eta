package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentPreset
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import android.content.Context

/** A failed catalog read/write remains visible until an explicit retry. Never show it as an empty catalog. */
internal class SubAgentPresetDirectory(private val repository: ConversationSubAgentPreferences, private val context: Context) {
    var entries by mutableStateOf<List<SubAgentPreset>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    private var retryVersion by mutableIntStateOf(0)
    private fun fail(failure: Exception) {
        if (failure is CancellationException) throw failure
        error = failure.message ?: failure.javaClass.simpleName
        loading = false
    }
    @Composable fun observe() {
        val version = retryVersion
        LaunchedEffect(this, version) {
            if (error != null) return@LaunchedEffect
            try {
                entries = repository.presets()
                loading = false
                repository.presetsFlow().collect { if (error == null) entries = it }
            } catch (failure: Exception) { fail(failure) }
        }
    }
    fun change(action: () -> Unit): Boolean {
        if (loading || error != null) return false
        return try {
            action()
            entries = repository.presets()
            true
        } catch (failure: Exception) { fail(failure); false }
    }
    fun retry() {
        try {
            check(repository.recoverDurability()) { context.getString(R.string.spc_restore_failed) }
            error = null
            loading = true
            retryVersion++
        } catch (failure: Exception) { fail(failure) }
    }
}

@Composable
internal fun rememberSubAgentPresetDirectory(repository: ConversationSubAgentPreferences): SubAgentPresetDirectory {
    val context = LocalContext.current
    return remember(repository) { SubAgentPresetDirectory(repository, context) }.also { it.observe() }
}

@Composable
internal fun SubAgentPresetDirectoryStatus(directory: SubAgentPresetDirectory) {
    val view = LocalView.current
    val error = directory.error
    when {
        error != null -> {
            Text(stringResource(R.string.spc_dir_error, error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { TouchHaptics.click(view); directory.retry() }) { Text(stringResource(R.string.spc_retry_read)) }
        }
        directory.loading -> Text(stringResource(R.string.spc_reading))
        directory.entries.isEmpty() -> Text(stringResource(R.string.spc_empty))
    }
}

/** Official M3 container: Card owns shape/colors/elevation, the row is an interactive ListItem. */
@Composable
internal fun SubAgentPresetCard(
    preset: SubAgentPreset,
    enabled: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val view = LocalView.current
    // 官方 Card（本版本 Card 即填充卡，形状取 CardDefaults.shape、颜色取 CardDefaults.cardColors，
    // 底层是 FilledCardTokens）：不再手写 RoundedCornerShape(24.dp) 或 surface 叠色。
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        // 尾部「更多操作」按钮放在 ListItem 外层当兄弟节点。ListItem 内部是
        // semantics(mergeDescendants = true)，塞进 trailingContent 会被吸进父节点，
        // TalkBack 就无法再单独聚焦这个按钮（普通 Surface/Card 不合并，行本身才留得住）。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ListItem(
                onClick = { TouchHaptics.click(view); onClick() },
                // cd 与子文本只部分重叠：重叠的是组名，补足的是「编辑/应用子代理组」这个动作 affordance，
                // 值得保留（代价是组名念两次）。这与 SubAgentSettingRow 的既有写法一致：
                // description「设置XX模型」+ 可见 label「模型」。
                // actionLabel 由调用方拼装（含组名），不在这里做 removeSuffix 之类的字符串裁剪。
                modifier = Modifier.weight(1f).semantics { contentDescription = actionLabel },
                enabled = enabled,
                // 卡片容器是 primaryContainer，正文/图标必须取配对的 onPrimaryContainer；
                // 不覆盖时 ListItemDefaults 会退回 onSurface / onSurfaceVariant，落在填充卡上对比度不足。
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    supportingColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    leadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    trailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                leadingContent = {
                    // 不写死 tint：跟随 ListItemDefaults 的 leadingIconColor（onPrimaryContainer）。
                    Icon(Icons.Rounded.AccountTree, null)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                },
                content = {
                    Column {
                        Text(preset.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.spc_profiles_summary, preset.config.profiles.size,
                            stringResource(if (preset.config.enabled) R.string.spc_auto_on else R.string.spc_auto_off)),
                            style = MaterialTheme.typography.bodyMedium)
                        // 「正在使用」同样落在 primaryContainer 上，取 onPrimaryContainer 而不是 primary
                        // （primary 在自家 primaryContainer 容器上对比度不足）。
                        if (selected) Text(stringResource(R.string.spc_current_in_use), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
            )
            // 尾部「更多操作」按钮是 ListItem 的兄弟节点：ListItem 是合并型宿主，
            // 塞进 trailingContent 会让它被吸进父节点，TalkBack 就无法单独聚焦这个按钮。
            trailing?.invoke()
        }
    }
}
