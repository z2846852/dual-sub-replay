package com.kienhoang.dualsubreplay.listening

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.CardDefaults
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
    startInRecall: Boolean = false,
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
    var sitcomMode by remember { mutableStateOf(false) }
    var sitcomSeason by remember { mutableStateOf(1) }
    val packSort = store.recallSort()
    val orderedPacks = sortListeningPacks(state.packs, packSort, store.recallRecentUsage())
        .filter { candidate ->
            if (!sitcomMode) candidate.kind != SITCOM_PACK_KIND else
                candidate.kind == SITCOM_PACK_KIND &&
                    candidate.id.startsWith("2bg-s%02d".format(sitcomSeason))
        }
    val seriesEpisodes = state.seriesEpisodes.filter { it.season == sitcomSeason }
    val cachedSeriesPacks = remember(state.packs) {
        state.packs.filter { it.kind == SITCOM_PACK_KIND }.associateBy { it.id }
    }
    val allSeriesPartIds = remember(state.seriesEpisodes) {
        state.seriesEpisodes
            .sortedWith(compareBy<ListeningSeriesEpisode> { it.season }.thenBy { it.episode })
            .flatMap { episode -> episode.parts.map { it.id } }
    }
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
    var recallMode by remember { mutableStateOf(startInRecall) }
    var initialRecallPlayed by remember { mutableStateOf(false) }
    var recallIndex by remember(pack?.id) { mutableStateOf(pack?.let(store::recallPosition) ?: 0) }
    var wrongOnly by remember(pack?.id) { mutableStateOf(false) }
    var reviewMode by remember { mutableStateOf(false) }
    var reviewWord by remember { mutableStateOf<String?>(null) }
    var answerVisible by remember { mutableStateOf(false) }
    var rawEditor by remember { mutableStateOf<ListeningReflectionRequest?>(null) }
    var delayedReviewOpen by remember { mutableStateOf(false) }

    // Opening via the top shortcut starts the saved sentence directly. The side-menu item
    // retains its current continuous-listening entry point. Never auto-restart after paging.
    LaunchedEffect(startInRecall, pack?.id) {
        if (startInRecall && pack != null && !initialRecallPlayed && pack.lines.isNotEmpty()) {
            initialRecallPlayed = true
            val resume = store.recallPosition(pack).coerceIn(pack.lines.indices)
            recallIndex = resume
            ListeningPlayback.start(ctx, pack, resume, oneShot = true)
        }
    }

    LaunchedEffect(sitcomMode, connected) {
        if (sitcomMode && connected) {
            busy = true
            try {
                message = github.downloadAllSeries()
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("听力训练", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showSettings = !showSettings }) { Text("设置") }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "返回 YouTube") }
                }
            }) { inner ->
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .padding(horizontal = 14.dp)
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = !sitcomMode,
                                onClick = {
                                    if (sitcomMode) {
                                        ListeningPlayback.stop(ctx)
                                        sitcomMode = false
                                        currentId = null
                                        showText = false
                                    }
                                },
                                label = { Text("日常课程") },
                            )
                            FilterChip(
                                selected = sitcomMode,
                                onClick = {
                                    if (!sitcomMode) {
                                        ListeningPlayback.stop(ctx)
                                        sitcomMode = true
                                        recallMode = true
                                        currentId = null
                                        showText = false
                                    }
                                },
                                label = { Text("🎬 破产姐妹") },
                            )
                        }
                        if (sitcomMode && busy) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Text("正在自动下载六季字幕…", Modifier.padding(start = 8.dp),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (sitcomMode && status.isNotBlank()) {
                            Text(status, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                        }
                    }
                    if (pack != null) {
                        item {
                            Text(
                                pack.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                            )
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
                                val nextSeriesPack = if (sitcomMode && !wrongOnly) {
                                    val at = allSeriesPartIds.indexOf(pack.id)
                                    if (at >= 0) allSeriesPartIds.getOrNull(at + 1)?.let(cachedSeriesPacks::get)
                                    else null
                                } else null
                                val nextIndex =
                                    if (wrongOnly && failed.isNotEmpty()) {
                                        failed.firstOrNull { it > recallIndex } ?: failed.first()
                                    } else {
                                        (recallIndex + 1).takeIf { it <= pack.lines.lastIndex }
                                            ?: if (nextSeriesPack != null) pack.lines.size else null
                                    }

                                fun playLine(i: Int) {
                                    if (i == pack.lines.size && nextSeriesPack != null) {
                                        sitcomSeason = nextSeriesPack.id.substringAfter("2bg-s").take(2).toInt()
                                        currentId = nextSeriesPack.id
                                        recallIndex = store.recallPosition(nextSeriesPack)
                                        store.setRecallPosition(nextSeriesPack, recallIndex)
                                        ListeningPlayback.start(ctx, nextSeriesPack, recallIndex, oneShot = true)
                                    } else {
                                        recallIndex = i.coerceIn(pack.lines.indices)
                                        store.setRecallPosition(pack, recallIndex)
                                        ListeningPlayback.start(ctx, pack, recallIndex, oneShot = true)
                                    }
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
                                        if (target != null) playLine(target)
                                        else if (!wrongOnly && nextSeriesPack != null) playLine(pack.lines.size)
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
                                    onOpenRaw = { lineId, prompt ->
                                        rawEditor = ListeningReflectionRequest(pack, lineId, prompt)
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
                    item {
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("内容与项目管理", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall)
                            TextButton(enabled = connected && !busy, onClick = {
                                busy = true
                                scope.launch {
                                    try { message = github.refreshContentNow() }
                                    finally { busy = false }
                                }
                            }) { Text("同步目录") }
                            if (sitcomMode) {
                                TextButton(enabled = connected && !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        try { message = github.downloadAllSeries() }
                                        finally { busy = false }
                                    }
                                }) { Text("补齐全部字幕") }
                            }
                        }
                    }
                    item {
                        // Five real counters stay visible without a multi-line diagnostic log.
                        val metrics = listOf(
                            (if (sitcomMode) "分段" else "故事") to orderedPacks.size,
                            "待传" to state.pending,
                            "词汇" to state.saved,
                            "待复习" to state.due,
                            "待巩固" to state.unestablished,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            metrics.forEachIndexed { i, (name, count) ->
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(1.dp),
                                ) {
                                    Text(
                                        count.toString(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        if (status.contains("失败") || status.startsWith("正在") || status.startsWith("同步完成") || status.startsWith("未配置")) {
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                    if (pack != null) {
                        item {
                            val metrics = store.learningMetrics()
                            val dueCount = store.dueRetests().size
                            val rate = metrics.firstPassRate?.let { it.toString() + "%" } ?: "数据不足"
                            val retention = metrics.delayedRate?.let { it.toString() + "%" } ?: "待复查"
                            val suggested = metrics.nextLevel(pack.level)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "真实学习：首次独立正确 $rate · 延迟保持 $retention · Raw " +
                                        metrics.freeExpressions,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (dueCount > 0) {
                                        Button(onClick = { delayedReviewOpen = true }) {
                                            Text("隔天复查 $dueCount 句")
                                        }
                                    }
                                    Text(
                                        if (metrics.firstIndependentChecks >= 5) {
                                            "建议难度 $suggested · 基于首次正确率"
                                        } else {
                                            "还需 " + (5 - metrics.firstIndependentChecks).coerceAtLeast(0) +
                                                " 次独立答题评估难度"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (metrics.firstIndependentChecks >= 5) {
                                        val suggestedPack = orderedPacks.firstOrNull {
                                            it.level.equals(suggested, ignoreCase = true)
                                        }
                                        if (suggestedPack != null && suggestedPack.id != pack.id) {
                                            TextButton(onClick = {
                                                currentId = suggestedPack.id
                                                recallMode = true
                                                recallIndex = store.recallPosition(suggestedPack)
                                                store.markPackUsed(suggestedPack)
                                                ListeningPlayback.start(ctx, suggestedPack, recallIndex, oneShot = true)
                                            }) { Text("练推荐") }
                                        }
                                    }
                                }
                                val personal = orderedPacks.filter { it.kind == "personal_drill" }
                                if (personal.isNotEmpty()) {
                                    val target = personal.firstOrNull { it.id != pack.id } ?: personal.first()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            "🎯 个人错点专项 " + personal.size + " 份",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = {
                                            currentId = target.id
                                            recallMode = true
                                            recallIndex = store.recallPosition(target)
                                            store.markPackUsed(target)
                                            ListeningPlayback.start(ctx, target, recallIndex, oneShot = true)
                                        }) { Text("练习 " + target.level) }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        if (sitcomMode) {
                            Text(
                                "《破产姐妹》专辑 · 按季选集，字幕分段复述",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                (1..6).forEach { number ->
                                    FilterChip(
                                        selected = sitcomSeason == number,
                                        onClick = {
                                            if (sitcomSeason != number) {
                                                ListeningPlayback.stop(ctx)
                                                sitcomSeason = number
                                                currentId = null
                                            }
                                        },
                                        label = { Text("第" + number + "季") },
                                    )
                                }
                            }
                            Text(
                                "第" + sitcomSeason + "季 · 目录 " + seriesEpisodes.size +
                                    " 集 / 已缓存 " + orderedPacks.size + " 段",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (sitcomMode && seriesEpisodes.isEmpty()) {
                        item {
                            Text(
                                "本季尚无字幕目录。进入专题时会自动同步全部字幕；需要重试可使用下方「补齐全部字幕」。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (sitcomMode && seriesEpisodes.isNotEmpty()) {
                        items(seriesEpisodes, key = { "2bg-episode-" + it.season + "-" + it.episode }) { episode ->
                            val loadedParts = episode.parts.mapNotNull { part ->
                                cachedSeriesPacks[part.id]
                            }
                            val downloaded = loadedParts.size == episode.parts.size
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(episode.title, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            episode.parts.size.toString() + " 段 · " +
                                                (if (downloaded) "已离线缓存" else "待批量下载"),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    OutlinedButton(enabled = downloaded && !busy, onClick = {
                                        val target = loadedParts.firstOrNull()
                                        if (target != null) {
                                            currentId = target.id
                                            recallMode = true
                                            recallIndex = store.recallPosition(target)
                                            store.markPackUsed(target)
                                            ListeningPlayback.start(ctx, target, recallIndex, oneShot = true)
                                        }
                                    }) {
                                        Text(if (downloaded) "开始复述" else "自动下载中")
                                    }
                                }
                            }
                        }
                    }
                    if (orderedPacks.isEmpty()) {
                        item {
                            Text(
                                if (sitcomMode) "字幕尚未缓存；进入专题将自动下载全部六季。" else
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
                            val selected = p.id == pack?.id
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
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
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor =
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                border = BorderStroke(
                                    if (selected) 1.5.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Text(
                                            (if (selected) "● " else "") + p.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                        )
                                        Text(
                                            p.level,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Text(
                                        stringResource(
                                            R.string.listening_recall_pack_summary,
                                            completed,
                                            p.lines.size,
                                            errors,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        statuses.forEach { result ->
                                            val color = when {
                                                result.softPassed -> androidx.compose.ui.graphics.Color(0xFFEAC34A)
                                                result.passed -> MaterialTheme.colorScheme.tertiary
                                                result.failed -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.surfaceVariant
                                            }
                                            Box(
                                                Modifier.weight(1f).height(5.dp)
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
                    item { Spacer(Modifier.padding(bottom = 20.dp)) }
                }
            }
        }
    }
    val openRaw = rawEditor
    if (openRaw != null) {
        ListeningRawReflectionDialog(
            request = openRaw,
            onSave = { request, text ->
                if (store.addRawReflection(request.pack, request.lineId, request.prompt, text)) {
                    message = "Raw 已保存在手机；点击审核同步后才会上传 GitHub"
                }
                rawEditor = null
            },
            onDismiss = { rawEditor = null },
        )
    }
    if (delayedReviewOpen) {
        ListeningDelayedReviewDialog(
            due = store.dueRetests(),
            onPlay = { item ->
                ListeningPlayback.start(ctx, item.pack, item.lineIndex, oneShot = true)
            },
            onCheck = { item, answer ->
                val passed = store.checkDelayedRecall(item.pack, item.pack.lines[item.lineIndex], answer)
                message = if (passed) "延迟回忆正确" else "延迟回忆未通过；已安排复查"
                passed
            },
            onClose = { delayedReviewOpen = false },
        )
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
            statusMessage = message,
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
