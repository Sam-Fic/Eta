package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.markdown.StreamingGfmParserSession
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothTextRevealPolicyTest {
    @Test
    fun revealPathsAppendBatchesButRebuildForNewLayoutsOrRewinds() {
        assertEquals(true, canAppendRevealPath(true, 10, 11))
        assertEquals(true, canAppendRevealPath(true, 10, 30))
        assertEquals(true, canAppendRevealPath(true, 0, 4))
        assertEquals(false, canAppendRevealPath(false, 10, 30))
        assertEquals(false, canAppendRevealPath(true, -1, 4))
        assertEquals(false, canAppendRevealPath(true, 10, 10))
        assertEquals(false, canAppendRevealPath(true, 10, 9))
    }

    @Test
    fun coordinatorTracksBackgroundAnimationSuspension() {
        val coordinator = SmoothTextRevealCoordinator()

        assertEquals(false, coordinator.isAnimationPaused)
        coordinator.pauseAnimationsAndCatchUp()
        assertEquals(true, coordinator.isAnimationPaused)
        coordinator.resumeAnimationsAfterCatchUp()
        assertEquals(false, coordinator.isAnimationPaused)
        coordinator.pauseAnimationsAndCatchUp()
        coordinator.resumeAnimationsWithoutCatchingUp()
        assertEquals(false, coordinator.isAnimationPaused)
    }

    @Test
    fun emptyAndOrdinaryTextExposeEveryGraphemeBoundary() {
        assertArrayEquals(intArrayOf(0), graphemeBoundaries(""))
        assertArrayEquals(intArrayOf(0, 1, 2, 3), graphemeBoundaries("A中B"))
    }

    @Test
    fun emojiSurrogatePairIsOneGrapheme() {
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("A😀B"),
        )
    }

    @Test
    fun extendedEmojiSequencesAreNeverSplit() {
        assertArrayEquals(
            intArrayOf(0, 1, 12, 13),
            graphemeBoundaries("A👨‍👩‍👧‍👦B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 5, 6),
            graphemeBoundaries("A👍🏽B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 5, 6),
            graphemeBoundaries("A🇨🇳B"),
        )
    }

    @Test
    fun combiningMarkAndCrLfStayInOneGrapheme() {
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("Ae\u0301B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("A\r\nB"),
        )
    }

    @Test
    fun appendedTextOnlyRebuildsTheLastPotentiallyExtendedGrapheme() {
        val familyPrefix = "A👨‍👩"
        val prefixBoundaries = graphemeBoundaries(familyPrefix)
        val family = "$familyPrefix‍👧‍👦B"

        assertArrayEquals(
            graphemeBoundaries(family),
            updateGraphemeBoundaries(familyPrefix, prefixBoundaries, family),
        )
        assertArrayEquals(
            graphemeBoundaries("A\r\nB"),
            updateGraphemeBoundaries("A\r", graphemeBoundaries("A\r"), "A\r\nB"),
        )
    }

    @Test
    fun nonAppendReplacementFallsBackToACompleteBoundaryScan() {
        val previous = "alpha 😀"
        val replacement = "beta 👨‍👩‍👧‍👦"

        assertArrayEquals(
            graphemeBoundaries(replacement),
            updateGraphemeBoundaries(previous, graphemeBoundaries(previous), replacement),
        )
    }

    @Test
    fun commonPrefixNeverEndsInsideAChangedSurrogatePair() {
        assertEquals(0, commonUtf16PrefixLength("😀 alpha", "😁 beta"))
        assertEquals(3, commonUtf16PrefixLength("A😀x", "A😀y"))
        assertEquals(0, commonUtf16PrefixLength("first", "second"))
    }

    @Test
    fun changedExtendedGraphemeSnapsPreservedPrefixToPreviousBoundary() {
        val previous = "👨‍👩X"
        val replacement = "👨‍👧Y"
        val commonPrefixEnd = commonUtf16PrefixLength(previous, replacement)
        val preservedBoundary = graphemeBoundaries(replacement)
            .last { boundary -> boundary <= commonPrefixEnd }

        assertEquals(3, commonPrefixEnd)
        assertEquals(0, preservedBoundary)
    }

    @Test
    fun markdownBatchEndsOnlyAfterCompleteGraphemes() {
        val content = "A👨‍👩‍👧‍👦中B"

        assertEquals(12, streamingMarkdownBatchEnd(content, start = 0, maxGraphemes = 2))
        assertEquals(13, streamingMarkdownBatchEnd(content, start = 12, maxGraphemes = 1))
        assertEquals(content.length, streamingMarkdownBatchEnd(content, start = 13, maxGraphemes = 8))
    }

    @Test
    fun markdownBatchCompletesGraphemeExtendedAcrossPreviousChunk() {
        val content = "A\u0301B"

        assertEquals(2, streamingMarkdownBatchEnd(content, start = 1, maxGraphemes = 1))
        assertEquals(0, streamingMarkdownBatchEnd(content, start = -2, maxGraphemes = 0))
        assertEquals(content.length, streamingMarkdownBatchEnd(content, start = 99, maxGraphemes = 4))
    }

    @Test
    fun markdownBatchSizeCatchesUpWithoutFloodingAFrame() {
        assertEquals(24, streamingMarkdownBatchSize(backlogChars = 1))
        assertEquals(40, streamingMarkdownBatchSize(backlogChars = 64))
        assertEquals(64, streamingMarkdownBatchSize(backlogChars = 160))
        assertEquals(96, streamingMarkdownBatchSize(backlogChars = 384))
    }

    @Test
    fun markdownDocumentCollapsesSourceBlankLinesIntoSemanticBlocks() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "第一段\n\n\n第二段",
            isComplete = true,
        )

        assertEquals(
            listOf(MarkdownElementTypes.PARAGRAPH, MarkdownElementTypes.PARAGRAPH),
            topLevelMarkdownBlocks(snapshot.state.node).map { node -> node.type },
        )
    }

    @Test
    fun markdownBlockSpacingBuildsReadableHierarchyWithoutLeadingGap() {
        assertEquals(0.sp, markdownBlockSpacing(null, MarkdownElementTypes.PARAGRAPH))
        assertEquals(
            16.sp,
            markdownBlockSpacing(MarkdownElementTypes.PARAGRAPH, MarkdownElementTypes.PARAGRAPH),
        )
        assertEquals(
            24.sp,
            markdownBlockSpacing(MarkdownElementTypes.PARAGRAPH, MarkdownElementTypes.ATX_2),
        )
        assertEquals(
            10.sp,
            markdownBlockSpacing(MarkdownElementTypes.ATX_2, MarkdownElementTypes.PARAGRAPH),
        )
        assertEquals(
            16.sp,
            markdownBlockSpacing(MarkdownElementTypes.PARAGRAPH, MarkdownElementTypes.UNORDERED_LIST),
        )
        assertEquals(
            16.sp,
            markdownBlockSpacing(GFMElementTypes.TABLE, MarkdownElementTypes.PARAGRAPH),
        )
    }

    @Test
    fun streamingListMarkerWaitsForItsOwnContentToStart() {
        val currentItem = RevealBlockKey(10)

        assertEquals(
            false,
            streamingListMarkerVisible(
                coordinatorActive = true,
                firstRevealKey = currentItem,
                startedRevealKeys = emptySet(),
                containsImage = false,
            ),
        )
        assertEquals(
            true,
            streamingListMarkerVisible(
                coordinatorActive = true,
                firstRevealKey = currentItem,
                startedRevealKeys = setOf(currentItem),
                containsImage = false,
            ),
        )
    }

    @Test
    fun streamingListMarkerKeepsImageItemsVisibleAndSuppressesEmptyItems() {
        assertEquals(
            true,
            streamingListMarkerVisible(
                coordinatorActive = true,
                firstRevealKey = null,
                startedRevealKeys = emptySet(),
                containsImage = true,
            ),
        )
        assertEquals(
            false,
            streamingListMarkerVisible(
                coordinatorActive = true,
                firstRevealKey = null,
                startedRevealKeys = emptySet(),
                containsImage = false,
            ),
        )
    }

    @Test
    fun streamingMarkdownHidesFutureBlocksUntilTheirRevealStarts() {
        val current = RevealBlockKey(0)
        val next = RevealBlockKey(40)
        assertEquals(
            true,
            streamingMarkdownBlockVisible(
                coordinatorActive = true,
                firstRevealKey = current,
                startedRevealKeys = setOf(current),
                nextRevealKey = next,
            ),
        )
        assertEquals(
            true,
            streamingMarkdownBlockVisible(
                coordinatorActive = true,
                firstRevealKey = next,
                startedRevealKeys = setOf(current),
                nextRevealKey = next,
            ),
        )
        assertEquals(
            false,
            streamingMarkdownBlockVisible(
                coordinatorActive = true,
                firstRevealKey = RevealBlockKey(80),
                startedRevealKeys = setOf(current),
                nextRevealKey = next,
            ),
        )
        assertEquals(
            true,
            streamingMarkdownBlockVisible(
                coordinatorActive = false,
                firstRevealKey = RevealBlockKey(80),
                startedRevealKeys = emptySet(),
                nextRevealKey = next,
            ),
        )
    }

    @Test
    fun completedBlocksFreezeOnceANewTailExists() {
        assertTrue(shouldFreezeStreamingMarkdownBlock(0, 40))
        assertFalse(shouldFreezeStreamingMarkdownBlock(40, 40))
        assertFalse(shouldFreezeStreamingMarkdownBlock(0, null))
    }

}
