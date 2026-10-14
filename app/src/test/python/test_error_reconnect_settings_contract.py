import pathlib
import unittest
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[3]
UI = ROOT / "src/main/kotlin/io/github/mangi/eta/ui"
RES = ROOT / "src/main/res"


class ErrorReconnectSettingsContractTest(unittest.TestCase):
    def test_entry_is_directly_below_title_model_and_opens_its_own_page(self):
        settings = (UI / "SettingsScreen.kt").read_text()
        title_entry = settings.index("onNavigate(AppRoute.TitleModel)")
        reconnect_entry = settings.index("onNavigate(AppRoute.ErrorReconnectSettings)")
        between = settings[title_entry:reconnect_entry]
        self.assertEqual(1, between.count("ArrowPreference("))
        self.assertIn("R.string.error_reconnect_title", between)
        self.assertNotIn("summary =", between)
        root = (UI / "app/AgentAppRoot.kt").read_text()
        reconnect = root.split("entry<AppRoute.ErrorReconnectSettings>", 1)[1].split("\n            entry<", 1)[0]
        self.assertIn("swipeDismiss = swipeDismiss", reconnect)
        self.assertIn("ErrorReconnectSettingsScreen(onBack = ::popRoute)", reconnect)
        self.assertNotIn("ModelFeatureSettingsScreen", reconnect)

    def test_page_is_global_accessible_single_choice_and_persisted(self):
        page = (UI / "ErrorReconnectSettingsScreen.kt").read_text()
        for required in (
            "EtaScaffoldPage(",
            "SettingsDataStore.errorReconnectPolicyFlow()",
            "SettingsDataStore.setErrorReconnectPolicy(selected)",
            "val policy = loadedPolicy ?: ErrorReconnectPolicy.DEFAULT",
            "collectAsState(initial = null)",
            "enabled = loadedPolicy != null && !saving",
            "ErrorReconnectPolicy.entries.forEach",
            "selectableGroup()",
            ".selectable(",
            "role = Role.RadioButton",
            "onClick = null",
            "EtaSegmentedRadioRow(",
            "R.string.error_reconnect_duration",
        ):
            self.assertIn(required, page)
        for forbidden in ("ModelFeature", "ProviderRepository", "ModelPicker", "selectedModelId", "selectedProviderId"):
            self.assertNotIn(forbidden, page)
        for name in ("none", "window_30s", "window_1m", "window_5m", "continuous"):
            self.assertEqual(1, page.count("R.string.error_reconnect_" + name))
        for hint in ("none", "30s", "1m", "5m", "continuous"):
            self.assertEqual(1, page.count("R.string.error_reconnect_hint_" + hint))

    def test_page_does_not_show_the_removed_top_description(self):
        page = (UI / "ErrorReconnectSettingsScreen.kt").read_text()
        self.assertNotIn("error_reconnect_description", page)
        self.assertIn('item(key = "error_reconnect_policy")', page)

    def test_reconnect_marker_has_equal_lines_and_an_unweighted_center(self):
        divider = (UI / "components/ErrorReconnectDivider.kt").read_text()
        m3_row = divider.split("    if (isMaterial3Theme())", 1)[1].split("    } else {", 1)[0]
        self.assertIn("modifier.fillMaxWidth()", m3_row)
        self.assertIn("padding(horizontal = 16.dp, vertical = 8.dp)", m3_row)
        self.assertEqual(2, m3_row.count("HorizontalDivider(modifier = Modifier.weight(1f))"))
        center = m3_row.split("            Surface(", 1)[1].split("            HorizontalDivider(", 1)[0]
        self.assertNotIn(".weight(", center)
        self.assertIn("textAlign = TextAlign.Center", center)
        self.assertIn("onClick = {", center)
        miuix_row = divider.split("    } else {", 1)[1].split("    if (showDetails)", 1)[0]
        self.assertEqual(2, miuix_row.count("Box(Modifier.weight(1f).height(0.5.dp).background(lineColor))"))
        self.assertIn("textAlign = TextAlign.Center", miuix_row)
        self.assertIn(".clickable", miuix_row)
        self.assertIn("ContextCompactedSummarySheet(", divider)

    def test_english_simplified_and_traditional_resources_match(self):
        keys = {
            "error_reconnect_title", "error_reconnect_description", "error_reconnect_duration",
            "error_reconnect_none", "error_reconnect_window_30s", "error_reconnect_window_1m",
            "error_reconnect_window_5m", "error_reconnect_continuous", "error_reconnect_save_failed",
            "error_reconnect_hint_none", "error_reconnect_hint_30s", "error_reconnect_hint_1m",
            "error_reconnect_hint_5m", "error_reconnect_hint_continuous",
            "reconnect_running", "reconnect_succeeded", "reconnect_failed", "reconnect_stopped",
            "reconnect_error_title", "reconnect_elapsed_format",
        }
        strings = {}
        for locale in ("values", "values-b+zh+Hans", "values-b+zh+Hant"):
            elements = ET.parse(RES / locale / "strings.xml").getroot().findall("string")
            names = [element.attrib["name"] for element in elements]
            strings[locale] = {element.attrib["name"]: "".join(element.itertext()) for element in elements}
            for key in keys:
                self.assertEqual(1, names.count(key), f"{locale}: {key}")
                self.assertTrue(strings[locale][key])
            for key in ("reconnect_running", "reconnect_elapsed_format"):
                self.assertEqual(1, strings[locale][key].count("%1$s"))
        self.assertEqual(
            "出错后自动重试，直到成功或超时。所有模型通用。",
            strings["values-b+zh+Hans"]["error_reconnect_description"],
        )
        self.assertEqual("总重试时长", strings["values-b+zh+Hans"]["error_reconnect_duration"])


if __name__ == "__main__":
    unittest.main()
