package com.kienhoang.dualsubreplay.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningSyncPolicyTest {
    @Test
    fun allUnsentRawAttemptsAreKeptForLaterDataCleaning() {
        assertTrue(ListeningSyncPolicy.mayUpload(synced = false))
        assertFalse(ListeningSyncPolicy.mayUpload(synced = true))
    }

    @Test
    fun onlyTheIdsExplicitlyReviewedCanUpload() {
        val approved = setOf("a", "c")
        assertTrue(ListeningSyncPolicy.approved("a", approved))
        assertFalse(ListeningSyncPolicy.approved("b", approved))
        assertFalse(ListeningSyncPolicy.approved("a", emptySet()))
    }

    @Test
    fun handpickedInsightsAndAutomaticChecksHaveSeparateCollections() {
        assertEquals("insights", ListeningSyncPolicy.remoteCollection("recall_insight"))
        assertEquals("events", ListeningSyncPolicy.remoteCollection("recall_checked"))
        assertEquals("events", ListeningSyncPolicy.remoteCollection("saved"))
    }

    @Test
    fun rawNotesUseOneValidatedUuidFilePerDate() {
        val path = ListeningSyncPolicy.rawPath("2026-11-24", "123e4567-e89b-12d3-a456-426614174000")
        assertEquals(
            "data/raw_inbox/2026-11-24/123e4567-e89b-12d3-a456-426614174000.json",
            path,
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ListeningSyncPolicy.rawPath("../secret", "123e4567-e89b-12d3-a456-426614174000")
        }
    }

    @Test
    fun errorMarkingIsCaseInsensitiveButIsSpecificToTheAttempt() {
        assertEquals(
            ListeningSyncPolicy.errorKey("a", "Across"),
            ListeningSyncPolicy.errorKey("a", "across"),
        )
        assertFalse(
            ListeningSyncPolicy.errorKey("a", "across") == ListeningSyncPolicy.errorKey("b", "across"),
        )
    }
}
