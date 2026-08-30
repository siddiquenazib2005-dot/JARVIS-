package com.jarvis.ai.memory.vector

import com.jarvis.ai.provider.SecretsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class VectorMemoryConfig(
    val topK: Int = 5,
    val similarityThreshold: Float = 0.35f,
    /** Approximate LLM context budget for memories (chars; ~4 chars ≈ 1 token). */
    val maxMemoryChars: Int = 1200,
    val importanceWeight: Float = 0.3f,
    val duplicateThreshold: Float = MemoryClassifier.DUPLICATE_THRESHOLD,
    /** How long a failing remote store is skipped before being retried. */
    val storeCooldownMs: Long = 60_000L
)

/**
 * Orchestrates the full vector memory pipeline:
 *
 *   content → classifier → embedder → duplicate check → vector store
 *   query   → embedder → similarity search → metadata filter → rank → top-K
 *
 * Storage resilience (priority order, re-evaluated per call):
 *   1. Pinecone  (PINECONE_API_KEY configured)
 *   2. Qdrant    (QDRANT_URL + key configured)
 *   3. InMemory  (always available terminal fallback)
 *
 * A remote store that throws is put on a short cooldown; calls within that
 * window skip straight to the next store in the chain, and the store recovers
 * automatically once the cooldown elapses.
 *
 * Security invariants:
 *  - secret-like content is never stored (classifier blocks it)
 *  - embeddings are never logged
 *  - user-controlled deletion is exposed via forget()/forgetFilter()/wipeAll()
 */
class VectorMemoryManager(
    private val embedder: EmbeddingProvider,
    private val secrets: SecretsSource,
    private val config: VectorMemoryConfig = VectorMemoryConfig(),
    private val now: () -> Long = System::currentTimeMillis,
    private val transport: com.jarvis.ai.provider.HttpTransport = com.jarvis.ai.provider.HttpTransports.default
) {

    private val classifier = MemoryClassifier()

    private val pineconeStore: PineconeVectorStore by lazy { PineconeVectorStore(secrets, transport) }
    private val qdrantStore: QdrantVectorStore by lazy { QdrantVectorStore(secrets, transport) }

    /** Shared local fallback so state persists across calls without a remote store. */
    val localStore: InMemoryVectorStore = InMemoryVectorStore()

    /** storeId → epoch-ms until which the store is skipped after a failure. */
    private val cooldowns = ConcurrentHashMap<String, Long>()

    /** Ordered candidate list honouring configuration and current cooldowns. */
    internal fun candidateStores(): List<VectorStore> {
        val candidates = mutableListOf<Pair<String, VectorStore>>()
        if (pineconeStore.isConfigured()) candidates += "pinecone" to pineconeStore
        if (qdrantStore.isConfigured()) candidates += "qdrant" to qdrantStore
        candidates += "inmemory" to localStore
        val ts = now()
        return candidates.filter { (id, _) -> (cooldowns[id] ?: 0L) <= ts }.map { it.second }
    }

    /** The store the last successful operation used — surfaced for health reporting. */
    @Volatile
    var activeStoreId: String = "unknown"
        private set

    private suspend fun <T> executeAcrossStores(
        op: String,
        block: suspend (VectorStore) -> T,
        default: T
    ): T = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (store in candidateStores()) {
            try {
                val result = block(store)
                activeStoreId = store.storeId
                return@withContext result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (store.storeId != "inmemory") {
                    cooldowns[store.storeId] = now() + config.storeCooldownMs
                }
            }
        }
        default
    }

    suspend fun remember(
        content: String,
        type: MemoryType = MemoryType.SEMANTIC,
        projectId: String? = null,
        sessionId: String? = null,
        tags: List<String> = emptyList(),
        source: String = "conversation",
        importanceOverride: Float? = null,
        fallbackStore: VectorStore = localStore
    ): MemoryWriteResult = withContext(Dispatchers.IO) {
        val embedding = embedder.embed(listOf(content)).firstOrNull()
            ?: return@withContext MemoryWriteResult(WriteStatus.IGNORED, reason = "embedding failed")

        val store = candidateStores().firstOrNull() ?: fallbackStore
        val existingTop = runCatching {
            store.search(namespace(projectId), embedding, topK = 1).firstOrNull()
        }.getOrNull()

        val verdict = classifier.classify(content, type, existingTop)
        when (verdict.decision) {
            MemoryDecision.IRRELEVANT ->
                return@withContext MemoryWriteResult(WriteStatus.IGNORED, reason = verdict.reason)
            MemoryDecision.DUPLICATE ->
                return@withContext MemoryWriteResult(
                    WriteStatus.DUPLICATE,
                    memoryId = existingTop?.record?.memoryId,
                    reason = verdict.reason
                )
            MemoryDecision.TEMPORARY_ONLY ->
                return@withContext MemoryWriteResult(WriteStatus.IGNORED, reason = verdict.reason)
            MemoryDecision.STORE -> Unit
        }

        val ts = now()
        val record = VectorRecord(
            memoryId = UUID.randomUUID().toString(),
            content = content.trim(),
            embedding = embedding,
            metadata = MemoryMetadata(
                memoryType = type,
                projectId = projectId,
                sessionId = sessionId,
                importance = importanceOverride ?: verdict.importance,
                source = source,
                tags = tags,
                createdAt = ts,
                updatedAt = ts
            )
        )
        // Persist through the chain: first healthy store wins, failures cool down and fail over.
        var persisted = false
        for (candidate in candidateStores()) {
            try {
                candidate.upsert(namespace(projectId), record)
                persisted = true
                activeStoreId = candidate.storeId
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (candidate.storeId != "inmemory") cooldowns[candidate.storeId] = now() + config.storeCooldownMs
            }
        }
        if (!persisted) {
            return@withContext MemoryWriteResult(WriteStatus.IGNORED, reason = "all stores unavailable")
        }
        MemoryWriteResult(WriteStatus.STORED, memoryId = record.memoryId)
    }

    /** Semantic retrieval with filtering, importance-weighted ranking and budget cap. */
    suspend fun recall(
        query: String,
        projectId: String? = null,
        filter: MemoryFilter = MemoryFilter(),
        fallbackStore: VectorStore = localStore
    ): List<ScoredMemory> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val queryVector = embedder.embed(listOf(query)).firstOrNull()
            ?: return@withContext emptyList()

        val hits = executeAcrossStores("search", { store ->
            store.search(namespace(projectId), queryVector, topK = config.topK * 2, filter = filter)
        }, emptyList())

        hits.filter { it.score >= config.similarityThreshold }
            .sortedByDescending { scored ->
                scored.score + scored.record.metadata.importance * config.importanceWeight
            }
            .take(config.topK)
            .runningFold(0 to emptyList<ScoredMemory>()) { acc, mem ->
                val used = acc.first + mem.record.content.length
                if (used > config.maxMemoryChars) acc else used to acc.second + mem
            }
            .lastOrNull()?.second.orEmpty()
    }

    /** Ranked memories formatted for LLM context injection. */
    suspend fun contextBlock(
        query: String,
        projectId: String? = null,
        fallbackStore: VectorStore = localStore
    ): String {
        val hits = recall(query, projectId, MemoryFilter(), fallbackStore)
        if (hits.isEmpty()) return ""
        return "Relevant memory:\n" + hits.joinToString("\n") { m ->
            "- [${m.record.metadata.memoryType.name.lowercase()}] ${m.record.content.take(220)}"
        }
    }

    suspend fun updateMemory(
        memoryId: String,
        newContent: String,
        projectId: String? = null,
        fallbackStore: VectorStore = localStore
    ): Boolean = withContext(Dispatchers.IO) {
        val ns = namespace(projectId)
        var existing: VectorRecord? = null
        for (store in candidateStores()) {
            existing = runCatching { store.get(ns, memoryId) }.getOrNull()
            if (existing != null) break
        }
        val target = existing ?: return@withContext false
        val embedding = embedder.embed(listOf(newContent)).firstOrNull()
            ?: return@withContext false
        var ok = false
        for (store in candidateStores()) {
            try {
                store.upsert(
                    ns,
                    target.copy(
                        content = newContent.trim(),
                        embedding = embedding,
                        metadata = target.metadata.copy(updatedAt = now())
                    )
                )
                ok = true
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (store.storeId != "inmemory") cooldowns[store.storeId] = now() + config.storeCooldownMs
            }
        }
        ok
    }

    suspend fun forget(
        memoryId: String,
        projectId: String? = null,
        fallbackStore: VectorStore = localStore
    ) = withContext(Dispatchers.IO) {
        val ns = namespace(projectId)
        executeAcrossStores("delete", { it.delete(ns, memoryId) }, Unit)
    }

    /** User-controlled bulk deletion scoped by project/type — privacy first. */
    suspend fun forgetFilter(
        filter: MemoryFilter,
        projectId: String? = null,
        fallbackStore: VectorStore = localStore
    ): Int = withContext(Dispatchers.IO) {
        val ns = namespace(projectId)
        executeAcrossStores("deleteByFilter", { it.deleteByFilter(ns, filter) }, 0).let { result ->
            // Remote filtered deletes report -1 (no count); treat as success.
            result
        }
    }

    suspend fun wipeAll(
        projectId: String? = null,
        fallbackStore: VectorStore = localStore
    ): Int = forgetFilter(MemoryFilter(), projectId, fallbackStore)

    private fun namespace(projectId: String?): String =
        projectId?.takeIf { it.isNotBlank() }?.let { "project_$it" } ?: DEFAULT_NAMESPACE

    companion object {
        const val DEFAULT_NAMESPACE = "jarvis_global"
    }
}
