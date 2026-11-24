package com.kienhoang.dualsubreplay.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListeningLearningMetricsTest {
    @Test fun noEvidenceDoesNotInventScoresOrRaiseLevel() {
        val m = ListeningLearningMetrics(0, 0, 0, 0, 0)
        assertNull(m.firstPassRate)
        assertNull(m.delayedRate)
        assertEquals("A2", m.nextLevel("A2"))
    }
    @Test fun recommendationNeedsAtLeastFiveIndependentAttempts() {
        assertEquals("A2", ListeningLearningMetrics(4, 4, 0, 0, 0).nextLevel("A2"))
        assertEquals("B1", ListeningLearningMetrics(5, 4, 0, 0, 0).nextLevel("A2"))
        assertEquals("A1", ListeningLearningMetrics(5, 1, 0, 0, 0).nextLevel("A2"))
        assertEquals("A2", ListeningLearningMetrics(5, 3, 0, 0, 0).nextLevel("A2"))
    }
    @Test fun retentionAndExpressiveCountsRemainDistinct() {
        val m = ListeningLearningMetrics(10, 7, 3, 2, 4)
        assertEquals(70, m.firstPassRate)
        assertEquals(66, m.delayedRate)
        assertEquals(4, m.freeExpressions)
    }
}
