package com.jarvis.ai.provider

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks provider-level health across the 8 defined states with
 * per-category cooldown policies and latency/success metrics.
 */
class ProviderHealthManager(private val now: () -> Long = System::currentTimeMillis) {

    data class Runtime(
        var state: HealthState = HealthState.HEALTHY,
        var consecutiveFailures: Int = 0,
        var emaLatencyMs: Long = 0L,
        var cooldownUntilMs: Long = 0L,
        var lastFailureCategory: FailureCategory? = null,
        var successCount: Long = 0L,
        var failureCount: Long = 0L
    )

    private val runtimes = ConcurrentHashMap<String, Runtime>()

    fun runtime(providerId: String): Runtime = runtimes.getOrPut(providerId) { Runtime() }

    fun recordSuccess(providerId: String, latencyMs: Long) {
        val r = runtime(providerId)
        r.emaLatencyMs = if (r.emaLatencyMs == 0L) latencyMs
        else (r.emaLatencyMs * 7 + latencyMs) / 8
        r.consecutiveFailures = 0
        r.cooldownUntilMs = 0L
        r.lastFailureCategory = null
        r.successCount += 1
        // Degraded = alive but consistently slow.
        r.state =
            if (r.emaLatencyMs > DEGRADED_LATENCY_MS && r.successCount > 1) HealthState.DEGRADED
            else HealthState.HEALTHY
    }

    /** Returns the applied cooldown duration in ms. */
    fun recordFailure(providerId: String, category: FailureCategory): Long {
        val r = runtime(providerId)
        r.consecutiveFailures += 1
        r.failureCount += 1
        r.lastFailureCategory = category
        val cooldown = when (category) {
            FailureCategory.RATE_LIMIT -> {
                r.state = HealthState.RATE_LIMITED
                KEY_RATE_COOLDOWN_MS
            }
            FailureCategory.AUTH -> {
                r.state = HealthState.AUTH_FAILED
                AUTH_COOLDOWN_MS
            }
            FailureCategory.TIMEOUT -> {
                r.state = HealthState.TIMEOUT
                backoff(r.consecutiveFailures)
            }
            FailureCategory.NETWORK -> {
                r.state = HealthState.NETWORK_FAILED
                backoff(r.consecutiveFailures)
            }
            else -> {
                r.state = HealthState.DEGRADED
                backoff(r.consecutiveFailures)
            }
        }
        r.cooldownUntilMs = now() + cooldown
        return cooldown
    }

    /**
     * A provider is selectable when not disabled, its cooldown has expired, and
     * (if cooling) at least one key slot is healthy — temporary failures never
     * permanently disable a provider.
     */
    fun isSelectable(
        providerId: String,
        enabled: Boolean,
        poolHasHealthyKey: Boolean
    ): Boolean {
        if (!enabled) return false
        val r = runtime(providerId)
        if (r.state == HealthState.DISABLED) return false
        val cooling = now() < r.cooldownUntilMs
        return !cooling || poolHasHealthyKey
    }

    fun metrics(providerId: String): Runtime = runtime(providerId)

    private fun backoff(consecutiveFailures: Int): Long =
        minOf(PROVIDER_BACKOFF_BASE_MS shl consecutiveFailures.coerceAtMost(5), PROVIDER_MAX_BACKOFF_MS)

    companion object {
        const val KEY_RATE_COOLDOWN_MS = 60_000L
        const val AUTH_COOLDOWN_MS = 900_000L
        const val PROVIDER_BACKOFF_BASE_MS = 5_000L
        const val PROVIDER_MAX_BACKOFF_MS = 600_000L
        const val DEGRADED_LATENCY_MS = 8_000L
    }
}
