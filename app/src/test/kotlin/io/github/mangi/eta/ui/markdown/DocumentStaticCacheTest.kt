package io.github.mangi.eta.ui.markdown

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class DocumentStaticCacheTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completedStreamIsAvailableOnFirstStaticCompositionAndRemount() = runBlocking {
        val state = DocumentStreamingState()
        val source = "Static handoff **prepared** answer"
        val style = MarkdownInlineStyle.Default
        val worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        try {
            state.submit(source, false, style)
            val snapshot = withTimeout(5_000) {
                while (state.snapshot == null) delay(1)
                checkNotNull(state.snapshot)
            }
            state.acknowledgeLayout(snapshot)
            assertTrue(state.markComplete(snapshot))
            val mounted = mutableStateOf(true)
            val seen = mutableListOf<MarkdownDocument?>()
            compose.setContent {
                if (mounted.value) {
                    val document by rememberStaticMarkdownDocument(source, style)
                    SideEffect { seen += document }
                }
            }
            compose.runOnIdle {
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it === snapshot.document })
                mounted.value = false
            }
            compose.runOnIdle { seen.clear(); mounted.value = true }
            compose.runOnIdle {
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it === snapshot.document })
            }
        } finally { worker.cancelAndJoin() }
    }

    @Test fun contentAndStyleChangesNeverExposePreviousDocument() {
        val firstSource = "First cached **document**"
        val secondSource = "Second cached **document**"
        val coldSource = "Uncached third **document**"
        val style = MarkdownInlineStyle.Default
        val changedStyle = style.copy(linkColor = Color.Red)
        val first = DocumentGfmParserSession().parse(firstSource, true, style).document
        val second = DocumentGfmParserSession().parse(secondSource, true, style).document
        val themed = DocumentGfmParserSession().parse(secondSource, true, changedStyle).document
        DocumentCache.put(firstSource, style, first)
        DocumentCache.put(secondSource, style, second)
        DocumentCache.put(secondSource, changedStyle, themed)
        val target = mutableStateOf(firstSource to style)
        val seen = mutableListOf<MarkdownDocument?>()
        compose.setContent {
            val (source, inline) = target.value
            val document by rememberStaticMarkdownDocument(source, inline)
            SideEffect { seen += document }
        }
        compose.runOnIdle {
            assertSame(first, seen.first())
            seen.clear(); target.value = secondSource to style
        }
        compose.runOnIdle {
            assertSame(second, seen.first())
            assertTrue(seen.all { it === second })
            seen.clear(); target.value = secondSource to changedStyle
        }
        compose.runOnIdle {
            assertSame(themed, seen.first())
            assertTrue(seen.all { it === themed })
            seen.clear(); target.value = coldSource to changedStyle
        }
        compose.waitUntil(5_000) { seen.any { it != null } }
        compose.runOnIdle {
            assertTrue(seen.none { it === first || it === second || it === themed })
            val parsed = checkNotNull(seen.last())
            assertEquals(changedStyle, parsed.inlineStyle)
            assertEquals(DocumentGfmParserSession().parse(coldSource, true, changedStyle).document.blocks,
                parsed.blocks)
        }
    }

    @Test fun rawCitationSourceHitsTheCompletedCleanDocument() {
        val raw = "Citation handoff [[1]](https://example.com) **body**"
        val clean = NumericCitationMarkup.strip(raw)
        assertNotEquals(raw, clean)
        val style = MarkdownInlineStyle.Default
        val prepared = DocumentGfmParserSession().parse(clean, true, style).document
        DocumentCache.put(clean, style, prepared)
        val seen = mutableListOf<MarkdownDocument?>()
        compose.setContent {
            val document by rememberStaticMarkdownDocument(raw, style)
            SideEffect { seen += document }
        }
        compose.runOnIdle {
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it === prepared })
        }
    }

    @Test fun staticCacheStillEvictsLeastRecentlyUsedEntriesAt96Documents() {
        val style = MarkdownInlineStyle.Default
        val document = DocumentGfmParserSession().parse("Cache capacity", true, style).document
        repeat(96) { DocumentCache.put("capacity-test-$it", style, document) }
        assertSame(document, DocumentCache.get("capacity-test-0", style))
        DocumentCache.put("capacity-test-96", style, document)
        assertSame(document, DocumentCache.get("capacity-test-0", style))
        assertNull(DocumentCache.get("capacity-test-1", style))
        assertSame(document, DocumentCache.get("capacity-test-96", style))
    }
}
