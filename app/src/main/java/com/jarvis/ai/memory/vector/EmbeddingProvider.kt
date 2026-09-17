package com.jarvis.ai.memory.vector

import com.jarvis.ai.provider.HttpTransport
import com.jarvis.ai.provider.HttpTransports
import com.jarvis.ai.provider.Jsons
import com.jarvis.ai.provider.SecretsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Converts text into embedding vectors. Implementations per vendor. */
interface EmbeddingProvider {
    val providerId: String

    /** Returns one vector per input, same order. */
    suspend fun embed(texts: List<String>): List<List<Float>>
}

/**
 * Mistral /v1/embeddings with the dedicated `mistral-embed` model (1024 dims).
 * Chosen first because a working Mistral key is already provisioned.
 */
class MistralEmbeddingProvider(
    private val secrets: SecretsSource,
    private val model: String = "mistral-embed",
    private val transport: HttpTransport = HttpTransports.default
) : EmbeddingProvider {

    override val providerId = "mistral"

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonContentType = "application/json".toMediaType()

    @Serializable
    internal data class EmbeddingRequest(val model: String, val input: List<String>)

    @Serializable
    internal data class EmbeddingDataItem(
        @SerialName("embedding") val embedding: List<Float> = emptyList(),
        @SerialName("index") val index: Int = 0
    )

    @Serializable
    internal data class EmbeddingResponse(val data: List<EmbeddingDataItem> = emptyList())

    override suspend fun embed(texts: List<String>): List<List<Float>> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            val key = secrets.get("MISTRAL_API_KEY#1")
                ?: throw IOException("mistral embeddings: key missing")
            val payload = json.encodeToString(
                EmbeddingRequest.serializer(),
                EmbeddingRequest(model = model, input = texts)
            )
            val request = Request.Builder()
                .url("https://api.mistral.ai/v1/embeddings")
                .header("Authorization", "Bearer $key")
                .post(payload.toRequestBody(jsonContentType))
                .build()
            transport.execute(request).use { response ->
                if (!response.isSuccessful) {
                    throw IOException("mistral embeddings HTTP ${response.code}")
                }
                val decoded = json.decodeFromString(
                    EmbeddingResponse.serializer(),
                    response.body?.string().orEmpty()
                )
                decoded.data.sortedBy { it.index }.map { it.embedding }
                    .also { vectors ->
                        if (vectors.size != texts.size) {
                            throw IOException("mistral embeddings: size mismatch")
                        }
                    }
            }
        }
}

/**
 * Deterministic hash-based embedder for unit tests and offline fallback.
 * Produces a fixed-dimension bag-of-hashes vector; cosine behaves sensibly
 * for identical/similar/disjoint inputs. NOT semantic quality — test only.
 */
class HashEmbeddingProvider(private val dimensions: Int = 64) : EmbeddingProvider {

    override val providerId = "hash-test"

    override suspend fun embed(texts: List<String>): List<List<Float>> =
        texts.map { text ->
            FloatArray(dimensions).also { vec ->
                text.lowercase().split(Regex("\\W+"))
                    .filter { it.isNotBlank() }
                    .forEach { token ->
                        val h = token.hashCode().toLong()
                        vec[((h % dimensions + dimensions) % dimensions).toInt()] += 1f
                        vec[(((h shr 7) % dimensions + dimensions) % dimensions).toInt()] += 0.5f
                    }
            }.toList()
        }
}
