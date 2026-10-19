package io.github.mangi.eta.agent.voice.offline

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineSpeechPackTest {
    @get:Rule val folder = TemporaryFolder()
    private val asset = SpeechModelCatalog.Asset("test", 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    // 上游未公布哈希的文本文件只校验长度，这里覆盖该分支。
    private val lengthOnly = SpeechModelCatalog.Asset("test", 3, null)

    @Test fun disabledByDefaultIncludingAfterDownload() {
        assertFalse(OfflineSpeechPack.State().enabled)
        assertFalse(OfflineSpeechPack.State(checking = false, ready = true).enabled)
    }
    @Test fun onlyExactLengthAndHashPassValidation() {
        val file = folder.newFile("test")
        file.writeText("abc")
        assertTrue(OfflineSpeechPack.valid(file, asset))
        file.writeText("abd")
        assertFalse(OfflineSpeechPack.valid(file, asset))
        file.writeText("ab")
        assertFalse(OfflineSpeechPack.valid(file, asset))
        file.delete()
        assertFalse(OfflineSpeechPack.valid(file, asset))
    }
    @Test fun lengthOnlyAssetSkipsHashingButStillChecksLength() {
        val file = folder.newFile("test")
        file.writeText("xyz")
        assertTrue(OfflineSpeechPack.valid(file, lengthOnly))
        file.writeText("xy")
        assertFalse(OfflineSpeechPack.valid(file, lengthOnly))
    }
}
