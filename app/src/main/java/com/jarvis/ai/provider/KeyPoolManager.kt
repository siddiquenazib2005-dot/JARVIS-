package com.jarvis.ai.provider

import java.util.concurrent.ConcurrentHashMap

/** One secret slot for a provider. Multiple slots per provider enable rotation. */
data class KeySlot(
    val providerId: String,
    val slotIndex: Int,
    val secretRef: String
) {
    @Volatile var consecutiveFailures: Int = 0
    @Volatile var cooldownUntilMs: Long = 0L
    @Volatile var lastUsedMs: Long = 0L
}

/**
 * Manages key slots per provider with deterministic, health-aware selection.
 * Selection is NEVER random: fewest consecutive failures first, then
 * least-recently-used, then slot order.
 */
class KeyPoolManager(private val secrets: SecretsSource) {

    private val pools = ConcurrentHashMap<String, MutableList<KeySlot>>()

    /** Registers an additional slot (multi-key support). Idempotent per index. */
    fun addSlot(providerId: String, envVarName: String, slotIndex: Int = 1): KeySlot {
        val slot = KeySlot(providerId, slotIndex, "${envVarName}#$slotIndex")
        pools.getOrPut(providerId) { mutableListOf() }.let { list ->
            if (list.none { it.slotIndex == slotIndex }) list.add(slot)
        }
        return slot
    }

    fun slots(providerId: String): List<KeySlot> =
        pools[providerId].orEmpty().sortedBy { it.slotIndex }

    /** All providers that currently have at least one registered key slot. */
    fun registeredProviders(): List<String> = pools.keys().toList().sorted()

    fun hasHealthySlot(providerId: String, nowMs: Long): Boolean =
        slots(providerId).any { it.cooldownUntilMs <= nowMs }

    /**
     * Deterministic pick: healthy slots only; ordered by failures then LRU then index.
     * Returns null when every slot is cooling down or the secret is absent.
     */
    fun pick(providerId: String, nowMs: Long): Pair<KeySlot, String>? {
        val candidate = slots(providerId)
            .filter { it.cooldownUntilMs <= nowMs }
            .minWithOrNull(
                compareBy({ it.consecutiveFailures }, { it.lastUsedMs }, { it.slotIndex })
            ) ?: return null
        val secret = secrets.get(candidate.secretRef) ?: secrets.get(envBaseRef(providerId))
        if (secret.isNullOrBlank()) return null
        candidate.lastUsedMs = nowMs
        return candidate to secret
    }

    fun markSuccess(slot: KeySlot) {
        slot.consecutiveFailures = 0
        slot.cooldownUntilMs = 0L
    }

    /** Applies per-category cooldown to the SLOT (provider-level handled elsewhere). */
    fun markFailure(slot: KeySlot, category: FailureCategory, nowMs: Long): Long {
        slot.consecutiveFailures += 1
        val cooldown = when (category) {
            FailureCategory.RATE_LIMIT -> RATE_LIMIT_COOLDOWN_MS
            FailureCategory.AUTH -> AUTH_COOLDOWN_MS
            else -> minOf(
                BASE_BACKOFF_MS shl slot.consecutiveFailures.coerceAtMost(6),
                MAX_BACKOFF_MS
            )
        }
        slot.cooldownUntilMs = nowMs + cooldown
        return cooldown
    }

    private fun envBaseRef(providerId: String): String =
        "#${ProviderRegistry.byId(providerId)?.envVarName.orEmpty()}#1"

    companion object {
        const val RATE_LIMIT_COOLDOWN_MS = 60_000L
        const val AUTH_COOLDOWN_MS = 900_000L
        const val BASE_BACKOFF_MS = 400L
        const val MAX_BACKOFF_MS = 300_000L
    }
}
