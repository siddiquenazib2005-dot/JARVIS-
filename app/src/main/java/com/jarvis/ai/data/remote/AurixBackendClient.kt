package com.jarvis.ai.data.remote

import android.content.Context
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Persisted pointer to the user's own AURIX backend.
 *
 * When a base URL is present the app sends chat through the backend, which owns
 * the provider keys, routing, failover and long-term memory. Empty URL means
 * on-device routing with keys stored in the encrypted secure store.
 */
object BackendPrefs {

    private const val PREFS_NAME = "aurix_backend"
    private const val KEY_URL = "base_url"
    private const val KEY_TOKEN = "app_token"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var baseUrl: String = ""
        private set

    @Volatile
    var appToken: String = ""
        private set

    /** True when the app should route chat through the backend. */
    val isEnabled: Boolean get() = baseUrl.isNotBlank()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        baseUrl = prefs.getString(KEY_URL, "").orEmpty()
        appToken = prefs.getString(KEY_TOKEN, "").orEmpty()
    }

    fun save(url: String, token: String) {
        baseUrl = normalise(url)
        appToken = token.trim()
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_URL, baseUrl)
            ?.putString(KEY_TOKEN, appToken)
            ?.apply()
    }

    fun clear() = save("", "")

    private fun normalise(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}

/** One provider row from `GET /v1/health`. */
data class BackendProviderStatus(
    val id: String,
    val label: String,
    val state: String,
    val keys: Int,
    val avgLatencyMs: Int
)

/**
 * Thin client for the AURIX backend. Deliberately uses org.json instead of
 * kotlinx-serialization so it stays independent of the existing DTO layer.
 */
class AurixBackendClient(
    private val baseUrl: String,
    private val appToken: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Streams a reply from `POST /v1/chat/stream`. */
    fun streamChat(
        history: List<Message>,
        prompt: String,
        sessionId: String,
        model: String?,
        providerId: String?
    ): Flow<String> = flow {
        val payload = JSONObject().apply {
            put("messages", toMessagesArray(history, prompt))
            put("sessionId", sessionId)
            if (!model.isNullOrBlank()) put("model", model)
            if (!providerId.isNullOrBlank()) put("provider", providerId)
        }
        val request = post("/v1/chat/stream", payload)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(describe(response.code))
            val source = response.body?.source() ?: throw IOException("Backend returned no data.")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val body = line.removePrefix("data:").trim()
                if (body.isEmpty()) continue
                if (body == "[DONE]") break
                val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
                json.optJSONObject("error")?.let {
                    throw IOException(it.optString("message", "Backend error"))
                }
                val delta = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                    ?.optString("content")
                    .orEmpty()
                if (delta.isNotEmpty()) emit(delta)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Non-streaming call, used for quick checks. */
    fun chatOnce(prompt: String, sessionId: String): String {
        val payload = JSONObject().apply {
            put("prompt", prompt)
            put("sessionId", sessionId)
        }
        client.newCall(post("/v1/chat", payload)).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(errorOf(raw) ?: describe(response.code))
            val text = runCatching { JSONObject(raw).optString("text") }.getOrNull().orEmpty()
            if (text.isBlank()) throw IOException(errorOf(raw) ?: "Backend returned no content.")
            return text
        }
    }

    /** Reads `GET /v1/health`. Returns null when the backend is unreachable. */
    fun health(): List<BackendProviderStatus>? {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/health")
            .apply { if (appToken.isNotBlank()) header("Authorization", "Bearer $appToken") }
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw = response.body?.string().orEmpty()
                val providers = JSONObject(raw).optJSONArray("providers") ?: return emptyList()
                (0 until providers.length()).mapNotNull { index ->
                    providers.optJSONObject(index)?.let {
                        BackendProviderStatus(
                            id = it.optString("id"),
                            label = it.optString("label"),
                            state = it.optString("state"),
                            keys = it.optInt("keys"),
                            avgLatencyMs = it.optInt("avgLatencyMs")
                        )
                    }
                }
            }
        }.getOrNull()
    }

    /** Human-readable connection summary for the settings screen. */
    fun describeConnection(): String {
        val statuses = health() ?: return "Backend unreachable — check the URL, sir."
        val live = statuses.filter { it.state == "healthy" }
        return if (live.isEmpty()) {
            "Backend reachable, but no provider key is configured on it, sir."
        } else {
            "Backend online ✓ — " + live.joinToString(", ") { it.label }
        }
    }

    private fun post(path: String, payload: JSONObject): Request =
        Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .apply { if (appToken.isNotBlank()) header("Authorization", "Bearer $appToken") }
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

    private fun toMessagesArray(history: List<Message>, prompt: String): JSONArray {
        val array = JSONArray()
        history.takeLast(16).forEach { message ->
            if (message.text.isNotBlank()) {
                array.put(
                    JSONObject()
                        .put("role", if (message.sender == Sender.USER) "user" else "assistant")
                        .put("content", message.text)
                )
            }
        }
        array.put(JSONObject().put("role", "user").put("content", prompt))
        return array
    }

    private fun errorOf(raw: String): String? = runCatching {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun describe(code: Int): String = when (code) {
        401 -> "Backend rejected the app token (HTTP 401), sir."
        404 -> "Backend URL looks wrong (HTTP 404), sir."
        429 -> "Backend is rate limited (HTTP 429), sir."
        502, 503 -> "Backend has no working provider right now (HTTP $code), sir."
        else -> "Backend fault (HTTP $code), sir."
    }
}
