"""Source boundary contracts only; Compose/runtime tests remain necessary."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class SubAgentProfileDialogContractTest(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_draft_form_is_not_an_immediate_editor_form(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for forbidden in ('LocalConversationSubAgentEditor', 'SubAgentProfileRow(',
                          '.updateProfile(', '.saveModel(', '.saveParallelLimit(', '.cycleGptSpeed(', '.add()', '.remove('):
            self.assertNotIn(forbidden, text)
        for required in ('editor.newProfileDraft()', 'editor.changeProfileModel(it, selection)',
                         'profile.withRole(requireNotNull(value))', 'AgentModelPickerProjector.project(',
                         'TtsModelPickerDialog(', 'ThinkingEffortPickerDialog(', 'EtaFormTextField(',
                         'if (draft.supportsTaskTier)', 'if (draft.role == "image_generation")'):
            self.assertIn(required, text)
        self.assertNotIn('OutlinedTextField(', text)

    def test_gpt_speed_edit_is_local_and_keyed_by_current_provider_selection(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for required in ('providers.singleOrNull { it.id == draft.providerId && it.isEnabled }',
                         'speedProvider?.models?.singleOrNull { it.id == draft.modelId && it.isEnabled }',
                         'val canEditSpeed = boundConfig != null && supportsGptSpeedBinding(speedProvider, speedModel)',
                         'if (canEditSpeed) SubAgentSettingRow(stringResource(R.string.sap_gpt_speed)',
                         'gptSpeedDisplayName(draft.gptSpeedForModel())'):
            self.assertIn(required, text)
        edit = text.split('if (canEditSpeed) SubAgentSettingRow(', 1)[1].split('SubAgentSettingRow(stringResource(R.string.sap_role)', 1)[0]
        for required in ('interact {', 'session.edit { profile ->',
                         'profile.providerId != draft.providerId', 'profile.modelId != draft.modelId',
                         'profile.role != draft.role',
                         'profile.copy(gptSpeedByModel = profile.gptSpeedByModel +',
                         'SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId)',
                         'profile.gptSpeedForModel().next()'):
            self.assertIn(required, edit)
        for forbidden in ('editor.', 'boundConfig.model', 'reasoning =', 'tier =', 'Toast', 'scope.launch'):
            self.assertNotIn(forbidden, edit)

    def test_user_dismiss_cannot_abandon_submitting_but_saved_and_owner_invalidation_close(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        self.assertIn('val dismiss = { session.dismiss(); latestDismiss() }', text)
        self.assertIn('val userDismiss = { if (!session.submitting) dismiss() }', text)
        self.assertIn('onDismissRequest = userDismiss', text)
        self.assertIn('SubAgentDraftDialogActions(ready, !session.submitting, userDismiss, onConfirm =', text)
        saved = text.split('if (result is ConversationSubAgentPreferences.WriteResult.Saved &&', 1)[1].split('else if', 1)[0]
        self.assertIn('session.isCurrent(latestEditor, latestEnabled)', saved)
        self.assertIn('dismiss()', saved)
        self.assertNotIn('userDismiss()', saved)
        self.assertNotIn('!session.submitting', saved)
        invalidated = text.split('LaunchedEffect(session, current)', 1)[1].split('if (!current) return', 1)[0]
        self.assertIn('if (!current) { session.dismiss(); latestDismiss() }', invalidated)
        self.assertNotIn('!session.submitting', invalidated)

    def test_every_open_is_owner_application_and_snapshot_bound(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for required in ('val openToken = Any()', 'editor.isDisposed', 'currentEditor !== editor',
                         'currentEditor?.owner != owner', 'current.presetApplicationToken == expectedApplicationToken',
                         'config.detached()', 'expectedProfile ?: editor.newProfileDraft()',
                         'DisposableEffect(session)', 'onDispose { session.dismiss() }',
                         'session.canCommit(latestEditor, latestEnabled, capturedTicket)'):
            self.assertIn(required, text)
        dismiss = text.split('fun dismiss()', 1)[1].split('fun isCurrent', 1)[0]
        self.assertNotIn('repository', dismiss)

    def test_suspend_submission_captures_and_only_saved_closes_and_haptics(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        submission = text.split('// Snapshot every argument before suspension;', 1)[1].split('if (usable && modelPicker)', 1)[0]
        for required in ('val capturedDraft', 'val capturedTicket', 'val capturedExpected',
                         'val capturedApplication', 'val capturedParallel', 'session.submitting = true',
                         'scope.launch', 'commitProfileDraft(', 'WriteResult.Saved',
                         'R.string.sap_not_saved', 'finally { session.submitting = false }'):
            self.assertIn(required, submission)
        self.assertLess(submission.index('val capturedDraft'), submission.index('scope.launch'))
        self.assertLess(submission.index('WriteResult.Saved'), submission.index('TouchHaptics.click(view)'))
        actions = text.split('private fun SubAgentDraftDialogActions(', 1)[1]
        self.assertIn('Arrangement.spacedBy(8.dp)', actions)
        self.assertEqual(actions.count('Modifier.weight(1f)'), 2)
        # 官方 TextButton（左）+ Button（右）的默认配色，不再自定义 textButtonColorsPrimary()。
        self.assertIn('TextButton(', actions)
        self.assertIn('Button(', actions)
        self.assertNotIn('textButtonColorsPrimary', actions)
        self.assertNotIn('colors =', actions)
        self.assertIn('onClick = onConfirm', actions)  # no eager confirmation haptic

    def test_parallel_uses_owner_actual_or_pending_confirmed_memory_and_api_pool(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for required in ('SubAgentParallelModel(it.providerId, it.model)',
                         'expectedProfile != null && !bindingChanged',
                         'if (next != null && !originalBinding) editor.rememberedParallelLimit(draft)',
                         'parallelEdited = restored != null',
                         'restored ?: openedConfig.parallelLimit(next)',
                         'SubAgentParallelLimitChange(model, number, openedConfig.parallelLimit(model))',
                         'if (!parallelEdited) return null', 'bindingChanged = true', 'resetParallel()'):
            self.assertIn(required, text)
        self.assertNotIn('SubAgentParallelModel(draft.providerId, draft.modelId)', text)

    def test_entry_points_keep_compact_card_and_never_preadd(self):
        row = self.source('ui/components/SubAgentProfileRow.kt')
        self.assertNotIn('SubAgentTaskTierButton', row)
        # 紧凑行改由官方 Row + ListItem 承载（原 88dp 高度容器交给 ListItem 的高度策略）。
        compact = row.split('Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically)', 1)[1].split('profileDraft?.let', 1)[0]
        self.assertNotIn('R.string.sap_task_tier', compact)
        self.assertIn('Icon(Icons.Rounded.MoreVert, stringResource(R.string.spr_config_for, profile.name)', row)
        self.assertIn('SubAgentProfileDraftSession.open(editor, profile)', row)
        self.assertIn('onLongClickLabel = stringResource(R.string.spr_adjust_thinking_for, profile.name)', row)
        conversation = self.source('ui/components/ConversationCollaborationDialog.kt')
        hint = conversation.split('Text(stringResource(R.string.ccd_tap_hint)', 1)[1]
        self.assertIn('Icon(Icons.Rounded.Add, stringResource(R.string.sap_add_profile))', hint)
        self.assertIn('SubAgentProfileDraftSession.open(panelEditor)', hint)
        hint_row = conversation.split('Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),', 1)[1].split('{', 1)[0]
        self.assertIn('verticalAlignment = Alignment.CenterVertically', hint_row)
        settings = self.source('ui/SubAgentSettingsScreen.kt')
        self.assertNotIn('editor.add()', settings)
        self.assertIn('SubAgentProfileDraftSession.open(editor)', settings)
        self.assertIn('R.string.action_rename', settings)
        self.assertIn('editor.remove(profile.id)', settings)

    def test_window_dialog_works_without_scaffold_and_scrolls_body_not_actions(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        # M3-only：宿主弹窗走自适应 EtaDialog（官方 AlertDialog），表单与嵌套选择各一份。
        self.assertIn('import io.github.mangi.eta.ui.adaptive.EtaDialog', text)
        self.assertEqual(text.count('EtaDialog(show = true'), 2)  # form + nested choices
        self.assertNotIn('WindowDialog(', text)
        self.assertNotIn('OverlayDialog(', text)
        self.assertNotIn('renderInRootScaffold', text)
        for required in ('profileDialogScrollableBody().verticalScroll(',
                         'SubAgentDraftDialogActions(ready',
                         'CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration())',
                         'WithoutPressRipple'):
            self.assertIn(required, text)

    def test_session_shortcuts_reserve_real_48dp_layout_targets(self):
        row = self.source('ui/components/SubAgentProfileRow.kt')
        # 官方 IconButton 内部已 minimumInteractiveComponentSize()，48dp 触摸目标不再手写。
        self.assertIn('IconButton(enabled = usable, onClick =', row)
        self.assertNotIn('Modifier.size(48.dp)', row)
        dialog = self.source('ui/components/ConversationCollaborationDialog.kt')
        self.assertIn('IconButton(modifier = Modifier.size(48.dp), enabled = panelEnabled', dialog)

    def test_ui_fixture_uses_production_theme_without_overlay_host_workaround(self):
        fixture = (Path(__file__).resolve().parents[1] / 'kotlin/io/github/mangi/eta/ui/components/SubAgentUiFixture.kt').read_text()
        self.assertIn('AgentAppTheme(', fixture)
        self.assertIn('applyInterfaceScale = true', fixture)
        self.assertNotIn('Scaffold(', fixture)
        self.assertNotIn('OverlayHost(', fixture)


if __name__ == '__main__':
    unittest.main()
