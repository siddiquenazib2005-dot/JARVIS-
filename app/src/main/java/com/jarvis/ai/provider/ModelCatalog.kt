package com.jarvis.ai.provider

/** One selectable entry in the model picker. */
data class ModelOption(
    val providerId: String,
    val providerLabel: String,
    val modelId: String,
    val label: String,
    val note: String,
    val envVarName: String,
    val supportsVision: Boolean = false
)

/**
 * Curated, human-readable model list surfaced in the picker. Every entry maps
 * back to a real [ProviderConfig] so the router can execute the pinned choice.
 *
 * The catalogue deliberately favours free / very cheap tiers first, because the
 * common setup is "bring a couple of free keys and let AURIX route".
 */
object ModelCatalog {

    val options: List<ModelOption> = listOf(
        // ---- Groq (fastest free tier) ----
        ModelOption(
            "groq", "Groq", "openai/gpt-oss-120b",
            "GPT-OSS 120B", "Very fast · free tier", "GROQ_API_KEY"
        ),
        ModelOption(
            "groq", "Groq", "openai/gpt-oss-20b",
            "GPT-OSS 20B", "Fastest · lightest", "GROQ_API_KEY"
        ),
        ModelOption(
            "groq", "Groq", "llama-3.3-70b-versatile",
            "Llama 3.3 70B", "Balanced chat", "GROQ_API_KEY"
        ),
        // ---- Gemini (free tier + vision) ----
        ModelOption(
            "gemini", "Google", "gemini-2.5-flash",
            "Gemini 2.5 Flash", "Free tier · vision", "GEMINI_API_KEY",
            supportsVision = true
        ),
        ModelOption(
            "gemini", "Google", "gemini-2.5-pro",
            "Gemini 2.5 Pro", "Deep reasoning", "GEMINI_API_KEY",
            supportsVision = true
        ),
        // ---- OpenRouter (one key, many models) ----
        ModelOption(
            "openrouter", "OpenRouter", "meta-llama/llama-3.3-70b-instruct",
            "Llama 3.3 70B", "One key · many models", "OPENROUTER_API_KEY"
        ),
        ModelOption(
            "openrouter", "OpenRouter", "deepseek/deepseek-r1",
            "DeepSeek R1", "Reasoning specialist", "OPENROUTER_API_KEY"
        ),
        ModelOption(
            "openrouter", "OpenRouter", "qwen/qwen-2.5-coder-32b-instruct",
            "Qwen 2.5 Coder", "Best for code", "OPENROUTER_API_KEY"
        ),
        // ---- Cerebras ----
        ModelOption(
            "cerebras", "Cerebras", "gpt-oss-120b",
            "GPT-OSS 120B", "Ultra low latency", "CEREBRAS_API_KEY"
        ),
        // ---- Mistral ----
        ModelOption(
            "mistral", "Mistral", "mistral-small-latest",
            "Mistral Small", "Free tier", "MISTRAL_API_KEY"
        ),
        ModelOption(
            "mistral", "Mistral", "codestral-latest",
            "Codestral", "Code specialist", "MISTRAL_API_KEY"
        ),
        // ---- OpenAI ----
        ModelOption(
            "openai", "OpenAI", "gpt-4o-mini",
            "GPT-4o mini", "Cheap · vision", "OPENAI_API_KEY",
            supportsVision = true
        ),
        ModelOption(
            "openai", "OpenAI", "gpt-4o",
            "GPT-4o", "Highest quality", "OPENAI_API_KEY",
            supportsVision = true
        )
    )

    /** Distinct provider ids referenced by the catalogue, in display order. */
    fun providerIds(): List<String> = options.map { it.providerId }.distinct()

    /** Every option belonging to one provider. */
    fun forProvider(providerId: String): List<ModelOption> =
        options.filter { it.providerId == providerId }

    /** Resolves the option matching a pinned provider/model pair. */
    fun find(providerId: String?, modelId: String?): ModelOption? {
        if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) return null
        return options.firstOrNull { it.providerId == providerId && it.modelId == modelId }
    }

    /** Friendly provider name for chips and status rows. */
    fun providerLabel(providerId: String): String =
        options.firstOrNull { it.providerId == providerId }?.providerLabel
            ?: providerId.replaceFirstChar { it.uppercase() }

    /** Env var that holds the key for a provider, if the catalogue knows it. */
    fun envVarFor(providerId: String): String? =
        options.firstOrNull { it.providerId == providerId }?.envVarName
            ?: ProviderRegistry.byId(providerId)?.envVarName
}
