package com.kienhoang.dualsubreplay.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningSyncPolicyTest {
    @Test
    fun excludedAndAlreadyUploadedRecordsDoNotEnterManualUpload() {
        assertTrue(ListeningSyncPolicy.mayUpload(synced = false, excluded = false))
        assertFalse(ListeningSyncPolicy.mayUpload(synced = false, excluded = true))
        assertFalse(ListeningSyncPolicy.mayUpload(synced = true, excluded = false))
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
}
