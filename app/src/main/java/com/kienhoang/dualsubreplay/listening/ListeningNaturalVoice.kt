package com.kienhoang.dualsubreplay.listening

import android.content.Context
import com.kienhoang.dualsubreplay.translation.GoogleWebSpeech
import com.kienhoang.dualsubreplay.translation.MAX_GOOGLE_SPEECH_CHARS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The same Google Translate web voice used by the original Practice word card.
 *
 * A first listen requires the network; saved MP3 files can play offline afterwards. This is not
 * an official stable API; the caller must fall back to Android TTS when unavailable.
 * The YouTube Practice sentence clip is different: that is recorded *real video audio* and cannot
 * be synthesized from an unrelated offline listening script.
 */
internal class ListeningNaturalVoice(context: Context) {
    private val folder = File(context.applicationContext.filesDir, "listening-natural-voice")
    private val google = GoogleWebSpeech()
    private val maxFiles = 400

    suspend fun cachedOrDownload(text: String): File? = withContext(Dispatchers.IO) {
        if (text.isBlank() || text.length > MAX_GOOGLE_SPEECH_CHARS) return@withContext null
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val target = File(folder, "$digest.mp3")
        if (target.isFile && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }
        folder.mkdirs()
        val temp = File(folder, "$digest.part")
        val ok = try {
            google.record(text, "en", temp)
        } catch (_: Exception) {
            false
        }
        if (!ok || temp.length() == 0L) {
            temp.delete()
            return@withContext null
        }
        val saved = temp.renameTo(target)
        if (!saved) {
            temp.delete()
            return@withContext null
        }
        // Keep a bounded number of recent audio files without deleting currently playing media.
        val files = folder.listFiles()?.filter { it.name.endsWith(".mp3") }.orEmpty()
        files.sortedBy { it.lastModified() }
            .take((files.size - maxFiles).coerceAtLeast(0))
            .filter { it != target }
            .forEach { it.delete() }
        target
    }
}
