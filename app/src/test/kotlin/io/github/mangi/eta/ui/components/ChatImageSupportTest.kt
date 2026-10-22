package io.github.mangi.eta.ui.components

import android.app.Application
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import android.util.Base64
import io.github.mangi.eta.agent.terminal.LinuxGuestPathResolver
import java.io.File
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChatImageSupportTest {
    @Test
    fun extractsMarkdownImageDestination() {
        val source = "see ![cat](https://example.com/cat.png) please"
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val image = collectMarkdownImageNodes(root).single()
        assertEquals("https://example.com/cat.png", markdownImageDestination(source, image))
        assertEquals(
            "https://example.com/cat.png",
            resolveChatImageSource(source, image),
        )
    }

    @Test
    fun extractsDataUrlMarkdownImage() {
        val source = "![shot](data:image/png;base64,aaa)"
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val image = collectMarkdownImageNodes(root).single()
        assertEquals("data:image/png;base64,aaa", markdownImageDestination(source, image))
    }

    @Test
    fun usesOverrideAsDirectSource() {
        assertEquals(
            "https://cdn.example/a.jpg",
            resolveChatImageSource(
                content = "https://cdn.example/a.jpg",
                node = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString("text"),
                sourceOverride = "https://cdn.example/a.jpg",
            ),
        )
        assertTrue(isDirectChatImageSource("data:image/jpeg;base64,xx"))
        assertTrue(isDirectChatImageSource("content://media/external/images/1"))
        assertFalse(isDirectChatImageSource("not-an-image"))
    }

    @Test
    fun mimeTypeAndFileNameFollowSource() {
        assertEquals("image/png", chatImageMimeType("data:image/png;base64,xx"))
        assertEquals("png", chatImageFileExtension("image/png"))
        assertEquals("jpg", chatImageFileExtension("image/jpeg"))
        assertTrue(chatImageFileName("image/webp").startsWith("Eta-"))
        assertTrue(chatImageFileName("image/webp").endsWith(".webp"))
        assertTrue(chatVideoFileName("video/mp4").endsWith(".mp4"))
        assertTrue(chatVideoFileName("video/webm", "clip.webm").endsWith(".webm"))
    }

    @Test
    fun readsDataUrlBytes() {
        val context = RuntimeEnvironment.getApplication()
        val png = tinyPng()
        val dataUrl = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        val payload = ChatImageBytes.readBytes(context, dataUrl)
        assertNotNull(payload)
        assertEquals("image/png", payload!!.mimeType)
        assertEquals(png.toList(), payload.bytes.toList())
    }

    @Test
    fun savesPngBytesToGallery() {
        val context = RuntimeEnvironment.getApplication()
        val png = tinyPng()
        val uri = ChatImageGallery.save(context, png, "image/png")
        assertNotNull(uri)
        val bytes = context.contentResolver.openInputStream(uri!!)?.use { it.readBytes() }
        assertNotNull(bytes)
        assertEquals(png.toList(), bytes!!.toList())
    }

    @Test
    fun acceptsGuestMinisAndRelativeSources() {
        assertTrue(isDirectChatImageSource("minis://workspace/out.png"))
        assertTrue(isDirectChatImageSource("/workspace/out.png"))
        assertTrue(isDirectChatImageSource("out.png"))
        assertTrue(isDirectChatImageSource("nested/out.png"))
        assertFalse(isDirectChatImageSource("not-an-image"))
        assertFalse(isDirectChatImageSource("plain words without dot"))
    }

    @Test
    fun resolvesGuestAndRelativeSourcesToWorkspaceHost() {
        val context = RuntimeEnvironment.getApplication()
        val workspace = LinuxGuestPathResolver.workspaceHostForApp(context)
        File(workspace, "nested").mkdirs()
        val png = tinyPng()
        File(workspace, "shot.png").writeBytes(png)
        File(workspace, "nested/shot.png").writeBytes(png)

        listOf(
            "/workspace/shot.png",
            "file:///workspace/shot.png",
            "minis://workspace/shot.png",
            "shot.png",
        ).forEach { source ->
            val payload = ChatImageBytes.readBytes(context, source)
            assertNotNull("source=$source", payload)
            assertEquals(png.toList(), payload!!.bytes.toList())
        }
        assertEquals(png.toList(), ChatImageBytes.readBytes(context, "nested/shot.png")!!.bytes.toList())
    }

    private fun tinyPng(): ByteArray = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )

    @Test
    fun collectsMarkdownImageSourcesInOrder() {
        val source = """
            see ![one](https://example.com/1.png)
            and ![two](https://example.com/2.jpg)
            skip ![bad](not-an-image)
        """.trimIndent()
        assertEquals(
            listOf("https://example.com/1.png", "https://example.com/2.jpg"),
            collectMarkdownImageSources(source),
        )
    }

    @Test
    fun previewGalleryKeepsConversationOrderAndAppendsUnknown() {
        val gallery = listOf("https://a.png", "https://b.png")
        assertEquals(gallery, previewGalleryFor("https://a.png", gallery))
        assertEquals(
            listOf("https://a.png", "https://b.png", "https://c.png"),
            previewGalleryFor("https://c.png", gallery),
        )
    }

    @Test
    fun collectPreviewableChatImagesFromUserAgentAndPending() {
        val messages = listOf(
            UserMessageUi(
                id = "u1",
                content = "hi",
                images = listOf("data:image/png;base64,aaa"),
                imageSources = listOf("content://media/1"),
            ),
            AgentMessageUi(
                id = "a1",
                content = "![shot](https://cdn.example/out.png)",
            ),
        )
        val pending = listOf(
            PendingImageUi(
                id = "p1",
                uri = "content://media/pending",
                dataUrl = "data:image/png;base64,bbb",
                mimeType = "image/png",
            ),
        )
        assertEquals(
            listOf(
                "content://media/1",
                "https://cdn.example/out.png",
                "content://media/pending",
            ),
            collectPreviewableChatImages(messages, pending),
        )
    }
}
