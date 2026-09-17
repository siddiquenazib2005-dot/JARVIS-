package com.jarvis.ai.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class Sender(val label: String) {
    USER("You"),
    AURIX("AURIX")
}

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false
)

data class SessionInfo(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New session",
    val updatedAt: Long = System.currentTimeMillis()
)

data class LatencyInfo(
    val firstTokenMs: Long? = null,
    val totalMs: Long? = null
)

data class UiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val apiKeyMissing: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val activeSessionId: String = "",
    val latency: LatencyInfo = LatencyInfo(),
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val notice: String? = null,
    /** True while a tool is awaiting explicit user confirmation (SEND confirms it). */
    val hasPendingConfirmation: Boolean = false,
    /** Provider that served the last reply (from the router's execution report). */
    val activeProvider: String = "",
    /** True when at least one provider key is provisioned in the secure backend. */
    val backendOnline: Boolean = false
)
