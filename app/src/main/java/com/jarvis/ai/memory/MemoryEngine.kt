package com.jarvis.ai.memory

import android.content.Context
import com.jarvis.ai.data.local.SecureStore
import com.jarvis.ai.data.model.Message
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Minimal storage seam so memory logic is unit-testable on the JVM. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun contains(key: String): Boolean
}

/** Production binding over the existing encrypted store (AndroidKeyStore AES-GCM). */
class SecureKvStore(context: Context) : KeyValueStore {
    private val secure = SecureStore(context)
    override fun getString(key: String): String? = secure.get(key)
    override fun putString(key: String, value: String) = secure.put(key, value)
    override fun remove(key: String) = secure.remove(key)
    override fun contains(key: String): Boolean = secure.contains(key)
}

/** Test/dev binding. */
class InMemoryKv : KeyValueStore {
    private val map = linkedMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun contains(key: String): Boolean = map.containsKey(key)
}

/**
 * Layered memory core.
 *
 *  Working   → supplied by caller (current conversation list)
 *  Session   → ChatDb (already persistent; not duplicated here)
 *  Long-term → semantic facts        ("fact:<topic>")
 *  Semantic  → scored retrieval over facts (ContextBuilder)
 *  Project   → project-scoped notes   ("proj:<key>")
 *  Episodes  → capped event log       ("mem:episodes")
 *
 * User-controlled deletion is first-class: forgetFact / deleteAllUserData.
 */
class MemoryEngine(private val kv: KeyValueStore) {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------- Facts (semantic long-term) ----------------

    fun rememberFact(topic: String, content: String): Boolean {
        val key = topic.trim().lowercase()
        if (key.isBlank() || content.isBlank()) return false
        kv.putString(FACT_PREFIX + key, content.trim())
        indexAdd(IDX_FACTS, key)
        return true
    }

    fun recallFact(topic: String): String? =
        kv.getString(FACT_PREFIX + topic.trim().lowercase())

    fun forgetFact(topic: String): Boolean {
        val key = topic.trim().lowercase()
        val existed = kv.contains(FACT_PREFIX + key)
        kv.remove(FACT_PREFIX + key)
        indexRemove(IDX_FACTS, key)
        return existed
    }

    fun allFacts(): Map<String, String> =
        indexList(IDX_FACTS).mapNotNull { k ->
            kv.getString(FACT_PREFIX + k)?.let { k to it }
        }.toMap()

    // ---------------- Preferences ----------------

    fun setPreference(key: String, value: String) {
        kv.putString(PREF_PREFIX + key.trim().lowercase(), value)
        indexAdd(IDX_PREFS, key.trim().lowercase())
    }

    fun getPreference(key: String): String? = kv.getString(PREF_PREFIX + key.trim().lowercase())

    // ---------------- Project context ----------------

    fun setProjectContext(projectId: String, key: String, value: String) {
        kv.putString("$PROJ_PREFIX${projectId.trim().lowercase()}:$key", value)
        indexAdd(IDX_PROJECTS, "${projectId.trim().lowercase()}:$key")
    }

    fun getProjectContext(projectId: String, key: String): String? =
        kv.getString("$PROJ_PREFIX${projectId.trim().lowercase()}:$key")

    // ---------------- Episodic log ----------------

    fun recordEpisode(summary: String) {
        if (summary.isBlank()) return
        val current = episodes()
        val stamped = "[${System.currentTimeMillis()}] ${summary.take(400)}"
        val updated = (current + listOf(stamped)).takeLast(EPISODE_CAP)
        kv.putString(
            EPISODES_KEY,
            json.encodeToString(ListSerializer(String.serializer()), updated)
        )
    }

    fun recentEpisodes(count: Int): List<String> = episodes().takeLast(count)

    private fun episodes(): List<String> = runCatching {
        json.decodeFromString(
            ListSerializer(String.serializer()),
            kv.getString(EPISODES_KEY).orEmpty()
        )
    }.getOrDefault(emptyList())

    // ---------------- Privacy: full wipe ----------------

    fun deleteAllUserData(): Int {
        var removed = 0
        listOf(IDX_FACTS, IDX_PREFS, IDX_PROJECTS).forEach { idx ->
            indexList(idx).forEach { raw ->
                val key = when (idx) {
                    IDX_FACTS -> FACT_PREFIX + raw
                    IDX_PREFS -> PREF_PREFIX + raw
                    else -> "$PROJ_PREFIX$raw"
                }
                if (kv.contains(key)) { kv.remove(key); removed++ }
            }
            kv.remove(idx)
        }
        if (kv.contains(EPISODES_KEY)) { kv.remove(EPISODES_KEY); removed++ }
        return removed
    }

    // ---------------- index helpers ----------------

    private fun indexList(indexKey: String): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), kv.getString(indexKey).orEmpty())
    }.getOrDefault(emptyList())

    private fun indexAdd(indexKey: String, entry: String) {
        val updated = (indexList(indexKey).filter { it != entry } + entry).takeLast(INDEX_CAP)
        kv.putString(indexKey, json.encodeToString(ListSerializer(String.serializer()), updated))
    }

    private fun indexRemove(indexKey: String, entry: String) {
        val updated = indexList(indexKey).filter { it != entry }
        kv.putString(indexKey, json.encodeToString(ListSerializer(String.serializer()), updated))
    }

    companion object {
        private const val FACT_PREFIX = "fact:"
        private const val PREF_PREFIX = "pref:"
        private const val PROJ_PREFIX = "proj:"
        private const val EPISODES_KEY = "mem:episodes"
        private const val IDX_FACTS = "idx:facts"
        private const val IDX_PREFS = "idx:prefs"
        private const val IDX_PROJECTS = "idx:projects"
        private const val EPISODE_CAP = 50
        private const val INDEX_CAP = 300
    }
}

/**
 * Assembles a bounded LLM context instead of dumping entire history:
 *   system instructions + top-K relevant facts + project context +
 *   trimmed recent conversation + current request.
 */
class ContextBuilder(
    private val memory: MemoryEngine,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val recentMessageLimit: Int = DEFAULT_RECENT_LIMIT,
    private val maxFacts: Int = 3
) {

    data class Built(val messages: List<LlmMsg>, val includedFactCount: Int, val charBudgetUsed: Int)

    data class LlmMsg(val role: String, val content: String)

    fun build(
        systemInstructions: String,
        recentConversation: List<Message>,
        currentRequest: String,
        projectId: String? = null,
        toolResults: String = ""
    ): Built {
        val queryTerms = currentRequest.lowercase()
            .split(Regex("\\W+")).filter { it.length > 2 }.toSet()

        val scored = memory.allFacts()
            .mapNotNull { (topic, content) ->
                val hay = "$topic $content".lowercase()
                val score = queryTerms.count { hay.contains(it) }
                if (score > 0) Triple(score, topic, content) else null
            }
            .sortedByDescending { it.first }
            .take(maxFacts)

        var budget = maxChars
        val parts = mutableListOf<LlmMsg>()

        parts += LlmMsg("system", systemInstructions)

        if (scored.isNotEmpty()) {
            val factBlock = scored.joinToString("\n") { "- ${it.second}: ${it.third}" }
            parts += LlmMsg("system", "Known facts about the user:\n$factBlock")
            budget -= factBlock.length
        }

        if (toolResults.isNotBlank()) {
            val trimmedTool = toolResults.take((budget * 0.4).toInt().coerceAtLeast(200))
            parts += LlmMsg("system", "Relevant tool results:\n$trimmedTool")
            budget -= trimmedTool.length
        }

        if (projectId != null) {
            memory.getProjectContext(projectId, "summary")?.let { summary ->
                parts += LlmMsg("system", "Active project '$projectId':\n${summary.take(600)}")
                budget -= minOf(summary.length, 600)
            }
        }

        val recentTail = recentConversation.takeLast(recentMessageLimit).asReversed()
            .takeWhile { msg ->
                budget -= msg.text.length + ROLE_OVERHEAD
                budget > 0
            }
            .reversed()

        recentTail.forEach { msg ->
            parts += LlmMsg(
                role = if (msg.sender == com.jarvis.ai.data.model.Sender.USER) "user" else "assistant",
                content = msg.text
            )
        }

        parts += LlmMsg(role = "user", content = currentRequest)

        return Built(parts, scored.size, maxChars - budget.coerceAtLeast(0))
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 3500
        const val DEFAULT_RECENT_LIMIT = 12
        private const val ROLE_OVERHEAD = 16
    }
}
