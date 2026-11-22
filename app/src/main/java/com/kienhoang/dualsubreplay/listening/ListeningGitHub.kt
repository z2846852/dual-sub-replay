package com.kienhoang.dualsubreplay.listening

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
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

    suspend fun syncNow(): String =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val token = vault.read() ?: return@withContext "未配置 PAT，请先连接 GitHub".also { _status.value = it }
                    _status.value = "正在上传本地学习记录…"
                    verify(token)
                    val sent = pushPending(token)
                    _status.value = "已上传 " + sent + " 条记录，正在下载对话…"
                    val packs = pullPacks(token)
                    store.putPacks(packs)
                    // Sync completion immediately queues cached natural audio for every distinct sentence.
                    // The cache worker is resumable and rate-limited; GitHub sync returns without waiting.
                    ListeningVoicePrefetch.schedule(application, store.state.value.packs)
                    val msg = "同步完成：上传 " + sent + " 条学习事件，获取 " + packs.size + " 个对话包"
                    _status.value = msg
                    msg
                } catch (e: Exception) {
                    val msg = "同步失败：" + safeError(e) + "。本地记录已保留，可手动重试"
                    _status.value = msg
                    msg
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

    private fun pushPending(token: String): Int {
        val pending = store.pendingEvents()
        val grouped =
            pending.groupBy { e ->
                val month = e.getString("created_at").substring(0, 7)
                check(month.matches(Regex("\\d{4}-\\d{2}")))
                month
            }
        for ((month, events) in grouped) {
            val device = store.state.value.deviceId
            check(device.matches(Regex("[0-9a-fA-F-]{36}")))
            val path = "data/events/" + device + "/" + month + ".jsonl"
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
