package com.kienhoang.dualsubreplay.listening

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    onToggleWrongOnly: () -> Unit,
    onPlayLine: (Int) -> Unit,
    onCheck: (String, Boolean) -> Unit,
    onHint: (Int) -> Unit,
    onSaveDraft: (String) -> Unit,
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
    val checkedCurrent = lastChecked != null && lastChecked == answer
    val comparison =
        remember(line.en, lastChecked) {
            lastChecked?.let { ListeningRecallCheck.compare(line.en, it) }
        }
    val canCheck = answer.isNotBlank() && !playing

    fun checkAnswer() {
        if (!canCheck) return
        onCheck(answer, correctionPending)
        lastChecked = answer
        revealed = 0
        correctionPending = false
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("\u266a ${line.speaker}", style = MaterialTheme.typography.titleMedium)
                Text("${index + 1}/${pack.lines.size}", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                when {
                    playing -> stringResource(R.string.listening_recall_playing)
                    finished -> stringResource(R.string.listening_recall_waiting)
                    else -> stringResource(R.string.listening_recall_hidden)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onTryLocal) {
                    Text(stringResource(R.string.listening_recall_try_local))
                }
            }
            Text(
                stringResource(R.string.listening_recall_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = answer,
                onValueChange = {
                    answer = it
                    if (lastChecked != it) revealed = 0
                },
                modifier = Modifier.fillMaxWidth().testTag("recall_answer"),
                placeholder = { Text(stringResource(R.string.listening_recall_placeholder)) },
                minLines = 2,
                maxLines = 4,
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
            Button(
                enabled = canCheck,
                onClick = { checkAnswer() },
                modifier = Modifier.fillMaxWidth().testTag("recall_check"),
            ) {
                Text(stringResource(R.string.listening_recall_check))
            }
            if (progress.attempts > 0) {
                Text(
                    if (progress.passed) {
                        stringResource(R.string.listening_recall_perfect)
                    } else {
                        stringResource(R.string.listening_recall_failed, progress.attempts)
                    },
                    color =
                        if (progress.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("recall_result"),
                )
                if (progress.failed) {
                    TextButton(onClick = {
                        correctionPending = true
                        revealed = 0
                    }) {
                        Text(stringResource(R.string.listening_recall_recognition_correction))
                    }
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
                ) { Text(stringResource(R.string.listening_recall_replay)) }
                OutlinedButton(
                    enabled = progress.mayShowRedOnly() && checkedCurrent && comparison?.perfect == false,
                    onClick = {
                        revealed = 1
                        onHint(1)
                    },
                    modifier = Modifier.weight(1f).testTag("recall_red_only"),
                ) { Text(stringResource(R.string.listening_recall_red_only)) }
                OutlinedButton(
                    onClick = {
                        revealed = 2
                        onHint(2)
                    },
                    modifier = Modifier.weight(1f).testTag("recall_reveal"),
                ) { Text(stringResource(R.string.listening_recall_reveal)) }
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
            Text(
                stringResource(R.string.listening_recall_summary, passes, failures, pack.lines.size),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                progressByLine.forEachIndexed { i, status ->
                    val bg =
                        when {
                            status.passed -> MaterialTheme.colorScheme.primary
                            status.failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    val fg =
                        when {
                            status.passed -> MaterialTheme.colorScheme.onPrimary
                            status.failed -> MaterialTheme.colorScheme.onError
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    val label =
                        when {
                            status.passed -> stringResource(R.string.listening_recall_status_passed)
                            status.failed -> stringResource(R.string.listening_recall_status_failed)
                            else -> stringResource(R.string.listening_recall_status_new)
                        }
                    Box(
                        modifier =
                            Modifier
                                .padding(1.dp)
                                .size(27.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(bg)
                                .border(
                                    if (i == index) 2.dp else 0.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    RoundedCornerShape(5.dp),
                                )
                                .clickable {
                                    onSaveDraft(answer)
                                    onPlayLine(i)
                                }
                                .semantics {
                                    contentDescription = "${i + 1}: $label"
                                }
                                .testTag("recall_segment_${i + 1}"),
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
            OutlinedButton(
                onClick = {
                    onSaveDraft(answer)
                    onToggleWrongOnly()
                },
                enabled = wrongOnly || failures > 0,
                modifier = Modifier.fillMaxWidth().testTag("recall_wrong_only"),
            ) {
                Text(
                    if (wrongOnly) {
                        stringResource(R.string.listening_recall_all_sentences)
                    } else {
                        stringResource(R.string.listening_recall_wrong_only, failures)
                    },
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
                Text(
                    text = shown,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (part.matches) Color.Transparent else errorBackground)
                            .then(clickable)
                            .padding(horizontal = 3.dp, vertical = 2.dp),
                    color = if (part.matches) regularColor else errorColor,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
