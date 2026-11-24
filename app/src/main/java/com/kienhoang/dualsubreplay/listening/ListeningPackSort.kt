package com.kienhoang.dualsubreplay.listening

internal enum class ListeningPackSort(val key: String) {
    RECENT("recent"),
    LEVEL_ASC("level_asc"),
    LEVEL_DESC("level_desc"),
    TITLE("title"),
    ;

    companion object {
        fun fromKey(key: String): ListeningPackSort = entries.firstOrNull { it.key == key } ?: RECENT
    }
}

/** Unknown levels are placed last; equal levels preserve the user's manifest order. */
internal fun listeningLevelRank(level: String): Int =
    when (level.trim().uppercase().substringBefore('-')) {
        "A1" -> 1
        "A2" -> 2
        "B1" -> 3
        "B2" -> 4
        "C1" -> 5
        "C2" -> 6
        else -> 99
    }

internal fun sortListeningPacks(
    packs: List<ListeningPack>,
    sort: ListeningPackSort,
    recentUse: Map<String, Long>,
): List<ListeningPack> =
    when (sort) {
        ListeningPackSort.RECENT -> packs.sortedByDescending { recentUse[it.id] ?: 0L }
        ListeningPackSort.LEVEL_ASC -> packs.sortedBy { listeningLevelRank(it.level) }
        ListeningPackSort.LEVEL_DESC ->
            packs.sortedWith(
                compareBy<ListeningPack> { listeningLevelRank(it.level) == 99 }
                    .thenByDescending { listeningLevelRank(it.level) },
            )
        ListeningPackSort.TITLE -> packs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

/** No incorrect transcription produces an automatic failed attempt or uncovers the answer. */
internal fun shouldAutoPassRecall(
    answer: String,
    original: String,
    playing: Boolean,
    alreadyPassed: Boolean,
): Boolean =
    !playing && !alreadyPassed && answer.isNotBlank() &&
        ListeningRecallCheck.compare(original, answer).perfect

/** Auto advance never wraps to the beginning of a pack, including wrong-only practice. */
internal fun nextAutomaticRecallIndex(
    current: Int,
    last: Int,
    wrongOnly: Boolean,
    remainingWrong: List<Int>,
): Int? {
    if (current >= last) return null
    return if (wrongOnly) remainingWrong.firstOrNull { it > current } else current + 1
}
