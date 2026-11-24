package com.kienhoang.dualsubreplay.listening

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningPackSortTest {
    private fun pack(id: String, level: String, title: String = id) =
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
}
