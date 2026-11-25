package com.kienhoang.dualsubreplay.listening

import android.content.Context
import android.util.AtomicFile
import com.kienhoang.dualsubreplay.data.SavedWord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** Immutable identifiers, JSON on disk, and a durable local outbox. No network calls here. */
internal data class ListeningLine(
    val id: String,
    val speaker: String,
    val en: String,
    val zh: String,
)

internal data class ListeningPack(
    val id: String,
    val title: String,
    val level: String,
    val lines: List<ListeningLine>,
    val followUpEn: String = "",
    val kind: String = "dialogue",
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("schema_version", 1)
            .put("id", id)
            .put("title", title)
            .put("level", level)
            .put("follow_up_en", followUpEn)
            .put("kind", kind)
            .put(
                "lines",
                JSONArray().apply {
                    lines.forEach {
                        put(
                            JSONObject()
                                .put("id", it.id)
                                .put("speaker", it.speaker)
                                .put("text_en", it.en)
                                .put("text_zh", it.zh),
                        )
                    }
                },
            )

    companion object {
        fun fromJson(data: JSONObject): ListeningPack {
            require(data.optInt("schema_version") == 1) { "Unsupported dialogue version" }
            val id = data.getString("id")
            require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
            val raw = data.getJSONArray("lines")
            require(raw.length() in 1..300) { "Dialogue line count must be 1..300" }
            val lines =
                (0 until raw.length()).map {
                    val line = raw.getJSONObject(it)
                    ListeningLine(
                        id = line.getString("id"),
                        speaker = line.optString("speaker", "A").take(24),
                        en = line.getString("text_en").take(1000),
                        zh = line.optString("text_zh", "").take(1000),
                    )
                }
            require(lines.all { it.en.isNotBlank() } && lines.map { it.id }.distinct().size == lines.size)
            return ListeningPack(
                id, data.optString("title", id).take(120), data.optString("level", "A2"),
                lines, data.optString("follow_up_en", "").take(500),
                data.optString("kind", "dialogue").take(32),
            )
        }
    }
}

internal data class ListeningSnapshot(
    val packs: List<ListeningPack>,
    val pending: Int,
    val saved: Int,
    val due: Int,
    val unestablished: Int,
    val deviceId: String,
    val recallRevision: Int,
    val seriesEpisodes: List<ListeningSeriesEpisode>,
)

/** Uses AtomicFile so a crash cannot destroy a half-written learning history. */
internal class ListeningStore(
    context: Context,
) {
    private val file = AtomicFile(File(context.filesDir, "offline-listening-state.json"))
    private val seriesFile = AtomicFile(File(context.filesDir, "offline-listening-series.json"))
    private var root = read()
    private val seriesPacks = readSeriesCache()
    private val _state = MutableStateFlow(snapshot())
    val state = _state.asStateFlow()

    init {
        // Migrate earlier per-episode downloads out of the frequently rewritten progress file.
        val previous = root.optJSONArray("packs") ?: JSONArray()
        val ordinary = JSONArray()
        var hadLegacySeries = false
        for (index in 0 until previous.length()) {
            val item = previous.getJSONObject(index)
            val pack = runCatching { ListeningPack.fromJson(item) }.getOrNull()
            if (pack?.kind == SITCOM_PACK_KIND) {
                seriesPacks.putIfAbsent(pack.id, pack)
                hadLegacySeries = true
            } else {
                ordinary.put(item)
            }
        }
        if (hadLegacySeries) {
            // A failure must never discard the original all-in-one cache.
            runCatching {
                persistSeriesCache(seriesPacks)
                root.put("packs", ordinary)
                persist()
            }
        }
    }

    private fun readSeriesCache(): LinkedHashMap<String, ListeningPack> {
        val result = LinkedHashMap<String, ListeningPack>()
        if (!seriesFile.baseFile.exists()) return result
        val stored = JSONArray(seriesFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(stored.length() <= 1000) { "剧集字幕缓存数量异常" }
        for (i in 0 until stored.length()) {
            val pack = ListeningPack.fromJson(stored.getJSONObject(i))
            require(pack.kind == SITCOM_PACK_KIND)
            result[pack.id] = pack
        }
        return result
    }

    private fun persistSeriesCache(packs: Map<String, ListeningPack>) {
        val payload = JSONArray().apply {
            packs.values.forEach { put(it.toJson()) }
        }
        var stream: java.io.FileOutputStream? = null
        try {
            stream = seriesFile.startWrite()
            stream.write(payload.toString().toByteArray(Charsets.UTF_8))
            seriesFile.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) seriesFile.failWrite(stream)
            throw error
        }
    }

    private fun read(): JSONObject {
        if (!file.baseFile.exists()) {
            return JSONObject()
                .put("schema_version", 1)
                .put("device_id", UUID.randomUUID().toString())
                .put("packs", JSONArray())
                .put("events", JSONArray())
        }
        return JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }).also {
            check(it.optInt("schema_version") == 1) { "Unsupported local listening data" }
        }
    }

    private fun snapshot(): ListeningSnapshot {
        val packsArray = root.optJSONArray("packs") ?: JSONArray()
        val events = root.optJSONArray("events") ?: JSONArray()
        val catalog = root.optJSONArray("series_catalog") ?: JSONArray()
        val seriesEpisodes = (0 until catalog.length()).mapNotNull {
            runCatching { ListeningSeriesEpisode.fromJson(catalog.getJSONObject(it)) }.getOrNull()
        }
        val byId = LinkedHashMap<String, ListeningPack>()
        for (index in 0 until packsArray.length()) {
            runCatching { ListeningPack.fromJson(packsArray.getJSONObject(index)) }
                .getOrNull()?.let { byId[it.id] = it }
        }
        seriesPacks.forEach { (id, pack) -> byId[id] = pack }
        val packs = byId.values.toList()
        val savedWords = HashSet<String>()
        var pending = 0
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            if (!e.optBoolean("deleted") && ListeningSyncPolicy.mayUpload(e.optBoolean("synced"))) pending++
            if (e.optBoolean("deleted") && e.optBoolean("synced")) pending++
            val word = e.optString("word").lowercase()
            if (e.optString("type") == "saved") savedWords.add(word)
        }
        val memories = root.optJSONObject("memory") ?: JSONObject()
        val now = System.currentTimeMillis()
        val due =
            savedWords.count { word ->
                val card = memories.optJSONObject(word)
                card != null && card.optLong("due_at", Long.MAX_VALUE) <= now
            }
        val unsettled =
            savedWords.count { word ->
                val card = memories.optJSONObject(word)
                card == null || memoryFromJson(card)?.established != true
            }
        return ListeningSnapshot(
            packs,
            pending,
            savedWords.size,
            due,
            unsettled,
            root.optString("device_id"),
            root.optInt("recall_revision"),
            seriesEpisodes,
        )
    }

    private fun persist() {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) file.failWrite(stream)
            throw e
        }
        _state.value = snapshot()
    }

    @Synchronized fun pendingEvents(): List<JSONObject> {
        val list = root.optJSONArray("events") ?: JSONArray()
        return (0 until list.length())
            .map { list.getJSONObject(it) }
            .filter { !it.optBoolean("deleted") && ListeningSyncPolicy.mayUpload(it.optBoolean("synced")) }
            .map(::remoteEvent)
    }

    /** All on-device attempts, including already uploaded or remotely restored history. */
    @Synchronized fun reviewableEvents(): List<JSONObject> {
        val list = root.optJSONArray("events") ?: return emptyList()
        return (0 until list.length())
            .map { list.getJSONObject(it) }
            .filter { !it.optBoolean("deleted") && it.optString("type") in setOf("recall_checked", "recall_insight", "raw_note") }
            .map { JSONObject(it.toString()) }
    }

    /**
     * Delete a user-selected practice event. Never-uploaded records are removed immediately.
     * Previously uploaded records disappear from the UI immediately, but their original device,
     * month and ID remain locally until the GitHub file edit succeeds or can be retried.
     * No deletion marker is created in GitHub.
     */
    @Synchronized fun deleteReviewRecord(eventId: String) {
        val entries = root.optJSONArray("events") ?: return
        val ids = HashSet<String>()
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            if (item.optString("id") == eventId &&
                item.optString("type") in setOf("recall_checked", "recall_insight", "raw_note")
            ) ids.add(eventId)
        }
        if (ids.isEmpty()) return
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            if (item.optString("type") == "recall_insight" &&
                item.optString("source_event_id") in ids
            ) ids.add(item.optString("id"))
        }
        val affectedLines = (0 until entries.length())
            .map { entries.getJSONObject(it) }
            .filter { it.optString("id") in ids && it.optString("type") == "recall_checked" }
            .map { it.optString("dialogue_id") to it.optString("line_id") }
            .toSet()
        val retained = JSONArray()
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            if (item.optString("id") !in ids) {
                retained.put(item)
            } else if (item.optBoolean("synced")) {
                item.put("deleted", true)
                retained.put(item)
            }
            // If unsynced, the actual local event is removed now, not tagged or uploaded.
        }
        root.put("events", retained)
        affectedLines.forEach { (packId, lineId) -> rebuildRecallAfterDeletion(packId, lineId) }
        changedRecall()
    }

    /**
     * A removed typo must not remain counted in the visible green/red status or attempt count.
     * Rebuild the derived line progress from the remaining checked events, not from the
     * now-deleted answer. Old remote submissions need not retain their original numbering.
     */
    private fun rebuildRecallAfterDeletion(packId: String, lineId: String) {
        val all = root.optJSONObject("recall_progress") ?: return
        val group = all.optJSONObject(packId) ?: return
        val old = group.optJSONObject(lineId) ?: return
        val reference = old.optString("reference")
        if (reference.isBlank()) return
        val entries = root.optJSONArray("events") ?: return
        val remaining = (0 until entries.length())
            .map { entries.getJSONObject(it) }
            .filter {
                it.optString("type") == "recall_checked" &&
                    it.optString("dialogue_id") == packId &&
                    it.optString("line_id") == lineId &&
                    !it.optBoolean("deleted")
            }.sortedBy { it.optString("created_at") }
        if (remaining.isEmpty()) {
            group.remove(lineId)
            return
        }
        val pack = snapshot().packs.firstOrNull { it.id == packId } ?: return
        val line = pack.lines.firstOrNull { it.id == lineId && it.en == reference } ?: return
        var rebuilt = RecallLineProgress()
        for (item in remaining) {
            val answer = item.optString("checked_input")
            if (answer.isBlank()) continue
            rebuilt = rebuilt.hint(item.optInt("hint_level"))
            rebuilt = rebuilt.check(
                answer,
                ListeningRecallCheck.compare(reference, answer, pack.kind == SITCOM_PACK_KIND),
                item.optBoolean("recognition_correction"),
            )
        }
        putRecall(pack, line, rebuilt)
    }

    /** Local retry queue only; there is no remote tombstone or second cloud collection. */
    @Synchronized fun pendingDeletions(): List<JSONObject> {
        val list = root.optJSONArray("events") ?: return emptyList()
        return (0 until list.length())
            .map { list.getJSONObject(it) }
            .filter {
                it.optBoolean("deleted") &&
                    it.optBoolean("synced") &&
                    it.optString("type") in setOf("recall_checked", "recall_insight", "raw_note")
            }.map { JSONObject(it.toString()) }
    }

    @Synchronized fun markDeletionsSynced(ids: Set<String>) {
        val list = root.optJSONArray("events") ?: return
        val retained = JSONArray()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.optString("id") !in ids || !item.optBoolean("deleted")) retained.put(item)
        }
        root.put("events", retained)
        changedRecall()
    }

    /** Import remote history without re-adding records waiting for a cloud delete. */
    @Synchronized fun importCloudReviewHistory(remote: List<JSONObject>) {
        val list = root.optJSONArray("events") ?: JSONArray().also { root.put("events", it) }
        val ids = (0 until list.length()).map { list.getJSONObject(it).optString("id") }.toMutableSet()
        var changed = false
        for (raw in remote.take(15_000)) {
            val id = raw.optString("id")
            if (id.isBlank() || id in ids ||
                raw.optString("type") !in setOf("recall_checked", "recall_insight", "raw_note")
            ) continue
            list.put(
                JSONObject(raw.toString())
                    .put("synced", true)
                    .put("cloud_imported", true),
            )
            ids.add(id)
            changed = true
        }
        if (changed) changedRecall()
    }

    /**
     * Keep every attempted transcription as raw evidence. A typo label is analysis metadata,
     * never a reason to discard the attempt or stop it from syncing.
     */
    @Synchronized fun toggleTypoLabel(eventId: String) {
        val events = root.optJSONArray("events") ?: return
        for (index in 0 until events.length()) {
            val item = events.getJSONObject(index)
            if (item.optString("id") != eventId ||
                item.optString("type") != "recall_checked" ||
                item.optBoolean("deleted")
            ) continue
            if (item.optString("review_label") == "typo") {
                item.remove("review_label")
            } else {
                item.put("review_label", "typo")
            }
            changedRecall()
            return
        }
    }

    /** Return links to selected, meaningful error points, including previously uploaded ones. */
    @Synchronized fun bookmarkedErrorKeys(): Set<String> {
        val events = root.optJSONArray("events") ?: return emptySet()
        return (0 until events.length()).mapNotNull { index ->
            val item = events.getJSONObject(index)
            if (item.optString("type") != "recall_insight" || item.optBoolean("deleted")) return@mapNotNull null
            val sourceId = item.optString("source_event_id")
            val error = item.optString("selected_error")
            if (sourceId.isBlank() || error.isBlank()) null else
                ListeningSyncPolicy.errorKey(sourceId, error)
        }.toSet()
    }

    /**
     * One-tap curation of a single error from an attempted sentence.
     * The underlying recall_checked event stays untouched and remains eligible for upload.
     * Tapping again removes an insight locally; uploaded insights are queued for a direct GitHub file edit.
     */
    @Synchronized fun toggleBookmarkedError(sourceEventId: String, selectedError: String) {
        val events = root.optJSONArray("events") ?: return
        val chosen = selectedError.trim().take(80)
        if (chosen.isBlank()) return
        val source = (0 until events.length()).map { events.getJSONObject(it) }
            .firstOrNull { it.optString("id") == sourceEventId &&
                it.optString("type") == "recall_checked" && !it.optBoolean("passed") && !it.optBoolean("deleted") }
            ?: return
        val wrong = source.optJSONArray("wrong_words") ?: return
        if ((0 until wrong.length()).none { wrong.optString(it).equals(chosen, ignoreCase = true) }) return

        for (index in 0 until events.length()) {
            val insight = events.getJSONObject(index)
            if (insight.optString("type") == "recall_insight" &&
                insight.optString("source_event_id") == sourceEventId &&
                insight.optString("selected_error").equals(chosen, ignoreCase = true)
            ) {
                if (insight.optBoolean("deleted")) continue
                if (insight.optBoolean("synced")) {
                    insight.put("deleted", true)
                    changedRecall()
                    return
                }
                val remaining = JSONArray()
                for (j in 0 until events.length()) if (j != index) remaining.put(events.getJSONObject(j))
                root.put("events", remaining)
                changedRecall()
                return
            }
        }
        val packId = source.optString("dialogue_id")
        val lineId = source.optString("line_id")
        val pack = snapshot().packs.firstOrNull { it.id == packId }
        val expected = pack?.lines?.firstOrNull { it.id == lineId }?.en.orEmpty()
        events.put(
            event("recall_insight", "", packId, lineId)
                .put("category", "selected_error")
                .put("source_event_id", sourceEventId)
                .put("selected_error", chosen)
                .put("expected", expected)
                .put("observed", source.optString("checked_input"))
                .put("source_attempts", source.optInt("attempt"))
                .put("wrong_words", JSONArray().put(chosen))
                .put("note", "")
                .put("user_selected", true),
        )
        changedRecall()
    }

    /**
     * Deliberate learning insight: separated from automatically captured attempts.
     * The selected category and annotation are learner-authored, not AI-inferred.
     */
    @Synchronized fun saveRecallInsight(
        pack: ListeningPack,
        line: ListeningLine,
        observed: String,
        category: String,
        note: String,
    ) {
        require(category in setOf("listening", "missing", "preposition", "grammar", "transcription", "other"))
        val events = root.optJSONArray("events") ?: JSONArray().also { root.put("events", it) }
        val noteText = note.trim().take(300)
        val actual = observed.trim().take(500)
        val previous = recallProgress(pack, line)
        if ((0 until events.length()).any { index ->
                val item = events.getJSONObject(index)
                item.optString("type") == "recall_insight" &&
                    item.optString("dialogue_id") == pack.id &&
                    item.optString("line_id") == line.id &&
                    item.optString("category") == category &&
                    item.optString("note") == noteText &&
                    item.optString("observed") == actual
            }) return
        events.put(
            event("recall_insight", "", pack.id, line.id)
                .put("category", category)
                .put("note", noteText)
                .put("expected", line.en)
                .put("observed", actual)
                .put("source_attempts", previous.attempts)
                .put("wrong_words", JSONArray(previous.wrongWords))
                .put("user_selected", true),
        )
        persist()
    }


    /**
     * MyWork-style Raw: user's words are kept verbatim, without AI scoring/correction.
     * Each note is a separate local UUID item and an independently uploadable GitHub file.
     */
    @Synchronized fun addRawReflection(
        pack: ListeningPack,
        lineId: String,
        prompt: String,
        text: String,
    ): Boolean {
        if (text.isBlank()) return false
        val original = text.take(5000)
        val events = root.optJSONArray("events") ?: JSONArray().also { root.put("events", it) }
        val note = event("raw_note", "", pack.id, lineId)
            .put("text", original.take(5000))
            .put("prompt", prompt.trim().take(500))
            .put("pack_title", pack.title)
            .put("source", if (lineId.isBlank()) "dialogue_follow_up" else "sentence_reflection")
            .put("reviewed_by_ai", false)
        events.put(note)
        changedRecall()
        return true
    }

    @Synchronized fun rawNotesFor(packId: String, lineId: String? = null): List<JSONObject> {
        val list = root.optJSONArray("events") ?: return emptyList()
        return (0 until list.length())
            .map { list.getJSONObject(it) }
            .filter {
                it.optString("type") == "raw_note" && !it.optBoolean("deleted") &&
                    it.optString("dialogue_id") == packId &&
                    (lineId == null || it.optString("line_id") == lineId)
            }.map { JSONObject(it.toString()) }
    }

    /** Distinguish independent recall success from immediate retries, corrections and assisted answers. */
    @Synchronized fun learningMetrics(): ListeningLearningMetrics {
        val events = root.optJSONArray("events") ?: JSONArray()
        val active = (0 until events.length()).map { events.getJSONObject(it) }
            .filter { !it.optBoolean("deleted") }
        val firstAttempts = active
            .filter {
                it.optString("type") == "recall_checked" &&
                    it.optInt("attempt") == 1 &&
                    it.optInt("hint_level") == 0 &&
                    !it.optBoolean("recognition_correction") &&
                    it.optString("review_label") != "typo"
            }.groupBy { it.optString("dialogue_id") + "/" + it.optString("line_id") }
            .values.mapNotNull { group -> group.minByOrNull { it.optString("created_at") } }
        val retests = active.filter { it.optString("type") == "recall_retention" }
        return ListeningLearningMetrics(
            firstIndependentChecks = firstAttempts.size,
            firstIndependentPasses = firstAttempts.count { it.optBoolean("passed") },
            delayedChecks = retests.size,
            delayedPasses = retests.count { it.optBoolean("passed") },
            freeExpressions = active.count { it.optString("type") == "raw_note" },
        )
    }

    /** A passed line becomes due after 1 day, then 7 days, then 30 days. */
    private fun initializeRetestIfNeeded(pack: ListeningPack, line: ListeningLine) {
        val all = root.optJSONObject("recall_retests") ?: JSONObject().also { root.put("recall_retests", it) }
        val key = pack.id + "/" + line.id
        if (all.has(key)) return
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        all.put(
            key,
            JSONObject()
                .put("reference", line.en)
                .put("stage", 0)
                .put("due_on", today.plusDays(1).toString())
                .put("original_day", today.toString()),
        )
    }

    /**
     * On upgrade, schedule existing successfully completed sentences too.
     * The date comes from the earliest recorded pass, not the app-upgrade date.
     * No synthetic learning event or retrospective score is created.
     */
    private fun bootstrapExistingRetests() {
        val all = root.optJSONObject("recall_retests") ?: JSONObject().also {
            root.put("recall_retests", it)
        }
        val events = root.optJSONArray("events") ?: JSONArray()
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        var changed = false
        for (pack in snapshot().packs) {
            for (line in pack.lines) {
                val key = pack.id + "/" + line.id
                if (all.has(key) || !recallProgress(pack, line).passed) continue
                val firstPass = (0 until events.length()).map { events.getJSONObject(it) }
                    .filter {
                        !it.optBoolean("deleted") &&
                            it.optString("type") == "recall_checked" &&
                            it.optString("dialogue_id") == pack.id &&
                            it.optString("line_id") == line.id &&
                            it.optBoolean("passed")
                    }.minByOrNull { it.optString("created_at") }
                val originalDay = runCatching {
                    LocalDate.parse(firstPass?.optString("created_at")?.take(10).orEmpty())
                }.getOrDefault(today)
                all.put(key, JSONObject()
                    .put("reference", line.en)
                    .put("stage", 0)
                    .put("due_on", originalDay.plusDays(1).toString())
                    .put("original_day", originalDay.toString()))
                changed = true
            }
        }
        if (changed) persist()
    }

    @Synchronized fun dueRetests(): List<ListeningRetest> {
        bootstrapExistingRetests()
        val all = root.optJSONObject("recall_retests") ?: return emptyList()
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val packs = snapshot().packs
        return all.keys().asSequence().mapNotNull { key ->
            val item = all.optJSONObject(key) ?: return@mapNotNull null
            if (item.optString("due_on") > today) return@mapNotNull null
            val packId = key.substringBeforeLast("/")
            val lineId = key.substringAfterLast("/")
            val pack = packs.firstOrNull { it.id == packId } ?: return@mapNotNull null
            val index = pack.lines.indexOfFirst { it.id == lineId }
            if (index < 0 || pack.lines[index].en != item.optString("reference") ||
                !recallProgress(pack, pack.lines[index]).passed
            ) return@mapNotNull null
            ListeningRetest(pack, index, item.optString("due_on"), item.optInt("stage"))
        }.sortedWith(compareBy({ it.dueOn }, { it.pack.title }, { it.lineIndex })).toList()
    }

    @Synchronized fun checkDelayedRecall(pack: ListeningPack, line: ListeningLine, text: String): Boolean {
        require(text.isNotBlank())
        val all = root.optJSONObject("recall_retests") ?: return false
        val key = pack.id + "/" + line.id
        val item = all.optJSONObject(key) ?: return false
        if (item.optString("reference") != line.en) return false
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        if (item.optString("due_on") > today.toString()) return false
        val passed = ListeningRecallCheck.compare(line.en, text).perfect
        val stage = if (passed) (item.optInt("stage") + 1).coerceAtMost(3) else 0
        val interval = when (stage) { 0 -> 1L; 1 -> 7L; else -> 30L }
        val events = root.optJSONArray("events") ?: JSONArray().also { root.put("events", it) }
        events.put(
            event("recall_retention", "", pack.id, line.id)
                .put("passed", passed)
                .put("stage", stage)
                .put("due_on", item.optString("due_on"))
                .put("checked_input", text.take(500))
                .put("mode", "delayed_no_hint"),
        )
        item.put("stage", stage)
        item.put("last_checked_on", today.toString())
        item.put("due_on", today.plusDays(interval).toString())
        changedRecall()
        return passed
    }

    @Synchronized fun markUploaded(ids: Set<String>) {
        val events = root.optJSONArray("events") ?: return
        for (i in 0 until events.length()) {
            val item = events.getJSONObject(i)
            if (item.optString("id") in ids && !item.optBoolean("deleted")) item.put("synced", true)
        }
        persist()
    }

    @Synchronized fun addWord(
        word: String,
        pack: ListeningPack,
        line: ListeningLine,
    ) {
        val clean = word.lowercase().trim().replace(Regex("[^a-z'-]"), "")
        if (!clean.matches(Regex("[a-z][a-z'-]{0,49}"))) return
        val items = root.optJSONArray("events") ?: JSONArray().also { root.put("events", it) }
        // One event per word in a sentence; repeated taps are not additional learning evidence.
        if ((0 until items.length()).any {
                val x = items.getJSONObject(it)
                x.optString("type") == "saved" && x.optString("word") == clean &&
                    x.optString("dialogue_id") == pack.id && x.optString("line_id") == line.id
            }
        ) {
            return
        }
        items.put(event("saved", clean, pack.id, line.id).put("sentence", line.en))
        persist()
    }

    /**
     * The original YouTube Practice database is the canonical word collection.
     * Mirror its English word/sentence saves into the durable listening event outbox, so both
     * YouTube and offline listening contribute to the same GitHub learning history and audio
     * recognition review queue. Never treat a reread/recomposition as another save.
     * There is deliberately no review score here: adding a word is not proof of recognizing it.
     */
    @Synchronized fun importSavedVocabulary(words: List<SavedWord>): Int {
        val events = root.optJSONArray("events") ?: JSONArray().also { root.put("events", it) }
        val knownIds = HashSet<String>()
        val knownOfflineSentences = HashSet<String>()
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            if (e.optString("type") != "saved") continue
            e.optString("source_word_id").takeIf { it.isNotBlank() }?.let(knownIds::add)
            if (e.optString("dialogue_id") != "youtube") {
                knownOfflineSentences += e.optString("word") + "\u0000" + e.optString("sentence")
            }
        }
        var added = 0
        for (entry in words) {
            if (entry.wordLanguage.lowercase().substringBefore('-') != "en") continue
            val clean = entry.word.lowercase().trim().replace(Regex("[^a-z'-]"), "")
            if (!clean.matches(Regex("[a-z][a-z'-]{0,49}"))) continue
            if (entry.id in knownIds) continue
            // An offline word already created its own saved event before the canonical DB save.
            if (entry.videoId == null && (clean + "\u0000" + entry.sentence) in knownOfflineSentences) continue
            val source = if (entry.videoId.isNullOrBlank()) "practice" else "youtube"
            events.put(
                event("saved", clean, source, entry.id)
                    .put("source", source)
                    .put("source_word_id", entry.id)
                    .put("sentence", entry.sentence)
                    .put("translation", entry.translatedSentence.orEmpty())
                    .put("video_id", entry.videoId.orEmpty())
                    .put("source_title", entry.sourceTitle.orEmpty()),
            )
            knownIds += entry.id
            added++
        }
        if (added > 0) persist()
        return added
    }

    @Synchronized fun reviewCandidates(): List<String> {
        val events = root.optJSONArray("events") ?: JSONArray()
        val saved =
            (0 until events.length())
                .map { events.getJSONObject(it) }
                .filter { it.optString("type") == "saved" }
                .map { it.optString("word") }
                .distinct()
        val memories = root.optJSONObject("memory") ?: JSONObject()
        val now = System.currentTimeMillis()
        return saved
            .filter { word ->
                val card = memories.optJSONObject(word)
                card == null || card.optLong("due_at", Long.MAX_VALUE) <= now
            }.sortedWith(
                compareBy<String> { word ->
                    val card = memories.optJSONObject(word)
                    // Due reviews first, then completely new cards.
                    card?.optLong("due_at", Long.MAX_VALUE) ?: Long.MAX_VALUE
                }.thenBy { it },
            )
    }

    private fun memoryFromJson(j: JSONObject?): Fsrs6.Memory? {
        if (j == null) return null
        val days = j.optJSONArray("successful_days") ?: JSONArray()
        return Fsrs6.Memory(
            j.getDouble("difficulty"),
            j.getDouble("stability_days"),
            j.getLong("last_review"),
            j.getLong("due_at"),
            j.getInt("reviews"),
            (0 until days.length()).map { days.getString(it) }.toSet(),
        )
    }

    @Synchronized fun addReview(
        word: String,
        rating: String,
    ) {
        val grade = listOf("again", "hard", "good", "easy").indexOf(rating) + 1
        require(grade in 1..4)
        val normalized = word.lowercase()
        val memories = root.optJSONObject("memory") ?: JSONObject().also { root.put("memory", it) }
        val now = System.currentTimeMillis()
        val result =
            Fsrs6.review(
                memoryFromJson(memories.optJSONObject(normalized)),
                grade,
                now,
                LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),
            )
        memories.put(
            normalized,
            JSONObject()
                .put("algorithm", Fsrs6.VERSION)
                .put("difficulty", result.difficulty)
                .put("stability_days", result.stabilityDays)
                .put("last_review", result.lastReview)
                .put("due_at", result.dueAt)
                .put("reviews", result.reviews)
                .put("successful_days", JSONArray(result.successfulDays.toList())),
        )
        root.getJSONArray("events").put(
            event("reviewed", normalized, "", "")
                .put("rating", rating)
                .put("fsrs_version", Fsrs6.VERSION)
                .put("stability_days", result.stabilityDays)
                .put("due_at", result.dueAt),
        )
        persist()
    }

    private fun event(
        type: String,
        word: String,
        dialogueId: String,
        lineId: String,
    ): JSONObject =
        JSONObject()
            .put("schema_version", 1)
            .put("id", UUID.randomUUID().toString())
            .put("device_id", root.optString("device_id"))
            .put("created_at", OffsetDateTime.now().toString())
            .put("type", type)
            .put("skill", "listening")
            .put("word", word)
            .put("dialogue_id", dialogueId)
            .put("line_id", lineId)
            .put("synced", false)

    /** The synced flag is a device-local outbox field, not part of the remote immutable event. */
    private fun remoteEvent(original: JSONObject): JSONObject =
        JSONObject(original.toString()).apply {
            remove("synced")
            // Old versions allowed excluding mistakes from uploads. Retain that fact
            // as an analysis label, while preserving the raw attempt for later cleaning.
            if (original.optBoolean("upload_excluded") && !has("review_label")) {
                put("review_label", "previously_excluded")
            }
            remove("upload_excluded")
            remove("deleted")
            remove("deletion_synced")
            remove("delete_requested_at")
            remove("cloud_imported")
        }

    /** Practice preferences live in the listening JSON, not global app settings. */
    @Synchronized fun recallAutoNext(): Boolean = root.optBoolean("recall_auto_next", false)

    @Synchronized fun setRecallAutoNext(
        enabled: Boolean,
    ) {
        if (recallAutoNext() == enabled) return
        root.put("recall_auto_next", enabled)
        changedRecall()
    }

    @Synchronized fun recallSort(): ListeningPackSort =
        ListeningPackSort.fromKey(root.optString("recall_sort", ListeningPackSort.RECENT.key))

    @Synchronized fun setRecallSort(sort: ListeningPackSort) {
        if (recallSort() == sort) return
        root.put("recall_sort", sort.key)
        changedRecall()
    }

    @Synchronized fun recallRecentUsage(): Map<String, Long> {
        val usage = root.optJSONObject("recall_last_used") ?: return emptyMap()
        return usage.keys().asSequence().associateWith { usage.optLong(it, 0L) }
    }

    @Synchronized fun markPackUsed(pack: ListeningPack) {
        val usage = root.optJSONObject("recall_last_used") ?: JSONObject()
        usage.put(pack.id, System.currentTimeMillis())
        root.put("recall_last_used", usage)
        changedRecall()
    }

    /** Per-line grades survive screen recreation and app updates without changing schema v1. */
    @Synchronized fun recallProgress(
        pack: ListeningPack,
        line: ListeningLine,
    ): RecallLineProgress {
        val all = root.optJSONObject("recall_progress") ?: return RecallLineProgress()
        val group = all.optJSONObject(pack.id) ?: return RecallLineProgress()
        val item = group.optJSONObject(line.id) ?: return RecallLineProgress()
        if (item.optString("reference") != line.en) return RecallLineProgress()
        val wrong = item.optJSONArray("wrong_words") ?: JSONArray()
        return RecallLineProgress(
            attempts = item.optInt("attempts").coerceAtLeast(0),
            passed = item.optBoolean("passed"),
            softPassed = item.optBoolean("soft_passed"),
            firstTryPassed = item.optBoolean("first_try_passed"),
            hintLevel = item.optInt("hint_level").coerceIn(0, 2),
            firstAnswer = item.optString("first_answer"),
            lastAnswer = item.optString("last_answer"),
            draft = if (item.has("draft")) item.optString("draft") else null,
            corrections = item.optInt("corrections").coerceAtLeast(0),
            wrongWords = (0 until wrong.length()).map { wrong.optString(it) },
        )
    }

    @Synchronized fun recallPosition(pack: ListeningPack): Int {
        val positions = root.optJSONObject("recall_positions") ?: return 0
        return positions.optInt(pack.id, 0).coerceIn(pack.lines.indices)
    }

    @Synchronized fun setRecallPosition(
        pack: ListeningPack,
        index: Int,
    ) {
        val positions = root.optJSONObject("recall_positions") ?: JSONObject()
        root.put("recall_positions", positions)
        val safe = index.coerceIn(pack.lines.indices)
        if (positions.optInt(pack.id, -1) == safe) return
        positions.put(pack.id, safe)
        changedRecall()
    }

    @Synchronized fun saveRecallDraft(
        pack: ListeningPack,
        line: ListeningLine,
        answer: String,
    ) {
        val old = recallProgress(pack, line)
        if (old.draft == answer) return
        putRecall(pack, line, old.copy(draft = answer.take(1000)))
        changedRecall()
    }

    @Synchronized fun setRecallHint(
        pack: ListeningPack,
        line: ListeningLine,
        level: Int,
    ) {
        val old = recallProgress(pack, line)
        if (old.hintLevel >= level) return
        putRecall(pack, line, old.hint(level))
        changedRecall()
    }

    @Synchronized fun checkRecall(
        pack: ListeningPack,
        line: ListeningLine,
        answer: String,
        recognitionCorrection: Boolean,
    ) {
        val before = recallProgress(pack, line)
        val result = ListeningRecallCheck.compare(line.en, answer, pack.kind == SITCOM_PACK_KIND)
        val after = before.check(answer.take(1000), result, recognitionCorrection)
        putRecall(pack, line, after)
        if (after.passed && !before.passed) initializeRetestIfNeeded(pack, line)
        val events = root.optJSONArray("events") ?: JSONArray().also { root.put("events", it) }
        events.put(
            event("recall_checked", "", pack.id, line.id)
                .put("passed", after.passed)
                .put("soft_passed", after.softPassed)
                .put("optional_omissions", JSONArray(result.optionalOmissions))
                .put("attempt", after.attempts)
                .put("first_try_passed", after.firstTryPassed)
                .put("hint_level", after.hintLevel)
                .put("recognition_correction", recognitionCorrection && before.attempts > 0)
                .put("differences", result.differences)
                .put("wrong_words", JSONArray(after.wrongWords))
                .put("initial_input", after.firstAnswer.take(240))
                .put("checked_input", answer.take(240)),
        )
        changedRecall()
    }

    private fun putRecall(
        pack: ListeningPack,
        line: ListeningLine,
        progress: RecallLineProgress,
    ) {
        val all = root.optJSONObject("recall_progress") ?: JSONObject()
        root.put("recall_progress", all)
        val group = all.optJSONObject(pack.id) ?: JSONObject()
        all.put(pack.id, group)
        group.put(
            line.id,
            JSONObject()
                .put("reference", line.en)
                .put("attempts", progress.attempts)
                .put("passed", progress.passed)
                .put("soft_passed", progress.softPassed)
                .put("first_try_passed", progress.firstTryPassed)
                .put("hint_level", progress.hintLevel)
                .put("first_answer", progress.firstAnswer)
                .put("last_answer", progress.lastAnswer)
                .put("corrections", progress.corrections)
                .put("wrong_words", JSONArray(progress.wrongWords))
                .apply { progress.draft?.let { put("draft", it) } },
        )
    }

    private fun changedRecall() {
        root.put("recall_revision", root.optInt("recall_revision") + 1)
        persist()
    }

    @Synchronized fun putPacks(packs: List<ListeningPack>) {
        val newSeries = packs.filter { it.kind == SITCOM_PACK_KIND }
        if (newSeries.isNotEmpty()) {
            val next = LinkedHashMap(seriesPacks)
            newSeries.forEach { next[it.id] = it }
            // Commit series text before replacing the live cache, keeping existing work on failure.
            persistSeriesCache(next)
            seriesPacks.clear()
            seriesPacks.putAll(next)
        }
        val ordinary = packs.filter { it.kind != SITCOM_PACK_KIND }
        if (ordinary.isNotEmpty()) {
            val previous = root.optJSONArray("packs") ?: JSONArray()
            val byId = LinkedHashMap<String, ListeningPack>()
            for (i in 0 until previous.length()) {
                runCatching { ListeningPack.fromJson(previous.getJSONObject(i)) }
                    .getOrNull()?.let { byId[it.id] = it }
            }
            ordinary.forEach { byId[it.id] = it }
            root.put("packs", JSONArray().apply { byId.values.forEach { put(it.toJson()) } })
            persist()
        } else if (newSeries.isNotEmpty()) {
            // Publish newly cached subtitles without rewriting the learning-events file.
            _state.value = snapshot()
        }
    }

    /** Cache only lightweight episode metadata; subtitle texts stay on-demand. */
    @Synchronized fun putSeriesEpisodes(episodes: List<ListeningSeriesEpisode>) {
        root.put("series_catalog", JSONArray().apply {
            episodes.forEach { put(it.toJson()) }
        })
        persist()
    }
}
