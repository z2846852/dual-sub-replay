package com.kienhoang.dualsubreplay.listening

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
    var correctionPending by remember(pack.id, line.id) { mutableStateOf(false) }
    var advancePending by remember(pack.id, line.id) { mutableStateOf(false) }
    var showInsightEditor by remember(pack.id, line.id) { mutableStateOf(false) }
    var insightCategory by remember(pack.id, line.id) { mutableStateOf("listening") }
    var insightNote by remember(pack.id, line.id) { mutableStateOf("") }
    var insightSaved by remember(pack.id, line.id) { mutableStateOf(false) }
    val checkedCurrent = lastChecked != null && lastChecked == answer
    val comparison =
        remember(line.en, lastChecked) {
            lastChecked?.let { ListeningRecallCheck.compare(line.en, it) }
        }
    val canCheck = answer.isNotBlank() && !playing
    val verifiedPass = progress.passed && checkedCurrent

    fun checkAnswer() {
        if (!canCheck || (progress.passed && checkedCurrent)) return
        val perfect = ListeningRecallCheck.compare(line.en, answer).perfect
        onCheck(answer, correctionPending)
        lastChecked = answer
        revealed = 0
        correctionPending = false
        advancePending = perfect
    }

    // Passing is automatic; incorrect partial dictation never creates an attempt or a hint.
    LaunchedEffect(answer, playing, line.en) {
        if (shouldAutoPassRecall(answer, line.en, playing, progress.passed)) {
            checkAnswer()
        }
    }
    LaunchedEffect(advancePending, autoNext, line.id) {
        if (advancePending && autoNext) {
            delay(650)
            advancePending = false
            onSaveDraft(answer)
            onAutoAdvance()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                Text("${index + 1}/${pack.lines.size}", style = MaterialTheme.typography.labelMedium)
                TextButton(
                    onClick = onOpenReview,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text("ⓘ", style = MaterialTheme.typography.titleMedium) }
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
                    modifier = Modifier.weight(1f).testTag("recall_check"),
                ) { Text(stringResource(R.string.listening_recall_check)) }
                if (progress.attempts > 0) {
                    Text(
                        if (progress.passed) {
                            stringResource(R.string.listening_recall_perfect)
                        } else {
                            stringResource(R.string.listening_recall_failed, progress.attempts)
                        },
                        color =
                            if (progress.passed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1.5f).testTag("recall_result"),
                    )
                }
            }
            if (progress.failed) {
                TextButton(
                    onClick = {
                        correctionPending = true
                        revealed = 0
                    },
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                ) { Text(stringResource(R.string.listening_recall_recognition_correction)) }
            }
            if (progress.attempts > 0) {
                TextButton(onClick = { showInsightEditor = true }) {
                    Text(if (insightSaved) "★ 已收藏此错误" else "☆ 收藏有价值的错误")
                }
            }
            if (correctionPending) {
                Text(
                    stringResource(R.string.listening_recall_correction_pending),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onSaveDraft(answer)
                        onPlayLine(index)
                    },
                    modifier = Modifier.weight(1f).testTag("recall_replay"),
                    contentPadding = PaddingValues(horizontal = 5.dp),
                ) { Text(stringResource(R.string.listening_recall_replay), maxLines = 1) }
                OutlinedButton(
                    enabled = progress.mayShowRedOnly() && checkedCurrent && comparison?.perfect == false,
                    onClick = {
                        revealed = 1
                        onHint(1)
                    },
                    modifier = Modifier.weight(1f).testTag("recall_red_only"),
                    contentPadding = PaddingValues(horizontal = 5.dp),
                ) { Text(stringResource(R.string.listening_recall_red_only), maxLines = 1) }
                OutlinedButton(
                    onClick = {
                        revealed = 2
                        onHint(2)
                    },
                    modifier = Modifier.weight(1f).testTag("recall_reveal"),
                    contentPadding = PaddingValues(horizontal = 5.dp),
                ) { Text(stringResource(R.string.listening_recall_reveal), maxLines = 1) }
            }
            if (revealed >= 1 && checkedCurrent && comparison != null && !comparison.perfect) {
                Text(
                    stringResource(R.string.listening_recall_actual),
                    style = MaterialTheme.typography.labelMedium,
                )
                RecallDifferenceRow(comparison, expected = false, onTargetTap = {})
            }
            if (revealed >= 2) {
                Text(
                    stringResource(R.string.listening_recall_expected),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (comparison != null && checkedCurrent) {
                    RecallDifferenceRow(comparison, expected = true, onTargetTap = { onWordTap(it, line) })
                } else {
                    Text(line.en)
                }
                if (line.zh.isNotBlank()) {
                    Text(line.zh, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(R.string.listening_recall_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            status.passed -> MaterialTheme.colorScheme.tertiary
                            status.failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    val fg =
                        when {
                            status.passed -> MaterialTheme.colorScheme.onTertiary
                            status.failed -> MaterialTheme.colorScheme.onError
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    val label =
                        when {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Checkbox(
                    checked = autoNext,
                    onCheckedChange = onSetAutoNext,
                    modifier = Modifier.size(36.dp).testTag("recall_auto_next"),
                )
                Text(
                    stringResource(R.string.listening_recall_auto_next),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { onSetAutoNext(!autoNext) },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = {
                        onSaveDraft(answer)
                        previousIndex?.let(onPlayLine)
                    },
                    enabled = previousIndex != null,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.listening_recall_previous)) }
                Button(
                    onClick = {
                        onSaveDraft(answer)
                        nextIndex?.let(onPlayLine)
                    },
                    enabled = nextIndex != null,
                    modifier = Modifier.weight(1f).testTag("recall_next"),
                ) { Text(stringResource(R.string.listening_recall_next)) }
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
                        .background(if (part.matches) Color.Transparent else errorBackground)
                val wordModifier = coloredModifier.then(clickable).padding(horizontal = 3.dp, vertical = 2.dp)
                Text(
                    text = shown,
                    modifier = wordModifier,
                    color = if (part.matches) regularColor else errorColor,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
