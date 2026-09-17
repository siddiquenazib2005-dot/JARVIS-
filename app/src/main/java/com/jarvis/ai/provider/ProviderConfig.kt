package com.jarvis.ai.provider

/** Static definition of every provider AURIX knows about. */
enum class ProviderType { LLM, SEARCH, NEWS, WEATHER, MAPS, FINANCE, SPACE, STT, TTS, VECTOR_DB }

/**
 * Central configuration record for one provider endpoint.
 * Never contains the secret itself — only a reference name resolved via [SecretsSource].
 */
data class ProviderConfig(
    val providerId: String,
    val providerType: ProviderType,
    val baseUrl: String,
    val enabled: Boolean,
    val priority: Int,
    val defaultModel: String,
    val capabilities: Set<Capability>,
    val timeoutMs: Long = 90_000L,
    val maxRetries: Int = 1,
    val envVarName: String
)

/** Canonical registry — single source of truth for routing decisions. */
object ProviderRegistry {

    private val configs: List<ProviderConfig> = listOf(
        // ---- LLM ----
        ProviderConfig(
            "openrouter", ProviderType.LLM, "https://openrouter.ai/api/v1", true, 10,
            "meta-llama/llama-3.3-70b-instruct",
            setOf(Capability.CHAT, Capability.REASONING, Capability.CODING),
            envVarName = "OPENROUTER_API_KEY"
        ),
        ProviderConfig(
            "groq", ProviderType.LLM, "https://api.groq.com/openai/v1", true, 20,
            "openai/gpt-oss-120b",
            setOf(Capability.CHAT, Capability.REASONING, Capability.CODING),
            envVarName = "GROQ_API_KEY"
        ),
        ProviderConfig(
            "cerebras", ProviderType.LLM, "https://api.cerebras.ai/v1", true, 30,
            "gpt-oss-120b",
            setOf(Capability.CHAT, Capability.CODING),
            envVarName = "CEREBRAS_API_KEY"
        ),
        ProviderConfig(
            "mistral", ProviderType.LLM, "https://api.mistral.ai/v1", true, 40,
            "mistral-small-latest",
            setOf(Capability.CHAT, Capability.REASONING, Capability.CODING, Capability.EMBEDDING),
            envVarName = "MISTRAL_API_KEY"
        ),
        ProviderConfig(
            "gemini", ProviderType.LLM, "https://generativelanguage.googleapis.com/v1beta", true, 50,
            "gemini-2.5-flash",
            setOf(Capability.CHAT, Capability.REASONING, Capability.CODING, Capability.VISION),
            envVarName = "GEMINI_API_KEY"
        ),
        ProviderConfig(
            "openai", ProviderType.LLM, "https://api.openai.com/v1", true, 60,
            "gpt-4o-mini",
            setOf(Capability.CHAT, Capability.REASONING, Capability.CODING, Capability.VISION),
            envVarName = "OPENAI_API_KEY"
        ),
        ProviderConfig(
            "huggingface", ProviderType.LLM, "https://router.huggingface.co/v1", false, 80,
            "meta-llama/Llama-3.3-70B-Instruct",
            setOf(Capability.CHAT),
            envVarName = "HUGGINGFACE_API_KEY"
        ),
        // ---- SEARCH ----
        ProviderConfig(
            "tavily", ProviderType.SEARCH, "https://api.tavily.com", true, 10,
            "",
            setOf(Capability.SEARCH, Capability.RAG), envVarName = "TAVILY_API_KEY"
        ),
        ProviderConfig(
            "serper", ProviderType.SEARCH, "https://google.serper.dev", true, 20,
            "", setOf(Capability.SEARCH), envVarName = "SERPER_API_KEY"
        ),
        ProviderConfig(
            "exa", ProviderType.SEARCH, "https://api.exa.ai", true, 30,
            "", setOf(Capability.SEARCH), envVarName = "EXA_API_KEY"
        ),
        // ---- NEWS ----
        ProviderConfig(
            "newsapi", ProviderType.NEWS, "https://newsapi.org", true, 10,
            "", setOf(Capability.NEWS), envVarName = "NEWSAPI_KEY"
        ),
        ProviderConfig(
            "gnews", ProviderType.NEWS, "https://gnews.io", true, 20,
            "", setOf(Capability.NEWS), envVarName = "GNEWS_API_KEY"
        ),
        // ---- WEATHER ----
        ProviderConfig(
            "openweather", ProviderType.WEATHER, "https://api.openweathermap.org", true, 10,
            "", setOf(Capability.WEATHER), envVarName = "OPENWEATHER_API_KEY"
        ),
        ProviderConfig(
            "visualcrossing", ProviderType.WEATHER, "https://weather.visualcrossing.com", true, 20,
            "", setOf(Capability.WEATHER), envVarName = "VISUALCROSSING_API_KEY"
        ),
        // ---- MAPS ----
        ProviderConfig(
            "tomtom", ProviderType.MAPS, "https://api.tomtom.com", true, 10,
            "", setOf(Capability.MAPS), envVarName = "TOMTOM_API_KEY"
        ),
        ProviderConfig(
            "geoapify", ProviderType.MAPS, "https://api.geoapify.com", true, 20,
            "", setOf(Capability.MAPS), envVarName = "GEOAPIFY_API_KEY"
        ),
        // ---- FINANCE ----
        ProviderConfig(
            "alphavantage", ProviderType.FINANCE, "https://www.alphavantage.co", true, 10,
            "", setOf(Capability.FINANCE), envVarName = "ALPHAVANTAGE_API_KEY"
        ),
        ProviderConfig(
            "finnhub", ProviderType.FINANCE, "https://finnhub.io", true, 20,
            "", setOf(Capability.FINANCE), envVarName = "FINNHUB_API_KEY"
        ),
        // ---- SPACE ----
        ProviderConfig(
            "nasa", ProviderType.SPACE, "https://api.nasa.gov", true, 10,
            "", setOf(Capability.SPACE), envVarName = "NASA_API_KEY"
        ),
        // ---- VECTOR DB ----
        ProviderConfig(
            "qdrant", ProviderType.VECTOR_DB, "", true, 10,
            "", setOf(Capability.RAG, Capability.EMBEDDING), envVarName = "QDRANT_API_KEY"
        ),
        // ---- STT ----
        ProviderConfig(
            "android_native_stt", ProviderType.STT, "local", true, 5,
            "", setOf(Capability.STT), envVarName = ""
        ),
        ProviderConfig(
            "deepgram", ProviderType.STT, "https://api.deepgram.com", true, 20,
            "nova-2", setOf(Capability.STT), envVarName = "DEEPGRAM_API_KEY"
        ),
        // ---- TTS ----
        ProviderConfig(
            "android_native_tts", ProviderType.TTS, "local", true, 5,
            "", setOf(Capability.TTS), envVarName = ""
        ),
        ProviderConfig(
            "elevenlabs", ProviderType.TTS, "https://api.elevenlabs.io", true, 20,
            "21m00Tcm4TlvDq8ikWAM", setOf(Capability.TTS), envVarName = "ELEVENLABS_API_KEY"
        )
    )

    fun all(): List<ProviderConfig> = configs

    fun enabledFor(capability: Capability): List<ProviderConfig> =
        configs.filter { it.enabled && capability in it.capabilities }.sortedBy { it.priority }

    fun byId(providerId: String): ProviderConfig? = configs.firstOrNull { it.providerId == providerId }
}
