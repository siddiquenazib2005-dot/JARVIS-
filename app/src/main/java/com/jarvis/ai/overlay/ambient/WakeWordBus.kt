package com.jarvis.ai.overlay.ambient

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase G bridge (extended by the wake-word engine phase): the
 * [com.jarvis.ai.service.WakeWordService] announces provider detections here
 * and the ambient overlay awakens. Nothing else changes: the service still
 * opens MainActivity exactly as before; the bus is a pure notification
 * side-channel so the orb can play its AWAKENING animation.
 *
 * Deliberately tiny:
 * - No second mic, no second recognizer, no new permissions.
 * - Debounced: engine events can fire several times for one utterance; only
 *   the first event inside [DEBOUNCE_MS] reaches listeners.
 * - Multiple listeners are supported (overlay + in-app surfaces) and are
 *   removed by their owners — no leaks.
 * - [phasePublish] mirrors the accepted detection into [AmbientPhaseBus] so
 *   the wake provider pauses during AWAKENING/LISTENING/THINKING/SPEAKING.
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
     * The accepted event is also published to [AmbientPhaseBus] as AWAKENING.
     * Synchronized so concurrent engine events can never double-pass the
     * debounce window.
     */
    @Synchronized
    fun announce(nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = lastAcceptedAtMs
        if (nowMs - last < DEBOUNCE_MS) return false
        lastAcceptedAtMs = nowMs
        listeners.forEach { runCatching { it() } }
        phasePublish(published = true)
        return true
    }

    /** Time of the last accepted detection (diagnostics/tests). */
    fun lastAcceptedAtMs(): Long = lastAcceptedAtMs

    /** Registers a listener. Call [stopObserving] on destroy. */
    fun observe(listener: () -> Unit) {
        listeners += listener
    }

    fun stopObserving(listener: () -> Unit) {
        listeners -= listener
    }

    /**
     * Publishes the assistant phase so the wake provider can gate itself.
     * Returns true when the publish was accepted (first event inside the
     * [DEBOUNCE_MS] window) — same rule as [announce], mirrored for phases.
     */
    fun phasePublish(published: Boolean): Boolean {
        if (!published) return false
        AmbientPhaseBus.publish(AmbientPhase.AWAKENING, lastAcceptedAtMs)
        return true
    }

    /** Test/diagnostic reset. */
    @Synchronized
    fun reset() {
        lastAcceptedAtMs = 0L
        listeners.clear()
    }
}
