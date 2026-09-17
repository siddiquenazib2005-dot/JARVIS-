package com.jarvis.ai.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class EventType {
    APP_STARTED, SESSION_STARTED, USER_INPUT, INTENT_DETECTED,
    PLAN_CREATED, TOOL_SELECTED, ACTION_STARTED, ACTION_COMPLETED,
    VERIFICATION_PASSED, MEMORY_UPDATED, MODEL_SELECTED,
    PROVIDER_FAILED, FALLBACK_TRIGGERED, TTS_STARTED, TTS_INTERRUPTED,
    DEVICE_ACTION, TASK_COMPLETED,
    ACCESSIBILITY_CONNECTED, ACCESSIBILITY_DISCONNECTED
}

data class SystemEvent(
    val type: EventType,
    val detail: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Central decoupled event bus. Modules subscribe without knowing each other.
 * Bounded history for diagnostics; secrets must never be placed in details.
 */
object EventBus {

    private val listeners = ConcurrentHashMap<EventType, CopyOnWriteArrayList<(SystemEvent) -> Unit>>()
    private val history = ArrayDeque<SystemEvent>(100)

    fun subscribe(type: EventType, listener: (SystemEvent) -> Unit): () -> Unit {
        listeners.getOrPut(type) { CopyOnWriteArrayList() }.add(listener)
        return { listeners[type]?.remove(listener) }
    }

    fun publish(type: EventType, detail: String = "") {
        val event = SystemEvent(type, detail.take(200))
        synchronized(history) {
            if (history.size >= 100) history.removeFirst()
            history.addLast(event)
        }
        listeners[event.type]?.forEach { runCatching { it(event) } }
        // NOTE: this object used to also re-dispatch a TASK_COMPLETED event to
        // ACTION_COMPLETED listeners. That cross-wiring had no production
        // subscriber on either side, and would have silently double-fired any
        // future ACTION_COMPLETED listener. Removed rather than left to bite.
    }

    @Synchronized
    fun recent(count: Int): List<SystemEvent> = history.toList().takeLast(count)

    @Synchronized
    fun clear() = history.clear()
}
