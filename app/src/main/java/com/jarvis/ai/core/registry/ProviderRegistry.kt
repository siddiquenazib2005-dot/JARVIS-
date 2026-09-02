package com.jarvis.ai.core.registry

import com.jarvis.ai.core.model.Capability
import com.jarvis.ai.core.model.ProviderModel
import com.jarvis.ai.core.security.KeyringManager

class ProviderRegistry(private val keyringManager: KeyringManager) {

    private val availableModels = mutableListOf<ProviderModel>()

    init {
        registerDefaultModels()
    }

    private fun registerDefaultModels() {
        availableModels.clear()

        // Gemini Models (Primary for Vision & Function Calling)
        availableModels.add(
            ProviderModel(
                id = "gemini-1.5-flash",
                providerName = "gemini",
                capabilities = setOf(
                    Capability.TEXT_GENERATION,
                    Capability.VISION,
                    Capability.FUNCTION_CALLING
                ),
                priority = 1
            )
        )

        // OpenAI Models (Fallback)
        availableModels.add(
            ProviderModel(
                id = "gpt-4o",
                providerName = "openai",
                capabilities = setOf(
                    Capability.TEXT_GENERATION,
                    Capability.VISION,
                    Capability.FUNCTION_CALLING
                ),
                priority = 2
            )
        )
    }

    fun getProvidersWithCapability(capability: Capability): List<ProviderModel> {
        return availableModels
            .filter { it.capabilities.contains(capability) && keyringManager.hasValidKey(it.providerName) }
            .sortedBy { it.priority }
    }
}
