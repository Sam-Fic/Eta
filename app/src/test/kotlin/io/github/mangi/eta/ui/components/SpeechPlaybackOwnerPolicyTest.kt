package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlaybackOwnerPolicyTest {
    @Test fun voiceConversationPlaybackIsNotTreatedAsOrphan() {
        assertFalse(
            shouldStopOrphanSpeechPlayback(
                owner = "voice-mode-123",
                messageEditActive = false,
                visibleCompletedAgentIds = setOf("assistant-1"),
            ),
        )
    }

    @Test fun previewPlaybackIsNotTreatedAsOrphan() {
        assertFalse(
            shouldStopOrphanSpeechPlayback(
                owner = "tts-preview",
                messageEditActive = false,
                visibleCompletedAgentIds = emptySet(),
            ),
        )
    }

    @Test fun agentToolPlaybackIsNotTreatedAsOrphan() {
        assertFalse(
            shouldStopOrphanSpeechPlayback(
                owner = "agent-tts",
                messageEditActive = false,
                visibleCompletedAgentIds = emptySet(),
            ),
        )
    }

    @Test fun finishedReplyPlaybackKeepsPlaying() {
        assertFalse(
            shouldStopOrphanSpeechPlayback(
                owner = "assistant-1",
                messageEditActive = false,
                visibleCompletedAgentIds = setOf("assistant-1"),
            ),
        )
    }

    @Test fun autoReadPlaybackIsNotTreatedAsOrphan() {
        assertFalse(
            shouldStopOrphanSpeechPlayback(
                owner = "auto-read-assistant-1",
                messageEditActive = false,
                visibleCompletedAgentIds = emptySet(),
            ),
        )
    }

    @Test fun streamingOrMissingReplyPlaybackStops() {
        assertTrue(
            shouldStopOrphanSpeechPlayback(
                owner = "assistant-1",
                messageEditActive = false,
                visibleCompletedAgentIds = emptySet(),
            ),
        )
        assertTrue(
            shouldStopOrphanSpeechPlayback(
                owner = "assistant-1",
                messageEditActive = true,
                visibleCompletedAgentIds = setOf("assistant-1"),
            ),
        )
    }
}
