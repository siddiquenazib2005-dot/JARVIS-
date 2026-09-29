package com.jarvis.ai.overlay.ambient

import android.view.Choreographer
import com.jarvis.ai.overlay.AvatarState
import com.jarvis.ai.overlay.AvatarStateBus
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Decides what the ambient entity is doing right now.
 *
 * Two inputs meet here:
 *  - the assistant's own turn state, published by the chat UI through
 *    [com.jarvis.ai.overlay.AvatarStateBus] (IDLE/LISTENING/THINKING/SPEAKING/ACTING), and
 *  - ambient lifecycle events (wake word, task completion, attention) raised
 *    directly by later ambient phases.
 *
 * It owns presence intensity — how visually prominent the entity is — and the
 * automatic decay of transient phases (SUCCESS/WARNING/ERROR/AWAKENING are
 * short-lived; the entity always settles back to the assistant's own state).
 * It does NOT execute anything: missions still run exclusively through the
 * existing MissionEngine path, this layer only presents.
 *
 * Pure JVM: time is injected, every rule is unit-testable.
 */
class AmbientPresenceController(
    private val now: () -> Long = System::currentTimeMillis,
    /** Global visual intensity multiplier (user setting), 0..1. */
    var animationIntensity: Float = 1f
) {
    @Volatile
    private var assistantPhase: AmbientPhase = AmbientPhase.from(AvatarStateBus.state())

    @Volatile
    private var transientPhase: AmbientPhase? = null

    @Volatile
    private var transientUntilMs: Long = 0L

    @Volatile
    private var minimizeRequested: Boolean = false

    private val lock = Any()

    /** The chat UI's state changed; transient phases always win over it. */
    fun onAssistantState(state: AvatarState) {
        assistantPhase = AmbientPhase.from(state)
    }

    /** Raises a short-lived phase (SUCCESS/WARNING/ERROR/AWAKENING/…). */
    fun pulse(phase: AmbientPhase, durationMs: Long) {
        require(phase != AmbientPhase.IDLE) { "IDLE is the absence of a pulse" }
        synchronized(lock) {
            transientPhase = phase
            transientUntilMs = now() + durationMs
        }
    }

    /**
     * Gesture/setting: park the entity until the next meaningful event.
     * Minimizing also CANCELS any running transient pulse — SLEEPING takes
     * over immediately (one visual state at a time, no animation stacking).
     */
    fun setMinimized(minimized: Boolean) {
        synchronized(lock) {
            minimizeRequested = minimized
            if (minimized) {
                transientPhase = null
                transientUntilMs = 0L
            }
        }
    }

    val minimized: Boolean get() = minimizeRequested

    /**
     * Snapshot for the renderer: which phase applies right now and how bright
     * it should be. Transient phases decay exactly once; afterwards the
     * assistant's own state (or SLEEPING when minimized) takes over again.
     */
    fun currentPhase(nowMs: Long = now()): AmbientPhase = synchronized(lock) {
        val transient = transientPhase
        if (transient != null) {
            if (nowMs < transientUntilMs) return transient
            transientPhase = null
            transientUntilMs = 0L
        }
        if (minimizeRequested) AmbientPhase.SLEEPING else assistantPhase
    }

    /**
     * Presence intensity 0..1 for the current phase: the user's animation
     * preference scaled by the phase's own baseline. SLEEPING is deliberately
     * faint so "almost invisible" is the default while nothing happens.
     */
    fun intensity(phase: AmbientPhase): Float =
        (phase.intensity * animationIntensity.coerceIn(0f, 1f)).coerceIn(0.05f, 1f)

    /** Transient phase still running? (Exposed for honest diagnostics.) */
    fun hasTransient(nowMs: Long = now()): Boolean = synchronized(lock) {
        transientPhase != null && nowMs < transientUntilMs
    }
}

/**
 * Single animation clock for the whole ambient layer.
 *
 * One Choreographer callback drives everything (spring, breathing, particles,
 * audio sampling) so the overlay never runs multiple background loops. When
 * [running] is false the chain stops — no hidden permanent loops, battery
 * respected. Frame pacing: active phases get every frame; low-power phases
 * (IDLE/SLEEPING) render every third frame, which is invisible for a slow
 * breathing orb and cuts overlay draw cost by ~66% while idle.
 */
class AmbientAnimationClock(
    private val onFrame: (timeMs: Long, deltaMs: Long) -> Unit
) {
    private var choreographer: Choreographer? = null
    private var running = false
    private var lastFrameMs = 0L
    private var lowPower = false
    private var skippedFrames = 0

    // Explicit SAM type: the body re-references this property, and without
    // the annotation Kotlin's type checker hits a recursive-declaration error.
    private val callback: Choreographer.FrameCallback = Choreographer.FrameCallback { frameMs ->
        if (!running) return@FrameCallback
        val delta = if (lastFrameMs == 0L) 16L else (frameMs - lastFrameMs).coerceIn(0L, 100L)
        lastFrameMs = frameMs
        var render = true
        if (lowPower) {
            skippedFrames = (skippedFrames + 1) % LOW_POWER_DIVISOR
            render = skippedFrames == 0
        }
        if (render) onFrame(frameMs, delta)
        choreographer?.postFrameCallback(this@AmbientAnimationClock.callback)
    }

    /** Starts the frame chain; safe to call repeatedly. */
    fun start(lowPowerPhase: Boolean) {
        if (running) {
            lowPower = lowPowerPhase
            return
        }
        running = true
        lowPower = lowPowerPhase
        skippedFrames = 0
        lastFrameMs = 0L
        choreographer = Choreographer.getInstance()
        choreographer?.postFrameCallback(callback)
    }

    /** Updates frame pacing without restarting the chain. */
    fun setLowPower(lowPowerPhase: Boolean) {
        lowPower = lowPowerPhase
    }

    /** Stops the frame chain entirely; nothing is scheduled afterwards. */
    fun stop() {
        running = false
        choreographer?.removeFrameCallback(callback)
        choreographer = null
    }

    val isRunning: Boolean get() = running

    private companion object {
        /** Low-power phases render every Nth Choreographer frame. */
        const val LOW_POWER_DIVISOR = 3
    }
}

/**
 * Bridges live audio amplitude from the app process to the overlay window.
 *
 * [com.jarvis.ai.viewmodel.JarvisViewModel.orbLevel] (real microphone RMS and
 * TTS envelope, already combined) publishes here; the overlay renderer samples
 * the latest value each frame through [AudioReactiveController]. The overlay
 * window cannot reach into the ViewModel, and the app must never depend on the
 * overlay being alive — a tiny shared bus keeps both directions decoupled.
 *
 * Single-writer, lock-free reads: a volatile float is atomic enough for an
 * animation input, and stale values are handled by the controller's decay.
 */
object AudioLevelBus {

    @Volatile
    private var level: Float = 0f

    private val listeners = CopyOnWriteArrayList<(Float) -> Unit>()

    /** Publishes the combined mic/TTS amplitude in 0..1. Called by the app. */
    fun publish(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        level = clamped
        listeners.forEach { runCatching { it(clamped) } }
    }

    /** Latest published level, for pollers that sample per frame. */
    fun latest(): Float = level

    /** Push-based notification for readers that prefer callbacks. */
    fun observe(listener: (Float) -> Unit) {
        listeners += listener
    }

    fun stopObserving(listener: (Float) -> Unit) {
        listeners -= listener
    }

    /** Clears energy when the entity hides or the service restarts. */
    fun reset() {
        level = 0f
    }
}
