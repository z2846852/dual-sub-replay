package com.kienhoang.dualsubreplay.listening

import org.json.JSONArray
import org.json.JSONObject

/** Episode metadata is small; subtitle parts are loaded only after the user selects an episode. */
internal data class ListeningSeriesPart(
    val id: String,
    val path: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("path", path)

    companion object {
        private val validPath =
            Regex("content/series/2-broke-girls/s[0-9]{2}/e[0-9]{2}/p[0-9]{2}\\.json")

        fun fromJson(source: JSONObject): ListeningSeriesPart {
            val id = source.getString("id")
            val path = source.getString("path")
            require(id.matches(Regex("2bg-s[0-9]{2}e[0-9]{2}-p[0-9]{2}")))
            require(path.matches(validPath)) { "Invalid series subtitle path" }
            return ListeningSeriesPart(id, path)
        }
    }
}

internal data class ListeningSeriesEpisode(
    val season: Int,
    val episode: Int,
    val title: String,
    val parts: List<ListeningSeriesPart>,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("season", season)
            .put("episode", episode)
            .put("title", title)
            .put("parts", JSONArray().apply { parts.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(source: JSONObject, season: Int? = null): ListeningSeriesEpisode {
            val seasonNo = season ?: source.getInt("season")
            val episodeNo = source.getInt("episode")
            require(seasonNo in 1..6 && episodeNo in 1..40)
            val entries = source.getJSONArray("parts")
            require(entries.length() in 1..20) { "Episode requires 1..20 subtitle parts" }
            val parts = (0 until entries.length()).map {
                ListeningSeriesPart.fromJson(entries.getJSONObject(it))
            }
            val prefix = "2bg-s%02de%02d-".format(seasonNo, episodeNo)
            require(parts.all { it.id.startsWith(prefix) })
            require(parts.map { it.id }.distinct().size == parts.size)
            val title = source.optString("title").ifBlank { "S%02dE%02d".format(seasonNo, episodeNo) }
            return ListeningSeriesEpisode(seasonNo, episodeNo, title.take(120), parts)
        }
    }
}

/** A separate pack namespace ensures the sitcom never appears in normal CEFR drills. */
internal const val SITCOM_PACK_KIND = "series_2_broke_girls"
