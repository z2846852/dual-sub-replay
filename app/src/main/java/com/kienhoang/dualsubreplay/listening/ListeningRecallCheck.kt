package com.kienhoang.dualsubreplay.listening

import java.text.Normalizer
import java.util.Locale

/** One position in the shortest word-level edit script. A missing word has no actual token. */
internal data class RecallWordDifference(
    val expected: String?,
    val actual: String?,
    val matches: Boolean,
)

internal data class RecallComparison(
    val words: List<RecallWordDifference>,
) {
    val differences: Int get() = words.count { !it.matches }
    val perfect: Boolean get() = words.isNotEmpty() && differences == 0
}

/**
 * Compares text supplied by the phone's own IME speech recognition against a dialogue line.
 * Case, whitespace and punctuation are not graded; every actual word (including a/the) is.
 * This measures transcription similarity, NOT the learner's acoustic pronunciation.
 */
internal object ListeningRecallCheck {
    private val wordPattern = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*""")
    private val apostrophes = Regex("['’]")

    private data class Word(
        val shown: String,
        val key: String,
    )

    private fun words(text: String): List<Word> =
        wordPattern
            .findAll(Normalizer.normalize(text, Normalizer.Form.NFKC))
            .map { match ->
                val shown = match.value
                Word(shown, shown.lowercase(Locale.ROOT).replace(apostrophes, ""))
            }.toList()

    fun compare(
        expected: String,
        actual: String,
    ): RecallComparison {
        val wanted = words(expected)
        val said = words(actual)
        val distances = Array(wanted.size + 1) { IntArray(said.size + 1) }
        for (i in wanted.indices) distances[i + 1][0] = i + 1
        for (j in said.indices) distances[0][j + 1] = j + 1
        for (i in 1..wanted.size) {
            for (j in 1..said.size) {
                val cost = if (wanted[i - 1].key == said[j - 1].key) 0 else 1
                distances[i][j] =
                    minOf(
                        distances[i - 1][j] + 1,
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
                i > 0 && distances[i][j] == distances[i - 1][j] + 1 -> {
                    result += RecallWordDifference(wanted[--i].shown, null, matches = false)
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
