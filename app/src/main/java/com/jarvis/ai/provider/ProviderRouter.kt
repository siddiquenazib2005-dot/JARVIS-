package com.jarvis.ai.provider

import com.jarvis.ai.data.model.Message
import com.jarvis.ai.orchestrator.OfflineProvider
import com.jarvis.ai.provider.adapters.LlmProviderFactory
import com.jarvis.ai.provider.adapters.NewsItem
import com.jarvis.ai.provider.adapters.SearchProvider
import com.jarvis.ai.provider.adapters.WebResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException

/** Streamed route output: incremental deltas followed by exactly one terminal report. */
sealed class RouteChunk {
    data class Delta(val text: String) : RouteChunk()
    data class Finished(val report: ExecutionReport) : RouteChunk()
}

/**
 * Capability-aware, health-aware request router.
 *
 * Failure policy (matches the specified example):
 *   Key1 429 → slot cooldown → Key2 timeout → Key3 … provider keys exhausted
 *   → provider-level backoff → next provider by priority → … → offline fallback.
 *
 * AUTH/RATE failures rotate to the next key immediately; TIMEOUT/SERVER/NETWORK
 * additionally apply provider-level exponential backoff after key exhaustion.
 */
class ProviderRouter(
    private val secrets: SecretsSource,
    private val keys: KeyPoolManager = KeyPoolManager(secrets),
    private val health: ProviderHealthManager = ProviderHealthManager(),
    private val transport: HttpTransport = HttpTransports.default,
    private val llmFactory: (
        config: ProviderConfig,
        apiKey: String,
        model: String
    ) -> com.jarvis.ai.orchestrator.AIProvider = { cfg, key, model ->
        LlmProviderFactory.create(cfg, key, model, transport)
    },
    private val providerManager: ProviderManager,
    private val now: () -> Long = System::currentTimeMillis
) {

    // -------------------------------------------------------------------
    // LLM TEXT ROUTING (CHAT / REASONING / CODING / VISION)
    // -------------------------------------------------------------------

    fun routeText(request: LlmRouteRequest): Flow<RouteChunk> = flow {
        val startedAt = now()
        val trail = mutableListOf<String>()
        var fallbacks = 0
        var retries = 0

        // Fast path: use ProviderManager.selectPrimary() for instant primary selection
        val primaryCfg = providerManager.selectPrimary(request.capability)
        val candidates = if (primaryCfg != null) {
            listOf(primaryCfg) + ProviderRegistry
                .enabledFor(request.capability)
                .filter { it.providerType == ProviderType.LLM && it.providerId != primaryCfg.providerId }
        } else {
            ProviderRegistry
                .enabledFor(request.capability)
                .filter { it.providerType == ProviderType.LLM }
        }

        for (cfg in candidates) {
            if (!health.isSelectable(cfg.providerId, cfg.enabled, keys.hasHealthySlot(cfg.providerId, now()))) {
                trail += "${cfg.providerId}:skip(cooldown)"
                continue
            }
            if (secrets.get(envRef(cfg.envVarName)) == null) {
                trail += "${cfg.providerId}:skip(no-key)"
                continue
            }
            val model = ModelRouting.resolveModel(cfg.providerId, request.capability, cfg.defaultModel)

            while (true) {
                val picked = keys.pick(cfg.providerId, now())
                if (picked == null) {
                    trail += "${cfg.providerId}:no-healthy-key"
                    break
                }
                val (slot, secret) = picked
                val attemptStart = now()
                try {
                    val provider = llmFactory(cfg, secret, model)
                    var produced = false
                    if (request.stream) {
                        provider.streamChat(request.history, request.systemPrompt, model)
                            .collect { delta ->
                                if (delta.isNotBlank()) {
                                    produced = true
                                }
                                emit(RouteChunk.Delta(delta))
                            }
                        if (!produced) throw IOException("empty stream")
                    } else {
                        val text = provider.chatOnce(request.history, request.systemPrompt, model)
                        if (text.isBlank()) throw IOException("empty content")
                        emit(RouteChunk.Delta(text))
                    }
                    health.recordSuccess(cfg.providerId, now() - attemptStart)
                    keys.markSuccess(slot)
                    trail += "${cfg.providerId}:success"
                    emit(RouteChunk.Finished(successReport(request, cfg, model, startedAt, trail, retries, fallbacks)))
                    return@flow
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    val category = FailureClassifier.classify(e.message)
                    keys.markFailure(slot, category, now())
                    health.recordFailure(cfg.providerId, category)
                    retries += 1
                    trail += "${cfg.providerId}#${slot.slotIndex}:${category.name.lowercase()}"
                    com.jarvis.ai.core.EventBus.publish(
                        com.jarvis.ai.core.EventType.PROVIDER_FAILED,
                        "${cfg.providerId}:${category.name.lowercase()}"
                    )
                    if (!keys.hasHealthySlot(cfg.providerId, now())) {
                        fallbacks += 1
                        com.jarvis.ai.core.EventBus.publish(
                            com.jarvis.ai.core.EventType.FALLBACK_TRIGGERED,
                            "leaving ${cfg.providerId}"
                        )
                        break
                    }
                    // Healthy key remains → rotate within the same provider.
                }
            }
        }

        // ---- terminal local fallback ----
        try {
            val offlineText = OfflineProvider().chatOnce(
                request.history, request.systemPrompt, "offline"
            )
            emit(RouteChunk.Delta(offlineText))
            trail += "offline:fallback"
            emit(
                RouteChunk.Finished(
                    ExecutionReport(
                        success = true,
                        metadata = metadataOf(request.capability, "offline", "local", startedAt, retries, fallbacks),
                        attempts = trail,
                        error = null
                    )
                )
            )
        } catch (e: Exception) {
            emit(
                RouteChunk.Finished(
                    ExecutionReport(
                        success = false,
                        metadata = metadataOf(request.capability, "", "", startedAt, retries, fallbacks),
                        attempts = trail,
                        error = SecretRedactor.redact(e.message ?: "all providers failed")
                    )
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    // -------------------------------------------------------------------
    // VISION ROUTING
    // -------------------------------------------------------------------

    /**
     * Routes a vision analysis request through the standard provider routing pipeline.
     * Uses Capability.VISION to select only vision-capable providers.
     * Supports Gemini and OpenAI vision models with health checks, key rotation, and fallback.
     */
    fun routeVision(request: VisionRouteRequest): Flow<VisionRouteChunk> = flow {
        val startedAt = now()
        val trail = mutableListOf<String>()
        var fallbacks = 0
        var retries = 0

        val candidates = ProviderRegistry
            .enabledFor(Capability.VISION)
            .filter { it.providerType == ProviderType.LLM }

        for (cfg in candidates) {
            if (!health.isSelectable(cfg.providerId, cfg.enabled, keys.hasHealthySlot(cfg.providerId, now()))) {
                trail += "${cfg.providerId}:skip(cooldown)"
                continue
            }
            if (secrets.get(envRef(cfg.envVarName)) == null) {
                trail += "${cfg.providerId}:skip(no-key)"
                continue
            }
            val model = ModelRouting.resolveModel(cfg.providerId, Capability.VISION, cfg.defaultModel)

            while (true) {
                val picked = keys.pick(cfg.providerId, now())
                if (picked == null) {
                    trail += "${cfg.providerId}:no-healthy-key"
                    break
                }
                val (slot, secret) = picked
                val attemptStart = now()
                try {
                    val provider = llmFactory(cfg, secret, model)
                    // Vision analysis is typically a single-shot request, not streamed
                    val text = provider.analyzeImage(request.base64Image, request.mimeType, request.prompt, model)
                    if (text.isBlank()) throw IOException("empty vision response")
                    emit(VisionRouteChunk.Delta(text))
                    health.recordSuccess(cfg.providerId, now() - attemptStart)
                    keys.markSuccess(slot)
                    trail += "${cfg.providerId}:success"
                    emit(VisionRouteChunk.Finished(successVisionReport(cfg, model, startedAt, trail, retries, fallbacks)))
                    return@flow
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    val category = FailureClassifier.classify(e.message)
                    keys.markFailure(slot, category, now())
                    health.recordFailure(cfg.providerId, category)
                    retries += 1
                    trail += "${cfg.providerId}#${slot.slotIndex}:${category.name.lowercase()}"
                    com.jarvis.ai.core.EventBus.publish(
                        com.jarvis.ai.core.EventType.PROVIDER_FAILED,
                        "${cfg.providerId}:${category.name.lowercase()}"
                    )
                    if (!keys.hasHealthySlot(cfg.providerId, now())) {
                        fallbacks += 1
                        com.jarvis.ai.core.EventBus.publish(
                            com.jarvis.ai.core.EventType.FALLBACK_TRIGGERED,
                            "leaving ${cfg.providerId}"
                        )
                        break
                    }
                    // Healthy key remains → rotate within the same provider.
                }
            }
        }

        // ---- terminal local fallback ----
        emit(
            VisionRouteChunk.Finished(
                ExecutionReport(
                    success = false,
                    metadata = metadataOf(Capability.VISION, "", "", startedAt, retries, fallbacks),
                    attempts = trail,
                    error = "vision: no configured vision-capable provider reachable"
                )
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun successVisionReport(
        cfg: ProviderConfig,
        model: String,
        startedAt: Long,
        trail: List<String>,
        retries: Int,
        fallbacks: Int
    ): ExecutionReport {
        val report = ExecutionReport(
            success = true,
            metadata = metadataOf(Capability.VISION, cfg.providerId, model, startedAt, retries, fallbacks),
            attempts = trail
        )
        ObsLog.record(
            ObsEvent(
                requestId = report.metadata.requestId,
                capability = Capability.VISION,
                provider = cfg.providerId,
                model = model,
                latencyMs = report.metadata.latencyMs,
                status = "SUCCESS",
                retryCount = report.metadata.retryCount,
                fallbackCount = report.metadata.fallbackCount,
                errorCategory = null
            )
        )
        return report
    }

    // -------------------------------------------------------------------
    // STRUCTURED DATA ROUTING (SEARCH / NEWS / WEATHER / MAPS / FINANCE / SPACE)
    // -------------------------------------------------------------------

    suspend fun routeData(capability: Capability, params: Map<String, String>): Pair<String?, ExecutionReport> {
        val startedAt = now()
        val trail = mutableListOf<String>()
        var fallbacks = 0
        var retries = 0

        for (cfg in ProviderRegistry.enabledFor(capability)) {
            if (!health.isSelectable(cfg.providerId, cfg.enabled, keys.hasHealthySlot(cfg.providerId, now()))) {
                trail += "${cfg.providerId}:skip(cooldown)"
                continue
            }
            while (true) {
                val picked = keys.pick(cfg.providerId, now())
                if (picked == null) {
                    trail += "${cfg.providerId}:no-healthy-key"
                    break
                }
                val (slot, secret) = picked
                val t0 = now()
                try {
                    val result = executeDataCall(cfg, secret, params)
                        ?: throw IOException("unsupported parameters")
                    if (result.isBlank()) throw IOException("empty content")
                    health.recordSuccess(cfg.providerId, now() - t0)
                    keys.markSuccess(slot)
                    trail += "${cfg.providerId}:success"
                    return result to ExecutionReport(
                        success = true,
                        metadata = metadataOf(capability, cfg.providerId, "", startedAt, retries, fallbacks),
                        attempts = trail
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    val category = FailureClassifier.classify(e.message)
                    keys.markFailure(slot, category, now())
                    health.recordFailure(cfg.providerId, category)
                    retries += 1
                    trail += "${cfg.providerId}:${category.name.lowercase()}"
                    if (!keys.hasHealthySlot(cfg.providerId, now())) {
                        fallbacks += 1
                        break
                    }
                }
            }
        }
        return null to ExecutionReport(
            success = false,
            metadata = metadataOf(capability, "", "", startedAt, retries, fallbacks),
            attempts = trail,
            error = "all ${capability.name.lowercase()} providers failed"
        )
    }

    /** Wires concrete adapters per capability; returns null when params unsupported. */
    private suspend fun executeDataCall(
        cfg: ProviderConfig,
        secret: String,
        params: Map<String, String>
    ): String? = withContext(Dispatchers.IO) {
        val scoped = object : SecretsSource {
            override fun get(reference: String): String? =
                if (reference == "${cfg.envVarName}#1") secret else secrets.get(reference)
        }
        when (capabilityKey(cfg)) {
            Capability.SEARCH -> {
                val query = params["query"] ?: return@withContext null
                formatResults(searchBackend(cfg.providerId, scoped).search(query, maxResults = 5))
            }
            Capability.NEWS -> {
                val backend = newsBackend(cfg.providerId, scoped)
                val items: List<NewsItem> = backend.headlines(params["topic"])
                formatResults(items.map { WebResult(it.title, it.url, it.description) })
            }
            Capability.WEATHER -> weatherBackend(cfg.providerId, scoped).current(
                params["city"] ?: params["location"] ?: return@withContext null
            ).let { "${it.tempC}°C, ${it.condition}" }
            Capability.MAPS -> mapsBackend(cfg.providerId, scoped).geocode(
                params["address"] ?: params["location"] ?: return@withContext null
            ).let { "${it.lat}, ${it.lon}" }
            Capability.FINANCE -> financeBackend(cfg.providerId, scoped).quote(
                params["symbol"] ?: return@withContext null
            ).let { "${it.symbol}: ${it.price ?: "n/a"}${it.changePercent?.let { c -> " ($c)" } ?: ""}" }
            Capability.SPACE -> spaceBackend(cfg.providerId, scoped).astronomyPictureOfTheDay()
                .let { "${it.title}\n\n${it.explanation}" }
            else -> null
        }
    }

    private fun searchBackend(id: String, scoped: SecretsSource): SearchProvider = when (id) {
        "tavily" -> com.jarvis.ai.provider.adapters.TavilySearch(scoped, transport)
        "serper" -> com.jarvis.ai.provider.adapters.SerperSearch(scoped, transport)
        "exa" -> com.jarvis.ai.provider.adapters.ExaSearch(scoped, transport)
        else -> throw IOException("no search adapter for $id")
    }

    private fun newsBackend(id: String, scoped: SecretsSource) = when (id) {
        "newsapi" -> com.jarvis.ai.provider.adapters.NewsApiProvider(scoped, transport)
        "gnews" -> com.jarvis.ai.provider.adapters.GNewsProvider(scoped, transport)
        else -> throw IOException("no news adapter for $id")
    }

    private fun weatherBackend(id: String, scoped: SecretsSource) = when (id) {
        "openweather" -> com.jarvis.ai.provider.adapters.OpenWeatherProvider(scoped, transport)
        "visualcrossing" -> com.jarvis.ai.provider.adapters.VisualCrossingProvider(scoped, transport)
        else -> throw IOException("no weather adapter for $id")
    }

    private fun mapsBackend(id: String, scoped: SecretsSource) = when (id) {
        "tomtom" -> com.jarvis.ai.provider.adapters.TomTomMaps(scoped, transport)
        "geoapify" -> com.jarvis.ai.provider.adapters.GeoapifyMaps(scoped, transport)
        else -> throw IOException("no maps adapter for $id")
    }

    private fun financeBackend(id: String, scoped: SecretsSource) = when (id) {
        "alphavantage" -> com.jarvis.ai.provider.adapters.AlphaVantageFinance(scoped, transport)
        "finnhub" -> com.jarvis.ai.provider.adapters.FinnhubFinance(scoped, transport)
        else -> throw IOException("no finance adapter for $id")
    }

    private fun spaceBackend(id: String, scoped: SecretsSource) = when (id) {
        "nasa" -> com.jarvis.ai.provider.adapters.NasaSpace(scoped, transport)
        else -> throw IOException("no space adapter for $id")
    }

    // -------------------------------------------------------------------

    private fun envRef(envVarName: String) = "$envVarName#1"

    private fun capabilityKey(cfg: ProviderConfig): Capability = cfg.capabilities.first()

    private fun metadataOf(
        capability: Capability,
        provider: String,
        model: String,
        startedAt: Long,
        retries: Int,
        fallbacks: Int
    ) = RequestMetadata(
        requestId = java.util.UUID.randomUUID().toString(),
        capability = capability,
        provider = provider,
        model = model,
        latencyMs = now() - startedAt,
        retryCount = retries,
        fallbackCount = fallbacks
    )

    private fun successReport(
        request: LlmRouteRequest,
        cfg: ProviderConfig,
        model: String,
        startedAt: Long,
        trail: List<String>,
        retries: Int,
        fallbacks: Int
    ): ExecutionReport {
        val report = ExecutionReport(
            success = true,
            metadata = metadataOf(request.capability, cfg.providerId, model, startedAt, retries, fallbacks),
            attempts = trail
        )
        ObsLog.record(
            ObsEvent(
                requestId = report.metadata.requestId,
                capability = report.metadata.capability,
                provider = report.metadata.provider,
                model = report.metadata.model,
                latencyMs = report.metadata.latencyMs,
                status = "SUCCESS",
                retryCount = report.metadata.retryCount,
                fallbackCount = report.metadata.fallbackCount,
                errorCategory = null
            )
        )
        return report
    }

    private fun formatResults(results: List<WebResult>): String =
        results.take(5).mapIndexedNotNull { index, r ->
            val t = r.title.ifBlank { return@mapIndexedNotNull null }
            "${index + 1}. $t — ${r.snippet.take(160)}${r.url.takeIf { it.isNotBlank() }?.let { "\n   $it" }.orEmpty()}"
        }.joinToString("\n\n").ifBlank { throw IOException("empty results") }
}

data class LlmRouteRequest(
    val capability: Capability,
    val history: List<Message>,
    val systemPrompt: String,
    val stream: Boolean = true,
    val priority: Int = 5
)

/** Request for vision analysis (image + prompt). */
data class VisionRouteRequest(
    val base64Image: String,
    val mimeType: String,
    val prompt: String,
    val stream: Boolean = false
)

/** Vision route output: single result (vision is not streamed incrementally). */
sealed class VisionRouteChunk {
    data class Delta(val text: String) : VisionRouteChunk()
    data class Finished(val report: ExecutionReport) : VisionRouteChunk()
}
