package com.jarvis.ai.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Live reachability report for one provider. */
data class ProviderStatus(
    val providerId: String,
    val reachable: Boolean,
    val stateHint: HealthState,
    val httpCode: Int?,
    val detail: String
)

/**
 * P1 closure helpers: deterministic primary-provider selection,
 * secret-driven multi-key bootstrap, and lightweight health probing.
 *
 * Probe policy (definitive codes mutate health; the rest are observational):
 *   200        → nothing mutated (success metric untouched to avoid skewing EMA)
 *   401 / 403  → AUTH cooldown applied
 *   429        → RATE_LIMITED cooldown applied
 */
class ProviderManager(
    private val keys: KeyPoolManager,
    private val health: ProviderHealthManager,
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Registers slot #1 (+ any KEY_2..KEY_5 found in secrets) for every enabled provider. */
    fun bootstrapFromSecrets(providers: List<ProviderConfig> = ProviderRegistry.all()) {
        providers.filter { it.enabled && it.envVarName.isNotBlank() }.forEach { cfg ->
            if (secrets.get("${cfg.envVarName}#1") != null) {
                keys.addSlot(cfg.providerId, cfg.envVarName, slotIndex = 1)
            }
            for (n in 2..MAX_KEY_SLOTS) {
                if (secrets.get("${cfg.envVarName}#$n") != null) {
                    keys.addSlot(cfg.providerId, cfg.envVarName, slotIndex = n)
                }
            }
        }
    }

    /**
     * Deterministic primary pick for a capability.
     * Score = 45% success-rate + 30% speed + 25% configured priority.
     * Providers without history get a neutral prior so fresh pools still route.
     */
    fun selectPrimary(capability: Capability): ProviderConfig? {
        // Honour an explicit pin from the model picker whenever that provider
        // still has a usable key; otherwise fall through to automatic scoring so
        // a dead pinned provider never blocks the whole assistant.
        RoutingPrefs.pinnedProviderId?.let { pinnedId ->
            val pinned = ProviderRegistry.byId(pinnedId)
            if (pinned != null &&
                capability in pinned.capabilities &&
                keys.pick(pinned.providerId, nowMs()) != null
            ) {
                return pinned
            }
        }
        val candidates = ProviderRegistry
            .enabledFor(capability)
            .filter { it.providerType == ProviderType.LLM }
            .filter {
                health.isSelectable(it.providerId, true, keys.hasHealthySlot(it.providerId, nowMs())) &&
                    keys.pick(it.providerId, nowMs()) != null
            }
        if (candidates.isEmpty()) return null
        val priorities = candidates.map { it.priority }
        val minPri = priorities.min()
        val maxPri = priorities.max()
        return candidates.maxByOrNull { cfg ->
            val r = health.runtime(cfg.providerId)
            val total = (r.successCount + r.failureCount).toFloat()
            val successRate = if (total == 0f) NEUTRAL_SUCCESS_RATE else r.successCount / total
            val speedScore = 1f - (r.emaLatencyMs.coerceAtMost(10_000L).toFloat() / 10_000f)
            val priorityScore =
                1f - (cfg.priority - minPri).toFloat() / ((maxPri - minPri + 1).toFloat())
            successRate * 0.45f + speedScore * 0.30f + priorityScore * 0.25f
        }
    }

    /** Provider IDs that currently have at least one key slot registered. */
    fun configuredProviderIds(): List<String> = keys.registeredProviders()

    /**
     * Best-effort reachability probe across LLM providers (GET /models).
     * Gemini uses a different auth scheme and is probed via its key-param URL.
     * Never throws — failures become unreachable statuses.
     */
    suspend fun healthCheck(
        capability: Capability = Capability.CHAT,
        includeDisabled: Boolean = false
    ): List<ProviderStatus> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        ProviderRegistry.all()
            .filter { it.providerType == ProviderType.LLM }
            .filter { includeDisabled || it.enabled }
            .map { cfg -> probe(cfg) }
    }

    private suspend fun probe(cfg: ProviderConfig): ProviderStatus {
        val t0 = System.currentTimeMillis()
        val key = secrets.get("${cfg.envVarName}#1")?.trim()
        if (key.isNullOrBlank()) {
            return ProviderStatus(cfg.providerId, false, HealthState.DISABLED, null, "no key configured")
        }
        return try {
            if (cfg.providerId == "gemini") {
                probeGemini(cfg, key, t0)
            } else {
                probeOpenAICompat(cfg, key, t0)
            }
        } catch (e: IOException) {
            ProviderStatus(
                cfg.providerId, false,
                FailureClassifier.classify(e.message).let {
                    if (it == FailureCategory.NETWORK) HealthState.NETWORK_FAILED else HealthState.COOLDOWN
                },
                null, e.message?.take(120) ?: "network error"
            )
        }
    }

    /**
     * Gemini probe: POST generateContent with a trivial "test" prompt.
     * GET /models is not a reliable reachability signal for Gemini and was
     * producing NETWORK_FAILED status despite valid keys; a successful
     * generateContent (HTTP 200) is the definitive ONLINE signal.
     */
    private suspend fun probeGemini(cfg: ProviderConfig, key: String, t0: Long): ProviderStatus {
        val url = "${cfg.baseUrl.trimEnd('/')}/models/gemini-2.5-flash:generateContent?key=$key"
        val payload =
            """{"contents":[{"role":"user","parts":[{"text":"ping"}]}],"generationConfig":{"maxOutputTokens":1}}"""
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
        transport.execute(request).use { response ->
            val latency = System.currentTimeMillis() - t0
            val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            // Always surface the raw HTTP code + body to logcat so a failing ping
            // is diagnosable instead of being swallowed into an opaque status.
            if (!response.isSuccessful) {
                android.util.Log.w(
                    "JarvisProbe",
                    "gemini probe HTTP ${response.code}: ${body.take(300)}"
                )
            }
            return when {
                response.isSuccessful -> {
                    ProviderStatus(cfg.providerId, true, HealthState.HEALTHY, response.code, "ok (${latency}ms)")
                }
                response.code == 429 -> {
                    health.recordFailure(cfg.providerId, FailureCategory.RATE_LIMIT)
                    ProviderStatus(cfg.providerId, false, HealthState.RATE_LIMITED, 429, "rate limited")
                }
                response.code == 401 || response.code == 403 -> {
                    health.recordFailure(cfg.providerId, FailureCategory.AUTH)
                    ProviderStatus(cfg.providerId, false, HealthState.AUTH_FAILED, response.code, "auth rejected")
                }
                response.code == 404 || response.code == 400 -> {
                    // Unknown model / malformed request — likely a key or URL problem,
                    // treat as auth/cooldown so routing backs off rather than spinning.
                    health.recordFailure(cfg.providerId, FailureCategory.AUTH)
                    ProviderStatus(cfg.providerId, false, HealthState.AUTH_FAILED, response.code, "HTTP ${response.code}")
                }
                else -> ProviderStatus(
                    cfg.providerId, false, HealthState.DEGRADED, response.code,
                    "HTTP ${response.code}"
                )
            }
        }
    }

    private suspend fun probeOpenAICompat(cfg: ProviderConfig, key: String, t0: Long): ProviderStatus {
        val url = "${cfg.baseUrl.trimEnd('/')}/models"
        val builder = okhttp3.Request.Builder().url(url).get()
            .header("Authorization", "Bearer $key")
        return try {
            transport.execute(builder.build()).use { response ->
                val latency = System.currentTimeMillis() - t0
                when {
                    response.isSuccessful -> {
                        ProviderStatus(cfg.providerId, true, HealthState.HEALTHY, response.code, "ok (${latency}ms)")
                    }
                    response.code == 429 -> {
                        health.recordFailure(cfg.providerId, FailureCategory.RATE_LIMIT)
                        ProviderStatus(cfg.providerId, false, HealthState.RATE_LIMITED, 429, "rate limited")
                    }
                    response.code == 401 || response.code == 403 -> {
                        health.recordFailure(cfg.providerId, FailureCategory.AUTH)
                        ProviderStatus(cfg.providerId, false, HealthState.AUTH_FAILED, response.code, "auth rejected")
                    }
                    else -> ProviderStatus(
                        cfg.providerId, false, HealthState.DEGRADED, response.code,
                        "HTTP ${response.code}"
                    )
                }
            }
        } catch (e: IOException) {
            ProviderStatus(
                cfg.providerId, false,
                FailureClassifier.classify(e.message).let {
                    if (it == FailureCategory.NETWORK) HealthState.NETWORK_FAILED else HealthState.COOLDOWN
                },
                null, e.message?.take(120) ?: "network error"
            )
        }
    }

    companion object {
        const val MAX_KEY_SLOTS = 5
        const val NEUTRAL_SUCCESS_RATE = 0.8f
        private const val JSON_MEDIA_TYPE = "application/json"
        private fun nowMs() = System.currentTimeMillis()
    }
}
