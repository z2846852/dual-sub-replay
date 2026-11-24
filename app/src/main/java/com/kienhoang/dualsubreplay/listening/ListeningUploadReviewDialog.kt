package com.kienhoang.dualsubreplay.listening

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** This dialog is the mandatory manual-upload gate; no local record is deleted. */
@Composable
internal fun ListeningUploadReviewDialog(
    pending: List<JSONObject>,
    excluded: List<JSONObject>,
    busy: Boolean,
    onExclude: (String) -> Unit,
    onRestore: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showExcluded by remember { mutableStateOf(false) }
    val automatic = pending.count { it.optString("type") == "recall_checked" }
    val curated = pending.count { it.optString("type") == "recall_insight" }
    val other = pending.size - automatic - curated

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("GitHub 同步前审核") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "待上传 ${pending.size} 条 · 自动练习 $automatic · 主动收藏 $curated · 其他 $other",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "点击 × 从本次及后续上传中排除笔误；本机练习历史不删除。可随时在下方恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("时间 / 类型", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Text("记录与结果", modifier = Modifier.weight(2.6f), style = MaterialTheme.typography.labelSmall)
                    Text("排除", style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(pending, key = { it.optString("id") }) { event ->
                        ListeningUploadRow(event, busy, false, onExclude)
                    }
                    if (pending.isEmpty()) {
                        item {
                            Text(
                                "暂无待上传的记录；仍可同步内容包和词汇备份。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (excluded.isNotEmpty()) {
                        item {
                            TextButton(onClick = { showExcluded = !showExcluded }, enabled = !busy) {
                                Text(if (showExcluded) "收起已排除" else "查看已排除 ${excluded.size} 条（可恢复）")
                            }
                        }
                        if (showExcluded) {
                            items(excluded, key = { "excluded-" + it.optString("id") }) { event ->
                                ListeningUploadRow(event, busy, true, onRestore)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "同步中…" else "确认上传 ${pending.size} 条并同步")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ListeningUploadRow(
    event: JSONObject,
    busy: Boolean,
    excluded: Boolean,
    onChange: (String) -> Unit,
) {
    val type = event.optString("type")
    val label =
        when (type) {
            "recall_checked" -> if (event.optBoolean("passed")) "复述通过" else "复述错误"
            "recall_insight" -> "★ 主动收藏"
            "saved" -> "收藏单词"
            "reviewed" -> "词汇复习"
            else -> type
        }
    val input =
        when (type) {
            "recall_checked" -> event.optString("checked_input")
            "recall_insight" -> event.optString("observed") +
                " · " + event.optString("category") + " " + event.optString("note")
            else -> event.optString("word")
        }.ifBlank { "（无文本）" }
    val timestamp = event.optString("created_at").replace('T', ' ').take(16)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(timestamp.takeLast(11), style = MaterialTheme.typography.labelSmall)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
        Column(modifier = Modifier.weight(2.6f)) {
            Text(input, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (type == "recall_checked") {
                Text(
                    event.optString("dialogue_id") + " / " + event.optString("line_id") +
                        " · 错词 " + event.optInt("differences"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (excluded) {
            TextButton(onClick = { onChange(event.optString("id")) }, enabled = !busy) { Text("恢复") }
        } else {
            IconButton(onClick = { onChange(event.optString("id")) }, enabled = !busy) {
                Icon(Icons.Default.Close, contentDescription = "不上传此条")
            }
        }
    }
    HorizontalDivider()
}
