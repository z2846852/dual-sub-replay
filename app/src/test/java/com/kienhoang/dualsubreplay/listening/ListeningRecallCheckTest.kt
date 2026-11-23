package com.kienhoang.dualsubreplay.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningRecallCheckTest {
    @Test
    fun ignoresCasePunctuationAndSpacing() {
        val result = ListeningRecallCheck.compare("I went to the shop!", "  i   WENT to the shop. ")
        assertTrue(result.perfect)
        assertEquals(0, result.differences)
    }

    @Test
    fun articlesAreRealErrors() {
        val result = ListeningRecallCheck.compare("I went to the shop.", "i went to a shop")
        assertEquals(1, result.differences)
        assertEquals("the", result.words.single { !it.matches }.expected)
        assertEquals("a", result.words.single { !it.matches }.actual)
    }

    @Test
    fun missingWordDoesNotShiftRemainingSentence() {
        val result = ListeningRecallCheck.compare("Please put the book on the table", "Please put book on the table")
        assertEquals(1, result.differences)
        val mistake = result.words.single { !it.matches }
        assertEquals("the", mistake.expected)
        assertEquals(null, mistake.actual)
        assertTrue(result.words.last().matches)
    }

    @Test
    fun unexpectedWordIsHighlighted() {
        val result = ListeningRecallCheck.compare("She goes home", "She really goes home")
        assertEquals(1, result.differences)
        val mistake = result.words.single { !it.matches }
        assertEquals(null, mistake.expected)
        assertEquals("really", mistake.actual)
    }

    @Test
    fun tenseAndPrepositionChangesCount() {
        val result = ListeningRecallCheck.compare("He went to school", "He go from school")
        assertEquals(2, result.differences)
        assertFalse(result.perfect)
    }

    @Test
    fun repeatedWordsAlignWithoutWrongTail() {
        val result = ListeningRecallCheck.compare("I I think this works", "I think this works")
        assertEquals(1, result.differences)
        assertTrue(result.words.last().matches)
    }

    @Test
    fun normalizedApostrophesMatch() {
        val result = ListeningRecallCheck.compare("I don’t know.", "i don't know")
        assertTrue(result.perfect)
    }

    @Test
    fun emptyAnswerShowsAllMissing() {
        val result = ListeningRecallCheck.compare("Try again", "")
        assertEquals(2, result.differences)
        assertFalse(result.perfect)
    }
}
