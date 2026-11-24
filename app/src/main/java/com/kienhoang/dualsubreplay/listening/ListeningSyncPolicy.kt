package com.kienhoang.dualsubreplay.listening

/**
 * Locally captured attempts are data, not automatically approved GitHub uploads.
 * Curated learner-marked insights have their own remote collection.
 */
internal object ListeningSyncPolicy {
    fun mayUpload(synced: Boolean, excluded: Boolean): Boolean = !synced && !excluded

    fun approved(id: String, approvedIds: Set<String>): Boolean = id in approvedIds

    fun remoteCollection(type: String): String =
        if (type == "recall_insight") "insights" else "events"
}
