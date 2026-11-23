package com.kienhoang.dualsubreplay.listening

/**
 * A sentence status is the latest checked result, not evidence of pronunciation quality.
 * Corrections to keyboard-transcribed text can be rechecked without adding a listening attempt.
 */
internal data class RecallLineProgress(
    val attempts: Int = 0,
    val passed: Boolean = false,
    val firstTryPassed: Boolean = false,
    val hintLevel: Int = 0,
    val firstAnswer: String = "",
    val lastAnswer: String = "",
    val draft: String? = null,
    val corrections: Int = 0,
    val wrongWords: List<String> = emptyList(),
) {
    val failed: Boolean get() = attempts > 0 && !passed

    fun check(
        answer: String,
        comparison: RecallComparison,
        recognitionCorrection: Boolean,
    ): RecallLineProgress {
        val correction = recognitionCorrection && attempts > 0
        val newAttempts = if (correction) attempts else attempts + 1
        val words =
            comparison.words
                .filterNot { it.matches }
                .mapNotNull { it.expected ?: it.actual }
                .take(20)
        return copy(
            attempts = newAttempts,
            passed = comparison.perfect,
            firstTryPassed = firstTryPassed ||
                (attempts == 0 && comparison.perfect && hintLevel == 0 && !correction),
            firstAnswer = if (attempts == 0) answer else firstAnswer,
            lastAnswer = answer,
            draft = answer,
            corrections = corrections + if (correction) 1 else 0,
            wrongWords = words,
        )
    }

    fun hint(level: Int): RecallLineProgress = copy(hintLevel = maxOf(hintLevel, level.coerceIn(0, 2)))

    fun mayShowRedOnly(): Boolean = attempts >= 2 && failed
}
