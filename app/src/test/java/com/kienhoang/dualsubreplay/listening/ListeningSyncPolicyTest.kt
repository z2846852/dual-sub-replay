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
