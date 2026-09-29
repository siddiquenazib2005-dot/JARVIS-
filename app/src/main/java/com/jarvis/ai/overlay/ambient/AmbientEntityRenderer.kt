package com.jarvis.ai.overlay.ambient

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/**
 * 2.5D holographic renderer for the ambient AURIX entity.
 *
 * Procedural Canvas rendering (no static PNG): translucent layered shell,
 * chromatic fringe, orbital rings, an inner energy core and a small
 * deterministic particle field. Every layer is a pure function of
 * (time, phase, audio level), so the entity reads as "an intelligent
 * digital organism made of light" while staying cheap enough for an overlay.
 *
 * Draw cost per frame: ~2 gradient circles (one pre-allocated gradient),
 * ~4 strokes, 2 ellipse arcs and N<=26 particles — comfortably inside a
 * 60fps budget for a 76dp window, and frame pacing halves that in idle.
 *
 * Allocation policy: paints and the core gradient are created once per size;
 * render() itself allocates nothing except the two tiny transform objects
 * Canvas requires for save/restore-free matrix tweaks (avoided too — we use
 * save/rotate/restore which reuses internal state).
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

    /** Reusable particle buffer; grown on demand, never reallocated per frame. */
    private var particleBuffer = FloatArray(2 * AmbientParticles.count(false, 1f))

    /**
     * Renders one frame of the entity centred at (cx, cy) with base radius
     * [radius]. [timeMs] is the animation clock, [audioLevel] the latest
     * smoothed mic/TTS amplitude in 0..1 (real audio, never faked here).
     */
    fun render(canvas: Canvas, cx: Float, cy: Float, radius: Float, timeMs: Long, audioLevel: Float) {
        val phase = presence.currentPhase(timeMs)
        val intensity = presence.intensity(phase)
        val audio = audioLevel.coerceIn(0f, 1f) * phase.audioGain

        // --- Motion: breathing + micro-orbit + voice expansion ----------------
        val breath = breathScale(phase, timeMs)
        val voice = 1f + audio * VOICE_EXPANSION
        val r = radius * breath * voice
        val orbitAngle = (timeMs % phase.orbitPeriodMs).toFloat() / phase.orbitPeriodMs * TAU
        val orbitX = cos(orbitAngle) * phase.orbitAmplitude * radius
        val orbitY = sin(orbitAngle) * phase.orbitAmplitude * radius * 0.6f
        val ox = cx + orbitX
        val oy = cy + orbitY

        val shellColor = shellColor(phase)
        val coreColor = coreColor(phase)

        // --- Layer 1: volumetric glow (two soft circles, no per-frame shader) -
        glow.color = shellColor
        glow.alpha = (26 + 54 * intensity).toInt()
        canvas.drawCircle(ox, oy, r * 1.28f, glow)
        glow.alpha = (18 + 40 * intensity).toInt()
        canvas.drawCircle(ox, oy, r * 1.55f, glow)

        // --- Layer 2: glass-like light shell with chromatic fringe ------------
        shellFill.color = shellColor
        shellFill.alpha = (16 + 26 * intensity).toInt()
        canvas.drawCircle(ox, oy, r * 0.88f, shellFill)

        // Audio-reactive distortion: the shell breathes with the voice, drawn
        // as six arc segments whose radii differ subtly with the level.
        val segments = if (phase.lowPower) 4 else SHELL_SEGMENTS
        shellStroke.strokeWidth = (r * 0.05f).coerceAtLeast(1f)
        var index = 0
        while (index < segments) {
            val segAngle = index.toFloat() / segments * TAU
            // Deterministic segment ripple driven by voice energy.
            val ripple = sin(segAngle * 3f + timeMs * 0.004f) * audio * r * 0.10f
            val segRadius = r * (0.86f + audio * 0.05f) + ripple
            val sweep = TAU / segments + 0.16f
            shellStroke.color = shellColor
            shellStroke.alpha = (150 + 80 * intensity).toInt()
            canvas.drawArc(
                ox - segRadius, oy - segRadius, ox + segRadius, oy + segRadius,
                segAngle * RAD_TO_DEG, sweep * RAD_TO_DEG, false, shellStroke
            )
            index++
        }

        // Chromatic separation: two thin offset strokes at low alpha — reads as
        // holographic fringing without any real refraction cost.
        val fringeOffset = r * 0.035f
        fringe.strokeWidth = (r * 0.02f).coerceAtLeast(0.5f)
        fringe.alpha = (46 * intensity).toInt()
        fringe.color = FRINGE_COOL
        canvas.drawCircle(ox - fringeOffset, oy, r * 0.92f, fringe)
        fringe.color = FRINGE_WARM
        canvas.drawCircle(ox + fringeOffset, oy, r * 0.92f, fringe)

        // --- Layer 3: orbital rings (2.5D: squashed ellipses, counter-rotating)
        drawRing(canvas, ox, oy, r * 1.02f, 0.34f, timeMs * RING_SPEED_A, intensity, shellColor)
        drawRing(canvas, ox, oy, r * 0.74f, -0.22f, timeMs * RING_SPEED_B, intensity, coreColor)

        // --- Layer 4: particle field ------------------------------------------
        val count = AmbientParticles.count(phase.lowPower, presence.animationIntensity)
        if (particleBuffer.size < 2 * count) particleBuffer = FloatArray(2 * count)
        val written = AmbientParticles.positions(count, timeMs, audio, particleBuffer)
        particlePaint.color = coreColor
        particlePaint.alpha = (90 + 120 * intensity).toInt()
        var i = 0
        while (i < written) {
            val px = particleBuffer[2 * i]
            val py = particleBuffer[2 * i + 1]
            val size = r * 0.045f * (0.7f + ((i * 37) % 100) / 100f)
            canvas.drawCircle(ox + px * r, oy + py * r, size, particlePaint)
            i++
        }

        // --- Layer 5: inner energy core (pre-allocated radial gradient) -------
        // The gradient is built centred on the origin and the canvas is
        // translated to the entity centre: no per-frame shader allocation.
        val coreRadius = r * (0.34f + audio * 0.10f)
        ensureGradient(coreRadius, coreColor)
        corePaint.shader = coreGradient
        canvas.save()
        canvas.translate(ox, oy)
        canvas.drawCircle(0f, 0f, coreRadius, corePaint)
        canvas.restore()
        corePaint.shader = null

        // Bright centre dot; pulse grows with voice energy.
        corePaint.color = Color.WHITE
        corePaint.alpha = (150 + 90 * intensity).toInt()
        canvas.drawCircle(ox, oy, r * (0.07f + audio * 0.04f), corePaint)
        corePaint.alpha = 255
    }

    private fun drawRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        tilt: Float,
        angleRad: Float,
        intensity: Float,
        color: Int
    ) {
        ringPaint.color = color
        ringPaint.alpha = (60 + 90 * intensity).toInt()
        ringPaint.strokeWidth = radius * 0.055f
        canvas.save()
        canvas.rotate(tilt * 57.29f, cx, cy)
        val squash = 0.42f
        canvas.drawArc(
            cx - radius, cy - radius * squash, cx + radius, cy + radius * squash,
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
        coreGradient = RadialGradient(0f, 0f, radius.coerceAtLeast(1f), intArrayOf(color, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
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
        const val RING_SPEED_A = 0.0011f
        const val RING_SPEED_B = -0.0007f
        const val FRINGE_COOL = 0xFF4FC3F7.toInt()
        const val FRINGE_WARM = 0xFFFF8A80.toInt()
    }
}
