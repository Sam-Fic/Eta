package io.github.mangi.eta.ui.screens.tools

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolRecipeStore
import io.github.mangi.eta.agent.tool.ToolRecipe
import io.github.mangi.eta.agent.tool.ToolRecipeShareException
import io.github.mangi.eta.agent.tool.ToolRecipeShareFormat
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaNoticeText
import io.github.mangi.eta.ui.adaptive.EtaPageSurface
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text as M3Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配方运行时——设置页与管理页共用的唯一数据源。
 *
 * 管理页与 agent 运行期必须看到同一份配方，因此这里持有唯一的 [io.github.mangi.eta.agent.tool.AgentToolRecipeStore]；
 * 用 StateFlow 暴露，管理页的启停/删除即时反映到模型工具列表。
 */
internal class ToolRecipeRuntime private constructor(context: Context) {
    private val store = AgentToolRecipeStore.get(context.applicationContext)

    /** 当前配方快照；每次写入后重新发射。 */
    val recipes = kotlinx.coroutines.flow.MutableStateFlow<List<ToolRecipe>>(emptyList())

    /** 每次落盘/删除后调用，刷新快照。 */
    fun refresh() {
        recipes.value = store.all()
    }

    fun all(): List<ToolRecipe> = store.all()

    fun find(name: String): ToolRecipe? = store.find(name)

    /** 组合链上需要 Root 的配方名集合——推导需要完整配方图，所以按快照整体算。 */
    fun rootDependentNames(): Set<String> = store.rootDependentRecipes(all())

    fun setEnabled(name: String, enabled: Boolean): Boolean =
        store.setEnabled(name, enabled)?.also { refresh() } != null

    fun delete(name: String): Boolean = store.delete(name).also { refresh() }

    /** 覆盖保存（导入 / 编辑共用）：不做存在性检查，由调用方决定。 */
    fun save(recipe: ToolRecipe): Boolean =
        when (val result = store.save(recipe)) {
            is AgentToolRecipeStore.SaveResult.Ok -> {
                refresh(); true
            }

            is AgentToolRecipeStore.SaveResult.Failed -> false
        }

    companion object {
        @Volatile
        private var instance: ToolRecipeRuntime? = null

        fun get(context: Context): ToolRecipeRuntime =
            instance ?: synchronized(this) {
                instance ?: ToolRecipeRuntime(context).also { it.refresh() }
            }
    }
}

/**
 * 自建工具管理页。
 *
 * 这个页面存在的理由：配方是模型自己写、自己调用的能力，用户必须能
 * - **审计**：每条配方到底做了什么（步骤一览）
 * - **接管**：随时停用 / 启用，不让模型继续调用
 * - **编辑**：改描述、改步骤（改坏了的配方由模型自己修，用户也能直接修）
 * - **分享**：导出/导入，让自进化成果离开这台设备
 */
@Composable
internal fun ToolRecipesScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val runtime = remember(context) { ToolRecipeRuntime.get(context) }
    val recipes by runtime.recipes.collectAsState()
    val rootDependent by runtime.recipes.collectAsState()
    val rootNeeding = remember(rootDependent) { runtime.rootDependentNames() }
    var editing by remember { mutableStateOf<ToolRecipe?>(null) }
    var deleteTarget by remember { mutableStateOf<ToolRecipe?>(null) }
    var shareTarget by remember { mutableStateOf<ToolRecipe?>(null) }
    var importReport by remember { mutableStateOf<String?>(null) }

    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val recipe = shareTarget
        shareTarget = null
        if (uri == null || recipe == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = runCatching {
                val payload = withContext(Dispatchers.IO) { ToolRecipeShareFormat.encode(listOf(recipe)) }
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
                    ?: error(resources.getString(R.string.recipe_export_failed))
                resources.getString(R.string.recipe_exported, recipe.name)
            }.getOrElse { throwable ->
                throwable.message ?: resources.getString(R.string.recipe_export_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.decodeToString()
                        ?: error(resources.getString(R.string.recipe_import_failed))
                    val imported = ToolRecipeShareFormat.decode(raw)
                    // 导入的配方一律停用：先让用户看过，再决定是否交给模型。
                    imported.forEach { recipe ->
                        runtime.save(recipe.copy(enabled = false))
                    }
                    resources.getString(R.string.recipe_imported, imported.size)
                }.getOrElse { throwable ->
                    when (throwable) {
                        is ToolRecipeShareException -> throwable.message ?: resources.getString(R.string.recipe_import_invalid)
                        else -> throwable.message ?: resources.getString(R.string.recipe_import_failed)
                    }
                }
            }
            importReport = report
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.route_tool_recipes),
        onBack = onBack,
        actions = {
            M3IconButton(
                onClick = {
                    TouchHaptics.click(view)
                    scope.launch(Dispatchers.IO) { runtime.refresh() }
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.action_refresh),
                )
            }
        },
    ) {
        item(key = "summary") {
            // 纯说明文字不套分段卡片：直接排在页面背景上，视觉层级更清楚。
            EtaNoticeText(
                title = stringResource(R.string.recipe_page_summary_title, recipes.size),
                summary = stringResource(R.string.recipe_page_summary),
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            )
        }
        item(key = "actions") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
                title = stringResource(R.string.recipe_share_section),
            ) {
                item {
                    RecipeActionRow(
                        title = stringResource(R.string.recipe_import),
                        summary = stringResource(R.string.recipe_import_summary),
                        icon = Icons.Rounded.Add,
                        onClick = {
                            TouchHaptics.click(view)
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                    )
                }
            }
        }
        item(key = "recipes") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
                title = stringResource(R.string.recipe_list_title, recipes.size),
            ) {
                if (recipes.isEmpty()) {
                    item {
                        EtaSegmentedItemContainer {
                            ListEmptyState(
                                title = stringResource(R.string.recipe_empty_title),
                                summary = stringResource(R.string.recipe_empty_summary),
                            )
                        }
                    }
                } else {
                    recipes
                        .sortedByDescending { it.updatedAtMillis }
                        .forEach { recipe ->
                            item(key = recipe.name) {
                                RecipeRow(
                                    recipe = recipe,
                                    rootNeeded = recipe.name in rootNeeding,
                                    onToggle = { enabled ->
                                        scope.launch(Dispatchers.IO) {
                                            if (!runtime.setEnabled(recipe.name, enabled)) {
                                                importReport = resources.getString(R.string.recipe_toggle_failed)
                                            }
                                        }
                                    },
                                    onEdit = { editing = recipe },
                                    onShare = {
                                        shareTarget = recipe
                                        shareLauncher.launch(recipeShareFileName(recipe.name))
                                    },
                                    onDelete = { deleteTarget = recipe },
                                )
                            }
                        }
                }
            }
        }
    }

    editing?.let { recipe ->
        RecipeEditPage(
            recipe = recipe,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch(Dispatchers.IO) {
                    val ok = runtime.save(updated)
                    importReport = if (ok) {
                        resources.getString(R.string.recipe_saved, updated.name)
                    } else {
                        resources.getString(R.string.recipe_save_failed)
                    }
                }
                editing = null
            },
        )
    }

    deleteTarget?.let { recipe ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.recipe_delete_title),
            summary = stringResource(R.string.recipe_delete_confirm, recipe.name),
            onDismissRequest = { deleteTarget = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { deleteTarget = null },
                    onConfirm = {
                        scope.launch(Dispatchers.IO) { runtime.delete(recipe.name) }
                        deleteTarget = null
                    },
                )
            },
        ) {
        }
    }

    importReport?.let { message ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.recipe_notice_title),
            summary = message,
            onDismissRequest = { importReport = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_ok),
                    onCancel = { importReport = null },
                    onConfirm = { importReport = null },
                )
            },
        ) {
        }
    }
}

/** 单条配方行：名称 + 描述 + 状态，行内可直接编辑 / 分享 / 删除。 */
@Composable
private fun RecipeRow(
    recipe: ToolRecipe,
    rootNeeded: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    // 一条分段行承载整份配方：开关在 trailingContent 里，编辑/分享/删除用图标按钮。
    // 不用第二条行——两条行会被分段列表渲染成两张卡片，一条配方就断了视觉。
    EtaSegmentedRow(
        modifier = Modifier.fillMaxWidth(),
        leadingContent = { RecipeLeadingIcon(Icons.Rounded.Build) },
        content = { M3Text(text = recipe.name) },
        supportingContent = {
            M3Text(
                text = buildString {
                    append(recipe.description)
                    append("\n")
                    append(
                        stringResource(
                            if (recipe.enabled) R.string.recipe_state_enabled else R.string.recipe_state_disabled,
                        ),
                    )
                    if (rootNeeded) {
                        append(" · ")
                        append(stringResource(R.string.recipe_state_root))
                    }
                    append(" · ")
                    append(stringResource(R.string.recipe_steps_count, recipe.steps.size))
                },
            )
        },
        trailingContent = {
            RecipeSwitch(
                checked = recipe.enabled,
                onToggle = onToggle,
            )
            RecipeRowIcon(
                icon = Icons.Rounded.Edit,
                contentDescription = stringResource(R.string.recipe_edit_one),
                onClick = onEdit,
            )
            RecipeRowIcon(
                icon = Icons.Rounded.Share,
                contentDescription = stringResource(R.string.recipe_share_one),
                onClick = onShare,
            )
            RecipeRowIcon(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = stringResource(R.string.recipe_delete_one),
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error,
            )
        },
    )
}

/** 配方行的开关：停用/启用。开关本身不弹确认——它是可逆操作，且行内即时反映。 */
@Composable
private fun RecipeSwitch(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val view = LocalView.current
    Switch(
        checked = checked,
        onCheckedChange = { enabled -> TouchHaptics.click(view); onToggle(enabled) },
    )
}

/** 配方编辑页：改描述与步骤说明；步骤本身由 JSON 承载，用户改坏也能继续让模型修。 */
@Composable
private fun RecipeEditPage(
    recipe: ToolRecipe,
    onDismiss: () -> Unit,
    onSave: (ToolRecipe) -> Unit,
) {
    var description by remember { mutableStateOf(recipe.description) }
    var enabled by remember { mutableStateOf(recipe.enabled) }
    EtaPageSurface(onDismissRequest = onDismiss) {
        EtaScaffold(
            title = stringResource(R.string.recipe_edit_title, recipe.name),
            onBack = onDismiss,
            modifier = Modifier.fillMaxSize().imePadding(),
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    EtaDialogActions(
                        modifier = Modifier.navigationBarsPadding().imePadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        confirmText = stringResource(R.string.action_save),
                        onCancel = onDismiss,
                        onConfirm = { onSave(recipe.copy(description = description, enabled = enabled)) },
                    )
                }
            },
        ) { padding, scrollConnection, _ ->
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .nestedScroll(scrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { M3Text(text = stringResource(R.string.recipe_description)) },
                    minLines = 2,
                )
                EtaSegmentedColumn(
                    title = stringResource(R.string.recipe_steps_section, recipe.steps.size),
                ) {
                    item {
                        EtaSegmentedItemContainer {
                            RecipeStepsText(recipe)
                        }
                    }
                }
                EtaSwitchRow(
                    title = stringResource(R.string.recipe_enable),
                    summary = stringResource(R.string.recipe_enable_summary),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
            }
        }
    }
}

/** 步骤一览：按顺序列出 op 与关键字段，让用户一眼看清配方做了什么。 */
@Composable
private fun RecipeStepsText(recipe: ToolRecipe) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        recipe.steps.forEachIndexed { index, step ->
            M3Text(
                text = "${index + 1}. ${recipeStepLabel(step)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun recipeStepLabel(step: io.github.mangi.eta.agent.tool.ToolRecipeStep): String = when (step) {
    is io.github.mangi.eta.agent.tool.ToolRecipeStep.HttpRequest ->
        stringResource(R.string.recipe_step_http, step.method, step.url)

    is io.github.mangi.eta.agent.tool.ToolRecipeStep.RootShell ->
        stringResource(R.string.recipe_step_root, step.command)

    is io.github.mangi.eta.agent.tool.ToolRecipeStep.SettingsRead ->
        stringResource(R.string.recipe_step_settings_read, step.namespace, step.key)

    is io.github.mangi.eta.agent.tool.ToolRecipeStep.SettingsWrite ->
        stringResource(R.string.recipe_step_settings_write, step.namespace, step.key)

    is io.github.mangi.eta.agent.tool.ToolRecipeStep.FileRead ->
        stringResource(R.string.recipe_step_file_read, step.path)

    is io.github.mangi.eta.agent.tool.ToolRecipeStep.FileWrite ->
        stringResource(R.string.recipe_step_file_write, step.path)

    is io.github.mangi.eta.agent.tool.ToolRecipeStep.ContentQuery ->
        stringResource(R.string.recipe_step_content, step.uri)

    is io.github.mangi.eta.agent.tool.ToolRecipeStep.Compose ->
        stringResource(R.string.recipe_step_compose, step.recipe)

    is io.github.mangi.eta.agent.tool.ToolRecipeStep.Literal ->
        stringResource(R.string.recipe_step_literal)
}

/** 分享文件名：带配方名与时间戳，便于在文件管理器里认出来。 */
private fun recipeShareFileName(name: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "$name-$stamp.${ToolRecipeShareFormat.FILE_SUFFIX}"
}

/** 管理页里的可点击动作行（导入 / 其它批量操作）。 */
@Composable
private fun RecipeActionRow(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    EtaSegmentedRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null)
        },
        content = { M3Text(text = title) },
        supportingContent = { M3Text(text = summary) },
    )
}

@Composable
private fun RecipeLeadingIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RecipeRowIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    M3IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}
