package com.jarvis.ai.overlay.ambient

import com.jarvis.ai.overlay.AvatarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Focused JVM tests for PHASE G (wake word), PHASE D (shell morph) and
 * PHASE E (screen-context presence), following the directive's test list:
 * wake-word debounce, state cancellation/replacement, context mapping,
 * morph continuity, form mapping. All classes are pure JVM; the Canvas
 * renderer itself is compile-verified by CI only.
 */
class AmbientGdeTest {

    // ------------------------------------------------------------------
    // 1. Wake word -> AWAKENING (via bus + presence controller)
    // ------------------------------------------------------------------

    @Test
    fun `wake word announcement reaches the overlay listener`() {
        var wakes = 0
        val listener: () -> Unit = { wakes++ }
        WakeWordBus.reset()
        WakeWordBus.observe(listener)
        val accepted = WakeWordBus.announce(nowMs = 10_000L)
        assertTrue(accepted)
        assertEquals(1, wakes)
        WakeWordBus.stopObserving(listener)
        WakeWordBus.reset()
    }

    @Test
    fun `wake word triggers an AWAKENING pulse that decays to the assistant state`() {
        var now = 1_000L
        val controller = AmbientPresenceController(now = { now })
        controller.onAssistantState(AvatarState.IDLE)
        controller.pulse(AmbientPhase.AWAKENING, AmbientGdeFixtures.AWAKENING_MS)
        assertEquals(AmbientPhase.AWAKENING, controller.currentPhase(now + 100))
        // AWAKENING -> LISTENING: assistant state becomes LISTENING right after
        controller.onAssistantState(AvatarState.LISTENING)
        now += AmbientGdeFixtures.AWAKENING_MS + 1
        assertEquals(AmbientPhase.LISTENING, controller.currentPhase(now))
    }

    // ------------------------------------------------------------------
    // 6. Wake-word debounce
    // ------------------------------------------------------------------

    @Test
    fun `repeated wake word events inside the debounce window are dropped`() {
        WakeWordBus.reset()
        var wakes = 0
        val listener: () -> Unit = { wakes++ }
        WakeWordBus.observe(listener)
        assertTrue(WakeWordBus.announce(nowMs = 10_000L))
        assertFalse(WakeWordBus.announce(nowMs = 10_000L + 400))
        assertFalse(WakeWordBus.announce(nowMs = 10_000L + WakeWordBus.DEBOUNCE_MS - 1))
        assertTrue(WakeWordBus.announce(nowMs = 10_000L + WakeWordBus.DEBOUNCE_MS))
        assertEquals(2, wakes)
        WakeWordBus.stopObserving(listener)
        WakeWordBus.reset()
    }

    // ------------------------------------------------------------------
    // 7. State cancellation — minimizing cancels running pulses
    // ------------------------------------------------------------------

    @Test
    fun `minimizing cancels a running transient pulse immediately`() {
        var now = 1_000L
        val controller = AmbientPresenceController(now = { now })
        controller.onAssistantState(AvatarState.THINKING)
        controller.pulse(AmbientPhase.SUCCESS, 2_000L)
        assertTrue(controller.hasTransient(now))
        controller.setMinimized(true)
        assertFalse(controller.hasTransient(now))
        assertEquals(AmbientPhase.SLEEPING, controller.currentPhase(now))
    }

    // ------------------------------------------------------------------
    // 8. State replacement — a newer pulse replaces the older one cleanly
    // ------------------------------------------------------------------

    @Test
    fun `a new transient pulse replaces the previous one without stacking`() {
        var now = 1_000L
        val controller = AmbientPresenceController(now = { now })
        controller.onAssistantState(AvatarState.IDLE)
        controller.pulse(AmbientPhase.WARNING, 5_000L)
        assertTrue(controller.hasTransient(now))
        controller.pulse(AmbientPhase.AWAKENING, 900L)
        assertEquals(AmbientPhase.AWAKENING, controller.currentPhase(now + 10))
        now += 1_000L
        // Only ONE transient existed: it already decayed, nothing is left over.
        assertFalse(controller.hasTransient(now))
        assertEquals(AmbientPhase.IDLE, controller.currentPhase(now))
    }

    // ------------------------------------------------------------------
    // 9. Context -> visual state mapping (Phase E)
    // ------------------------------------------------------------------

    @Test
    fun `phases map to their context states`() {
        assertEquals(AmbientContextState.LISTENING, AmbientContextState.fromPhase(AmbientPhase.LISTENING))
        assertEquals(AmbientContextState.THINKING, AmbientContextState.fromPhase(AmbientPhase.THINKING))
        assertEquals(AmbientContextState.THINKING, AmbientContextState.fromPhase(AmbientPhase.ACTING))
        assertEquals(AmbientContextState.SPEAKING, AmbientContextState.fromPhase(AmbientPhase.SPEAKING))
        assertEquals(AmbientContextState.SLEEPING, AmbientContextState.fromPhase(AmbientPhase.SLEEPING))
        assertEquals(AmbientContextState.NORMAL_IDLE, AmbientContextState.fromPhase(AmbientPhase.IDLE))
    }

    @Test
    fun `context resolution order is important event over sleeping over interacting over phase`() {
        var now = 1_000L
        val controller = AmbientContextController(now = { now })
        controller.onPhase(AmbientPhase.IDLE)
        assertEquals(AmbientContextState.NORMAL_IDLE, controller.current(now))

        controller.setInteracting(true)
        assertEquals(AmbientContextState.USER_INTERACTING, controller.current(now))
        controller.setInteracting(false)

        controller.setSleeping(true)
        assertEquals(AmbientContextState.SLEEPING, controller.current(now))

        controller.importantEvent(now)
        assertEquals(AmbientContextState.IMPORTANT_EVENT, controller.current(now))

        now += AmbientContextController.DEFAULT_IMPORTANT_EVENT_MS + 1
        assertEquals(AmbientContextState.SLEEPING, controller.current(now))

        controller.setSleeping(false)
        assertEquals(AmbientContextState.NORMAL_IDLE, controller.current(now))
    }

    @Test
    fun `sleeping context is smaller and dimmer than idle`() {
        assertTrue(AmbientContextState.SLEEPING.scaleMultiplier < AmbientContextState.NORMAL_IDLE.scaleMultiplier)
        assertTrue(AmbientContextState.SLEEPING.intensityMultiplier < AmbientContextState.NORMAL_IDLE.intensityMultiplier)
        assertTrue(AmbientContextState.IMPORTANT_EVENT.scaleMultiplier > AmbientContextState.NORMAL_IDLE.scaleMultiplier)
    }

    // ------------------------------------------------------------------
    // Phase D — form mapping + continuous morph
    // ------------------------------------------------------------------

    @Test
    fun `forms map from phases exactly as the directive requires`() {
        assertEquals(AmbientForm.ORB, AmbientForm.from(AmbientPhase.IDLE))
        assertEquals(AmbientForm.ORB, AmbientForm.from(AmbientPhase.SLEEPING))
        assertEquals(AmbientForm.ENERGY_RING, AmbientForm.from(AmbientPhase.AWAKENING))
        assertEquals(AmbientForm.LISTENING_FIELD, AmbientForm.from(AmbientPhase.LISTENING))
        assertEquals(AmbientForm.THINKING_CORE, AmbientForm.from(AmbientPhase.THINKING))
        assertEquals(AmbientForm.VOICE_FORM, AmbientForm.from(AmbientPhase.SPEAKING))
    }

    @Test
    fun `morph is continuous between forms with no hard cut`() {
        val morph = AmbientFormMorph()
        morph.snapTo(AmbientForm.ORB)
        val before = morph.snapshot()
        assertEquals(AmbientForm.ORB.scale, before.scale, 0.0001f)

        morph.target(AmbientForm.ENERGY_RING)
        // Early: still close to the old form, but already moving.
        morph.advance(16L)
        val early = morph.snapshot()
        assertTrue(early.scale > before.scale)
        assertTrue(early.scale < AmbientForm.ENERGY_RING.scale)
        // 10 frames later: noticeably further along, still continuous.
        repeat(9) { morph.advance(16L) }
        val later = morph.snapshot()
        assertTrue(later.scale > early.scale)
        assertTrue(later.scale < AmbientForm.ENERGY_RING.scale)
        // Long run converges to the target exactly.
        repeat(200) { morph.advance(16L) }
        val settled = morph.snapshot()
        assertEquals(AmbientForm.ENERGY_RING.scale, settled.scale, 0.01f)
        assertEquals(AmbientForm.ENERGY_RING.ringHollowness, settled.ringHollowness, 0.01f)
    }

    @Test
    fun `morph survives a huge frame gap without overshoot or NaN`() {
        val morph = AmbientFormMorph()
        morph.snapTo(AmbientForm.ORB)
        morph.target(AmbientForm.VOICE_FORM)
        morph.advance(60_000L)
        val snapshot = morph.snapshot()
        assertEquals(AmbientForm.VOICE_FORM.scale, snapshot.scale, 0.001f)
        assertTrue(!snapshot.scale.isNaN() && !snapshot.squash.isNaN())
    }

    @Test
    fun `energy ring is hollow and orb is filled`() {
        assertTrue(AmbientForm.ENERGY_RING.ringHollowness > 0.4f)
        assertTrue(AmbientForm.ORB.ringHollowness < 0.1f)
        assertTrue(AmbientForm.ENERGY_RING.spinSpeed > AmbientForm.ORB.spinSpeed)
    }

    // ------------------------------------------------------------------
    // 10. Position transition — plan still anticipation-first (regression)
    // ------------------------------------------------------------------

    @Test
    fun `position transition keeps anticipation and safe target`() {
        val screen = AmbientScreenModel(widthPx = 1080, heightPx = 2280, statusGuardPx = 66)
        val plan = AmbientEntityPositioner.plan(
            from = AmbientPoint(60f, 120f),
            desiredX = 1000f,
            desiredY = 2000f,
            screen = screen,
            entitySizePx = 200
        )
        assertTrue(plan.anticipationMs > 0L)
        assertTrue(plan.travelDistancePx > 100f)
        assertEquals(
            AmbientEntityPositioner.resolveTarget(1000f, 2000f, screen, 200),
            plan.to
        )
    }

    // ------------------------------------------------------------------
    // 11. Renderer state mapping — frame assembly is phase-faithful
    // ------------------------------------------------------------------

    @Test
    fun `ambient frame carries the winning phase into the renderer inputs`() {
        var now = 1_000L
        val presence = AmbientPresenceController(now = { now })
        val context = AmbientContextController(now = { now })
        val morph = AmbientFormMorph()
        presence.onAssistantState(AvatarState.IDLE)
        morph.snapTo(AmbientForm.from(presence.currentPhase(now)))
        context.onPhase(presence.currentPhase(now))

        presence.pulse(AmbientPhase.SPEAKING, 800L)
        val phase = presence.currentPhase(now + 100)
        // Mirror the service wiring: the rendered phase is fed to the context
        // controller on every change (see FloatingAvatarService.onAmbientFrame).
        context.onPhase(phase)
        val frame = AmbientFrame(
            phase = phase,
            form = morph.snapshot(),
            context = context.profile(now + 100),
            audio = 0.5f,
            timeMs = now + 100
        )
        // Pulse wins over IDLE, context follows the phase mapping.
        assertEquals(AmbientPhase.SPEAKING, frame.phase)
        assertEquals(AmbientContextState.SPEAKING, frame.context.state)
        assertEquals(AmbientForm.ORB.label, frame.form.targetForm.label)
    }

    @Test
    fun `audio gain differs per phase so listening reacts more than idle`() {
        assertTrue(AmbientPhase.LISTENING.audioGain > AmbientPhase.IDLE.audioGain)
        assertTrue(AmbientPhase.SPEAKING.audioGain > AmbientPhase.THINKING.audioGain)
        assertEquals(0f, AmbientPhase.SLEEPING.audioGain, 0.0001f)
    }

    // ------------------------------------------------------------------
    // Phase F remainder — gesture classification (pure JVM decision table)
    // ------------------------------------------------------------------

    @Test
    fun `fast vertical swipes are directional commands`() {
        assertEquals(
            AmbientGestureClassifier.Gesture.WAKE,
            AmbientGestureClassifier.classify(velocityXPxPerSec = 200f, velocityYPxPerSec = -2500f)
        )
        assertEquals(
            AmbientGestureClassifier.Gesture.MINIMIZE,
            AmbientGestureClassifier.classify(velocityXPxPerSec = 100f, velocityYPxPerSec = 2600f)
        )
    }

    @Test
    fun `fast sideways releases fling with momentum instead of changing state`() {
        assertEquals(
            AmbientGestureClassifier.Gesture.FLING,
            AmbientGestureClassifier.classify(velocityXPxPerSec = 3000f, velocityYPxPerSec = 300f)
        )
    }

    @Test
    fun `slow releases never trigger swipe actions`() {
        assertEquals(
            AmbientGestureClassifier.Gesture.REST,
            AmbientGestureClassifier.classify(velocityXPxPerSec = 0f, velocityYPxPerSec = -900f)
        )
    }

    @Test
    fun `diagonal drags do not accidentally fire swipes`() {
        assertEquals(
            AmbientGestureClassifier.Gesture.FLING,
            AmbientGestureClassifier.classify(velocityXPxPerSec = 2200f, velocityYPxPerSec = -2400f)
        )
    }

    @Test
    fun `transient remaining time is visible for pulse-lifetime effects`() {
        var now = 1_000L
        val controller = AmbientPresenceController(now = { now })
        controller.pulse(AmbientPhase.AWAKENING, 900L)
        val remaining = controller.transientRemainingMs(now)
        requireNotNull(remaining)
        assertTrue("remaining=$remaining", remaining in 1..900)
        now += 900L
        assertEquals(null, controller.transientRemainingMs(now))
    }
}

/** Shared constants mirroring the service wiring. */
private object AmbientGdeFixtures {
    const val AWAKENING_MS = 900L
}
