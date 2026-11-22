package com.kienhoang.dualsubreplay.listening

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Full-screen overlay; original YouTube WebView stays composed and untouched underneath it.
 * TTS uses a separate foreground Service and can continue while the phone is locked.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun ListeningScreen(
    store: ListeningStore,
    github: ListeningGitHub,
    vocabulary: VocabularyRepository,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsStateWithLifecycle()
    val status by github.status.collectAsStateWithLifecycle()
    val playback by ListeningPlayback.state.collectAsStateWithLifecycle()
    var currentId by remember { mutableStateOf<String?>(null) }
    val pack = state.packs.firstOrNull { it.id == currentId } ?: state.packs.firstOrNull()
    var word by remember { mutableStateOf<String?>(null) }
    var selectedLine by remember { mutableStateOf<ListeningLine?>(null) }
    var pat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(github.connected) }
    var message by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var reviewMode by remember { mutableStateOf(false) }
    var reviewWord by remember { mutableStateOf<String?>(null) }
    var answerVisible by remember { mutableStateOf(false) }
    val selectedText = selectedLine?.en.orEmpty()
    val wordRegex = remember { Regex("[A-Za-z]+(?:['-][A-Za-z]+)*") }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Headphones, contentDescription = null)
                    Text("  离线英语听力", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showSettings = !showSettings }) { Text("GitHub") }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "返回 YouTube") }
                }
            }) { inner ->
                LazyColumn(
                    Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "对话包 " + state.packs.size + " · 未上传 " + state.pending +
                                " · 收藏词 " + state.saved + " · 到期复习 " + state.due +
                                " · 未稳固 " + state.unestablished,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.unestablished >= 100) {
                        item {
                            Card {
                                Text(
                                    "当前有 " + state.unestablished +
                                        " 个尚未经过有效复习验证的词。建议优先复习；仍可继续收藏。",
                                    Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                    if (showSettings) {
                        item {
                            Card {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("连接私有 z2846852/english 仓库", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "PAT 只保存在本机 Keystore 加密文件中。首次连接联网验证，此后每天首次打开自动同步一次。",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (!connected) {
                                        OutlinedTextField(
                                            value = pat,
                                            onValueChange = { pat = it },
                                            label = { Text("GitHub Fine-grained PAT") },
                                            visualTransformation = PasswordVisualTransformation(),
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Button(enabled = !busy && pat.isNotBlank(), onClick = {
                                            busy = true
                                            scope.launch {
                                                try {
                                                    github.connect(pat)
                                                    connected = true
                                                    pat = ""
                                                    github.syncNow()
                                                } catch (
                                                    e: Exception,
                                                ) {
                                                    message = "连接失败：" + (e.message ?: "请检查权限")
                                                } finally {
                                                    busy = false
                                                }
                                            }
                                        }) { Text("连接并同步") }
                                    } else {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = {
                                                github.forgetToken()
                                                connected = false
                                            }) { Text("清除 PAT") }
                                            Text("已连接", Modifier.padding(top = 10.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy && connected, onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        message = github.syncNow()
                                    } finally {
                                        busy = false
                                    }
                                }
                            }) { Text("手动同步 GitHub") }
                            if (busy) CircularProgressIndicator()
                        }
                        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        HorizontalDivider()
                        Text(
                            "听力记忆训练（FSRS-6，90% 目标保留率）",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "只在没有看文字的情况下，对自己的首次识别进行评分。普通连续听力不会自动刷分。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!reviewMode) {
                            Button(enabled = store.reviewCandidates().isNotEmpty(), onClick = {
                                reviewWord = store.reviewCandidates().firstOrNull()
                                answerVisible = false
                                reviewMode = true
                            }) { Text("开始听辨复习") }
                        } else {
                            val target = reviewWord
                            if (target == null) {
                                Text("当前待复习项目已经完成。")
                                OutlinedButton(onClick = { reviewMode = false }) { Text("退出") }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val practice =
                                            ListeningPack(
                                                "self-test",
                                                "听辨自测",
                                                "A2",
                                                listOf(ListeningLine("word", "A", target, "")),
                                            )
                                        ListeningPlayback.start(ctx, practice)
                                    }) { Text("▶ 播放声音") }
                                    OutlinedButton(onClick = { answerVisible = true }) { Text("显示答案") }
                                }
                                if (answerVisible) {
                                    Text(target, style = MaterialTheme.typography.headlineSmall)
                                    Text("你首次听到时的识别情况：", style = MaterialTheme.typography.bodySmall)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf(
                                            "again" to "没认出",
                                            "hard" to "很费力",
                                            "good" to "认出",
                                            "easy" to "立即认出",
                                        ).forEach { (rating, label) ->
                                            OutlinedButton(onClick = {
                                                store.addReview(target, rating)
                                                reviewWord = store.reviewCandidates().firstOrNull()
                                                answerVisible = false
                                            }) { Text(label) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.packs.isEmpty()) {
                        item {
                            Text(
                                "暂无对话包。点击右上角 GitHub，输入仅授权 english 仓库的 PAT 后同步。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        item { Text("选择听力材料", style = MaterialTheme.typography.titleSmall) }
                        items(state.packs, key = { it.id }) { p ->
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                currentId = p.id
                                selectedLine = null
                                word = null
                                showText = false
                            }) {
                                Text((if (p.id == pack?.id) "● " else "") + p.title + " · " + p.level)
                            }
                        }
                        if (pack != null) {
                            item {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(onClick = { ListeningPlayback.start(ctx, pack) }) { Text("▶ 连续播放") }
                                    OutlinedButton(onClick = { ListeningPlayback.stop(ctx) }) { Text("■ 停止") }
                                    TextButton(onClick = { showText = !showText }) {
                                        Text(if (showText) "隐藏原文" else "显示原文")
                                    }
                                }
                                if (playback.packId == pack.id) {
                                    Text(
                                        if (playback.playing) {
                                            "正在朗读第 " + (playback.currentLine + 1) + " 句"
                                        } else {
                                            (playback.error ?: "已停止")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (showText) {
                                items(pack.lines, key = { pack.id + it.id }) { line ->
                                    Card {
                                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(line.speaker + "：", style = MaterialTheme.typography.labelLarge)
                                                Spacer(Modifier.weight(1f))
                                                TextButton(onClick = {
                                                    ListeningPlayback.start(ctx, pack, pack.lines.indexOf(line))
                                                }) { Text("从这里播放") }
                                            }
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                val parts = wordRegex.findAll(line.en).map { it.value }.toList()
                                                parts.forEach { part ->
                                                    Text(
                                                        part + " ",
                                                        modifier =
                                                            Modifier
                                                                .clickable {
                                                                    word = part
                                                                    selectedLine = line
                                                                }.padding(vertical = 5.dp),
                                                        color =
                                                            if (word == part && selectedLine == line) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurface
                                                            },
                                                    )
                                                }
                                            }
                                            Text(
                                                line.textZh(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (word != null && pack != null && selectedLine != null) {
                        item {
                            HorizontalDivider()
                            Text("听力盲点：" + word, style = MaterialTheme.typography.titleMedium)
                            Text(selectedText, style = MaterialTheme.typography.bodyMedium)
                            Text(selectedLine?.zh.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                val w = requireNotNull(word)
                                val line = requireNotNull(selectedLine)
                                store.addWord(w, pack, line)
                                scope.launch {
                                    try {
                                        val id =
                                            (pack.id + ":" + line.id + ":" + w.lowercase())
                                                .toByteArray(StandardCharsets.UTF_8)
                                                .let {
                                                    MessageDigest.getInstance("SHA-256").digest(it)
                                                }.joinToString("") { "%02x".format(it) }
                                        vocabulary.save(
                                            SavedWord(
                                                id = id,
                                                word = w,
                                                reading = null,
                                                wordLanguage = "en",
                                                meaningLanguage = "zh",
                                                meaning = "",
                                                sentence = line.en,
                                                translatedSentence = line.zh,
                                                videoId = null,
                                                startMs = 0,
                                                endMs = 0,
                                                translated = false,
                                                online = false,
                                                sourceTitle = pack.title,
                                            ),
                                        )
                                        message = "已收藏 " + w + "（本地，待同步 GitHub）"
                                    } catch (e: Exception) {
                                        message = "学习事件已保存，但词库保存失败：" + (e.message ?: "")
                                    }
                                }
                            }) { Text("收藏到生词簿") }
                            Text(
                                "当前版本保留原句上下文；更多 AI 例句将在 GitHub 专项对话包中提供。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    item { Spacer(Modifier.padding(bottom = 20.dp)) }
                }
            }
        }
    }
}

private fun ListeningLine.textZh(): String = zh
