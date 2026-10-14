"""Preset UI/source contracts; these do not substitute for running Compose tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class SubAgentPresetUiContractTest(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_settings_shell_owns_repository_and_preset_editor_not_conversation_local(self):
        text = self.source('ui/SubAgentSettingsScreen.kt')
        shell, detail = text.split('private fun SubAgentPresetDetail(', 1)
        self.assertNotIn('LocalConversationSubAgentEditor.current', text)
        for needle in ('ConversationSubAgentPreferences()', 'remember { repositoryFactory() }',
                       'SubAgentConfigKey.Preset(group.id)', 'selectedId == owner.value',
                       'repository.presetExists(owner.value)', 'LocalConversationSubAgentEditor provides editor',
                       'BackHandler(enabled = isCurrentRoute, onBack = detailBack)', 'editor.dispose(); selectedId = null',
                       'DisposableEffect(editor)', 'onDispose { editor.dispose() }', '添加子代理组'):
            self.assertIn(needle, shell)
        for needle in ('添加子代理', 'SubAgentProfileRow(', 'settings = true',
                       'updateProfile(profile.id)', 'editor.remove(profile.id)'):
            self.assertIn(needle, detail)
        # Preset profile editing still writes through the real editor, but neither switch belongs here.
        for forbidden in ('Text("自动委派"', 'setEnabled(', 'setDiagnosticsEnabled', 'diagnosticsEnabled', '子代理诊断日志'):
            self.assertNotIn(forbidden, detail)
        editor = self.source('ui/components/ConversationSubAgentEditor.kt')
        self.assertNotIn('setDiagnosticsEnabled', editor)
        self.assertIn('fun setEnabled(value: Boolean) = update { it.copy(enabled = value) }', editor)
        self.assertNotIn('taskRunning', shell)
        self.assertNotIn('editor.add()', detail)
        self.assertIn('SubAgentProfileDraftSession.open(editor)', detail)
        self.assertIn('SubAgentProfileConfigDialog(session, editor, providers', detail)

    def test_catalog_supports_named_empty_add_rename_delete_and_explicit_retry(self):
        settings = self.source('ui/SubAgentSettingsScreen.kt')
        # 组列表已改为 KernelSU 式分段列表（EtaScaffoldPage + EtaSegmentedColumn + FAB），
        # 共享的 SubAgentPresetCard 只保留给会话协作面板使用。
        for needle in ('repository.addPreset(name.trim())', 'repository.renamePreset(', 'repository.removePreset(',
                       'EtaScaffoldPage(', 'EtaSegmentedColumn(', 'SubAgentPresetDirectoryStatus(directory)'):
            self.assertIn(needle, settings)
        cards = self.source('ui/components/SubAgentPresetCard.kt')
        for needle in ('repository.presets()', 'repository.presetsFlow().collect', 'recoverDurability()',
                       'if (error != null) return@LaunchedEffect', 'TouchHaptics.click(view); directory.retry()'):
            self.assertIn(needle, cards)

    def test_shared_card_is_light_rounded_haptic_and_ripple_free(self):
        cards = self.source('ui/components/SubAgentPresetCard.kt')
        for needle in ('RoundedCornerShape(24.dp)', 'primaryContainer.copy(alpha = 0.38f)',
                       'indication = null', 'TouchHaptics.click(view)', 'Role.Button'):
            self.assertIn(needle, cards)
        for path in ('ui/SubAgentSettingsScreen.kt', 'ui/components/ConversationCollaborationDialog.kt'):
            self.assertIn('WithoutPressRipple', self.source(path))
            self.assertIn('LocalRippleConfiguration provides null', self.source(path))

    def test_legacy_dialog_is_non_writing_and_saved_only_enters_original_panel(self):
        text = self.source('ui/components/ConversationCollaborationDialog.kt')
        for needle in ('current?.appliedPresetId == null', '使用当前配置', '切换子代理组',
                       'val editor = LocalConversationSubAgentEditor.current', 'editor.applyPreset(preset.id)',
                       'result is ConversationSubAgentPreferences.WriteResult.Saved',
                       '子代理组未应用，当前会话配置已保留', 'popup.isCurrent(ticket, popup)',
                       'DisposableEffect(popup)', 'popup.dismiss()'):
            self.assertIn(needle, text)
        self.assertNotIn('SubAgentConfigKey.Preset', text)
        self.assertNotIn('LaunchedEffect', text)  # Opening never auto-applies or replaces the old owner config.
        use_current = text.split('Text("使用当前配置")', 1)[0].rsplit('TextButton(enabled = canChange', 1)[1]
        self.assertNotIn('applyPreset', use_current)
        self.assertNotIn('setEnabled', use_current)

    def test_rows_capture_application_and_dispose_instead_of_trusting_root_state(self):
        dialog = self.source('ui/components/ConversationCollaborationDialog.kt')
        for needle in ('key(editor, current.presetApplicationToken)', 'editor.scoped(current.presetApplicationToken)',
                       'latestOwnerMatches() && !latestTaskRunning && !latestChoosing',
                       'LocalConversationSubAgentEditor provides panelEditor', 'DisposableEffect(scoped)', 'onDispose { scoped.dispose() }'):
            self.assertIn(needle, dialog)
        editor = self.source('ui/components/ConversationSubAgentEditor.kt')
        for needle in ('fun dispose() { disposed = true }', '!disposed && state is SubAgentEditorState.Loaded',
                       'root.enabled && gate() && root.applicationMatches(applicationToken)',
                       'old.presetApplicationToken != observedToken',
                       'repository.applyPreset(owner, id) { enabled && canApply() }'):
            self.assertIn(needle, editor)
        speed = editor.split('suspend fun cycleGptSpeed(', 1)[1].split('fun saveModel(', 1)[0]
        self.assertLess(speed.index('val capturedToken = capturedConfig.presetApplicationToken'), speed.index('providerLookup('))
        self.assertIn('(state as? SubAgentEditorState.Loaded)?.config?.presetApplicationToken != capturedToken', speed)
        self.assertIn('confirmedUpdate(id, binding)', speed)
        self.assertIn('if (config.presetApplicationToken != capturedToken) throw LostOwner()', speed)
        self.assertLess(speed.index('providerLookup('), speed.index('confirmedUpdate(id, binding)'))
        self.assertLess(speed.index('confirmedUpdate(id, binding)'),
                        speed.index('if (config.presetApplicationToken != capturedToken) throw LostOwner()'))

    def test_scoped_panel_observes_and_displays_explicit_error_recovery(self):
        dialog = self.source('ui/components/ConversationCollaborationDialog.kt')
        self.assertIn('val panelEditor = if (!choosing && current != null && editor != null)', dialog)
        panel = dialog.split('val panelState = panelEditor?.observe()', 1)[1]
        for needle in ('val panelConfig = (panelState as? SubAgentEditorState.Loaded)?.config',
                       'val panelEnabled = canChange && panelEditor?.enabled == true && panelConfig != null',
                       'when (panelState)', '本会话配置保存或读取失败', '重试本会话配置',
                       'editor.retry()', 'panelEditor.retry()',
                       '(panelConfig ?: current).profiles.forEach',
                       'SubAgentProfileRow(profile, providers, enabled = panelEnabled,',
                       'SubAgentProfileDraftSession.open(panelEditor)',
                       'SubAgentProfileConfigDialog(session, panelEditor, providers',
                       'Switch(checked = panelConfig?.enabled ?: current.enabled, enabled = panelEnabled'):
            self.assertIn(needle, panel)
        self.assertNotIn('SubAgentProfileRow(profile, providers, enabled = canChange)', panel)

    def test_catalog_actions_use_existing_bottom_sheet_and_do_not_replace_profile_config(self):
        settings = self.source('ui/SubAgentSettingsScreen.kt')
        shell, detail = settings.split('private fun SubAgentPresetDetail(', 1)
        for needle in ('actionsPreset = preset', 'SubAgentPresetActionsSheet(preset',
                       'name = preset.name; rename = preset', 'if (editable) delete = preset',
                       'repository.renamePreset(', 'repository.removePreset(', 'AlertDialog(onDismissRequest = { delete = null }'):
            self.assertIn(needle, shell)
        self.assertNotIn('SubAgentDropdownMenu(', shell)
        self.assertNotIn('DropdownMenuItem(', shell)
        sheet = self.source('ui/components/SubAgentPresetActionsSheet.kt')
        for needle in ('ModalBottomSheet(', 'rememberModalBottomSheetState(skipPartiallyExpanded = true)',
                       'RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)', 'BottomSheetDefaults.DragHandle()',
                       'Icons.Rounded.AccountTree', 'Text(preset.name', 'HorizontalDivider(',
                       'SubAgentPresetActionRow("重命名组", Icons.Rounded.Edit, MaterialTheme.colorScheme.onSurface, onRename)',
                       'SubAgentPresetActionRow("删除组", Icons.Rounded.DeleteOutline, MaterialTheme.colorScheme.error, onDelete)',
                       'verticalScroll(rememberScrollState())', 'heightIn(min = 64.dp)',
                       'TouchHaptics.click(view)', 'indication = null'):
            self.assertIn(needle, sheet)
        for forbidden in ('OverlayDialog(', '复制', 'repository.', 'height(', 'requiredHeight('):
            self.assertNotIn(forbidden, sheet)
        self.assertIn('SubAgentProfileConfigDialog(session, editor, providers', detail)
        row = self.source('ui/components/SubAgentProfileRow.kt')
        for needle in ('Icon(Icons.Rounded.MoreVert, "配置${profile.name}"',
                       'SubAgentProfileDraftSession.open(editor, profile)',
                       'SubAgentProfileConfigDialog(session, editor, providers',
                       'onClickLabel = "选择${profile.name}模型"', 'onLongClickLabel = "调整${profile.name}思考深度"',
                       'TouchHaptics.longPress(view); thinkingPicker = true'):
            self.assertIn(needle, row)
        self.assertNotIn('SubAgentPresetActionsSheet(', row)

    def test_dialog_has_adaptive_body_and_one_state_dependent_left_action_in_fixed_footer(self):
        dialog = self.source('ui/components/ConversationCollaborationDialog.kt')
        body, footer = dialog.split('// Fixed footer:', 1)
        for label in ('使用当前配置', '切换子代理组'):
            self.assertNotIn('Text("' + label + '")', body)
            self.assertEqual(footer.count('Text("' + label + '")'), 1)
        for needle in ('Row(Modifier.fillMaxWidth()', 'heightIn(min = 48.dp)',
                       'verticalAlignment = Alignment.CenterVertically', 'Spacer(Modifier.weight(1f))',
                       'if (choosing && current != null', 'else if (!choosing && panelEditor != null)',
                       'popup.isCurrent(ticket, popup)', 'latestCanChange && latestChoosing && editor.enabled',
                       'panelEnabled && panelEditor.enabled', '!latestChoosing',
                       'Text("完成")', 'TouchHaptics.click(view)'):
            self.assertIn(needle, footer)
        self.assertNotIn('verticalScroll(', footer)
        self.assertNotIn('applyPreset(', footer)
        self.assertNotIn('setEnabled(', footer)
        for needle in ('widthIn(max = 480.dp)', 'heightIn(max = maximumHeight)',
                       '.weight(1f, fill = false)', '.verticalScroll(',
                       'LocalConfiguration.current.screenHeightDp - 48', 'appliedPresetName?.let',
                       'Text("自动委派"', 'panelEditor.setEnabled(it)'):
            self.assertIn(needle, body)
        for forbidden in ('OverlayDialog(', 'requiredHeight(', 'fillMaxHeight('):
            self.assertNotIn(forbidden, dialog)

    def test_small_viewport_alignment_scroll_and_retained_callback_tests_are_present(self):
        tests = Path(__file__).resolve().parents[1] / 'kotlin/io/github/mangi/eta/ui/components'
        collaboration = (tests / 'ConversationCollaborationDialogTest.kt').read_text()
        for needle in ('@Config(qualifiers = "w320dp-h480dp")', 'assertFooterAligned(',
                       'body.bottom <= footer.top', 'left.center.y - done.center.y',
                       'emptyChooserAndEmptyCurrentConfigWrapHeightAndAlignFooterWithoutWrites',
                       'longChooserAndAppliedPanelKeepBothFooterActionsFixedDuringScroll',
                       'retainedFooterCallbacksCannotOperateAReopenedChooserOrReappliedPanel',
                       'getUnclippedBoundsInRoot().let { it.bottom - it.top < 300.dp }'):
            self.assertIn(needle, collaboration)
        settings = (tests / 'SubAgentPresetSettingsTest.kt').read_text()
        # 该测试断言中文资源字符串，需固定中文 locale，否则 Robolectric 默认 en-rUS 会让
        # stringResource 解析成英文而断言失败。
        for needle in ('@Config(qualifiers = "zh-rCN-w320dp-h480dp")',
                       'presetMoreOpensBottomActionsOnSmallScreenWithoutEditingOrCopyAction',
                       'presetDetailHidesGlobalSwitchesAndProfileEditsPreserveAutomaticDelegation',
                       '子代理组操作面板', 'assertFalse("removing the switch must not replace the stored enabled value", saved.enabled)'):
            self.assertIn(needle, settings)

    def test_back_is_gated_by_actual_route_and_presets_cannot_run(self):
        settings = self.source('ui/SubAgentSettingsScreen.kt')
        self.assertIn('isCurrentRoute: Boolean = true', settings)
        self.assertIn('BackHandler(enabled = isCurrentRoute, onBack = detailBack)', settings)
        root = self.source('ui/app/AgentAppRoot.kt')
        self.assertIn('isCurrentRoute = backStack.lastOrNull() == AppRoute.SubAgents', root)
        state = self.source('ui/app/AgentAppState.kt')
        self.assertIn('is SubAgentConfigKey.Preset -> error("预设不能作为会话运行配置")', state)

    def test_parallel_limit_wording_fits_preset_and_conversation_without_layout_change(self):
        text = self.source('ui/components/SubAgentParallelLimitRow.kt')
        self.assertIn('本配置内相同提供商/API 模型共用此上限', text)
        self.assertNotIn('同一会话下', text)


if __name__ == '__main__':
    unittest.main()
