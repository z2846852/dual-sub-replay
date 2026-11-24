package com.kienhoang.dualsubreplay.listening

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kienhoang.dualsubreplay.MainActivity
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

internal data class ListeningPlaybackState(
    val packId: String? = null,
    val currentLine: Int = 0,
    val playing: Boolean = false,
    val paused: Boolean = false,
    val error: String? = null,
    /** Android TTS reports these character positions only when its engine supports range callbacks. */
    val spokenStart: Int = 0,
    val spokenEnd: Int = 0,
    val hasWordTimings: Boolean = false,
    val voiceName: String? = null,
    /** True only after a one-sentence recall playback has completely finished. */
    val finished: Boolean = false,
)

/** Kept outside Compose so the foreground Service can update the current line. */
internal object ListeningPlayback {
    val state = MutableStateFlow(ListeningPlaybackState())

    fun start(
        context: Context,
        pack: ListeningPack,
        lineIndex: Int = 0,
        oneShot: Boolean = false,
    ) {
        val intent =
            Intent(context, ListeningSpeechService::class.java).apply {
                action = ListeningSpeechService.PLAY
                putExtra(ListeningSpeechService.CONTENT, pack.toJson().toString())
                putExtra(ListeningSpeechService.LINE, lineIndex)
                putExtra(ListeningSpeechService.ONE_SHOT, oneShot)
            }
        ContextCompat.startForegroundService(context, intent)
    }

    fun pause(context: Context) {
        context.startService(Intent(context, ListeningSpeechService::class.java).apply { action = ListeningSpeechService.PAUSE })
    }

    fun resume(context: Context) {
        context.startService(Intent(context, ListeningSpeechService::class.java).apply { action = ListeningSpeechService.RESUME })
    }

    fun tryLocal(context: Context) {
        context.startService(Intent(context, ListeningSpeechService::class.java).apply { action = ListeningSpeechService.TRY_LOCAL })
    }

    fun stop(context: Context) {
        context.startService(Intent(context, ListeningSpeechService::class.java).apply { action = ListeningSpeechService.STOP })
    }
}

/**
 * Background dialogue playback. Prefers the same Google web voice as Practice when connected,
 * keeps successful MP3 recordings for later offline listening, and falls back to installed Android
 * TTS on network errors/unsupported long sentences. Never changes YouTube's playback.
 */
internal class ListeningSpeechService :
    Service(),
    TextToSpeech.OnInitListener {
    companion object {
        const val PLAY = "offline-listening.PLAY"
        const val PAUSE = "offline-listening.PAUSE"
        const val RESUME = "offline-listening.RESUME"
        const val TRY_LOCAL = "offline-listening.TRY_LOCAL"
        const val STOP = "offline-listening.STOP"
        const val CONTENT = "dialogue"
        const val LINE = "line"
        const val ONE_SHOT = "one_shot"
        private const val CHANNEL = "offline_listening"
        private const val NOTICE = 18209
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pack: ListeningPack? = null
    private var index = 0
    private var running = false
    private var oneShot = false
    private var utteranceGeneration = 0L
    private var activeUtterance: String? = null
    private var voiceName: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speechJob: Job? = null
    private var player: MediaPlayer? = null
    private var natural: ListeningNaturalVoice? = null
    private var generation = 0L

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "离线英语听力",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == STOP) {
            stopNow()
            return START_NOT_STICKY
        }
        if (intent?.action == PAUSE) {
            pauseNow()
            return START_NOT_STICKY
        }
        if (intent?.action == TRY_LOCAL) {
            if (pack != null && !running) {
                running = true
                ListeningPlayback.state.value =
                    ListeningPlayback.state.value.copy(playing = true, paused = false, error = null)
                speakLocal()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == RESUME) {
            if (pack != null && !running) {
                running = true
                if (player != null) {
                    runCatching { player?.start() }.onFailure { speakLine() }
                    ListeningPlayback.state.value =
                        ListeningPlayback.state.value.copy(playing = true, paused = false)
                } else {
                    speakLine()
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action != PLAY) return START_NOT_STICKY
        try {
            val parsed = ListeningPack.fromJson(org.json.JSONObject(intent.getStringExtra(CONTENT) ?: ""))
            pack = parsed
            index = intent.getIntExtra(LINE, 0).coerceIn(0, parsed.lines.lastIndex)
            oneShot = intent.getBooleanExtra(ONE_SHOT, false)
            running = true
            cancelCurrentAudio()
            ListeningPlayback.state.value = ListeningPlaybackState(parsed.id, index, true)
            startForeground(NOTICE, notification())
            speakLine()
        } catch (e: Exception) {
            reportError("对话无法播放：" + (e.message ?: "未知错误"))
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            reportError("系统 TTS 初始化失败，请检查语音引擎")
            return
        }
        val engine = tts ?: return
        // Honour the system's chosen offline English voice first. This lets users switch to
        // an installed neural TTS engine (Kokoro/Piper) without bundling its models in this APK.
        val currentVoice = engine.voice
        val voice =
            currentVoice?.takeIf {
                it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired
            } ?: engine.voices
                ?.filter { it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired }
                ?.sortedWith(
                    compareByDescending<android.speech.tts.Voice> { it.quality }
                        .thenByDescending { it.locale.country == "US" },
                )?.firstOrNull()
        if (voice == null || engine.setVoice(voice) < TextToSpeech.SUCCESS) {
            reportError("未找到离线英语语音包，请在系统文字转语音设置中下载")
            return
        }
        voiceName = voice.name
        engine.setSpeechRate(0.90f)
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    handler.post {
                        if (running && utteranceId == activeUtterance) {
                            val previous = ListeningPlayback.state.value
                            if (previous.playing && previous.packId == pack?.id) {
                                ListeningPlayback.state.value =
                                    previous.copy(
                                        spokenStart = start.coerceAtLeast(0),
                                        spokenEnd = end.coerceAtLeast(start),
                                        hasWordTimings = true,
                                    )
                            }
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    handler.post {
                        if (running && utteranceId == activeUtterance) {
                            nextLine()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handler.post {
                        if (running && utteranceId == activeUtterance) {
                            reportError("TTS 朗读失败，检查离线语音包")
                        }
                    }
                }
            },
        )
        ready = true
        if (running) speakLocal()
    }

    private fun speakLine() {
        val current = pack ?: return
        if (!running) return
        val line = current.lines.getOrNull(index) ?: return stopNow()
        cancelCurrentAudio()
        val requestId = generation
        val preferOnline = getSharedPreferences("dual_sub_preferences", MODE_PRIVATE)
            .getBoolean("listening_natural_voice", true)
        ListeningPlayback.state.value = ListeningPlaybackState(
            current.id,
            index,
            true,
            voiceName = if (preferOnline) "Google 自然发音（优先）" else voiceName,
        )
        getSystemService(NotificationManager::class.java).notify(NOTICE, notification())
        if (!preferOnline) {
            speakLocal()
            return
        }
        speechJob = scope.launch {
            val file = (natural ?: ListeningNaturalVoice(this@ListeningSpeechService).also { natural = it })
                .cachedOrDownload(line.en)
            if (!running || generation != requestId) return@launch
            if (file != null && playRecording(file, requestId)) return@launch
            // Do not silently swap the learning voice. The user may explicitly try the phone TTS.
            running = false
            ListeningPlayback.state.value =
                ListeningPlayback.state.value.copy(
                    playing = false,
                    paused = true,
                    error = "未缓存且 Google 发音暂不可用",
                    voiceName = null,
                )
        }
    }

    /** Plays the cached Google MP3; returns false on an Android media decoder failure. */
    private fun playRecording(file: java.io.File, requestId: Long): Boolean {
        return try {
            val media = MediaPlayer()
            media.setDataSource(file.absolutePath)
            media.setOnCompletionListener {
                if (running && requestId == generation) nextLine()
            }
            media.setOnErrorListener { _, _, _ ->
                if (running && requestId == generation) {
                    releasePlayer()
                    speakLocal()
                }
                true
            }
            media.prepare()
            if (!running || requestId != generation) {
                media.release()
                false
            } else {
                player = media
                ListeningPlayback.state.value = ListeningPlayback.state.value.copy(
                    voiceName = "Google 自然发音 · 已缓存可离线播放",
                )
                media.start()
                true
            }
        } catch (_: Exception) {
            releasePlayer()
            false
        }
    }

    /** Used when offline, a sentence is longer than Google's request limit, or Google is down. */
    private fun speakLocal() {
        if (!running) return
        if (tts == null) {
            tts = TextToSpeech(this, this)
            return
        }
        if (!ready) return
        val current = pack ?: return
        val line = current.lines.getOrNull(index) ?: return stopNow()
        val utteranceId = "${current.id}:${index}:${++utteranceGeneration}"
        activeUtterance = utteranceId
        ListeningPlayback.state.value = ListeningPlayback.state.value.copy(
            playing = true,
            paused = false,
            error = null,
            voiceName = voiceName ?: "本机 TTS",
        )
        val result = tts?.speak(line.en, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) reportError("离线发音失败")
    }

    private fun nextLine() {
        if (oneShot) {
            running = false
            cancelCurrentAudio()
            ListeningPlayback.state.value =
                ListeningPlayback.state.value.copy(playing = false, paused = false, finished = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        index++
        if (index >= (pack?.lines?.size ?: 0)) stopNow() else speakLine()
    }

    private fun releasePlayer() {
        val old = player
        player = null
        runCatching { old?.release() }
    }

    private fun cancelCurrentAudio() {
        generation++
        speechJob?.cancel()
        speechJob = null
        activeUtterance = null
        releasePlayer()
        tts?.stop()
    }

    private fun notification(): android.app.Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ListeningSpeechService::class.java).apply { action = STOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app_logo_mark)
            .setContentTitle(pack?.title ?: "离线英语听力")
            .setContentText(pack?.lines?.getOrNull(index)?.en ?: "正在朗读")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stop)
            .build()
    }

    /** Pause the current sentence, keep the pack and position for explicit resume. */
    private fun pauseNow() {
        if (!running) return
        running = false
        if (player != null) {
            player?.pause()
        } else {
            // Downloading may still complete in the background; never play after pause.
            generation++
            speechJob?.cancel()
            speechJob = null
            activeUtterance = null
            tts?.stop()
        }
        ListeningPlayback.state.value = ListeningPlayback.state.value.copy(playing = false, paused = true)
        getSystemService(NotificationManager::class.java).notify(NOTICE, notification())
    }

    private fun reportError(message: String) {
        ListeningPlayback.state.value = ListeningPlaybackState(pack?.id, index, false, error = message)
        running = false
        cancelCurrentAudio()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopNow() {
        running = false
        cancelCurrentAudio()
        ListeningPlayback.state.value = ListeningPlaybackState(pack?.id, index, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelCurrentAudio()
        scope.cancel()
        tts?.shutdown()
        tts = null
        ready = false
        super.onDestroy()
    }
}
