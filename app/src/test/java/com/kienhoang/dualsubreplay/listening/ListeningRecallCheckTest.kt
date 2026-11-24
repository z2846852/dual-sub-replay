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
    fun thereIsAndTheresMatchInBothDirections() {
        assertTrue(ListeningRecallCheck.compare("There is a good coffee shop", "There's a good coffee shop").perfect)
        assertTrue(ListeningRecallCheck.compare("There's a good coffee shop", "There is a good coffee shop").perfect)
        assertTrue(ListeningRecallCheck.compare("There’s a good coffee shop", "There is a good coffee shop").perfect)
    }

    @Test
    fun commonContractionsMatchExpandedSpeech() {
        assertTrue(ListeningRecallCheck.compare("I am sure you are right", "I'm sure you're right").perfect)
        assertTrue(ListeningRecallCheck.compare("We will go if it is ready", "We'll go if it's ready").perfect)
        assertTrue(ListeningRecallCheck.compare("Do not worry, I cannot stay", "Don't worry I can't stay").perfect)
        assertTrue(ListeningRecallCheck.compare("I would like to try", "I'd like to try").perfect)
    }

    @Test
    fun contractionMatchesDoNotHideOtherWrongWords() {
        val result = ListeningRecallCheck.compare("There is a coffee shop", "There's the coffee shop")
        assertFalse(result.perfect)
        assertEquals(1, result.differences)
        assertEquals("a", result.words.single { !it.matches }.expected)
        assertEquals("the", result.words.single { !it.matches }.actual)
    }

    @Test
    fun possessionAndTenseAreNotFalselyAccepted() {
        assertFalse(ListeningRecallCheck.compare("Its door is open", "It's door is open").perfect)
        assertFalse(ListeningRecallCheck.compare("There was a shop", "There's a shop").perfect)
        assertFalse(ListeningRecallCheck.compare("He has a car", "He is a car").perfect)
    }

    @Test
    fun emptyAnswerShowsAllMissing() {
        val result = ListeningRecallCheck.compare("Try again", "")
        assertEquals(2, result.differences)
        assertFalse(result.perfect)
    }
}
