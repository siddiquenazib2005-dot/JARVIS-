package com.jarvis.ai.overlay.ambient

import com.jarvis.ai.overlay.AvatarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * JVM tests for the ambient entity foundation: collision-safe positioning,
 * spring motion (no teleportation), presence decay, audio smoothing and the
 * deterministic particle field. Android rendering (Canvas/Choreographer) is
 * intentionally NOT covered here — it is verified by CI compile + device use.
 */
class AmbientFoundationTest {

    private val screen = AmbientScreenModel(widthPx = 1080, heightPx = 2280, statusGuardPx = 66)
    private val size = 200

    // ------------------------------------------------------------------
    // AmbientEntityPositioner.resolveTarget — collision-safe clamping
    // ------------------------------------------------------------------

    @Test
    fun `target stays inside the safe area even for wild coordinates`() {
        val target = AmbientEntityPositioner.resolveTarget(-5000f, -5000f, screen, size)
        assertEquals(screen.safeLeftPx.toFloat(), target.x)
        assertEquals(screen.safeTopPx.toFloat(), target.y)

        val far = AmbientEntityPositioner.resolveTarget(90000f, 90000f, screen, size)
        assertTrue(far.x <= screen.safeRightPx - size + 1)
        assertTrue(far.y <= screen.safeBottomPx - size + 1)
    }

    @Test
    fun `status bar guard keeps the entity below the status bar`() {
        val target = AmbientEntityPositioner.resolveTarget(100f, 0f, screen, size)
        assertTrue("y=${target.y} must be >= ${screen.safeTopPx}", target.y >= screen.safeTopPx)
        assertEquals(66f, target.y)
    }

    @Test
    fun `legal coordinates pass through unchanged`() {
        val target = AmbientEntityPositioner.resolveTarget(300f, 500f, screen, size)
        assertEquals(300f, target.x)
        assertEquals(500f, target.y)
    }

    @Test
    fun `tiny screens still produce a reachable target`() {
        val tiny = AmbientScreenModel(widthPx = 100, heightPx = 100, topInsetPx = 80, bottomInsetPx = 80)
        val target = AmbientEntityPositioner.resolveTarget(0f, 0f, tiny, 200)
        // Clamped into the degenerate safe band instead of an infinite loop/NaN.
        assertTrue(target.x >= tiny.safeLeftPx)
        assertTrue(target.y >= tiny.safeTopPx)
        assertTrue(!target.x.isNaN() && !target.y.isNaN())
    }

    // ------------------------------------------------------------------
    // AmbientEntityPositioner.plan — anticipation → travel → settle
    // ------------------------------------------------------------------

    @Test
    fun `zero-distance plan has no anticipation`() {
        val from = AmbientPoint(400f, 400f)
        val plan = AmbientEntityPositioner.plan(from, 400.5f, 400.5f, screen, size)
        assertEquals(0L, plan.anticipationMs)
        assertEquals(from.x, plan.anticipation.x)
        assertEquals(from.y, plan.anticipation.y)
    }

    @Test
    fun `long plan leans opposite the travel direction and stays bounded`() {
        val from = AmbientPoint(100f, 100f)
        val plan = AmbientEntityPositioner.plan(from, 800f, 1600f, screen, size)
        val profile = AmbientMotionProfile()
        // Anticipation is a small step AWAY from the destination.
        val awayX = plan.anticipation.x - from.x
        val awayY = plan.anticipation.y - from.y
        val toDx = plan.to.x - from.x
        val toDy = plan.to.y - from.y
        assertTrue(awayX * toDx < 0 || awayY * toDy < 0)
        val pullback = hypot(awayX, awayY)
        assertTrue("pullback=$pullback must be <= ${profile.anticipationPx}", pullback <= profile.anticipationPx + 0.01f)
        assertTrue(plan.travelDistancePx > 100f)
        assertTrue(plan.anticipationMs > 0L)
    }

    @Test
    fun `plan target is the resolved safe position`() {
        val plan = AmbientEntityPositioner.plan(AmbientPoint(100f, 100f), 90000f, 0f, screen, size)
        val resolved = AmbientEntityPositioner.resolveTarget(90000f, 0f, screen, size)
        assertEquals(resolved, plan.to)
    }

    // ------------------------------------------------------------------
    // AmbientSpringPosition — no teleportation, converges, clamped time
    // ------------------------------------------------------------------

    @Test
    fun `spring approaches the target continuously without teleporting`() {
        val spring = AmbientSpringPosition()
        val start = AmbientPoint(100f, 100f)
        spring.snapTo(start)
        val target = AmbientPoint(700f, 1500f)
        spring.setTarget(target)

        val travel = hypot(target.x - start.x, target.y - start.y)
        var maxFrameStep = 0f
        var maxDistance = 0f
        var frames = 0
        while (!spring.settled && frames < 240) {
            val before = spring.current
            spring.advance(16L)
            val after = spring.current
            maxFrameStep = maxOf(maxFrameStep, hypot(after.x - before.x, after.y - before.y))
            maxDistance = maxOf(maxDistance, hypot(target.x - after.x, target.y - after.y))
            frames++
        }
        // No single frame may cover more than 20% of the total travel — a
        // teleport would be a single step of the whole distance.
        assertTrue("frame step $maxFrameStep too large", maxFrameStep <= travel * 0.20f)
        // The spring never flies away from its target (overshoot is small).
        assertTrue("drifted $maxDistance px from target", maxDistance <= travel * 1.05f)
        assertTrue("spring did not settle in 240 frames", spring.settled)
    }

    @Test
    fun `overshoot stays small and returns to the target`() {
        val spring = AmbientSpringPosition()
        spring.snapTo(AmbientPoint(500f, 500f))
        spring.setTarget(AmbientPoint(500f, 1400f))
        var maxPast = 0f
        var frames = 0
        while (frames < 240 && !(spring.settled)) {
            spring.advance(16L)
            maxPast = maxOf(maxPast, spring.current.y - 1400f)
            frames++
        }
        assertTrue("overshoot ${maxPast}px too large", maxPast < 60f)
        assertTrue(spring.settled)
    }

    @Test
    fun `a huge frame gap is clamped and still converges`() {
        val spring = AmbientSpringPosition()
        spring.snapTo(AmbientPoint(200f, 200f))
        spring.setTarget(AmbientPoint(600f, 600f))
        // Simulates the process being frozen for 10 seconds: one giant advance.
        spring.advance(10_000L)
        var frames = 0
        while (!spring.settled && frames < 240) {
            spring.advance(16L)
            frames++
        }
        assertTrue(spring.settled)
        val current = spring.current
        assertTrue(!current.x.isNaN() && !current.y.isNaN())
        assertEquals(600f, current.x, 4f)
        assertEquals(600f, current.y, 4f)
    }

    @Test
    fun `drag translate moves the entity one to one`() {
        val spring = AmbientSpringPosition()
        spring.snapTo(AmbientPoint(300f, 300f))
        spring.translate(12f, -7f)
        assertEquals(312f, spring.current.x, 0.001f)
        assertEquals(293f, spring.current.y, 0.001f)
        assertEquals(spring.current, spring.target)
    }

    @Test
    fun `startTransition with anticipation keeps the final target honest`() {
        val spring = AmbientSpringPosition()
        spring.snapTo(AmbientPoint(100f, 100f))
        val plan = AmbientEntityPositioner.plan(AmbientPoint(100f, 100f), 900f, 100f, screen, size)
        spring.startTransition(plan)
        assertEquals(plan.to, spring.target)
        // During anticipation the position has not reached the destination yet.
        spring.advance(40L)
        assertTrue(spring.current.x < plan.to.x - 100f)
    }

    // ------------------------------------------------------------------
    // AudioReactiveController — attack/release/stale decay
    // ------------------------------------------------------------------

    @Test
    fun `audio level rises quickly on attack and decays to silence when stale`() {
        var now = 1_000L
        val controller = AudioReactiveController(now = { now })
        controller.updateSource(0.9f, now)
        val first = controller.sample(now + 16)
        assertTrue("first sample should move toward the source", first in 0.3f..0.9f)
        val second = controller.sample(now + 32)
        assertTrue(second > first)
        // Now silence: after the stale window every sample decays toward zero.
        var level = controller.sample(now + 300)
        repeat(60) { level = controller.sample(now + 301 + it) }
        assertTrue("stale audio must decay to silence, got $level", level < 0.1f)
    }

    @Test
    fun `audio level clamps out-of-range input`() {
        var now = 5_000L
        val controller = AudioReactiveController(now = { now })
        controller.updateSource(42f, now)
        assertTrue(controller.sample(now + 1) <= 1f)
        controller.updateSource(-3f, now)
        assertTrue(controller.sample(now + 1) >= 0f)
    }

    @Test
    fun `reset silences the controller`() {
        var now = 1_000L
        val controller = AudioReactiveController(now = { now })
        controller.updateSource(0.8f, now)
        controller.sample(now + 16)
        controller.reset()
        now += 10_000L
        assertEquals(0f, controller.sample(now), 0.0001f)
    }

    // ------------------------------------------------------------------
    // AmbientPhase + AmbientPresenceController
    // ------------------------------------------------------------------

    @Test
    fun `every avatar state maps to its ambient phase`() {
        assertEquals(AmbientPhase.IDLE, AmbientPhase.from(AvatarState.IDLE))
        assertEquals(AmbientPhase.LISTENING, AmbientPhase.from(AvatarState.LISTENING))
        assertEquals(AmbientPhase.THINKING, AmbientPhase.from(AvatarState.THINKING))
        assertEquals(AmbientPhase.SPEAKING, AmbientPhase.from(AvatarState.SPEAKING))
        assertEquals(AmbientPhase.ACTING, AmbientPhase.from(AvatarState.ACTING))
    }

    @Test
    fun `transient pulse decays back to the assistant state exactly once`() {
        var now = 1_000L
        val controller = AmbientPresenceController(now = { now })
        controller.onAssistantState(AvatarState.IDLE)
        controller.pulse(AmbientPhase.SUCCESS, durationMs = 800L)
        assertEquals(AmbientPhase.SUCCESS, controller.currentPhase(now + 100))
        assertTrue(controller.hasTransient(now + 100))
        assertEquals(AmbientPhase.SUCCESS, controller.currentPhase(now + 700))
        now += 900L
        assertFalse(controller.hasTransient(now))
        assertEquals(AmbientPhase.IDLE, controller.currentPhase(now))
        // And it must not re-decay into something else later.
        now += 60_000L
        assertEquals(AmbientPhase.IDLE, controller.currentPhase(now))
    }

    @Test
    fun `minimize parks the entity in SLEEPING`() {
        val controller = AmbientPresenceController()
        controller.onAssistantState(AvatarState.LISTENING)
        controller.setMinimized(true)
        assertEquals(AmbientPhase.SLEEPING, controller.currentPhase())
        controller.setMinimized(false)
        assertEquals(AmbientPhase.LISTENING, controller.currentPhase())
    }

    @Test
    fun `animation intensity scales presence and is floored`() {
        val controller = AmbientPresenceController()
        controller.animationIntensity = 0.5f
        val half = controller.intensity(AmbientPhase.IDLE)
        assertEquals(AmbientPhase.IDLE.intensity * 0.5f, half, 0.0001f)
        controller.animationIntensity = 0f
        assertTrue(controller.intensity(AmbientPhase.IDLE) >= 0.05f)
        controller.animationIntensity = 9f
        assertTrue(controller.intensity(AmbientPhase.IDLE) <= 1f)
    }

    @Test
    fun `pulsing with IDLE is rejected`() {
        val controller = AmbientPresenceController()
        var threw = false
        try {
            controller.pulse(AmbientPhase.IDLE, 100L)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ------------------------------------------------------------------
    // AmbientParticles — deterministic, bounded, budgeted
    // ------------------------------------------------------------------

    @Test
    fun `particles are deterministic and stay near the entity`() {
        val outA = FloatArray(64)
        val outB = FloatArray(64)
        val count = AmbientParticles.positions(32, 12_345L, 0.5f, outA)
        val writtenB = AmbientParticles.positions(32, 12_345L, 0.5f, outB)
        assertEquals(32, count)
        assertEquals(count, writtenB)
        for (index in 0 until 2 * count) {
            assertEquals(outA[index], outB[index], 0f)
            assertTrue("coordinate out of unit space: ${outA[index]}", abs(outA[index]) <= 1.1f)
        }
    }

    @Test
    fun `particle budget respects low power and user intensity`() {
        assertTrue(AmbientParticles.count(lowPower = true, particleIntensity = 1f) <
            AmbientParticles.count(lowPower = false, particleIntensity = 1f))
        assertEquals(6, AmbientParticles.count(lowPower = false, particleIntensity = 0f))
    }

    @Test
    fun `phases declare distinct visual identities`() {
        val phases = AmbientPhase.entries
        assertEquals(10, phases.size)
        assertNotEquals(AmbientPhase.IDLE.intensity, AmbientPhase.LISTENING.intensity)
        assertNotEquals(AmbientPhase.SLEEPING.breathPeriodMs, AmbientPhase.IDLE.breathPeriodMs)
        assertTrue(AmbientPhase.IDLE.lowPower && AmbientPhase.SLEEPING.lowPower)
        assertTrue(!AmbientPhase.LISTENING.lowPower)
    }
}
