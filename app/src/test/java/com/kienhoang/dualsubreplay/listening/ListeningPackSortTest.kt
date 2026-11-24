package com.kienhoang.dualsubreplay.listening

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningPackSortTest {
    private fun pack(
        id: String,
        level: String,
        title: String = id,
    ) =
        ListeningPack(id, title, level, listOf(ListeningLine("1", "A", "Hello", "")))

    @Test
    fun recentSortDefaultsToManifestOrderAndUsesMostRecentOpenFirst() {
        val packs = listOf(pack("first", "B1"), pack("second", "A2"), pack("third", "C1"))
        assertEquals(packs, sortListeningPacks(packs, ListeningPackSort.RECENT, emptyMap()))
        assertEquals(
            listOf("third", "first", "second"),
            sortListeningPacks(packs, ListeningPackSort.RECENT, mapOf("third" to 300L, "first" to 200L))
                .map { it.id },
        )
    }

    @Test
    fun levelsCanSortBothWays() {
        val packs = listOf(pack("b", "B1"), pack("c", "C1"), pack("a", "A1"))
        assertEquals(listOf("a", "b", "c"), sortListeningPacks(packs, ListeningPackSort.LEVEL_ASC, emptyMap()).map { it.id })
        assertEquals(listOf("c", "b", "a"), sortListeningPacks(packs, ListeningPackSort.LEVEL_DESC, emptyMap()).map { it.id })
    }

    @Test
    fun sortKeySurvivesUnknownPreferences() {
        assertEquals(ListeningPackSort.RECENT, ListeningPackSort.fromKey("garbage"))
        assertEquals(ListeningPackSort.LEVEL_ASC, ListeningPackSort.fromKey("level_asc"))
    }

    @Test
    fun autoPassRequiresExactWordsAndNotAlreadyPassed() {
        assertEquals(true, shouldAutoPassRecall(" EXCUSE me!", "Excuse me", playing = false, alreadyPassed = false))
        assertEquals(false, shouldAutoPassRecall("Excuse", "Excuse me", playing = false, alreadyPassed = false))
        assertEquals(false, shouldAutoPassRecall("Excuse me", "Excuse me", playing = true, alreadyPassed = false))
        assertEquals(false, shouldAutoPassRecall("Excuse me", "Excuse me", playing = false, alreadyPassed = true))
    }

    @Test
    fun autoNextStopsAtLastLineAndDoesNotWrapWrongOnlyQueue() {
        assertEquals(1, nextAutomaticRecallIndex(0, 13, wrongOnly = false, remainingWrong = emptyList()))
        assertEquals(6, nextAutomaticRecallIndex(2, 13, wrongOnly = true, remainingWrong = listOf(1, 6, 8)))
        assertEquals(null, nextAutomaticRecallIndex(8, 13, wrongOnly = true, remainingWrong = listOf(1, 6)))
        assertEquals(null, nextAutomaticRecallIndex(13, 13, wrongOnly = false, remainingWrong = emptyList()))
    }
}
