package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.ChineseMorphology
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import com.kienhoang.dualsubreplay.data.savedWordId
import com.kienhoang.dualsubreplay.translation.GoogleWebTranslator
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import com.kienhoang.dualsubreplay.translation.TranslationLanguageOption
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** One source, its English/Chinese counterpart, and independent direct/pivot target translations. */
internal data class TripleTranslationRoute(val source: String, val target: String)

internal fun tripleTranslationRoutes(input: String, selectedTarget: String): List<TripleTranslationRoute> {
    val source = bilingualSourceLanguage(input)
    val pivot = if (source == "zh") "en" else "zh"
    require(selectedTarget !in setOf("zh", "en"))
    return listOf(
        TripleTranslationRoute(source, pivot),
        TripleTranslationRoute(source, selectedTarget),
        TripleTranslationRoute(pivot, selectedTarget),
    )
}

private data class QuickResult(
    val text: String = "",
    val offline: Boolean = false,
    val providerLabel: String = "Google",
    val loading: Boolean = false,
    val error: String? = null,
)

private fun chineseLanguageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
        .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        ?: TranslationLanguages.displayName(code)

@Composable
@Suppress("LongMethod")
internal fun TripleTranslatePane(
    vocabulary: VocabularyRepository,
    snackbar: SnackbarHostState,
    translator: String,
) {
    val context = LocalContext.current.applicationContext
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val pronouncer = rememberWordPronouncer()
    val google = remember(context) { GoogleWebTranslator(File(context.cacheDir, "google-translations")) }
    val local = remember(context) { OnDeviceTranslator(File(context.cacheDir, "subtitle-translations")) }

    var input by rememberSaveable { mutableStateOf("") }
    var targetLanguage by rememberSaveable { mutableStateOf("th") }
    var showLanguages by remember { mutableStateOf(false) }
    var languageQuery by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var pivotResult by remember { mutableStateOf(QuickResult()) }
    var directResult by remember { mutableStateOf(QuickResult()) }
    var viaResult by remember { mutableStateOf(QuickResult()) }
    var selectedWord by remember { mutableStateOf<BilingualWordPick?>(null) }

    val source = bilingualSourceLanguage(input)
    val pivot = if (source == "zh") "en" else "zh"
    val routes = tripleTranslationRoutes(input, targetLanguage)
    val targetName = chineseLanguageName(targetLanguage)
    val sourceName = if (source == "zh") "中文" else "英文"
    val pivotName = if (pivot == "zh") "中文" else "英文"

    fun edit(value: String) {
        input = value
        pivotResult = QuickResult()
        directResult = QuickResult()
        viaResult = QuickResult()
        selectedWord = null
    }

    fun copy(value: String) {
        clipboard.setText(AnnotatedString(value))
        scope.launch { snackbar.showSnackbar("已复制") }
    }

    fun saveWord(pick: BilingualWordPick, sentence: String) {
        val word = if (pick.language == "en") pick.word.lowercase(Locale.ROOT) else pick.word
        val meaningLanguage = if (pick.language == "zh") "en" else "zh"
        val id = savedWordId(word, pick.language, meaningLanguage, null, null)
        scope.launch {
            try {
                val existing = vocabulary.words.value.firstOrNull { it.id == id }
                vocabulary.save(
                    SavedWord(
                        id = id,
                        word = word,
                        reading = null,
                        wordLanguage = pick.language,
                        meaningLanguage = meaningLanguage,
                        meaning = existing?.meaning.orEmpty(),
                        sentence = sentence,
                        translatedSentence = if (pick.language == source) pivotResult.text else input,
                        videoId = null,
                        startMs = 0,
                        endMs = 0,
                        translated = pick.language != source,
                        online = false,
                        sourceTitle = "一翻三",
                    ),
                )
                snackbar.showSnackbar("已加入生词本：" + word)
                if (existing?.meaning.isNullOrBlank()) {
                    val meaning = bilingualTranslate(google, local, pick.language, meaningLanguage, word).first
                    if (meaning.isNotBlank()) {
                        vocabulary.update(id) { old ->
                            if (old.meaning.isBlank()) old.copy(meaning = meaning) else old
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                snackbar.showSnackbar("已尝试收藏；释义获取失败，可稍后重试")
            }
        }
    }

    fun tapWord(pick: BilingualWordPick) {
        selectedWord = pick
        pronouncer.speak(pick.word, pick.language)
    }

    LaunchedEffect(Unit) { ChineseMorphology.warmUp() }

    // The direct translation does not depend on the intermediate English/Chinese sentence.
    // It therefore stays available even if the pivot or its network request fails.
    LaunchedEffect(input, targetLanguage, translator, retry) {
        val text = input.trim()
        if (text.isEmpty()) return@LaunchedEffect
        if (text.length > 4_000) {
            directResult = QuickResult(error = "最多输入 4000 字")
            return@LaunchedEffect
        }
        delay(420)
        directResult = QuickResult(loading = true)
        try {
            val result = translateWithChoice(context, google, local, translator, routes[1].source, routes[1].target, text)
            currentCoroutineContext().ensureActive()
            directResult = if (result.text.isNotBlank()) QuickResult(text = result.text, providerLabel = result.label)
                else QuickResult(error = "翻译结果为空")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            directResult = QuickResult(error = error.message ?: "翻译失败")
        }
    }

    LaunchedEffect(input, translator, retry) {
        val text = input.trim()
        if (text.isEmpty()) return@LaunchedEffect
        if (text.length > 4_000) {
            pivotResult = QuickResult(error = "最多输入 4000 字")
            return@LaunchedEffect
        }
        delay(420)
        pivotResult = QuickResult(loading = true)
        try {
            val result = translateWithChoice(context, google, local, translator, routes[0].source, routes[0].target, text)
            currentCoroutineContext().ensureActive()
            pivotResult = if (result.text.isNotBlank()) QuickResult(text = result.text, providerLabel = result.label)
                else QuickResult(error = "翻译结果为空")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            pivotResult = QuickResult(error = error.message ?: "翻译失败")
        }
    }

    // Only the THIRD result uses the intermediate sentence; never silently substitute the
    // Chinese direct translation when English->Thai fails, or the comparison is meaningless.
    LaunchedEffect(input, pivotResult.text, targetLanguage, translator, retry) {
        val intermediate = pivotResult.text
        if (input.isBlank() || intermediate.isBlank()) {
            viaResult = QuickResult()
            return@LaunchedEffect
        }
        viaResult = QuickResult(loading = true)
        try {
            val result = translateWithChoice(context, google, local, translator, routes[2].source, routes[2].target, intermediate)
            currentCoroutineContext().ensureActive()
            viaResult = if (result.text.isNotBlank()) QuickResult(text = result.text, providerLabel = result.label)
                else QuickResult(error = "翻译结果为空")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            viaResult = QuickResult(error = error.message ?: "翻译失败")
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().testTag("triple_input_card"),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "输入 · 自动识别：$sourceName",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TranslationCornerButton("朗读输入", enabled = input.isNotBlank(), onClick = { pronouncer.speak(input, source) }, copy = false)
                    TranslationCornerButton("复制输入", enabled = input.isNotBlank(), onClick = { copy(input) }, copy = true)
                    IconButton(onClick = { edit("") }, enabled = input.isNotEmpty(), modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "清空输入", modifier = Modifier.size(18.dp))
                    }
                }
                BasicTextField(
                    value = input,
                    onValueChange = ::edit,
                    modifier = Modifier.fillMaxWidth().height(58.dp).testTag("triple_source_text"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) Text("输入中文或英文，自动翻译…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    },
                )
            }
        }
        TripleResultCard(
            title = "$pivotName · 基础译文",
            language = pivot,
            result = pivotResult,
            selected = selectedWord,
            onSpeak = { pronouncer.speak(it, pivot) },
            onCopy = ::copy,
            onRetry = { retry++ },
            onTapWord = ::tapWord,
            onSaveWord = ::saveWord,
            tag = "triple_pivot_result",
        )
        TripleResultCard(
            title = "$targetName · $sourceName 直译",
            language = targetLanguage,
            result = directResult,
            selected = selectedWord,
            onSpeak = { pronouncer.speak(it, targetLanguage) },
            onCopy = ::copy,
            onRetry = { retry++ },
            onChooseLanguage = { showLanguages = true },
            onTapWord = ::tapWord,
            onSaveWord = ::saveWord,
            tag = "triple_direct_result",
        )
        TripleResultCard(
            title = "$targetName · 经$pivotName",
            language = targetLanguage,
            result = when {
                pivotResult.error != null -> QuickResult(error = "基础译文失败，无法进行转译")
                pivotResult.loading -> QuickResult(loading = true)
                else -> viaResult
            },
            selected = selectedWord,
            onSpeak = { pronouncer.speak(it, targetLanguage) },
            onCopy = ::copy,
            onRetry = { retry++ },
            onTapWord = ::tapWord,
            onSaveWord = ::saveWord,
            tag = "triple_via_result",
        )
        pronouncer.message?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (showLanguages) {
        val options = TranslationLanguages.all.filter { it.code !in setOf("zh", "en") }
        val priority = listOf("th", "ja", "ko", "vi", "id", "es", "fr", "de", "ru", "ar")
        val matching = options.filter { option ->
            languageQuery.isBlank() || option.code.contains(languageQuery, ignoreCase = true) ||
                option.name.contains(languageQuery, ignoreCase = true) ||
                chineseLanguageName(option.code).contains(languageQuery, ignoreCase = true)
        }.sortedWith(compareBy<TranslationLanguageOption>({ priority.indexOf(it.code).let { index -> if (index < 0) 100 else index } }, { it.name }))
        AlertDialog(
            onDismissRequest = { showLanguages = false },
            title = { Text("目标语言") },
            text = {
                Column {
                    OutlinedTextField(
                        value = languageQuery,
                        onValueChange = { languageQuery = it },
                        label = { Text("搜索语言") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(310.dp)) {
                        items(matching, key = TranslationLanguageOption::code) { option ->
                            TextButton(
                                onClick = {
                                    targetLanguage = option.code
                                    directResult = QuickResult()
                                    viaResult = QuickResult()
                                    showLanguages = false
                                    languageQuery = ""
                                },
                                modifier = Modifier.fillMaxWidth().testTag("triple_language_" + option.code),
                            ) {
                                Text("${chineseLanguageName(option.code)} · ${option.name}", modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguages = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun TranslationCornerButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    copy: Boolean,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(
            if (copy) Icons.Default.ContentCopy else Icons.Default.VolumeUp,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun TripleResultCard(
    title: String,
    language: String,
    result: QuickResult,
    selected: BilingualWordPick?,
    onSpeak: (String) -> Unit,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onChooseLanguage: (() -> Unit)? = null,
    onTapWord: (BilingualWordPick) -> Unit,
    onSaveWord: (BilingualWordPick, String) -> Unit,
    tag: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (onChooseLanguage == null) {
                    Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                } else {
                    TextButton(
                        onClick = onChooseLanguage,
                        modifier = Modifier.weight(1f).height(32.dp).testTag("triple_target_picker"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("$title ⌄", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (result.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp))
                }
                if (result.text.isNotBlank()) {
                    Text(result.providerLabel, style = MaterialTheme.typography.labelSmall)
                }
                TranslationCornerButton("朗读译文", result.text.isNotBlank(), { onSpeak(result.text) }, copy = false)
                TranslationCornerButton("复制译文", result.text.isNotBlank(), { onCopy(result.text) }, copy = true)
                if (result.error != null) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Refresh, "重新翻译", modifier = Modifier.size(18.dp))
                    }
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp, max = 110.dp).verticalScroll(rememberScrollState()),
            ) {
                when {
                    result.text.isNotBlank() -> BilingualInteractiveText(
                        text = result.text,
                        language = language,
                        selected = selected,
                        onTap = onTapWord,
                        onLongPress = { pick -> onSaveWord(pick, result.text) },
                    )
                    result.error != null -> Text(result.error, color = MaterialTheme.colorScheme.error)
                    result.loading -> Text("正在翻译…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text("等待输入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
