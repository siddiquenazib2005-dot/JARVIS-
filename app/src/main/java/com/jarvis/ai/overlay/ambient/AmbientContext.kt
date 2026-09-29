package com.jarvis.ai.overlay.ambient

import com.jarvis.ai.overlay.AvatarState
import com.jarvis.ai.overlay.AvatarStateBus

/**
 * Phase E: lightweight screen-context awareness for the ambient entity.
 *
 * The context layer uses ONLY signals AURIX legitimately already has:
 *  - its own assistant phase (listening/thinking/speaking) via the bus,
 *  - whether the user is currently touching the companion (its own view),
 *  - ambient lifecycle events raised inside AURIX (important event pulse),
 *  - the minimize/sleep control.
 *
 * No screen monitoring, no screenshots, no content inspection — the enum
 * below only modulates how big/bright the entity renders and nothing else.
 *
 * Resolution order (exactly ONE winner — no animation stacking):
 *   IMPORTANT_EVENT (transient) > SLEEPING > USER_INTERACTING > phase-mapped
 *   state > NORMAL_IDLE
 */
enum class AmbientContextState(
    val label: String,
    /** Size multiplier applied to the entity radius. */
    val scaleMultiplier: Float,
    /** Brightness/energy multiplier applied to the presence intensity. */
    val intensityMultiplier: Float
) {
    /** Nothing happening: small and subtle, easy to ignore. */
    NORMAL_IDLE("ambient", scaleMultiplier = 0.92f, intensityMultiplier = 0.75f),

    /** The user is touching/dragging the entity: slightly more present. */
    USER_INTERACTING("attentive", scaleMultiplier = 1.02f, intensityMultiplier = 1.00f),

    /** Microphone open: expanded so the voice field is readable. */
    LISTENING("listening", scaleMultiplier = 1.08f, intensityMultiplier = 1.00f),

    /** Working: normal size, active internal motion. */
    THINKING("processing", scaleMultiplier = 1.00f, intensityMultiplier = 0.90f),

    /** Talking: voice-reactive, slightly expanded. */
    SPEAKING("speaking", scaleMultiplier = 1.05f, intensityMultiplier = 1.00f),

    /** Important event: temporarily larger and brighter, then decays. */
    IMPORTANT_EVENT("alert", scaleMultiplier = 1.15f, intensityMultiplier = 1.00f),

    /** Minimized: almost invisible. */
    SLEEPING("resting", scaleMultiplier = 0.70f, intensityMultiplier = 0.35f);

    companion object {
        /** Base mapping from the assistant's own phase. */
        fun fromPhase(phase: AmbientPhase): AmbientContextState = when (phase) {
            AmbientPhase.LISTENING -> LISTENING
            AmbientPhase.THINKING, AmbientPhase.ACTING -> THINKING
            AmbientPhase.SPEAKING -> SPEAKING
            AmbientPhase.SLEEPING -> SLEEPING
            else -> NORMAL_IDLE
        }
    }
}

/** Immutable per-frame visual profile derived from the context state. */
data class AmbientContextProfile(
    val state: AmbientContextState,
    val scaleMultiplier: Float,
    val intensityMultiplier: Float
) {
    companion object {
        /** Neutral profile used before the service feeds the renderer. */
        val DEFAULT = AmbientContextProfile(
            state = AmbientContextState.NORMAL_IDLE,
            scaleMultiplier = 1f,
            intensityMultiplier = 1f
        )
    }
}

/**
 * Tracks the current context state and resolves it to a visual profile.
 *
 * Pure JVM: time is injected, every rule is unit-testable. The overlay service
 * feeds it phase changes, touch events and important-event pulses; the renderer
 * only consumes [profile].
 */
class AmbientContextController(
    private val now: () -> Long = System::currentTimeMillis,
    /** How long an important-event presence boost lasts by default. */
    private val importantEventMs: Long = DEFAULT_IMPORTANT_EVENT_MS
) {
    @Volatile
    private var phase: AmbientPhase = AmbientPhase.from(AvatarStateBus.state())

    @Volatile
    private var interacting: Boolean = false

    @Volatile
    private var sleeping: Boolean = false

    @Volatile
    private var importantUntilMs: Long = 0L

    private val lock = Any()

    /** The assistant's phase changed (already the winning one). */
    fun onPhase(current: AmbientPhase) {
        phase = current
    }

    /** User touch began/ended on the companion window. */
    fun setInteracting(value: Boolean) {
        interacting = value
    }

    /** Minimize control: parks the entity in SLEEPING until woken. */
    fun setSleeping(value: Boolean) {
        sleeping = value
    }

    /** Raises the transient IMPORTANT_EVENT presence boost. */
    fun importantEvent(nowMs: Long = now()) {
        synchronized(lock) { importantUntilMs = nowMs + importantEventMs }
    }

    /** Resolves the single winning context state. */
    fun current(nowMs: Long = now()): AmbientContextState = synchronized(lock) {
        if (nowMs < importantUntilMs) return AmbientContextState.IMPORTANT_EVENT
        if (sleeping) return AmbientContextState.SLEEPING
        if (interacting) return AmbientContextState.USER_INTERACTING
        val mapped = AmbientContextState.fromPhase(phase)
        // An interactive phase is more specific than the generic idle look.
        if (mapped == AmbientContextState.NORMAL_IDLE && interacting) {
            return AmbientContextState.USER_INTERACTING
        }
        mapped
    }

    /** Visual profile for the renderer. */
    fun profile(nowMs: Long = now()): AmbientContextProfile {
        val state = current(nowMs)
        return AmbientContextProfile(
            state = state,
            scaleMultiplier = state.scaleMultiplier,
            intensityMultiplier = state.intensityMultiplier
        )
    }

    companion object {
        const val DEFAULT_IMPORTANT_EVENT_MS = 2500L
    }
}
