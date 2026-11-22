package com.kienhoang.dualsubreplay.listening

import android.content.Context
import com.kienhoang.dualsubreplay.translation.MAX_GOOGLE_SPEECH_CHARS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Preload every distinct line after a dialogue sync, not at the point of tapping a word.
 * The Google web TTS endpoint is unofficial and has undocumented rate limits. Only one request
 * is issued at a time, with a delay between cache misses. On repeated failure we back off rather
 * than hammering Google; the next sync or launch resumes the missing files.
 */
internal object ListeningVoicePrefetch {
    internal data class Progress(
        val complete: Int = 0,
        val total: Int = 0,
        val failed: Int = 0,
        val running: Boolean = false,
        val warning: String? = null,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(Progress())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var lastRequest: List<String>? = null

    @Synchronized
    fun schedule(context: Context, packs: List<ListeningPack>) {
        val texts = packs.flatMap { it.lines }.map { it.en.trim() }.filter { it.isNotBlank() }.distinct()
        if (texts.isEmpty()) return
        if (job?.isActive == true && lastRequest == texts) return
        job?.cancel()
        lastRequest = texts
        val app = context.applicationContext
        job = scope.launch {
            val voice = ListeningNaturalVoice(app)
            var complete = texts.count { voice.cached(it) != null }
            var failures = 0
            var consecutiveFailures = 0
            var warning: String? = null
            _state.value = Progress(complete, texts.size, running = complete < texts.size)
            for (line in texts) {
                if (!isActive) return@launch
                if (voice.cached(line) != null) continue
                if (line.length > MAX_GOOGLE_SPEECH_CHARS) {
                    failures++
                    warning = "部分长句超过单次 Google 语音请求长度"
                    continue
                }
                val result = voice.cachedOrDownload(line)
                if (!isActive) return@launch
                if (result != null) {
                    complete++
                    consecutiveFailures = 0
                } else {
                    failures++
                    consecutiveFailures++
                    warning = "语音接口暂不可用，未缓存的句子将在下次同步重试"
                    if (consecutiveFailures >= 2) break
                }
                _state.value = Progress(complete, texts.size, failures, running = true)
                delay(2_000L)
            }
            _state.value = Progress(complete, texts.size, failures, running = false, warning = warning)
        }
    }
}
