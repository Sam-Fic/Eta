"""Source wiring guards; runtime layer/height behavior is covered by RetainedCardDrawLayerTest.

These do not measure RenderThread/GPU frame times or replace on-device visual/selection checks.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/components'


class ChatDrawLayerDedupContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.chat = (SOURCE / 'ChatMessageItem.kt').read_text()
        cls.layer = cls.chat.split(
            'internal fun AnimatedVisibilityScope.retainDrawLayerWhenIdle(', 1
        )[1].split('internal fun tailDetailsExit(', 1)[0]
        cls.tool = cls.chat.split('private fun ToolActivityInline(', 1)[1].split(
            'private fun BrowserPagePreview(', 1
        )[0]

    def test_thinking_selection_uses_one_jointly_gated_layer(self):
        self.assertIn(
            'HapticSelectionContainer(\n'
            '                modifier = retainDrawLayerWhenIdle(enabled = streamingState == null)\n'
            '                    .toggleProbe(toggleProbeRef, "content"),',
            self.chat,
        )
        self.assertNotIn('completedContentDrawLayer(retainDrawLayerWhenIdle(', self.chat)
        self.assertIn('enabled: Boolean = true', self.layer)
        self.assertIn('transition.currentState == EnterExitState.Visible', self.layer)
        self.assertIn('transition.targetState == EnterExitState.Visible', self.layer)
        self.assertIn(
            'val cache = enabled && settled && heightPx in 1..MAX_RETAINED_LAYER_HEIGHT_PX',
            self.layer,
        )

    def test_single_layer_and_height_observer_are_unconditional(self):
        self.assertIn('private const val MAX_RETAINED_LAYER_HEIGHT_PX = 8192', self.chat)
        self.assertEqual(1, self.layer.count('.onSizeChanged { heightPx = it.height }'))
        self.assertEqual(1, self.layer.count('.graphicsLayer('))
        self.assertIn('return Modifier\n        .onSizeChanged', self.layer)
        self.assertIn('CompositingStrategy.Offscreen', self.layer)
        self.assertIn('CompositingStrategy.Auto', self.layer)
        self.assertNotIn('return if', self.layer)
        self.assertNotIn('.then(', self.layer)
        self.assertNotIn('graphicsLayer {', self.layer)  # keep the stable parameter-form element

    def test_tool_details_reuse_card_body_with_a_single_whole_card_layer(self):
        details = self.tool.split('visible = isExpanded && hasDetails,', 1)[1]
        self.assertNotIn('retainDrawLayerWhenIdle(', details)
        # 展开体复用提问卡的 WorkCardExpandedBody（官方 shapes.large 外壳），不再自绘
        # miuix 时代的 squircleSurface 面板，因此 MiuixTheme 也随之一并消失。
        self.assertIn(
            'WorkCardExpandedBody(modifier = Modifier.toggleProbe(toggleProbeRef, "content"))',
            details,
        )
        self.assertNotIn('squircleSurface(', details)
        self.assertNotIn('MiuixTheme', self.chat)
        # 留帧层只有一层，且包在整卡外壳（WorkProcessCardSlice）上。
        self.assertEqual(1, details.count('completedContentDrawLayer('))
        card = details.split('// 独立行复用提问卡片的整卡外壳', 1)[1]
        self.assertIn('.let { completedContentDrawLayer(it, toolRowSettled) }', card)
        self.assertIn('HapticSelectionContainer {', details)
        self.assertIn('enter = tailDetailsEnter(anchorBottom)', self.tool)
        self.assertIn('exit = tailDetailsExit(anchorBottom)', self.tool)


if __name__ == '__main__':
    unittest.main()
