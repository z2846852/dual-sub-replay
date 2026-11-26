package com.kienhoang.dualsubreplay.listening

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.kienhoang.dualsubreplay.translation.GoogleWebTranslator
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import com.kienhoang.dualsubreplay.ui.GOOGLE_TRANSLATION_CHOICE
import com.kienhoang.dualsubreplay.ui.LISTENING_TRANSLATION_CHOICE_PREF
import com.kienhoang.dualsubreplay.ui.TranslationChoiceMenu
import com.kienhoang.dualsubreplay.ui.availableTranslationChoices
import com.kienhoang.dualsubreplay.ui.translateWithChoice
import java.io.File
import com.kienhoang.dualsubreplay.R

/**
 * The learner controls when hints are exposed. The native text field keeps long-press word
 * selection and the keyboard's microphone; this does not measure phonetic pronunciation.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun ListeningRecallCard(
    pack: ListeningPack,
    lineIndex: Int,
    progressByLine: List<RecallLineProgress>,
    playback: ListeningPlaybackState,
    previousIndex: Int?,
    nextIndex: Int?,
    wrongOnly: Boolean,
    autoNext: Boolean,
    onSetAutoNext: (Boolean) -> Unit,
    onAutoAdvance: () -> Unit,
    onToggleWrongOnly: () -> Unit,
    onPlayLine: (Int) -> Unit,
    onCheck: (String, Boolean) -> Unit,
    onHint: (Int) -> Unit,
    onSaveDraft: (String) -> Unit,
    onSaveInsight: (String, String, String) -> Unit,
    onOpenReview: () -> Unit,
    onOpenRaw: (String, String) -> Unit,
    onTryLocal: () -> Unit,
    onWordTap: (String, ListeningLine) -> Unit,
) {
    val index = lineIndex.coerceIn(pack.lines.indices)
    val line = pack.lines[index]
    val progress = progressByLine[index]
    val current = playback.packId == pack.id && playback.currentLine == index
    val playing = current && playback.playing
    val finished = current && playback.finished
    val error = playback.error.takeIf { current }

    var answer by remember(pack.id, line.id) {
        mutableStateOf(progress.draft ?: progress.lastAnswer)
    }
    var lastChecked by remember(pack.id, line.id) {
        mutableStateOf(if (progress.attempts > 0) progress.lastAnswer else null)
    }
    var revealed by remember(pack.id, line.id) { mutableIntStateOf(0) }
    var showChinese by remember(pack.id, line.id) { mutableStateOf(false) }
    val translationContext = LocalContext.current.applicationContext
    val translationPreferences = remember(translationContext) {
        translationContext.getSharedPreferences("dual_sub_preferences", 0)
    }
    val translationChoices = remember(translationContext) { availableTranslationChoices(translationContext) }
    var hintEngine by remember {
        mutableStateOf(translationPreferences.getString(LISTENING_TRANSLATION_CHOICE_PREF, GOOGLE_TRANSLATION_CHOICE)
            ?: GOOGLE_TRANSLATION_CHOICE)
    }
    val selectedHintEngine = hintEngine.takeIf { saved -> translationChoices.any { it.key == saved } }
        ?: GOOGLE_TRANSLATION_CHOICE
    var translatedHint by remember(pack.id, line.id, selectedHintEngine) { mutableStateOf("") }
    var hintLoading by remember(pack.id, line.id, selectedHintEngine) { mutableStateOf(false) }
    var hintError by remember(pack.id, line.id, selectedHintEngine) { mutableStateOf<String?>(null) }
    val hintGoogle = remember(translationContext) {
        GoogleWebTranslator(File(translationContext.cacheDir, "google-translations"))
    }
    val hintLocal = remember(translationContext) {
        OnDeviceTranslator(File(translationContext.cacheDir, "subtitle-translations"))
    }
    LaunchedEffect(pack.id, line.id, showChinese, selectedHintEngine) {
        if (!showChinese || line.en.isBlank()) return@LaunchedEffect
        if (selectedHintEngine == GOOGLE_TRANSLATION_CHOICE && line.zh.isNotBlank()) return@LaunchedEffect
        hintLoading = true
        hintError = null
        try {
            translatedHint = translateWithChoice(
                translationContext, hintGoogle, hintLocal, selectedHintEngine, "en", "zh", line.en,
            ).text
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            hintError = error.message ?: "翻译失败，检查网络或密钥后重试"
        } finally {
            hintLoading = false
        }
    }
    val isSitcom = pack.kind == SITCOM_PACK_KIND
    var correctionPending by remember(pack.id, line.id) { mutableStateOf(false) }
    var advancePending by remember(pack.id, line.id) { mutableStateOf(false) }
    var showInsightEditor by remember(pack.id, line.id) { mutableStateOf(false) }
    var insightCategory by remember(pack.id, line.id) { mutableStateOf("listening") }
    var insightNote by remember(pack.id, line.id) { mutableStateOf("") }
    var insightSaved by remember(pack.id, line.id) { mutableStateOf(false) }
    val checkedCurrent = lastChecked != null && lastChecked == answer
    val comparison =
        remember(line.en, lastChecked) {
            lastChecked?.let { ListeningRecallCheck.compare(line.en, it, isSitcom) }
        }
    val canCheck = answer.isNotBlank() && !playing
    val verifiedPass = progress.passed && checkedCurrent
    val verifiedSoftPass = verifiedPass && progress.softPassed

    fun checkAnswer() {
        if (!canCheck || (progress.passed && checkedCurrent)) return
        val perfect = ListeningRecallCheck.compare(line.en, answer).perfect
        onCheck(answer, correctionPending)
        lastChecked = answer
        revealed = 0
        correctionPending = false
        // A fully correct sitcom answer advances; yellow-only soft passes stay on this line.
        advancePending = perfect
    }

    // Passing is automatic; incorrect partial dictation never creates an attempt or a hint.
    LaunchedEffect(answer, playing, line.en) {
        if (shouldAutoPassRecall(answer, line.en, playing, progress.passed && !progress.softPassed)) {
            checkAnswer()
        }
    }
    LaunchedEffect(advancePending, autoNext, line.id) {
        if (advancePending && (autoNext || isSitcom)) {
            delay(650)
            advancePending = false
            onSaveDraft(answer)
            onAutoAdvance()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("\u266a ${line.speaker}", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        playing -> stringResource(R.string.listening_recall_playing)
                        finished -> stringResource(R.string.listening_recall_waiting)
                        else -> stringResource(R.string.listening_recall_hidden)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${index + 1}/${pack.lines.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onOpenReview,
                    modifier = Modifier.size(width = 40.dp, height = 40.dp).testTag("recall_history"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) { Text("!", style = MaterialTheme.typography.titleMedium) }
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onTryLocal) {
                    Text(stringResource(R.string.listening_recall_try_local))
                }
            }
            if (answer.isEmpty()) {
                Text(
                    stringResource(R.string.listening_recall_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = answer,
                onValueChange = {
                    answer = it
                    advancePending = false
                    if (lastChecked != it) revealed = 0
                },
                modifier = Modifier.fillMaxWidth().testTag("recall_answer"),
                placeholder = { Text(stringResource(R.string.listening_recall_placeholder)) },
                minLines = 1,
                maxLines = 3,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor =
                            if (verifiedPass) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor =
                            if (verifiedPass) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                        focusedContainerColor =
                            if (verifiedPass) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
                        unfocusedContainerColor =
                            if (verifiedPass) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
                    ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { checkAnswer() }),
                trailingIcon = {
                    if (answer.isNotEmpty()) {
                        IconButton(onClick = {
                            answer = ""
                            revealed = 0
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.listening_recall_clear),
                            )
                        }
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = canCheck,
                    onClick = { checkAnswer() },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                if (verifiedPass) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            contentColor =
                                if (verifiedPass) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
                        ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.weight(0.9f).height(44.dp).testTag("recall_check"),
                ) { Text(stringResource(R.string.listening_recall_check)) }
                if (isSitcom) {
                    OutlinedButton(
                        enabled = nextIndex != null,
                        onClick = {
                            onSaveDraft(answer)
                            nextIndex?.let(onPlayLine)
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                        modifier = Modifier.height(44.dp).testTag("recall_quick_next"),
                    ) { Text("下一句", style = MaterialTheme.typography.labelMedium) }
                }
                if (progress.attempts > 0) {
                    Text(
                        if (verifiedSoftPass) {
                            "宽松通过 · 人名/俚语已标黄"
                        } else if (progress.passed) {
                            stringResource(R.string.listening_recall_perfect)
                        } else {
                            stringResource(R.string.listening_recall_failed, progress.attempts)
                        },
                        color =
                            if (verifiedSoftPass) Color(0xFFB48A00)
                            else if (progress.passed) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1.5f).testTag("recall_result"),
                    )
                }
            }
            // Inline tools: quick feedback stays visible, while correction and bookmarking share one row.
            if (progress.attempts > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (progress.failed) {
                        TextButton(
                            onClick = {
                                correctionPending = true
                                revealed = 0
                            },
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text(
                                stringResource(R.string.listening_recall_recognition_correction),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                            )
                        }
                    }
                    TextButton(
                        onClick = { showInsightEditor = true },
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text(
                            if (insightSaved) "★ 已收藏" else "☆ 收藏错点",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            if (correctionPending) {
                Text(
                    stringResource(R.string.listening_recall_correction_pending),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onSaveDraft(answer)
                        onPlayLine(index)
                    },
                    modifier = Modifier.weight(1f).height(42.dp).testTag("recall_replay"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                ) { Text(stringResource(R.string.listening_recall_replay), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                OutlinedButton(
                    enabled = line.en.isNotBlank(),
                    onClick = {
                        showChinese = !showChinese
                        if (showChinese) onHint(1)
                    },
                    modifier = Modifier.weight(1f).height(42.dp).testTag("recall_show_meaning"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                ) { Text(
                    if (showChinese) "隐藏中文" else stringResource(R.string.listening_recall_show_meaning),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                ) }
                OutlinedButton(
                    onClick = {
                        revealed = 2
                        onHint(2)
                    },
                    modifier = Modifier.weight(1f).height(42.dp).testTag("recall_reveal"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                ) { Text(stringResource(R.string.listening_recall_reveal), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
            // Mark differences immediately after checking; showing Chinese is a separate voluntary hint.
            if (checkedCurrent && comparison != null && !comparison.perfect) {
                Text(
                    stringResource(R.string.listening_recall_actual),
                    style = MaterialTheme.typography.labelMedium,
                )
                RecallDifferenceRow(comparison, expected = false, onTargetTap = {})
            }
            if (showChinese) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("中文译法", style = MaterialTheme.typography.labelSmall)
                    TranslationChoiceMenu(translationChoices, selectedHintEngine) { choice ->
                        hintEngine = choice
                        translationPreferences.edit().putString(LISTENING_TRANSLATION_CHOICE_PREF, choice).apply()
                    }
                    if (selectedHintEngine == GOOGLE_TRANSLATION_CHOICE && line.zh.isNotBlank()) {
                        Text("课件原译", style = MaterialTheme.typography.labelSmall)
                    }
                    if (hintLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                }
                val meaning = if (selectedHintEngine == GOOGLE_TRANSLATION_CHOICE && line.zh.isNotBlank()) {
                    line.zh
                } else {
                    translatedHint
                }
                when {
                    meaning.isNotBlank() -> Text(
                        meaning,
                        modifier = Modifier.fillMaxWidth().testTag("recall_meaning_text"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    hintError != null -> Text(hintError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    hintLoading -> Text("翻译中…", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (revealed >= 2 || verifiedSoftPass) {
                Text(
                    stringResource(R.string.listening_recall_expected),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (comparison != null && checkedCurrent) {
                    RecallDifferenceRow(comparison, expected = true, onTargetTap = { onWordTap(it, line) })
                } else {
                    Text(line.en)
                }
                Text(
                    if (verifiedSoftPass) "黄色仅代表可省略词；请核对原句，可手动点「下一句」或再次复述。"
                    else stringResource(R.string.listening_recall_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { onOpenRaw(line.id, "What would you say after: " + line.en) },
                    modifier = Modifier.weight(1f),
                ) { Text("✎ 追加我的话", style = MaterialTheme.typography.labelMedium) }
                if (index == pack.lines.lastIndex) {
                    TextButton(
                        onClick = {
                            onOpenRaw(
                                "",
                                pack.followUpEn.ifBlank {
                                    "What would you say next in this conversation? Explain in English."
                                },
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("💬 回答对话问题", style = MaterialTheme.typography.labelMedium) }
                }
            }
            if (index == pack.lines.lastIndex) {
                Text(
                    "对话思考题：" + pack.followUpEn.ifBlank {
                        "What would you say next in this conversation? Explain in English."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val passes = progressByLine.count { it.passed }
            val failures = progressByLine.count { it.failed }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.listening_recall_summary, passes, failures, pack.lines.size),
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(
                    onClick = {
                        onSaveDraft(answer)
                        onToggleWrongOnly()
                    },
                    enabled = wrongOnly || failures > 0,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (wrongOnly) {
                            stringResource(R.string.listening_recall_all_sentences)
                        } else {
                            stringResource(R.string.listening_recall_wrong_only, failures)
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                progressByLine.forEachIndexed { i, status ->
                    val bg =
                        when {
                            status.softPassed -> Color(0xFFEAC34A)
                            status.passed -> MaterialTheme.colorScheme.tertiary
                            status.failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    val fg =
                        when {
                            status.softPassed -> Color(0xFF312900)
                            status.passed -> MaterialTheme.colorScheme.onTertiary
                            status.failed -> MaterialTheme.colorScheme.onError
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    val label =
                        when {
                            status.softPassed -> "宽松通过"
                            status.passed -> stringResource(R.string.listening_recall_status_passed)
                            status.failed -> stringResource(R.string.listening_recall_status_failed)
                            else -> stringResource(R.string.listening_recall_status_new)
                        }
                    val shape = RoundedCornerShape(5.dp)
                    val sizedModifier = Modifier.width(19.dp).height(27.dp)
                    val tintedModifier = sizedModifier.clip(shape).background(bg)
                    val outlinedModifier =
                        tintedModifier.border(
                            if (i == index) 2.dp else 0.dp,
                            MaterialTheme.colorScheme.onSurface,
                            shape,
                        )
                    val clickableModifier =
                        outlinedModifier.clickable {
                            onSaveDraft(answer)
                            onPlayLine(i)
                        }
                    val accessibleModifier =
                        clickableModifier.semantics {
                            contentDescription = "${i + 1}: $label"
                        }
                    val taggedModifier = accessibleModifier.testTag("recall_segment_${i + 1}")
                    Box(
                        modifier = taggedModifier,
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${i + 1}",
                            color = fg,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Checkbox(
                    checked = autoNext || isSitcom,
                    enabled = !isSitcom,
                    onCheckedChange = onSetAutoNext,
                    modifier = Modifier.size(40.dp).testTag("recall_auto_next"),
                )
                Text(
                    if (isSitcom) "全绿自动下一句 · 黄色停留" else stringResource(R.string.listening_recall_auto_next),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    modifier = Modifier.weight(1f).then(
                        if (isSitcom) Modifier else Modifier.clickable { onSetAutoNext(!autoNext) }
                    ),
                )
                OutlinedButton(
                    onClick = {
                        onSaveDraft(answer)
                        previousIndex?.let(onPlayLine)
                    },
                    enabled = previousIndex != null,
                    modifier = Modifier.size(width = 45.dp, height = 44.dp).testTag("recall_previous"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("‹", style = MaterialTheme.typography.titleLarge) }
                Button(
                    onClick = {
                        onSaveDraft(answer)
                        nextIndex?.let(onPlayLine)
                    },
                    enabled = nextIndex != null,
                    modifier = Modifier.height(44.dp).testTag("recall_next"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp),
                ) { Text(stringResource(R.string.listening_recall_next), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
    if (showInsightEditor) {
        val choices =
            listOf(
                "listening" to "听辨混淆",
                "missing" to "漏听/漏词",
                "preposition" to "介词/搭配",
                "grammar" to "语法/时态",
                "transcription" to "输入法误识别",
                "other" to "其他",
            )
        AlertDialog(
            onDismissRequest = { showInsightEditor = false },
            title = { Text("主动收藏这一类错误") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "这是一条主动标注的学习记录，与自动核对日志分开存储；只有你确认上传才会同步。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        choices.forEach { (key, label) ->
                            FilterChip(
                                selected = insightCategory == key,
                                onClick = { insightCategory = key },
                                label = { Text(label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = insightNote,
                        onValueChange = { insightNote = it.take(300) },
                        label = { Text("为什么值得记住（可选）") },
                        placeholder = { Text("例：at the lights 是固定搭配") },
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveInsight(insightCategory, insightNote, answer)
                    insightSaved = true
                    showInsightEditor = false
                }) { Text("收藏到重点错误") }
            },
            dismissButton = {
                TextButton(onClick = { showInsightEditor = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RecallDifferenceRow(
    comparison: RecallComparison,
    expected: Boolean,
    onTargetTap: (String) -> Unit,
) {
    val errorColor = MaterialTheme.colorScheme.error
    val regularColor = MaterialTheme.colorScheme.onSurface
    val errorBackground = MaterialTheme.colorScheme.errorContainer
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val optionalColor = if (dark) Color(0xFFFFD54F) else Color(0xFF8B6500)
    val optionalBackground = if (dark) Color(0x665C4A00) else Color(0x40FFDC62)
    val missing = stringResource(R.string.listening_recall_missing)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        comparison.words.forEach { part ->
            val shown =
                if (expected) part.expected else part.actual ?: if (part.expected != null) missing else null
            if (shown != null) {
                val clickable = if (expected) Modifier.clickable { onTargetTap(shown) } else Modifier
                val coloredModifier =
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            when {
                                part.matches -> Color.Transparent
                                part.optionalOmission -> optionalBackground
                                else -> errorBackground
                            },
                        )
                val wordModifier = coloredModifier.then(clickable).padding(horizontal = 3.dp, vertical = 2.dp)
                Text(
                    text = shown,
                    modifier = wordModifier,
                    color = when {
                        part.matches -> regularColor
                        part.optionalOmission -> optionalColor
                        else -> errorColor
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
