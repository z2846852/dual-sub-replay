package com.kienhoang.dualsubreplay.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ListeningSeriesArchiveTest {
    private val path = "content/series/2-broke-girls/s01/e01/p01.json"
    private val id = "2bg-s01e01-p01"

    private fun archived(items: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            items.forEach { (file, data) ->
                zip.putNextEntry(ZipEntry("z2846852-english-abcdef123456/" + file))
                zip.write(data.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun pack(packId: String = id): String =
        """{"schema_version":1,"id":"$packId","title":"S01E01","level":"B1","kind":"series_2_broke_girls","lines":[{"id":"l001","speaker":"A","text_en":"I can help.","text_zh":"我能帮忙。"}]}"""

    @Test
    fun importsOnlyExpectedValidatedSubtitlesFromOneArchive() {
        val contents = archived(mapOf(
            "README.md" to "unrelated file",
            path to pack(),
            "content/series/2-broke-girls/s01/e99/p01.json" to "{}",
        ))
        var loaded = 0
        val packs = ListeningSeriesArchive.readMissing(
            ByteArrayInputStream(contents), mapOf(path to id),
        ) { loaded = it }
        assertEquals(1, loaded)
        assertEquals(id, packs.single().id)
        assertEquals("I can help.", packs.single().lines.single().en)
    }

    @Test
    fun archiveMustContainTheEntireRequestedSet() {
        val contents = archived(mapOf(path to pack()))
        assertThrows(IllegalStateException::class.java) {
            ListeningSeriesArchive.readMissing(
                ByteArrayInputStream(contents),
                mapOf(path to id, "content/series/2-broke-girls/s01/e01/p02.json" to "2bg-s01e01-p02"),
            )
        }
    }

    @Test
    fun contentIdCannotBeReplacedByAnotherEpisode() {
        val contents = archived(mapOf(path to pack("2bg-s02e01-p01")))
        assertThrows(IllegalArgumentException::class.java) {
            ListeningSeriesArchive.readMissing(ByteArrayInputStream(contents), mapOf(path to id))
        }
    }
}
