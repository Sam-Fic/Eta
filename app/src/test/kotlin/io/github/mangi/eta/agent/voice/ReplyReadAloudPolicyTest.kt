package io.github.mangi.eta.agent.voice

import android.app.Application
import io.github.mangi.eta.agent.voice.tts.SpeechSpeakableText
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.config.ReplyReadAloudPreference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReplyReadAloudPolicyTest {

    @Before fun reset() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        Prefs.localAgentPreferences()!!.edit().clear().commit()
    }

    @Test fun autoReadDefaultsOffAndStreamSynthesisDefaultsOn() {
        assertFalse(ReplyReadAloudPreference.autoRead)
        assertTrue(ReplyReadAloudPreference.streamSynthesis)
    }

    @Test fun switchesRoundTripThroughLocalAgentPreferences() {
        ReplyReadAloudPreference.setAutoRead(true)
        ReplyReadAloudPreference.setStreamSynthesis(false)
        assertTrue(ReplyReadAloudPreference.autoRead)
        assertFalse(ReplyReadAloudPreference.streamSynthesis)
        // 总开关关闭不影响细分开关的持久值，重新打开后细分选择仍然保留。
        ReplyReadAloudPreference.setAutoRead(false)
        assertFalse(ReplyReadAloudPreference.autoRead)
        assertFalse(ReplyReadAloudPreference.streamSynthesis)
        ReplyReadAloudPreference.setAutoRead(true)
        assertFalse(ReplyReadAloudPreference.streamSynthesis)
    }

    @Test fun openingHistoryNeverReadsOldReplies() {
        val tracker = ReplyReadAloudTracker()
        assertTrue(
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = false, lastAgentId = "old", lastAgentText = "上一段的回复。"),
                streamSynthesis = true,
            ).isEmpty(),
        )
        assertFalse(tracker.armed)
    }

    @Test fun streamingReadsOnlyCommittedSentencesAndNeverRepeatsThem() {
        val tracker = ReplyReadAloudTracker()
        // 新运行开始：上一条回复不会被重读。
        assertTrue(
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = true, lastAgentId = "old", lastAgentText = "上一段的回复。"),
                streamSynthesis = true,
            ).isEmpty(),
        )
        assertEquals(
            listOf("第一句。"),
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = "第一句。第二句"),
                streamSynthesis = true,
            ),
        )
        // 重复快照（丢帧合并 / 浮窗与应用同时喂同一状态）不重复朗读。
        assertTrue(
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = "第一句。第二句"),
                streamSynthesis = true,
            ).isEmpty(),
        )
        // 回复结束：读剩余未朗读部分。
        assertEquals(
            listOf("第二句"),
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = false, lastAgentId = "m1", lastAgentText = "第一句。第二句"),
                streamSynthesis = true,
            ),
        )
        assertEquals("m1", tracker.finalizedId)
        // 结束后的重复快照（重新组合）不重读。
        assertTrue(
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = false, lastAgentId = "m1", lastAgentText = "第一句。第二句"),
                streamSynthesis = true,
            ).isEmpty(),
        )
        assertFalse(tracker.armed)
    }

    @Test fun streamSynthesisOffWaitsForTheWholeReply() {
        val tracker = ReplyReadAloudTracker()
        // 运行开始：基线是运行前就存在的旧回复。
        tracker.onSnapshot(
            VoiceChatSnapshot(isStreaming = true, lastAgentId = "m0", lastAgentText = "旧回复。"),
            streamSynthesis = false,
        )
        assertTrue(tracker.armed)
        // 新回复流式中不开口。
        assertTrue(
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = "第一句。第二句"),
                streamSynthesis = false,
            ).isEmpty(),
        )
        // 回复全部结束后一次读完整段。
        assertEquals(
            listOf("第一句。", "第二句"),
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = false, lastAgentId = "m1", lastAgentText = "第一句。第二句"),
                streamSynthesis = false,
            ),
        )
    }

    @Test fun turningStreamSynthesisOffMidReplyStillReadsTheUnspokenTail() {
        val tracker = ReplyReadAloudTracker()
        tracker.onSnapshot(
            VoiceChatSnapshot(isStreaming = true, lastAgentId = "m0", lastAgentText = "旧回复。"),
            streamSynthesis = true,
        )
        tracker.onSnapshot(
            VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = "第一句。第二句"),
            streamSynthesis = true,
        )
        // 中途关闭流式合成：不再逐句抢读，但已读过的句子不会被重复。
        assertTrue(
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = "第一句。第二句"),
                streamSynthesis = false,
            ).isEmpty(),
        )
        assertEquals(
            listOf("第二句"),
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = false, lastAgentId = "m1", lastAgentText = "第一句。第二句"),
                streamSynthesis = false,
            ),
        )
    }

    @Test fun nextRunOnlyReadsTheNewReply() {
        val tracker = ReplyReadAloudTracker()
        tracker.onSnapshot(VoiceChatSnapshot(isStreaming = true, lastAgentId = "m0", lastAgentText = "旧回复。"), streamSynthesis = true)
        tracker.onSnapshot(VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = "第一句。第二句"), streamSynthesis = true)
        tracker.onSnapshot(VoiceChatSnapshot(isStreaming = false, lastAgentId = "m1", lastAgentText = "第一句。第二句"), streamSynthesis = true)
        // 新一轮运行开始：流式期间仍盯着上一条回复，不重读。
        assertTrue(
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = "第一句。第二句"),
                streamSynthesis = true,
            ).isEmpty(),
        )
        assertEquals(
            listOf("新的回复。"),
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = true, lastAgentId = "m2", lastAgentText = "新的回复。"),
                streamSynthesis = true,
            ),
        )
    }

    @Test fun switchingConversationsAfterAFinishedRunDoesNotRead() {
        val tracker = ReplyReadAloudTracker()
        tracker.onSnapshot(VoiceChatSnapshot(isStreaming = true, lastAgentId = "m0", lastAgentText = "旧回复。"), streamSynthesis = true)
        tracker.onSnapshot(VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = "第一句。"), streamSynthesis = true)
        tracker.onSnapshot(VoiceChatSnapshot(isStreaming = false, lastAgentId = "m1", lastAgentText = "第一句。"), streamSynthesis = true)
        assertFalse(tracker.armed)
        // 切换到另一段会话：最后一条是别人的历史回复，不读。
        assertTrue(
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = false, lastAgentId = "other", lastAgentText = "另一段会话的回复。"),
                streamSynthesis = true,
            ).isEmpty(),
        )
        assertFalse(tracker.armed)
    }

    @Test fun emptyReplyDisarmsAndNeverLeaksIntoTheNextConversation() {
        val tracker = ReplyReadAloudTracker()
        tracker.onSnapshot(VoiceChatSnapshot(isStreaming = true, lastAgentId = "m0", lastAgentText = "旧回复。"), streamSynthesis = true)
        tracker.onSnapshot(VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = ""), streamSynthesis = true)
        assertTrue(tracker.armed)
        assertTrue(
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = false, lastAgentId = "m1", lastAgentText = ""),
                streamSynthesis = true,
            ).isEmpty(),
        )
        assertEquals("m1", tracker.finalizedId)
        assertFalse(tracker.armed)
        assertTrue(
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = false, lastAgentId = "other", lastAgentText = "别处的回复。"),
                streamSynthesis = true,
            ).isEmpty(),
        )
    }

    @Test fun overLongReplyIsNotAutoReadAndIsMarkedFinalized() {
        val tracker = ReplyReadAloudTracker()
        tracker.onSnapshot(VoiceChatSnapshot(isStreaming = true, lastAgentId = "m0", lastAgentText = "旧回复。"), streamSynthesis = true)
        tracker.onSnapshot(VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = "开头。"), streamSynthesis = true)
        val tooLong = "啊".repeat(SpeechSpeakableText.MAX_SOURCE_CHARS + 1)
        assertTrue(
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = false, lastAgentId = "m1", lastAgentText = tooLong),
                streamSynthesis = true,
            ).isEmpty(),
        )
        assertEquals("m1", tracker.finalizedId)
    }

    @Test fun resetClearsEveryPieceOfProgress() {
        val tracker = ReplyReadAloudTracker()
        tracker.onSnapshot(VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = "第一句。"), streamSynthesis = true)
        tracker.reset()
        assertFalse(tracker.armed)
        assertNull(tracker.baselineId)
        assertNull(tracker.currentId)
        assertEquals(0, tracker.spokenCount)
        assertNull(tracker.finalizedId)
    }

    @Test fun runStartTreatsThePreRunReplyAsBaseline() {
        val tracker = ReplyReadAloudTracker()
        // 已有回复的会话里发新消息：运行刚开始时最后一条还是旧回复，不能读。
        val oldReply = VoiceChatSnapshot(isStreaming = true, lastAgentId = "m1", lastAgentText = "旧回复。")
        assertTrue(tracker.onSnapshot(oldReply, streamSynthesis = true).isEmpty())
        assertEquals("m1", tracker.baselineId)
        // 基线在流式期间持续被跳过，直到真正的新回复出现。
        assertTrue(tracker.onSnapshot(oldReply, streamSynthesis = true).isEmpty())
        assertEquals(
            listOf("新回复。"),
            tracker.onSnapshot(
                VoiceChatSnapshot(isStreaming = true, lastAgentId = "m2", lastAgentText = "新回复。"),
                streamSynthesis = true,
            ),
        )
    }
}
