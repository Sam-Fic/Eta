"""Source wiring/gating only. Behavioral tests are Kotlin and run in GitHub Actions, not here."""
from pathlib import Path
import re
import unittest
from test_chat_scroll_diagnostics_contract import body as original_body, class_body
from test_agent_chat_viewport_contract import code_only

ROOT = Path(__file__).resolve().parents[4]
UI = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui'


def body(source, name):
    # Existing helper matches simple fun declarations. Normalize generic/extension signatures only.
    source = re.sub(r'\bfun\s+<[^>]+>\s+', 'fun ', source)
    source = re.sub(r'\bfun\s+[A-Za-z_.]+\.' + re.escape(name) + r'(?=\s*\()', 'fun ' + name, source)
    return original_body(source, name)


class FrameSpanCorrelationContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.stream = (UI / 'components/StreamPerformanceDiagnostics.kt').read_text()
        cls.bounded = (UI / 'components/BoundedStreamDiagnostics.kt').read_text()
        cls.helper = (UI / 'components/FrameSpanCorrelation.kt').read_text()
        cls.chat = (UI / 'components/AgentChatBody.kt').read_text()
        cls.markdown = (UI / 'components/ChatMessageItem.kt').read_text()
        cls.modifier = (UI / 'components/StreamDiagnosticModifier.kt').read_text()

    def test_anomalous_capture_does_not_scan_or_construct_spans_in_admission_lock(self):
        capture = body(code_only(self.bounded), 'protectFrame')
        self.assertLess(capture.index('recentSnapshot('), capture.index('.recentForFrame(frame)'))
        self.assertIn('all.take(FRAME_CAPTURE_MAX_SPANS)', capture)
        self.assertIn('if (protectedSize == protectedSpans.size) protectedDropped++', capture)
        recent = class_body(self.bounded, 'DiagnosticRawDetailSnapshot').split('fun recentForFrame', 1)[1].split('fun select()', 1)[0]
        self.assertNotIn('synchronized', recent)
        self.assertIn('slowColumns?.recordsInRange(from, to)', recent)
        self.assertLess(recent.index('if (!mains[slot]'), recent.index('DiagnosticSpanRecord('))
        raw_capture = body(code_only(self.bounded), 'recentSnapshot')
        self.assertNotIn('.copyOf()', raw_capture)
        self.assertIn('endIndex = size', raw_capture)
        self.assertIn('slowColumns.add(', self.bounded)
        self.assertIn('slowColumns.detached()', self.bounded)
        self.assertIn('previousWindow = raw', self.bounded)
        self.assertNotIn('previousWindow:', class_body(code_only(self.bounded), 'DiagnosticRawDetailSnapshot'))

    def test_list_source_uses_full_bounded_capture_and_fails_closed_on_source_window_loss(self):
        capture = body(code_only(self.bounded), 'protectFrame')
        self.assertEqual(capture.count('recentSnapshot('), 1)
        self.assertIn('(current + previous).distinctBy { it.span }', capture)
        self.assertIn('val selected = all.take(FRAME_CAPTURE_MAX_SPANS)', capture)
        self.assertIn('val evidence = DiagnosticFrameEvidence(all,', capture)
        self.assertNotIn('DiagnosticFrameEvidence(selected', capture)
        self.assertIn('selected.forEach { span ->', capture)
        self.assertIn('sourceWindowLoss = pair.first.sourceWindowLoss() || pair.second?.sourceWindowLoss() == true', capture)
        matching = body(code_only(self.helper), 'forFrame')
        before_copy = matching.split('val detached =', 1)[0]
        for guard in ('frame.sourceWindowLoss', 'frame.sourceWindowUnknown',
                      'evidence.sourceWindowLoss', 'evidence.sourceWindowUnknown'):
            self.assertIn(guard, before_copy)
        self.assertIn('val listSpans = evidence.spans.filter', matching)
        self.assertNotIn('.take(', matching)
        self.assertNotIn('FRAME_CAPTURE_MAX_SPANS', matching)
        self.assertIn('if (listSpans.any { (it.attribution?.list ?: 0L) == 0L }) return null', matching)
        self.assertIn('if (matched.size > 1) return null', matching)
        self.assertNotIn('evidence.spans)', self.stream)

    def test_single_observed_capture_is_only_for_budget_admitted_frames_on_existing_worker(self):
        self.assertIn('window.addOnFrameMetricsAvailableListener(listener, handler)', self.stream)
        listener = self.stream.split('val listener = Window.OnFrameMetricsAvailableListener', 1)[1].split(
            'window.addOnFrameMetricsAvailableListener(listener, handler)', 1)[0]
        retained = listener.split('if ((firstDraw || missed || severe) && session.details.reserveFrame(severe)) {', 1)[1].split(
            '// Capture first-draw evidence', 1)[0]
        self.assertEqual(listener.count('session.details.protectFrame(record)'), 1)
        self.assertNotIn('recentForFrame(', listener)
        self.assertNotIn('if (severe) session.observerCosts', listener)
        self.assertLess(retained.index('observerCosts.observe(DiagnosticObserverCosts.Phase.Protect)'),
                        retained.index('session.details.protectFrame(record)'))
        self.assertIn('session.listSamples.forFrame(record, evidence)', retained)
        self.assertIn('sourceWindowLoss = evidence.sourceWindowLoss', retained)
        self.assertIn('sourceWindowUnknown = evidence.sourceWindowUnknown', retained)
        self.assertIn('diagnosticFrameEvidenceIncomplete(frame, detail, snapshot.openSpans)', self.stream)
        self.assertNotIn('protectFrame(', body(code_only(self.stream), 'measure'))

    def test_lookback_omission_and_thread_mapping_saturation_are_emitted(self):
        for field in ('lookbackMatched=${correlation.totalPreceding}',
                      'lookbackEmitted=${correlation.preceding.size}',
                      'lookbackOmitted=${correlation.precedingOmitted}', 'session.threadIds.fields()'):
            self.assertIn(field, self.stream)

    def test_render_and_geometry_are_generation_gated_before_keys_or_snapshot_reads(self):
        for name, expensive in [('rowAttribution', 'session.rowTokens.token'),
                                ('recordListGeometry', 'state.layoutInfo'),
                                ('componentAttribution', 'diagnosticComponentType')]:
            source = body(code_only(self.stream), name)
            self.assertLess(source.index('enabledSession() ?: return'), source.index(expensive))
        overlay = body(code_only(self.stream), 'withRenderAttribution')
        self.assertIn('attribution.session != session.serial', overlay)
        self.assertIn('context.current()?.attribution?.takeIf { it.session == session.serial }', overlay)
        geometry = body(code_only(self.stream), 'recordListGeometry')
        self.assertIn('visibleItemsInfo.take(FRAME_LIST_MAX_ROWS)', geometry)
        for forbidden in ('mutableState', 'launch', 'withFrameNanos', 'post', 'scrollToItem'):
            self.assertNotIn(forbidden, geometry)

    def test_row_and_block_identity_are_wired_into_existing_containers(self):
        self.assertIn('LocalStreamDiagnosticRow provides StreamPerformanceDiagnostics.rowAttribution(', self.chat)
        self.assertIn('diagnosticList, entry.key, timelineDiagnosticRowType(entry)', self.chat)
        for label in ('measure', 'place', 'draw'):
            method = {'place': 'Placement', 'measure': 'Measure', 'draw': 'Draw'}[label]
            self.assertIn(f'.streamDiagnostic{method}("row.{label}", diagnosticRow)', self.chat)
        self.assertIn('StreamPerformanceDiagnostics.recordListGeometry(diagnosticList, scrollState, visibleMessages.size)', self.chat)
        self.assertIn('diagnosticRow, index, node.type.name, node.endOffset - node.startOffset', self.markdown)
        self.assertIn('withRenderAttribution(diagnosticAttribution)', self.markdown)
        self.assertEqual(self.modifier.count('withRenderAttribution(attribution)'), 3)
        self.assertIn('key = { it.key }', self.chat)

    def test_log_reports_incompleteness_and_does_not_call_overlap_the_cause(self):
        for label in ('frameCorrelation', 'spanOverlap', 'frameLookback', 'frameList', 'frameListRow', 'frameMainMessage'):
            self.assertIn('v=2 type=' + label, self.stream)
        for field in ('evidenceIncomplete=', 'protectedBudgetDropped=', 'frameCaptureTruncated=',
                      'previousWindowSpans=', 'rowTokenSaturated=', 'selfUpperBoundNs=', 'ageAtFrameEndNs='):
            self.assertIn(field, self.stream)
        self.assertIn('mainSpanOverlapNotCausality', self.stream)
        self.assertIn('directChildUnionUpperBoundIfMissingChildren', self.stream)
        self.assertIn('zeroMatch=notProofOfNoMainWork', self.stream)
        self.assertIn('relation=precedingNotFrameOverlap', self.stream)

    def test_settings_sections_have_only_static_compiled_identities(self):
        settings = (UI / 'SettingsScreen.kt').read_text()
        helpers = (UI / 'components/ChatFrameDiagnostics.kt').read_text()
        calls = re.findall(r'\.settingsSectionDiagnostics\("([a-z_]+)"\)', settings)
        # 四个 hook 分组合并进系统接管子页后，主页只剩 9 个分组边界标签。
        self.assertEqual(len(calls), 9)
        allowed = body(helpers, 'diagnosticComponentType')
        for section in calls:
            self.assertIn('"' + section + '"', allowed)
        extension = body(helpers, 'settingsSectionDiagnostics')
        self.assertLess(extension.index('!StreamPerformanceDiagnostics.enabled'), extension.index('componentAttribution'))
        self.assertIn('streamDiagnosticMeasure("settings.section.measure", attr)', extension)
        self.assertIn('streamDiagnosticDraw("settings.section.draw", attr)', extension)

    def test_cpu_is_injected_only_in_live_attachment_and_not_a_blocking_diagnosis(self):
        self.assertIn('private val cpuClock: (() -> Long)? = null', self.stream)
        self.assertIn('cpuClock = { Debug.threadCpuTimeNanos() }', self.stream)
        self.assertIn('threadCpuCounterNotBlockedDiagnosis', self.stream)
        self.assertIn('schedstat = { mainThreadSchedstat.sample() }', self.stream)
        self.assertIn('message.schedstatFields()', self.stream)
        self.assertIn('topStage=${message.topStage ?: "none"}', self.stream)
        self.assertIn('beforeFirstNs=${message.beforeFirstNs} afterLastNs=${message.afterLastNs}', self.stream)
        self.assertIn('topStage = topStages[slot]', self.stream)
        bounded = (UI / 'components/BoundedStreamDiagnostics.kt').read_text()
        self.assertIn('schedstat=runQueueSeparateFromBlocked', bounded)
        self.assertIn('fun schedstatFields()', bounded)
        self.assertNotIn('Debug.threadCpuTimeNanos()', class_body(code_only(self.stream), 'MainThreadMessageLog'))
        self.assertNotIn('/proc/self/task', class_body(code_only(self.stream), 'MainThreadMessageLog'))

    def test_all_behavioral_tests_are_in_ci_default_test_source_set(self):
        tests = ROOT / 'app/src/test/kotlin/io/github/mangi/eta/ui/components'
        pure = (tests / 'FrameSpanCorrelationTest.kt').read_text()
        self.assertGreaterEqual(pure.count('@Test'), 10)
        for scenario in ('secondListSourceBeyond512ProtectedSpansCannotBecomeAUniqueSource',
                         'missingAnonymousListTokenBeyond512ProtectedSpansFailsClosed',
                         'untruncatedUniqueListSourceStillMatchesAmongMultipleGeometrySources',
                         'previousWindowSecondSourceBeyond512ProtectedSpansStillRejectsUniqueness',
                         'previousWindowUniqueListSourceStillMatchesWithoutRecapturing',
                         'sourceWindowLossAndUnknownRejectEvenApparentlyUniqueCapturedSourcesAndFallback'):
            self.assertIn('@Test fun ' + scenario + '()', pure)
        modifier = (tests / 'StreamDiagnosticModifierTest.kt').read_text()
        for scenario in ('renderIdentityInheritsEventAndParent', 'staleRenderMetadataCannotCrossForegroundSessions',
                         'changedRowMetadataUpdatesExistingMeasureNode'):
            self.assertIn(scenario, modifier)
        workflow = (ROOT / '.github/workflows/build-debug.yml').read_text()
        runner = (ROOT / '.github/scripts/run_unit_tests.py').read_text()
        self.assertIn('python3 -u .github/scripts/run_unit_tests.py', workflow)
        self.assertIn(':app:testDebugUnitTest', runner)
        self.assertIn(':app:assembleRelease', workflow)


if __name__ == '__main__':
    unittest.main()
