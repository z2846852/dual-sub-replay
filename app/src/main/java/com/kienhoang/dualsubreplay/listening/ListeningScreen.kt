package com.kienhoang.dualsubreplay.listening

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.KnownWordsRepository
import com.kienhoang.dualsubreplay.data.knownWordKey
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import com.kienhoang.dualsubreplay.ui.rememberWordPronouncer
import com.kienhoang.dualsubreplay.ui.rememberStudySettings
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Full-screen overlay; original YouTube WebView stays composed and untouched underneath it.
 * The speech service prefers Google natural-voice MP3/cache, with optional device TTS.
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
    val wordPronouncer = rememberWordPronouncer()
    val knownWordRepository = remember(ctx) { KnownWordsRepository.get(ctx) }
    val knownWords by remember(knownWordRepository) { knownWordRepository.words("en") }.collectAsStateWithLifecycle()
    val vocabularyWords by vocabulary.words.collectAsStateWithLifecycle()
    val learningWords = remember(vocabularyWords) {
        vocabularyWords.filter { it.wordLanguage.substringBefore('-').equals("en", ignoreCase = true) }
            .mapNotNull { knownWordKey(it.word) }.toSet()
    }
    val studySettings by rememberStudySettings()
    val cacheProgress by ListeningVoicePrefetch.state.collectAsStateWithLifecycle()
    val voicePreferences = remember(ctx) {
        ctx.getSharedPreferences("dual_sub_preferences", android.content.Context.MODE_PRIVATE)
    }
    var preferNatural by remember { mutableStateOf(voicePreferences.getBoolean("listening_natural_voice", true)) }
    val state by store.state.collectAsStateWithLifecycle()
    val status by github.status.collectAsStateWithLifecycle()
    val playback by ListeningPlayback.state.collectAsStateWithLifecycle()
    var currentId by remember { mutableStateOf<String?>(null) }
    val packSort = store.recallSort()
    val orderedPacks = sortListeningPacks(state.packs, packSort, store.recallRecentUsage())
    val pack = orderedPacks.firstOrNull { it.id == currentId } ?: orderedPacks.firstOrNull()
    var word by remember { mutableStateOf<String?>(null) }
    var selectedLine by remember { mutableStateOf<ListeningLine?>(null) }
    var pat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(github.connected) }
    var message by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showUploadReview by remember { mutableStateOf(false) }
    var focusedReviewLine by remember { mutableStateOf<String?>(null) }
    var recallMode by remember { mutableStateOf(false) }
    var recallIndex by remember(pack?.id) { mutableStateOf(pack?.let(store::recallPosition) ?: 0) }
    var wrongOnly by remember(pack?.id) { mutableStateOf(false) }
    var reviewMode by remember { mutableStateOf(false) }
    var reviewWord by remember { mutableStateOf<String?>(null) }
    var answerVisible by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Headphones, contentDescription = null)
                    Text("  离线英语听力", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showSettings = !showSettings }) { Text("设置") }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "返回 YouTube") }
                }
            }) { inner ->
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .padding(horizontal = 16.dp)
                        .imePadding(),
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
                    if (pack != null) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilterChip(
                                    selected = !recallMode,
                                    onClick = {
                                        if (recallMode) {
                                            ListeningPlayback.stop(ctx)
                                            recallMode = false
                                        }
                                    },
                                    label = { Text(stringResource(R.string.listening_continuous)) },
                                )
                                FilterChip(
                                    selected = recallMode,
                                    onClick = {
                                        if (!recallMode) {
                                            recallMode = true
                                            showText = false
                                            val target =
                                                if (playback.packId == pack.id && playback.playing) {
                                                    playback.currentLine
                                                } else {
                                                    store.recallPosition(pack)
                                                }
                                            recallIndex = target.coerceIn(pack.lines.indices)
                                            store.setRecallPosition(pack, recallIndex)
                                            ListeningPlayback.start(ctx, pack, recallIndex, oneShot = true)
                                        }
                                    },
                                    label = { Text(stringResource(R.string.listening_recall)) },
                                )
                            }
                        }
                        if (recallMode) {
                            item {
                                val progress = pack.lines.map { store.recallProgress(pack, it) }
                                val failed = pack.lines.indices.filter { progress[it].failed }
                                val previousIndex =
                                    if (wrongOnly && failed.isNotEmpty()) {
                                        failed.lastOrNull { it < recallIndex } ?: failed.last()
                                    } else {
                                        (recallIndex - 1).takeIf { it >= 0 }
                                    }
                                val nextIndex =
                                    if (wrongOnly && failed.isNotEmpty()) {
                                        failed.firstOrNull { it > recallIndex } ?: failed.first()
                                    } else {
                                        (recallIndex + 1).takeIf { it <= pack.lines.lastIndex }
                                    }

                                fun playLine(i: Int) {
                                    recallIndex = i.coerceIn(pack.lines.indices)
                                    store.setRecallPosition(pack, recallIndex)
                                    ListeningPlayback.start(ctx, pack, recallIndex, oneShot = true)
                                }
                                ListeningRecallCard(
                                    pack = pack,
                                    lineIndex = recallIndex,
                                    progressByLine = progress,
                                    playback = playback,
                                    previousIndex = previousIndex,
                                    nextIndex = nextIndex,
                                    wrongOnly = wrongOnly,
                                    autoNext = store.recallAutoNext(),
                                    onSetAutoNext = store::setRecallAutoNext,
                                    onAutoAdvance = {
                                        val remaining =
                                            if (wrongOnly) {
                                                pack.lines.indices.filter { i ->
                                                    store.recallProgress(pack, pack.lines[i]).failed
                                                }
                                            } else {
                                                emptyList()
                                            }
                                        val target =
                                            nextAutomaticRecallIndex(
                                                recallIndex,
                                                pack.lines.lastIndex,
                                                wrongOnly,
                                                remaining,
                                            )
                                        target?.let(::playLine)
                                    },
                                    onToggleWrongOnly = {
                                        if (wrongOnly) {
                                            wrongOnly = false
                                        } else if (failed.isNotEmpty()) {
                                            wrongOnly = true
                                            playLine(failed.first())
                                        }
                                    },
                                    onPlayLine = { playLine(it) },
                                    onCheck = { answer, correction ->
                                        store.checkRecall(pack, pack.lines[recallIndex], answer, correction)
                                    },
                                    onHint = { level ->
                                        store.setRecallHint(pack, pack.lines[recallIndex], level)
                                    },
                                    onSaveDraft = { answer ->
                                        store.saveRecallDraft(pack, pack.lines[recallIndex], answer)
                                    },
                                    onSaveInsight = { category, note, observed ->
                                        store.saveRecallInsight(
                                            pack,
                                            pack.lines[recallIndex],
                                            observed,
                                            category,
                                            note,
                                        )
                                    },
                                    onOpenReview = {
                                        focusedReviewLine = pack.id + "/" + pack.lines[recallIndex].id
                                        showUploadReview = true
                                        if (connected && !busy) {
                                            busy = true
                                            scope.launch {
                                                try { message = github.refreshCloudReviewHistory() }
                                                finally { busy = false }
                                            }
                                        }
                                    },
                                    onTryLocal = { ListeningPlayback.tryLocal(ctx) },
                                    onWordTap = { selected, line ->
                                        word = selected
                                        selectedLine = line
                                    },
                                )
                            }
                        } else {
                            item {
                                ListeningKaraokeCard(
                                    pack = pack,
                                    playback = playback,
                                    showText = showText,
                                    knownWords = if (studySettings.knownWords) knownWords else emptySet(),
                                    learningWords = learningWords,
                                    markNewWords = studySettings.knownWords && studySettings.markNewWords,
                                    onPrevious = {
                                        val i = if (playback.packId == pack.id) playback.currentLine else 0
                                        ListeningPlayback.start(ctx, pack, (i - 1).coerceAtLeast(0))
                                    },
                                    onNext = {
                                        val i = if (playback.packId == pack.id) playback.currentLine else 0
                                        ListeningPlayback.start(ctx, pack, (i + 1).coerceAtMost(pack.lines.lastIndex))
                                    },
                                    onPlayPause = {
                                        when {
                                            playback.playing && playback.packId == pack.id ->
                                                ListeningPlayback.pause(ctx)
                                            playback.paused && playback.packId == pack.id && playback.error == null ->
                                                ListeningPlayback.resume(ctx)
                                            else -> ListeningPlayback.start(
                                                ctx,
                                                pack,
                                                if (playback.packId == pack.id) playback.currentLine else 0,
                                            )
                                        }
                                    },
                                    onTryLocal = { ListeningPlayback.tryLocal(ctx) },
                                    onToggleText = { showText = !showText },
                                    onWordTap = { selected, line ->
                                        if (playback.playing && playback.packId == pack.id) ListeningPlayback.pause(ctx)
                                        word = selected
                                        selectedLine = line
                                    },
                                )
                            }
                        }
                    }
                    if (state.packs.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.listening_recall_no_stories),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.listening_recall_stories),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Box {
                                    val sortLabel =
                                        when (packSort) {
                                            ListeningPackSort.RECENT -> R.string.listening_recall_sort_recent
                                            ListeningPackSort.LEVEL_ASC -> R.string.listening_recall_sort_easy
                                            ListeningPackSort.LEVEL_DESC -> R.string.listening_recall_sort_hard
                                            ListeningPackSort.TITLE -> R.string.listening_recall_sort_title
                                        }
                                    TextButton(onClick = { showSortMenu = true }) {
                                        Text(stringResource(sortLabel))
                                    }
                                    DropdownMenu(
                                        expanded = showSortMenu,
                                        onDismissRequest = { showSortMenu = false },
                                    ) {
                                        ListeningPackSort.entries.forEach { choice ->
                                            val label =
                                                when (choice) {
                                                    ListeningPackSort.RECENT -> R.string.listening_recall_sort_recent
                                                    ListeningPackSort.LEVEL_ASC -> R.string.listening_recall_sort_easy
                                                    ListeningPackSort.LEVEL_DESC -> R.string.listening_recall_sort_hard
                                                    ListeningPackSort.TITLE -> R.string.listening_recall_sort_title
                                                }
                                            DropdownMenuItem(
                                                text = { Text(stringResource(label)) },
                                                onClick = {
                                                    store.setRecallSort(choice)
                                                    showSortMenu = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        items(orderedPacks, key = { it.id }) { p ->
                            val statuses = p.lines.map { store.recallProgress(p, it) }
                            val completed = statuses.count { it.passed }
                            val errors = statuses.count { it.failed }
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                store.markPackUsed(p)
                                currentId = p.id
                                selectedLine = null
                                word = null
                                showText = false
                                if (recallMode) {
                                    wrongOnly = false
                                    recallIndex = store.recallPosition(p)
                                    ListeningPlayback.start(ctx, p, recallIndex, oneShot = true)
                                } else if (playback.playing && playback.packId != p.id) {
                                    ListeningPlayback.stop(ctx)
                                }
                            }) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text((if (p.id == pack?.id) "● " else "") + p.title + " · " + p.level)
                                    Text(
                                        stringResource(
                                            R.string.listening_recall_pack_summary,
                                            completed,
                                            p.lines.size,
                                            errors,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        statuses.forEach { item ->
                                            val color =
                                                when {
                                                    item.passed -> MaterialTheme.colorScheme.tertiary
                                                    item.failed -> MaterialTheme.colorScheme.error
                                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                                }
                                            Box(
                                                Modifier
                                                    .weight(1f)
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(color),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (pack != null) {
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
                                            ListeningRainbowWords(
                                                text = line.en,
                                                active = playback.playing &&
                                                    playback.packId == pack.id &&
                                                    playback.currentLine == pack.lines.indexOf(line),
                                                synced = playback.hasWordTimings &&
                                                    playback.packId == pack.id &&
                                                    playback.currentLine == pack.lines.indexOf(line),
                                                start = playback.spokenStart,
                                                end = playback.spokenEnd,
                                                onWordTap = { selected ->
                                                    if (playback.playing && playback.packId == pack.id) ListeningPlayback.pause(ctx)
                                                    word = selected
                                                    selectedLine = line
                                                },
                                                onDark = false,
                                                knownWords = if (studySettings.knownWords) knownWords else emptySet(),
                                                learningWords = learningWords,
                                                markNewWords = studySettings.knownWords && studySettings.markNewWords,
                                            )
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("自然语音预缓存", style = MaterialTheme.typography.titleSmall)
                                            Text("同步后自动下载，缓存缺失时可尝试本机语音",
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        Switch(checked = preferNatural, onCheckedChange = {
                                            preferNatural = it
                                            voicePreferences.edit().putBoolean("listening_natural_voice", it).apply()
                                        })
                                    }
                                    Text("连接私有 z2846852/english 仓库", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "PAT 仅在本机加密保存。每天首次打开时尝试同步学习事件和词汇记忆；换手机且本地词库为空时可恢复云端备份。",
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
                                focusedReviewLine = null
                                showUploadReview = true
                                busy = true
                                scope.launch {
                                    try { message = github.refreshCloudReviewHistory() }
                                    finally { busy = false }
                                }
                            }) { Text("审核并同步 GitHub") }
                            if (busy) CircularProgressIndicator()
                        }
                        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                        if (cacheProgress.total > 0 &&
                            (cacheProgress.running || cacheProgress.complete < cacheProgress.total)) {
                            Text(
                                "语音缓存 ${cacheProgress.complete}/${cacheProgress.total}" +
                                    (cacheProgress.warning?.let { " · ❗$it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    item {
                        HorizontalDivider()
                        Text(
                            "听辨复习 · 从听懂开始",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "先盲听，再判断自己是否真正认出。听过很多次只代表接触过，不等于已经听懂。",
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
                    item { Spacer(Modifier.padding(bottom = 20.dp)) }
                }
            }
        }
    }
    if (showUploadReview) {
        val history = store.reviewableEvents()
        ListeningUploadReviewDialog(
            records = history,
            packs = state.packs,
            focusedLineKey = focusedReviewLine,
            bookmarked = store.bookmarkedErrorKeys(),
            pendingUploadCount = store.pendingEvents().size,
            pendingDeleteCount = store.pendingDeletions().size,
            busy = busy,
            onBookmark = store::toggleBookmarkedError,
            onTypo = store::toggleTypoLabel,
            onDelete = { id ->
                store.deleteReviewRecord(id)
                val pending = store.pendingDeletions().map { it.getString("id") }.toSet()
                if (pending.isNotEmpty() && connected && !busy) {
                    busy = true
                    scope.launch {
                        try {
                            message = github.deletePendingHistoryNow(pending)
                        } finally {
                            busy = false
                        }
                    }
                } else {
                    message = if (pending.isEmpty()) {
                        "已从本地彻底删除"
                    } else {
                        "本地已删除；GitHub 暂未连接，下次同步继续删除云端记录"
                    }
                }
            },
            onRefreshCloud = {
                if (!busy && connected) {
                    busy = true
                    scope.launch {
                        try { message = github.refreshCloudReviewHistory() }
                        finally { busy = false }
                    }
                }
            },
            onConfirmSync = {
                val approvedIds = store.pendingEvents().map { it.getString("id") }.toSet()
                val approvedDeletions = store.pendingDeletions().map { it.getString("id") }.toSet()
                 busy = true
                scope.launch {
                    try {
                        message = github.syncNow(
                            approvedEventIds = approvedIds,
                            approvedDeletionIds = approvedDeletions,
                        )
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = {
                if (!busy) {
                    showUploadReview = false
                    focusedReviewLine = null
                }
            },
        )
    }

    // The word card must be immediately reachable after a tap, not buried below all dialogue rows.
    // Tapping a word pauses speech first; collecting it does not count as a successful audio review.
    val selectedWord = word
    val selected = selectedLine
    val currentPack = pack
    if (selectedWord != null && selected != null && currentPack != null) {
        AlertDialog(
            onDismissRequest = {
                wordPronouncer.stop()
                word = null
                selectedLine = null
                if (!recallMode && playback.paused && playback.packId == currentPack.id) {
                    ListeningPlayback.resume(ctx)
                }
            },
            title = { Text("听力生词 · $selectedWord") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.en)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            wordPronouncer.speak(selectedWord, "en")
                        }) { Text("🔊 单词发音") }
                        OutlinedButton(onClick = {
                            wordPronouncer.stop()
                            word = null
                            selectedLine = null
                            ListeningPlayback.start(
                                ctx,
                                currentPack,
                                currentPack.lines.indexOf(selected).coerceAtLeast(0),
                                oneShot = recallMode,
                            )
                        }) { Text("▶ 重听本句") }
                    }
                    if (selected.zh.isNotBlank()) {
                        Text(selected.zh, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        if (!recallMode && playback.paused && playback.packId == currentPack.id) {
                            "朗读已暂停。收藏后将从这一句开头继续。"
                        } else {
                            "收藏到统一的生词簿，之后可以做听辨复习并同步 GitHub。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val w = selectedWord
                    val line = selected
                    val savedPack = currentPack
                    wordPronouncer.stop()
                    word = null
                    selectedLine = null
                    scope.launch {
                        try {
                            store.addWord(w, savedPack, line)
                            val id =
                                (savedPack.id + ":" + line.id + ":" + w.lowercase())
                                    .toByteArray(StandardCharsets.UTF_8)
                                    .let { MessageDigest.getInstance("SHA-256").digest(it) }
                                    .joinToString("") { "%02x".format(it) }
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
                                    sourceTitle = savedPack.title,
                                ),
                            )
                            message = "已收藏 $w · 待下次 GitHub 同步"
                            if (!recallMode && playback.paused && playback.packId == savedPack.id) {
                                ListeningPlayback.resume(ctx)
                            }
                        } catch (e: Exception) {
                            message = "收藏失败：" + (e.message ?: "未知错误")
                        }
                    }
                }) { Text("收藏并继续") }
            },
            dismissButton = {
                TextButton(onClick = {
                    wordPronouncer.stop()
                    word = null
                    selectedLine = null
                    if (!recallMode && playback.paused && playback.packId == currentPack.id) {
                        ListeningPlayback.resume(ctx)
                    }
                }) { Text("不收藏 · 继续") }
            },
        )
    }
}

private fun ListeningLine.textZh(): String = zh
