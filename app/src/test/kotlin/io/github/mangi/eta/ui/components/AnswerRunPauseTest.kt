package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerRunPauseTest {
    @Test fun noPauseNeverReachesAnyAnswer() {
        assertFalse(answerFollowsRunPause(false, true, null, "a"))
        assertFalse(answerFollowsRunPause(false, false, "a", "a"))
    }

    @Test fun liveOrUnrevealedAnswersFollowTheRunPause() {
        assertTrue(answerFollowsRunPause(true, true, null, "partial"))
        assertTrue(answerFollowsRunPause(true, true, "partial", "partial"))
        assertTrue(answerFollowsRunPause(true, false, null, "done"))
        assertTrue(answerFollowsRunPause(true, false, "don", "done"))
    }

    @Test fun exactlyRevealedFinishedAnswerIgnoresAnotherRowsPause() {
        assertFalse(answerFollowsRunPause(true, false, "done", "done"))
    }
}
