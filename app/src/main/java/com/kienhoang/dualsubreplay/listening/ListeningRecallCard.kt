package com.kienhoang.dualsubreplay.listening

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

/**
 * A separate mode of Offline Listening: one Google-natural-voice sentence, an explicit IME
 * dictation attempt, and a word-level text comparison. No speech recognition is owned by the app.
 */
@Composable
internal fun ListeningRecallCard(
    pack: ListeningPack,
    lineIndex: Int,
    playback: ListeningPlaybackState,
    onPlayLine: (Int) -> Unit,
    onTryLocal: () -> Unit,
    onWordTap: (String, ListeningLine) -> Unit,
) {
    val index = lineIndex.coerceIn(pack.lines.indices)
    val line = pack.lines[index]
    val current = playback.packId == pack.id && playback.currentLine == index
    val playing = current && playback.playing
    val finished = current && playback.finished
    val error = playback.error.takeIf { current }
    var answer by remember(pack.id, line.id) { mutableStateOf("") }
    var checked by remember(pack.id, line.id) { mutableStateOf(false) }
    val comparison = remember(line.en, answer, checked) {
        if (checked) ListeningRecallCheck.compare(line.en, answer) else null
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
                Text(
                    "\u266a ${line.speaker}",
                    style = MaterialTheme.typography.titleMedium,
                )
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
                TextButton(onClick = onTryLocal) { Text(stringResource(R.string.listening_recall_try_local)) }
            }
            OutlinedButton(
                onClick = { onPlayLine(index) },
                modifier = Modifier.fillMaxWidth().testTag("recall_replay"),
            ) { Text(stringResource(R.string.listening_recall_replay)) }

            Text(
                stringResource(R.string.listening_recall_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val canCheck = answer.isNotBlank() && !playing && !checked
            OutlinedTextField(
                value = answer,
                onValueChange = { if (!checked) answer = it },
                modifier = Modifier.fillMaxWidth().testTag("recall_answer"),
                placeholder = { Text(stringResource(R.string.listening_recall_placeholder)) },
                minLines = 2,
                maxLines = 4,
                enabled = !checked,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canCheck) checked = true }),
            )

            if (comparison == null) {
                Button(
                    enabled = canCheck,
                    onClick = { checked = true },
                    modifier = Modifier.fillMaxWidth().testTag("recall_check"),
                ) { Text(stringResource(R.string.listening_recall_check)) }
            } else {
                Text(
                    if (comparison.perfect) {
                        stringResource(R.string.listening_recall_perfect)
                    } else {
                        stringResource(R.string.listening_recall_differences, comparison.differences)
                    },
                    color = if (comparison.perfect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("recall_result"),
                )
                Text(stringResource(R.string.listening_recall_expected), style = MaterialTheme.typography.labelMedium)
                RecallDifferenceRow(comparison, expected = true, onTargetTap = { onWordTap(it, line) })
                Text(stringResource(R.string.listening_recall_actual), style = MaterialTheme.typography.labelMedium)
                RecallDifferenceRow(comparison, expected = false, onTargetTap = {})
                if (line.zh.isNotBlank()) {
                    Text(line.zh, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(R.string.listening_recall_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        checked = false
                        answer = ""
                        onPlayLine(index)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("recall_retry"),
                ) { Text(stringResource(R.string.listening_recall_retry)) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { onPlayLine(index - 1) },
                    enabled = index > 0,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.listening_recall_previous)) }
                Button(
                    onClick = { onPlayLine(index + 1) },
                    enabled = index < pack.lines.lastIndex,
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
            val shown = if (expected) part.expected else part.actual ?: if (part.expected != null) missing else null
            if (shown != null) {
                val clickable = if (expected) Modifier.clickable { onTargetTap(shown) } else Modifier
                Text(
                    text = shown,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp))
                        .background(if (part.matches) Color.Transparent else errorBackground)
                        .then(clickable).padding(horizontal = 3.dp, vertical = 2.dp),
                    color = if (part.matches) regularColor else errorColor,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
