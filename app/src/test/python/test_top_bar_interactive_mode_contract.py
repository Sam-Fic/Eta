"""No-SDK wiring/parameter and strict divider contracts for the restored menu.

Actual attached-node touches, persistence and live setting changes are exercised by
TopBarOverflowMenuInteractiveModeTest; these checks do not substitute for Compose.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta"


def read(path):
    return (SRC / path).read_text(encoding="utf-8")


def balanced(source, start):
    """Extract one balanced Kotlin argument list or block, ignoring string literals."""
    opening = source[start]
    closing = {"(": ")", "{": "}"}[opening]
    depth = 0
    quoted = False
    escaped = False
    for index in range(start, len(source)):
        char = source[index]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
            continue
        if char == '"':
            quoted = True
        elif char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError("Unclosed Kotlin block or call")


def arguments(source, name):
    match = re.search(r"\b" + re.escape(name) + r"\s*\(", source)
    if not match:
        raise AssertionError(f"Missing {name} call")
    return balanced(source, match.end() - 1)


def callback(source, name):
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*\{", source)
    if not match:
        raise AssertionError(f"Missing {name} callback")
    return balanced(source, match.end() - 1)


def compact(source):
    return re.sub(r"\s+", "", source)


class TopBarInteractiveModeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.menu = read("ui/app/TopBarOverflowMenu.kt")
        popup = cls.menu.index("EtaDropdownMenu(")
        cls.content = cls.menu[popup:cls.menu.index("ConversationTokenUsageDialog(", popup)]
        cls.entries = []
        cls.sequence = []
        for match in re.finditer(r"\b(DropdownMenuItem|MenuSectionDivider)\(", cls.content):
            if match.group(1) == "MenuSectionDivider":
                cls.sequence.append("divider")
            else:
                entry = balanced(cls.content, match.end() - 1)
                cls.entries.append(entry)
                # 行文案可以是 Text(stringResource(...))，也可以再带着色参数
                # （如「删除当前会话」的 error 色）；标签只认资源名。
                label = re.search(r"text\s*=\s*\{\s*Text\(\s*stringResource\(R\.string\.(\w+)\)", entry)
                if label:
                    cls.sequence.append(label.group(1))
                elif "text = { Text(kimiWebLabel) }" in entry:
                    cls.sequence.append("kimiWebLabel")
                else:
                    raise AssertionError("Unexpected menu row label")
        interactive = [entry for entry in cls.entries if "R.string.action_interactive_mode" in entry]
        if len(interactive) != 1:
            raise AssertionError("Expected exactly one interactive-mode menu row")
        cls.interactive = interactive[0]

    def test_exact_menu_order_restores_only_the_two_missing_group_lines(self):
        # M3 壳层把「新建对话」独立成顶栏按钮（AgentAppShell 里的 AddComment IconButton），
        # 不再占溢出菜单一行；菜单末尾新增「删除当前会话」分组行。
        self.assertEqual([
            "action_search_history",
            "divider", "action_interactive_mode", "divider",
            "action_open_terminal", "action_open_browser", "action_browse_workspace_files",
            "action_sub_agent_status", "kimiWebLabel", "divider", "action_token_usage",
            "action_compress_conversation", "action_auto_compress_context",
            "divider", "action_delete_current_conversation",
            "divider", "capability_kimi_stop",
        ], self.sequence)
        stop_at = self.content.index("if (canStopKimiWeb)")
        stop = balanced(self.content, self.content.index("{", stop_at))
        self.assertEqual(1, stop.count("MenuSectionDivider()"))
        self.assertIn("R.string.capability_kimi_stop", stop)
        self.assertEqual(4, self.content[:stop_at].count("MenuSectionDivider()"))
        self.assertEqual(5, self.content.count("MenuSectionDivider()"))

    def test_divider_uses_the_existing_helper_and_exact_original_style(self):
        helper_at = self.menu.index("private fun MenuSectionDivider()")
        helper = balanced(self.menu, self.menu.index("{", helper_at))
        # 注释里说明了「保持官方默认」，比对结构时先剔除注释再压缩空白。
        self.assertEqual(compact("""{
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }"""), compact(re.sub(r"//[^\n]*", "", helper)))
        self.assertNotIn("MiuixTheme", helper)
        self.assertNotIn("thickness", helper)  # 官方默认厚度
        self.assertNotIn("color =", helper)  # 官方默认 outlineVariant，不再自绘透明度
        self.assertEqual(1, self.menu.count("HorizontalDivider("))

    def test_menu_observes_the_real_preference_without_initialization_writes(self):
        self.assertIn("val interactiveModePreference = remember { InteractiveModePreference() }", self.menu)
        self.assertIn("val interactiveModeEnabled by rememberInteractiveModeEnabled(interactiveModePreference)", self.menu)
        self.assertEqual(2, self.menu.count("interactiveModePreference.setEnabled("))
        self.assertEqual(2, self.interactive.count("interactiveModePreference.setEnabled("))
        for forbidden in ("Prefs.putBoolean", "AgentTaskSurface.save", "ask_user", "LaunchedEffect", "SideEffect"):
            self.assertNotIn(forbidden, self.menu)

    def test_row_and_switch_are_both_clickable_without_dismissing_the_popup(self):
        self.assertIn('modifier = CompactMenuItemModifier.testTag("top-bar-interactive-mode-row")', self.interactive)
        self.assertIn("contentPadding = CompactMenuItemPadding", self.interactive)
        self.assertIn("imageVector = Icons.Rounded.TouchApp", self.interactive)
        self.assertIn("modifier = Modifier.size(TopBarMenuIconSize)", self.interactive)
        switch = arguments(self.interactive, "Switch")
        self.assertIn("checked = interactiveModeEnabled", switch)
        self.assertIn("interactiveModePreference.setEnabled(enabled)", callback(switch, "onCheckedChange"))
        self.assertIn("onCheckedChange = { enabled ->", switch)
        self.assertIn('Modifier.scale(0.72f).testTag("top-bar-interactive-mode-switch")', switch)
        click = callback(self.interactive, "onClick")
        self.assertIn("interactiveModePreference.setEnabled(!interactiveModeEnabled)", click)
        self.assertNotIn("menuState.dismiss", self.interactive)
        self.assertEqual(2, self.interactive.count("TouchHaptics.click(view)"))
        self.assertIn("private val CompactMenuItemModifier = Modifier.height(40.dp)", self.menu)
        self.assertIn("private val CompactMenuItemPadding = PaddingValues(horizontal = 12.dp)", self.menu)

    def test_preference_cold_read_and_observer_use_only_the_independent_local_boolean(self):
        preference = read("config/InteractiveModePreference.kt")
        state = read("ui/components/InteractiveModePreferenceState.kt")
        self.assertIn("private val preferences: SharedPreferences? = Prefs.localAgentPreferences()", preference)
        self.assertIn('const val PREF_KEY = "interactive_mode"', preference)
        self.assertIn("val enabled: Boolean get() = read(preferences)", preference)
        self.assertIn("fun read(preferences: SharedPreferences? = Prefs.localAgentPreferences()): Boolean", preference)
        self.assertIn("runCatching { preferences?.getBoolean(PREF_KEY, false) ?: false }.getOrDefault(false)", preference)
        self.assertEqual(1, preference.count("getBoolean("))
        self.assertEqual(1, preference.count("putBoolean("))
        setter_at = preference.index("fun setEnabled(")
        setter = balanced(preference, preference.index("{", setter_at))
        self.assertIn("preferences?.edit()?.putBoolean(PREF_KEY, enabled)?.apply()", setter)
        # All write access is confined to an explicit user-requested toggle, not
        # construction, cold reads, migration, or observer registration/refresh.
        self.assertEqual(1, preference.count(".edit()"))
        self.assertNotIn(".edit()", preference[:setter_at])
        for source in (self.menu, preference, state):
            # Comments may describe the intentional absence of migration.
            code = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
            for forbidden in ("AgentTaskSurface", "agent_task_surface", "migrate", "putString", "Prefs.putBoolean"):
                self.assertNotIn(forbidden, code)
        self.assertIn("key == null || key == PREF_KEY", preference)
        self.assertIn("preferences?.registerOnSharedPreferenceChangeListener(listener)", preference)
        self.assertIn("preferences?.unregisterOnSharedPreferenceChangeListener(listener)", preference)
        self.assertIn("closed.compareAndSet(false, true)", preference)
        self.assertIn("if (!closed.get()) onChanged(enabled)", preference)
        self.assertIn("main.removeCallbacks(refresh)", preference)
        self.assertIn("remember(preference) { mutableStateOf(preference.enabled) }", state)
        self.assertIn("DisposableEffect(preference)", state)
        self.assertIn("preference.observe { enabled.value = it }", state)
        self.assertIn("onDispose { observation.close() }", state)

    def test_behavior_tests_cover_location_isolation_real_touches_and_observer_disposal(self):
        tests = ROOT / "app/src/test/kotlin/io/github/mangi/eta"
        preference_test = (tests / "config/InteractiveModePreferenceTest.kt").read_text(encoding="utf-8")
        menu_test = (tests / "ui/app/TopBarOverflowMenuInteractiveModeTest.kt").read_text(encoding="utf-8")
        observer_test = (tests / "ui/components/InteractiveModePreferenceStateTest.kt").read_text(encoding="utf-8")
        for case in (
            "absentIndependentKeyDefaultsFalseForEveryLocationWithoutWritingOrMigrating",
            "existingIndependentBooleanWinsForAllLocationsAndColdReadsAgreeWithObservers",
            "togglingOnAndOffNeverChangesForegroundBackgroundAskOrAMissingLocation",
            "settingsLocationWritesDoNotChangeTheSwitchOrNotifyItsObservers",
            "externalIndependentWritesRemovalAndClearRefreshTheSameColdRead",
            "corruptBooleanAndUnavailableStorageFailClosedWithoutRepairWrites",
            "backgroundNotificationsUseMainAndReadLatestStorageRatherThanCapturedValues",
            "registrationRereadsAndCloseUnregistersOnceSuppressingQueuedAndLateCallbacks",
        ):
            self.assertIn(f"@Test fun {case}()", preference_test)
        for mode in ("FOREGROUND", "BACKGROUND", "ASK"):
            self.assertIn(f"exerciseRealTouches(AgentTaskSurfaceMode.{mode})", menu_test)
        self.assertIn("row().performTouchInput { click(Offset(width * 0.35f, center.y)) }", menu_test)
        self.assertIn("switch().performTouchInput { click() }", menu_test)
        self.assertIn("compose.mainClock.advanceTimeBy(500)", menu_test)
        self.assertIn("assertStored(mode)", menu_test)
        self.assertIn("assertEnabled(true)", menu_test)
        self.assertIn("assertEnabled(false)", menu_test)
        self.assertIn("settingsLocationChangesNeverChangeTheSwitchButIndependentWritesRefreshIt", menu_test)
        self.assertIn("persistedIndependentTrueIsUsedOnFirstCompositionWithoutChangingForeground", menu_test)
        self.assertIn("askLocationDoesNotEnableOrInitializeTheIndependentSwitch", menu_test)
        self.assertIn("rememberInteractiveModeEnabled(selected.value)", observer_test)
        self.assertIn("assertEquals(0, first.listeners.size)", observer_test)
        self.assertIn("assertEquals(0, second.listeners.size)", observer_test)
        self.assertIn("assertEquals(2, second.unregisterCount)", observer_test)

    def test_menu_call_parameters_keep_existing_actions_and_avoid_lifted_toggle_state(self):
        declaration = arguments(self.menu, "TopBarOverflowMenu")
        caller = arguments(read("ui/app/AgentAppShell.kt"), "TopBarOverflowMenu")
        for name in ("onOpenTerminal", "onLaunchKimiWeb", "kimiWebLabel",
                     "canStopKimiWeb", "onStopKimiWeb", "onRefreshKimiWeb", "onOpenBrowser",
                     "onOpenWorkspace", "autoCompressEnabled", "isCompressingContext",
                     "onToggleAutoCompress", "onCompressConversation", "onSearchHistory",
                     "onOpenHistoryHit", "tokenUsage", "subAgentStatuses",
                     "canDeleteCurrentConversation", "onDeleteCurrentConversation"):
            with self.subTest(parameter=name):
                self.assertRegex(declaration, r"\b" + name + r"\s*:")
                self.assertIn(f"{name} = {name}", caller)
        self.assertNotIn("interactiveMode", declaration)
        self.assertNotIn("interactiveMode", caller)
        auto = next(entry for entry in self.entries if "R.string.action_auto_compress_context" in entry)
        self.assertIn("checked = autoCompressEnabled", arguments(auto, "Switch"))
        self.assertIn("onToggleAutoCompress(!autoCompressEnabled)", callback(auto, "onClick"))


if __name__ == "__main__":
    unittest.main()
