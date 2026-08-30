package com.jarvis.ai.data.remote

import com.jarvis.ai.data.model.ApiMessage
import com.jarvis.ai.data.model.ChatCompletionRequest
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
internal data class ChatCompletionResponse(
    @SerialName("choices") val choices: List<CompletionChoice> = emptyList(),
    @SerialName("error") val error: ApiErrorBody? = null
)

@Serializable
internal data class CompletionChoice(
    @SerialName("message") val message: CompletionMessage = CompletionMessage(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class CompletionMessage(
    @SerialName("role") val role: String? = null,
    @SerialName("content") val content: String? = null
)

@Serializable
internal data class StreamChunk(
    @SerialName("choices") val choices: List<StreamChoice> = emptyList(),
    @SerialName("error") val error: ApiErrorBody? = null
)

@Serializable
internal data class StreamChoice(
    @SerialName("delta") val delta: StreamDelta = StreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class StreamDelta(
    @SerialName("role") val role: String? = null,
    @SerialName("content") val content: String? = null
)

@Serializable
internal data class ApiErrorBody(
    @SerialName("message") val message: String? = null,
    @SerialName("code") val code: String? = null
)

@Serializable
internal data class ErrorEnvelope(
    @SerialName("error") val error: ApiErrorBody? = null
)

class JarvisApiClient(
    private val baseUrl: String,
    private val model: String
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun streamChat(
        history: List<Message>,
        systemPrompt: String,
        apiKey: String
    ): Flow<String> = flow {
        val request = buildRequest(history, systemPrompt, apiKey, stream = true)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(providerError(response, uplinkError(response.code)))
            }
            if (response.header("Content-Type").orEmpty()
                .contains(JSON_MEDIA_TYPE, ignoreCase = true)
            ) {
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
                        chunk.error?.message?.takeIf { it.isNotBlank() }?.let {
                            throw IOException(it)
                        }
                        val delta = chunk.choices.firstOrNull()?.delta?.content.orEmpty()
                        if (delta.isNotEmpty()) emit(delta)
                    }
                    SseEvent.Done -> break
                    SseEvent.Ignore -> Unit
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun chatOnce(
        history: List<Message>,
        systemPrompt: String,
        apiKey: String
    ): String {
        val request = buildRequest(history, systemPrompt, apiKey, stream = false)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(providerError(response, uplinkError(response.code)))
            }
            val body = response.body?.string() ?: throw IOException("Uplink returned no data.")
            val decoded = runCatching {
                json.decodeFromString(ChatCompletionResponse.serializer(), body)
            }.getOrNull()
            val content = decoded?.choices?.firstOrNull()?.message?.content
            return content?.takeIf { it.isNotEmpty() }
                ?: throw IOException(
                    decoded?.error?.message?.takeIf { it.isNotBlank() }
                        ?: "Uplink returned no content."
                )
        }
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

    private fun buildRequest(
        history: List<Message>,
        systemPrompt: String,
        apiKey: String,
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
            .post(payload.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
    }

    private fun providerError(response: Response, fallback: String): String {
        val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        if (body.isNotBlank()) {
            val envelope = runCatching {
                json.decodeFromString(ErrorEnvelope.serializer(), body)
            }.getOrNull()
            envelope?.error?.message?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return fallback
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

    private fun uplinkError(code: Int): String = when (code) {
        401 -> "Authentication rejected by uplink (HTTP 401). API key invalid."
        402 -> "Payment required by provider (HTTP 402)."
        403 -> "Access forbidden (HTTP 403). Key lacks access to this model."
        404 -> "Endpoint not found (HTTP 404). Check base URL in Settings."
        429 -> "Rate limited by provider (HTTP 429). Try again shortly."
        else -> "Uplink fault (HTTP $code)."
    }

    companion object {
        private const val JSON_MEDIA_TYPE = "application/json"
    }
}
