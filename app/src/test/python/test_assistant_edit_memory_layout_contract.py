"""Source boundary only; the assistant edit page layout itself is Compose behavior."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class AssistantEditMemoryLayoutContract(unittest.TestCase):
    def test_memory_editor_is_separated_from_the_switch_card(self):
        editor = (ROOT / 'ui/screens/assistants/AssistantEditScreen.kt').read_text(encoding='utf-8')
        memory = editor.split('item(key = "memory")', 1)[1].split('item(key = "skills")', 1)[0]
        # 开关卡与 MEMORY.md 编辑框是两种不同表面：编辑框不再挤进分段列表的 2dp 接缝，
        # 而是作为独立元素按 M3E 的 8dp 元素间距排在卡片下方。
        self.assertNotIn('surface = false', memory)
        self.assertIn('EtaSegmentedColumn(', memory)
        self.assertIn('modifier = Modifier.padding(bottom = EtaDimens.SpaceSm)', memory)
        self.assertIn('AssistantField(', memory)
        self.assertLess(memory.index('EtaSegmentedColumn('), memory.index('AssistantField('))


if __name__ == '__main__':
    unittest.main()
