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
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("schema_version", 1)
            .put("id", id)
            .put("title", title)
            .put("level", level)
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
            return ListeningPack(id, data.optString("title", id).take(120), data.optString("level", "A2"), lines)
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
)

/** Uses AtomicFile so a crash cannot destroy a half-written learning history. */
internal class ListeningStore(
    context: Context,
) {
    private val file = AtomicFile(File(context.filesDir, "offline-listening-state.json"))
    private var root = read()
    private val _state = MutableStateFlow(snapshot())
    val state = _state.asStateFlow()

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
        val packs =
            (0 until packsArray.length()).mapNotNull {
                runCatching { ListeningPack.fromJson(packsArray.getJSONObject(it)) }.getOrNull()
            }
        val savedWords = HashSet<String>()
        var pending = 0
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            if (!e.optBoolean("synced")) pending++
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
            .filter { !it.optBoolean("synced") }
            .map(::remoteEvent)
    }

    @Synchronized fun markUploaded(ids: Set<String>) {
        val events = root.optJSONArray("events") ?: return
        for (i in 0 until events.length()) {
            val item = events.getJSONObject(i)
            if (item.optString("id") in ids) item.put("synced", true)
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
    private fun remoteEvent(original: JSONObject): JSONObject = JSONObject(original.toString()).apply { remove("synced") }

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
        val result = ListeningRecallCheck.compare(line.en, answer)
        val after = before.check(answer.take(1000), result, recognitionCorrection)
        putRecall(pack, line, after)
        val events = root.optJSONArray("events") ?: JSONArray().also { root.put("events", it) }
        events.put(
            event("recall_checked", "", pack.id, line.id)
                .put("passed", after.passed)
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
        val previous = root.optJSONArray("packs") ?: JSONArray()
        val byId = LinkedHashMap<String, ListeningPack>()
        for (i in 0 until previous.length()) {
            runCatching { ListeningPack.fromJson(previous.getJSONObject(i)) }.getOrNull()?.let { byId[it.id] = it }
        }
        packs.forEach { byId[it.id] = it }
        val all = JSONArray()
        byId.values.forEach { all.put(it.toJson()) }
        root.put("packs", all)
        persist()
    }
}
