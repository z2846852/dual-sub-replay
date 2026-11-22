package com.kienhoang.dualsubreplay.listening

import android.content.Context
import com.kienhoang.dualsubreplay.translation.GoogleWebSpeech
import com.kienhoang.dualsubreplay.translation.MAX_GOOGLE_SPEECH_CHARS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    companion object {
        // Playback and the batch preloader share the files and must not race on *.part.
        private val downloadLock = Mutex()
    }

    fun cached(text: String): File? {
        if (text.isBlank()) return null
        val file = File(folder, filename(text))
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    private fun filename(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".mp3"

    suspend fun cachedOrDownload(text: String): File? = downloadLock.withLock {
        withContext(Dispatchers.IO) {
        if (text.isBlank() || text.length > MAX_GOOGLE_SPEECH_CHARS) return@withContext null
        val target = File(folder, filename(text))
        if (target.isFile && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }
        folder.mkdirs()
        // Avoid creating files on a nearly full device. Existing audio is never deleted on a sync.
        if (folder.usableSpace < 128L * 1024 * 1024) return@withContext null
        val temp = File(folder, target.nameWithoutExtension + ".part")
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
        target
        }
    }
}

