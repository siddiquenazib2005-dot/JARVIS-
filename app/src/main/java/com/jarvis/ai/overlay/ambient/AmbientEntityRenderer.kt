package com.jarvis.ai.overlay.ambient

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything the renderer needs for one frame, assembled by the service from
 * the ambient controllers. Keeping it a plain data class means the view never
 * touches the controllers and rendering stays a pure draw call.
 */
data class AmbientFrame(
    val phase: AmbientPhase,
    val form: MorphSnapshot,
    val context: AmbientContextProfile,
    val audio: Float,
    val timeMs: Long,
    /** Ms left on the active transient pulse, 0 when none (drives H effects). */
    val pulseRemainingMs: Long = 0L
) {
    companion object {
        /** Used before the first clock tick arrives (startup frame). */
        fun initial(): AmbientFrame = AmbientFrame(
            phase = AmbientPhase.IDLE,
            form = MorphSnapshot(
                scale = AmbientForm.ORB.scale,
                squash = AmbientForm.ORB.squash,
                ringHollowness = AmbientForm.ORB.ringHollowness,
                spinSpeed = AmbientForm.ORB.spinSpeed,
                deformation = AmbientForm.ORB.deformation,
                targetForm = AmbientForm.ORB
            ),
            context = AmbientContextProfile.DEFAULT,
            audio = 0f,
            timeMs = 0L
        )
    }
}

/**
 * 2.5D holographic renderer with Phase D shell morphing.
 *
 * Procedural Canvas rendering (no static PNG): volumetric glow, morphing
 * shell (orb ↔ energy ring ↔ listening field ↔ thinking core ↔ voice form),
 * chromatic fringe, counter-rotating orbital rings, a deterministic particle
 * field and a gradient energy core. All layers are pure functions of the
 * supplied [AmbientFrame] — the entity is never drawn from hidden state, so
 * every visual decision stays testable through the pure-JVM form/context
 * classes.
 *
 * Draw cost per frame: ~2 gradient circles, one squashed local-canvas block
 * (shell + fringe + rings + core), N ≤ 26 particles. The local-canvas
 * translate/scale gives the whole entity its 2.5D squash without any
 * per-layer math and without allocations.
 */
class AmbientEntityRenderer(
    private val presence: AmbientPresenceController
) {
    // Layered paints, allocated once.
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fringe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var coreGradient: RadialGradient? = null
    private var lastGradientSize = 0f
    private var lastGradientColor = 0

    private var particleBuffer = FloatArray(2 * AmbientParticles.count(false, 1f))

    /**
     * Renders one frame of the entity centred at (cx, cy) with base [radius].
     * Phase drives colour + breathing, the morph snapshot drives geometry,
     * the context profile drives size/intensity, audio drives reactivity.
     */
    fun render(canvas: Canvas, cx: Float, cy: Float, radius: Float, frame: AmbientFrame) {
        val phase = frame.phase
        val form = frame.form
        val context = frame.context
        val audio = frame.audio.coerceIn(0f, 1f) * phase.audioGain
        val timeMs = frame.timeMs

        // --- Size: base radius × context × form × breathing × voice ----------
        val breath = breathScale(phase, timeMs)
        val voice = 1f + audio * VOICE_EXPANSION
        val r = radius * context.scaleMultiplier * form.scale * breath * voice
        val intensity = (presence.intensity(phase) * context.intensityMultiplier)
            .coerceIn(0.02f, 1f)

        val shellColor = shellColor(phase)
        val coreColor = coreColor(phase)

        // Micro-orbit: subtle drift so the entity feels alive while parked.
        val orbitAngle = (timeMs % phase.orbitPeriodMs).toFloat() / phase.orbitPeriodMs * TAU
        var ox = cx + cos(orbitAngle) * phase.orbitAmplitude * radius
        var oy = cy + sin(orbitAngle) * phase.orbitAmplitude * radius * 0.6f

        // Phase H — ERROR glitch: brief deterministic positional jitter while
        // the pulse lives; it dies with the pulse, never loops forever.
        if (phase == AmbientPhase.ERROR && frame.pulseRemainingMs > 0L) {
            val env = (frame.pulseRemainingMs.toFloat() / ERROR_GLITCH_SPAN_MS).coerceIn(0f, 1f)
            ox += sin(timeMs * 0.089f) * r * 0.05f * env
            oy += cos(timeMs * 0.113f) * r * 0.04f * env
        }

        // --- Layer 1: volumetric glow ----------------------------------------
        glow.color = shellColor
        glow.alpha = (26 + 54 * intensity).toInt()
        drawSquashedCircle(canvas, ox, oy, r * 1.28f, form.squash, glow)
        glow.alpha = (18 + 40 * intensity).toInt()
        drawSquashedCircle(canvas, ox, oy, r * 1.55f, form.squash, glow)

        // --- Local squashed canvas: shell, fringe, rings, core ---------------
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(1f, form.squash.coerceAtLeast(0.30f))

        // Shell: hollow forms (energy ring / listening field) push the stroke
        // outward and drop the fill; filled forms keep the glass disc.
        val hollowness = form.ringHollowness.coerceIn(0f, 1f)
        if (hollowness < 0.4f) {
            shellFill.color = shellColor
            shellFill.alpha = ((16 + 26 * intensity) * (1f - hollowness)).toInt()
            canvas.drawCircle(0f, 0f, r * 0.88f, shellFill)
        }

        // Audio-reactive, deformation-reactive shell arcs. The wobble makes
        // VOICE_FORM look waveform-like and LISTENING_FIELD ripple with the mic.
        val segments = if (phase.lowPower) 4 else SHELL_SEGMENTS
        shellStroke.strokeWidth = (r * 0.05f).coerceAtLeast(1f)
        shellStroke.color = shellColor
        shellStroke.alpha = (150 + 80 * intensity).toInt()
        val strokeRadius = r * (0.86f + 0.14f * hollowness + audio * 0.05f)
        var index = 0
        while (index < segments) {
            val segAngle = index.toFloat() / segments * TAU
            val ripple = sin(segAngle * 3f + timeMs * 0.004f) *
                (form.deformation + audio * 0.10f) * r
            val segRadius = strokeRadius + ripple
            val sweep = TAU / segments + 0.16f
            canvas.drawArc(
                -segRadius, -segRadius, segRadius, segRadius,
                segAngle * RAD_TO_DEG, sweep * RAD_TO_DEG, false, shellStroke
            )
            index++
        }

        // Chromatic separation: two thin offset strokes — holographic fringing.
        val fringeOffset = r * 0.035f
        fringe.strokeWidth = (r * 0.02f).coerceAtLeast(0.5f)
        fringe.alpha = (46 * intensity).toInt()
        fringe.color = FRINGE_COOL
        canvas.drawCircle(-fringeOffset, 0f, r * 0.92f, fringe)
        fringe.color = FRINGE_WARM
        canvas.drawCircle(fringeOffset, 0f, r * 0.92f, fringe)

        // Orbital rings: squashed ellipses, counter-rotating, spin from the form.
        val spin = timeMs * 0.001f * form.spinSpeed
        drawRing(canvas, r * 1.02f * (1f + audio * 0.05f), 0.34f, spin, intensity, shellColor)
        drawRing(canvas, r * 0.74f, -0.22f, -spin * 1.3f, intensity, coreColor)

        // Inner energy core: smaller on hollow forms, pulses with the voice.
        val coreRadius = r * (0.34f - 0.10f * hollowness + audio * 0.10f)
        ensureGradient(coreRadius, coreColor)
        corePaint.shader = coreGradient
        canvas.drawCircle(0f, 0f, coreRadius, corePaint)
        corePaint.shader = null

        canvas.restore()

        // --- Particles (unsquashed draw space, own 0.82 ellipse) -------------
        val count = AmbientParticles.count(phase.lowPower, presence.animationIntensity)
        if (particleBuffer.size < 2 * count) particleBuffer = FloatArray(2 * count)
        val written = AmbientParticles.positions(count, timeMs, audio, particleBuffer)
        particlePaint.color = coreColor
        particlePaint.alpha = (90 + 120 * intensity).toInt()
        // Phase H — AWAKENING: the entity reconstructs itself from light, so
        // particles start as a wide cloud and converge as the pulse spends.
        val spread = if (phase == AmbientPhase.AWAKENING && frame.pulseRemainingMs > 0L) {
            1f + 0.8f * (frame.pulseRemainingMs.toFloat() / AWAKENING_SPAN_MS).coerceIn(0f, 1f)
        } else 1f
        var i = 0
        while (i < written) {
            val px = particleBuffer[2 * i]
            val py = particleBuffer[2 * i + 1]
            val size = r * 0.045f * (0.7f + ((i * 37) % 100) / 100f)
            canvas.drawCircle(ox + px * r * spread, oy + py * r * spread, size, particlePaint)
            i++
        }

        // --- Bright centre dot (in squashed space, drawn last) ----------------
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(1f, form.squash.coerceAtLeast(0.30f))
        corePaint.color = Color.WHITE
        corePaint.alpha = (150 + 90 * intensity).toInt()
        canvas.drawCircle(0f, 0f, r * (0.07f + audio * 0.04f), corePaint)
        corePaint.alpha = 255
        canvas.restore()
    }

    private fun drawSquashedCircle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        squash: Float,
        paint: Paint
    ) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(1f, squash.coerceAtLeast(0.30f))
        canvas.drawCircle(0f, 0f, radius, paint)
        canvas.restore()
    }

    /** Ring drawn in local (already translated+squashed) coordinates. */
    private fun drawRing(
        canvas: Canvas,
        radius: Float,
        tilt: Float,
        angleRad: Float,
        intensity: Float,
        color: Int
    ) {
        ringPaint.color = color
        ringPaint.alpha = (60 + 90 * intensity).toInt()
        ringPaint.strokeWidth = (radius * 0.055f).coerceAtLeast(0.5f)
        canvas.save()
        canvas.rotate(tilt * 57.29f)
        val squash = 0.42f
        canvas.drawArc(
            -radius, -radius * squash, radius, radius * squash,
            angleRad * RAD_TO_DEG % 360f, 300f, false, ringPaint
        )
        canvas.restore()
    }

    private fun breathScale(phase: AmbientPhase, timeMs: Long): Float {
        val t = (timeMs % phase.breathPeriodMs).toFloat() / phase.breathPeriodMs
        return 1f + sin(t * TAU) * phase.breathAmplitude
    }

    private fun ensureGradient(radius: Float, color: Int) {
        val existing = coreGradient
        // Reuse only when the cached gradient is at least as large AND was
        // built for the same phase colour; otherwise rebuild (cheap, rare).
        if (existing != null && lastGradientSize >= radius && lastGradientColor == color) return
        coreGradient = RadialGradient(
            0f, 0f, radius.coerceAtLeast(1f),
            intArrayOf(color, Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        lastGradientSize = radius
        lastGradientColor = color
    }

    private fun shellColor(phase: AmbientPhase): Int = when (phase) {
        AmbientPhase.IDLE, AmbientPhase.SLEEPING -> 0xFFFF6B00.toInt()
        AmbientPhase.LISTENING -> 0xFF00B0FF.toInt()
        AmbientPhase.THINKING -> 0xFFFFA000.toInt()
        AmbientPhase.SPEAKING -> 0xFF00C8FF.toInt()
        AmbientPhase.ACTING -> 0xFFFF9500.toInt()
        AmbientPhase.AWAKENING -> 0xFF7DF9FF.toInt()
        AmbientPhase.SUCCESS -> 0xFF34C759.toInt()
        AmbientPhase.WARNING -> 0xFFFFB300.toInt()
        AmbientPhase.ERROR -> 0xFFFF3B30.toInt()
    }

    private fun coreColor(phase: AmbientPhase): Int = when (phase) {
        AmbientPhase.IDLE, AmbientPhase.SLEEPING -> 0xFFFF1744.toInt()
        AmbientPhase.LISTENING -> 0xFF34C759.toInt()
        AmbientPhase.THINKING -> 0xFFFF6B00.toInt()
        AmbientPhase.SPEAKING -> 0xFF00B0FF.toInt()
        AmbientPhase.ACTING -> 0xFFFF3B30.toInt()
        AmbientPhase.AWAKENING -> 0xFFB3E5FC.toInt()
        AmbientPhase.SUCCESS -> 0xFF30D158.toInt()
        AmbientPhase.WARNING -> 0xFFFFC400.toInt()
        AmbientPhase.ERROR -> 0xFFFF5252.toInt()
    }

    private companion object {
        const val TAU = (Math.PI * 2).toFloat()
        const val RAD_TO_DEG = 57.2957795f
        const val SHELL_SEGMENTS = 6
        const val VOICE_EXPANSION = 0.22f
        const val FRINGE_COOL = 0xFF4FC3F7.toInt()
        const val FRINGE_WARM = 0xFFFF8A80.toInt()

        /** Pulse budgets these effects assume; matches the service wiring. */
        const val AWAKENING_SPAN_MS = 900L
        const val ERROR_GLITCH_SPAN_MS = 900L
    }
}
