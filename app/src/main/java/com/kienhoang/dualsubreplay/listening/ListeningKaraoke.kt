package com.kienhoang.dualsubreplay.listening

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pastel accents shared by the progress track and the spoken tokens. */
private val rainbow = listOf(
    Color(0xFF68DCE4),
    Color(0xFF9B98FF),
    Color(0xFFFF8CC0),
    Color(0xFFFFC978),
    Color(0xFF6CE5AF),
)
private val night = Color(0xFF172039)
private val waiting = Color(0xFFC1C9DC)
private val wordPattern = Regex("[A-Za-z]+(?:['-][A-Za-z]+)*")
private val visibleTokens = Regex("\\S+\\s*")

/**
 * Uses actual character ranges from Android's onRangeStart callback, never fabricated timings.
 * When the chosen TTS engine has no range callbacks the bar is indeterminate.
 */
@Composable
internal fun ListeningKaraokeCard(
    pack: ListeningPack,
    playback: ListeningPlaybackState,
    showText: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onToggleText: () -> Unit,
    onVoices: () -> Unit,
    onWordTap: (word: String, line: ListeningLine) -> Unit,
) {
    val active = playback.playing && playback.packId == pack.id
    val rowIndex = if (playback.packId == pack.id) playback.currentLine.coerceIn(pack.lines.indices) else 0
    val line = pack.lines[rowIndex]
    val synchronized = active && playback.hasWordTimings
    val fraction = if (synchronized) {
        playback.spokenStart.toFloat() / line.en.length.coerceAtLeast(1)
    } else {
        0f
    }
    val smoothProgress by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(180),
        label = "spoken-word-progress",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = night),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (active) "♪ 正在播放 · ${line.speaker}" else "♪ ${pack.title}",
                    color = Color(0xFFFAFAFF),
                    fontWeight = FontWeight.SemiBold,
                )
                Text("${rowIndex + 1} / ${pack.lines.size}", color = waiting)
            }
            if (showText) {
                ListeningRainbowWords(
                    text = line.en,
                    active = active,
                    synced = synchronized,
                    start = playback.spokenStart,
                    end = playback.spokenEnd,
                    onWordTap = { onWordTap(it, line) },
                    onDark = true,
                )
                Text(line.textZh(), color = waiting, fontSize = 14.sp)
            } else {
                Text("盲听模式 · 不显示英文与译文", color = waiting, fontSize = 15.sp)
            }
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
            ) {
                if (synchronized) {
                    Box(
                        Modifier.fillMaxWidth(smoothProgress).height(8.dp)
                            .background(Brush.horizontalGradient(rainbow)),
                    )
                } else if (active) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = rainbow[1],
                        trackColor = Color.Transparent,
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.horizontalGradient(rainbow)))
                }
            }
            Text(
                when {
                    active && synchronized -> "跟随实际语音位置逐词高亮"
                    active -> "正在朗读 · 当前语音引擎未提供逐词时间戳"
                    playback.packId == pack.id && playback.error != null -> playback.error
                    else -> "选择材料后点击播放"
                },
                color = waiting,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onPlay) { Text("▶ 播放") }
                OutlinedButton(onClick = onStop) { Text("■ 停止") }
                TextButton(onClick = onToggleText) {
                    Text(if (showText) "隐藏字幕" else "显示字幕", color = rainbow[0])
                }
            }
            TextButton(onClick = onVoices) {
                Text(
                    "更换离线声音 · ${playback.voiceName ?: "系统 TTS"}",
                    color = rainbow[0],
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
internal fun ListeningRainbowWords(
    text: String,
    active: Boolean,
    synced: Boolean,
    start: Int,
    end: Int,
    onWordTap: (String) -> Unit,
    onDark: Boolean,
) {
    val normal = if (onDark) waiting else Color(0xFF49546A)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        visibleTokens.findAll(text).forEachIndexed { n, token ->
            val word = wordPattern.find(token.value)?.value
            val inRange = synced && token.range.first < end && token.range.last >= start
            val finished = synced && token.range.last < start
            val tint = when {
                inRange -> Color.White
                finished -> rainbow[n % rainbow.size]
                active && synced -> normal
                else -> rainbow[n % rainbow.size]
            }
            val shape = RoundedCornerShape(8.dp)
            Text(
                token.value.trimEnd(),
                modifier = Modifier
                    .clip(shape)
                    .background(if (inRange) rainbow[n % rainbow.size].copy(alpha = 0.35f) else Color.Transparent)
                    .then(if (word != null) Modifier.clickable { onWordTap(word) } else Modifier)
                    .padding(horizontal = 3.dp, vertical = 3.dp),
                color = tint,
                fontSize = if (onDark) 21.sp else 16.sp,
                fontWeight = if (inRange) FontWeight.Bold else FontWeight.Medium,
                textDecoration = if (inRange) TextDecoration.Underline else TextDecoration.None,
            )
        }
    }
}
