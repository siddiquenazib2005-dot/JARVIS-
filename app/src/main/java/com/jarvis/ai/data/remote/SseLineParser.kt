package com.jarvis.ai.data.remote

sealed class SseEvent {
    data class DataPayload(val rawJson: String) : SseEvent()
    object Done : SseEvent()
    object Ignore : SseEvent()
}

object SseLineParser {

    fun parse(line: String): SseEvent {
        val trimmed = line.trimEnd('\r', '\n')
        if (trimmed.isEmpty()) return SseEvent.Ignore
        if (trimmed.startsWith(":") ||
            trimmed.startsWith("event:") ||
            trimmed.startsWith("id:") ||
            trimmed.startsWith("retry:")
        ) {
            return SseEvent.Ignore
        }
        if (!trimmed.startsWith("data:")) return SseEvent.Ignore
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return SseEvent.Ignore
        return if (payload == DONE_SENTINEL) SseEvent.Done else SseEvent.DataPayload(payload)
    }

    fun parseAll(raw: String): List<SseEvent> =
        raw.split('\n').map { parse(it) }.filterNot { it is SseEvent.Ignore }

    private const val DONE_SENTINEL = "[DONE]"
}
