package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import org.junit.Assert.*
import org.junit.Test

class VoiceEntryPolicyTest {
    @Test fun onlyConversationAndRealtimeAreExposed() {
        for (bits in 0..3) {
            val config = DoubaoVoiceConfig.Config(
                conversationEnabled = bits and 1 != 0,
                duplexEnabled = bits and 2 != 0,
            )
            val expected = buildList {
                if (bits and 1 != 0) add(VoiceEntryMode.UNIVERSAL)
                if (bits and 2 != 0) add(VoiceEntryMode.DOUBAO_DUPLEX)
            }
            assertEquals(expected, VoiceEntryPolicy.modes(config))
            assertEquals(expected.singleOrNull(), VoiceEntryPolicy.directMode(config))
            assertEquals(expected.size > 1, VoiceEntryPolicy.canChoose(config))
            VoiceEntryMode.entries.forEach {
                assertEquals(it in expected, VoiceEntryPolicy.enabled(config, it))
            }
        }
    }

    @Test fun dictationIsNeverAStandaloneEntry() {
        val config = DoubaoVoiceConfig.Config(
            inputEnabled = true,
            conversationEnabled = false,
            duplexEnabled = false,
        )
        assertTrue(VoiceEntryPolicy.modes(config).isEmpty())
        assertFalse(VoiceEntryPolicy.enabled(config, VoiceEntryMode.DICTATION))
    }

    @Test fun legacyDictationSelectionStartsMergedConversation() {
        val config = DoubaoVoiceConfig.Config(conversationEnabled = true, duplexEnabled = true)
        assertEquals(VoiceEntryMode.UNIVERSAL, VoiceEntryPolicy.directMode(config, "dictation"))
        assertEquals(VoiceEntryMode.UNIVERSAL, VoiceEntryPolicy.directMode(config, "universal"))
        assertEquals(VoiceEntryMode.DOUBAO_DUPLEX, VoiceEntryPolicy.directMode(config, "doubao_duplex"))
        assertNull(VoiceEntryPolicy.directMode(config, ""))
        assertTrue(VoiceEntryPolicy.canChoose(config))
    }

    @Test fun disabledHistoryFallsBackToRemainingModeOrChooser() {
        val onlyDuplex = DoubaoVoiceConfig.Config(conversationEnabled = false, duplexEnabled = true)
        assertEquals(VoiceEntryMode.DOUBAO_DUPLEX, VoiceEntryPolicy.directMode(onlyDuplex, "dictation"))
        assertEquals(VoiceEntryMode.DOUBAO_DUPLEX, VoiceEntryPolicy.directMode(onlyDuplex, "universal"))
        assertNull(VoiceEntryPolicy.directMode(onlyDuplex.copy(duplexEnabled = false), "dictation"))
    }
}
