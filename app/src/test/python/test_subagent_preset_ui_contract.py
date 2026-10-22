"""Preset UI/source contracts; these do not substitute for running Compose tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class SubAgentPresetUiContractTest(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_list_shell_owns_catalog_while_detail_screen_owns_the_preset_editor(self):
        shell = self.source('ui/SubAgentSettingsScreen.kt')
        detail = self.source('ui/SubAgentPresetDetailScreen.kt')
        self.assertNotIn('LocalConversationSubAgentEditor.current', shell)
        for needle in ('ConversationSubAgentPreferences()', 'remember { repositoryFactory() }',
                       'rememberSubAgentPresetDirectory(repository)', 'onOpenPreset(preset.id)'):
            self.assertIn(needle, shell)
        # 详情是独立路由：列表页不再自己持有预设 editor，也不再靠 selectedId/BackHandler 内部切换。
        self.assertNotIn('ConversationSubAgentEditor(', shell)
        self.assertNotIn('BackHandler(', shell)
        self.assertNotIn('selectedId', shell)
        for needle in ('SubAgentConfigKey.Preset(preset.id)', 'ConversationSubAgentEditor(owner, repository)',
                       'presetExists(owner.value)', 'LocalConversationSubAgentEditor provides editor',
                       'DisposableEffect(editor)', 'onDispose { editor.dispose() }'):
            self.assertIn(needle, detail)
        for needle in ('R.string.sap_add_profile', 'SubAgentProfileRow(', 'settings = true',
                       'header = {', 'updateProfile(profile.id)', 'editor.remove(profile.id)'):
            self.assertIn(needle, detail)
        # Preset profile editing still writes through the real editor, but neither switch belongs here.
        for forbidden in ('R.string.ccd_auto_delegation', 'setEnabled(', 'setDiagnosticsEnabled', 'diagnosticsEnabled', '子代理诊断日志'):
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

    def test_shared_card_is_an_official_container_with_shared_ripple_suppression(self):
        cards = self.source('ui/components/SubAgentPresetCard.kt')
        # 官方填充 Card 承担形状/容器色，行交给官方可交互 ListItem（命中区、涟漪、Role 语义）。
        for needle in ('Card(', 'CardDefaults.cardColors(',
                       'containerColor = MaterialTheme.colorScheme.primaryContainer',
                       'ListItem(', 'onClick = { TouchHaptics.click(view); onClick() }'):
            self.assertIn(needle, cards)
        # 不再手写圆角/叠透明度，也不再逐处把指示器置空（比对前先剔除注释）。
        code = '\n'.join(line.split('//')[0] for line in cards.splitlines())
        for gone in ('RoundedCornerShape', 'primaryContainer.copy(alpha', 'indication = null'):
            self.assertNotIn(gone, code)
        for path in ('ui/SubAgentSettingsScreen.kt', 'ui/components/ConversationCollaborationDialog.kt'):
            self.assertIn('WithoutPressRipple', self.source(path))
            self.assertIn('LocalRippleConfiguration provides etaRippleConfiguration()', self.source(path))
            self.assertNotIn('LocalRippleConfiguration provides null', self.source(path))

    def test_legacy_dialog_is_non_writing_and_saved_only_enters_original_panel(self):
        text = self.source('ui/components/ConversationCollaborationDialog.kt')
        for needle in ('current?.appliedPresetId == null', 'R.string.ccd_use_current_config',
                       'R.string.ccd_switch_group',
                       'val editor = LocalConversationSubAgentEditor.current', 'editor.applyPreset(preset.id)',
                       'result is ConversationSubAgentPreferences.WriteResult.Saved',
                       'R.string.ccd_apply_failed', 'popup.isCurrent(ticket, popup)',
                       'DisposableEffect(popup)', 'popup.dismiss()'):
            self.assertIn(needle, text)
        self.assertNotIn('SubAgentConfigKey.Preset', text)
        self.assertNotIn('LaunchedEffect', text)  # Opening never auto-applies or replaces the old owner config.
        use_current = text.split('Text(stringResource(R.string.ccd_use_current_config))', 1)[0].rsplit('TextButton(enabled = canChange', 1)[1]
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
                       'when (panelState)', 'R.string.ccd_panel_config_failed', 'R.string.ccd_retry_panel_config',
                       'editor.retry()', 'panelEditor.retry()',
                       '(panelConfig ?: current).profiles.forEach',
                       'SubAgentProfileRow(profile, providers, enabled = panelEnabled,',
                       'SubAgentProfileDraftSession.open(panelEditor)',
                       'SubAgentProfileConfigDialog(session, panelEditor, providers',
                       'Switch(checked = panelConfig?.enabled ?: current.enabled, enabled = panelEnabled'):
            self.assertIn(needle, panel)
        self.assertNotIn('SubAgentProfileRow(profile, providers, enabled = canChange)', panel)

    def test_catalog_actions_use_anchored_menu_and_do_not_replace_profile_config(self):
        shell = self.source('ui/SubAgentSettingsScreen.kt')
        detail = self.source('ui/SubAgentPresetDetailScreen.kt')
        # 组溢出操作改走就地弹出菜单：与详情页子代理标题行同一套 DropdownMenu，不再拉起底部面板。
        for needle in ('SubAgentDropdownMenu(', 'DropdownMenuItem(',
                       'name = preset.name; rename = preset', 'if (editable) delete = preset',
                       'repository.renamePreset(', 'repository.removePreset(', 'AlertDialog(onDismissRequest = { delete = null }'):
            self.assertIn(needle, shell)
        for gone in ('actionsPreset', 'SubAgentPresetActionsSheet', 'ModalBottomSheet('):
            self.assertNotIn(gone, shell)
        self.assertIn('SubAgentProfileConfigDialog(session, editor, providers', detail)
        row = self.source('ui/components/SubAgentProfileRow.kt')
        for needle in ('Icon(Icons.Rounded.MoreVert, stringResource(R.string.spr_config_for, profile.name)',
                       'SubAgentProfileDraftSession.open(editor, profile)',
                       'SubAgentProfileConfigDialog(session, editor, providers',
                       'onLongClickLabel = stringResource(R.string.spr_adjust_thinking_for, profile.name)',
                       'onLongClick = { if (currentUsable && canThink) { thinkingPicker = true } }'):
            self.assertIn(needle, row)
        # 可访问名走资源，点击/长按由官方 ListItem 槽位承载：不再手写 onClickLabel，也不再补长按触感。
        self.assertNotIn('onClickLabel =', row)
        self.assertNotIn('TouchHaptics.longPress', row)
        self.assertNotIn('SubAgentPresetActionsSheet(', row)

    def test_dialog_has_adaptive_body_and_one_state_dependent_left_action_in_fixed_footer(self):
        dialog = self.source('ui/components/ConversationCollaborationDialog.kt')
        # 页面结构：固定动作区在 bottomBar，正文在脚手架内容区并单独滚动。
        _, rest = dialog.split('bottomBar = {', 1)
        footer, body = rest.split(') { padding, scrollConnection, _ ->', 1)
        for resource in ('R.string.ccd_use_current_config', 'R.string.ccd_switch_group'):
            with self.subTest(resource=resource):
                self.assertNotIn(f'stringResource({resource})', body)
                self.assertEqual(footer.count(f'stringResource({resource})'), 1)
        for needle in ('Modifier.fillMaxWidth()', 'heightIn(min = 48.dp)',
                       'verticalAlignment = Alignment.CenterVertically',
                       'horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)',
                       'if (choosing && current != null', 'else if (!choosing && panelEditor != null)',
                       'popup.isCurrent(ticket, popup)', 'latestCanChange && latestChoosing && editor.enabled',
                       'panelEnabled && panelEditor.enabled', '!latestChoosing',
                       'Text(stringResource(R.string.action_done))', 'TouchHaptics.click(view)',
                       'testTag("subagent-collaboration-footer")'):
            self.assertIn(needle, footer)
        self.assertNotIn('verticalScroll(', footer)
        self.assertNotIn('applyPreset(', footer)
        self.assertNotIn('setEnabled(', footer)
        for needle in ('.nestedScroll(scrollConnection)', '.verticalScroll(',
                       'appliedPresetName?.let', 'panelEditor.setEnabled(it)',
                       'Text(stringResource(R.string.ccd_auto_delegation))', 'Switch(checked = panelConfig?.enabled',
                       'testTag("subagent-collaboration-body")'):
            self.assertIn(needle, body)
        # 整页承载：外壳与顶栏交给官方页面组件，不再手写上限或屏幕高度算术。
        self.assertIn('EtaPageSurface(onDismissRequest = onDismiss)', dialog)
        self.assertIn('EtaScaffold(', dialog)
        for gone in ('widthIn(max = 480.dp)', 'heightIn(max =', 'LocalConfiguration.current.screenHeightDp',
                     'ModalBottomSheet('):
            self.assertNotIn(gone, dialog)
        for forbidden in ('OverlayDialog(', 'requiredHeight(', 'fillMaxHeight('):
            self.assertNotIn(forbidden, dialog)

    def test_small_viewport_alignment_scroll_and_retained_callback_tests_are_present(self):
        tests = Path(__file__).resolve().parents[1] / 'kotlin/io/github/mangi/eta/ui/components'
        collaboration = (tests / 'ConversationCollaborationDialogTest.kt').read_text()
        for needle in ('@Config(qualifiers = "zh-rCN-w320dp-h480dp")', 'assertFooterAligned(',
                       'body.bottom <= footer.top', 'left.center.y - done.center.y',
                       'emptyChooserAndEmptyCurrentConfigWrapHeightAndAlignFooterWithoutWrites',
                       'longChooserAndAppliedPanelKeepBothFooterActionsFixedDuringScroll',
                       'retainedFooterCallbacksCannotOperateAReopenedChooserOrReappliedPanel',
                       'lockedDialogKeepsDisabledDoneButtonInsideSmallViewport',
                       'compactConfigKeepsAccessibleTouchTargetAndDoneStaysVisible'):
            self.assertIn(needle, collaboration)
        settings = (tests / 'SubAgentPresetSettingsTest.kt').read_text()
        # 该测试断言中文资源字符串，需固定中文 locale，否则 Robolectric 默认 en-rUS 会让
        # stringResource 解析成英文而断言失败。
        for needle in ('@Config(qualifiers = "zh-rCN-w320dp-h480dp")',
                       'presetMoreOpensActionMenuOnSmallScreenWithoutEditingOrCopyAction',
                       'presetDetailHidesGlobalSwitchesAndProfileEditsPreserveAutomaticDelegation',
                       '重命名组', 'assertFalse("removing the switch must not replace the stored enabled value", saved.enabled)'):
            self.assertIn(needle, settings)

    def test_detail_is_a_real_route_and_presets_cannot_run(self):
        shell = self.source('ui/SubAgentSettingsScreen.kt')
        detail = self.source('ui/SubAgentPresetDetailScreen.kt')
        route = self.source('ui/navigation/AppRoute.kt')
        table = self.source('ui/navigation/AgentNavigator.kt')
        root = self.source('ui/app/AgentAppRoot.kt')
        # 详情页是独立导航目标，才能复用 NavHost 的 300ms 滑动淡入淡出与预测性返回。
        self.assertIn('data class SubAgentPresetDetail(val presetId: String) : AppRoute', route)
        self.assertIn('AppRoute.SubAgentPresetDetail::class', table)
        for needle in ('onOpenPreset = { presetId -> pushRoute(AppRoute.SubAgentPresetDetail(presetId)) }',
                       'composable<AppRoute.SubAgentPresetDetail> { entry ->',
                       'val route = entry.toRoute<AppRoute.SubAgentPresetDetail>()',
                       'SubAgentPresetDetailScreen(',
                       'isCurrentRoute = currentRoute == route'):
            self.assertIn(needle, root)
        self.assertNotIn('selectedId', shell)
        self.assertNotIn('BackHandler', shell)
        self.assertIn('isCurrentRoute: Boolean = true', detail)
        self.assertIn('enabled = isCurrentRoute', detail)
        state = self.source('ui/app/AgentAppState.kt')
        self.assertIn('is SubAgentConfigKey.Preset -> error("预设不能作为会话运行配置")', state)

    def test_parallel_limit_wording_fits_preset_and_conversation_without_layout_change(self):
        text = self.source('ui/components/SubAgentParallelLimitRow.kt')
        # 文案已外置到资源；范围说明按「本配置」表述，不再出现会话级措辞。
        self.assertIn('R.string.sap_parallel_shared_note', text)
        self.assertIn('R.string.sap_parallel_set_for', text)
        self.assertNotIn('同一会话下', text)
        strings = (ROOT.parents[4] / 'res/values/strings.xml').read_text()
        note = next(line for line in strings.splitlines() if 'name="sap_parallel_shared_note"' in line)
        self.assertIn('within this config', note)
        self.assertNotIn('conversation', note)


if __name__ == '__main__':
    unittest.main()
