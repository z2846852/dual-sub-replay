package com.kienhoang.dualsubreplay.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Longer "words" are analyzer mistakes or whole lines, never kept. */
private const val MAX_KNOWN_WORD_LENGTH = 64

/** Counts of words not known yet wait this long before they are written, so a line costs no file write. */
private const val SEEN_FLUSH_DELAY_MS = 30_000L

/** A stored count above this is a broken file, not a real count. */
private const val MAX_SEEN_COUNT = 1_000

private const val PERCENT = 100

/**
 * The key a word is known by: its dictionary form when the analyzer gives one (Japanese and English),
 * else the word itself, lower-cased. Null for tokens without a letter (punctuation, numbers).
 */
internal fun knownWordKey(token: AnalyzedToken): String? = knownWordKey(token.baseForm?.takeIf { it.isNotBlank() } ?: token.text)

internal fun knownWordKey(word: String): String? =
    word
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotEmpty() && it.length <= MAX_KNOWN_WORD_LENGTH && it.any(Char::isLetter) && '\n' !in it }

/** The language a known-word list belongs to: the base code ("en" for "en-US", "zh" for "zh-Hans"). */
internal fun knownWordLanguage(code: String?): String? =
    code
        ?.substringBefore('-')
        ?.substringBefore('_')
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.matches(Regex("[a-z]{2,3}")) }

/**
 * Do not interpret Chinese or other non-Latin subtitles as English merely because a provider
 * labelled their caption track "en". Conservative filtering is preferable to poisoning a
 * permanent word history. This is script validation, not full language identification:
 * Spanish and other Latin-script languages still need a better detector.
 */
internal fun credibleEnglishSubtitle(text: String): Boolean {
    var latinLetters = 0
    var otherLetters = 0
    text.forEach { char ->
        if (char.isLetter()) {
            if (Character.UnicodeScript.of(char.code) == Character.UnicodeScript.LATIN) {
                latinLetters++
            } else {
                otherLetters++
            }
        }
    }
    return latinLetters >= 2 && otherLetters == 0
}

/** The word keys of each subtitle line, in order and with repeats. Unreliable English rows are ignored. */
internal fun lineWordKeys(
    segments: List<SubtitleSegment>,
    languageCode: String?,
): List<List<String>> =
    segments.map { segment ->
        if (knownWordLanguage(languageCode) == "en" && !credibleEnglishSubtitle(segment.originalText)) {
            emptyList()
        } else {
            LanguageAwareTokenizer.tokenize(segment.originalText, languageCode).mapNotNull(::knownWordKey)
        }
    }

/** How much of a video's words are known, from [lineWordKeys]. */
internal data class VideoComprehension(
    /** Word occurrences in the whole video. */
    val words: Int,
    /** Occurrences of known words. */
    val known: Int,
    /** Occurrences of saved (learning) words that are not marked known. */
    val learning: Int,
    /** Different words that are neither known nor saved. */
    val newWords: Set<String>,
    /** Lines with exactly one new word. */
    val oneNewWordLines: Set<Int>,
) {
    /** The known share, rounded down, so 100% means every word is known. */
    val percent: Int get() = if (words == 0) 0 else known * PERCENT / words
}

internal fun videoComprehension(
    lines: List<List<String>>,
    known: Set<String>,
    learning: Set<String>,
): VideoComprehension {
    var words = 0
    var knownCount = 0
    var learningCount = 0
    val newWords = LinkedHashSet<String>()
    val oneNew = HashSet<Int>()
    lines.forEachIndexed { index, keys ->
        val lineNew = HashSet<String>()
        keys.forEach { key ->
            words++
            when (key) {
                in known -> knownCount++
                in learning -> learningCount++
                else -> lineNew += key
            }
        }
        if (lineNew.size == 1) oneNew += index
        newWords += lineNew
    }
    return VideoComprehension(words, knownCount, learningCount, newWords, oneNew)
}

/** What one counted line did: the new counts of words not known yet, and the words that became known. */
internal data class Encounters(
    val counts: Map<String, Int>,
    val nowKnown: Set<String>,
)

/**
 * Counts one line's words for Automatic known words: each different word of the line once, except
 * known and saved (learning) words. A word seen [threshold] times becomes known and leaves the counts.
 */
internal fun countEncounters(
    counts: Map<String, Int>,
    lineKeys: Collection<String>,
    known: Set<String>,
    learning: Set<String>,
    threshold: Int,
): Encounters {
    val updated = counts.toMutableMap()
    val nowKnown = LinkedHashSet<String>()
    lineKeys.toSet().forEach { key ->
        if (key in known || key in learning) return@forEach
        val seen = (updated[key] ?: 0) + 1
        if (seen >= threshold) {
            updated.remove(key)
            nowKnown += key
        } else {
            updated[key] = seen
        }
    }
    return Encounters(updated, nowKnown)
}

/**
 * The original player slides its subtitle window and reuses list indices. An index is therefore
 * not a subtitle identity: using it silently drops later lines. Use absolute cue times, original
 * text, video and caption track instead. Keep keys for this app session so seeking/repeats, and
 * switching away and back to a video, cannot inflate the counters.
 */
internal class LineEncounters {
    private val lines = HashSet<String>()

    fun take(
        videoId: String,
        trackId: String?,
        cue: SubtitleSegment,
    ): Boolean {
        if (videoId.isBlank() || cue.startMs < 0 || cue.endMs <= cue.startMs) return false
        return lines.add(
            listOf(videoId, trackId.orEmpty(), cue.startMs.toString(), cue.endMs.toString(), cue.originalText)
                .joinToString("\u0000"),
        )
    }
}

/** A `<language>.seen` file's text: one `word<TAB>count` per line. */
internal fun parseSeenCounts(text: String): Map<String, Int> =
    text
        .lineSequence()
        .mapNotNull { line ->
            val key = knownWordKey(line.substringBefore('\t')) ?: return@mapNotNull null
            val count = line.substringAfter('\t', "").toIntOrNull()?.takeIf { it in 1..MAX_SEEN_COUNT } ?: return@mapNotNull null
            key to count
        }.toMap()

internal fun seenCountsText(counts: Map<String, Int>): String =
    counts.entries
        .sortedBy { it.key }
        .joinToString("\n") { "${it.key}\t${it.value}" }

/**
 * A portable checkpoint of the known-word list and incomplete exposure counters. Exposure counts
 * are observations, NOT evidence that the learner can recognize the word by ear.
 */
internal data class KnownWordArchive(
    val known: Map<String, Set<String>>,
    val seen: Map<String, Map<String, Int>>,
)

/**
 * The words the user marked as known, one list per language in `files/known-words/<language>.txt`
 * (one key per line), and for Automatic known words how often each word not known yet was seen, in
 * `<language>.seen`. Separate from preferences, so Reset all settings keeps them, like saved words.
 */
internal class KnownWordsRepository internal constructor(
    private val directory: File,
) {
    private val lists = ConcurrentHashMap<String, MutableStateFlow<Set<String>>>()
    private val seenLists = ConcurrentHashMap<String, MutableStateFlow<Map<String, Int>>>()
    private val loaded = ConcurrentHashMap.newKeySet<String>()
    private val unsaved = ConcurrentHashMap.newKeySet<String>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _total = MutableStateFlow<Int?>(null)

    @Volatile private var flushing: Job? = null

    /** How many words are known in every language together, once counted (see [refreshTotal]). */
    val total: StateFlow<Int?> = _total.asStateFlow()

    /** The known words of [language] (a [knownWordLanguage] code); empty until the list is read. */
    fun words(language: String): StateFlow<Set<String>> {
        lists[language]?.let { return it.asStateFlow() }
        val created = MutableStateFlow<Set<String>>(emptySet())
        val flow = lists.putIfAbsent(language, created) ?: created
        if (flow === created) scope.launch { mutex.withLock { knownList(language) } }
        return flow.asStateFlow()
    }

    /** How often each word of [language] not known yet was seen (Automatic known words); empty until read. */
    fun seen(language: String): StateFlow<Map<String, Int>> {
        seenLists[language]?.let { return it.asStateFlow() }
        val created = MutableStateFlow<Map<String, Int>>(emptyMap())
        val flow = seenLists.putIfAbsent(language, created) ?: created
        if (flow === created) scope.launch { mutex.withLock { seenList(language) } }
        return flow.asStateFlow()
    }

    /**
     * Counts one played line's words (see [countEncounters]). Words reaching [threshold] are written
     * as known at once; the other counts are written [SEEN_FLUSH_DELAY_MS] later, or by [flush].
     */
    suspend fun encounter(
        language: String,
        lineKeys: Collection<String>,
        learning: Set<String>,
        threshold: Int,
    ) = withContext(Dispatchers.IO) {
        if (lineKeys.isEmpty() || knownWordLanguage(language) != language) return@withContext
        val nowKnown =
            mutex.withLock {
                val known = knownList(language)
                val seen = seenList(language)
                val result = countEncounters(seen.value, lineKeys, known.value, learning, threshold)
                seen.value = result.counts
                unsaved += language
                if (result.nowKnown.isNotEmpty()) {
                    val updated = known.value + result.nowKnown
                    write(language, updated)
                    known.value = updated
                    _total.value = null
                }
                result.nowKnown
            }
        if (nowKnown.isNotEmpty()) refreshTotal()
        flushLater()
    }

    /** Writes the counts waiting to be written now (the video paused or the screen closed). */
    suspend fun flush() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                unsaved.toList().forEach { language ->
                    unsaved -= language
                    seenLists[language]?.let { writeSeen(language, it.value) }
                }
            }
        }

    /** [flush] without waiting, for a screen that is going away. */
    fun flushSoon() {
        if (unsaved.isNotEmpty()) scope.launch { flush() }
    }

    private fun flushLater() {
        if (flushing?.isActive == true) return
        flushing =
            scope.launch {
                delay(SEEN_FLUSH_DELAY_MS)
                flush()
            }
    }

    suspend fun setKnown(
        language: String,
        keys: Collection<String>,
        known: Boolean,
    ) = withContext(Dispatchers.IO) {
        val valid = keys.mapNotNull(::knownWordKey)
        if (valid.isEmpty() || knownWordLanguage(language) != language) return@withContext
        mutex.withLock {
            val flow = knownList(language)
            val updated = if (known) flow.value + valid else flow.value - valid.toSet()
            write(language, updated)
            flow.value = updated
            // Marked either way, a word's count starts again from nothing.
            val seen = seenList(language)
            if (valid.any(seen.value::containsKey)) {
                seen.value = seen.value - valid.toSet()
                writeSeen(language, seen.value)
            }
            _total.value = null
        }
        refreshTotal()
    }

    /** Forgets every known word in every language. */
    suspend fun clearAll() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                directory.listFiles()?.forEach { it.delete() }
                lists.values.forEach { it.value = emptySet() }
                seenLists.values.forEach { it.value = emptyMap() }
                unsaved.clear()
                _total.value = 0
            }
        }

    /**
     * Export a complete checkpoint including counters not yet flushed to disk.
     * A cloud backup cannot silently omit the last 30 seconds of listening.
     */
    suspend fun exportArchive(): KnownWordArchive =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val known = LinkedHashMap<String, Set<String>>()
                val seen = LinkedHashMap<String, Map<String, Int>>()
                storedLanguages().forEach { language ->
                    knownList(language).value.takeIf { it.isNotEmpty() }?.let { known[language] = it.toSet() }
                    seenList(language).value.takeIf { it.isNotEmpty() }?.let { seen[language] = it.toMap() }
                }
                KnownWordArchive(known, seen)
            }
        }

    /**
     * Restoring is deliberately non-destructive. Never overwrite existing local observations
     * or manually corrected known words with an older cloud checkpoint.
     */
    suspend fun restoreArchiveIfEmpty(archive: KnownWordArchive): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val languages = storedLanguages()
                if (languages.any { knownList(it).value.isNotEmpty() || seenList(it).value.isNotEmpty() }) {
                    return@withLock false
                }
                val incomingLanguages = (archive.known.keys + archive.seen.keys).toSet()
                    .filter { knownWordLanguage(it) == it }
                if (incomingLanguages.isEmpty()) return@withLock false
                require(archive.known.values.sumOf { it.size } <= 100_000) { "Word backup is too large" }
                incomingLanguages.forEach { language ->
                    val words = archive.known[language].orEmpty().mapNotNull(::knownWordKey).toSet()
                    val counts = archive.seen[language].orEmpty().mapNotNull { (raw, count) ->
                        val key = knownWordKey(raw)
                        if (key != null && key !in words && count in 1..MAX_SEEN_COUNT) key to count else null
                    }.toMap()
                    write(language, words)
                    writeSeen(language, counts)
                    knownList(language).value = words
                    seenList(language).value = counts
                }
                _total.value = incomingLanguages.sumOf { knownList(it).value.size }
                true
            }
        }

    private fun storedLanguages(): Set<String> =
        (directory.listFiles().orEmpty().mapNotNull { file ->
            file.name.takeIf { it.endsWith(".txt") || it.endsWith(".seen") }?.substringBeforeLast('.')
        } + lists.keys + seenLists.keys).filter { knownWordLanguage(it) == it }.toSet()

    /** Every language's known words, read from their files (Backup & sharing). */
    suspend fun allKnown(): Map<String, Set<String>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                directory
                    .listFiles { file -> file.name.endsWith(".txt") }
                    .orEmpty()
                    .map { it.name.removeSuffix(".txt") }
                    .filter { knownWordLanguage(it) == it }
                    .associateWith { knownList(it).value }
                    .filterValues { it.isNotEmpty() }
            }
        }

    suspend fun refreshTotal() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _total.value = directory.listFiles { file -> file.name.endsWith(".txt") }?.sumOf { file -> readFile(file).size } ?: 0
            }
        }

    private fun file(language: String) = File(directory, "$language.txt")

    /** [language]'s known words, read from its file the first time (callers hold [mutex]). */
    private fun knownList(language: String): MutableStateFlow<Set<String>> {
        val flow = lists.getOrPut(language) { MutableStateFlow(emptySet()) }
        if (loaded.add(language)) flow.value = read(language) + flow.value
        return flow
    }

    /** [language]'s seen counts, read from its file the first time (callers hold [mutex]). */
    private fun seenList(language: String): MutableStateFlow<Map<String, Int>> {
        val flow = seenLists.getOrPut(language) { MutableStateFlow(emptyMap()) }
        if (loaded.add("seen:$language")) {
            val stored = runCatching { File(directory, "$language.seen").takeIf(File::isFile)?.readText(Charsets.UTF_8) }.getOrNull()
            flow.value = parseSeenCounts(stored.orEmpty()) + flow.value
        }
        return flow
    }

    private fun read(language: String): Set<String> = readFile(file(language))

    private fun readFile(file: File): Set<String> =
        runCatching { file.takeIf(File::isFile)?.readLines(Charsets.UTF_8) }
            .getOrNull()
            .orEmpty()
            .mapNotNull(::knownWordKey)
            .toSet()

    private fun write(
        language: String,
        words: Set<String>,
    ) = replace(file(language), words.sorted().joinToString("\n"))

    private fun writeSeen(
        language: String,
        counts: Map<String, Int>,
    ) = replace(File(directory, "$language.seen"), seenCountsText(counts))

    private fun replace(
        target: File,
        text: String,
    ) {
        directory.mkdirs()
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(text, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }

    companion object {
        @Volatile private var instance: KnownWordsRepository? = null

        fun get(context: Context): KnownWordsRepository =
            instance ?: synchronized(this) {
                instance ?: KnownWordsRepository(File(context.applicationContext.filesDir, "known-words")).also { instance = it }
            }
    }
}
