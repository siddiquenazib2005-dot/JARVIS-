package com.jarvis.ai.orchestrator

import com.jarvis.ai.provider.Capability
import com.jarvis.ai.provider.ProviderRouter
import com.jarvis.ai.provider.SecretsSource
import com.jarvis.ai.provider.VisionRouteRequest
import com.jarvis.ai.provider.VisionRouteChunk
import com.jarvis.ai.provider.SecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Vision pipeline backend.
 *
 * Delegates to [ProviderRouter.routeVision] which handles:
 *  - Provider selection via Capability.VISION
 *  - Health checks, key rotation, cooldown, fallback
 *  - Gemini and OpenAI vision providers
 */
class VisionAnalyzer(
    private val providerRouter: ProviderRouter,
    private val secrets: SecretsSource
) {

    suspend fun analyze(
        base64Image: String,
        mimeType: String = "image/jpeg",
        prompt: String = "Describe what you see."
    ): VisionResult = withContext(Dispatchers.IO) {
        val request = VisionRouteRequest(base64Image, mimeType, prompt)
        val textBuilder = StringBuilder()
        var providerId = ""
        var model = ""
        var latencyMs = 0L
        var success = false
        var error: String? = null

        providerRouter.routeVision(VisionRouteRequest(base64Image, mimeType, prompt)).collect { chunk ->
            when (chunk) {
                is VisionRouteChunk.Delta -> {
                    textBuilder.append(chunk.text)
                }
                is VisionRouteChunk.Finished -> {
                    if (chunk.report.success) {
                        success = true
                        providerId = chunk.report.metadata.provider
                        model = chunk.report.metadata.model
                        latencyMs = chunk.report.metadata.latencyMs
                    } else {
                        error = chunk.report.error
                    }
                }
            }
        }

        if (success) {
            VisionResult(
                text = textBuilder.toString(),
                providerId = providerId,
                model = model,
                latencyMs = latencyMs
            )
        } else {
            throw IOException(SecretRedactor.redact(error ?: "vision: no configured vision-capable provider reachable"))
        }
    }

    data class VisionResult(val text: String, val providerId: String, val model: String, val latencyMs: Long)
}