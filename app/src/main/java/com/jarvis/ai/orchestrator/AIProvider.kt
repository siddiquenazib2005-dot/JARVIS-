package com.jarvis.ai.orchestrator

import com.jarvis.ai.data.model.ApiMessage
import com.jarvis.ai.data.model.ChatCompletionRequest
import com.jarvis.ai.data.model.ChatCompletionResponse
import com.jarvis.ai.data.model.ErrorEnvelope
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.data.model.VisionResponse
import com.jarvis.ai.data.remote.SseEvent
import com.jarvis.ai.data.remote.SseLineParser
import com.jarvis.ai.provider.HttpTransport
import com.jarvis.ai.provider.HttpTransports
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlin.text.isNotBlank
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Result of an AI provider call. */
sealed class AIProviderResult {
    data class Success(
        val content: String,
        val model: String,
        val usage: Map<String, Any?>? = null
    ) : AIProviderResult()

    data class Failure(val error: String, val recoverable: Boolean) : AIProviderResult()
}

/** Interface for AI providers. */
interface AIProvider {
    val name: String
    val supportedModels: List<String>
    val defaultModel: String
    val supportsStreaming: Boolean

    suspend fun isAvailable(): Boolean

    suspend fun streamChat(
        history: List<Message>,
        systemPrompt: String,
        model: String
    ): Flow<String>

    suspend fun chatOnce(
        history: List<Message>,
        systemPrompt: String,
        model: String
    ): String

    /**
     * Analyzes an image with a prompt. Returns the analysis text.
     * Implementations should handle the specific vision API format.
     */
    suspend fun analyzeImage(
        base64Image: String,
        mimeType: String,
        prompt: String,
        model: String
    ): String
}

/** OpenAI-compatible provider implementation. */
open class OpenAICompatibleProvider(
    override val name: String,
    private val baseUrl: String,
    private val apiKey: String,
    override val defaultModel: String,
    override val supportedModels: List<String>,
    override val supportsStreaming: Boolean = true,
    private val transport: HttpTransport = HttpTransports.default
) : AIProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val jsonContentType = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun isAvailable(): Boolean = apiKey.isNotBlank()

    override suspend fun streamChat(
        history: List<Message>,
        systemPrompt: String,
        model: String
    ): Flow<String> = flow {
        val request = buildRequest(history, systemPrompt, model, stream = true)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(providerError(response))
            }
            if (response.header("Content-Type").orEmpty().contains("application/json", ignoreCase = true)) {
                emitFullResponse(response)
                return@use
            }
            val source = response.body?.source() ?: throw IOException("Uplink returned no data.")
            while (true) {
                val line = source.readUtf8Line() ?: break
                when (val event = SseLineParser.parse(line)) {
                    is SseEvent.DataPayload -> {
                        val chunk = runCatching {
                            json.decodeFromString(StreamChunk.serializer(), event.rawJson)
                        }.getOrNull() ?: continue
                        chunk.error?.message?.takeIf { it.isNotBlank() }?.let { throw IOException(it) }
                        val delta = chunk.choices.firstOrNull()?.delta?.content.orEmpty()
                        if (delta.isNotEmpty()) emit(delta)
                    }
                    SseEvent.Done -> break
                    SseEvent.Ignore -> Unit
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun chatOnce(
        history: List<Message>,
        systemPrompt: String,
        model: String
    ): String = withContext(Dispatchers.IO) {
        val request = buildRequest(history, systemPrompt, model, stream = false)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(providerError(response))
            }
            val body = response.body?.string() ?: throw IOException("Uplink returned no data.")
            val decoded = runCatching {
                json.decodeFromString(ChatCompletionResponse.serializer(), body)
            }.getOrNull()
            val content = decoded?.choices?.firstOrNull()?.message?.content
            content?.takeIf { it.isNotEmpty() }
                ?: throw IOException(
                    decoded?.error?.message?.takeIf { it.isNotBlank() }
                        ?: "Uplink returned no content."
                )
        }
    }

    /**
     * Analyzes an image using OpenAI-compatible vision API (gpt-4o-mini, gpt-4o, etc.).
     * Uses the standard chat/completions endpoint with image_url content parts.
     */
    override suspend fun analyzeImage(
        base64Image: String,
        mimeType: String,
        prompt: String,
        model: String
    ): String = withContext(Dispatchers.IO) {
        val payload = """{
            "model": "$model",
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "text", "text": "$prompt"},
                    {"type": "image_url", "image_url": {"url": "data:$mimeType;base64,$base64Image"}}
                ]
            }]
        }""".trimIndent()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonContentType))
            .build()
        transport.execute(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException(providerError(response))
            }
            val body = response.body?.string() ?: throw IOException("Vision API returned no data.")
            val decoded = runCatching {
                json.decodeFromString(VisionResponse.serializer(), body)
            }.getOrNull()
            val content = decoded?.choices?.firstOrNull()?.message?.content
            content?.takeIf { it.isNotBlank() }
                ?: throw IOException(decoded?.error?.message?.takeIf { it.isNotBlank() } ?: "Vision API returned no data.")
        }
    }

    private fun buildRequest(
        history: List<Message>,
        systemPrompt: String,
        model: String,
        stream: Boolean
    ): Request {
        val payload = json.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(
                model = model,
                messages = toApiMessages(history, systemPrompt),
                stream = stream
            )
        )
        return Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(jsonContentType))
            .build()
    }

    private suspend fun FlowCollector<String>.emitFullResponse(response: Response) {
        val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        val decoded = runCatching {
            json.decodeFromString(ChatCompletionResponse.serializer(), body)
        }.getOrNull()
        val content = decoded?.choices?.firstOrNull()?.message?.content.orEmpty()
        if (content.isEmpty()) {
            throw IOException(
                decoded?.error?.message?.takeIf { it.isNotBlank() }
                    ?: "Uplink returned no content."
            )
        }
        emit(content)
    }

    private fun providerError(response: Response): String {
        val fallback = "Uplink fault (HTTP ${response.code})."
        val bodyText = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        if (bodyText.isBlank()) return fallback
        val decoded = runCatching {
            json.decodeFromString(ErrorEnvelope.serializer(), bodyText)
        }.getOrNull()
        return decoded?.message?.let { it.takeIf { it.isNotBlank() } } ?: fallback
    }

    private fun toApiMessages(history: List<Message>, systemPrompt: String): List<ApiMessage> =
        buildList {
            add(ApiMessage(role = "system", content = systemPrompt))
            history.forEach { message ->
                if (message.text.isNotBlank()) {
                    add(
                        ApiMessage(
                            role = if (message.sender == Sender.USER) "user" else "assistant",
                            content = message.text
                        )
                    )
                }
            }
        }
}

/** Offline provider for when no network is available. */
class OfflineProvider(
    override val name: String = "Offline",
    override val defaultModel: String = "offline",
    override val supportedModels: List<String> = listOf("offline"),
    override val supportsStreaming: Boolean = false
) : AIProvider {

    override suspend fun isAvailable(): Boolean = true

    override suspend fun streamChat(
        history: List<Message>,
        systemPrompt: String,
        model: String
    ): Flow<String> = flow {
        val reply = com.jarvis.ai.data.repository.OfflineJarvisEngine.respond(lastUserText(history))
        emit(reply)
    }

    override suspend fun chatOnce(
        history: List<Message>,
        systemPrompt: String,
        model: String
    ): String = com.jarvis.ai.data.repository.OfflineJarvisEngine.respond(lastUserText(history))

    override suspend fun analyzeImage(
        base64Image: String,
        mimeType: String,
        prompt: String,
        model: String
    ): String = "Vision not available in offline mode, sir."
}

private fun lastUserText(history: List<Message>): String =
    history.lastOrNull { it.sender == Sender.USER }?.text ?: ""

@Serializable
data class StreamChunk(
    @SerialName("choices") val choices: List<StreamChoice> = emptyList(),
    @SerialName("error") val error: ErrorEnvelope? = null
)

@Serializable
data class StreamChoice(
    @SerialName("index") val index: Int = 0,
    @SerialName("delta") val delta: StreamDelta = StreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class StreamDelta(
    @SerialName("role") val role: String? = null,
    @SerialName("content") val content: String? = null
)