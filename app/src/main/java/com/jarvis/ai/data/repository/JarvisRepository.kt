package com.aurix.ai.data.repository

import android.content.Context
import com.aurix.ai.data.local.SecureStore
import com.aurix.ai.data.model.Message
import com.aurix.ai.data.model.Sender
import com.aurix.ai.data.remote.AurixApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class ProviderPreset(
    val label: String,
    val baseUrl: String,
    val suggestedModel: String
)

class AurixRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val secure = SecureStore(context)

    init {
        migrateLegacyApiKey()
    }

    private fun migrateLegacyApiKey() {
        if (secure.contains(KEY_API_KEY)) return
        val legacy = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty()
        if (legacy.isBlank()) return
        secure.put(KEY_API_KEY, legacy)
        if (secure.get(KEY_API_KEY) == legacy) {
            prefs.edit().remove(KEY_API_KEY).apply()
        }
    }

    fun getApiKey(): String = secure.get(KEY_API_KEY).orEmpty().trim()

    fun saveApiKey(key: String) = secure.put(KEY_API_KEY, key.trim())

    fun getBaseUrl(): String = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)
        ?.trim().orEmpty().ifBlank { DEFAULT_BASE_URL }

    fun saveBaseUrl(url: String) =
        prefs.edit().putString(KEY_BASE_URL, url.trim()).apply()

    fun getModel(): String = prefs.getString(KEY_MODEL, DEFAULT_MODEL)
        ?.trim().orEmpty().ifBlank { DEFAULT_MODEL }

    fun saveModel(model: String) =
        prefs.edit().putString(KEY_MODEL, model.trim()).apply()

    fun hasApiKey(): Boolean = getApiKey().isNotBlank()

    fun presets(): List<ProviderPreset> = PROVIDER_PRESETS

    fun sendMessage(history: List<Message>): Flow<String> {
        val key = getApiKey()
        return if (key.isBlank()) offlineReply(lastUserText(history))
        else AurixApiClient(getBaseUrl(), getModel()).streamChat(history, SYSTEM_PROMPT, key)
    }

    suspend fun sendMessageOnce(history: List<Message>): String {
        val key = getApiKey()
        if (key.isBlank()) return OfflineAurixEngine.respond(lastUserText(history))
        val client = AurixApiClient(getBaseUrl(), getModel())
        return withContext(Dispatchers.IO) { client.chatOnce(history, SYSTEM_PROMPT, key) }
    }

    private fun lastUserText(history: List<Message>): String =
        history.lastOrNull { it.sender == Sender.USER }?.text.orEmpty()

    private fun offlineReply(userText: String): Flow<String> = flow {
        delay(Random.nextLong(450L, 950L))
        val reply = OfflineAurixEngine.respond(userText)
        reply.split(" ").forEachIndexed { index, word ->
            emit(if (index == 0) word else " $word")
            delay(22L)
        }
    }

    companion object {
        private const val PREFS_NAME = "aurix_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        private val PROVIDER_PRESETS = listOf(
            ProviderPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            ProviderPreset(
                "OpenRouter",
                "https://openrouter.ai/api/v1",
                "meta-llama/llama-3.3-70b-instruct"
            ),
            ProviderPreset("Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b"),
            ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat")
        )

        const val SYSTEM_PROMPT = """You are AURIX (Just A Rather Very Intelligent System), a refined AI assistant in the style of Tony Stark's assistant. Address the user respectfully as "sir". Be concise, precise, lightly witty and technically competent. Prefer short clear paragraphs. When computing results, state the final answer clearly."""
    }
}
