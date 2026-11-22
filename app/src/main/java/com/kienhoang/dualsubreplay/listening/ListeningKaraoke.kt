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
import androidx.compose.ui.Alignment
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.knownWordKey
import com.kienhoang.dualsubreplay.ui.newWordTint
import com.kienhoang.dualsubreplay.ui.partOfSpeechColor
import androidx.compose.runtime.remember
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
    knownWords: Set<String>,
    learningWords: Set<String>,
    markNewWords: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onTryLocal: () -> Unit,
    onToggleText: () -> Unit,
    onWordTap: (word: String, line: ListeningLine) -> Unit,
) {
    val active = playback.playing && playback.packId == pack.id
    val paused = playback.paused && playback.packId == pack.id
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
                    if (active) "♪ ${line.speaker}" else pack.title,
                    color = Color(0xFFFAFAFF),
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${rowIndex + 1}/${pack.lines.size}", color = waiting)
                    TextButton(onClick = onToggleText) {
                        Text(if (showText) "隐藏" else "显示字幕", color = rainbow[0], fontSize = 12.sp)
                    }
                }
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
                    knownWords = knownWords,
                    learningWords = learningWords,
                    markNewWords = markNewWords,
                )
                Text(line.zh, color = waiting, fontSize = 14.sp)
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
            if (playback.packId == pack.id && playback.error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("❗ " + playback.error, color = waiting, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onTryLocal) { Text("尝试本机", color = rainbow[0]) }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onPrevious, enabled = rowIndex > 0, modifier = Modifier.weight(1f)) {
                    Text("◀ 上一句", fontSize = 13.sp)
                }
                Button(onClick = onPlayPause, modifier = Modifier.weight(1.1f)) {
                    Text(if (active) "Ⅱ 暂停" else "▶ 播放", fontSize = 14.sp)
                }
                OutlinedButton(onClick = onNext, enabled = rowIndex < pack.lines.lastIndex, modifier = Modifier.weight(1f)) {
                    Text("下一句 ▶", fontSize = 13.sp)
                }
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
    knownWords: Set<String> = emptySet(),
    learningWords: Set<String> = emptySet(),
    markNewWords: Boolean = false,
) {
    val normal = if (onDark) waiting else Color(0xFF49546A)
    val analyzed = remember(text) { LanguageAwareTokenizer.tokenize(text, "en") }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        visibleTokens.findAll(text).forEachIndexed { n, token ->
            val parsed = analyzed.firstOrNull { it.startIndex <= token.range.first && it.endIndex > token.range.first }
            val word = wordPattern.find(token.value)?.value
            val key = parsed?.let(::knownWordKey) ?: word?.let(::knownWordKey)
            val known = key != null && key in knownWords
            val newWord = markNewWords && key != null && key !in knownWords && key !in learningWords
            val inRange = synced && token.range.first < end && token.range.last >= start
            val finished = synced && token.range.last < start
            val posColor = parsed?.takeIf { it.partOfSpeech != PartOfSpeech.OTHER }
                ?.let { partOfSpeechColor(it.partOfSpeech, !onDark) }
            val tint = when {
                inRange -> if (onDark) Color.White else Color(0xFF182039)
                known -> if (onDark) Color.White else normal
                finished -> posColor ?: rainbow[n % rainbow.size]
                active && synced -> normal
                else -> posColor ?: rainbow[n % rainbow.size]
            }
            val shape = RoundedCornerShape(8.dp)
            val backdrop = when {
                inRange -> rainbow[n % rainbow.size].copy(alpha = 0.35f)
                newWord -> newWordTint(rainbow[0])
                else -> Color.Transparent
            }
            Text(
                token.value.trimEnd(),
                modifier = Modifier
                    .clip(shape)
                    .background(backdrop)
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
