package com.jarvis.ai.provider.adapters

import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.orchestrator.AIProvider
import com.jarvis.ai.provider.HttpTransport
import com.jarvis.ai.provider.HttpTransports
import com.jarvis.ai.provider.ProviderConfig
import com.jarvis.ai.provider.SecretsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Gemini uses a request/response shape different from OpenAI-compatible
 * endpoints, hence a dedicated adapter behind the common [AIProvider] interface.
 *
 * Streaming: Uses the streamGenerateContent endpoint with SSE streaming.
 */
class GeminiProvider(
    override val name: String,
    private val apiKey: String,
    override val defaultModel: String,
    override val supportedModels: List<String> = listOf("gemini-2.5-flash", "gemini-flash-latest"),
    override val supportsStreaming: Boolean = true,
    private val transport: HttpTransport = HttpTransports.default
) : AIProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonContentType = "application/json".toMediaType()

    override suspend fun isAvailable(): Boolean = apiKey.isNotBlank()

    override suspend fun streamChat(
        history: List<Message>,
        systemPrompt: String,
        model: String
    ): Flow<String> = flow {
        val payload = json.encodeToString(
            GeminiRequest.serializer(),
            GeminiRequest(
                contents = history.filter { it.text.isNotBlank() }.map { msg ->
                    GeminiContent(
                        role = if (msg.sender == Sender.USER) "user" else "model",
                        parts = listOf(GeminiPart(text = msg.text))
                    )
                },
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt)))
            )
        )
        val url = "${BASE_URL.trimEnd('/')}/models/${model}:streamGenerateContent?key=$apiKey&alt=sse"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonContentType))
            .build()

        transport.execute(request).use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw IOException("HTTP ${response.code}: ${body.take(300)}")
            }
            val source = response.body?.source() ?: throw IOException("Gemini stream returned no data.")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank() || line.startsWith(":") || !line.startsWith("data: ")) continue
                val data = line.substring(6)
                if (data == "[DONE]") break
                val chunk = runCatching {
                    json.decodeFromString(GeminiStreamChunk.serializer(), data)
                }.getOrNull() ?: continue
                val delta = chunk.candidates?.firstOrNull()?.content?.parts
                    ?.mapNotNull { it.text }?.joinToString("") ?: ""
                if (delta.isNotEmpty()) emit(delta)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun chatOnce(
        history: List<Message>,
        systemPrompt: String,
        model: String
    ): String {
        val payload = json.encodeToString(
            GeminiRequest.serializer(),
            GeminiRequest(
                contents = history.filter { it.text.isNotBlank() }.map { msg ->
                    GeminiContent(
                        role = if (msg.sender == Sender.USER) "user" else "model",
                        parts = listOf(GeminiPart(text = msg.text))
                    )
                },
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt)))
            )
        )
        val url = "${BASE_URL.trimEnd('/')}/models/${model}:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonContentType))
            .build()

        transport.execute(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.body?.string()?.take(300)}")
            }
            val body = response.body?.string() ?: throw IOException("Gemini returned no data.")
            val decoded = runCatching {
                json.decodeFromString(GeminiResponse.serializer(), body)
            }.getOrNull()
            val text = decoded?.candidates?.firstOrNull()?.content?.parts
                .orEmpty().mapNotNull { it.text }.joinToString("")
            return text.takeIf { it.isNotBlank() }
                ?: throw IOException(
                    decoded?.error?.message?.takeIf { it.isNotBlank() }
                        ?: "Gemini returned no content."
                )
        }
    }

    /**
     * Vision: sends an image (base64, no data-uri prefix) + prompt to Gemini.
     * Only call this for vision-capable providers — registry caps enforce it.
     */
    override suspend fun analyzeImage(
        base64Image: String,
        mimeType: String,
        prompt: String,
        model: String
    ): String {
        val payload = json.encodeToString(
            GeminiVisionRequest.serializer(),
            GeminiVisionRequest(
                contents = listOf(
                    GeminiContent(
                        role = "user",
                        parts = listOf(
                            GeminiPart(inlineData = GeminiInline(mimeType, base64Image)),
                            GeminiPart(text = prompt)
                        )
                    )
                )
            )
        )
        val url = "${BASE_URL}/models/${model}:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonContentType))
            .build()
        transport.execute(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("gemini-vision HTTP ${response.code}")
            }
            val decoded = runCatching {
                json.decodeFromString(GeminiResponse.serializer(), response.body?.string().orEmpty())
            }.getOrNull()
            return decoded?.candidates?.firstOrNull()?.content?.parts
                ?.mapNotNull { it.text }?.joinToString("")
                ?.takeIf { it.isNotBlank() }
                ?: throw IOException("gemini-vision: empty analysis")
        }
    }

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}

@Serializable
internal data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null
)

@Serializable
internal data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

@Serializable
internal data class GeminiPart(
    val text: String? = null,
    @SerialName("inline_data") val inlineData: GeminiInline? = null
)

@Serializable
internal data class GeminiInline(
    @SerialName("mime_type") val mimeType: String,
    val data: String
)

@Serializable
internal data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    @SerialName("error") val error: ProviderApiError? = null
)

@Serializable
internal data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null
)

@Serializable
internal data class ProviderApiError(val message: String? = null)

// ---- Gemini Streaming DTOs ----

@Serializable
internal data class GeminiStreamChunk(
    val candidates: List<GeminiStreamCandidate> = emptyList()
)

@Serializable
internal data class GeminiStreamCandidate(
    val content: GeminiStreamContent? = null,
    val finishReason: String? = null
)

@Serializable
internal data class GeminiStreamContent(
    val parts: List<GeminiStreamPart> = emptyList()
)

@Serializable
internal data class GeminiStreamPart(
    val text: String? = null
)

@Serializable
internal data class GeminiVisionRequest(val contents: List<GeminiContent>)

/**
 * Builds concrete [AIProvider] instances from registry configs.
 */
object LlmProviderFactory {

    fun create(
        config: ProviderConfig,
        apiKey: String,
        model: String,
        transport: HttpTransport = HttpTransports.default
    ): AIProvider = when (config.providerId) {
        "gemini" -> GeminiProvider(
            name = config.providerId,
            apiKey = apiKey,
            defaultModel = model,
            transport = transport
        )
        else -> com.jarvis.ai.orchestrator.OpenAICompatibleProvider(
            name = config.providerId,
            baseUrl = config.baseUrl,
            apiKey = apiKey,
            defaultModel = model,
            supportedModels = listOf(model),
            transport = transport
        )
    }

    /** Convenience used by tests to build offline terminal candidates. */
    fun offline(): AIProvider = com.jarvis.ai.orchestrator.OfflineProvider()
}
