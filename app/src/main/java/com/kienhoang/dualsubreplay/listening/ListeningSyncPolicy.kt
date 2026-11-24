package com.kienhoang.dualsubreplay.listening

/** Raw attempts are retained; the reviewed upload only sends the IDs approved at confirmation. */
internal object ListeningSyncPolicy {
    fun mayUpload(synced: Boolean): Boolean = !synced

    fun approved(id: String, approvedIds: Set<String>): Boolean = id in approvedIds

    fun remoteCollection(type: String): String =
        if (type == "recall_insight") "insights" else "events"

    fun eventMonth(createdAt: String): String? =
        createdAt.take(7).takeIf { it.matches(Regex("\\d{4}-\\d{2}")) }

    fun validDeviceId(id: String): Boolean = id.matches(Regex("[0-9a-fA-F-]{36}"))

    fun errorKey(eventId: String, expectedWord: String): String =
        eventId + ":" + expectedWord.lowercase(java.util.Locale.ROOT)
}
