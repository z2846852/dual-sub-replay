package com.kienhoang.dualsubreplay.listening

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ListeningSeriesTest {
    @Test
    fun parsesSeasonEpisodeAndParts() {
        val episode = JSONObject()
            .put("episode", 1)
            .put("title", "S01E01")
            .put("parts", JSONArray().put(
                JSONObject().put("id", "2bg-s01e01-p01")
                    .put("path", "content/series/2-broke-girls/s01/e01/p01.json"),
            ))
        val parsed = ListeningSeriesEpisode.fromJson(episode, 1)
        assertEquals(1, parsed.season)
        assertEquals(1, parsed.episode)
        assertEquals("2bg-s01e01-p01", parsed.parts.first().id)
        assertEquals("S01E01", ListeningSeriesEpisode.fromJson(parsed.toJson()).title)
    }

    @Test
    fun rejectsPathsOutsideSeriesAndMissingParts() {
        val bad = JSONObject().put("id", "2bg-s01e01-p01")
            .put("path", "content/dialogues/other.json")
        assertThrows(IllegalArgumentException::class.java) {
            ListeningSeriesPart.fromJson(bad)
        }
        val empty = JSONObject().put("season", 1).put("episode", 1)
            .put("parts", JSONArray())
        assertThrows(IllegalArgumentException::class.java) {
            ListeningSeriesEpisode.fromJson(empty)
        }
    }
}
