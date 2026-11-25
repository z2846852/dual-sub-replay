package com.kienhoang.dualsubreplay.listening

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.kienhoang.dualsubreplay.data.KnownWordArchive
import com.kienhoang.dualsubreplay.data.KnownWordsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** PAT ciphertext never enters Android backup: its Keystore key is device-bound. */
internal class ListeningVault(
    context: Context,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "english-github-pat.bin"))
    private val alias = "dual-sub-english-keystore-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    @Synchronized fun isConfigured(): Boolean = file.baseFile.exists()

    @Synchronized fun save(token: String) {
        require(token.length in 20..300)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()) // Never supply the IV yourself on Android Keystore.
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val iv = requireNotNull(cipher.iv)
        check(iv.size == 12)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(iv)
            stream.write(ciphertext)
            file.finishWrite(stream)
        } catch (ex: Exception) {
            if (stream != null) file.failWrite(stream)
            throw ex
        }
    }

    @Synchronized fun read(): String? {
        if (!file.baseFile.exists()) return null
        val all = file.openRead().use { it.readBytes() }
        check(all.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }

    @Synchronized fun clear() {
        file.delete()
    }
}

/** Network access is allowed only from explicit connection/sync or the first daily launch. */
internal class ListeningGitHub(
    context: Context,
    private val store: ListeningStore,
) {
    private val vault = ListeningVault(context)
    private val application = context.applicationContext
    private val mutex = Mutex()
    private val preferences = context.getSharedPreferences("offline_listening_sync", Context.MODE_PRIVATE)
    private val base = "https://api.github.com/repos/z2846852/english"
    private val _status = MutableStateFlow("离线就绪")
    val status = _status.asStateFlow()
    val connected get() = vault.isConfigured()

    suspend fun connect(raw: String): String =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val token = raw.trim()
                require(token.length in 20..300) { "请输入有效的 fine-grained PAT" }
                verify(token)
                vault.save(token)
                val msg = "PAT 已安全保存，点击同步即可下载新对话"
                _status.value = msg
                msg
            }
        }

    fun forgetToken() {
        vault.clear()
        _status.value = "PAT 已清除，本地单词和对话仍保留"
    }

    suspend fun syncNow(
        approvedEventIds: Set<String> = emptySet(),
        approvedDeletionIds: Set<String> = emptySet(),
    ): String =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val token = vault.read() ?: return@withContext "未配置 PAT，请先连接 GitHub".also { _status.value = it }
                    _status.value =
                        if (approvedEventIds.isNotEmpty() || approvedDeletionIds.isNotEmpty()) {
                            "正在应用审核通过的上传与删除…"
                        } else {
                            "正在获取学习内容…"
                        }
                    verify(token)
                    val deleted = if (approvedDeletionIds.isNotEmpty()) pushDeletions(token, approvedDeletionIds) else 0
                    val sent = if (approvedEventIds.isNotEmpty()) pushPending(token, approvedEventIds) else 0
                    _status.value = if (approvedEventIds.isNotEmpty() || approvedDeletionIds.isNotEmpty()) {
                        "已上传 " + sent + " 条学习事件，正在备份词汇记忆…"
                    } else {
                        "自动同步不上传未经审核的逐句记录，正在备份词汇记忆…"
                    }
                    restoreWordMemoryOnce(token)
                    pushWordMemory(token)
                    _status.value = "词汇记忆已备份，正在下载对话…"
                    val packs = pullPacks(token)
                    val sitcomEpisodes = pullSeriesEpisodes(token)
                    store.putPacks(packs)
                    store.putSeriesEpisodes(sitcomEpisodes)
                    // Sync completion immediately queues cached natural audio for every distinct sentence.
                    // The cache worker is resumable and rate-limited; GitHub sync returns without waiting.
                    ListeningVoicePrefetch.schedule(application, packs)
                    val msg = "同步完成：上传 " + sent + " 条事件、云端删除 " + deleted +
                        " 条、备份词汇记忆，获取 " + packs.size + " 个普通对话包、" +
                        sitcomEpisodes.size + " 集剧集索引"
                    _status.value = msg
                    msg
                } catch (e: Exception) {
                    val msg = "同步失败：" + safeError(e) + "。本地记录已保留，可手动重试"
                    _status.value = msg
                    msg
                }
            }
        }

    /**
     * Read-only, user-triggered course refresh. This must remain independent of word-memory
     * backups and reviewed event uploads: a failed write must never block lesson downloads.
     */
    suspend fun refreshContentNow(): String =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val token = vault.read()
                    if (token == null) {
                        val message = "未配置 PAT，请在设置中连接 GitHub"
                        _status.value = message
                        return@withContext message
                    }
                    _status.value = "正在从 English/main 获取课程…"
                    verify(token)
                    val existingIds = store.state.value.packs.mapTo(HashSet()) { it.id }
                    val packs = pullPacks(token)
                    require(packs.isNotEmpty()) { "云端课程清单为空，保留原有本地内容" }
                    val sitcomEpisodes = pullSeriesEpisodes(token)
                    store.putPacks(packs)
                    store.putSeriesEpisodes(sitcomEpisodes)
                    ListeningVoicePrefetch.schedule(application, packs)
                    val newlyAdded = packs.count { it.id !in existingIds }
                    val total = store.state.value.packs.size
                    val message = "同步完成：云端 " + packs.size +
                        " 个普通课程，新增 " + newlyAdded + " 个，本地共 " + total +
                        " 个；剧集目录 " + sitcomEpisodes.size + " 集"
                    _status.value = message
                    message
                } catch (error: Exception) {
                    val message = "同步失败：" + safeError(error) + "。原有本地课程不受影响"
                    _status.value = message
                    message
                }
            }
        }

    suspend fun autoSyncOnceDaily() {
        if (!vault.isConfigured()) return
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        synchronized(preferences) {
            if (preferences.getString("last_auto_attempt_day", null) == today) return
            preferences.edit().putString("last_auto_attempt_day", today).commit()
        }
        syncNow()
    }

    private fun verify(token: String) {
        val repo = JSONObject(request("GET", base, token))
        check(repo.optString("full_name").equals("z2846852/english", ignoreCase = true)) { "PAT 指向非预期仓库" }
    }

    private fun pushPending(token: String, approvedEventIds: Set<String>): Int {
        val pending = store.pendingEvents().filter { ListeningSyncPolicy.approved(it.getString("id"), approvedEventIds) }
        val raw = pending.filter { it.optString("type") == "raw_note" }
        for (note in raw) {
            pushRawNote(token, note)
            store.markUploaded(setOf(note.getString("id")))
        }
        val grouped =
            pending.filter { it.optString("type") != "raw_note" }.groupBy { e ->
                val month = e.getString("created_at").substring(0, 7)
                check(month.matches(Regex("\\d{4}-\\d{2}")))
                val stream = ListeningSyncPolicy.remoteCollection(e.optString("type"))
                stream to month
            }
        for ((key, events) in grouped) {
            val (stream, month) = key
            val device = store.state.value.deviceId
            check(device.matches(Regex("[0-9a-fA-F-]{36}")))
            val path = "data/" + stream + "/" + device + "/" + month + ".jsonl"
            var successful = false
            for (attempt in 1..4) {
                val existing = getFile(token, path)
                val union = LinkedHashMap<String, JSONObject>()
                existing?.first?.lineSequence()?.filter { it.isNotBlank() }?.forEach { raw ->
                    val e = JSONObject(raw)
                    union[e.getString("id")] = e
                }
                events.forEach { union[it.getString("id")] = it }
                val content =
                    union.values
                        .sortedWith(compareBy({ it.optString("created_at") }, { it.optString("id") }))
                        .joinToString(separator = "\n", postfix = "\n") { it.toString() }
                val body =
                    JSONObject()
                        .put("message", "Sync listening events from " + device + " (" + month + ")")
                        .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                        .put("branch", "main")
                existing?.second?.let { body.put("sha", it) }
                try {
                    request("PUT", base + "/contents/" + path, token, body.toString())
                    successful = true
                    break
                } catch (e: GitHubHttpException) {
                    if (e.status !in listOf(409, 412, 422) || attempt == 4) throw e
                }
            }
            check(successful)
            store.markUploaded(events.map { it.getString("id") }.toSet())
        }
        return pending.size
    }



    /** Mirror MyWork Raw: one unprocessed user note per stable UUID, no rewriting its words. */
    private fun pushRawNote(token: String, event: JSONObject) {
        val id = event.getString("id")
        val day = event.getString("created_at").take(10)
        require(id.matches(Regex("[0-9a-fA-F-]{36}")) &&
            day.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Raw ID / 日期无效" }
        val path = ListeningSyncPolicy.rawPath(day, id)
        val original = event.getString("text")
        require(original.isNotBlank() && original.length <= 5000)
        val existing = getFile(token, path)
        if (existing != null) {
            val saved = JSONObject(existing.first)
            check(saved.optString("id") == id && saved.optString("text") == original) {
                "云端 Raw 同 ID 但正文不同，拒绝覆盖"
            }
            return
        }
        val body = JSONObject()
            .put("message", "raw: preserve unprocessed English reflection " + id.take(8))
            .put("branch", "main")
            .put("content", Base64.encodeToString(
                event.toString(2).toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
            ))
        try {
            request("PUT", base + "/contents/" + path, token, body.toString())
        } catch (error: GitHubHttpException) {
            if (error.status !in listOf(409, 422)) throw error
            val saved = getFile(token, path)?.first?.let(::JSONObject) ?: throw error
            if (saved.optString("id") != id || saved.optString("text") != original) throw error
        }
    }

    private fun removeRawNote(token: String, item: JSONObject) {
        val id = item.getString("id")
        val day = item.getString("created_at").take(10)
        require(id.matches(Regex("[0-9a-fA-F-]{36}")) &&
            day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        val path = ListeningSyncPolicy.rawPath(day, id)
        for (attempt in 1..4) {
            val current = getFile(token, path) ?: return
            check(JSONObject(current.first).optString("id") == id) {
                "云端 Raw ID 不匹配，拒绝删除"
            }
            val payload = JSONObject()
                .put("message", "Delete selected raw reflection " + id.take(8))
                .put("branch", "main")
                .put("sha", current.second)
            try {
                request("DELETE", base + "/contents/" + path, token, payload.toString())
                return
            } catch (error: GitHubHttpException) {
                if (error.status !in listOf(409, 412, 422) || attempt == 4) throw error
            }
        }
    }

    /**
     * One local record maps to one ID in english/main's monthly JSONL.
     * Remove that ID directly using GitHub Contents API with its SHA precondition.
     * When the last record is removed, delete the empty JSONL file itself.
     * Retry conflicts but never create cloud tombstones or audit collections.
     */
    private fun pushDeletions(token: String, approvedIds: Set<String>): Int {
        val approved = store.pendingDeletions().filter { it.optString("id") in approvedIds }
        val raw = approved.filter { it.optString("type") == "raw_note" }
        for (item in raw) {
            removeRawNote(token, item)
            store.markDeletionsSynced(setOf(item.getString("id")))
        }
        val grouped = approved.filter { it.optString("type") != "raw_note" }
            .groupBy { item ->
                val device = item.optString("device_id")
                val month = ListeningSyncPolicy.eventMonth(item.optString("created_at"))
                require(ListeningSyncPolicy.validDeviceId(device) && month != null) {
                    "记录缺少有效设备或日期，无法定位 GitHub 文件"
                }
                Triple(device, month, ListeningSyncPolicy.remoteCollection(item.optString("type")))
            }
        var completed = raw.size
        for ((key, records) in grouped) {
            val (device, month, collection) = key
            val ids = records.map { it.getString("id") }.toSet()
            val path = "data/$collection/$device/$month.jsonl"
            removeRemoteIds(token, path, ids)
            store.markDeletionsSynced(ids)
            completed += ids.size
        }
        return completed
    }

    /** No new GitHub files: edit the current JSONL or delete it when empty. */
    private fun removeRemoteIds(token: String, path: String, ids: Set<String>) {
        for (attempt in 1..4) {
            val existing = getFile(token, path) ?: return
            val records = existing.first.lineSequence()
                .filter { it.isNotBlank() }
                .map { JSONObject(it) }
                .filter { it.optString("id") !in ids }
                .toList()
            val originalCount = existing.first.lineSequence().count { it.isNotBlank() }
            if (originalCount == records.size) return
            val body = JSONObject()
                .put("message", "Remove selected practice record(s) from $path")
                .put("branch", "main")
                .put("sha", existing.second)
            val method = if (records.isEmpty()) "DELETE" else "PUT"
            if (records.isNotEmpty()) {
                val content = records.joinToString(separator = "\n", postfix = "\n") { it.toString() }
                body.put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            }
            try {
                request(method, base + "/contents/" + path, token, body.toString())
                return
            } catch (error: GitHubHttpException) {
                if (error.status !in listOf(409, 412, 422) || attempt == 4) throw error
            }
        }
    }

    /** A delete action does not trigger download, word-memory backup, or an ordinary upload. */
    suspend fun deletePendingHistoryNow(approvedIds: Set<String>): String =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    if (approvedIds.isEmpty()) return@withContext "没有待删除的云端记录"
                    val token = vault.read() ?: return@withContext "未连接 GitHub，删除将在下次同步时重试"
                    _status.value = "正在从 GitHub 删除所选记录…"
                    verify(token)
                    val count = pushDeletions(token, approvedIds)
                    val message = "已从 GitHub 当前文件删除 $count 条记录"
                    _status.value = message
                    message
                } catch (e: Exception) {
                    val message = "GitHub 删除失败：" + safeError(e) + "；本地已删除，待重试"
                    _status.value = message
                    message
                }
            }
        }

    /** Load existing GitHub history into the on-device review panel. */
    suspend fun refreshCloudReviewHistory(): String =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val token = vault.read() ?: return@withContext "未连接 GitHub"
                    _status.value = "正在读取 GitHub 历史记录…"
                    val attempts = ArrayList<JSONObject>()
                    for (stream in listOf("events", "insights")) {
                        val devices = listChildren(token, "data/$stream")
                            .filter { it.first == "dir" && ListeningSyncPolicy.validDeviceId(it.second) }
                            .take(20)
                        for ((_, device) in devices) {
                            val months = listChildren(token, "data/$stream/$device")
                                .filter { it.first == "file" && it.second.matches(Regex("\\d{4}-\\d{2}\\.jsonl")) }
                                .take(48)
                            for ((_, filename) in months) {
                                val raw = getFile(token, "data/$stream/$device/$filename")?.first ?: continue
                                for (line in raw.lineSequence().filter { it.isNotBlank() }) {
                                    val item = runCatching { JSONObject(line) }.getOrNull() ?: continue
                                    if (
                                        item.optString("type") in setOf("recall_checked", "recall_insight") &&
                                        item.optString("device_id") == device &&
                                        item.optString("id").isNotBlank() &&
                                        attempts.size < 15_000
                                    ) attempts.add(item)
                                }
                            }
                        }
                    }
                    // Raw uses MyWork's one-UUID-per-file layout, not monthly JSONL.
                    // Bound downloads to recent days to avoid excessive API calls.
                    val rawDays = listChildren(token, "data/raw_inbox")
                        .filter { it.first == "dir" &&
                            it.second.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                        .sortedByDescending { it.second }.take(45)
                    for ((_, day) in rawDays) {
                        val notes = listChildren(token, "data/raw_inbox/$day")
                            .filter { it.first == "file" &&
                                it.second.matches(Regex("[0-9a-fA-F-]{36}\\.json")) }
                            .take(50)
                        for ((_, filename) in notes) {
                            val raw = getFile(token, "data/raw_inbox/$day/$filename")?.first ?: continue
                            val item = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                            if (item.optString("type") == "raw_note" &&
                                item.optString("id") + ".json" == filename &&
                                attempts.size < 15_000
                            ) attempts.add(item)
                        }
                    }
                    store.importCloudReviewHistory(attempts)
                    val msg = "已加载 " + attempts.size + " 条云端历史，可逐条审查、删除或收藏"
                    _status.value = msg
                    msg
                } catch (e: Exception) {
                    val msg = "读取历史失败：" + safeError(e)
                    _status.value = msg
                    msg
                }
            }
        }

    private fun listChildren(token: String, path: String): List<Pair<String, String>> {
        val raw =
            try {
                request("GET", "$base/contents/$path?ref=main", token)
            } catch (e: GitHubHttpException) {
                if (e.status == 404) return emptyList()
                throw e
            }
        val entries = JSONArray(raw)
        return (0 until minOf(entries.length(), 1000)).mapNotNull { i ->
            val item = entries.optJSONObject(i) ?: return@mapNotNull null
            val kind = item.optString("type")
            val name = item.optString("name")
            if (kind !in setOf("dir", "file") || name.isBlank()) null else kind to name
        }
    }

    /**
     * Store a device-specific checkpoint. One checkpoint per device keeps concurrent phones from
     * overwriting each other's data. This is a backup, not a claim of precise cross-device merging.
     */
    private suspend fun pushWordMemory(token: String) {
        val archive = KnownWordsRepository.get(application).exportArchive()
        val device = store.state.value.deviceId
        require(device.matches(Regex("[0-9a-fA-F-]{36}")))
        val path = "data/word-memory/$device.json"
        val content = wordMemoryJson(device, archive).toString()
        require(content.toByteArray(Charsets.UTF_8).size <= 650_000) { "词汇记忆文件过大，未上传" }
        for (attempt in 1..4) {
            val existing = getFile(token, path)
            val body = JSONObject()
                .put("message", "Back up vocabulary observations from $device")
                .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                .put("branch", "main")
            existing?.second?.let { body.put("sha", it) }
            try {
                request("PUT", base + "/contents/" + path, token, body.toString())
                return
            } catch (e: GitHubHttpException) {
                if (e.status !in listOf(409, 412, 422) || attempt == 4) throw e
            }
        }
    }

    /**
     * On a fresh installation only, recover the newest cloud checkpoint if the word store is empty.
     * Never merge over existing local corrections or replace current learning with old counters.
     */
    private suspend fun restoreWordMemoryOnce(token: String) {
        if (preferences.getBoolean("word_memory_restore_checked_v1", false)) return
        val remote = newestWordMemory(token)
        if (remote != null) KnownWordsRepository.get(application).restoreArchiveIfEmpty(remote)
        preferences.edit().putBoolean("word_memory_restore_checked_v1", true).commit()
    }

    private fun newestWordMemory(token: String): KnownWordArchive? {
        val entries = try {
            JSONArray(request("GET", base + "/contents/data/word-memory?ref=main", token))
        } catch (e: GitHubHttpException) {
            if (e.status == 404) return null else throw e
        }
        // A normal user has a few devices. Bound remote reads rather than downloading an
        // unbounded directory of user-controlled files.
        var newest: Instant? = null
        var archive: KnownWordArchive? = null
        for (i in 0 until minOf(entries.length(), 20)) {
            val name = entries.optJSONObject(i)?.optString("name").orEmpty()
            if (!name.matches(Regex("[0-9a-fA-F-]{36}\\.json"))) continue
            val text = getFile(token, "data/word-memory/$name")?.first ?: continue
            val payload = runCatching { JSONObject(text) }.getOrNull() ?: continue
            if (payload.optInt("schema_version") != 1) continue
            val date = runCatching { Instant.parse(payload.optString("updated_at")) }.getOrNull() ?: continue
            if (newest?.let { !date.isAfter(it) } == true) continue
            val parsed = runCatching { parseWordMemory(payload) }.getOrNull() ?: continue
            newest = date
            archive = parsed
        }
        return archive
    }

    private fun wordMemoryJson(device: String, archive: KnownWordArchive): JSONObject =
        JSONObject()
            .put("schema_version", 1)
            .put("device_id", device)
            .put("updated_at", Instant.now().toString())
            .put(
                "known",
                JSONObject().apply {
                    archive.known.toSortedMap().forEach { (language, words) ->
                        put(language, JSONArray(words.sorted()))
                    }
                },
            ).put(
                "seen",
                JSONObject().apply {
                    archive.seen.toSortedMap().forEach { (language, counts) ->
                        put(language, JSONObject().apply {
                            counts.toSortedMap().forEach { (word, count) -> put(word, count) }
                        })
                    }
                },
            )

    private fun parseWordMemory(payload: JSONObject): KnownWordArchive {
        val knownJson = payload.optJSONObject("known") ?: JSONObject()
        val seenJson = payload.optJSONObject("seen") ?: JSONObject()
        val known = knownJson.keys().asSequence().associateWith { language ->
            val values = knownJson.optJSONArray(language) ?: JSONArray()
            require(values.length() <= 100_000)
            (0 until values.length()).map { values.getString(it) }.toSet()
        }
        val seen = seenJson.keys().asSequence().associateWith { language ->
            val values = seenJson.optJSONObject(language) ?: JSONObject()
            require(values.length() <= 100_000)
            values.keys().asSequence().associateWith { values.getInt(it) }
        }
        return KnownWordArchive(known, seen)
    }

    /** Read only metadata for all seasons; never fetch every subtitle on the normal sync path. */
    private fun pullSeriesEpisodes(token: String): List<ListeningSeriesEpisode> {
        val rootPath = "content/series/2-broke-girls/manifest.json"
        val text = getFile(token, rootPath)?.first ?: return emptyList()
        val root = JSONObject(text)
        require(root.optInt("schema_version") == 1 &&
            root.optString("series_id") == "2-broke-girls") { "无效剧集专题索引" }
        val seasons = root.getJSONArray("seasons")
        require(seasons.length() <= 6) { "季数超出允许范围" }
        val all = ArrayList<ListeningSeriesEpisode>()
        for (i in 0 until seasons.length()) {
            val entry = seasons.getJSONObject(i)
            val season = entry.getInt("season")
            val path = entry.getString("path")
            require(season in 1..6 && path == "content/series/2-broke-girls/s%02d/manifest.json".format(season))
            val seasonText = getFile(token, path)?.first ?: error("Missing season manifest: $path")
            val manifest = JSONObject(seasonText)
            require(manifest.optInt("schema_version") == 1 && manifest.optInt("season") == season)
            val episodes = manifest.getJSONArray("episodes")
            require(episodes.length() <= 40)
            for (index in 0 until episodes.length()) {
                all += ListeningSeriesEpisode.fromJson(episodes.getJSONObject(index), season)
            }
        }
        require(all.map { it.season to it.episode }.distinct().size == all.size)
        return all.sortedWith(compareBy<ListeningSeriesEpisode> { it.season }.thenBy { it.episode })
    }


    /**
     * One-click, resumable subtitle-only cache. A GitHub archive costs one request instead of
     * hundreds of Contents API calls. Existing packs are skipped, and no 60k-line TTS job is queued.
     */
    suspend fun downloadAllSeries(): String =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val token = vault.read()
                        ?: return@withContext "请先连接 English GitHub PAT"
                    var episodes = store.state.value.seriesEpisodes
                    if (episodes.isEmpty()) {
                        _status.value = "正在获取六季目录…"
                        verify(token)
                        episodes = pullSeriesEpisodes(token)
                        store.putSeriesEpisodes(episodes)
                    }
                    val all = episodes.flatMap { it.parts }
                    if (all.isEmpty()) {
                        val message = "专题目录暂无可下载的字幕"
                        _status.value = message
                        return@withContext message
                    }
                    val cached = store.state.value.packs
                        .filter { it.kind == SITCOM_PACK_KIND }
                        .mapTo(HashSet()) { it.id }
                    val missing = all.filter { it.id !in cached }
                    if (missing.isEmpty()) {
                        val message = "全部字幕已离线缓存：${episodes.size} 集 / ${all.size} 段"
                        _status.value = message
                        return@withContext message
                    }
                    require(missing.map { it.path }.distinct().size == missing.size)
                    _status.value = "正在一次性下载全部剧集字幕：还缺 ${missing.size} 段…"
                    val expected = missing.associate { it.path to it.id }
                    val batch = ArrayList<ListeningPack>(64)
                    val loaded = withSeriesArchive(token) { input ->
                        ListeningSeriesArchive.readMissing(input, expected) { count, pack ->
                            batch.add(pack)
                            if (batch.size >= 64) {
                                // Save validated sections incrementally. An interrupted download
                                // can retry without discarding previously stored subtitles.
                                store.putPacks(batch)
                                batch.clear()
                            }
                            if (count == 1 || count % 20 == 0 || count == missing.size) {
                                _status.value = "正在缓存字幕 ${count}/${missing.size} 段"
                            }
                        }
                    }
                    if (batch.isNotEmpty()) store.putPacks(batch)
                    val message = "全部字幕已下载：${episodes.size} 集、${all.size} 段；本次新增 ${loaded.size} 段"
                    _status.value = message
                    message
                } catch (error: Exception) {
                    val message = "字幕批量下载失败：" + safeError(error) + "。已缓存的句子不会丢失，重新进入专题可重试"
                    _status.value = message
                    message
                }
            }
        }

    /** GitHub responds with a short-lived private codeload link. Do not forward the PAT to it. */
    private fun <T> withSeriesArchive(token: String, consume: (InputStream) -> T): T {
        val api = URL(base + "/zipball/main").openConnection() as HttpURLConnection
        api.instanceFollowRedirects = false
        api.requestMethod = "GET"
        api.connectTimeout = 20_000
        api.readTimeout = 60_000
        api.setRequestProperty("Authorization", "Bearer " + token)
        api.setRequestProperty("Accept", "application/vnd.github+json")
        api.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        try {
            val code = api.responseCode
            if (code in 200..299) return api.inputStream.use(consume)
            if (code !in 301..303 && code != 307 && code != 308) {
                throw GitHubHttpException(code)
            }
            val location = api.getHeaderField("Location")
                ?: error("GitHub ZIP 下载未返回跳转地址")
            val target = URL(location)
            require(target.protocol == "https" && target.host == "codeload.github.com") {
                "拒绝不可信的字幕归档跳转域名"
            }
            val download = target.openConnection() as HttpURLConnection
            download.requestMethod = "GET"
            download.connectTimeout = 20_000
            download.readTimeout = 90_000
            try {
                val downloadedCode = download.responseCode
                if (downloadedCode !in 200..299) throw GitHubHttpException(downloadedCode)
                return download.inputStream.use(consume)
            } finally {
                download.disconnect()
            }
        } finally {
            api.disconnect()
        }
    }

    /** Download one episode's sections on demand, preserving existing recalled lines offline. */
    suspend fun loadSeriesEpisode(episode: ListeningSeriesEpisode): String =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val token = vault.read() ?: return@withContext "请先连接 English GitHub PAT"
                    _status.value = "正在获取 S%02dE%02d 的字幕…".format(episode.season, episode.episode)
                    verify(token)
                    val parts = episode.parts.map { part ->
                        val payload = getFile(token, part.path)?.first
                            ?: error("Missing subtitle part: " + part.path)
                        ListeningPack.fromJson(JSONObject(payload)).also { pack ->
                            require(pack.id == part.id && pack.kind == SITCOM_PACK_KIND)
                        }
                    }
                    store.putPacks(parts)
                    ListeningVoicePrefetch.schedule(application, parts)
                    val message = "已载入 S%02dE%02d，共 %d 段、%d 句".format(
                        episode.season, episode.episode, parts.size, parts.sumOf { it.lines.size },
                    )
                    _status.value = message
                    message
                } catch (error: Exception) {
                    val message = "剧集加载失败：" + safeError(error) + "。已有离线内容保留"
                    _status.value = message
                    message
                }
            }
        }

    private fun pullPacks(token: String): List<ListeningPack> {
        val manifestText = getFile(token, "content/manifest.json")?.first ?: return emptyList()
        val manifest = JSONObject(manifestText)
        check(manifest.optInt("schema_version") == 1) { "不支持此对话包清单版本" }
        val items = manifest.getJSONArray("packs")
        require(items.length() <= 100) { "对话包数量超过上限" }
        return (0 until items.length()).map { index ->
            val p = items.getJSONObject(index)
            val path = p.getString("path")
            require(path.matches(Regex("content/(dialogues|drills)/[a-zA-Z0-9_-]+\\.json"))) { "无效对话路径" }
            val content = getFile(token, path)?.first ?: error("Missing dialogue: " + path)
            ListeningPack.fromJson(JSONObject(content)).also {
                require(it.id == p.getString("id")) { "对话包 ID 与清单不匹配" }
            }
        }
    }

    private fun getFile(
        token: String,
        path: String,
    ): Pair<String, String>? {
        val result =
            try {
                request("GET", base + "/contents/" + path + "?ref=main", token)
            } catch (
                e: GitHubHttpException,
            ) {
                if (e.status == 404) return null else throw e
            }
        val json = JSONObject(result)
        require(json.getString("encoding") == "base64")
        val bytes = Base64.decode(json.getString("content"), Base64.DEFAULT)
        require(bytes.size <= 700_000)
        return String(bytes, Charsets.UTF_8) to json.getString("sha")
    }

    private fun request(
        method: String,
        address: String,
        token: String,
        body: String? = null,
    ): String {
        require(address == base || address.startsWith(base + "/"))
        val conn = URL(address).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("Authorization", "Bearer " + token)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val buffer = ByteArrayOutputStream()
            stream?.use { input ->
                val bytes = ByteArray(8192)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    check(buffer.size() + n < 1_500_000) { "GitHub 响应超出大小限制" }
                    buffer.write(bytes, 0, n)
                }
            }
            if (code !in 200..299) throw GitHubHttpException(code)
            return buffer.toString(Charsets.UTF_8.name())
        } finally {
            conn.disconnect()
        }
    }

    private fun safeError(ex: Exception): String =
        when (ex) {
            is GitHubHttpException ->
                when (ex.status) {
                    401 -> "PAT 无效或已失效（401）"
                    403 -> "GitHub 拒绝访问或限流（403）"
                    404 -> "仓库或文件不可访问（404），请检查 PAT 仓库授权"
                    else -> "GitHub HTTP " + ex.status
                }
            else -> ex.message?.take(100) ?: ex.javaClass.simpleName
        }

    private class GitHubHttpException(
        val status: Int,
    ) : RuntimeException("GitHub HTTP " + status)
}
