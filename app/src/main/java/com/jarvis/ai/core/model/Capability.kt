package com.jarvis.ai.core.model

enum class Capability {
    TEXT_GENERATION,
    VISION,
    FUNCTION_CALLING,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH
}

data class ProviderModel(
    val id: String,
    val providerName: String,
    val capabilities: Set<Capability>,
    val priority: Int = 1
)
