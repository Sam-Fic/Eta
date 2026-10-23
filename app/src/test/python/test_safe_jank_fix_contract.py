from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[4]
MAIN = ROOT / 'app/src/main/kotlin/io/github/mangi/eta'

class SafeJankFixContract(unittest.TestCase):
    def test_only_real_scaffold_boundary_filters_unused_budget_history(self):
        body = (MAIN/'ui/components/AgentChatBody.kt').read_text()
        scaffold = body[body.index('internal fun AgentChatScaffold('):body.index('private fun AgentChatBottomBar(')]
        self.assertEqual(1, scaffold.count('history = historyForContextSendBudget('))
        gate = (MAIN/'ui/components/ContextSendBudgetGate.kt').read_text()
        self.assertIn('if (measuredContextTokens != null && !autoCompressEnabled) true else false', gate)
        self.assertEqual(2, gate.count('contextSendBudgetRequired(measuredContextTokens, autoCompressEnabled)'))
        self.assertIn(')) history else emptyList()', gate)
        test = (ROOT/'app/src/test/kotlin/io/github/mangi/eta/ui/components/AgentChatComposerProductionRegressionTest.kt').read_text()
        self.assertIn('AgentChatScaffold(', test)
        self.assertNotIn('historyForContextSendBudget(', test)
        self.assertIn('Assert.assertTrue(takeCount() > 0)', test)

    def test_real_preference_owner_keeps_sync_lifecycle_and_failed_commit_retry(self):
        prefs = (MAIN/'config/Prefs.kt').read_text()
        self.assertIn('private val agentPreferenceReconciler = AgentPreferenceReconciler(', prefs)
        self.assertIn('agentPreferenceReconciler.reconcile(requireNotNull(service), local, remotePreferences)', prefs)
        bridge = (MAIN/'config/AgentPreferenceReconciler.kt').read_text()
        self.assertIn('owner.get() !== serviceIdentity || preferences.get() !== remote', bridge)
        self.assertLess(bridge.index('writes.keys.forEach(confirmed::remove)'), bridge.index('runCatching { remoteEditor.commit() }'))
        self.assertIn('confirmed.putAll(writes)', bridge)
        self.assertNotIn('Dispatchers', bridge)
        self.assertNotIn('launch {', bridge)
        screen = (MAIN/'ui/SettingsScreen.kt').read_text()
        self.assertIn('Prefs.reconcileAgentPreferences(service)', screen)
        self.assertIn('EtaApp.addServiceStateListener(listener, notifyImmediately = true)', screen)
        self.assertIn('EtaApp.removeServiceStateListener(listener)', screen)

    def test_terminal_allocation_fix_preserves_existing_fallback_and_live_probe_registry(self):
        projector = (MAIN/'ui/app/AgentRunMessageProjector.kt').read_text()
        self.assertEqual(2, projector.count('messages.mapPreservingSnapshot { message ->'))
        helper = (MAIN/'ui/model/AgentIncrementalList.kt').read_text()
        self.assertIn('.replacing(changedIndex, changedValue as T).withoutReplacementHint()', helper)
        self.assertIn('if (changedIndex < 0) return this', helper)
        self.assertNotIn('@Stable', helper)
        self.assertNotIn('@Immutable', helper)
        cache = (MAIN/'ui/components/AgentTimelineProjectionCache.kt').read_text()
        self.assertIn('old.isStreaming && current.isStreaming', cache)
        for filename in ('AgentChatInputBar.kt', 'BoundedStreamDiagnostics.kt'):
            self.assertIn('"chat.input.compose"', (MAIN/'ui/components'/filename).read_text())

    def test_reveal_clock_resumes_from_the_pending_block(self):
        reveal = (MAIN/'ui/components/SmoothTextReveal.kt').read_text()
        finder = reveal.split('private fun firstPendingRecord', 1)[1].split('private fun notePending', 1)[0]
        self.assertIn('records.tailMap(cursor)', finder)
        self.assertNotIn('records.values.firstOrNull', finder)
        clock = reveal.split('suspend fun runFrameClock', 1)[1].split('private fun updateRecord', 1)[0]
        # 到达即显现：帧时钟直接把进度追平到目标，不再经过打字机速度排队。
        self.assertIn('record.progress = record.targetCount', clock)
        self.assertNotIn('advanceSmoothReveal(', clock)
        self.assertEqual(clock.count('firstPendingRecord()'), 2)
