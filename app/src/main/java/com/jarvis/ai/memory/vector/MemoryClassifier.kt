package com.jarvis.ai.memory.vector

import com.jarvis.ai.provider.SecretRedactor

/** What the classifier decided about a piece of candidate memory. */
enum class MemoryDecision { STORE, TEMPORARY_ONLY, DUPLICATE, IRRELEVANT }

data class Classification(
    val decision: MemoryDecision,
    val reason: String,
    val importance: Float
)

/**
 * Decides whether information deserves persistent semantic memory.
 * Heuristic v1 — deterministic and unit-testable; LLM-assisted classification
 * can later implement this same contract.
 */
class MemoryClassifier {

    fun classify(
        content: String,
        type: MemoryType,
        existingTopMatch: ScoredMemory?
    ): Classification {
        val trimmed = content.trim()
        if (trimmed.length < MIN_MEANINGFUL_CHARS) {
            return Classification(MemoryDecision.IRRELEVANT, "too short", 0f)
        }

        // Security rule: secrets must never enter vector memory.
        if (containsSecret(trimmed)) {
            return Classification(MemoryDecision.IRRELEVANT, "secret-like content blocked", 0f)
        }

        if (existingTopMatch != null && existingTopMatch.score >= DUPLICATE_THRESHOLD) {
            return Classification(
                MemoryDecision.DUPLICATE,
                "similarity ${"%.3f".format(existingTopMatch.score)} with ${existingTopMatch.record.memoryId}",
                existingTopMatch.record.metadata.importance
            )
        }

        return when (type) {
            MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.PROJECT -> Classification(
                MemoryDecision.STORE, "explicit $type statement",
                defaultImportance(type)
            )
            MemoryType.PROCEDURAL, MemoryType.SEMANTIC -> Classification(
                MemoryDecision.STORE, "$type captured",
                defaultImportance(type)
            )
            MemoryType.CONVERSATION_SUMMARY, MemoryType.EPISODIC ->
                if (trimmed.length >= EPISODIC_MIN_CHARS) {
                    Classification(MemoryDecision.STORE, "substantial episode", defaultImportance(type))
                } else {
                    Classification(MemoryDecision.TEMPORARY_ONLY, "minor episode", 0.2f)
                }
        }
    }

    /** True when content looks like it contains credentials — such text is never stored. */
    fun containsSecret(content: String): Boolean =
        listOf(
            Regex("sk-or-v1-[A-Za-z0-9]{8,}"),
            Regex("gsk_[A-Za-z0-9]{8,}"),
            Regex("csk-[A-Za-z0-9]{8,}"),
            Regex("tvly-[A-Za-z0-9\\-]{8,}"),
            Regex("pcsk_[A-Za-z0-9_\\-]{8,}"),
            Regex("eyJ[A-Za-z0-9._\\-]{20,}"),          // JWT / Qdrant key
            Regex("(?i)(api[_-]?key|secret|password)\\s*[:=]\\s*\\S{6,}")
        ).any { it.containsMatchIn(content) }

    companion object {
        const val MIN_MEANINGFUL_CHARS = 8
        const val EPISODIC_MIN_CHARS = 25
        const val DUPLICATE_THRESHOLD = 0.97f

        fun defaultImportance(type: MemoryType): Float = when (type) {
            MemoryType.PROJECT -> 0.85f
            MemoryType.PROCEDURAL -> 0.75f
            MemoryType.FACT -> 0.7f
            MemoryType.SEMANTIC -> 0.65f
            MemoryType.PREFERENCE -> 0.65f
            MemoryType.CONVERSATION_SUMMARY -> 0.45f
            MemoryType.EPISODIC -> 0.4f
        }
    }
}
