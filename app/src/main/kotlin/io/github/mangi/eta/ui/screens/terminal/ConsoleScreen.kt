package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.LocalView
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.label
import io.github.mangi.eta.agent.terminal.TerminalScreenBuffer
import io.github.mangi.eta.ui.app.ConsoleStore
import io.github.mangi.eta.ui.app.ConsoleUiState
import io.github.mangi.eta.ui.app.UserTerminalStore
import io.github.mangi.eta.ui.components.toSpanStyle
import androidx.compose.material3.Icon
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.isMaterial3Theme
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 终端界面的根背景色：M3 用官方 surface，MIUI X 沿用 miuix surface。 */
@Composable
internal fun terminalRootSurface(): androidx.compose.ui.graphics.Color =
    if (isMaterial3Theme()) MaterialTheme.colorScheme.surface else MiuixTheme.colorScheme.surface

/** 终端正文前景色。 */
@Composable
internal fun terminalForeground(): androidx.compose.ui.graphics.Color =
    if (isMaterial3Theme()) MaterialTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface

/** 终端次要前景色（摘要、提示）。 */
@Composable
internal fun terminalMutedForeground(): androidx.compose.ui.graphics.Color =
    if (isMaterial3Theme()) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

/** 终端强调色。 */
@Composable
internal fun terminalAccentColor(): androidx.compose.ui.graphics.Color =
    if (isMaterial3Theme()) MaterialTheme.colorScheme.primary else MiuixTheme.colorScheme.primary

/** 终端错误/失败态前景色。 */
@Composable
internal fun terminalErrorColor(): androidx.compose.ui.graphics.Color =
    if (isMaterial3Theme()) MaterialTheme.colorScheme.error else MiuixTheme.colorScheme.error

/** 终端等宽字符网格的字阶：M3 用官方 bodySmall + 等宽字族。 */
@Composable
internal fun terminalGridTextStyle(): TextStyle =
    if (isMaterial3Theme()) {
        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    } else {
        MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace)
    }

/** 终端等宽正文块（M3 走官方 Text，MIUI X 走 miuix Text）。 */
@Composable
internal fun TerminalMonoText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = terminalForeground(),
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    val style = terminalGridTextStyle()
    if (isMaterial3Theme()) {
        M3Text(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            softWrap = softWrap,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        MiuixText(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            softWrap = softWrap,
            maxLines = maxLines,
        )
    }
}

/** 终端进行中指示：M3 用官方 CircularProgressIndicator，MIUI X 沿用 miuix 无限进度。 */
@Composable
internal fun TerminalBusyIndicator(size: androidx.compose.ui.unit.Dp) {
    if (isMaterial3Theme()) {
        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(size))
    } else {
        InfiniteProgressIndicator(size = size)
    }
}

/** 终端日志/正文视图：M3 用官方 Text + bodyLarge，MIUI X 沿用 miuix Text。 */
@Composable
internal fun TerminalBodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = terminalForeground(),
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    if (isMaterial3Theme()) {
        M3Text(
            text = text,
            modifier = modifier,
            color = color,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        MiuixText(
            text = text,
            modifier = modifier,
            color = color,
            style = MiuixTheme.textStyles.body1,
            textAlign = textAlign,
            maxLines = maxLines,
        )
    }
}

/**
 * 环境切换标签（Android / Linux）。
 *
 * M3：官方 `SingleChoiceSegmentedButtonRow` + `SegmentedButton`，自带选中语义、涟漪与
 * 官方连接形状（`SegmentedButtonDefaults.itemShape`）。
 */
@Composable
internal fun TerminalEnvironmentTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    if (isMaterial3Theme()) {
        SingleChoiceSegmentedButtonRow(modifier = modifier) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = {
                        TouchHaptics.click(view)
                        onSelect(index)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                ) {
                    M3Text(text = label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    } else {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                MiuixText(
                    text = label,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (index == selectedIndex) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    fontWeight = if (index == selectedIndex) FontWeight.SemiBold else null,
                    modifier = Modifier
                        .clickable(onClick = {
                            TouchHaptics.click(view)
                            onSelect(index)
                        })
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 终端状态栏图标按钮：M3 用官方图标按钮（容器/图标尺寸取 `IconButtonDefaults` 令牌）。 */
@Composable
internal fun TerminalIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    if (isMaterial3Theme()) {
        EtaIconButton(
            icon = icon,
            contentDescription = contentDescription,
            onClick = {
                TouchHaptics.click(view)
                onClick()
            },
            modifier = modifier,
            size = EtaIconSize.XS,
            style = EtaButtonStyle.Standard,
        )
    } else {
        MiuixIconButton(onClick = {
            TouchHaptics.click(view)
            onClick()
        }, modifier = modifier) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 终端状态栏/浮层里的文字按钮。
 *
 * M3：官方 `TextButton`（默认胶囊 + `labelLarge`）；破坏性动作用官方
 * `ButtonDefaults.textButtonColors` 搭配 `errorContainer/onErrorContainer` 角色色。
 */
@Composable
internal fun TerminalTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val view = LocalView.current
    if (isMaterial3Theme()) {
        M3TextButton(
            onClick = {
                TouchHaptics.click(view)
                onClick()
            },
            modifier = modifier,
            enabled = enabled,
            colors = if (destructive) {
                M3ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                M3ButtonDefaults.textButtonColors()
            },
        ) {
            M3Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        MiuixTextButton(
            text = text,
            onClick = {
                TouchHaptics.click(view)
                onClick()
            },
            modifier = modifier,
            enabled = enabled,
            minHeight = 28.dp,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            textStyle = MiuixTheme.textStyles.body2,
            colors = if (destructive) {
                MiuixButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error,
                    textColor = MiuixTheme.colorScheme.onError,
                )
            } else {
                MiuixButtonDefaults.textButtonColorsPrimary()
            },
        )
    }
}

/** 终端入口：块式终端为本体；PTY 可用时状态栏提供控制台模式切换。 */
@Composable
internal fun TerminalEntryScreen(
    terminalStore: UserTerminalStore,
    consoleStore: ConsoleStore,
    onOpenEnvironment: () -> Unit,
) {
    val consoleState by consoleStore.uiState.collectAsState()
    var consoleMode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        terminalStore.refreshLinuxReady()
        consoleStore.refreshLinuxEnvironment()
        consoleStore.probePtySupport()
    }
    when {
        consoleMode && consoleState.ptySupported == true -> ConsoleScreen(
            store = consoleStore,
            terminalStore = terminalStore,
            onExitConsole = { consoleMode = false },
            onOpenEnvironment = onOpenEnvironment,
        )
        else -> UserTerminalScreen(
            store = terminalStore,
            onOpenEnvironment = onOpenEnvironment,
            onOpenConsole = if (consoleState.ptySupported == true) {
                { consoleMode = true }
            } else {
                null
            },
        )
    }
}

/**
 * 控制台：PTY 全屏视图，面向 TUI 与交互式 CLI。
 * 网格渲染按行复用（Line.id + version）；软键盘经隐藏输入框捕获，特殊键由键条补齐。
 */
@Composable
internal fun ConsoleScreen(
    store: ConsoleStore,
    terminalStore: UserTerminalStore,
    onExitConsole: () -> Unit,
    onOpenEnvironment: () -> Unit,
) {
    val state by store.uiState.collectAsState()
    val terminalState by terminalStore.uiState.collectAsState()
    var showTasks by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var ctrlActive by remember { mutableStateOf(false) }
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(terminalRootSurface())
            .imePadding()
            .navigationBarsPadding(),
    ) {
        ConsoleStatusBar(
            environment = state.environment,
            linuxEnvironment = state.linuxEnvironment,
            onSwitchEnvironment = store::switchEnvironment,
            onOpenTasks = {
                terminalStore.refreshDaemonTasks()
                showTasks = true
            },
            onOpenSessions = { showSessions = true },
            onExitConsole = onExitConsole,
        )
        ConsoleGrid(
            state = state,
            store = store,
            focusRequester = focusRequester,
            fieldValue = fieldValue,
            onFieldChange = { newValue ->
                val inserted = diffInserted(fieldValue.text, newValue.text)
                if (inserted.isNotEmpty()) {
                    val bytes = if (ctrlActive && inserted.length == 1 && inserted[0].isLetter()) {
                        ctrlActive = false
                        (inserted[0].lowercaseChar().code and 0x1F).toChar().toString()
                    } else {
                        inserted
                    }
                    store.write(bytes.replace("\n", "\r"))
                }
                val deleted = diffDeleted(fieldValue.text, newValue.text)
                repeat(deleted) { store.write("\u007F") }
                fieldValue = if (newValue.text.length > 64) TextFieldValue("") else newValue
            },
            onOpenEnvironment = onOpenEnvironment,
        )
        ConsoleKeyBar(
            ctrlActive = ctrlActive,
            onCtrl = { ctrlActive = !ctrlActive },
            onKey = { store.write(it) },
        )
    }

    if (showTasks) {
        DaemonTasksDialog(
            tasks = terminalState.daemonTasks,
            onDismiss = { showTasks = false },
            onStop = terminalStore::stopDaemonTask,
            onLoadLogs = terminalStore::daemonLogs,
        )
    }

    if (showSessions) {
        SessionListDialog(
            rows = state.sessions.map { session ->
                SessionDialogRow(
                    id = session.id,
                    environment = session.environment,
                    subtitle = "",
                    active = session.id == state.activeSessionId,
                    running = !session.exited,
                    alive = !session.exited,
                )
            },
            onDismiss = { showSessions = false },
            onSelect = store::switchSession,
            onRestart = store::restartSession,
            onClose = store::closeSession,
            onNew = store::newSession,
        )
    }
}

@Composable
private fun ConsoleStatusBar(
    environment: TerminalEnvironment,
    linuxEnvironment: TerminalEnvironment,
    onSwitchEnvironment: (TerminalEnvironment) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSessions: () -> Unit,
    onExitConsole: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalEnvironmentTabs(
            labels = listOf("Android", linuxEnvironment.label()),
            selectedIndex = if (environment == TerminalEnvironment.ANDROID) 0 else 1,
            onSelect = { index ->
                onSwitchEnvironment(
                    if (index == 0) TerminalEnvironment.ANDROID else linuxEnvironment,
                )
            },
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalIconButton(
                icon = Icons.Rounded.Layers,
                contentDescription = stringResource(R.string.terminal_sessions),
                onClick = onOpenSessions,
            )
            TerminalIconButton(
                icon = Icons.Rounded.Insights,
                contentDescription = stringResource(R.string.terminal_daemon_tasks),
                onClick = onOpenTasks,
            )
            TerminalTextButton(
                text = stringResource(R.string.terminal_block_mode),
                onClick = onExitConsole,
            )
        }
    }
}

@Composable
private fun ColumnScope.ConsoleGrid(
    state: ConsoleUiState,
    store: ConsoleStore,
    focusRequester: FocusRequester,
    fieldValue: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    onOpenEnvironment: () -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val cellStyle = terminalGridTextStyle()
    val defaultColor = terminalForeground()
    val cursorColor = terminalForeground()
    val cursorTextColor = terminalRootSurface()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            // PTY 网格本体：点按只用于把焦点交给隐藏输入框，没有可点击语义，
            // 官���组件都会画涟漪/按压态，落在终端字符流上会造成闪烁，故保留无涟漪的手势。
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() },
    ) {
        val cellSize = remember(cellStyle) { measureCell(textMeasurer, cellStyle) }
        val cols = with(density) { (maxWidth.toPx() / cellSize.first).toInt() }
        val rows = with(density) { (maxHeight.toPx() / cellSize.second).toInt() }
        if (cols > 0 && rows > 0) {
            LaunchedEffect(cols, rows) {
                store.open(state.environment, cols, rows)
            }
        }

        val frame = state.frame
        val listState = rememberLazyListState()
        val atBottom by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
                lastVisible >= layoutInfo.totalItemsCount - 1
            }
        }
        LaunchedEffect(frame) {
            if (atBottom && frame.lines.isNotEmpty()) {
                listState.scrollToItem(frame.lines.lastIndex)
            }
        }
        val cursorLineIndex = frame.lines.size - frame.screenRows + frame.cursorRow
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            itemsIndexed(
                items = frame.lines,
                key = { _, line -> line.id },
                contentType = { _, _ -> "console-line" },
            ) { index, line ->
                val cursorCol = if (state.connected && frame.cursorVisible && index == cursorLineIndex) {
                    frame.cursorCol
                } else {
                    null
                }
                ConsoleLine(
                    line = line,
                    cursorCol = cursorCol,
                    defaultColor = defaultColor,
                    cursorColor = cursorColor,
                    cursorTextColor = cursorTextColor,
                    style = cellStyle,
                )
            }
        }

        BasicTextField(
            value = fieldValue,
            onValueChange = onFieldChange,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
            modifier = Modifier
                .size(1.dp)
                .alpha(0.01f)
                .focusRequester(focusRequester),
        )
        val lifecycleOwner = LocalLifecycleOwner.current
        var shouldAutoFocus by remember { mutableStateOf(true) }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> shouldAutoFocus = false
                    Lifecycle.Event.ON_RESUME -> shouldAutoFocus = true
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(Unit) {
            if (shouldAutoFocus) {
                focusRequester.requestFocus()
            }
        }

        if (state.exited || state.failMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(terminalRootSurface().copy(alpha = 0.92f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                TerminalBodyText(
                    text = state.failMessage ?: stringResource(R.string.terminal_session_closed),
                    modifier = Modifier,
                    color = terminalMutedForeground(),
                    textAlign = TextAlign.Center,
                )
                TerminalTextButton(
                    text = stringResource(R.string.terminal_reconnect),
                    onClick = store::reconnect,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (state.failMessage != null) {
                    TerminalTextButton(
                        text = stringResource(R.string.terminal_open_environment),
                        onClick = onOpenEnvironment,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleLine(
    line: TerminalScreenBuffer.Line,
    cursorCol: Int?,
    defaultColor: Color,
    cursorColor: Color,
    cursorTextColor: Color,
    style: TextStyle,
) {
    val text = remember(line.id, line.version, cursorCol, defaultColor) {
        lineToAnnotated(line, cursorCol, cursorColor, cursorTextColor)
    }
    if (isMaterial3Theme()) {
        M3Text(
            text = text,
            style = style.copy(color = defaultColor),
            softWrap = false,
            maxLines = 1,
        )
    } else {
        MiuixText(
            text = text,
            style = style.copy(color = defaultColor),
            softWrap = false,
            maxLines = 1,
        )
    }
}

/** 网格行 → AnnotatedString：连续同样式单元格合并为一个 span；光标格反色。 */
private fun lineToAnnotated(
    line: TerminalScreenBuffer.Line,
    cursorCol: Int?,
    cursorColor: Color,
    cursorTextColor: Color,
): AnnotatedString {
    val cells = line.snapshot()
    return buildAnnotatedString {
        var i = 0
        while (i < cells.size) {
            val cell = cells[i]
            if (cell.continuation) {
                i++
                continue
            }
            val isCursor = i == cursorCol
            var j = i + 1
            while (j < cells.size &&
                !cells[j].continuation &&
                cells[j].style == cell.style &&
                (j == cursorCol) == isCursor
            ) {
                j++
            }
            val span: SpanStyle = if (isCursor) {
                SpanStyle(background = cursorColor, color = cursorTextColor)
            } else {
                cell.style.toSpanStyle()
            }
            if (isCursor || !cell.style.isPlain) {
                withStyle(span) {
                    for (k in i until j) if (!cells[k].continuation) append(cells[k].text)
                }
            } else {
                for (k in i until j) if (!cells[k].continuation) append(cells[k].text)
            }
            i = j
        }
    }
}

@Composable
private fun ConsoleKeyBar(
    ctrlActive: Boolean,
    onCtrl: () -> Unit,
    onKey: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KeyChip(label = "Esc", modifier = Modifier.weight(1f)) { onKey("\u001B") }
        KeyChip(
            label = "Ctrl",
            active = ctrlActive,
            modifier = Modifier.weight(1f),
            onClick = onCtrl,
        )
        KeyChip(label = "Tab", modifier = Modifier.weight(1f)) { onKey("\t") }
        KeyChip(label = "←", modifier = Modifier.weight(1f)) { onKey("\u001B[D") }
        KeyChip(label = "↑", modifier = Modifier.weight(1f)) { onKey("\u001B[A") }
        KeyChip(label = "↓", modifier = Modifier.weight(1f)) { onKey("\u001B[B") }
        KeyChip(label = "→", modifier = Modifier.weight(1f)) { onKey("\u001B[C") }
    }
}

@Composable
private fun KeyChip(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    if (isMaterial3Theme()) {
        // 官方按钮：激活键用 FilledButton，其余用 FilledTonalButton；
        // 胶囊形状、容器色、字阶与按压动效全部取官方默认值，不覆盖。
        if (active) {
            M3Button(
                onClick = {
                    TouchHaptics.keyboardTap(view)
                    onClick()
                },
                modifier = modifier,
            ) {
                M3Text(text = label, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            FilledTonalButton(
                onClick = {
                    TouchHaptics.keyboardTap(view)
                    onClick()
                },
                modifier = modifier,
            ) {
                M3Text(text = label, style = MaterialTheme.typography.labelLarge)
            }
        }
        return
    }
    Box(
        modifier = modifier
            .background(
                if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = {
                TouchHaptics.keyboardTap(view)
                onClick()
            })
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiuixText(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (active) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            fontWeight = if (active) FontWeight.SemiBold else null,
        )
    }
}

private fun measureCell(textMeasurer: TextMeasurer, style: TextStyle): Pair<Int, Int> {
    val result = textMeasurer.measure("M", style)
    return result.size.width to result.size.height
}

/** 提取新插入的文本；非追加式变化（输入法重组）返回空，由调用方维持现状。 */
private fun diffInserted(old: String, new: String): String =
    if (new.length > old.length && new.startsWith(old)) new.substring(old.length) else ""

/** 提取删除的字符数（仅末尾删除）。 */
private fun diffDeleted(old: String, new: String): Int =
    if (new.length < old.length && old.startsWith(new)) old.length - new.length else 0
