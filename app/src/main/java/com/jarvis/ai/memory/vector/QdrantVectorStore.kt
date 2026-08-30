package com.jarvis.ai.memory.vector

import com.jarvis.ai.provider.FailureCategory
import com.jarvis.ai.provider.HttpTransport
import com.jarvis.ai.provider.HttpTransports
import kotlinx.serialization.json.Json
import com.jarvis.ai.provider.SecretRedactor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Qdrant Cloud / self-hosted REST implementation.
 *
 * Requires:
 *  - QDRANT_API_KEY secret (already provisioned by the user)
 *  - QDRANT_URL    secret (cluster URL, e.g. https://xyz.eu-central.aws.cloud.qdrant.io)
 *
 * When QDRANT_URL is absent the MemoryManager automatically falls back to
 * InMemoryVectorStore — the app stays fully functional either way.
 *
 * Transport policy: transient IO failures are retried with exponential backoff;
 * HTTP error codes are mapped to structured [VectorStoreException] categories so
 * the resilient store chain can decide whether failover is worthwhile.
 */
class QdrantVectorStore(
    private val secrets: com.jarvis.ai.provider.SecretsSource,
    private val transport: HttpTransport = HttpTransports.default,
    private val now: () -> Long = System::currentTimeMillis
) : VectorStore {

    override val storeId = "qdrant"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonContentType = "application/json".toMediaType()

    fun isConfigured(): Boolean =
        secrets.get("QDRANT_URL#1")?.trim()?.startsWith("http") == true &&
            !secrets.get("QDRANT_API_KEY#1").isNullOrBlank()

    private fun baseUrl(): String =
        secrets.get("QDRANT_URL#1")?.trim()?.trimEnd('/')
            ?.takeIf { it.startsWith("http") }
            ?: throw VectorStoreException(null, FailureCategory.UNKNOWN, "qdrant: QDRANT_URL not configured")

    private fun apiKey(): String =
        secrets.get("QDRANT_API_KEY#1") ?: throw VectorStoreException(null, FailureCategory.AUTH, "qdrant: key missing")

    @Volatile
    private var ensuredDimensions: Int? = null

    /** Validates that the remote collection matches the embedding dimension in use. */
    suspend fun collectionDimensions(): Int? {
        if (!isConfigured()) return null
        val request = Request.Builder()
            .url("${baseUrl()}/collections/$COLLECTION")
            .header("api-key", apiKey())
            .get()
            .build()
        return executeWithRetry(request).let { (code, body) ->
            if (code != 200) return null
            runCatching {
                json.decodeFromString(QdrantCollectionInfo.serializer(), body).config?.params?.vectors?.size
            }.getOrNull()
        }
    }

    private suspend fun ensureCollection(dims: Int) {
        if (ensuredDimensions == dims) return
        val existing = collectionDimensions()
        if (existing != null) {
            require(existing == dims) {
                "qdrant: collection '$COLLECTION' has dimension $existing but embeddings are $dims"
            }
            ensuredDimensions = existing
            return
        }
        val body = json.encodeToString(
            QdrantCreateCollection.serializer(),
            QdrantCreateCollection(QdrantVectorsSpec(size = dims))
        )
        val request = Request.Builder()
            .url("${baseUrl()}/collections/$COLLECTION?wait=true")
            .header("api-key", apiKey())
            .put(body.toRequestBody(jsonContentType))
            .build()
        executeWithRetry(request).let { (code, body) ->
            if (!code.isSuccess()) {
                // Some Qdrant versions answer 400 "already exists" — verify before failing.
                val existingNow = collectionDimensions()
                if (existingNow == null) {
                    throw VectorStoreException(
                        code, StoreHttpStatus.categoryFor(code),
                        "qdrant create-collection HTTP $code: ${SecretRedactor.redact(body.take(160))}"
                    )
                }
            }
        }
        ensuredDimensions = dims
    }

    private fun toPayload(record: VectorRecord): QdrantPayload = with(record.metadata) {
        QdrantPayload(
            content = record.content,
            memoryType = memoryType.name,
            projectId = projectId,
            sessionId = sessionId,
            importance = importance,
            source = source,
            tags = tags,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    override suspend fun upsert(namespace: String, record: VectorRecord) {
        ensureCollection(record.embedding.size)
        val point = QdrantPoint(
            id = record.memoryId,
            vector = record.embedding,
            payload = toPayload(record).copy(updatedAt = now())
        )
        val body = json.encodeToString(QdrantUpsertRequest.serializer(), QdrantUpsertRequest(listOf(point)))
        val request = Request.Builder()
            .url("${baseUrl()}/collections/$COLLECTION/points?wait=true")
            .header("api-key", apiKey())
            .post(body.toRequestBody(jsonContentType))
            .build()
        executeWithRetry(request).let { (code, _) ->
            if (!code.isSuccess()) {
                throw VectorStoreException(code, StoreHttpStatus.categoryFor(code), "qdrant upsert HTTP $code")
            }
        }
    }

    /**
     * Builds a native Qdrant filter. Type filters use `match.any` so ANY of the
     * requested types matches (the previous comma-joined single match was wrong).
     */
    internal fun qdrantFilter(filter: MemoryFilter?): QdrantFilter? {
        if (filter == null) return null
        val must = mutableListOf<QdrantCondition>()
        filter.types?.takeIf { it.isNotEmpty() }?.let { types ->
            must += QdrantCondition(
                key = "memoryType",
                match = QdrantMatch(any = types.map { JsonPrimitive(it.name) })
            )
        }
        filter.projectId?.let { pid ->
            must += QdrantCondition("projectId", QdrantMatch(value = JsonPrimitive(pid)))
        }
        filter.sessionId?.let { sid ->
            must += QdrantCondition("sessionId", QdrantMatch(value = JsonPrimitive(sid)))
        }
        return if (must.isEmpty()) null else QdrantFilter(must)
    }

    private fun fromPayload(id: String, score: Float, p: QdrantPayload): ScoredMemory {
        val type = runCatching { MemoryType.valueOf(p.memoryType) }
            .getOrDefault(MemoryType.SEMANTIC)
        return ScoredMemory(
            record = VectorRecord(
                memoryId = id,
                content = p.content,
                embedding = emptyList(),
                metadata = MemoryMetadata(
                    memoryType = type,
                    projectId = p.projectId,
                    sessionId = p.sessionId,
                    importance = p.importance,
                    source = p.source,
                    tags = p.tags,
                    createdAt = p.createdAt,
                    updatedAt = p.updatedAt
                )
            ),
            score = score
        )
    }

    override suspend fun search(
        namespace: String,
        queryEmbedding: List<Float>,
        topK: Int,
        filter: MemoryFilter?
    ): List<ScoredMemory> {
        ensureCollection(queryEmbedding.size)
        val body = json.encodeToString(
            QdrantSearchRequest.serializer(),
            QdrantSearchRequest(
                vector = queryEmbedding,
                limit = topK,
                filter = qdrantFilter(filter)
            )
        )
        val request = Request.Builder()
            .url("${baseUrl()}/collections/$COLLECTION/points/search")
            .header("api-key", apiKey())
            .post(body.toRequestBody(jsonContentType))
            .build()
        executeWithRetry(request).let { (code, respBody) ->
            if (!code.isSuccess()) {
                throw VectorStoreException(code, StoreHttpStatus.categoryFor(code), "qdrant search HTTP $code")
            }
            val decoded = json.decodeFromString(QdrantSearchResponse.serializer(), respBody)
            return decoded.result.mapNotNull { hit ->
                val payload = hit.payload ?: return@mapNotNull null
                fromPayload(hit.id.orEmpty(), hit.score?.toFloat() ?: 0f, payload)
            }
        }
    }

    /** Fetches one point via the dedicated /points/get endpoint with its payload. */
    override suspend fun get(namespace: String, memoryId: String): VectorRecord? {
        val body = json.encodeToString(
            QdrantGetRequest.serializer(),
            QdrantGetRequest(ids = listOf(memoryId), withPayload = true)
        )
        val request = Request.Builder()
            .url("${baseUrl()}/collections/$COLLECTION/points/get")
            .header("api-key", apiKey())
            .post(body.toRequestBody(jsonContentType))
            .build()
        executeWithRetry(request).let { (code, respBody) ->
            if (!code.isSuccess()) return null
            val decoded = runCatching {
                json.decodeFromString(QdrantGetResponse.serializer(), respBody)
            }.getOrNull() ?: return null
            val point = decoded.result.firstOrNull() ?: return null
            val payload = point.payload ?: return null
            return fromPayload(point.id ?: memoryId, 1f, payload).record
        }
    }

    override suspend fun delete(namespace: String, memoryId: String) {
        val body = json.encodeToString(
            QdrantDeleteRequest.serializer(), QdrantDeleteRequest(listOf(memoryId))
        )
        val request = Request.Builder()
            .url("${baseUrl()}/collections/$COLLECTION/points/delete?wait=true")
            .header("api-key", apiKey())
            .post(body.toRequestBody(jsonContentType))
            .build()
        executeWithRetry(request).let { (code, _) ->
            if (!code.isSuccess() && code != 404) {
                throw VectorStoreException(code, StoreHttpStatus.categoryFor(code), "qdrant delete HTTP $code")
            }
        }
    }

    override suspend fun deleteByFilter(namespace: String, filter: MemoryFilter): Int {
        // Empty filter means "delete everything in the namespace" for wipe semantics.
        val qfilter = qdrantFilter(filter) ?: QdrantFilter(must = emptyList())
        val body = json.encodeToString(
            QdrantFilterWrapper.serializer(),
            QdrantFilterWrapper(filter = qfilter)
        )
        val request = Request.Builder()
            .url("${baseUrl()}/collections/$COLLECTION/points/delete?wait=true")
            .header("api-key", apiKey())
            .post(body.toRequestBody(jsonContentType))
            .build()
        executeWithRetry(request).let { (code, _) ->
            if (!code.isSuccess()) {
                throw VectorStoreException(code, StoreHttpStatus.categoryFor(code), "qdrant delete-by-filter HTTP $code")
            }
        }
        // Qdrant returns operation status, not a count; caller re-checks if needed.
        return -1
    }

    /** Executes with bounded exponential-backoff retries for transient IO failures. */
    private suspend fun executeWithRetry(request: Request): Pair<Int, String> {
        var lastError: IOException? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                transport.execute(request).use { response ->
                    return response.code to (response.body?.string().orEmpty())
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_RETRIES) kotlinx.coroutines.delay(BASE_BACKOFF_MS shl attempt)
            }
        }
        throw VectorStoreException(
            null, FailureCategory.NETWORK,
            SecretRedactor.redact(lastError?.message ?: "network failure")
        )
    }

    private fun Int.isSuccess(): Boolean = this in 200..299

    @kotlinx.serialization.Serializable
    internal data class QdrantFilterWrapper(val filter: QdrantFilter)

    companion object {
        const val COLLECTION = "jarvis_memory"
        const val MAX_RETRIES = 2
        const val BASE_BACKOFF_MS = 400L
    }
}

/** Maps an HTTP status to the shared failure taxonomy used across vector stores. */
object StoreHttpStatus {
    fun categoryFor(code: Int): FailureCategory = when (code) {
        401, 403 -> FailureCategory.AUTH
        429 -> FailureCategory.RATE_LIMIT
        in 500..599 -> FailureCategory.SERVER
        else -> FailureCategory.UNKNOWN
    }
}
