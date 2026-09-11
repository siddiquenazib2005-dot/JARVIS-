package com.jarvis.ai.memory.vector

import com.jarvis.ai.memory.KeyValueStore
import com.jarvis.ai.provider.Jsons
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.sqrt

/*
 * ON-DEVICE MEMORY (brain step 5)
 *
 * Why this file exists:
 *
 * Until now long-term memory had two hard dependencies on the network --
 * MistralEmbeddingProvider needed a key to turn text into a vector, and the
 * only durable stores were Pinecone/Qdrant. The local fallback was
 * InMemoryVectorStore, which lives in RAM and is therefore erased the moment
 * Android kills the process. Net effect: with no cloud keys AURIX remembered
 * nothing across app restarts.
 *
 * ChatGPT-style memory has to survive a restart and cost nothing, so both
 * dependencies get a local implementation here:
 *
 *  - [LocalEmbeddingProvider] turns text into a vector with pure arithmetic.
 *  - [PersistentVectorStore] keeps records on disk through KeyValueStore.
 *  - [FallbackEmbeddingProvider] prefers the real (better) cloud embedder and
 *    silently drops to the local one when there is no key or no network.
 *
 * Honest limitation, stated up front: local vectors are LEXICAL, not semantic.
 * "my bike is red" will match "what colour is my bike" through shared words,
 * but it will NOT match "what does my two-wheeler look like". Cloud embeddings
 * handle that; this layer is the floor, not the ceiling.
 */

/**
 * Deterministic on-device embedder: hashed bag of words plus character
 * trigrams, L2-normalised.
 *
 * Trigrams are what make it usable in practice rather than just in theory --
 * they give partial credit for typos, Hinglish spelling drift and inflections
 * ("bhai"/"bhaiya", "mummy"/"mumy"), which whole-word hashing alone cannot do.
 *
 * Deliberately NOT HashEmbeddingProvider: that one is 64 dims, unnormalised,
 * word-only and documented as test-only. Collisions at 64 dims make unrelated
 * memories look similar, which is worse than forgetting.
 */
class LocalEmbeddingProvider(private val dimensions: Int = 256) : EmbeddingProvider {

    override val providerId = "on-device"

    override suspend fun embed(texts: List<String>): List<List<Float>> =
        texts.map { embedOne(it) }

    private fun embedOne(text: String): List<Float> {
        val vec = FloatArray(dimensions)
        val clean = text.lowercase().trim()
        if (clean.isEmpty()) return vec.toList()

        val tokens = clean.split(TOKEN_SPLIT).filter { it.isNotBlank() && it !in STOPWORDS }

        // Whole words carry the most meaning, so they get the largest weight.
        tokens.forEach { token ->
            bump(vec, token.hashCode(), WORD_WEIGHT)
        }

        // Character trigrams add fuzzy tolerance.
        tokens.forEach { token ->
            val padded = " $token "
            if (padded.length >= 3) {
                for (i in 0..padded.length - 3) {
                    bump(vec, padded.substring(i, i + 3).hashCode(), TRIGRAM_WEIGHT)
                }
            }
        }

        // L2 normalise so cosine similarity is not skewed by text length: a
        // one-line fact and a long paragraph must stay comparable.
        var norm = 0f
        for (v in vec) norm += v * v
        if (norm <= 0f) return vec.toList()
        val inv = 1f / sqrt(norm)
        for (i in vec.indices) vec[i] = vec[i] * inv
        return vec.toList()
    }

    private fun bump(vec: FloatArray, hash: Int, weight: Float) {
        val h = hash.toLong()
        val index = ((h % dimensions + dimensions) % dimensions).toInt()
        // Sign from a different bit range keeps unrelated features from always
        // adding up, which is what keeps collisions from faking similarity.
        val sign = if ((h shr 17) and 1L == 0L) 1f else -1f
        vec[index] += weight * sign
    }

    companion object {
        private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private const val WORD_WEIGHT = 1.0f
        private const val TRIGRAM_WEIGHT = 0.35f

        /**
         * Tiny stoplist only. Aggressive stopword removal destroys short
         * memories such as "i am from patna", so this stays minimal and
         * covers both English and common Hinglish filler.
         */
        private val STOPWORDS = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "to", "of",
            "and", "or", "that", "this", "it", "in", "on", "at", "for",
            "hai", "hain", "tha", "thi", "ka", "ke", "ki", "ko", "se", "me"
        )
    }
}

/**
 * Uses [primary] when it works, [fallback] when it does not.
 *
 * Two rules that matter:
 *  1. Never throw. A memory feature must not be able to break a chat turn.
 *  2. Never mix vector spaces. A cloud vector and a local vector are not
 *     comparable, so the active space is recorded and exposed via
 *     [activeProviderId]; the store namespaces records by it, which stops
 *     stale cloud vectors from being compared against local ones and
 *     returning nonsense.
 */
class FallbackEmbeddingProvider(
    private val primary: EmbeddingProvider,
    private val fallback: EmbeddingProvider = LocalEmbeddingProvider()
) : EmbeddingProvider {

    override val providerId = "fallback"

    @Volatile
    var activeProviderId: String = fallback.providerId
        private set

    override suspend fun embed(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) return emptyList()
        return try {
            val vectors = primary.embed(texts)
            if (vectors.size != texts.size || vectors.any { it.isEmpty() }) {
                throw IllegalStateException("primary embedder returned nothing usable")
            }
            activeProviderId = primary.providerId
            vectors
        } catch (e: Exception) {
            activeProviderId = fallback.providerId
            fallback.embed(texts)
        }
    }
}

/**
 * Durable local vector store backed by [KeyValueStore], so on Android it
 * inherits SecureKvStore's encryption -- memories are personal data and must
 * not sit in plaintext prefs.
 *
 * Records are held per namespace as one JSON document. That is a conscious
 * trade: rewriting a namespace on every write is fine at the few-hundred-
 * record scale enforced by [MAX_RECORDS_PER_NAMESPACE], and it avoids shipping
 * a database migration for a feature this small. If memory ever grows past
 * that, this class is the only thing that needs replacing.
 *
 * Eviction keeps the most important and most recent memories: score =
 * importance, ties broken by updatedAt. A fact the user explicitly asked to be
 * remembered therefore outlives idle chatter.
 */
class PersistentVectorStore(
    private val kv: KeyValueStore,
    private val maxRecords: Int = MAX_RECORDS_PER_NAMESPACE
) : VectorStore {

    override val storeId = "on-device"

    private val lock = Any()

    override suspend fun upsert(namespace: String, record: VectorRecord) {
        synchronized(lock) {
            val records = load(namespace).toMutableList()
            records.removeAll { it.memoryId == record.memoryId }
            records += record
            val trimmed = if (records.size <= maxRecords) {
                records
            } else {
                records
                    .sortedWith(
                        compareByDescending<VectorRecord> { it.metadata.importance }
                            .thenByDescending { it.metadata.updatedAt }
                    )
                    .take(maxRecords)
            }
            save(namespace, trimmed)
        }
    }

    override suspend fun search(
        namespace: String,
        queryEmbedding: List<Float>,
        topK: Int,
        filter: MemoryFilter?
    ): List<ScoredMemory> {
        if (queryEmbedding.isEmpty() || topK <= 0) return emptyList()
        val records = synchronized(lock) { load(namespace) }
        return records.asSequence()
            .filter { accepts(it.metadata, filter) }
            // Dimension guard: a namespace can contain vectors written by a
            // different embedder after the user adds or loses an API key.
            // cosineSimilarity returns 0 for mismatched sizes, so these are
            // dropped rather than ranked as irrelevant noise.
            .filter { it.embedding.size == queryEmbedding.size }
            .map { ScoredMemory(it, cosineSimilarity(queryEmbedding, it.embedding)) }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    override suspend fun get(namespace: String, memoryId: String): VectorRecord? =
        synchronized(lock) { load(namespace).firstOrNull { it.memoryId == memoryId } }

    override suspend fun delete(namespace: String, memoryId: String) {
        synchronized(lock) {
            val records = load(namespace).filterNot { it.memoryId == memoryId }
            save(namespace, records)
        }
    }

    override suspend fun deleteByFilter(namespace: String, filter: MemoryFilter): Int =
        synchronized(lock) {
            val records = load(namespace)
            val keep = records.filterNot { accepts(it.metadata, filter) }
            save(namespace, keep)
            records.size - keep.size
        }

    /** Total stored memories, for the settings screen and for tests. */
    fun count(namespace: String): Int = synchronized(lock) { load(namespace).size }

    // ------------------------------------------------------------------
    // Filtering (local copy: VectorModels keeps its matcher private)
    // ------------------------------------------------------------------

    private fun accepts(metadata: MemoryMetadata, filter: MemoryFilter?): Boolean {
        if (filter == null) return true
        filter.types?.let { if (metadata.memoryType !in it) return false }
        filter.projectId?.let { if (metadata.projectId != it) return false }
        filter.sessionId?.let { if (metadata.sessionId != it) return false }
        filter.minImportance?.let { if (metadata.importance < it) return false }
        filter.anyTag?.let { if (it !in metadata.tags) return false }
        return true
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private fun key(namespace: String) = KEY_PREFIX + namespace

    private fun load(namespace: String): List<VectorRecord> {
        val raw = kv.getString(key(namespace)) ?: return emptyList()
        // Corrupt or partially written JSON must degrade to "no memories",
        // never to a crash on app start.
        val parsed = runCatching { Jsons.lenient.parseToJsonElement(raw).jsonArray }
            .getOrNull() ?: return emptyList()
        return parsed.mapNotNull { element -> decode(element as? JsonObject ?: return@mapNotNull null) }
    }

    private fun save(namespace: String, records: List<VectorRecord>) {
        if (records.isEmpty()) {
            kv.remove(key(namespace))
            return
        }
        val json = buildJsonArray {
            records.forEach { record ->
                addJsonObject {
                    put("id", record.memoryId)
                    put("content", record.content)
                    putJsonArray("embedding") {
                        record.embedding.forEach { add(it) }
                    }
                    putJsonObject("meta") {
                        put("type", record.metadata.memoryType.name)
                        record.metadata.projectId?.let { put("project", it) }
                        record.metadata.sessionId?.let { put("session", it) }
                        put("importance", record.metadata.importance)
                        put("source", record.metadata.source)
                        putJsonArray("tags") { record.metadata.tags.forEach { add(it) } }
                        put("createdAt", record.metadata.createdAt)
                        put("updatedAt", record.metadata.updatedAt)
                    }
                }
            }
        }
        kv.putString(key(namespace), json.toString())
    }

    private fun decode(obj: JsonObject): VectorRecord? {
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        val content = obj["content"]?.jsonPrimitive?.content ?: return null
        val embedding = (obj["embedding"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.content.toFloatOrNull() }
            ?: emptyList()
        val meta = obj["meta"] as? JsonObject
        val typeName = meta?.get("type")?.jsonPrimitive?.content
        val type = MemoryType.entries.firstOrNull { it.name == typeName } ?: MemoryType.SEMANTIC
        val now = System.currentTimeMillis()
        return VectorRecord(
            memoryId = id,
            content = content,
            embedding = embedding,
            metadata = MemoryMetadata(
                memoryType = type,
                projectId = meta?.get("project")?.jsonPrimitive?.content,
                sessionId = meta?.get("session")?.jsonPrimitive?.content,
                importance = meta?.get("importance")?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.5f,
                source = meta?.get("source")?.jsonPrimitive?.content ?: "conversation",
                tags = (meta?.get("tags") as? JsonArray)
                    ?.map { it.jsonPrimitive.content }
                    ?: emptyList(),
                createdAt = meta?.get("createdAt")?.jsonPrimitive?.content?.toLongOrNull() ?: now,
                updatedAt = meta?.get("updatedAt")?.jsonPrimitive?.content?.toLongOrNull() ?: now
            )
        )
    }

    companion object {
        private const val KEY_PREFIX = "vmem:"
        const val MAX_RECORDS_PER_NAMESPACE = 400
    }
}
