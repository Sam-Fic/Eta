"""Source contract: shared vision/title settings text must not inherit black.

两个说明文本现在都走自适应 `EtaText`，颜色不再逐处手写，契约落在
「EtaText 的 M3 语义默认前景」与「两处调用不覆写颜色」上。
"""
from pathlib import Path
import re
import unittest


class ModelFeatureSettingsColorsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ui = Path(__file__).resolve().parents[3] / "src/main/kotlin/io/github/mangi/eta/ui"
        cls.source = (ui / "ModelFeatureSettingsScreen.kt").read_text(encoding="utf-8")
        cls.basics = (ui / "adaptive/EtaBasics.kt").read_text(encoding="utf-8")

    def eta_text_call_for(self, resource):
        # Bound assertions to one call so a correctly colored sibling cannot hide a
        # regression in the description itself.
        for match in re.finditer(r"\bEtaText\(", self.source):
            depth = 1
            for end in range(match.end(), len(self.source)):
                char = self.source[end]
                if char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
                if depth == 0:
                    call = self.source[match.start():end + 1]
                    if f"R.string.{resource}" in call:
                        return call
                    break
        self.fail(f"No EtaText call found for {resource}")

    def test_page_descriptions_inherit_the_shared_theme_foreground(self):
        for resource in ("vision_feature_description", "title_feature_description"):
            with self.subTest(resource=resource):
                call = self.eta_text_call_for(resource)
                # 不逐处覆写颜色：默认前景来自 EtaText 的 M3 语义色。
                self.assertNotRegex(call, r"\bcolor\s*=")
        body_arm = self.basics.split("val defaultColor = when (effective) {", 1)[1].split("}", 1)[0]
        self.assertIn("EtaTextStyle.Title, EtaTextStyle.Body -> MaterialTheme.colorScheme.onSurface", body_arm)
        self.assertNotIn("Color.Black", self.source)
        self.assertNotIn("Color(0x", self.source)


if __name__ == "__main__":
    unittest.main()
