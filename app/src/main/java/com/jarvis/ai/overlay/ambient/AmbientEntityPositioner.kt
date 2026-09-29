package com.jarvis.ai.overlay.ambient

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Screen geometry and safe areas the ambient entity must never enter.
 *
 * The overlay service fills this from real window metrics when available and
 * falls back to display metrics plus a status-bar guard on older APIs. Keeping
 * it a plain data class keeps every positioning decision unit-testable.
 */
data class AmbientScreenModel(
    val widthPx: Int,
    val heightPx: Int,
    val topInsetPx: Int = 0,
    val bottomInsetPx: Int = 0,
    val leftInsetPx: Int = 0,
    val rightInsetPx: Int = 0,
    /** Extra clearance under the status bar when real insets are unavailable. */
    val statusGuardPx: Int = 0
) {
    val safeLeftPx: Int get() = leftInsetPx
    val safeTopPx: Int get() = topInsetPx + statusGuardPx
    val safeRightPx: Int get() = (widthPx - rightInsetPx - 1).coerceAtLeast(safeLeftPx)
    val safeBottomPx: Int get() = (heightPx - bottomInsetPx - 1).coerceAtLeast(safeTopPx)
}

/**
 * Motion tunables for the ambient entity.
 *
 * Movement follows a fixed arc — anticipation, smooth travel, settling — so a
 * reposition reads as intentional. The final approach is a slightly
 * underdamped spring (one small overshoot), which is what makes it feel alive
 * instead of tweened. Values are in pixels / milliseconds / per-frame units.
 */
data class AmbientMotionProfile(
    /** Spring constant: acceleration toward the target, per frame². */
    val stiffness: Float = 0.12f,
    /** Velocity fraction shed per frame (tuned to ζ≈0.8: one small overshoot). */
    val damping: Float = 0.55f,
    /** Fraction of the travel distance used as the anticipation pull-back. */
    val anticipationFraction: Float = 0.10f,
    /** Hard cap on the anticipation pull-back so short hops stay subtle. */
    val anticipationPx: Float = 4f,
    /** How long the entity leans away before travelling. */
    val anticipationMs: Long = 90L,
    /** Target duration of the main travel; only used to describe the plan. */
    val movementMs: Long = 340L,
    /** Expected overshoot past the target before settling, in pixels. */
    val overshootPx: Float = 3f,
    /** Time the settle oscillation is given after the main travel. */
    val settleMs: Long = 180L,
    /** Distance below which the entity is considered parked. */
    val settleRadiusPx: Float = 2f
)

/** A point in overlay-window coordinates (the entity's top-left corner). */
data class AmbientPoint(val x: Float, val y: Float)

/**
 * A computed repositioning arc: where the entity is, where it will lean
 * (anticipation), and where it will come to rest. Pure data — the service
 * applies it frame by frame through [AmbientSpringPosition].
 */
data class AmbientTransitionPlan(
    val from: AmbientPoint,
    val anticipation: AmbientPoint,
    val to: AmbientPoint,
    val anticipationMs: Long,
    val movementMs: Long,
    val settleMs: Long
) {
    val travelDistancePx: Float get() = hypot(to.x - from.x, to.y - from.y)
}

/**
 * Dynamic positioning for the ambient entity.
 *
 * Owns two concerns:
 *  1. [resolveTarget] / [plan] — turn a desired position into a collision-safe
 *     target plus an anticipation→travel→settle arc (pure functions).
 *  2. [AmbientSpringPosition] — integrate the spring frame by frame with a
 *     clamped timestep so jank never explodes the physics.
 *
 * There is deliberately no randomness here: the entity never teleports and
 * never drifts while parked — micro-idle motion is the renderer's breathing/
 * orbit, which does not fight the touch target.
 */
object AmbientEntityPositioner {

    /**
     * Clamps a desired top-left position so the whole entity stays inside the
     * safe area. Unconditional clamp means even a huge layout change (rotation,
     * keyboard) can leave the entity unreachable.
     */
    fun resolveTarget(
        rawX: Float,
        rawY: Float,
        screen: AmbientScreenModel,
        entitySizePx: Int
    ): AmbientPoint {
        val maxX = (screen.safeRightPx - entitySizePx + 1).coerceAtLeast(screen.safeLeftPx)
        val maxY = (screen.safeBottomPx - entitySizePx + 1).coerceAtLeast(screen.safeTopPx)
        val x = rawX.coerceIn(screen.safeLeftPx.toFloat(), maxX.toFloat())
        val y = rawY.coerceIn(screen.safeTopPx.toFloat(), maxY.toFloat())
        return AmbientPoint(x, y)
    }

    /**
     * Builds the repositioning arc from [from] toward a desired position.
     * The target is resolved through [resolveTarget]; the anticipation point
     * sits slightly opposite the travel direction (a lean before the move).
     * Zero-distance requests return an empty arc with no anticipation.
     */
    fun plan(
        from: AmbientPoint,
        desiredX: Float,
        desiredY: Float,
        screen: AmbientScreenModel,
        entitySizePx: Int,
        profile: AmbientMotionProfile = AmbientMotionProfile()
    ): AmbientTransitionPlan {
        val to = resolveTarget(desiredX, desiredY, screen, entitySizePx)
        val dx = to.x - from.x
        val dy = to.y - from.y
        val distance = hypot(dx, dy)
        if (distance < profile.settleRadiusPx) {
            return AmbientTransitionPlan(
                from = from,
                anticipation = from,
                to = to,
                anticipationMs = 0L,
                movementMs = 0L,
                settleMs = 0L
            )
        }
        val pullback = (distance * profile.anticipationFraction)
            .coerceAtMost(profile.anticipationPx)
        val anticipation = AmbientPoint(
            x = from.x - dx / distance * pullback,
            y = from.y - dy / distance * pullback
        )
        return AmbientTransitionPlan(
            from = from,
            anticipation = anticipation,
            to = to,
            anticipationMs = profile.anticipationMs,
            movementMs = profile.movementMs,
            settleMs = profile.settleMs
        )
    }
}

/**
 * Frame-by-frame spring integrator for the entity position.
 *
 * Semi-implicit Euler with a clamped timestep (≈1–2.5 display frames per
 * step): underdamped enough for a single small overshoot, stable even when a
 * frame arrives very late. All state is private and mutated only from the
 * animation thread (the Choreographer callback on the main looper).
 */
class AmbientSpringPosition(
    private val profile: AmbientMotionProfile = AmbientMotionProfile(),
    /** Reference frame length used to normalise integration steps. */
    private val referenceFrameMs: Float = 1000f / 60f
) {
    private var x: Float = 0f
    private var y: Float = 0f
    private var velocityX: Float = 0f
    private var velocityY: Float = 0f
    /** Target the spring is chasing this frame (anticipation or final). */
    private var activeTargetX: Float = 0f
    private var activeTargetY: Float = 0f
    /** Where the transition ends up once the anticipation stage is over. */
    private var finalTargetX: Float = 0f
    private var finalTargetY: Float = 0f
    /** Anticipation point while the lean-away stage is running, else null. */
    private var intermediateX: Float = Float.NaN
    private var intermediateY: Float = Float.NaN
    private var intermediateRemainingMs: Long = 0L

    /** Current position snapshot. */
    val current: AmbientPoint get() = AmbientPoint(x, y)

    /** Position the spring is converging on. */
    val target: AmbientPoint get() = AmbientPoint(finalTargetX, finalTargetY)

    /** True once the entity is parked on its final target (within settle radius). */
    val settled: Boolean
        get() = abs(finalTargetX - x) <= profile.settleRadiusPx &&
            abs(finalTargetY - y) <= profile.settleRadiusPx &&
            abs(velocityX) <= SETTLE_VELOCITY_PX_PER_FRAME &&
            abs(velocityY) <= SETTLE_VELOCITY_PX_PER_FRAME

    /** Places the entity instantly (no animation). Used on startup/restore. */
    fun snapTo(point: AmbientPoint) {
        x = point.x
        y = point.y
        activeTargetX = point.x
        activeTargetY = point.y
        finalTargetX = point.x
        finalTargetY = point.y
        velocityX = 0f
        velocityY = 0f
        intermediateX = Float.NaN
        intermediateY = Float.NaN
        intermediateRemainingMs = 0L
    }

    /** Hands the spring a new destination; travel starts on the next advance. */
    fun setTarget(point: AmbientPoint) {
        finalTargetX = point.x
        finalTargetY = point.y
        activeTargetX = point.x
        activeTargetY = point.y
        intermediateX = Float.NaN
        intermediateY = Float.NaN
        intermediateRemainingMs = 0L
    }

    /**
     * Applies a full repositioning arc: lean away ([AmbientTransitionPlan.anticipation])
     * for the plan's anticipation duration, then chase the final position.
     * Trivial (near-zero distance) plans degrade to [setTarget].
     */
    fun startTransition(plan: AmbientTransitionPlan) {
        if (plan.anticipationMs <= 0L || plan.travelDistancePx < profile.settleRadiusPx) {
            setTarget(plan.to)
            return
        }
        intermediateX = plan.anticipation.x
        intermediateY = plan.anticipation.y
        intermediateRemainingMs = plan.anticipationMs
        finalTargetX = plan.to.x
        finalTargetY = plan.to.y
        activeTargetX = plan.anticipation.x
        activeTargetY = plan.anticipation.y
    }

    /** Nudges both position and target while the user drags — 1:1 finger follow. */
    fun translate(dxPx: Float, dyPx: Float) {
        activeTargetX += dxPx
        activeTargetY += dyPx
        finalTargetX += dxPx
        finalTargetY += dyPx
        intermediateX = if (intermediateX.isNaN()) Float.NaN else intermediateX + dxPx
        intermediateY = if (intermediateY.isNaN()) Float.NaN else intermediateY + dyPx
        x += dxPx
        y += dyPx
    }

    /**
     * Advances the physics by [dtMs] of wall time. Large gaps (app was frozen,
     * animation just resumed) are integrated in bounded sub-steps so a single
     * slow frame cannot fling the entity across the screen.
     */
    fun advance(dtMs: Long) {
        if (dtMs <= 0L) return
        advanceAnticipation(dtMs)
        var remaining = dtMs.coerceAtMost(MAX_STEP_MS * MAX_SUBSTEPS)
        while (remaining > 0L) {
            val step = (remaining.coerceAtMost(MAX_STEP_MS)) / referenceFrameMs
            integrate(step)
            remaining -= MAX_STEP_MS
        }
    }

    /** Countdown of the anticipation lean; hands over to the final target. */
    private fun advanceAnticipation(dtMs: Long) {
        if (intermediateX.isNaN()) return
        intermediateRemainingMs -= dtMs
        if (intermediateRemainingMs <= 0L) {
            activeTargetX = finalTargetX
            activeTargetY = finalTargetY
            intermediateX = Float.NaN
            intermediateY = Float.NaN
        }
    }

    private fun integrate(step: Float) {
        velocityX += (profile.stiffness * (activeTargetX - x) - profile.damping * velocityX) * step
        velocityY += (profile.stiffness * (activeTargetY - y) - profile.damping * velocityY) * step
        x += velocityX * step
        y += velocityY * step
    }

    private companion object {
        /** Longest physics sub-step, ~2.5 display frames. */
        const val MAX_STEP_MS = 42L
        /** Never integrate more than ~2s of backlog in one advance() call. */
        const val MAX_SUBSTEPS = 48L
        /** Below this per-frame velocity the entity counts as parked. */
        const val SETTLE_VELOCITY_PX_PER_FRAME = 0.08f
    }
}
