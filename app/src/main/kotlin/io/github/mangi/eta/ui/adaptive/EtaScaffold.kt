package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R

/**
 * 二级列表页骨架：标准 [Scaffold] + [LargeFlexibleTopAppBar] + [LazyColumn]。
 */
@Composable
fun EtaScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    topBarModifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    floatingActionButton: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    EtaMaterialScaffoldPage(
        title = title,
        onBack = onBack,
        modifier = modifier,
        actions = actions,
        listState = listState,
        topBarModifier = topBarModifier,
        listModifier = listModifier,
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/**
 * FAB 的展开态随列表滚动收起 / 展开（KernelSU `TemplateMaterial` 同款阈值状态机）：
 * 回到顶部必定展开；向下滚动累计超过 [threshold] 收起；向上滚动累计超过 [threshold] 展开。
 *
 * 供页面用 `EtaScaffoldPage(floatingActionButton = …)` + `EtaExtendedFab(expanded = …)` 消费。
 */
@Composable
fun rememberFabExpandedState(
    listState: LazyListState,
    threshold: Dp = 100.dp,
): State<Boolean> {
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    return remember(listState, thresholdPx) {
        var lastIndex = 0
        var lastOffset = 0
        var scrollDelta = 0f
        var expanded = true
        derivedStateOf {
            val currentIndex = listState.firstVisibleItemIndex
            val currentOffset = listState.firstVisibleItemScrollOffset
            val delta = when {
                currentIndex == lastIndex -> (currentOffset - lastOffset).toFloat()
                currentIndex > lastIndex -> thresholdPx
                else -> -thresholdPx
            }
            scrollDelta = (scrollDelta + delta).coerceIn(-thresholdPx, thresholdPx)
            lastIndex = currentIndex
            lastOffset = currentOffset
            when {
                currentIndex == 0 -> {
                    expanded = true
                    scrollDelta = 0f
                }
                expanded && scrollDelta >= thresholdPx -> {
                    expanded = false
                    scrollDelta = 0f
                }
                !expanded && scrollDelta <= -thresholdPx -> {
                    expanded = true
                    scrollDelta = 0f
                }
            }
            expanded
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EtaMaterialScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    topBarModifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    floatingActionButton: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    // 大标题随滚动折叠成单行顶栏（M3 LargeTopAppBar）。
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // KernelSU 式：整页背景与顶栏同用 surfaceContainer，分段条目（surfaceBright）浮在其上。
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = topBarModifier,
                navigationIcon = {
                    EtaTopBarBackButton(
                        onClick = onBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        // Scaffold 不会为 FAB 让出导航栏安全区，这里按 KernelSU `TemplateMaterial` 的做法补上。
        floatingActionButtonPosition = FabPosition.End,
        floatingActionButton = floatingActionButton?.let { fab ->
            {
                Box(
                    modifier = Modifier.padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    fab()
                }
            }
        } ?: {},
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = listModifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
        ) {
            content()
            item(key = "eta_bottom_spacer") {
                // FAB 浮在列表之上，末条内容需要额外让出「Small Extended FAB 高度 + 底部间距」。
                Spacer(
                    modifier = Modifier
                    .height(if (floatingActionButton != null) 88.dp else 24.dp)
                    .navigationBarsPadding(),
                )
            }
        }
    }
}

/**
 * 自定义内容二级页骨架。
 *
 * 内容回调提供 `paddingValues`、用于 `nestedScroll` 的滚动连接，以及宽屏内容侧边距。
 */
@Composable
fun EtaScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (
        paddingValues: PaddingValues,
        scrollConnection: NestedScrollConnection,
        sidePadding: Dp,
    ) -> Unit,
) {
    EtaMaterialScaffold(title, onBack, modifier, actions, content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EtaMaterialScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues, NestedScrollConnection, Dp) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // KernelSU 式：整页背景与顶栏同用 surfaceContainer，分段条目（surfaceBright）浮在其上。
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    EtaTopBarBackButton(
                        onClick = onBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            content(paddingValues, scrollBehavior.nestedScrollConnection, 0.dp)
        }
    }
}
