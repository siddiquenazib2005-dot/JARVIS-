package com.jarvis.ai.memory.vector

import com.jarvis.ai.provider.FailureCategory
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** Structured vector-store failure carrying the HTTP code and failure category. */
class VectorStoreException(
    val httpCode: Int?,
    val category: FailureCategory,
    message: String
) : IOException(message)

/** What kind of information this memory represents. */
enum class MemoryType {
    EPISODIC, SEMANTIC, PROJECT, PREFERENCE, FACT, PROCEDURAL, CONVERSATION_SUMMARY
}

/** Filterable metadata attached to every vector record. */
data class MemoryMetadata(
    val memoryType: MemoryType,
    val projectId: String? = null,
    val sessionId: String? = null,
    val importance: Float = 0.5f,
    val source: String = "conversation",
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Full stored record: content + embedding + metadata. */
data class VectorRecord(
    val memoryId: String,
    val content: String,
    val embedding: List<Float>,
    val metadata: MemoryMetadata
)

data class MemoryFilter(
    val types: Set<MemoryType>? = null,
    val projectId: String? = null,
    val sessionId: String? = null,
    val minImportance: Float? = null,
    val anyTag: String? = null
)

data class ScoredMemory(val record: VectorRecord, val score: Float)

enum class WriteStatus { STORED, DUPLICATE, IGNORED }

data class MemoryWriteResult(
    val status: WriteStatus,
    val memoryId: String? = null,
    val reason: String? = null
)

fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var dot = 0f; var na = 0f; var nb = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
    }
    if (na == 0f || nb == 0f) return 0f
    return (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1f, 1f)
}

/**
 * Storage seam — AURIX is never permanently coupled to one vector database.
 * Implementations: InMemoryVectorStore (local fallback), QdrantVectorStore (cloud),
 * Pinecone/pgvector reserved for later.
 */
interface VectorStore {
    val storeId: String

    suspend fun upsert(namespace: String, record: VectorRecord)

    suspend fun search(
        namespace: String,
        queryEmbedding: List<Float>,
        topK: Int,
        filter: MemoryFilter? = null
    ): List<ScoredMemory>

    suspend fun get(namespace: String, memoryId: String): VectorRecord?

    suspend fun delete(namespace: String, memoryId: String)

    /** Returns count actually deleted. */
    suspend fun deleteByFilter(namespace: String, filter: MemoryFilter): Int
}

/**
 * Local zero-dependency implementation. Doubles as the offline fallback when no
 * remote vector DB is configured, and as the reference semantics for all others.
 */
class InMemoryVectorStore : VectorStore {

    override val storeId = "inmemory"

    private val lock = Any()
    private val namespaces = HashMap<String, LinkedHashMap<String, VectorRecord>>()

    override suspend fun upsert(namespace: String, record: VectorRecord) {
        synchronized(lock) {
            namespaces.getOrPut(namespace) { LinkedHashMap() }[record.memoryId] = record
        }
    }

    override suspend fun search(
        namespace: String,
        queryEmbedding: List<Float>,
        topK: Int,
        filter: MemoryFilter?
    ): List<ScoredMemory> {
        val records: List<VectorRecord> = synchronized(lock) {
            namespaces[namespace].orEmpty().values.toList()
        }
        return records.asSequence()
            .filter { matches(it.metadata, filter) }
            .map { ScoredMemory(it, cosineSimilarity(queryEmbedding, it.embedding)) }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    override suspend fun get(namespace: String, memoryId: String): VectorRecord? =
        synchronized(lock) { namespaces[namespace]?.get(memoryId) }

    override suspend fun delete(namespace: String, memoryId: String) {
        synchronized(lock) { namespaces[namespace]?.remove(memoryId) }
    }

    override suspend fun deleteByFilter(namespace: String, filter: MemoryFilter): Int =
        synchronized(lock) {
            val bucket = namespaces[namespace] ?: return 0
            val toDelete = bucket.values.filter { matches(it.metadata, filter) }
            toDelete.forEach { bucket.remove(it.memoryId) }
            toDelete.size
        }

    private fun matches(meta: MemoryMetadata, filter: MemoryFilter?): Boolean {
        if (filter == null) return true
        if (filter.types != null && meta.memoryType !in filter.types) return false
        if (filter.projectId != null && meta.projectId != filter.projectId) return false
        if (filter.sessionId != null && meta.sessionId != filter.sessionId) return false
        if (filter.minImportance != null && meta.importance < filter.minImportance!!) return false
        if (filter.anyTag != null && meta.tags.none { it.equals(filter.anyTag, true) }) return false
        return true
    }
}

// ---------------------------------------------------------------------------
// Qdrant REST DTOs
// ---------------------------------------------------------------------------

@Serializable
internal data class QdrantPoint(
    val id: String,
    val vector: List<Float>,
    val payload: QdrantPayload
)

@Serializable
internal data class QdrantPayload(
    @SerialName("content") val content: String = "",
    @SerialName("memoryType") val memoryType: String = "",
    @SerialName("projectId") val projectId: String? = null,
    @SerialName("sessionId") val sessionId: String? = null,
    @SerialName("importance") val importance: Float = 0.5f,
    @SerialName("source") val source: String = "",
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("createdAt") val createdAt: Long = 0L,
    @SerialName("updatedAt") val updatedAt: Long = 0L
)

@Serializable
internal data class QdrantUpsertRequest(val points: List<QdrantPoint>)

@Serializable
internal data class QdrantSearchRequest(
    val vector: List<Float>,
    val limit: Int,
    val with_payload: Boolean = true,
    val filter: QdrantFilter? = null
)

@Serializable
internal data class QdrantFilter(val must: List<QdrantCondition> = emptyList())

@Serializable
internal data class QdrantCondition(val key: String, val match: QdrantMatch)

/** `match` supports either a single value or an `any` list (match-any semantics). */
@Serializable
internal data class QdrantMatch(
    val value: kotlinx.serialization.json.JsonElement? = null,
    val any: List<kotlinx.serialization.json.JsonElement>? = null
)

@Serializable
internal data class QdrantSearchHit(
    val id: String? = null,
    val score: Double? = null,
    val payload: QdrantPayload? = null
)

@Serializable
internal data class QdrantSearchResponse(val result: List<QdrantSearchHit> = emptyList())

@Serializable
internal data class QdrantCreateCollection(
    val vectors: QdrantVectorsSpec
)

@Serializable
internal data class QdrantVectorsSpec(
    val size: Int,
    val distance: String = "Cosine"
)

@Serializable
internal data class QdrantDeleteRequest(val points: List<String>)

// ---- points/get (fetch with payload) ----

@Serializable
internal data class QdrantGetRequest(
    val ids: List<String>,
    @SerialName("with_payload") val withPayload: Boolean = true,
    @SerialName("with_vector") val withVector: Boolean = false
)

@Serializable
internal data class QdrantGetResponse(
    val result: List<QdrantGetPoint> = emptyList()
)

@Serializable
internal data class QdrantGetPoint(
    val id: String? = null,
    val payload: QdrantPayload? = null
)

// ---- collection info (dimension validation) ----

@Serializable
internal data class QdrantCollectionInfo(val config: QdrantCollectionConfig? = null)

@Serializable
internal data class QdrantCollectionConfig(val params: QdrantCollectionParams? = null)

@Serializable
internal data class QdrantCollectionParams(val vectors: QdrantVectorsSpec? = null)
