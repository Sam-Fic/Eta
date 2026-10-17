"""Source wiring guards only: no Kotlin compile, real-device rendering or ripple capture."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/components'


class QuestionMessageLayoutContractTest(unittest.TestCase):
    def setUp(self):
        self.card = (SOURCE / 'AgentQuestionCard.kt').read_text()
        self.entry = (SOURCE / 'ChatMessageItem.kt').read_text()
        self.shell = (SOURCE / 'AgentWorkProcessCard.kt').read_text()

    def test_actual_steps_shell_owns_the_single_message_gutter(self):
        self.assertIn('WorkProcessCardSlice(part = WorkProcessCardPart.Whole, modifier = modifier)', self.card)
        # M3 外壳以 16dp 消息左右留白（WorkProcessCardSliceM3 的 Box padding）。
        self.assertIn('start = 16.dp', self.shell)
        self.assertIn('end = 16.dp', self.shell)
        # 不再自绘 Surface 容器（外壳由 WorkProcessCardSlice 的 clip + surfaceContainer 承担）；
        # etaOnSurface() 是语义色调用，不算容器调用。
        self.assertNotRegex(self.card, r'(?<![A-Za-z])Surface\(')
        self.assertIn('question-card-surface', self.card)
        branch = self.entry.split('is io.github.mangi.eta.ui.model.AgentQuestionMessageUi ->', 1)[1].split('is UserMessageUi ->', 1)[0]
        self.assertNotIn('padding(', branch)  # no duplicated gutter
        self.assertIn('actions.onQuestionDraftChanged(message.request.conversationId, message.request.questionId, draft)', branch)
        self.assertIn('actions.onSubmitQuestionAnswer(message.request.conversationId, message.request.questionId)', branch)

    def test_whole_card_uses_existing_step_animation_and_click_time_anchor(self):
        self.assertEqual(1, self.card.count('AnimatedVisibility('))
        self.assertIn('enter = tailDetailsEnter(anchorBottom), exit = tailDetailsExit(anchorBottom)', self.card)
        self.assertIn('Column(modifier = retainDrawLayerWhenIdle())', self.card)
        parent = (SOURCE / 'AgentChatBody.kt').read_text()
        provider = parent.index('LocalExpansionHoldsBottom provides expansionHoldsBottom')
        entry = parent.index('is AgentTimelineRow.Message -> {', provider)
        self.assertIn('ChatMessageItem(', parent[entry:entry + 2500])
        self.assertIn('val holdsBottom = LocalExpansionHoldsBottom.current', self.card)
        self.assertEqual(1, self.card.count('holdsBottom()'))
        # 点击当刻捕获“是否钉底”，同时释放锚定；展开/收起仍走在位动画。
        toggle = self.card.split('val toggle: () -> Unit = {', 1)[1].split('\n    }', 1)[0]
        self.assertIn('anchorBottom = holdsBottom()', toggle)
        self.assertIn('expanded = !expanded', toggle)
        self.assertIn('onToggle = toggle,', self.card)
        # 展开箭头是与思考/工具卡片共用的 WorkCardChevron。
        self.assertIn('Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight', self.shell)
        self.assertNotIn('tween(', self.card)  # do not invent a similar but different animation

    def test_authoritative_status_not_submit_latch_controls_default_disclosure(self):
        self.assertIn('key(AgentQuestionProjection.messageId(message.request))', self.card)
        self.assertIn('var expanded by remember(message.status) { mutableStateOf(waiting) }', self.card)
        self.assertNotIn('remember(message.submitting)', self.card)
        submit = self.card.split('Button(onClick =', 1)[1].split(') {', 1)[0]
        self.assertIn('if (editable && valid) onSubmit()', submit)
        self.assertNotIn('expanded', submit)
        self.assertIn('val editable = !message.submitting', self.card)
        self.assertIn('AgentQuestionCodec.validateAnswer(request, displayed).accepted', self.card)

    def test_no_nested_disclosures_or_clipped_body_and_note(self):
        for obsolete in ('detailsExpanded', 'fullTextExpanded', 'noteExpanded', 'QuestionDisclosureRow',
                         'QuestionDescription', 'question_show_full_text', 'question_note_added'):
            self.assertNotIn(obsolete, self.card)
        body = self.card.split('AnimatedVisibility(visible = expanded,', 1)[1]
        self.assertNotIn('TextOverflow.Ellipsis', body)
        self.assertIn('maxLines = Int.MAX_VALUE', body)
        self.assertIn('MaterialTheme.typography.bodyLarge', body)
        self.assertIn('MaterialTheme.typography.bodyMedium', body)

    def test_official_material_components_own_press_feedback(self):
        # M3-only：整卡由官方 ListItem / RadioButton / Button / Badge 组成，按压涟漪、形状形变
        # 与语义由官方组件自带，不再手拼 clickable(indication = null) 或压制涟漪的包裹层。
        for component in ('ListItem(', 'RadioButton(', 'Button(', 'Badge('):
            self.assertIn(component, self.card)
        self.assertIn('selectableGroup()', self.card)
        self.assertNotIn('WithoutPressRipple', self.card)
        self.assertNotIn('LocalRippleConfiguration', self.card)
        self.assertNotIn('indication = null', self.card)

    def test_history_uses_only_authoritative_answer_and_one_summary(self):
        self.assertIn('message.answer.takeIf { message.status == AgentQuestionStatus.Answered }', self.card)
        history = self.card.split('private fun QuestionReadOnlyDetails', 1)[1].split('private fun QuestionHistoryOption', 1)[0]
        for draft in ('message.selectedOptionId', 'message.otherText', 'message.note'):
            self.assertNotIn(draft, history)
        self.assertEqual(1, self.card.count('questionSummary(message)?.let'))
        self.assertIn('if (answer == null)', history)
        self.assertIn('answer?.note?.takeIf', history)
        self.assertNotIn('onDraftChanged', history)
        self.assertNotIn('onSubmit', history)

    def test_optional_controls_still_respect_request_capabilities(self):
        self.assertIn('if (request.allowOther)', self.card)
        self.assertIn('if (request.allowDelegation)', self.card)
        self.assertIn('if (request.allowNote) QuestionTextField', self.card)
        self.assertIn('onClick = null', self.card)  # radio's parent owns the accessible full row
        # 单选语义由官方 ListItem(selected/onClick) + RadioButton 提供，不再声明 role = Role.RadioButton。
        self.assertIn('selected = selected,', self.card)
        self.assertIn('RadioButton(selected = selected, onClick = null', self.card)
        self.assertIn('.heightIn(min = 48.dp)', self.card)
        self.assertIn('option.id == request.recommendedOptionId', self.card)
        self.assertIn('displayed.kind == "option" && displayed.optionId == option.id', self.card)
        self.assertNotRegex(self.card, r'selectedOptionId\s*=\s*request.recommendedOptionId')

    def test_monet_semantic_colors_and_whole_card_semantics(self):
        # M3-only：颜色全部走 MaterialTheme.colorScheme / eta* 语义角色。
        self.assertIn('MaterialTheme.colorScheme.', self.card)
        self.assertIn('etaOnSurface()', self.card)
        self.assertIn('etaOnSurfaceVariant()', self.card)
        self.assertIn('etaPrimary()', self.card)
        # 展开态改用 stateDescription 语义（标题栏 ListItem 自带的“展开/收起”文案资源已不再使用）。
        self.assertIn('stateDescription = state,', self.card)
        self.assertIn('.semantics { this.stateDescription = stateDescription }', self.card)
        self.assertNotRegex(self.card, r'Color\(0x')
        self.assertNotIn('tonalElevation', self.card)


if __name__ == '__main__':
    unittest.main()
