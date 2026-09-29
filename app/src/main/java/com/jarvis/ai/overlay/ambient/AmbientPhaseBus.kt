package com.jarvis.ai.overlay.ambient

/**
 * Publishes the assistant's interaction phase (driven by the chat UI's own
 * state) so background listeners — specifically the wake-word provider — can
 * pause while the microphone or the conversation is owned by someone else.
 *
 * Pure JVM, no Android imports: values are written by ChatScreen's existing
 * state-sync LaunchedEffect and read by WakeWordService. A timestamp is kept
 * for diagnostics; the wake gate currently consumes the state value only.
 */
object AmbientPhaseBus {

    @Volatile
    private var current: AmbientPhase = AmbientPhase.IDLE

    @Volatile
    private var updatedAtMs: Long = 0L

    /** Publishes a new phase. No-op when the phase has not changed. */
    fun publish(phase: AmbientPhase, nowMs: Long = System.currentTimeMillis()) {
        if (phase == current) return
        current = phase
        updatedAtMs = nowMs
    }

    /** The last published phase (defaults to IDLE before any publish). */
    fun current(): AmbientPhase = current

    /** When the phase last changed (diagnostics/tests). */
    fun lastChangedAtMs(): Long = updatedAtMs

    /** Test/diagnostic reset. */
    fun reset() {
        current = AmbientPhase.IDLE
        updatedAtMs = 0L
    }
}
