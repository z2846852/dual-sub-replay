package com.kienhoang.dualsubreplay.listening

import org.junit.Assert.*
import org.junit.Test

/** Formula-level regressions; these tests need no Android device or GitHub credentials. */
class Fsrs6Test {
    private val day = 86_400_000L

    @Test fun newCardsGetGradesAndDueTimestamps() {
        val start = 1_700_000_000_000L
        val again = Fsrs6.review(null, 1, start, "2026-11-22")
        val easy = Fsrs6.review(null, 4, start, "2026-11-22")
        assertTrue(easy.stabilityDays > again.stabilityDays)
        assertEquals(start + 600_000L, again.dueAt)
        assertTrue(easy.dueAt > again.dueAt)
        assertEquals("FSRS-6-default-no-fuzz", Fsrs6.VERSION)
    }

    @Test fun retrievabilityDeclinesWithTime() {
        val now = 1_700_000_000_000L
        val card = Fsrs6.review(null, 3, now, "2026-11-22")
        val afterDay = Fsrs6.retrievability(card, now + day)
        val afterWeek = Fsrs6.retrievability(card, now + 7 * day)
        assertTrue(afterWeek < afterDay)
        assertTrue(afterDay in 0.0..1.0)
    }

    @Test fun independentSuccessDaysAreDistinct() {
        val now = 1_700_000_000_000L
        val first = Fsrs6.review(null, 3, now, "2026-11-22")
        val second = Fsrs6.review(first, 4, now + 3 * day, "2026-11-25")
        assertEquals(2, second.successfulDays.size)
        assertFalse(second.established && second.stabilityDays < 30.0)
    }

    @Test fun failedRecallStartsShortRelearning() {
        val now = 1_700_000_000_000L
        val first = Fsrs6.review(null, 4, now, "2026-11-22")
        val forgotten = Fsrs6.review(first, 1, now + 30 * day, "2026-12-22")
        assertEquals(now + 30 * day + 600_000L, forgotten.dueAt)
        assertTrue(forgotten.difficulty in 1.0..10.0)
    }
}
