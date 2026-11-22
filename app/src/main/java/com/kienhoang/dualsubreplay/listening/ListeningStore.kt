package com.kienhoang.dualsubreplay.listening

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

/** Immutable identifiers, JSON on disk, and a durable local outbox. No network calls here. */
internal data class ListeningLine(val id: String, val speaker: String, val en: String, val zh: String)
internal data class ListeningPack(val id: String, val title: String, val level: String, val lines: List<ListeningLine>) {
    fun toJson(): JSONObject = JSONObject().put("schema_version", 1).put("id", id).put("title", title)
        .put("level", level).put("lines", JSONArray().apply {
            lines.forEach { put(JSONObject().put("id", it.id).put("speaker", it.speaker)
                .put("text_en", it.en).put("text_zh", it.zh)) }
        })
    companion object {
        fun fromJson(data: JSONObject): ListeningPack {
            require(data.optInt("schema_version") == 1) { "Unsupported dialogue version" }
            val id = data.getString("id")
            require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
            val raw = data.getJSONArray("lines")
            require(raw.length() in 1..300) { "Dialogue line count must be 1..300" }
            val lines = (0 until raw.length()).map {
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
)

/** Uses AtomicFile so a crash cannot destroy a half-written learning history. */
internal class ListeningStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "offline-listening-state.json"))
    private var root = read()
    private val _state = MutableStateFlow(snapshot())
    val state = _state.asStateFlow()

    private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject().put("schema_version", 1)
            .put("device_id", UUID.randomUUID().toString())
            .put("packs", JSONArray()).put("events", JSONArray())
        return JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }).also {
            check(it.optInt("schema_version") == 1) { "Unsupported local listening data" }
        }
    }

    private fun snapshot(): ListeningSnapshot {
        val packsArray = root.optJSONArray("packs") ?: JSONArray()
        val events = root.optJSONArray("events") ?: JSONArray()
        val packs = (0 until packsArray.length()).mapNotNull {
            runCatching { ListeningPack.fromJson(packsArray.getJSONObject(it)) }.getOrNull()
        }
        val savedWords = HashSet<String>()
        val reviewedWords = HashSet<String>()
        var pending = 0
        var due = 0
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            if (!e.optBoolean("synced")) pending++
            val word = e.optString("word").lowercase()
            if (e.optString("type") == "saved") savedWords.add(word)
            if (e.optString("type") == "reviewed" && e.optString("rating") in listOf("good", "easy")) reviewedWords.add(word)
        }
        // Until FSRS is activated, a seen/reviewed count is not a validated long-term-memory estimate.
        return ListeningSnapshot(packs, pending, savedWords.size, due,
            savedWords.count { it !in reviewedWords }, root.optString("device_id"))
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
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filter { !it.optBoolean("synced") }.map(::remoteEvent)
    }
    @Synchronized fun markUploaded(ids: Set<String>) {
        val events = root.optJSONArray("events") ?: return
        for (i in 0 until events.length()) {
            val item = events.getJSONObject(i)
            if (item.optString("id") in ids) item.put("synced", true)
        }
        persist()
    }
    @Synchronized fun addWord(word: String, pack: ListeningPack, line: ListeningLine) {
        val clean = word.lowercase().trim().replace(Regex("[^a-z'-]"), "")
        if (!clean.matches(Regex("[a-z][a-z'-]{0,49}"))) return
        val items = root.optJSONArray("events") ?: JSONArray().also { root.put("events", it) }
        // One event per word in a sentence; repeated taps are not additional learning evidence.
        if ((0 until items.length()).any {
            val x = items.getJSONObject(it)
            x.optString("type") == "saved" && x.optString("word") == clean &&
                x.optString("dialogue_id") == pack.id && x.optString("line_id") == line.id
        }) return
        items.put(event("saved", clean, pack.id, line.id).put("sentence", line.en))
        persist()
    }
    @Synchronized fun addReview(word: String, rating: String) {
        require(rating in listOf("again", "hard", "good", "easy"))
        root.getJSONArray("events").put(event("reviewed", word.lowercase(), "", "").put("rating", rating))
        persist()
    }
    private fun event(type: String, word: String, dialogueId: String, lineId: String): JSONObject =
        JSONObject().put("schema_version", 1).put("id", UUID.randomUUID().toString())
            .put("device_id", root.optString("device_id"))
            .put("created_at", OffsetDateTime.now().toString())
            .put("type", type).put("skill", "listening").put("word", word)
            .put("dialogue_id", dialogueId).put("line_id", lineId).put("synced", false)

    /** The synced flag is a device-local outbox field, not part of the remote immutable event. */
    private fun remoteEvent(original: JSONObject): JSONObject = JSONObject(original.toString()).apply { remove("synced") }

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
