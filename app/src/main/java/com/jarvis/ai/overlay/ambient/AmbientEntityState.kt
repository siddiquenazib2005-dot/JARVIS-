package com.jarvis.ai.overlay.ambient

import com.jarvis.ai.overlay.AvatarState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Visual phase of the ambient AURIX entity.
 *
 * This is the living-behaviour layer above [AvatarState]: the chat UI keeps
 * publishing the five assistant states through [com.jarvis.ai.overlay.AvatarStateBus]
 * and this enum maps each of them onto a distinct motion profile. The extra
 * phases (SLEEPING, AWAKENING, SUCCESS, …) already exist so later ambient
 * phases can publish them without touching the rendering code again.
 *
 * Every phase has a deterministic, time-driven motion profile — no random
 * jumping, no screensaver loops. Time is always supplied by the caller so the
 * whole profile is unit-testable on the JVM.
 */
enum class AmbientPhase(
    val label: String,
    /** Fraction of the radius added/removed by breathing, peak value. */
    val breathAmplitude: Float,
    /** One full breath cycle in milliseconds. */
    val breathPeriodMs: Long,
    /** Fraction of the radius traced by the slow micro-orbit, peak value. */
    val orbitAmplitude: Float,
    /** One full micro-orbit in milliseconds. */
    val orbitPeriodMs: Long,
    /** Baseline visual energy 0..1 (glow, particle alpha, ring speed). */
    val intensity: Float,
    /** How strongly live audio amplitude modulates this phase, 0..1. */
    val audioGain: Float,
    /** Low-power phases render at a reduced frame rate to save battery. */
    val lowPower: Boolean
) {
    /** Nothing happening: slow breathing, barely-there orbit, easy to ignore. */
    IDLE(
        label = "on standby",
        breathAmplitude = 0.035f,
        breathPeriodMs = 2800L,
        orbitAmplitude = 0.06f,
        orbitPeriodMs = 9000L,
        intensity = 0.45f,
        audioGain = 0.15f,
        lowPower = true
    ),

    /** Microphone open: the entity wakes up and reacts to live voice amplitude. */
    LISTENING(
        label = "listening",
        breathAmplitude = 0.05f,
        breathPeriodMs = 1400L,
        orbitAmplitude = 0.04f,
        orbitPeriodMs = 5000L,
        intensity = 0.95f,
        audioGain = 0.45f,
        lowPower = false
    ),

    /** A model call or tool chain is running: energy churns inside the core. */
    THINKING(
        label = "working on it",
        breathAmplitude = 0.04f,
        breathPeriodMs = 1000L,
        orbitAmplitude = 0.09f,
        orbitPeriodMs = 2200L,
        intensity = 0.80f,
        audioGain = 0.10f,
        lowPower = false
    ),

    /** AURIX is talking back: the shell pulses with TTS output amplitude. */
    SPEAKING(
        label = "speaking",
        breathAmplitude = 0.06f,
        breathPeriodMs = 900L,
        orbitAmplitude = 0.05f,
        orbitPeriodMs = 3000L,
        intensity = 0.90f,
        audioGain = 0.60f,
        lowPower = false
    ),

    /** A device action is executing on screen; visually distinct from THINKING. */
    ACTING(
        label = "acting on device",
        breathAmplitude = 0.04f,
        breathPeriodMs = 700L,
        orbitAmplitude = 0.07f,
        orbitPeriodMs = 1600L,
        intensity = 0.95f,
        audioGain = 0.10f,
        lowPower = false
    ),

    /** Deeply minimized presence: tiny, very slow breathing only. */
    SLEEPING(
        label = "resting",
        breathAmplitude = 0.02f,
        breathPeriodMs = 4200L,
        orbitAmplitude = 0.02f,
        orbitPeriodMs = 14000L,
        intensity = 0.25f,
        audioGain = 0.0f,
        lowPower = true
    ),

    /** Reconstructing after a wake event: fast, bright, briefly expansive. */
    AWAKENING(
        label = "awakening",
        breathAmplitude = 0.10f,
        breathPeriodMs = 900L,
        orbitAmplitude = 0.12f,
        orbitPeriodMs = 1200L,
        intensity = 1.00f,
        audioGain = 0.20f,
        lowPower = false
    ),

    /** Short completion pulse; presence controller returns to IDLE afterwards. */
    SUCCESS(
        label = "done",
        breathAmplitude = 0.08f,
        breathPeriodMs = 600L,
        orbitAmplitude = 0.06f,
        orbitPeriodMs = 1800L,
        intensity = 1.00f,
        audioGain = 0.10f,
        lowPower = false
    ),

    /** Controlled attention pulse — noticeable, never an annoying flash. */
    WARNING(
        label = "attention",
        breathAmplitude = 0.07f,
        breathPeriodMs = 1100L,
        orbitAmplitude = 0.05f,
        orbitPeriodMs = 2000L,
        intensity = 0.80f,
        audioGain = 0.10f,
        lowPower = false
    ),

    /** Brief glitch-like distortion; automatically settles back to IDLE. */
    ERROR(
        label = "something failed",
        breathAmplitude = 0.09f,
        breathPeriodMs = 500L,
        orbitAmplitude = 0.10f,
        orbitPeriodMs = 900L,
        intensity = 0.85f,
        audioGain = 0.0f,
        lowPower = false
    );

    companion object {
        /**
         * Maps the chat-published [AvatarState] onto a motion profile. The
         * priority ladder lives in [AvatarState.from]; by the time a state
         * reaches this layer it is already the winning one.
         */
        fun from(avatar: AvatarState): AmbientPhase = when (avatar) {
            AvatarState.IDLE -> IDLE
            AvatarState.LISTENING -> LISTENING
            AvatarState.THINKING -> THINKING
            AvatarState.SPEAKING -> SPEAKING
            AvatarState.ACTING -> ACTING
        }
    }
}

/**
 * Smoothed, attack/release audio-reactive level for the ambient entity.
 *
 * The real amplitude comes from [com.jarvis.ai.viewmodel.JarvisViewModel.orbLevel]
 * (mic RMS from [com.jarvis.ai.service.AudioLevelEngine] combined with the TTS
 * envelope) and reaches this class through the overlay audio bus. Nothing here
 * fakes audio: when no updates arrive within [staleAfterMs] the level decays
 * to silence, so a muted mic or stopped TTS never leaves a looping animation.
 *
 * Pure JVM: time is injected, so tests drive exact frames.
 */
class AudioReactiveController(
    private val now: () -> Long = System::currentTimeMillis,
    /** Fraction of the gap closed per sample when the level rises. */
    private val attack: Float = 0.50f,
    /** Fraction of the gap closed per sample when the level falls. */
    private val release: Float = 0.10f,
    /** Updates older than this are treated as silence. */
    private val staleAfterMs: Long = 250L
) {
    @Volatile
    private var sourceLevel: Float = 0f

    @Volatile
    private var sourceAtMs: Long = 0L

    @Volatile
    private var smoothed: Float = 0f

    private val lock = Any()

    /** Publishes a fresh amplitude in 0..1 from the app process. */
    fun updateSource(level: Float, atMs: Long = now()) {
        sourceLevel = level.coerceIn(0f, 1f)
        sourceAtMs = atMs
    }

    /**
     * Advances the smoothed level to [nowMs] and returns it in 0..1.
     * Called once per animation frame by the renderer's clock.
     */
    fun sample(nowMs: Long = now()): Float = synchronized(lock) {
        val stale = nowMs - sourceAtMs > staleAfterMs
        val target = if (stale) 0f else sourceLevel
        val coefficient = if (target > smoothed) attack else release
        smoothed += (target - smoothed) * coefficient
        if (smoothed < 0.005f) 0f else smoothed.coerceIn(0f, 1f)
    }

    /** Forgets all energy — used when the entity hides or the service restarts. */
    fun reset() {
        synchronized(lock) {
            sourceLevel = 0f
            smoothed = 0f
            sourceAtMs = 0L
        }
    }
}

/**
 * Deterministic particle field for the holographic shell.
 *
 * Positions are a pure function of (index, timeMs, audio) — no allocation,
 * no randomness at draw time, so frames are reproducible and the math is
 * unit-testable on the JVM. The golden angle spreads particles evenly so
 * low particle counts still look uniformly distributed.
 */
object AmbientParticles {

    const val GOLDEN_ANGLE = 2.39996322972865332f

    /**
     * Writes [count] particle positions into [out] as (x, y) pairs in unit
     * entity space (radius 1 = entity edge, scaled ellipses give 2.5D depth).
     * Returns the number of pairs written. [out] must hold 2 * [count] floats.
     */
    fun positions(
        count: Int,
        timeMs: Long,
        audio: Float,
        out: FloatArray
    ): Int {
        var written = 0
        for (index in 0 until count) {
            if (2 * index + 1 >= out.size) break
            val seed = index * GOLDEN_ANGLE
            // Speed varies per particle and multiplies up with voice energy.
            val speed = 0.00022f + fraction(index, 7919) * 0.00045f
            val angle = seed + timeMs * speed * (1f + audio * 1.4f)
            val baseRadius = 0.45f + fraction(index, 104729) * 0.45f
            val wobble = sin(timeMs * 0.0011f + index * 1.7f) * 0.05f
            val radius = baseRadius + wobble + audio * 0.08f
            // Elliptical squash: reads as orbital depth (2.5D) on a flat canvas.
            out[2 * index] = cos(angle) * radius
            out[2 * index + 1] = sin(angle) * radius * 0.82f
            written++
        }
        return written
    }

    /** Stable per-index pseudo-random fraction in 0..1 (no runtime RNG). */
    private fun fraction(index: Int, salt: Int): Float =
        ((index * salt) % 1000) / 1000f

    /**
     * Particle budget. Deliberately small: the shell reads as volumetric with
     * ~2 dozen particles, and low-power devices drop to half without looking
     * broken. Scaled by the user's particle-intensity preference.
     */
    fun count(lowPower: Boolean, particleIntensity: Float): Int {
        val base = if (lowPower) 14 else 26
        return max(6, (base * particleIntensity.coerceIn(0f, 1f)).toInt())
    }
}
