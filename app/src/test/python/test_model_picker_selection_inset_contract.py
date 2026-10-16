"""Source guards only; no Kotlin build, pixel capture or Android UI execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
UI = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui'


class ModelPickerSegmentedContractTest(unittest.TestCase):
    def setUp(self):
        self.source = (UI / 'TtsModelPickerDialog.kt').read_text(encoding='utf-8')

    def test_dialog_uses_segmented_radio_rows_grouped_by_provider(self):
        dialog = self.source.split('internal fun TtsModelPickerDialog', 1)[1].split('private fun ModelPickerRadioRow', 1)[0]
        # 按提供商分组平铺：提供商作分段列标题，模型为组内分段单选行。
        self.assertIn('EtaSegmentedColumn(', dialog)
        self.assertIn('title = group.providerName', dialog)
        self.assertIn('item(key = "${model.providerId}/${model.id}")', dialog)
        self.assertIn('EtaSegmentedRadioRow(', self.source)
        # 去掉折叠：不再出现展开/收起箭头或 expanded 状态。
        self.assertNotIn('ExpandMore', dialog)
        self.assertNotIn('ExpandLess', dialog)
        self.assertNotIn('expanded', dialog)

    def test_selection_is_pending_until_confirm_and_cancel_dismisses(self):
        dialog = self.source.split('internal fun TtsModelPickerDialog', 1)[1].split('private fun ModelPickerRadioRow', 1)[0]
        self.assertIn('var pendingModelId by remember', dialog)
        self.assertIn('onModelSelected(pendingProviderId.orEmpty(), pendingModelId.orEmpty())', dialog)
        self.assertIn('onClearSelection?.invoke()', dialog)
        self.assertIn('TextButton(onClick = confirm, enabled = confirmEnabled)', dialog)
        self.assertIn('TextButton(onClick = onDismiss)', dialog)
        self.assertIn('R.string.action_confirm', dialog)
        self.assertIn('R.string.action_cancel', dialog)
        # 点击模型只暂存待确认状态，不直接提交。
        self.assertNotIn('onClick = { TouchHaptics.click(view); onModelSelected', dialog)

    def test_actual_subagent_entry_enables_highlight(self):
        entry = (UI / 'components/SubAgentProfileRow.kt').read_text(encoding='utf-8')
        picker = entry.split('if (usable && modelPicker)', 1)[1].split('if (usable && thinkingPicker', 1)[0]
        self.assertIn('TtsModelPickerDialog(models', picker)
        self.assertIn('highlightSelection = true', picker)


if __name__ == '__main__':
    unittest.main()
