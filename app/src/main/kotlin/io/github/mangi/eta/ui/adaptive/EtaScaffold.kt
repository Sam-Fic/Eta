package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.MiuixScaffoldPage

/**
 * 二级列表页骨架的自适应实现。
 *
 * - MIUI X：委托 [MiuixScaffoldPage]（大标题折叠 + 顶栏毛玻璃 + 越界回弹）。
 * - Material 3：标准 [Scaffold] + [TopAppBar] + [LazyColumn]。
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
    content: LazyListScope.() -> Unit,
) {
    if (isMaterial3Theme()) {
        EtaMaterialScaffoldPage(
            title = title,
            onBack = onBack,
            modifier = modifier,
            actions = actions,
            listState = listState,
            topBarModifier = topBarModifier,
            listModifier = listModifier,
            content = content,
        )
    } else {
        MiuixScaffoldPage(
            title = title,
            onBack = onBack,
            modifier = modifier,
            actions = actions,
            listState = listState,
            topBarModifier = topBarModifier,
            listModifier = listModifier,
        ) {
            content()
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
    content: LazyListScope.() -> Unit,
) {
    // 与 MIUI X 侧一致：大标题随滚动折叠成单行顶栏（M3 LargeTopAppBar）。
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = topBarModifier,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        },
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
                Spacer(
                    modifier = Modifier
                        .height(24.dp)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

/**
 * 自定义内容二级页骨架的自适应实现。
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
    if (isMaterial3Theme()) {
        EtaMaterialScaffold(title, onBack, modifier, actions, content)
    } else {
        MiuixScaffold(
            title = title,
            onBack = onBack,
            modifier = modifier,
            actions = actions,
        ) { paddingValues, scrollBehavior, sidePadding ->
            content(paddingValues, scrollBehavior.nestedScrollConnection, sidePadding)
        }
    }
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            content(paddingValues, scrollBehavior.nestedScrollConnection, 0.dp)
        }
    }
}
