package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.mangi.eta.agent.delegation.SubAgentPreset
import io.github.mangi.eta.ui.haptics.TouchHaptics

/** Catalog actions only; profile/model configuration keeps its existing dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentPresetActionsSheet(
    preset: SubAgentPreset,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        // 形状、容器色与拖拽手柄全部走官方默认值
        // （shape = BottomSheetDefaults.ExpandedShape、dragHandle = BottomSheetDefaults.DragHandle()），不手写圆角。
    ) {
        // Wrap content instead of imposing a large sheet height, and allow short landscape windows.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .semantics { contentDescription = "子代理组操作面板" }) {
            // 官方展示型 ListItem：容器形状/内边距/排版/图标槽位均为 ListItemDefaults 默认值。
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                leadingContent = { Icon(Icons.Rounded.AccountTree, contentDescription = null) },
                content = { Text(preset.name, style = MaterialTheme.typography.titleLarge) },
            )
            HorizontalDivider()
            SubAgentPresetActionRow("重命名组", Icons.Rounded.Edit, MaterialTheme.colorScheme.onSurface, onRename)
            SubAgentPresetActionRow("删除组", Icons.Rounded.DeleteOutline, MaterialTheme.colorScheme.error, onDelete)
        }
    }
}

@Composable
private fun SubAgentPresetActionRow(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    val view = LocalView.current
    // 官方可交互 ListItem：点击目标、涟漪、Role.Button 语义、按压形变与内边距全部由组件提供，
    // 不再用 Row + clickable + 手写 padding 拼装。
    ListItem(
        onClick = { TouchHaptics.click(view); onClick() },
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        content = { Text(label, style = MaterialTheme.typography.bodyLarge, color = tint) },
    )
}
