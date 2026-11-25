package com.kienhoang.dualsubreplay.listening

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Read only missing subtitle segments from the authenticated English GitHub ZIP archive.
 * The archive is never unzipped onto disk: all paths and payloads must match the season manifests.
 */
internal object ListeningSeriesArchive {
    private const val ROOT = "content/series/2-broke-girls/"
    private const val MAX_ENTRY_BYTES = 700_000
    private const val MAX_ZIP_ENTRIES = 30_000

    fun readMissing(
        input: InputStream,
        expectedByPath: Map<String, String>,
        onLoaded: (Int) -> Unit = {},
    ): List<ListeningPack> {
        if (expectedByPath.isEmpty()) return emptyList()
        val results = LinkedHashMap<String, ListeningPack>()
        ZipInputStream(input.buffered()).use { zip ->
            var scanned = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                scanned++
                check(scanned <= MAX_ZIP_ENTRIES) { "字幕归档项目数量超出限制" }
                if (!entry.isDirectory) {
                    val rootOffset = entry.name.indexOf(ROOT)
                    val path = if (rootOffset >= 0) entry.name.substring(rootOffset) else ""
                    val expectedId = expectedByPath[path]
                    if (expectedId != null) {
                        check(path !in results) { "ZIP 内字幕文件重复：$path" }
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val size = zip.read(buffer)
                            if (size < 0) break
                            check(bytes.size() + size <= MAX_ENTRY_BYTES) { "字幕文件超出大小限制：$path" }
                            bytes.write(buffer, 0, size)
                        }
                        val pack = ListeningPack.fromJson(
                            JSONObject(bytes.toString(Charsets.UTF_8.name())),
                        )
                        require(pack.id == expectedId && pack.kind == SITCOM_PACK_KIND) {
                            "字幕内容与索引不匹配：$path"
                        }
                        results[path] = pack
                        onLoaded(results.size)
                    }
                }
                zip.closeEntry()
            }
        }
        check(results.size == expectedByPath.size) {
            "下载不完整：读取到 ${results.size}/${expectedByPath.size} 个字幕分段"
        }
        return expectedByPath.keys.map { path -> requireNotNull(results[path]) }
    }
}
