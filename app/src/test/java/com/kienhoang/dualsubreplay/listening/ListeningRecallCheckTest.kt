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
    fun spokenNumbersAndDigitsCompareEquallyWithoutHidingActualMistakes() {
        assertTrue(ListeningRecallCheck.compare("Only about five minutes", "Only about 5 minutes").perfect)
        assertTrue(ListeningRecallCheck.compare("It takes sixty seconds", "It takes 60 seconds").perfect)
        assertFalse(ListeningRecallCheck.compare("Only about five minutes", "Only about 6 minutes").perfect)
        assertFalse(ListeningRecallCheck.compare("Take two bags", "Take the bags").perfect)
    }

    @Test
    fun sitcomMissingNameAndSlangAreYellowEligible() {
        val comparison = ListeningRecallCheck.compare(
            "Max, you are freaking amazing, Caroline!",
            "You are amazing!",
            ignoreOptional = true,
        )
        assertFalse(comparison.perfect)
        assertTrue(comparison.softPass)
        assertEquals(3, comparison.optionalOmissions.size)
        assertEquals(setOf("Max", "freaking", "Caroline"), comparison.optionalOmissions.toSet())
        assertEquals(0, comparison.blockingDifferences)
    }

    @Test
    fun ordinaryContentDoesNotWaiveTheSameNames() {
        val result = ListeningRecallCheck.compare(
            "Max, you are freaking amazing!",
            "You are amazing!",
        )
        assertFalse(result.softPass)
        assertFalse(result.perfect)
        assertTrue(result.blockingDifferences > 0)
    }

    @Test
    fun sitcomRealErrorsStillBlockPassing() {
        val result = ListeningRecallCheck.compare(
            "Max, you are freaking amazing!",
            "You is amazing!",
            ignoreOptional = true,
        )
        assertFalse(result.perfect)
        assertFalse(result.softPass)
        assertTrue(result.blockingDifferences > 0)
    }

    @Test
    fun slangNeverMakesEmptyAnswerOrAllOptionalSentencePass() {
        val empty = ListeningRecallCheck.compare("Max is here", "", ignoreOptional = true)
        assertFalse(empty.softPass)
        val allIgnored = ListeningRecallCheck.compare("Max, Caroline!", "", ignoreOptional = true)
        assertFalse(allIgnored.softPass)
    }

    @Test
    fun commonUsefulColloquialismsRemainMandatory() {
        val result = ListeningRecallCheck.compare(
            "I'm gonna help Max",
            "I am help",
            ignoreOptional = true,
        )
        assertFalse(result.softPass)
        assertTrue(result.blockingDifferences > 0)
    }

    @Test
    fun replacingANameWithAnotherWordIsNotAnOptionalOmission() {
        val result = ListeningRecallCheck.compare(
            "Max is coming",
            "Alex is coming",
            ignoreOptional = true,
        )
        assertFalse(result.softPass)
        assertTrue(result.blockingDifferences > 0)
    }

    @Test
    fun dialectContractionsAreKeptWholeAndCanBeOmittedInSitcom() {
        val result = ListeningRecallCheck.compare(
            "Y'all, Max ain't wrong!",
            "Max wrong!",
            ignoreOptional = true,
        )
        assertTrue(result.softPass)
        assertEquals(setOf("Y'all", "ain't"), result.optionalOmissions.toSet())
    }

    @Test
    fun emptyAnswerShowsAllMissing() {
        val result = ListeningRecallCheck.compare("Try again", "")
        assertEquals(2, result.differences)
        assertFalse(result.perfect)
    }
}
