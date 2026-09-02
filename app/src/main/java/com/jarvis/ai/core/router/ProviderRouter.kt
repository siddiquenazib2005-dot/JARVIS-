package com.jarvis.ai.core.router

import com.jarvis.ai.core.model.Capability
import com.jarvis.ai.core.network.GeminiVisionAdapter
import com.jarvis.ai.core.registry.ProviderRegistry
import com.jarvis.ai.core.security.KeyringManager

class ProviderRouter(
    private val registry: ProviderRegistry,
    private val keyringManager: KeyringManager,
    private val geminiAdapter: GeminiVisionAdapter
) {

    suspend fun routeRequest(prompt: String, imageBytes: ByteArray? = null): String {
        val requiredCapability = if (imageBytes != null && imageBytes.isNotEmpty()) {
            Capability.VISION
        } else {
            Capability.TEXT_GENERATION
        }

        val providers = registry.getProvidersWithCapability(requiredCapability)

        if (providers.isEmpty()) {
            throw IllegalStateException(
                "No active provider with capability [${requiredCapability.name}] configured or API keys missing."
            )
        }

        var lastException: Exception? = null

        for (provider in providers) {
            val apiKey = keyringManager.getKey(provider.providerName) ?: continue
            try {
                return when (provider.providerName) {
                    "gemini" -> geminiAdapter.generateResponse(prompt, imageBytes, apiKey)
                    else -> throw UnsupportedOperationException("Provider ${provider.providerName} not implemented yet")
                }
            } catch (e: Exception) {
                lastException = e
                continue
            }
        }

        throw RuntimeException(
            "All available providers failed. Root cause: ${lastException?.localizedMessage}",
            lastException
        )
    }
}
