"""Static wiring checks only; no device rendering, vibration or Kotlin compilation."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[4]
KOTLIN = ROOT / 'app/src/main/kotlin/io/github/mangi/eta'


def read(path):
    return (KOTLIN / path).read_text(encoding='utf-8')


class VoiceFormStyleHapticsContractTest(unittest.TestCase):
    def test_shared_form_has_rounded_filled_outline_and_floating_label(self):
        field = read('ui/components/EtaFormTextField.kt')
        self.assertIn('OutlinedTextField(', field)
        self.assertIn('internal val EtaInputCornerRadius = 14.dp', field)
        self.assertIn('shape = RoundedCornerShape(EtaInputCornerRadius)', field)
        self.assertIn('lerp(colors.surface, colors.surfaceContainerHigh, 0.35f)', field)
        for name in ('focusedContainerColor', 'unfocusedContainerColor', 'disabledContainerColor'):
            self.assertIn(f'{name} = container', field)
        self.assertIn('unfocusedBorderColor = colors.outline.copy(alpha = 0.30f)', field)
        self.assertIn('label = { Text(hint) }', field)
        self.assertIn('import androidx.compose.material3.Text', field)  # inherit animated label size/color
        self.assertNotIn('useLabelAsPlaceholder', field)
        self.assertIn('contentDescription = hint', field)
        self.assertNotRegex(field, r'Color\(0x')

    def test_chat_shell_matches_form_style_without_replacing_stateful_editor(self):
        chat = read('ui/components/AgentChatInputBar.kt')
        self.assertIn('private val InputContainerShape = RoundedCornerShape(EtaInputCornerRadius)', chat)
        shell = chat.split('inputContainerTopPx = coordinates', 1)[1].split('ChatComposerActionRow(', 1)[0]
        self.assertIn('.clip(InputContainerShape)', shell)
        self.assertIn('.background(etaInputContainerColor())', shell)
        self.assertIn('width = 0.5.dp', shell)
        self.assertIn('outline.copy(alpha = 0.30f)', shell)
        self.assertNotIn('.dropShadow(', shell)
        self.assertIn('BasicTextField(', shell)
        self.assertIn('state = textFieldState', shell)
        self.assertIn('maxHeightInLines = 6', shell)
        self.assertIn('.focusProperties { canFocus = !drawerBlocksIme }', shell)
        self.assertIn('VoiceModeStatusPanel(', shell)

    def test_shared_field_keeps_line_limits_password_and_disabled_state(self):
        field = read('ui/components/EtaFormTextField.kt')
        for name in ('value', 'onValueChange', 'enabled', 'singleLine', 'visualTransformation', 'keyboardOptions'):
            self.assertIn(f'{name} = {name}', field)
        self.assertIn('minLines = if (singleLine) 1 else minLines', field)
        self.assertIn('maxLines = if (singleLine) 1 else maxLines', field)
        self.assertIn('maxLines: Int = Int.MAX_VALUE', field)
        self.assertIn('visualTransformation: VisualTransformation = VisualTransformation.None', field)
        self.assertIn('LocalRippleConfiguration provides null', field)
        self.assertIn('WithoutPressRipple {', field)
        self.assertNotIn('mutableStateOf', field)  # draft ownership stays at callers

    def test_screens_and_question_use_same_shared_field(self):
        names = ('ui/PersonalVoicesScreen.kt', 'ui/VoiceModeSettingsScreen.kt',
                 'ui/VoiceConversationRecognitionSettings.kt', 'ui/DoubaoVoiceSettings.kt',
                 'ui/components/AgentQuestionCard.kt')
        for path in names:
            with self.subTest(path=path):
                source = read(path)
                self.assertIn('EtaFormTextField(', source)
                self.assertNotIn('OutlinedTextField(', source)
        mimo = read('ui/PersonalVoicesScreen.kt')
        self.assertIn('name = it.take(80)', mimo)
        self.assertIn('singleLine = true, enabled = !busy', mimo)
        self.assertIn('sample = it.take(300)', mimo)
        realtime = read('ui/VoiceModeSettingsScreen.kt')
        self.assertIn('minLines = 2', realtime)
        self.assertIn('maxLines = 5', realtime)
        self.assertIn('Prefs.putString(Prefs.Keys.AGENT_VOICE_DOUBAO_INSTRUCTIONS, it)', realtime)
        question = read('ui/components/AgentQuestionCard.kt')
        self.assertIn('hint = label', question)
        self.assertIn('minLines = 1, maxLines = Int.MAX_VALUE', question)
        self.assertIn('otherText = it.take(2000)', question)
        self.assertIn('note = it.take(2000)', question)
        self.assertIn('val editable = !message.submitting', question)
        cloud = read('ui/VoiceConversationRecognitionSettings.kt')
        self.assertIn('visualTransformation = PasswordVisualTransformation()', cloud)
        doubao = read('ui/DoubaoVoiceSettings.kt')
        self.assertIn('visualTransformation = visualTransformation', doubao)

    def test_missing_voice_controls_use_app_haptic_wrappers_once(self):
        for path in ('ui/VoiceModeSettingsScreen.kt', 'ui/VoiceConversationRecognitionSettings.kt'):
            source = read(path)
            self.assertIn('EtaSwitchRow(', source)
            self.assertIn('EtaClickableRow(', source)
            self.assertNotIn('TouchHaptics.click', source)  # wrappers own haptic, dialogs own their own
        wrappers = read('ui/components/HapticPreferences.kt')
        self.assertIn('TouchHaptics.click(view)', wrappers)
        self.assertIn('enabled = enabled', wrappers)
        speech = read('ui/SpeechSettingsScreen.kt')
        # 识别方式就地弹选 + 内联 ASR 连接设置；不再跳 "asr" 子页。
        self.assertIn('EtaSegmentedDropdownRow(', speech)
        self.assertIn('config.asrKey', speech)
        self.assertNotIn('page = "asr"', speech)
        self.assertNotIn('DoubaoVoiceSettings(', speech)
        touch = read('ui/haptics/TouchHaptics.kt')
        self.assertIn('if (!ignoreAppSwitch && !isTouchEnabled()) return false', touch)

    def test_refresh_is_disabled_while_running_and_dialogs_keep_owned_haptics(self):
        realtime = read('ui/VoiceModeSettingsScreen.kt')
        self.assertIn('enabled = !refreshing', realtime)
        self.assertIn('if (!refreshing) scope.launch', realtime)
        picker = read('ui/TtsModelPickerDialog.kt')
        self.assertIn('TouchHaptics.click', picker)
        actions = read('ui/components/MiuixDialogActions.kt')
        self.assertEqual(2, actions.count('TouchHaptics.click(view)'))
        for name in ('onSelected', 'onDismiss'):
            # New screen wiring does not add feedback in already-haptic dialog callbacks.
            for callback in re.finditer(name + r'\s*=\s*\{([^}]+)\}', realtime):
                self.assertNotIn('TouchHaptics', callback.group(1))


if __name__ == '__main__':
    unittest.main()
