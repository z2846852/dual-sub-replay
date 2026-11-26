package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.ChineseMorphology
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import com.kienhoang.dualsubreplay.data.savedWordId
import com.kienhoang.dualsubreplay.translation.GoogleWebTranslator
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private const val AUTO_TRANSLATE_DELAY_MS = 420L
private const val MAX_INTERACTIVE_TRANSLATION_LENGTH = 4_000

/** Chinese input goes to English; English input goes to Chinese. Han characters win in mixed text. */
internal fun bilingualSourceLanguage(text: String): String =
    when {
        text.any { it.code in 0x3400..0x9FFF } -> "zh"
        text.any(Char::isLetter) -> "en"
        else -> "zh"
    }

/** Quick editor calls one Google request, not the subtitle engine's multi-second retry queue. */
internal suspend fun bilingualTranslate(
    google: GoogleWebTranslator,
    local: OnDeviceTranslator,
    source: String,
    target: String,
    text: String,
): Pair<String, Boolean> {
    try {
        google.cachedTranslation(source, target, text)?.takeIf(String::isNotBlank)?.let { return it to false }
        google.translateNextBatch(source, target, listOf(text))
        google.cachedTranslation(source, target, text)?.takeIf(String::isNotBlank)?.let { return it to false }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Google uses unofficial web endpoints; a failed single attempt should not stall the editor.
    }
    return local.translateSingle(source, target, text) to true
}

internal data class BilingualWordPick(
    val word: String,
    val language: String,
)

/** Two copies of the same translator. Each composable invocation owns its own input and results. */
@Composable
internal fun BilingualTranslateScreen(
    vocabulary: VocabularyRepository,
    onClose: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val appContext = LocalContext.current.applicationContext
    val preferences = remember(appContext) { appContext.getSharedPreferences("dual_sub_preferences", 0) }
    var tripleMode by rememberSaveable {
        mutableStateOf(preferences.getBoolean("triple_translate_mode", false))
    }
    val translationChoices = remember(appContext) { availableTranslationChoices(appContext) }
    var translator by rememberSaveable {
        mutableStateOf(preferences.getString(QUICK_TRANSLATION_CHOICE_PREF, GOOGLE_TRANSLATION_CHOICE)
            ?: GOOGLE_TRANSLATION_CHOICE)
    }
    val chosenTranslator = translator.takeIf { wanted -> translationChoices.any { it.key == wanted } }
        ?: GOOGLE_TRANSLATION_CHOICE
    fun selectTranslator(picked: String) {
        translator = picked
        preferences.edit().putString(QUICK_TRANSLATION_CHOICE_PREF, picked).apply()
    }
    val savedEditors = rememberSaveableStateHolder()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("即时翻译", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                tripleMode = !tripleMode
                                preferences.edit().putBoolean("triple_translate_mode", tripleMode).apply()
                            },
                            modifier = Modifier.testTag("translation_mode_toggle"),
                        ) { Text(if (tripleMode) "一翻三 ▾" else "双实例 ▾") }
                        TranslationChoiceMenu(translationChoices, chosenTranslator, ::selectTranslator)
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
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (tripleMode) {
                        savedEditors.SaveableStateProvider("triple") {
                            TripleTranslatePane(vocabulary, snackbar, chosenTranslator, ::selectTranslator)
                        }
                    } else {
                        savedEditors.SaveableStateProvider("dual") {
                            BilingualTranslatePane(vocabulary, snackbar, chosenTranslator, ::selectTranslator)
                            HorizontalDivider()
                            BilingualTranslatePane(vocabulary, snackbar, chosenTranslator, ::selectTranslator)
                        }
                    }
                }
            }
        }
    }
}

/** The same reusable editor, instantiated twice without shared editor state or linked directions. */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun BilingualTranslatePane(
    vocabulary: VocabularyRepository,
    snackbar: SnackbarHostState,
    translator: String,
    onSelectTranslator: (String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val pronouncer = rememberWordPronouncer()
    val google = remember(context) { GoogleWebTranslator(File(context.cacheDir, "google-translations")) }
    val local = remember(context) { OnDeviceTranslator(File(context.cacheDir, "subtitle-translations")) }
    val savedWords by vocabulary.words.collectAsState()

    var sourceText by rememberSaveable { mutableStateOf("") }
    var forcedSource by rememberSaveable { mutableStateOf<String?>(null) }
    var translatedText by remember { mutableStateOf("") }
    var translationLabel by remember { mutableStateOf("Google") }
    var translating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryVersion by remember { mutableIntStateOf(0) }
    var pickedWord by remember { mutableStateOf<BilingualWordPick?>(null) }
    var pickedMeaning by remember { mutableStateOf("") }
    var showSourceWords by rememberSaveable { mutableStateOf(false) }

    val source = forcedSource ?: bilingualSourceLanguage(sourceText)
    val target = if (source == "zh") "en" else "zh"
    val sourceLabel = if (source == "zh") "中文" else "英文"
    val targetLabel = if (target == "zh") "中文" else "英文"

    fun editText(
        text: String,
        manualSource: String? = null,
    ) {
        sourceText = text
        forcedSource = manualSource
        translatedText = ""
        translating = false
        errorMessage = null
        pickedWord = null
        pickedMeaning = ""
        if (text.isBlank()) showSourceWords = false
    }

    fun copyText(text: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbar.showSnackbar("已复制到剪贴板") }
    }

    fun saveWord(pick: BilingualWordPick) {
        val word = if (pick.language == "en") pick.word.lowercase(Locale.ROOT) else pick.word
        val meaningLanguage = if (pick.language == "en") "zh" else "en"
        val fromSource = pick.language == source
        val sentence = if (fromSource) sourceText.trim() else translatedText.trim()
        val translatedSentence = if (fromSource) translatedText.trim() else sourceText.trim()
        val alreadyResolved = pickedMeaning.takeIf { pickedWord == pick }.orEmpty()
        scope.launch {
            try {
                val id = savedWordId(word, pick.language, meaningLanguage, null, null)
                val existing = vocabulary.words.value.firstOrNull { it.id == id }
                val saved =
                    SavedWord(
                        id = id,
                        word = word,
                        reading = null,
                        wordLanguage = pick.language,
                        meaningLanguage = meaningLanguage,
                        meaning = existing?.meaning?.takeIf(String::isNotBlank) ?: alreadyResolved,
                        sentence = sentence,
                        translatedSentence = translatedSentence.takeIf(String::isNotBlank),
                        videoId = null,
                        startMs = 0,
                        endMs = 0,
                        translated = !fromSource,
                        online = false,
                        sourceTitle = "中英互译",
                    )
                vocabulary.save(saved)
                snackbar.showSnackbar("已加入生词本：" + word)
                if (saved.meaning.isBlank()) {
                    // Save immediately: dictionary/network failures must not lose a learner's selection.
                    val meaning =
                        try {
                            bilingualTranslate(google, local, pick.language, meaningLanguage, word).first
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            ""
                        }
                    if (meaning.isNotBlank()) {
                        vocabulary.update(id) { old ->
                            if (old.meaning.isBlank()) old.copy(meaning = meaning) else old
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                snackbar.showSnackbar("收藏失败：" + (error.message ?: "请重试"))
            }
        }
    }

    LaunchedEffect(Unit) {
        ChineseMorphology.warmUp()
    }

    // LaunchedEffect cancels previous work whenever the text or direction changes.
    LaunchedEffect(sourceText, source, translator, retryVersion) {
        val query = sourceText.trim()
        // Do not leave a previous engine's output visible while a newly selected AI fails.
        translatedText = ""
        translationLabel = if (translator == GOOGLE_TRANSLATION_CHOICE) "Google" else "AI"
        if (query.isBlank()) {
            translatedText = ""
            translating = false
            errorMessage = null
            return@LaunchedEffect
        }
        if (query.length > MAX_INTERACTIVE_TRANSLATION_LENGTH) {
            errorMessage = "内容超过 4000 字，请缩短后再翻译"
            translating = false
            return@LaunchedEffect
        }
        delay(if (translator == GOOGLE_TRANSLATION_CHOICE) AUTO_TRANSLATE_DELAY_MS else 900L)
        translating = true
        errorMessage = null
        try {
            val result = translateWithChoice(context, google, local, translator, source, target, query)
            currentCoroutineContext().ensureActive()
            if (result.text.isBlank()) {
                errorMessage = "暂时无法翻译，请重试"
            } else {
                translatedText = result.text
                translationLabel = result.label
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            errorMessage = "翻译失败：" + (error.message ?: "请检查网络或离线语言包")
        } finally {
            translating = false
        }
    }

    LaunchedEffect(pickedWord) {
        val pick =
            pickedWord ?: run {
                pickedMeaning = ""
                return@LaunchedEffect
            }
        val toLanguage = if (pick.language == "en") "zh" else "en"
        val id =
            savedWordId(
                if (pick.language == "en") pick.word.lowercase(Locale.ROOT) else pick.word,
                pick.language,
                toLanguage,
                null,
                null,
            )
        pickedMeaning = savedWords.firstOrNull { it.id == id }?.meaning.orEmpty()
        if (pickedMeaning.isBlank()) {
            try {
                val meaning = bilingualTranslate(google, local, pick.language, toLanguage, pick.word).first
                currentCoroutineContext().ensureActive()
                pickedMeaning = meaning
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The word can still be pronounced and saved if lookup fails.
            }
        }
    }

    fun chooseWord(pick: BilingualWordPick) {
        pickedWord = pick
        pronouncer.speak(pick.word, pick.language)
    }


    // Each pane has the same compact fixed-height input. Long drafts scroll *inside* the field.
    // Word actions stay available behind one explicit affordance instead of duplicating the
    // entire original sentence below each result by default.
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().testTag("bilingual_input_card"),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        (if (forcedSource == null) "自动识别：" else "指定：") + sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).testTag("bilingual_translate_direction"),
                        maxLines = 1,
                    )
                    IconButton(
                        onClick = {
                            val oldTarget = target
                            editText(translatedText.ifBlank { sourceText }, oldTarget)
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "交换翻译方向", modifier = Modifier.size(18.dp))
                    }
                    TextButton(
                        enabled = sourceText.isNotBlank(),
                        onClick = { showSourceWords = !showSourceWords },
                        modifier = Modifier.height(32.dp).testTag("bilingual_source_words_toggle"),
                        contentPadding = PaddingValues(horizontal = 3.dp),
                    ) {
                        Text(if (showSourceWords) "收起" else "逐词", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(
                        enabled = sourceText.isNotBlank(),
                        onClick = { pronouncer.speak(sourceText.trim(), source) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "朗读原文", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        enabled = sourceText.isNotBlank(),
                        onClick = { copyText(sourceText) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制原文", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        enabled = sourceText.isNotEmpty(),
                        onClick = { editText("") },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "清空输入", modifier = Modifier.size(18.dp))
                    }
                }
                BasicTextField(
                    value = sourceText,
                    onValueChange = { editText(it) },
                    modifier = Modifier.fillMaxWidth().height(58.dp).testTag("bilingual_translate_input"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { inner ->
                        Box {
                            if (sourceText.isEmpty()) {
                                Text("输入中文或英文…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        }
                    },
                )
            }
        }
        if (showSourceWords && sourceText.isNotBlank()) {
            BilingualInteractiveText(
                text = sourceText,
                language = source,
                selected = pickedWord,
                onTap = ::chooseWord,
                onLongPress = ::saveWord,
            )
        }
        Card(Modifier.fillMaxWidth().testTag("bilingual_translate_result")) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "$targetLabel · $translationLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (translating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        }
                        if (translatedText.isNotBlank()) {
                            IconButton(
                                onClick = { pronouncer.speak(translatedText, target) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Default.VolumeUp, contentDescription = "朗读整句译文", modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = { copyText(translatedText) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "复制译文", modifier = Modifier.size(20.dp))
                            }
                        }
                        if (errorMessage != null) {
                            IconButton(onClick = { retryVersion++ }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "重试翻译", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    when {
                        translatedText.isNotBlank() -> BilingualInteractiveText(
                            text = translatedText,
                            language = target,
                            selected = pickedWord,
                            onTap = ::chooseWord,
                            onLongPress = ::saveWord,
                        )
                        errorMessage != null -> {
                            Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                            if (translator != GOOGLE_TRANSLATION_CHOICE) {
                                TextButton(
                                    onClick = { onSelectTranslator(GOOGLE_TRANSLATION_CHOICE) },
                                    modifier = Modifier.testTag("quick_translation_switch_google"),
                                ) { Text("改用 Google") }
                            }
                        }
                        translating -> Text("正在翻译…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text("正在等待输入…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        if (pickedWord != null) {
            val pick = pickedWord!!
            val otherLanguage = if (pick.language == "en") "zh" else "en"
            val id = savedWordId(
                if (pick.language == "en") pick.word.lowercase(Locale.ROOT) else pick.word,
                pick.language,
                otherLanguage,
                null,
                null,
            )
            val isSaved = savedWords.any { it.id == id }
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pick.word,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { pronouncer.speak(pick.word, pick.language) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "朗读选中单词", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { copyText(pick.word) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制选中单词", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { saveWord(pick) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                if (isSaved) Icons.Default.Check else Icons.Default.BookmarkAdd,
                                contentDescription = if (isSaved) "已收藏，更新生词" else "加入生词本",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Text(
                        if (pickedMeaning.isNotBlank()) pickedMeaning else "释义查询中，仍可直接收藏",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        pronouncer.message?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Both the source and translated line are tappable. The existing language-aware tokenizer uses
 * the bundled Chinese dictionary when available and falls back to individual Han characters.
 */
@Composable
internal fun BilingualInteractiveText(
    text: String,
    language: String,
    selected: BilingualWordPick?,
    onTap: (BilingualWordPick) -> Unit,
    onLongPress: (BilingualWordPick) -> Unit,
) {
    val dictionaryRevision by ChineseMorphology.revision.collectAsState()
    val tokens =
        remember(text, language, dictionaryRevision) {
            bilingualWordTokens(text, language)
        }
    var layout by remember(text, language) { mutableStateOf<TextLayoutResult?>(null) }
    val selectedBackground = MaterialTheme.colorScheme.primaryContainer
    val highlightedText =
        buildAnnotatedString {
            append(text)
            tokens.filter { selected?.word == it.text && selected.language == language }.forEach { token ->
                addStyle(
                    SpanStyle(background = selectedBackground),
                    token.startIndex,
                    token.endIndex,
                )
            }
        }
    Text(
        text = highlightedText,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(text, language, tokens) {
                    detectTapGestures(
                        onTap = { position ->
                            bilingualWordAtPosition(tokens, layout, position)?.let { onTap(BilingualWordPick(it, language)) }
                        },
                        onLongPress = { position ->
                            bilingualWordAtPosition(tokens, layout, position)?.let {
                                onLongPress(BilingualWordPick(it, language))
                            }
                        },
                    )
                },
        onTextLayout = { layout = it },
    )
}

/** Match token indices to the rendered string; a missing Chinese lexicon still permits character taps. */
internal fun bilingualWordTokens(
    text: String,
    language: String,
): List<AnalyzedToken> {
    if (language == "zh" && ChineseMorphology.analyze(text) == null) {
        return text.mapIndexedNotNull { index, char ->
            if (char.code in 0x3400..0x9FFF) {
                AnalyzedToken(char.toString(), index, index + 1, PartOfSpeech.OTHER)
            } else {
                null
            }
        }
    }
    return LanguageAwareTokenizer.tokenize(text, language).filter {
        it.text.any(Char::isLetterOrDigit) && it.startIndex >= 0 && it.endIndex <= text.length
    }
}

private fun bilingualWordAtPosition(
    tokens: List<AnalyzedToken>,
    layout: TextLayoutResult?,
    position: Offset,
): String? {
    val offset = layout?.getOffsetForPosition(position) ?: return null
    return tokens.firstOrNull { offset >= it.startIndex && offset < it.endIndex }?.text
}
