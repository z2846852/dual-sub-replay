package com.kienhoang.dualsubreplay.listening

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * FSRS-6 default-weight scheduler (90% target retention).
 *
 * Implements the memory-state equations (D/S/R) without random interval fuzz.
 * Short learning steps are app-defined; first-listen outcomes are user rated,
 * not independently verified by speech recognition.
 * References: open-spaced-repetition/free-spaced-repetition-scheduler and FSRS-Kotlin.
 */
internal object Fsrs6 {
    const val VERSION = "FSRS-6-default-no-fuzz"
    const val RETENTION = 0.90
    private const val DAY = 86_400_000L
    private val w = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
        0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629,
        1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )
    private val decay = -w[20]
    private val factor = 0.9.pow(1 / decay) - 1
    internal data class Memory(
        val difficulty: Double,
        val stabilityDays: Double,
        val lastReview: Long,
        val dueAt: Long,
        val reviews: Int,
        val successfulDays: Set<String> = emptySet(),
    ) {
        val established: Boolean get() = stabilityDays >= 30.0 && successfulDays.size >= 2
    }

    fun retrievability(memory: Memory, now: Long): Double {
        val elapsed = max(0.0, (now - memory.lastReview).toDouble() / DAY)
        return (1.0 + factor * elapsed / max(0.01, memory.stabilityDays)).pow(decay).coerceIn(0.0, 1.0)
    }
    private fun initDifficulty(grade: Int) = (w[4] - exp(w[5] * (grade - 1)) + 1).coerceIn(1.0, 10.0)
    private fun nextDifficulty(d: Double, grade: Int): Double {
        val delta = -w[6] * (grade - 3)
        val damped = delta * ((10.0 - d) / 9.0)
        return (w[7] * initDifficulty(4) + (1 - w[7]) * (d + damped)).coerceIn(1.0, 10.0)
    }
    private fun shortTerm(s: Double, grade: Int): Double {
        val ratio = exp(w[17] * (grade - 3 + w[18])) * s.pow(-w[19])
        return max(0.01, s * if (grade >= 3) max(1.0, ratio) else ratio)
    }
    private fun forget(d: Double, s: Double, r: Double): Double {
        val estimate = w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) *
            exp((1 - r) * w[14])
        return max(0.01, min(estimate, s / exp(w[17] * w[18])))
    }
    private fun recall(d: Double, s: Double, r: Double, grade: Int): Double {
        val hard = if (grade == 2) w[15] else 1.0
        val easy = if (grade == 4) w[16] else 1.0
        return max(0.01, s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) *
            (exp((1 - r) * w[10]) - 1) * hard * easy))
    }
    fun review(before: Memory?, grade: Int, now: Long, day: String): Memory {
        require(grade in 1..4)
        val s: Double
        val d: Double
        if (before == null) {
            s = w[grade - 1]
            d = initDifficulty(grade)
        } else {
            val r = retrievability(before, now)
            d = nextDifficulty(before.difficulty, grade)
            s = when {
                grade == 1 -> forget(d, before.stabilityDays, r)
                now - before.lastReview < DAY -> shortTerm(before.stabilityDays, grade)
                else -> recall(d, before.stabilityDays, r, grade)
            }
        }
        // On failed retrieval, keep a brief learning step before the next self-test.
        val interval = if (grade == 1) 10 * 60 * 1000L
            else if (before == null && grade == 2) 20 * 60 * 1000L
            else max(DAY, (s / factor * (RETENTION.pow(1 / decay) - 1) * DAY)
                .roundToLong())
        val days = (before?.successfulDays ?: emptySet()) + (if (grade >= 3) setOf(day) else emptySet())
        return Memory(d, s, now, now + interval, (before?.reviews ?: 0) + 1, days)
    }
}
