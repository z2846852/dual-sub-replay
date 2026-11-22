package com.kienhoang.dualsubreplay.listening

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kienhoang.dualsubreplay.MainActivity
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

internal data class ListeningPlaybackState(
    val packId: String? = null,
    val currentLine: Int = 0,
    val playing: Boolean = false,
    val error: String? = null,
)

/** Kept outside Compose so the foreground Service can update the current line. */
internal object ListeningPlayback {
    val state = MutableStateFlow(ListeningPlaybackState())

    fun start(
        context: Context,
        pack: ListeningPack,
        lineIndex: Int = 0,
    ) {
        val intent =
            Intent(context, ListeningSpeechService::class.java).apply {
                action = ListeningSpeechService.PLAY
                putExtra(ListeningSpeechService.CONTENT, pack.toJson().toString())
                putExtra(ListeningSpeechService.LINE, lineIndex)
            }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, ListeningSpeechService::class.java).apply { action = ListeningSpeechService.STOP })
    }
}

/**
 * On-device Android TTS reading, with a foreground notification while audio plays.
 * Intentionally never invokes web TTS or any network service; rejects network-required voices.
 * This is an independent service and does not modify the original YouTube playback.
 */
internal class ListeningSpeechService :
    Service(),
    TextToSpeech.OnInitListener {
    companion object {
        const val PLAY = "offline-listening.PLAY"
        const val STOP = "offline-listening.STOP"
        const val CONTENT = "dialogue"
        const val LINE = "line"
        private const val CHANNEL = "offline_listening"
        private const val NOTICE = 18209
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pack: ListeningPack? = null
    private var index = 0
    private var running = false

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
        if (intent?.action != PLAY) return START_NOT_STICKY
        try {
            val parsed = ListeningPack.fromJson(org.json.JSONObject(intent.getStringExtra(CONTENT) ?: ""))
            pack = parsed
            index = intent.getIntExtra(LINE, 0).coerceIn(0, parsed.lines.lastIndex)
            running = true
            ListeningPlayback.state.value = ListeningPlaybackState(parsed.id, index, true)
            startForeground(NOTICE, notification())
            if (tts == null) {
                tts = TextToSpeech(this, this)
            } else if (ready) {
                speakLine()
            }
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
        val voice =
            engine.voices
                ?.filter { v ->
                    v.locale.language == Locale.ENGLISH.language && !v.isNetworkConnectionRequired
                }?.sortedByDescending { it.locale.country == "US" }
                ?.firstOrNull()
        if (voice == null || engine.setVoice(voice) < TextToSpeech.SUCCESS) {
            reportError("未找到离线英语语音包，请在系统文字转语音设置中下载")
            return
        }
        engine.setSpeechRate(0.90f)
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    handler.post {
                        if (running && utteranceId == "line-" + index) {
                            index++
                            if (index >= (pack?.lines?.size ?: 0)) stopNow() else speakLine()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handler.post { reportError("TTS 朗读失败，检查离线语音包") }
                }
            },
        )
        ready = true
        if (running) speakLine()
    }

    private fun speakLine() {
        val current = pack ?: return
        if (!ready || !running) return
        val line = current.lines.getOrNull(index) ?: return stopNow()
        ListeningPlayback.state.value = ListeningPlaybackState(current.id, index, true)
        getSystemService(NotificationManager::class.java).notify(NOTICE, notification())
        val result = tts?.speak(line.en, TextToSpeech.QUEUE_FLUSH, null, "line-" + index)
        if (result != TextToSpeech.SUCCESS) reportError("离线发音失败")
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

    private fun reportError(message: String) {
        ListeningPlayback.state.value = ListeningPlaybackState(pack?.id, index, false, message)
        running = false
        tts?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopNow() {
        running = false
        tts?.stop()
        ListeningPlayback.state.value = ListeningPlaybackState(pack?.id, index, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        ready = false
        super.onDestroy()
    }
}
