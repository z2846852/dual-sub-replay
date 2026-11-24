package com.kienhoang.dualsubreplay.listening

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Original words are not graded, rephrased, or automatically sent to AI. */
internal data class ListeningReflectionRequest(
    val pack: ListeningPack,
    val lineId: String,
    val prompt: String,
)

@Composable
internal fun ListeningRawReflectionDialog(
    request: ListeningReflectionRequest,
    onSave: (ListeningReflectionRequest, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var answer by remember(request.pack.id, request.lineId, request.prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (request.lineId.isBlank()) "对话后自由表达" else "这一句 · 我的想法") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(request.prompt, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "用英语表达自己的想法。保存到本地 Raw；稍后在 GitHub 审核中决定是否上传。" +
                        " 这里不自动修改原文、不给口音分数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it.take(5000) },
                    label = { Text("自由表达 · 原文保存") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                    minLines = 4,
                    maxLines = 9,
                )
                Text(answer.length.toString() + "/5000", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(enabled = answer.isNotBlank(), onClick = { onSave(request, answer) }) {
                Text("保存 Raw")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Separate next-day/7-day/30-day tests from memorising the sentence immediately. */
@Composable
internal fun ListeningDelayedReviewDialog(
    due: List<ListeningRetest>,
    onPlay: (ListeningRetest) -> Unit,
    onCheck: (ListeningRetest, String) -> Boolean,
    onClose: () -> Unit,
) {
    val item = due.firstOrNull()
    if (item == null) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("延迟复查已完成") },
            text = { Text("没有到期句子。下一批会在对应日期出现。") },
            confirmButton = { Button(onClick = onClose) { Text("完成") } },
        )
        return
    }
    key(item.pack.id, item.lineIndex, item.dueOn) {
        var answer by remember { mutableStateOf("") }
        var lastResult by remember { mutableStateOf<Boolean?>(null) }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("隔天回忆 · " + due.size + " 句待测") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        item.pack.title + " · 第 " + (item.lineIndex + 1) + " 句",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "不看原句，先听音再输入。只记录延迟回忆结果，不覆盖原来的首次成绩。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { onPlay(item) }) { Text("▶ 重听该句") }
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it.take(1000) },
                        label = { Text("你听到的英文") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                    )
                    if (lastResult != null) {
                        Text(
                            if (lastResult == true) "复查通过，已安排下一次间隔"
                            else "本次未通过，明天继续复查；不会抹去此前进步",
                            color = if (lastResult == true) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(item.pack.lines[item.lineIndex].en, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = answer.isNotBlank() && lastResult == null,
                    onClick = { lastResult = onCheck(item, answer) },
                ) { Text(if (lastResult != null) "已记录" else "核对并记入保持率") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("返回") } },
        )
    }
}
