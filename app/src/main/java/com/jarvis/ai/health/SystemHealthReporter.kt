package com.jarvis.ai.health

import com.jarvis.ai.core.JarvisRuntime
import com.jarvis.ai.memory.vector.VectorMemoryManager
import com.jarvis.ai.system.SystemAwareness
import com.jarvis.ai.system.SystemSnapshot

/** Real, measured system health — no synthetic numbers anywhere. */
data class HealthReport(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val online: Boolean?,
    val lowStorage: Boolean?,
    val memoryPressurePercent: Int?,
    val providers: List<ProviderHealthEntry>,
    val vectorStoreId: String,
    val voiceInputAvailable: Boolean,
    val ttsAvailable: Boolean,
    val recentEventCount: Int,
    val generatedAtMs: Long
)

data class ProviderHealthEntry(
    val providerId: String,
    val state: String,
    val emaLatencyMs: Long,
    val successCount: Long,
    val failureCount: Long,
    val coolingDown: Boolean,
    val hasKeyConfigured: Boolean,
    val enabled: Boolean
)

/**
 * Aggregates REAL telemetry:
 *  - per-provider state/latency/success-failure counters from [ProviderHealthManager]
 *  - battery/network/storage/memory from [SystemAwareness]
 *  - active vector store id from [VectorMemoryManager]
 *  - STT availability and TTS readiness flags supplied by the caller
 */
class SystemHealthReporter(
    private val runtime: JarvisRuntime,
    private val awareness: SystemAwareness,
    private val voiceInputAvailable: () -> Boolean = { false },
    private val ttsAvailable: () -> Boolean = { false }
) {

    fun report(snapshot: SystemSnapshot? = null): HealthReport {
        val snap = snapshot ?: awareness.snapshot()
        val ts = System.currentTimeMillis()

        val providerEntries = runtime.keys.registeredProviders().map { providerId ->
            val r = runtime.health.metrics(providerId)
            ProviderHealthEntry(
                providerId = providerId,
                state = r.state.name,
                emaLatencyMs = r.emaLatencyMs,
                successCount = r.successCount,
                failureCount = r.failureCount,
                coolingDown = ts < r.cooldownUntilMs,
                hasKeyConfigured = runtime.keys.slots(providerId).isNotEmpty(),
                enabled = com.jarvis.ai.provider.ProviderRegistry.byId(providerId)?.enabled ?: true
            )
        }

        return HealthReport(
            batteryPercent = snap.batteryPercent,
            charging = snap.charging,
            online = snap.online,
            lowStorage = snap.lowStorage,
            memoryPressurePercent = snap.memoryPressurePercent,
            providers = providerEntries.sortedBy { it.providerId },
            vectorStoreId = runtime.vectorMemory.activeStoreId,
            voiceInputAvailable = voiceInputAvailable(),
            ttsAvailable = ttsAvailable(),
            recentEventCount = com.jarvis.ai.core.EventBus.recent(Int.MAX_VALUE).size,
            generatedAtMs = ts
        )
    }
}
