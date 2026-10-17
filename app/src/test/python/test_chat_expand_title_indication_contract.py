"""展开标题栏统一走共享的 Material 3 披露标题行。

Source contracts, not a runtime rendering or performance test. 三个展开标题栏
（工作过程、思考、工具）现在都复用 `WorkCardDisclosureHeader`；整行命中区、涟漪、
最小行高与语义由官方 `ListItem(onClick = ...)` 提供，miuix 时代「手动 clickable
且 indication = null」的契约不再成立。
"""
from pathlib import Path
import re
import unittest

from test_agent_chat_viewport_contract import balanced_end, code_only


ROOT = Path(__file__).resolve().parents[3]
CHAT = ROOT / "src/main/kotlin/io/github/mangi/eta/ui/components/ChatMessageItem.kt"
HEADER = ROOT / "src/main/kotlin/io/github/mangi/eta/ui/components/AgentWorkProcessCard.kt"


def function_body(source, name):
    match = re.search(rf"\bfun\s+{re.escape(name)}\s*\(", source)
    if match is None:
        raise AssertionError(f"Missing function {name}")
    start = source.index("(", match.start())
    end = balanced_end(source, start, "(", ")")
    start = source.index("{", end)
    return source[start + 1:balanced_end(source, start, "{", "}")]


def call_args(source, name):
    match = re.search(rf"\b{re.escape(name)}\s*\(", source)
    if match is None:
        raise AssertionError(f"Missing call {name}")
    opening = source.index("(", match.start())
    end = balanced_end(source, opening, "(", ")")
    return match.start(), end + 1, source[opening + 1:end]


def clickable_calls(source):
    for match in re.finditer(r"\.clickable\s*\(", source):
        opening = source.index("(", match.start())
        end = balanced_end(source, opening, "(", ")")
        yield match.start(), end + 1, source[opening + 1:end]


class ChatExpandTitleIndicationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = code_only(CHAT.read_text(encoding="utf-8"))
        cls.header = code_only(HEADER.read_text(encoding="utf-8"))

    def header_call(self, name):
        body = function_body(self.source, name)
        calls = re.findall(r"\bWorkCardDisclosureHeader\s*\(", body)
        self.assertEqual(len(calls), 1, f"Expected exactly one shared header call in {name}")
        start, end, args = call_args(body, "WorkCardDisclosureHeader")
        self.assertRegex(args, r"\bonToggle\s*=")
        # 官方 ListItem 自带涟漪与语义，这里不应再自己挂一个压制反馈的点击区。
        self.assertNotRegex(args, r"\bindication\s*=\s*null\b")
        self.assertNotIn(".clickable", args)
        return body, start, end, args

    def toggle_body(self, body):
        match = re.search(r"val toggle:\s*\(\)\s*->\s*Unit\s*=\s*\{", body)
        self.assertIsNotNone(match, "Missing toggle lambda")
        start = body.index("{", match.end() - 1)
        return body[start + 1:balanced_end(body, start, "{", "}")]

    def test_work_header_keeps_on_toggle(self):
        _, _, _, args = self.header_call("AgentWorkProcessHeader")
        self.assertRegex(args, r"\bonToggle\s*=\s*onToggle\b")

    def test_thinking_header_keeps_expansion_callback(self):
        body, _, _, args = self.header_call("ThinkingRow")
        self.assertRegex(args, r"\bonToggle\s*=\s*toggle\b")
        callback = self.toggle_body(body)
        self.assertIn("anchorBottom = expansionHoldsBottom()", callback)
        self.assertIn("manuallyExpanded = true", callback)
        self.assertIn("expandedByTap = !expanded", callback)
        self.assertIn("expanded = !expanded", callback)
        self.assertIn("StreamPerformanceDiagnostics.markToggle", callback)

    def test_toggle_stays_confined_to_the_shared_title_row(self):
        # 折叠/展开只由官方 ListItem 标题行触发，展开正文不再叠加第二个点击区；
        # 与提问卡片、工具卡片保持一致。
        body, call_start, _, _ = self.header_call("ThinkingRow")
        self.assertNotIn(".clickable", body)
        self.assertNotIn("pointerInput", body)
        visible = body.index("AnimatedVisibility(")
        self.assertLess(call_start, visible, "The header row must sit above the expanded body")

    def test_tool_header_keeps_expansion_callback(self):
        body, _, _, args = self.header_call("ToolActivityInline")
        self.assertRegex(args, r"\bonToggle\s*=\s*if\s*\(\s*hasDetails\s*\)\s*toggle\s*else\s*null\b")
        callback = self.toggle_body(body)
        self.assertIn("anchorBottom = expansionHoldsBottom()", callback)
        self.assertIn("expandedByTap = !isExpanded", callback)
        self.assertIn("isExpanded = !isExpanded", callback)
        self.assertIn("StreamPerformanceDiagnostics.markToggle", callback)

    def test_tool_row_is_only_toggleable_with_details(self):
        body, _, _, args = self.header_call("ToolActivityInline")
        # 没有详情时整行不可点击（onToggle = null），与展开体的 hasDetails 守卫同源。
        self.assertRegex(args, r"\bonToggle\s*=\s*if\s*\(\s*hasDetails\s*\)\s*toggle\s*else\s*null\b")
        self.assertIn("visible = isExpanded && hasDetails", body)

    def test_shared_header_uses_official_list_item_onclick(self):
        self.assertIn("ListItem(", self.header)
        self.assertIn("onClick = onToggle,", self.header)
        self.assertIn("onToggle: (() -> Unit)?", self.header)
        declaration = self.header.split("internal fun WorkCardDisclosureHeader(", 1)[1]
        declaration = declaration.split(") {", 1)[0]
        self.assertNotIn("indication", declaration)
        self.assertNotIn("disabled", declaration)

    def test_no_click_indication_suppression_is_left_in_the_chat_item(self):
        suppressed = [args for _, _, args in clickable_calls(self.source)
                      if re.search(r"\bindication\s*=\s*null\b", args)]
        self.assertEqual([], suppressed)
        self.assertNotIn("LocalIndication", self.source)
        self.assertNotIn("WithoutPressRipple", self.source)
        self.assertNotIn("LocalRippleConfiguration", self.source)


if __name__ == "__main__":
    unittest.main()
