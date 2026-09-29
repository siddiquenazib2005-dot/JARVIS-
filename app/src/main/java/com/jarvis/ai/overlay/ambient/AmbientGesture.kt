package com.jarvis.ai.overlay.ambient

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure classification of a drag release into a gesture (Phase F).
 *
 * Extracted from the overlay view so the decision table is unit-testable on
 * the JVM: velocity units are px/second, matching the view's tracker.
 *
 *   fast swipe UP   -> WAKE      (same as the hold gesture: start listening)
 *   fast swipe DOWN -> MINIMIZE  ("swipe away to dismiss")
 *   fast sideways   -> FLING     (keep momentum, settle collision-safe)
 *   slow release    -> REST      (plain drag: glide to the nearest edge)
 *
 * Vertical swipes must clearly dominate sideways drift, so a diagonal drag
 * never accidentally triggers a state change.
 */
object AmbientGestureClassifier {

    enum class Gesture { WAKE, MINIMIZE, FLING, REST }

    /** Release speed below which the gesture is treated as a plain drag. */
    const val FLING_MIN_SPEED_PX_PER_SEC = 1200f

    /** A vertical swipe only counts when it is this much larger than |vx|. */
    const val SWIPE_DOMINANCE = 1.4f

    fun classify(
        velocityXPxPerSec: Float,
        velocityYPxPerSec: Float
    ): Gesture {
        val speed = hypot(velocityXPxPerSec, velocityYPxPerSec)
        if (speed < FLING_MIN_SPEED_PX_PER_SEC) return Gesture.REST
        val verticalDominates = abs(velocityYPxPerSec) > abs(velocityXPxPerSec) * SWIPE_DOMINANCE
        if (!verticalDominates) return Gesture.FLING
        // Screen Y grows downward: fast negative velocity = swipe upward.
        return if (velocityYPxPerSec < 0f) Gesture.WAKE else Gesture.MINIMIZE
    }
}
