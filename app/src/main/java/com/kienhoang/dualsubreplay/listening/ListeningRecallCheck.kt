package com.kienhoang.dualsubreplay.listening

import java.text.Normalizer
import java.util.Locale

/** One position in the shortest word-level edit script. A missing word has no actual token. */
internal data class RecallWordDifference(
    val expected: String?,
    val actual: String?,
    val matches: Boolean,
    /** A missing, explicitly whitelisted proper name or non-essential slang term. */
    val optionalOmission: Boolean = false,
)

internal data class RecallComparison(
    val words: List<RecallWordDifference>,
) {
    val differences: Int get() = words.count { !it.matches }
    val perfect: Boolean get() = words.isNotEmpty() && differences == 0
    val optionalOmissions: List<String>
        get() = words.filter { it.optionalOmission }.mapNotNull { it.expected }
    val blockingDifferences: Int get() = words.count { !it.matches && !it.optionalOmission }
    /** A relaxed pass is not a perfect answer: keep the original visible and require manual Next. */
    val softPass: Boolean
        get() = optionalOmissions.isNotEmpty() && blockingDifferences == 0 && words.any { it.matches }
}

/**
 * Compares text supplied by the phone's own IME speech recognition against a dialogue line.
 * Case, whitespace and punctuation are not graded; every actual word (including a/the) is.
 * This measures transcription similarity, NOT the learner's acoustic pronunciation.
 */
internal object ListeningRecallCheck {
    private val wordPattern = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*""")
    private val apostrophes = Regex("['’]")
    private val numberWords = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3",
        "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11",
        "twelve" to "12", "thirteen" to "13", "fourteen" to "14",
        "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
        "eighteen" to "18", "nineteen" to "19", "twenty" to "20",
        "thirty" to "30", "forty" to "40", "fifty" to "50",
        "sixty" to "60", "seventy" to "70", "eighty" to "80", "ninety" to "90",
    )
    /** Restricted to the sitcom study pack; never infer a proper name from sentence-initial caps. */
    private val optionalNames = setOf(
        "max", "caroline", "han", "oleg", "sophie", "earl", "andy",
        "chestnut", "peach", "darius", "deke", "candy", "johnny",
        "luis", "nash", "randy", "jennifer", "martha", "bebe",
        "shoshanna", "sandra", "sasha", "channing", "bobby", "barbara",
        "nicolas", "stewart", "nancy", "jessica", "joe", "kim",
        "mary", "john", "brian", "bob", "lawrence", "patrick",
    )
    /** Do not waive useful conversational forms such as gonna, wanna, gotta, ain't. */
    private val optionalSlang = setOf(
        "bitch", "bitches", "bullshit", "crap", "crappy", "asshole",
        "jackass", "friggin", "frigging", "freakin", "freaking",
        "fucking", "fuck", "shitty", "shit", "douche", "douchebag",
        "ain't", "y'all", "yo", "dude", "bro", "uh", "uhh", "um", "umm", "erm",
    )

    private val isContractions =
        setOf("he", "she", "it", "there", "that", "what", "who", "where", "here", "how", "when", "why")

    private data class Word(
        val shown: String,
        val key: String,
    )

    /** Expand common, unambiguous spoken contractions before aligning individual words. */
    private fun expand(word: Word): List<Word> {
        val key = word.key
        val expanded =
            when {
                key == "can't" -> listOf("can", "not")
                key == "won't" -> listOf("will", "not")
                key == "shan't" -> listOf("shall", "not")
                key == "cannot" -> listOf("can", "not")
                key == "let's" -> listOf("let", "us")
                key == "i'm" -> listOf("i", "am")
                key.endsWith("n't") -> listOf(key.dropLast(3), "not")
                key.endsWith("'re") -> listOf(key.dropLast(3), "are")
                key.endsWith("'ve") -> listOf(key.dropLast(3), "have")
                key.endsWith("'ll") -> listOf(key.dropLast(3), "will")
                key.endsWith("'d") -> listOf(key.dropLast(2), "would")
                key.endsWith("'s") && key.dropLast(2) in isContractions ->
                    listOf(key.dropLast(2), "is")
                else -> return listOf(word)
            }
        // One physical word can align with two spoken words without showing a duplicate.
        return expanded.mapIndexed { index, normalized ->
            Word(if (index == 0) word.shown else "", normalized)
        }
    }

    private fun words(text: String): List<Word> =
        wordPattern
            .findAll(Normalizer.normalize(text, Normalizer.Form.NFKC))
            .map { match ->
                val shown = match.value
                Word(
                    shown,
                    shown.lowercase(Locale.ROOT).replace(apostrophes, "'")
                        .let { numberWords[it] ?: it },
                )
            }.flatMap(::expand)
            .toList()

    fun compare(
        expected: String,
        actual: String,
        ignoreOptional: Boolean = false,
    ): RecallComparison {
        val wanted = words(expected)
        val said = words(actual)
        val optionalExpected = wanted.map { word ->
            ignoreOptional && (
                word.key in optionalNames || word.key in optionalSlang ||
                    (word.key.endsWith("'s") && word.key.removeSuffix("'s") in optionalNames)
            )
        }
        val distances = Array(wanted.size + 1) { IntArray(said.size + 1) }
        for (i in wanted.indices) {
            distances[i + 1][0] = distances[i][0] + if (optionalExpected[i]) 0 else 1
        }
        for (j in said.indices) distances[0][j + 1] = j + 1
        for (i in 1..wanted.size) {
            for (j in 1..said.size) {
                val cost = if (wanted[i - 1].key == said[j - 1].key) 0 else 1
                distances[i][j] =
                    minOf(
                        distances[i - 1][j] + if (optionalExpected[i - 1]) 0 else 1,
                        distances[i][j - 1] + 1,
                        distances[i - 1][j - 1] + cost,
                    )
            }
        }

        val result = ArrayList<RecallWordDifference>(maxOf(wanted.size, said.size))
        var i = wanted.size
        var j = said.size
        while (i > 0 || j > 0) {
            val same = i > 0 && j > 0 && wanted[i - 1].key == said[j - 1].key
            when {
                same && distances[i][j] == distances[i - 1][j - 1] -> {
                    result += RecallWordDifference(wanted[--i].shown, said[--j].shown, matches = true)
                }
                i > 0 && j > 0 && distances[i][j] == distances[i - 1][j - 1] + 1 -> {
                    result += RecallWordDifference(wanted[--i].shown, said[--j].shown, matches = false)
                }
                i > 0 && distances[i][j] == distances[i - 1][j] +
                    (if (optionalExpected[i - 1]) 0 else 1) -> {
                    val omitted = --i
                    result += RecallWordDifference(
                        wanted[omitted].shown, null, matches = false,
                        optionalOmission = optionalExpected[omitted],
                    )
                }
                else -> {
                    check(j > 0)
                    result += RecallWordDifference(null, said[--j].shown, matches = false)
                }
            }
        }
        result.reverse()
        return RecallComparison(result)
    }
}
