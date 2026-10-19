from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/ui'

class RetestBoundariesContract(unittest.TestCase):
    def test_first_service_notification_reuses_only_successful_identity_match(self):
        helper = (ROOT / 'InitialServicePreferenceSnapshot.kt').read_text()
        self.assertIn('firstCallback && service === serviceSnapshot && cached != null', helper)
        self.assertLess(helper.index('firstCallback = false'), helper.index('refresh(service)'))
        self.assertIn('serviceSnapshot = null', helper)
        self.assertIn('valueSnapshot = null', helper)
        settings = (ROOT / 'SettingsScreen.kt').read_text()
        self.assertIn('val service = EtaApp.serviceInstance', settings)
        self.assertIn('val initialPrefs = StreamPerformanceDiagnostics.measure("settings.prefs.initial")', settings)
        self.assertIn('val initialPreferences = remember', settings)
        self.assertIn('InitialServicePreferenceSnapshot(service, initialPrefs)', settings)
        self.assertIn('mutableStateOf(initialPreferences.initialValueForUi())', settings)
        self.assertNotIn('initialServiceAndPrefs', settings)
        self.assertIn('prefs = initialPreferences.resolve(service)', settings)
        listener = settings.split('val listener = object : EtaApp.ServiceStateListener', 1)[1].split('WithoutPressRipple {', 1)[0]
        chain = ['initialPreferences.resolve(service)', 'enhancementHistory.captureConnected(connected)', 'Prefs.reconcileAgentPreferences(service)', 'RuntimeConfigRepository.ensureDefaults(service)', 'EtaApp.addServiceStateListener(listener, notifyImmediately = true)', 'EtaApp.removeServiceStateListener(listener)']
        self.assertEqual(sorted(listener.index(x) for x in chain), [listener.index(x) for x in chain])

    def test_user_prompt_parse_is_in_the_existing_content_keyed_cache(self):
        source = (ROOT / 'components/ChatMessageItem.kt').read_text()
        user = source.split('private fun UserMessageBubble(', 1)[1].split('\n@Composable', 1)[0]
        self.assertIn('val visiblePrompt = remember(message.content)', user)
        self.assertIn('StreamPerformanceDiagnostics.measure("render.userPrompt.parse")', user)
        self.assertEqual(user.count('AgentFileReferencePromptCodec.parse(message.content)'), 1)
        self.assertIn('text = visiblePrompt.request', user)
        # M3-only：用户气泡正文取官方 bodyLarge / onSurface（miuix 分支已删除）。
        self.assertIn('MaterialTheme.typography.bodyLarge', user)
        self.assertIn('MaterialTheme.colorScheme.onSurface', user)
        self.assertNotIn('MiuixTheme', user)
        self.assertIn('val diagnosticRow = LocalStreamDiagnosticRow.current', user)
        for label in ['render.userBubble.measure', 'render.userText.measure']:
            self.assertIn(f'.streamDiagnosticMeasure("{label}", diagnosticRow)', user)
        for label in ['render.userBubble.draw', 'render.userText.draw']:
            self.assertIn(f'.streamDiagnosticDraw("{label}", diagnosticRow)', user)
        self.assertNotIn('TextLayoutResult', user)
        self.assertNotIn('maxLines =', user)

    def test_all_new_boundary_names_are_registered_fixed_labels(self):
        labels = (ROOT / 'components/BoundedStreamDiagnostics.kt').read_text()
        settings = (ROOT / 'SettingsScreen.kt').read_text()
        user = (ROOT / 'components/ChatMessageItem.kt').read_text()
        for label in ['settings.prefs.initial', 'settings.prefs.refresh', 'settings.prefs.capture', 'settings.prefs.reconcile', 'settings.service.subscribe', 'render.userPrompt.parse', 'render.userBubble.compose', 'render.userBubble.measure', 'render.userBubble.draw', 'render.userText.measure', 'render.userText.draw']:
            self.assertIn(f'"{label}"', labels)
            self.assertIn(f'"{label}"', settings + user)
