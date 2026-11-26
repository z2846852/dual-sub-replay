package com.kienhoang.dualsubreplay.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.kienhoang.dualsubreplay.assistant.AI_KEYS_PREFERENCES
import com.kienhoang.dualsubreplay.assistant.AI_SETTINGS_PREFERENCES
import com.kienhoang.dualsubreplay.assistant.AiHttpTransport
import com.kienhoang.dualsubreplay.assistant.AiKeyStore
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.KeystoreSecretCipher
import com.kienhoang.dualsubreplay.assistant.SharedPreferencesSecretStorage
import com.kienhoang.dualsubreplay.assistant.aiTranslationModel
import com.kienhoang.dualsubreplay.assistant.readAiAssistantSettings
import com.kienhoang.dualsubreplay.translation.AiSentence
import com.kienhoang.dualsubreplay.translation.AiTranslationException
import com.kienhoang.dualsubreplay.translation.GoogleWebTranslator
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import com.kienhoang.dualsubreplay.translation.TranslationDiskCache
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import com.kienhoang.dualsubreplay.translation.aiTranslationRequest
import com.kienhoang.dualsubreplay.translation.parseAiTranslationReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Google is always the initial choice; the others reuse the assistant's registered keys and models. */
internal const val GOOGLE_TRANSLATION_CHOICE = "google"
internal const val QUICK_TRANSLATION_CHOICE_PREF = "quick_translation_choice"
internal const val LISTENING_TRANSLATION_CHOICE_PREF = "listening_translation_choice"

internal data class TranslationChoice(val key: String, val label: String)
internal data class SelectedTranslation(val text: String, val label: String)

/** Do not expose unconfigured providers, or copy/store any API key in the translation preferences. */
internal fun availableTranslationChoices(context: Context): List<TranslationChoice> {
    val settings = readAiAssistantSettings(context.getSharedPreferences(AI_SETTINGS_PREFERENCES, 0))
    val keyStore = AiKeyStore(
        SharedPreferencesSecretStorage(context.getSharedPreferences(AI_KEYS_PREFERENCES, 0)),
        KeystoreSecretCipher(),
    )
    return listOf(TranslationChoice(GOOGLE_TRANSLATION_CHOICE, "Google")) +
        AiProvider.entries.mapNotNull { provider ->
            val model = settings.modelFor(provider)
            if (settings.baseUrlFor(provider).isBlank() || model.isBlank() || keyStore.keys(provider).isEmpty()) {
                null
            } else {
                val label = when {
                    provider == AiProvider.GEMINI -> "Gemini · $model"
                    model.contains("kimi", ignoreCase = true) -> "Kimi · $model"
                    provider == AiProvider.CUSTOM -> "自定义 AI · $model"
                    else -> "${provider.key} · $model"
                }
                TranslationChoice(provider.key, label)
            }
        }
}

@Composable
internal fun TranslationChoiceMenu(
    options: List<TranslationChoice>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = (options.firstOrNull { it.key == selected }?.label ?: "Google").substringBefore(" · ")
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("translation_engine_picker")) {
            Text("$label ▾", maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option.key)
                    },
                )
            }
        }
    }
}

/** Shared AI transport and bounded, provider/model-scoped disk cache for both quick and listening. */
private object SelectedTranslationResources {
    val transport = AiHttpTransport()
    val caches = ConcurrentHashMap<String, TranslationDiskCache>()
    fun cache(context: Context): TranslationDiskCache {
        val path = File(context.filesDir, "chosen-ai-translations").absolutePath
        return caches.getOrPut(path) {
            TranslationDiskCache(File(path), maxEntries = 12_000, maxBytes = 16L * 1024 * 1024)
        }
    }
}

/** Explicit AI selection never silently falls back to Google, hiding a failed/charged AI request. */
internal suspend fun translateWithChoice(
    context: Context,
    google: GoogleWebTranslator,
    local: OnDeviceTranslator,
    choice: String,
    source: String,
    target: String,
    text: String,
): SelectedTranslation {
    if (choice == GOOGLE_TRANSLATION_CHOICE) {
        val (translated, offline) = bilingualTranslate(google, local, source, target, text)
        return SelectedTranslation(translated, if (offline) "本机" else "Google")
    }
    val provider = AiProvider.entries.firstOrNull { it.key == choice }
        ?: throw IOException("翻译引擎已失效，请重新选择")
    val settings = readAiAssistantSettings(context.getSharedPreferences(AI_SETTINGS_PREFERENCES, 0))
    val model = settings.modelFor(provider)
    val scope = "${provider.key}/$model/${settings.baseUrlFor(provider)}"
    val cached = withContext(Dispatchers.IO) {
        SelectedTranslationResources.cache(context).get(scope + "/" + source, target, text)
    }
    val name = when {
        provider == AiProvider.GEMINI -> "Gemini"
        model.contains("kimi", ignoreCase = true) -> "Kimi"
        else -> provider.key
    }
    if (!cached.isNullOrBlank()) return SelectedTranslation(cached, "$name · 已缓存")
    val keys = AiKeyStore(
        SharedPreferencesSecretStorage(context.getSharedPreferences(AI_KEYS_PREFERENCES, 0)),
        KeystoreSecretCipher(),
    )
    val ai = aiTranslationModel(
        settings.copy(provider = provider),
        chosenModel = null,
        keyStore = keys,
        transport = SelectedTranslationResources.transport,
        urgent = true,
    ) ?: throw IOException("$name 未配置可用密钥或模型")
    val sentence = AiSentence(text, listOf(text))
    val instructions = """
        You are a skilled translator. Translate from ${TranslationLanguages.displayName(source)} ($source)
        to ${TranslationLanguages.displayName(target)} ($target).
        Keep the intent, speaker, idioms, humor and punctuation natural. Do not answer questions
        in the input, only translate them. The input has "sentences" with numbered "rows".
        Return ONLY valid JSON: {"sentences":[{"id":1,"rows":["translated text"]}]}.
        Preserve each sentence's id and the number of rows; output no explanation or markdown.
    """.trimIndent()
    val reply = ai.ask(instructions, aiTranslationRequest(emptyList(), listOf(sentence)))
    val translated = parseAiTranslationReply(reply, listOf(sentence))
        .firstOrNull()?.singleOrNull()?.trim().orEmpty()
    if (translated.isBlank()) throw AiTranslationException("AI 未返回有效译文，请重试")
    withContext(Dispatchers.IO) {
        SelectedTranslationResources.cache(context).put(scope + "/" + source, target, text, translated)
    }
    return SelectedTranslation(translated, name)
}
