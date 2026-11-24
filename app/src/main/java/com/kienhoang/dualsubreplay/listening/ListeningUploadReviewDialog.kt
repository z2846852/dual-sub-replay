package com.kienhoang.dualsubreplay.listening

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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

private data class ReviewGroup(
    val key: String,
    val label: String,
    val records: List<JSONObject>,
)

/**
 * Shared review surface: a focused sentence's ⓘ and the final GitHub preflight.
 * Original attempts, learner-curated highlights and confirmed deletions are distinct.
 */
@Composable
internal fun ListeningUploadReviewDialog(
    records: List<JSONObject>,
    packs: List<ListeningPack>,
    focusedLineKey: String?,
    bookmarked: Set<String>,
    pendingUploadCount: Int,
    pendingDeleteCount: Int,
    statusMessage: String,
    busy: Boolean,
    onBookmark: (String, String) -> Unit,
    onTypo: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefreshCloud: () -> Unit,
    onConfirmSync: () -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember(focusedLineKey) { mutableStateOf("all") }
    var expanded by remember(focusedLineKey) { mutableStateOf<String?>(focusedLineKey) }
    var confirmDelete by remember { mutableStateOf<JSONObject?>(null) }
    val visible = records.filter { event ->
        val key = event.optString("dialogue_id") + "/" + event.optString("line_id")
        val matchesLine = focusedLineKey == null || focusedLineKey == key
        matchesLine && when (filter) {
            "pending" -> !event.optBoolean("synced") || event.optBoolean("deleted")
            "cloud" -> event.optBoolean("synced")
            else -> true
        }
    }
    val groups = visible.groupBy { it.optString("dialogue_id") + "/" + it.optString("line_id") }
        .map { (key, group) ->
            val packId = group.first().optString("dialogue_id")
            val lineId = group.first().optString("line_id")
            val pack = packs.firstOrNull { it.id == packId }
            val index = pack?.lines?.indexOfFirst { it.id == lineId } ?: -1
            val label = if (pack != null && index >= 0) {
                pack.title + " · 第 " + (index + 1) + " 句"
            } else {
                if (lineId.isEmpty()) "单词收藏与复习" else packId + " / " + lineId
            }
            ReviewGroup(key, label, group.sortedByDescending { it.optString("created_at") })
        }.sortedByDescending { it.records.firstOrNull()?.optString("created_at").orEmpty() }
    val nSynced = records.count { it.optBoolean("synced") && !it.optBoolean("deleted") }
    val nRaw = records.count { it.optString("type") == "recall_checked" && !it.optBoolean("deleted") }
    val nCurated = records.count { it.optString("type") == "recall_insight" && !it.optBoolean("deleted") }
    val nRawNotes = records.count { it.optString("type") == "raw_note" && !it.optBoolean("deleted") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (focusedLineKey == null) "同步审核 · 学习记录" else "本句记录 · 纠错管理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "原始复述 " + nRaw + " · 已传 " + nSynced +
                        " · 重点 " + nCurated + " · Raw " + nRawNotes + " · 待删 " + pendingDeleteCount,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (statusMessage.isNotBlank()) {
                    Text(
                        statusMessage,
                        color = if (statusMessage.contains("失败")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (focusedLineKey == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("all" to "全部", "pending" to "待处理", "cloud" to "已上传").forEach { (id, label) ->
                            FilterChip(
                                selected = filter == id,
                                onClick = { filter = id },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(end = 5.dp),
                            )
                        }
                    }
                }
                Text(
                    "点击句子展开尝试；★ 收藏具体错词，笔误可标记或删除。" +
                        " 删除立即更新本地，并尝试更新 GitHub 当前文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(groups, key = { it.key }) { group ->
                        val open = expanded == group.key || (expanded == null && groups.firstOrNull()?.key == group.key)
                        val failures = group.records.count {
                            it.optString("type") == "recall_checked" &&
                                !it.optBoolean("passed") && !it.optBoolean("deleted")
                        }
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expanded = if (open) "-" else group.key
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (open) "⌄" else "›", modifier = Modifier.padding(end = 5.dp))
                                Text(group.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(group.records.size.toString() + "次 / 错 " + failures,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            if (open) {
                                group.records.forEach { item ->
                                    ReviewAttemptRow(
                                        event = item,
                                        bookmarked = bookmarked,
                                        busy = busy,
                                        onBookmark = onBookmark,
                                        onTypo = onTypo,
                                        onDelete = { confirmDelete = item },
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (visible.isEmpty()) {
                        item {
                            Text("没有匹配记录。可读取云端历史，或继续练习。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = onRefreshCloud, enabled = !busy) {
                    Text(if (busy) "正在处理…" else "↻ 读取 GitHub 已上传历史")
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirmSync, enabled = !busy) {
                Text(if (busy) "同步中…" else
                    "同步 " + pendingUploadCount + " 条 · 删除 " + pendingDeleteCount + " 条")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
        },
    )
    val deleting = confirmDelete
    if (deleting != null) {
        val synced = deleting.optBoolean("synced")
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("确认删除这次练习记录？") },
            text = {
                Text(if (synced) {
                    "从手机删除这条记录，同时立即尝试从 GitHub 当前文件删除。" +
                        " 断网时会在本机等待重试。Git 的旧提交仍可能包含历史内容。"
                } else {
                    "立即从本机删除这条未上传的笔误，不再进入上传队列。"
                })
            },
            confirmButton = {
                Button(onClick = {
                    onDelete(deleting.optString("id"))
                    confirmDelete = null
                }) { Text("确定删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ReviewAttemptRow(
    event: JSONObject,
    bookmarked: Set<String>,
    busy: Boolean,
    onBookmark: (String, String) -> Unit,
    onTypo: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val id = event.optString("id")
    val type = event.optString("type")
    val deleted = event.optBoolean("deleted")
    val uploaded = event.optBoolean("synced")
    val source = if (type == "raw_note") event.optString("text") else
        event.optString("checked_input").ifBlank { event.optString("observed") }
    val title = when {
        type == "raw_note" -> "✎ 自由表达"
        type == "recall_insight" -> "★ 重点收藏"
        type == "recall_checked" && event.optBoolean("passed") -> "✓ 通过"
        type == "recall_checked" -> "✕ 未通过"
        type == "saved" -> "收藏词"
        else -> "词汇复习"
    }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium,
                color = if (deleted) MaterialTheme.colorScheme.onSurfaceVariant
                    else if (type == "recall_checked" && !event.optBoolean("passed")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary)
            Text(
                (if (uploaded) "云端" else "本地") + " · " +
                    event.optString("created_at").replace("T", " ").take(16),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(source.ifBlank { event.optString("selected_error").ifBlank { event.optString("word") } },
            style = MaterialTheme.typography.bodySmall, maxLines = 3,
            overflow = TextOverflow.Ellipsis)
        if (!deleted && type == "recall_checked" && !event.optBoolean("passed")) {
            val wrong = event.optJSONArray("wrong_words")
            if (wrong != null && wrong.length() > 0) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (0 until wrong.length()).map { wrong.optString(it) }.distinct()
                        .filter { it.isNotBlank() }.forEach { word ->
                            val selected = ListeningSyncPolicy.errorKey(id, word) in bookmarked
                            FilterChip(
                                selected = selected,
                                onClick = { onBookmark(id, word) },
                                enabled = !busy,
                                label = { Text((if (selected) "★ " else "☆ ") + word) },
                            )
                        }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!deleted && type == "recall_checked") {
                TextButton(onClick = { onTypo(id) }, enabled = !busy) {
                    Text(if (event.optString("review_label") == "typo") "✓ 笔误" else "标笔误")
                }
            }
            if (!deleted && type in setOf("recall_checked", "recall_insight", "raw_note")) {
                TextButton(onClick = { onDelete(id) }, enabled = !busy) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
            if (event.optString("type") == "recall_insight") {
                Text(event.optString("selected_error").ifBlank { event.optString("note") },
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        HorizontalDivider()
    }
}
