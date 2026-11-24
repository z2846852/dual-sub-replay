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
        ListeningPackSort.LEVEL_DESC -> packs.sortedBy { -listeningLevelRank(it.level).coerceAtMost(6) }
        ListeningPackSort.TITLE -> packs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
