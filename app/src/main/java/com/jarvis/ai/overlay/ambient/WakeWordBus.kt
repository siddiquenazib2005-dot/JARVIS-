package com.jarvis.ai.overlay.ambient

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase G bridge: the EXISTING [com.jarvis.ai.service.WakeWordService] (the
 * only microphone/wake-word pipeline in the app) announces detections here and
 * the ambient overlay awakens. Nothing else changes: the service still opens
 * MainActivity exactly as before; the bus is a pure notification side-channel
 * so the orb can play its AWAKENING animation.
 *
 * Deliberately tiny:
 * - No second mic, no second recognizer, no new permissions.
 * - Debounced: recognizer partial results can fire several times for one
 *   utterance; only the first event inside [DEBOUNCE_MS] reaches listeners.
 * - Listeners are removed by the overlay in onDestroy — no leaks.
 */
object WakeWordBus {

    /** Minimum gap between two accepted wake events. */
    const val DEBOUNCE_MS = 1200L

    @Volatile
    private var lastAcceptedAtMs: Long = 0L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Announces a wake-word detection. Returns true when the event was
     * accepted (first inside the debounce window) and listeners were notified.
     */
    fun announce(nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = lastAcceptedAtMs
        if (nowMs - last < DEBOUNCE_MS) return false
        lastAcceptedAtMs = nowMs
        listeners.forEach { runCatching { it() } }
        return true
    }

    /** Time of the last accepted detection (diagnostics/tests). */
    fun lastAcceptedAtMs(): Long = lastAcceptedAtMs

    /** Registers an overlay listener. Call [stopObserving] on destroy. */
    fun observe(listener: () -> Unit) {
        listeners += listener
    }

    fun stopObserving(listener: () -> Unit) {
        listeners -= listener
    }

    /** Test/diagnostic reset. */
    fun reset() {
        lastAcceptedAtMs = 0L
        listeners.clear()
    }
}
