package com.jarvis.ai.memory.vector

import com.jarvis.ai.provider.FailureCategory
import com.jarvis.ai.provider.HttpTransport
import com.jarvis.ai.provider.HttpTransports
import com.jarvis.ai.provider.SecretRedactor
import com.jarvis.ai.provider.SecretsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

private fun metaString(meta: Map<String, JsonElement>?, key: String): String? =
    (meta?.get(key) as? JsonPrimitive)?.content

/**
 * REAL Pinecone REST implementation behind the shared [VectorStore] seam.
 *
 * Data-plane:  POST /vectors/upsert | /query | /vectors/delete, GET /vectors/fetch
 * Control-plane: GET/POST https://api.pinecone.io/indexes — used to resolve the
 * index host when PINECONE_INDEX_HOST is absent, and to validate dimensions.
 *
 * Secrets consumed (backend-only, never logged):
 *  - PINECONE_API_KEY      (required)
 *  - PINECONE_INDEX_HOST   (optional; auto-resolved via control plane when absent)
 *
 * Transient IO failures are retried with exponential backoff. HTTP errors are
 * mapped into [VectorStoreException] categories so the resilient store chain in
 * [VectorMemoryManager] can fail over to Qdrant and then the local store.
 */
class PineconeVectorStore(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default,
    private val controlPlaneUrl: String = "https://api.pinecone.io",
    private val now: () -> Long = System::currentTimeMillis
) : VectorStore {

    override val storeId = "pinecone"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMt = "application/json".toMediaType()

    fun isConfigured(): Boolean = !secrets.get("PINECONE_API_KEY#1").isNullOrBlank()

    private fun apiKey(): String =
        secrets.get("PINECONE_API_KEY#1")
            ?: throw VectorStoreException(null, FailureCategory.AUTH, "pinecone: key missing")

    @Volatile
    private var resolvedHost: String? = null
    private val hostMutex = Mutex()

    /**
     * Index host resolution order:
     *  1. cached value from this process
     *  2. PINECONE_INDEX_HOST secret
     *  3. live control-plane lookup (first index on the account)
     */
    private suspend fun host(): String {
        resolvedHost?.let { return it }
        secrets.get("PINECONE_INDEX_HOST#1")?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") }
            ?.let { resolvedHost = it; return it }

        hostMutex.withLock {
            resolvedHost?.let { return it }
            val (code, body) = call(
                Request.Builder()
                    .url("$controlPlaneUrl/indexes")
                    .header("Api-Key", apiKey())
                    .header("X-Pinecone-Api-Version", API_VERSION)
                    .get()
                    .build()
            )
            if (code != 200) {
                throw VectorStoreException(code, StoreHttpStatus.categoryFor(code), "pinecone: index list HTTP $code")
            }
            val decoded = runCatching { json.decodeFromString(IndexList.serializer(), body) }.getOrNull()
            val found = decoded?.indexes
                ?.firstOrNull { it.name == DEFAULT_INDEX_NAME } ?: decoded?.indexes?.firstOrNull()
            val hostUrl = found?.host?.trim()?.trimEnd('/')
            if (hostUrl.isNullOrBlank()) {
                throw VectorStoreException(null, FailureCategory.UNKNOWN, "pinecone: no index available")
            }
            resolvedHost = "https://$hostUrl".removePrefix("https://https://")
            return resolvedHost!!
        }
    }

    /** Embedding dimension declared by the index; null when it cannot be determined. */
    suspend fun indexDimensions(): Int? {
        val (code, body) = call(
            Request.Builder()
                .url("$controlPlaneUrl/indexes/${DEFAULT_INDEX_NAME}")
                .header("Api-Key", apiKey())
                .header("X-Pinecone-Api-Version", API_VERSION)
                .get()
                .build()
        )
        if (code != 200) return null
        val dims = runCatching {
            json.decodeFromString(IndexDescription.serializer(), body).dimension
        }.getOrDefault(0)
        return dims.takeIf { it > 0 }
    }

    private fun ns(namespace: String): String = namespace.ifBlank {
        secrets.get("PINECONE_NAMESPACE#1")?.takeIf { it.isNotBlank() } ?: VectorMemoryManager.DEFAULT_NAMESPACE
    }

    /** Executes with bounded retries; returns (httpCode, body). Never throws for HTTP codes. */
    private suspend fun call(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                transport.execute(request).use { response ->
                    return@withContext response.code to (response.body?.string().orEmpty())
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_RETRIES) delay(minOf(BASE_BACKOFF_MS shl attempt, MAX_BACKOFF_MS))
            }
        }
        throw VectorStoreException(
            null, FailureCategory.NETWORK,
            SecretRedactor.redact(lastError?.message ?: "network failure")
        )
    }

    private fun handle(code: Int, body: String): String {
        return when {
            code in 200..299 -> body
            code == 429 -> throw VectorStoreException(code, FailureCategory.RATE_LIMIT, "rate limited")
            code in 500..599 -> throw VectorStoreException(code, FailureCategory.SERVER, "HTTP $code")
            code == 401 || code == 403 ->
                throw VectorStoreException(code, FailureCategory.AUTH, "auth rejected (HTTP $code)")
            else -> throw VectorStoreException(
                code, FailureCategory.UNKNOWN,
                SecretRedactor.redact("HTTP $code: ${body.take(140)}")
            )
        }
    }

    /** Runs a data-plane call and maps non-2xx codes to structured exceptions. */
    private suspend fun handleCall(block: suspend () -> Pair<Int, String>): Pair<Int, String> {
        val (code, body) = block()
        handle(code, body)
        return code to body
    }

    private suspend fun dataPlane(path: String, namespace: String, payloadJson: String): Pair<Int, String> =
        call(
            Request.Builder()
                .url("${host()}$path?namespace=${urlEncode(ns(namespace))}")
                .header("Api-Key", apiKey())
                .header("X-Pinecone-Api-Version", API_VERSION)
                .post(payloadJson.toRequestBody(jsonMt))
                .build()
        )

    override suspend fun upsert(namespace: String, record: VectorRecord) = withContext(Dispatchers.IO) {
        val dims = indexDimensions()
        if (dims != null && record.embedding.isNotEmpty() && record.embedding.size != dims) {
            throw VectorStoreException(
                null, FailureCategory.MALFORMED_RESPONSE,
                "pinecone: embedding dimension mismatch " +
                    "(record dim ${record.embedding.size} != index dim $dims)"
            )
        }
        val body = json.encodeToString(
            UpsertRequest.serializer(),
            UpsertRequest(
                vectors = listOf(PineconeItem(record.memoryId, record.embedding, metaJson(record))),
                namespace = ns(namespace)
            )
        )
        val (code, respBody) = handleCall { dataPlane("/vectors/upsert", namespace, body) }
        val count = runCatching {
            json.decodeFromString(UpsertResponse.serializer(), respBody).upsertedCount
        }.getOrDefault(0)
        if (code !in 200..299 || count < 1) {
            throw VectorStoreException(
                code, FailureCategory.MALFORMED_RESPONSE,
                "upsert failed (HTTP $code, count=$count)"
            )
        }
        Unit
    }

    private fun metaJson(record: VectorRecord): JsonObject = buildJsonObject {
        put("content", record.content)
        put("memoryType", record.metadata.memoryType.name)
        record.metadata.projectId?.let { put("projectId", it) }
        record.metadata.sessionId?.let { put("sessionId", it) }
        put("importance", record.metadata.importance)
        put("source", record.metadata.source)
        put("createdAt", record.metadata.createdAt)
        put("updatedAt", now())
        if (record.metadata.tags.isNotEmpty()) {
            put(
                "tags",
                kotlinx.serialization.json.JsonArray(record.metadata.tags.map { JsonPrimitive(it) })
            )
        }
    }

    /** Native Pinecone metadata filter; type lists use `$in` (match-any). */
    internal fun pineconeFilter(filter: MemoryFilter?, allowDeleteAll: Boolean = false): JsonElement? {
        if (filter == null) return null
        val empty = filter.types.isNullOrEmpty() && filter.projectId == null &&
            filter.sessionId == null && filter.anyTag == null && filter.minImportance == null
        if (empty && !allowDeleteAll) return null
        return buildJsonObject {
            filter.types?.takeIf { it.isNotEmpty() }?.let { types ->
                put("memoryType", buildJsonObject { put("\$in", kotlinx.serialization.json.JsonArray(types.map { JsonPrimitive(it.name) })) })
            }
            filter.projectId?.let { put("projectId", buildJsonObject { put("\$eq", JsonPrimitive(it)) }) }
            filter.sessionId?.let { put("sessionId", buildJsonObject { put("\$eq", JsonPrimitive(it)) }) }
            filter.anyTag?.let { tag -> put("tags", buildJsonObject { put("\$in", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(tag)))) }) }
        }
    }

    override suspend fun search(
        namespace: String, queryEmbedding: List<Float>, topK: Int, filter: MemoryFilter?
    ): List<ScoredMemory> = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            QueryRequest.serializer(),
            QueryRequest(
                namespace = ns(namespace),
                topK = topK,
                vector = queryEmbedding,
                filter = pineconeFilter(filter),
                includeMetadata = true
            )
        )
        val (code, respBody) = handleCall { dataPlane("/query", namespace, body) }
        val decoded = runCatching {
            json.decodeFromString(QueryResponse.serializer(), respBody)
        }.getOrElse { throw VectorStoreException(code, FailureCategory.MALFORMED_RESPONSE, "query unparseable") }
        decoded.matches.map { m ->
            ScoredMemory(
                record = VectorRecord(
                    memoryId = m.id.orEmpty(),
                    content = metaString(m.metadata, "content").orEmpty(),
                    embedding = emptyList(),
                    metadata = MemoryMetadata(
                        memoryType = runCatching {
                            MemoryType.valueOf(metaString(m.metadata, "memoryType") ?: "SEMANTIC")
                        }.getOrDefault(MemoryType.SEMANTIC),
                        projectId = metaString(m.metadata, "projectId"),
                        sessionId = metaString(m.metadata, "sessionId"),
                        importance = metaString(m.metadata, "importance")?.toFloatOrNull() ?: 0.5f,
                        source = metaString(m.metadata, "source").orEmpty(),
                        createdAt = metaString(m.metadata, "createdAt")?.toLongOrNull() ?: 0L,
                        updatedAt = metaString(m.metadata, "updatedAt")?.toLongOrNull() ?: now()
                    )
                ),
                score = (m.score ?: 0.0).toFloat()
            )
        }
    }

    override suspend fun get(namespace: String, memoryId: String): VectorRecord? = withContext(Dispatchers.IO) {
        val url = "${host()}/vectors/fetch?ids=${urlEncode(memoryId)}&namespace=${urlEncode(ns(namespace))}"
        val (code, text) = call(
            Request.Builder().url(url)
                .header("Api-Key", apiKey()).header("X-Pinecone-Api-Version", API_VERSION).get().build()
        )
        if (code !in 200..299) return@withContext null
        runCatching {
            json.decodeFromString(FetchResponse.serializer(), text).vectors[memoryId]
        }.getOrNull()?.let { v ->
            VectorRecord(
                memoryId = memoryId,
                content = metaString(v.metadata, "content").orEmpty(),
                embedding = v.values ?: emptyList(),
                metadata = MemoryMetadata(
                    memoryType = runCatching {
                        MemoryType.valueOf(metaString(v.metadata, "memoryType") ?: "SEMANTIC")
                    }.getOrDefault(MemoryType.SEMANTIC),
                    projectId = metaString(v.metadata, "projectId"),
                    sessionId = metaString(v.metadata, "sessionId"),
                    importance = metaString(v.metadata, "importance")?.toFloatOrNull() ?: 0.5f,
                    source = metaString(v.metadata, "source").orEmpty(),
                    createdAt = metaString(v.metadata, "createdAt")?.toLongOrNull() ?: 0L,
                    updatedAt = metaString(v.metadata, "updatedAt")?.toLongOrNull() ?: now()
                )
            )
        }
    }

    override suspend fun delete(namespace: String, memoryId: String) = withContext(Dispatchers.IO) {
        val (code, _) = handleCall {
            dataPlane(
                "/vectors/delete", namespace,
                json.encodeToString(DeleteIds.serializer(), DeleteIds(ids = listOf(memoryId), namespace = ns(namespace)))
            )
        }
        if (code !in 200..299 && code != 404) {
            throw VectorStoreException(code, FailureCategory.SERVER, "delete failed HTTP $code")
        }
        Unit
    }

    override suspend fun deleteByFilter(namespace: String, filter: MemoryFilter): Int = withContext(Dispatchers.IO) {
        val f = pineconeFilter(filter, allowDeleteAll = true)
        // deleteAll requires NO filter field; a filtered delete requires no deleteAll.
        val payload: String = if (f == null || (f as? JsonObject)?.isEmpty() == true) {
            json.encodeToString(DeleteAll.serializer(), DeleteAll(namespace = ns(namespace), deleteAllFlag = true))
        } else {
            json.encodeToString(DeleteByFilterD.serializer(), DeleteByFilterD(filter = f, namespace = ns(namespace)))
        }
        val (code, _) = handleCall { dataPlane("/vectors/delete", namespace, payload) }
        if (code !in 200..299) {
            throw VectorStoreException(code, FailureCategory.SERVER, "deleteByFilter HTTP $code")
        }
        -1 // Pinecone does not report a count for filtered deletes
    }

    suspend fun healthCheck(): ProviderStatusDto {
        return try {
            val (code, _) = call(
                Request.Builder().url("${host()}/vectors/fetch?ids=probe")
                    .header("Api-Key", apiKey()).header("X-Pinecone-Api-Version", API_VERSION).get().build()
            )
            ProviderStatusDto(storeId, code in 200..299, code, "pinecone probe")
        } catch (e: Exception) {
            ProviderStatusDto(storeId, false, null, SecretRedactor.redact(e.message))
        }
    }

    internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val API_VERSION = "2025-01"
        const val DEFAULT_INDEX_NAME = "jarvis-memory"
        const val BASE_BACKOFF_MS = 400L
        const val MAX_BACKOFF_MS = 8_000L
        const val MAX_RETRIES = 2
    }
}

/** Lightweight status DTO for dashboards (no provider-layer dependency cycle). */
data class ProviderStatusDto(
    val storeId: String,
    val reachable: Boolean,
    val httpCode: Int?,
    val detail: String
)

@Serializable
internal data class UpsertRequest(val vectors: List<PineconeItem>, val namespace: String)

@Serializable
internal data class PineconeItem(
    val id: String,
    val values: List<Float>,
    val metadata: JsonObject
)

@Serializable
internal data class UpsertResponse(@SerialName("upsertedCount") val upsertedCount: Int = 0)

@Serializable
internal data class QueryRequest(
    val namespace: String,
    val topK: Int,
    val vector: List<Float>,
    val filter: JsonElement? = null,
    @SerialName("includeMetadata") val includeMetadata: Boolean = true
)

@Serializable
internal data class QueryMatch(
    val id: String? = null,
    val score: Double? = null,
    val metadata: Map<String, JsonElement>? = null
)

@Serializable
internal data class QueryResponse(val matches: List<QueryMatch> = emptyList())

@Serializable
internal data class FetchVector(
    val id: String? = null,
    val values: List<Float>? = null,
    val metadata: Map<String, JsonElement>? = null
)

@Serializable
internal data class FetchResponse(val vectors: Map<String, FetchVector> = emptyMap())

@Serializable
internal data class DeleteIds(val ids: List<String>, val namespace: String)

@Serializable
internal data class DeleteAll(
    val namespace: String,
    @SerialName("deleteAll") val deleteAllFlag: Boolean = true
)

@Serializable
internal data class DeleteByFilterD(
    val filter: JsonElement,
    val namespace: String
)

// ---- control plane ----

@Serializable
internal data class IndexList(val indexes: List<IndexDescription> = emptyList())

@Serializable
internal data class IndexDescription(
    val name: String = "",
    val host: String = "",
    val dimension: Int = 0,
    val metric: String = "",
    val status: IndexStatus? = null
)

@Serializable
internal data class IndexStatus(val ready: Boolean = false, val state: String = "")
