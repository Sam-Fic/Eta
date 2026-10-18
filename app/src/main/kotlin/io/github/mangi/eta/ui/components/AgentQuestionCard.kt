package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaOnSurface
import io.github.mangi.eta.ui.adaptive.etaOnSurfaceVariant
import io.github.mangi.eta.ui.adaptive.etaPrimary
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.app.AgentQuestionProjection
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi

/** Draft and submission state stay in the conversation store. Only whole-card disclosure is local; the form itself is never folded in parts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentQuestionCard(
    message: AgentQuestionMessageUi,
    modifier: Modifier = Modifier,
    onDraftChanged: (AgentQuestionAnswer) -> Unit,
    onSubmit: () -> Unit,
) {
    // A recycled chat slot must not inherit another conversation/run/call/question's controls.
    key(AgentQuestionProjection.messageId(message.request)) {
        // M3 Expressive：卡片内所有可点区域都要有官方按下反馈。
        AgentQuestionCardContent(message, modifier, onDraftChanged, onSubmit)
    }
}

@Composable
private fun AgentQuestionCardContent(
    message: AgentQuestionMessageUi,
    modifier: Modifier,
    onDraftChanged: (AgentQuestionAnswer) -> Unit,
    onSubmit: () -> Unit,
) {
    val request = message.request
    val waiting = message.status == AgentQuestionStatus.Waiting
    // Submission is guarded by the store. A local latch could stay locked when a fast failure
    // returns to the original snapshot before Compose observes the intermediate submitting state.
    // Only authoritative status transitions reset disclosure. Submitting/retry keep the form.
    var expanded by remember(message.status) { mutableStateOf(waiting) }
    var anchorBottom by remember { mutableStateOf(false) }
    val holdsBottom = LocalExpansionHoldsBottom.current
    // 展开前解除底部锚定，标题钉在原位、内容向下生长可见。
    val releaseAnchor = LocalChatAnchorRelease.current
    val state = stringResource(if (expanded) R.string.question_expanded else R.string.question_collapsed)
    val statusText = questionStatusText(message.status)
    val toggle: () -> Unit = {
        anchorBottom = holdsBottom()
        if (!expanded) releaseAnchor()
        expanded = !expanded
    }
    // Reuse the steps' actual outlined shell, including its sole 16dp message gutters.
    WorkProcessCardSlice(part = WorkProcessCardPart.Whole, modifier = modifier) {
        Column(Modifier.fillMaxWidth().testTag("question-card-surface")) {
            QuestionDisclosureHeader(
                expanded = expanded,
                statusText = statusText,
                title = request.title,
                waiting = waiting,
                stateDescription = state,
                onToggle = toggle,
            )
            AnimatedVisibility(visible = expanded,
                enter = tailDetailsEnter(anchorBottom), exit = tailDetailsExit(anchorBottom)) {
                Column(modifier = retainDrawLayerWhenIdle()) {
                    // M3 Expressive：官方 HorizontalDivider 画卡片内的发丝分隔线，
                    // 缩进与标题正文起始位置对齐（与思考/工具卡片一致）。
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(request.title, modifier = Modifier.fillMaxWidth(),
                            style = etaTextStyle(EtaType.Body),
                            color = etaOnSurface())
                        Text(request.question, modifier = Modifier.fillMaxWidth(),
                            style = etaTextStyle(EtaType.Body),
                            color = etaOnSurface())
                        if (!waiting) {
                            QuestionReadOnlyDetails(message)
                        } else {
                            val editable = !message.submitting
                            val displayed = AgentQuestionProjection.draftAnswer(message)
                            val draft = AgentQuestionAnswer(message.answerKind, message.selectedOptionId, message.otherText, message.note)
                            val valid = AgentQuestionCodec.validateAnswer(request, displayed).accepted
                            val changeDraft: (AgentQuestionAnswer) -> Unit = { if (editable) onDraftChanged(it) }
                            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                request.options.forEach { option ->
                                    key(option.id) {
                                        val selected = displayed.kind == "option" && displayed.optionId == option.id
                                        QuestionOptionRow(option.label, option.description, selected, editable,
                                            option.id == request.recommendedOptionId) {
                                            changeDraft(draft.copy(kind = "option", optionId = option.id))
                                        }
                                    }
                                }
                                if (request.allowOther) {
                                    QuestionOptionRow(stringResource(R.string.question_other), "",
                                        displayed.kind == "other", editable, false) {
                                        changeDraft(draft.copy(kind = "other", optionId = null))
                                    }
                                    if (displayed.kind == "other") QuestionTextField(
                                        value = message.otherText,
                                        onValueChange = { changeDraft(draft.copy(kind = "other", optionId = null, otherText = it.take(2000))) },
                                        enabled = editable, label = stringResource(R.string.question_other_hint))
                                }
                                if (request.allowDelegation) QuestionOptionRow(stringResource(R.string.question_delegate),
                                    stringResource(R.string.question_delegate_hint), displayed.kind == "delegate", editable,
                                    false) { changeDraft(draft.copy(kind = "delegate", optionId = null)) }
                            }
                            if (request.allowNote) QuestionTextField(value = message.note,
                                onValueChange = { changeDraft(draft.copy(note = it.take(2000))) }, enabled = editable,
                                label = stringResource(R.string.question_note))
                            message.error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error,
                                    style = etaTextStyle(EtaType.Caption))
                            }
                            // M3 Expressive：官方 Button 的默认形状、容器高度、内边距、
                            // primary / onPrimary 与禁用态颜色全部交给官方默认值。
                            Button(onClick = { if (editable && valid) onSubmit() },
                                enabled = editable && valid, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (message.submitting) R.string.question_submitting else R.string.question_submit))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 卡片标题栏。
 *
 * 用官方 `ListItem(onClick = …)` 承担展开/收起的可点区域、涟漪与语义；
 * 容器保持透明，外壳仍由 `WorkProcessCardSlice` 绘制。
 */
@Composable
private fun QuestionDisclosureHeader(
    expanded: Boolean,
    statusText: String,
    title: String,
    waiting: Boolean,
    stateDescription: String,
    onToggle: () -> Unit,
) {
    // 标题栏与思考/工具卡片共用同一构件，三类卡片的标题行逐值一致。
    WorkCardDisclosureHeader(
        leadingIcon = Icons.Rounded.ChatBubbleOutline,
        leadingTint = if (waiting) etaPrimary() else etaOnSurfaceVariant(),
        title = "$statusText · $title",
        titleColor = etaOnSurfaceVariant(),
        expanded = expanded,
        onToggle = onToggle,
        modifier = Modifier.semantics { this.stateDescription = stateDescription },
    )
}

@Composable
private fun questionStatusText(status: AgentQuestionStatus): String = stringResource(when (status) {
    AgentQuestionStatus.Waiting -> R.string.question_heading
    AgentQuestionStatus.Answered -> R.string.question_answered
    AgentQuestionStatus.Cancelled -> R.string.question_cancelled
    AgentQuestionStatus.Interrupted -> R.string.question_interrupted
})

@Composable
private fun QuestionOptionRow(
    label: String, description: String, selected: Boolean, enabled: Boolean, recommended: Boolean,
    onClick: () -> Unit,
) {
    // M3 Expressive：官方单选 ListItem 承担整行命中区、选中容器色、涟漪与 Role.RadioButton；
    // 单选圆点用官方 RadioButton（不自绘，也不手拼 selectable + 背景）。
    ListItem(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        leadingContent = {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        },
        supportingContent = if (description.isNotBlank()) {
            {
                Text(
                    text = description,
                    style = etaTextStyle(EtaType.Secondary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
        content = { QuestionOptionLabel(label, recommended) },
    )
}

@Composable
private fun QuestionOptionLabel(label: String, recommended: Boolean) {
    // M3 Expressive：「推荐」标签用官方 Badge（官方容器色与内边距），不自绘底色圆角块。
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = etaTextStyle(EtaType.Body), color = MaterialTheme.colorScheme.onSurface)
        if (recommended) {
            Badge(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Text(stringResource(R.string.question_recommended), style = etaTextStyle(EtaType.Micro))
            }
        }
    }
}

@Composable
private fun QuestionTextField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, label: String) {
    EtaFormTextField(value = value, onValueChange = onValueChange, enabled = enabled, hint = label,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), minLines = 1, maxLines = Int.MAX_VALUE)
}

@Composable
private fun questionSummary(message: AgentQuestionMessageUi): String? {
    if (message.status != AgentQuestionStatus.Answered) return null
    // Never turn an uncommitted draft into a historical answer. The header already shows status.
    val answer = message.answer ?: return null
    val choice = when (answer.kind) {
        "option" -> message.request.options.firstOrNull { it.id == answer.optionId }?.label ?: return null
        "other" -> stringResource(R.string.question_other) + " " + answer.otherText
        "delegate" -> stringResource(R.string.question_delegate)
        else -> return null
    }
    return stringResource(R.string.question_selected, choice)
}

/** Historical details are text, not a disabled form; they can never submit or mutate a draft. */
@Composable
private fun QuestionReadOnlyDetails(message: AgentQuestionMessageUi) {
    val request = message.request
    // Only the submitted answer is authority. Never read selectedOptionId, otherText or note drafts here.
    val answer = message.answer.takeIf { message.status == AgentQuestionStatus.Answered }
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val labelStyle = MaterialTheme.typography.bodyMedium
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyColor = etaOnSurface()
    val summaryColor = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        questionSummary(message)?.let { summary ->
            // This is the only summary. Custom answers remain complete, even when very long.
            Text(summary, modifier = Modifier.fillMaxWidth(), style = bodyStyle, color = summaryColor)
        }
        if (answer == null) {
            // Unanswered history may show the original options, never an uncommitted draft.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                request.options.forEach { option ->
                    QuestionHistoryOption(option.label, option.description, selected = false,
                        recommended = option.id == request.recommendedOptionId)
                }
            }
        } else {
            when (answer.kind) {
                "option" -> request.options.firstOrNull { it.id == answer.optionId }?.description
                    ?.takeIf { it.isNotBlank() }?.let {
                        Text(it, modifier = Modifier.fillMaxWidth(), style = labelStyle, color = labelColor)
                    }
                "delegate" -> Text(stringResource(R.string.question_delegate_hint), modifier = Modifier.fillMaxWidth(),
                    style = labelStyle, color = labelColor)
                else -> Unit
            }
        }
        answer?.note?.takeIf { it.isNotBlank() }?.let { note ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.question_note), style = labelStyle, color = labelColor)
                Text(note, modifier = Modifier.fillMaxWidth(), style = bodyStyle, color = bodyColor)
            }
        }
    }
}

@Composable
private fun QuestionHistoryOption(label: String, description: String, selected: Boolean, recommended: Boolean = false) {
    // M3 Expressive：历史项是纯展示的官方 ListItem；未选中的圆点用官方禁用 RadioButton，
    // 不手绘圆形描边。
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        // 官方展示型 ListItem 已自行 mergeDescendants，不必重复声明。
        // 但它的 applySemantics 只在 !enabled 时给 disabled()，不设 selected，
        // 所以 selected 由本层补上（与单选重载不同：那个重载官方会自己设）。
        modifier = Modifier.semantics { this.selected = selected },
        trailingContent = {
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                RadioButton(selected = false, onClick = null, enabled = false)
            }
        },
        supportingContent = if (description.isNotBlank()) {
            {
                Text(
                    text = description,
                    style = etaTextStyle(EtaType.Secondary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
        content = { QuestionOptionLabel(label, recommended) },
    )
}
