package com.kienhoang.dualsubreplay.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningRecallProgressTest {
    @Test
    fun firstFailureUnlocksChineseMeaningHint() {
        val comparison = ListeningRecallCheck.compare("the shop", "a shop")
        val progress = RecallLineProgress().check("a shop", comparison, recognitionCorrection = false)
        assertEquals(1, progress.attempts)
        assertTrue(progress.failed)
        assertTrue(progress.mayShowMeaning())
        assertEquals("a shop", progress.firstAnswer)
        assertEquals(listOf("the"), progress.wrongWords)
    }

    @Test
    fun secondFailureKeepsChineseHintAvailableButDoesNotRevealAnswer() {
        val comparison = ListeningRecallCheck.compare("the shop", "a shop")
        val first = RecallLineProgress().check("a shop", comparison, recognitionCorrection = false)
        val second = first.check("a shop", comparison, recognitionCorrection = false)
        assertTrue(second.mayShowMeaning())
        assertEquals(0, second.hintLevel)
        assertEquals(2, second.attempts)
        assertEquals(1, second.hint(1).hintLevel)
        assertEquals(2, second.hint(2).hintLevel)
        assertFalse(RecallLineProgress().mayShowMeaning())
    }

    @Test
    fun correctLaterClearsRedStatusAndRemembersFirstTryFailure() {
        val wrong = ListeningRecallCheck.compare("the shop", "a shop")
        val right = ListeningRecallCheck.compare("the shop", "the shop")
        val result =
            RecallLineProgress()
                .check("a shop", wrong, recognitionCorrection = false)
                .check("the shop", right, recognitionCorrection = false)
        assertTrue(result.passed)
        assertFalse(result.failed)
        assertFalse(result.firstTryPassed)
        assertEquals(2, result.attempts)
    }

    @Test
    fun independentFirstTryIsRememberedEvenAfterLaterMistake() {
        val correct = ListeningRecallCheck.compare("She went home", "she went home")
        val wrong = ListeningRecallCheck.compare("She went home", "she goes home")
        val result =
            RecallLineProgress()
                .check("she went home", correct, recognitionCorrection = false)
                .check("she goes home", wrong, recognitionCorrection = false)
        assertTrue(result.firstTryPassed)
        assertTrue(result.failed)
    }

    @Test
    fun recognitionCorrectionDoesNotAddAttemptAndKeepsOriginalInput() {
        val wrong = ListeningRecallCheck.compare("the shop", "a shop")
        val right = ListeningRecallCheck.compare("the shop", "the shop")
        val result =
            RecallLineProgress()
                .check("a shop", wrong, recognitionCorrection = false)
                .check("the shop", right, recognitionCorrection = true)
        assertEquals(1, result.attempts)
        assertEquals(1, result.corrections)
        assertEquals("a shop", result.firstAnswer)
        assertEquals("the shop", result.lastAnswer)
        assertFalse(result.firstTryPassed)
    }

    @Test
    fun hintBeforeFirstCorrectCheckPreventsIndependentFirstTry() {
        val correct = ListeningRecallCheck.compare("the shop", "the shop")
        val result = RecallLineProgress().hint(2).check("the shop", correct, recognitionCorrection = false)
        assertTrue(result.passed)
        assertFalse(result.firstTryPassed)
    }
}
