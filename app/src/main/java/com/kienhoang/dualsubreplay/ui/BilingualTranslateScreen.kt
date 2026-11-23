package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import com.kienhoang.dualsubreplay.data.savedWordId
import com.kienhoang.dualsubreplay.translation.GoogleWebTranslator
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Chinese input goes to English; English input goes to Chinese. Han characters win in mixed text. */
internal fun bilingualSourceLanguage(text: String): String =
    when {
        text.any { it.code in 0x3400..0x9FFF } -> "zh"
        text.any(Char::isLetter) -> "en"
        else -> "zh"
    }

private val englishWordPattern = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*")

private suspend fun bilingualTranslate(
    google: GoogleWebTranslator,
    local: OnDeviceTranslator,
    source: String,
    target: String,
    text: String,
): Pair<String, Boolean> {
    try {
        val result = google.translate(source, target, text)
        if (!result.isNullOrBlank()) return result to false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Google web endpoints are unofficial and can be unavailable. Use the existing ML Kit engine.
    }
    return local.translateSingle(source, target, text) to true
}

/** A separate full-screen editor, preserving the YouTube player and its existing translation settings. */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun BilingualTranslateScreen(
    vocabulary: VocabularyRepository,
    onClose: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pronouncer = rememberWordPronouncer()
    val google = remember(context) { GoogleWebTranslator(File(context.cacheDir, "google-translations")) }
    val local = remember(context) { OnDeviceTranslator(File(context.cacheDir, "subtitle-translations")) }

    var sourceText by rememberSaveable { mutableStateOf("") }
    var forcedSource by rememberSaveable { mutableStateOf<String?>(null) }
    var translatedText by rememberSaveable { mutableStateOf("") }
    var usedOffline by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var translationJob by remember { mutableStateOf<Job?>(null) }
    val source = forcedSource ?: bilingualSourceLanguage(sourceText)
    val target = if (source == "zh") "en" else "zh"
    val sourceLabel = if (source == "zh") "中文" else "英文"
    val targetLabel = if (target == "zh") "中文" else "英文"

    fun saveWord(raw: String) {
        val word = raw.lowercase(Locale.ROOT)
        val englishSentence = if (source == "en") sourceText.trim() else translatedText.trim()
        val chineseSentence = if (source == "zh") sourceText.trim() else translatedText.trim()
        scope.launch {
            try {
                val id = savedWordId(word, "en", "zh", null, null)
                val old = vocabulary.words.value.firstOrNull { it.id == id }
                val saved =
                    SavedWord(
                        id = id,
                        word = word,
                        reading = null,
                        wordLanguage = "en",
                        meaningLanguage = "zh",
                        meaning = old?.meaning.orEmpty(),
                        sentence = englishSentence,
                        translatedSentence = chineseSentence.takeIf { it.isNotBlank() },
                        videoId = null,
                        startMs = 0,
                        endMs = 0,
                        translated = false,
                        online = false,
                        sourceTitle = "中英互译",
                    )
                vocabulary.save(saved)
                launch { snackbar.showSnackbar("已加入生词本：" + word) }
                if (old?.meaning.isNullOrBlank()) {
                    val meaning =
                        try {
                            bilingualTranslate(google, local, "en", "zh", word).first
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            ""
                        }
                    if (meaning.isNotBlank()) vocabulary.save(saved.copy(meaning = meaning))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                snackbar.showSnackbar("收藏失败：" + (error.message ?: "未知错误"))
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("中英互译", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "关闭翻译")
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { inner ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sourceLabel, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            forcedSource = target
                            translationJob?.cancel()
                            translatedText = ""
                            busy = false
                        }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "交换翻译方向")
                        }
                        Spacer(Modifier.weight(1f))
                        Text(targetLabel, style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedTextField(
                        value = sourceText,
                        onValueChange = {
                            translationJob?.cancel()
                            sourceText = it
                            forcedSource = null
                            translatedText = ""
                            busy = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("输入中文或英文") },
                        minLines = 3,
                        maxLines = 9,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            enabled = sourceText.isNotBlank() && !busy,
                            onClick = {
                                val text = sourceText.trim()
                                val from = source
                                val to = target
                                busy = true
                                translatedText = ""
                                translationJob =
                                    scope.launch {
                                        try {
                                            val (result, offline) = bilingualTranslate(google, local, from, to, text)
                                            translatedText = result
                                            usedOffline = offline
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            snackbar.showSnackbar("翻译失败：" + (error.message ?: "请检查网络或离线语言包"))
                                        } finally {
                                            busy = false
                                        }
                                    }
                            },
                        ) { Text("翻译") }
                        TextButton(onClick = {
                            translationJob?.cancel()
                            sourceText = ""
                            forcedSource = null
                            translatedText = ""
                            busy = false
                        }) { Text("清空") }
                        if (busy) CircularProgressIndicator()
                    }
                    if (translatedText.isNotBlank()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(targetLabel + "译文", style = MaterialTheme.typography.labelLarge)
                                if (target == "en") {
                                    EnglishWordPressText(
                                        translatedText,
                                        { word -> pronouncer.speak(word, "en") },
                                        ::saveWord,
                                    )
                                } else {
                                    Text(translatedText, style = MaterialTheme.typography.bodyLarge)
                                }
                                Text(
                                    if (usedOffline) "ML Kit 离线翻译" else "Google 在线翻译",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (source == "en" && sourceText.isNotBlank()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("英文原文 · 可点按单词", style = MaterialTheme.typography.labelLarge)
                                EnglishWordPressText(
                                    sourceText,
                                    { word -> pronouncer.speak(word, "en") },
                                    ::saveWord,
                                )
                            }
                        }
                    }
                    Text(
                        "点按英文单词朗读，长按加入现有生词本；首次离线翻译可能需要下载语言模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EnglishWordPressText(
    text: String,
    onTap: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(text) {
                    detectTapGestures(
                        onTap = { position -> wordUnderFinger(text, layout, position)?.let(onTap) },
                        onLongPress = { position -> wordUnderFinger(text, layout, position)?.let(onLongPress) },
                    )
                },
        onTextLayout = { layout = it },
    )
}

private fun wordUnderFinger(
    text: String,
    layout: TextLayoutResult?,
    position: Offset,
): String? {
    val index = layout?.getOffsetForPosition(position) ?: return null
    return englishWordPattern.findAll(text).firstOrNull { index in it.range }?.value
}
