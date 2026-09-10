package com.jarvis.ai.provider

/**
 * Task-aware model selection. The same provider should not serve every task:
 * math → fast model, chat → balanced, coding → code-specialised,
 * deep reasoning → reasoning model, vision → vision-capable model only.
 */
object ModelRouting {

    /** providerId → capability → ordered model preferences. */
    private val table: Map<String, Map<Capability, List<String>>> = mapOf(
        "openai" to mapOf(
            Capability.CHAT to listOf("gpt-4o-mini"),
            Capability.CODING to listOf("gpt-4o-mini", "gpt-4o"),
            Capability.REASONING to listOf("gpt-4o-mini", "gpt-4o"),
            Capability.VISION to listOf("gpt-4o-mini")
        ),
        "groq" to mapOf(
            Capability.CHAT to listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b"),
            Capability.CODING to listOf("qwen/qwen3.8-27b", "openai/gpt-oss-120b"),
            Capability.REASONING to listOf("openai/gpt-oss-120b")
        ),
        "deepseek" to mapOf(
            Capability.CHAT to listOf("deepseek-chat"),
            Capability.CODING to listOf("deepseek-chat"),
            Capability.REASONING to listOf("deepseek-reasoner")
        ),
        "openrouter" to mapOf(
            Capability.CHAT to listOf("meta-llama/llama-3.3-70b-instruct"),
            Capability.CODING to listOf(
                "qwen/qwen-2.5-coder-32b-instruct",
                "meta-llama/llama-3.3-70b-instruct"
            ),
            Capability.REASONING to listOf(
                "deepseek/deepseek-r1",
                "meta-llama/llama-3.3-70b-instruct"
            )
        ),
        "cerebras" to mapOf(
            Capability.CHAT to listOf("gpt-oss-120b"),
            Capability.CODING to listOf("gpt-oss-120b")
        ),
        "mistral" to mapOf(
            Capability.CHAT to listOf("mistral-small-latest"),
            Capability.CODING to listOf("codestral-latest", "mistral-small-latest"),
            Capability.REASONING to listOf("mistral-small-latest")
        ),
        "gemini" to mapOf(
            Capability.CHAT to listOf("gemini-2.5-flash", "gemini-flash-latest"),
            Capability.CODING to listOf("gemini-2.5-flash"),
            Capability.REASONING to listOf("gemini-2.5-pro", "gemini-2.5-flash"),
            Capability.VISION to listOf("gemini-2.5-flash")
        )
    )

    /**
     * Resolves the best model for a capability on a provider.
     * Falls back to the provider default when no specialised entry exists.
     */
    fun resolveModel(providerId: String, capability: Capability, providerDefault: String): String {
        // A model pinned from the picker always wins for the provider it belongs to.
        val pinned = RoutingPrefs.pinnedModel
        if (!pinned.isNullOrBlank() && RoutingPrefs.pinnedProviderId == providerId) {
            return pinned
        }
        val entry = table[providerId]?.get(capability).orEmpty().firstOrNull()
        return entry ?: when (capability) {
            Capability.CODING -> codingFallback(providerId) ?: providerDefault
            Capability.REASONING -> reasoningFallback(providerId) ?: providerDefault
            else -> providerDefault
        }
    }

    private fun codingFallback(providerId: String): String? =
        table[providerId]?.get(Capability.CHAT)?.firstOrNull()

    private fun reasoningFallback(providerId: String): String? =
        table[providerId]?.get(Capability.CHAT)?.firstOrNull()
}
