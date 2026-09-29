package com.jarvis.ai.overlay.ambient

import kotlin.math.exp

/**
 * Procedural shell forms of the ambient entity (Phase D).
 *
 * The entity must not look like a static orb: each assistant phase maps to a
 * distinct geometry and the renderer morphs continuously between them — never
 * a hard cut. A form is a small set of scalar profile parameters, so morphing
 * is plain number interpolation (cheap, smooth, unit-testable on the JVM; the
 * Canvas side just draws whatever the current parameters describe).
 *
 * Mapping (directive §PHASE D):
 *   IDLE/SLEEPING      -> ORB
 *   AWAKENING          -> ENERGY_RING
 *   LISTENING          -> LISTENING_FIELD
 *   THINKING/ACTING    -> THINKING_CORE
 *   SPEAKING/ERROR     -> VOICE_FORM (most deformable = glitch-friendliest)
 *   SUCCESS/WARNING    -> ORB (phase breathing already carries the pulse)
 */
enum class AmbientForm(
    val label: String,
    /** Overall size multiplier applied to the base radius. */
    val scale: Float,
    /** Shell squash: 1 = sphere, lower = flatter energy field/ring. */
    val squash: Float,
    /** Fraction of the radius drawn hollow (0 = filled orb, 0.5 = ring). */
    val ringHollowness: Float,
    /** Base shell/ring rotation speed, radians per second. */
    val spinSpeed: Float,
    /** Wobble of the shell outline, as a fraction of the radius. */
    val deformation: Float
) {
    /** The resting identity: a breathing orb. */
    ORB("orb", scale = 1.00f, squash = 1.00f, ringHollowness = 0.00f, spinSpeed = 0.35f, deformation = 0.02f),

    /** Awakening: bright, hollow, fast-spinning energy ring. */
    ENERGY_RING("energy ring", scale = 1.18f, squash = 0.72f, ringHollowness = 0.55f, spinSpeed = 2.40f, deformation = 0.06f),

    /** Listening: expanded field that ripples with microphone amplitude. */
    LISTENING_FIELD("listening field", scale = 1.10f, squash = 0.88f, ringHollowness = 0.20f, spinSpeed = 1.10f, deformation = 0.10f),

    /** Thinking: compact dense core with fast internal churn. */
    THINKING_CORE("thinking core", scale = 0.94f, squash = 1.04f, ringHollowness = 0.10f, spinSpeed = 1.70f, deformation = 0.05f),

    /** Speaking: waveform-like form driven by the TTS envelope. */
    VOICE_FORM("voice form", scale = 1.06f, squash = 0.94f, ringHollowness = 0.05f, spinSpeed = 0.90f, deformation = 0.14f);

    companion object {
        /** Phase -> form mapping. Kept total so every phase has a geometry. */
        fun from(phase: AmbientPhase): AmbientForm = when (phase) {
            AmbientPhase.IDLE -> ORB
            AmbientPhase.SLEEPING -> ORB
            AmbientPhase.AWAKENING -> ENERGY_RING
            AmbientPhase.LISTENING -> LISTENING_FIELD
            AmbientPhase.THINKING -> THINKING_CORE
            AmbientPhase.ACTING -> THINKING_CORE
            AmbientPhase.SPEAKING -> VOICE_FORM
            AmbientPhase.ERROR -> VOICE_FORM
            AmbientPhase.SUCCESS -> ORB
            AmbientPhase.WARNING -> ORB
        }
    }
}

/** Immutable interpolated parameter set the renderer draws for one frame. */
data class MorphSnapshot(
    val scale: Float,
    val squash: Float,
    val ringHollowness: Float,
    val spinSpeed: Float,
    val deformation: Float,
    /** Form the parameters are converging toward. */
    val targetForm: AmbientForm
)

/**
 * Continuous morph state between forms (Phase D: no hard cuts).
 *
 * Parameters approach the target form exponentially — the same smooth
 * ease-out used everywhere else in the ambient layer, frame-rate independent
 * and stable under jank. The renderer retargets on every phase change and
 * advances once per frame; because every parameter interpolates independently,
 * a transition reads as the entity *becoming* the next form.
 */
class AmbientFormMorph(
    /** Time constant of the exponential approach, in milliseconds. */
    private val tauMs: Float = MORPH_TAU_MS
) {
    private var scale: Float = AmbientForm.ORB.scale
    private var squash: Float = AmbientForm.ORB.squash
    private var ringHollowness: Float = AmbientForm.ORB.ringHollowness
    private var spinSpeed: Float = AmbientForm.ORB.spinSpeed
    private var deformation: Float = AmbientForm.ORB.deformation

    private var currentForm: AmbientForm = AmbientForm.ORB

    /** The form currently being approached. */
    val form: AmbientForm get() = currentForm

    /** Snaps all parameters instantly. Used when the overlay first appears. */
    fun snapTo(form: AmbientForm) {
        currentForm = form
        scale = form.scale
        squash = form.squash
        ringHollowness = form.ringHollowness
        spinSpeed = form.spinSpeed
        deformation = form.deformation
    }

    /** Retargets the morph. Same form = no-op; different = smooth transition. */
    fun target(form: AmbientForm) {
        currentForm = form
    }

    /** Advances the exponential approach by [dtMs] of wall time. */
    fun advance(dtMs: Long) {
        if (dtMs <= 0L) return
        val k = 1f - exp(-dtMs / tauMs)
        scale += (currentForm.scale - scale) * k
        squash += (currentForm.squash - squash) * k
        ringHollowness += (currentForm.ringHollowness - ringHollowness) * k
        spinSpeed += (currentForm.spinSpeed - spinSpeed) * k
        deformation += (currentForm.deformation - deformation) * k
    }

    /** Snapshot for rendering. */
    fun snapshot(): MorphSnapshot = MorphSnapshot(
        scale = scale,
        squash = squash,
        ringHollowness = ringHollowness,
        spinSpeed = spinSpeed,
        deformation = deformation,
        targetForm = currentForm
    )

    companion object {
        /** ~220ms time constant: quick enough to read, slow enough to be smooth. */
        const val MORPH_TAU_MS = 220f
    }
}
