package com.kienhoang.dualsubreplay.listening

internal data class ListeningLearningMetrics(
    val firstIndependentChecks: Int,
    val firstIndependentPasses: Int,
    val delayedChecks: Int,
    val delayedPasses: Int,
    val freeExpressions: Int,
) {
    val firstPassRate: Int?
        get() = if (firstIndependentChecks > 0) firstIndependentPasses * 100 / firstIndependentChecks else null
    val delayedRate: Int?
        get() = if (delayedChecks > 0) delayedPasses * 100 / delayedChecks else null

    /** Fewer than five independent checks is not enough evidence to change difficulty. */
    fun nextLevel(currentLevel: String): String {
        val levels = listOf("A1", "A2", "B1", "B2", "C1", "C2")
        val i = levels.indexOf(currentLevel.uppercase())
        if (i < 0 || firstIndependentChecks < 5) return currentLevel
        val rate = firstPassRate ?: return currentLevel
        return levels[(i + when { rate >= 80 -> 1; rate < 40 -> -1; else -> 0 }).coerceIn(levels.indices)]
    }
}

internal data class ListeningRetest(
    val pack: ListeningPack,
    val lineIndex: Int,
    val dueOn: String,
    val stage: Int,
)
