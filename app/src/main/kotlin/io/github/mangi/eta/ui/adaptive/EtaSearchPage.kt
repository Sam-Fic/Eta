package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay

/**
 * 独立搜索页骨架：从宿主页右上角的单搜索图标跳转进来，整页就是搜索。
 *
 * 顶栏为官方 [TopAppBar]：左侧返回钮 + 标题槽透明底搜索框（无边框融入标题栏）+
 * 右侧清除按钮（有输入时才出现）。进入页面自动聚焦并弹出输入法；IME 搜索动作会记录
 * 查询历史并收起键盘，过滤逻辑由调用方的 [content] 按搜索词实时完成（输入即出结果）。
 *
 * 未输入时：有历史则显示「搜索记录」列表（点一条回填搜索框，可一键清除）；
 * 无历史则显示官方 Empty state（空状态）——居中的非交互占位图标 + 提示标语，
 * **不复制宿主页内容**（避免与主页重复）；有搜索结果由各页自己的空态文案兜底。
 * 结果点按方（各搜索页）可调 [EtaSearchHistory.add] 记录当时搜索词。
 *
 * 与宿主页的 [EtaScaffoldPage] 保持同一套背景色与底栏安全区处理；[query] 用
 * `rememberSaveable` 保存，旋转屏幕不丢关键词。
 *
 * @param searchPlaceholder 搜索框占位文案（通常即「搜索XX」），同时用作空态标语。
 * @param onBack 顶栏返回钮回调。
 * @param historyKey 查询历史的存储分域键（如 "assistants"）；null 禁用历史。
 * @param content 列表内容，参数为当前搜索词；仅在有输入时渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtaSearchPage(
    searchPlaceholder: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    historyKey: String? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.(query: String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    // 历史状态只在页内两处变更（IME 提交 / 清除），结果点按方直接写盘，
    // 返回本页时 remember 重建自然重读。
    var history by remember(historyKey) {
        mutableStateOf(historyKey?.let { EtaSearchHistory.load(context, it) }.orEmpty())
    }

    // 进入页面即聚焦并弹出输入法（等输入框摆好后请求，失败稍候重试）。
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(32)
        }
    }

    /** 记录一次搜索（IME 提交时调用），空查询忽略。 */
    fun recordSearch(text: String) {
        val key = historyKey ?: return
        EtaSearchHistory.add(context, key, text)
        history = EtaSearchHistory.load(context, key)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // KernelSU 式：整页背景与顶栏同用 surfaceContainer，分段条目（surfaceBright）浮在其上。
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        placeholder = {
                            Text(
                                text = searchPlaceholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                recordSearch(query)
                                focusManager.clearFocus()
                            },
                        ),
                    )
                },
                navigationIcon = {
                    EtaTopBarBackButton(
                        onClick = onBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                },
                actions = {
                    // 有输入内容时才出现清除按钮。
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_close),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
        ) {
            if (query.isBlank()) {
                // 未输入：有历史先列「搜索记录」（点一条回填搜索框，可一键清除）；
                // 无历史再退回官方 Empty state（空状态）占位。都不铺宿主页内容。
                if (history.isNotEmpty()) {
                    // 历史列表的间距/图标规格与设置主页的分组列表完全一致：
                    // 组标题下 8dp、行间 2dp 露出背景缝、组末 13dp、左右页边距 16dp。
                    item(key = "search_history_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = EtaGroupHorizontalPadding,
                                    end = EtaGroupHorizontalPadding,
                                    bottom = EtaSegmentedTitleSpacing,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.action_search_history),
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                historyKey?.let { EtaSearchHistory.clear(context, it) }
                                history = emptyList()
                            }) {
                                Text(text = stringResource(R.string.action_clear))
                            }
                        }
                    }
                    history.forEachIndexed { index, record ->
                        item(key = "search_history_$index") {
                            EtaSegmentedItem(index = index, count = history.size) {
                                EtaClickableRow(
                                    title = record,
                                    leading = {
                                        // 与设置主页行首图标同一规格（onSurfaceVariant 24dp）。
                                        Icon(
                                            imageVector = Icons.Rounded.History,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(end = 8.dp)
                                                .size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    showChevron = false,
                                    onClick = { query = record },
                                    modifier = Modifier.padding(
                                        start = EtaGroupHorizontalPadding,
                                        end = EtaGroupHorizontalPadding,
                                        bottom = if (index == history.lastIndex) {
                                            EtaSegmentedGroupSpacing
                                        } else {
                                            EtaSegmentedGap
                                        },
                                    ),
                                )
                            }
                        }
                    }
                } else {
                    item(key = "search_empty_state") {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(horizontal = 32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(72.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = searchPlaceholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            } else {
                content(query)
                item(key = "eta_bottom_spacer") {
                    Spacer(
                        modifier = Modifier
                            .height(24.dp)
                            .navigationBarsPadding(),
                    )
                }
            }
        }
    }
}
