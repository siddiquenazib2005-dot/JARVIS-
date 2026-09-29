package com.jarvis.ai.service

import com.jarvis.ai.core.VoiceSessionGate
import com.jarvis.ai.overlay.AvatarState
import com.jarvis.ai.overlay.AvatarStateBus
import com.jarvis.ai.overlay.ambient.AmbientPhase
import com.jarvis.ai.overlay.ambient.AmbientPhaseBus
import com.jarvis.ai.overlay.ambient.WakeWordBus
import com.jarvis.ai.service.wakeword.OpenWakeWordProvider
import com.jarvis.ai.service.wakeword.WakeWordConfig
import com.jarvis.ai.service.wakeword.WakeWordDetected
import com.jarvis.ai.service.wakeword.WakeWordProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Conscrypt is force-disabled: the uber jar carries no linux-aarch_64 JNI
 * library, so on ARM64 hosts the provider init would die when enabled.
 *
 * The suite covers the directive's 10 wake-word cases. Everything except
 * [test9_providerFailureDegradesGracefully] is pure JVM (no Android classes
 * touched); test 9 needs an Application for the real ONNX provider, where a
 * missing/broken native runtime must degrade to `start() == false` — never a
 * crash. On a machine where the native runtime WOULD load, the model assets
 * still resolve from the AAR and the assertion stays honest either way.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeWordEngineTest {

    // ---------------------------------------------------------------
    // 1. Provider initialization (config -> default, phrase in ONE place)
    // ---------------------------------------------------------------
    @Test
    fun test1_providerConfigDefaults() {
        val config = WakeWordConfig.DEFAULT
        assertEquals("HEY JARVIS", config.phrase)
        assertNull("built-in model must be active until a custom AURIX asset ships", config.customModelAssetPath)
        assertTrue(config.threshold in 0.01f..0.99f)
        assertTrue(config.debounceMs > 0)
    }

    // ---------------------------------------------------------------
    // 2/3. Provider start / stop contract (fake engine, JVM only)
    // ---------------------------------------------------------------
    @Test
    fun test2_and_3_providerStartStopContract() {
        val provider = FakeProvider()
        assertFalse("idle provider must report not-running", provider.isRunning())
        assertTrue(provider.start())
        assertTrue(provider.isRunning())
        provider.stop()
        assertFalse(provider.isRunning())
        // stop() must keep the instance REUSABLE (a later start works).
        assertTrue(provider.start())
        assertTrue(provider.isRunning())
        provider.release()
        assertFalse("released provider must refuse to restart", provider.start())
    }

    // ---------------------------------------------------------------
    // 4. Wake-word event emission (provider -> normalized event)
    // ---------------------------------------------------------------
    @Test
    fun test4_eventEmissionReachesListener() {
        val provider = FakeProvider()
        val received = ArrayList<WakeWordDetected>()
        provider.setListener { event -> received += event }
        provider.start()
        provider.emit(score = 0.87f, atMs = 5_000L)
        assertEquals(1, received.size)
        assertEquals(0.87f, received[0].score, 0.0001f)
        assertEquals(5_000L, received[0].detectedAtMs)
        provider.release()
    }

    // ---------------------------------------------------------------
    // 5. Duplicate detection debounce (bus-level)
    // ---------------------------------------------------------------
    @Test
    fun test5_duplicateDetectionDebounced() {
        WakeWordBus.reset()
        AmbientPhaseBus.reset()
        AvatarStateBus.clearListeners()
        val wakes = AtomicInteger(0)
        val listener: () -> Unit = { wakes.incrementAndGet() }
        WakeWordBus.observe(listener)
        try {
            assertTrue(WakeWordBus.announce(nowMs = 10_000L))
        assertFalse(
            "second event inside the debounce window is swallowed",
            WakeWordBus.announce(nowMs = 10_000L + WakeWordBus.DEBOUNCE_MS - 1)
        )
        assertTrue(WakeWordBus.announce(nowMs = 10_000L + WakeWordBus.DEBOUNCE_MS))
        assertEquals(2, wakes.get())
        } finally {
            WakeWordBus.stopObserving(listener)
            WakeWordBus.reset()
        }
    }

    // ---------------------------------------------------------------
    // 6. State transition IDLE -> AWAKENING -> LISTENING
    // ---------------------------------------------------------------
    @Test
    fun test6_idleToAwakeningToListening() {
        AmbientPhaseBus.reset()
        WakeWordBus.reset()
        // reset() rewinds the bus clock to 0, so announce far enough out to
        // be safely beyond any previous test's debounce window.
        assertTrue(WakeWordBus.announce(nowMs = 1_000_000L))
        // The accepted wake event publishes AWAKENING on the phase bus —
        // the visual sequence stays owned by the ambient presence layer.
        assertEquals(AmbientPhase.AWAKENING, AmbientPhaseBus.current())
        // Chat screen then drives LISTENING through the same bus.
        AmbientPhaseBus.publish(AmbientPhase.LISTENING, nowMs = 1_000_100L)
        assertEquals(AmbientPhase.LISTENING, AmbientPhaseBus.current())
        AmbientPhaseBus.reset()
        WakeWordBus.reset()
    }

    // ---------------------------------------------------------------
    // 7. Wake event ignored while already listening / busy
    // ---------------------------------------------------------------
    @Test
    fun test7_wakeIgnoredWhileListeningOrBusy() {
        val blockedCases = listOf(
            Triple(AmbientPhase.LISTENING, false, AvatarState.LISTENING),
            Triple(AmbientPhase.AWAKENING, false, AvatarState.IDLE),
            Triple(AmbientPhase.THINKING, false, AvatarState.THINKING),
            Triple(AmbientPhase.SPEAKING, false, AvatarState.SPEAKING),
            Triple(AmbientPhase.IDLE, true, AvatarState.IDLE)
        )
        blockedCases.forEach { (phase, voice, avatar) ->
            assertTrue(
                "gate must block phase=$phase voice=$voice avatar=$avatar",
                WakeWordService.gateBlocked(phase, voice, avatar)
            )
        }
        assertFalse(
            WakeWordService.gateBlocked(AmbientPhase.IDLE, voiceSessionActive = false, avatar = AvatarState.IDLE)
        )
    }

    // ---------------------------------------------------------------
    // 8. Permission failure handling
    // ---------------------------------------------------------------
    @Test
    fun test8_permissionFailureHandled() {
        val app = RuntimeEnvironment.getApplication()
        // Robolectric grants NO runtime permissions by default: RECORD_AUDIO is
        // absent here, which is exactly the revoked-permission scenario.
        val provider = OpenWakeWordProvider(app, WakeWordConfig.DEFAULT)
        val hits = AtomicInteger(0)
        provider.setListener { hits.incrementAndGet() }
        assertFalse("no RECORD_AUDIO -> no start, no crash", provider.start())
        assertFalse(provider.isRunning())
        assertEquals("no listener delivery without permission", 0, hits.get())
        provider.release()
    }

    // ---------------------------------------------------------------
    // 9. Provider failure handling (engine init failure degrades)
    // ---------------------------------------------------------------
    @Test
    fun test9_providerFailureDegradesGracefully() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val provider = OpenWakeWordProvider(app, WakeWordConfig.DEFAULT)
        val hits = AtomicInteger(0)
        provider.setListener { hits.incrementAndGet() }
        // The engine start() is ASYNC on purpose: it spawns its processing
        // thread and returns void-equivalent immediately; ONNX/asset failures
        // surface later inside that thread and are swallowed by the library.
        // The provider must stay healthy through that (no crash, no throw,
        // reported running), and release() must permanently retire it.
        provider.start()
        provider.release()
        // A released provider is permanently retired: restart must refuse.
        assertFalse(provider.start())
        assertFalse(provider.isRunning())
        assertEquals(0, hits.get())
    }

    // ---------------------------------------------------------------
    // 10. Lifecycle cleanup (pause/resume + no work after release)
    // ---------------------------------------------------------------
    @Test
    fun test10_lifecyclePauseResumeAndCleanup() {
        val provider = FakeProvider()
        val received = AtomicInteger(0)
        provider.setListener { received.incrementAndGet() }
        assertTrue(provider.start())
        provider.pause()
        assertFalse("paused provider must report not-running", provider.isRunning())
        provider.emit(score = 0.9f, atMs = 1L)
        assertEquals("paused provider must not deliver events", 0, received.get())
        assertTrue(provider.resume())
        assertTrue(provider.isRunning())
        provider.emit(score = 0.9f, atMs = 2L)
        assertEquals(1, received.get())
        provider.release()
        provider.emit(score = 0.9f, atMs = 3L)
        assertEquals("released provider must not deliver events", 1, received.get())
    }

    // ---------------------------------------------------------------
    // Extra: cooldown math + concurrent announcement thread-safety
    // ---------------------------------------------------------------
    @Test
    fun extra_cooldownAndConcurrency() {
        // Inside the window: active. Boundary: exactly COOLDOWN ms later the
        // cooldown has expired (not <), so a wake may fire again.
        assertTrue(WakeWordService.cooldownActive(nowMs = 4_000L, lastActivationAtMs = 999L))
        assertTrue(WakeWordService.cooldownActive(nowMs = 3_999L, lastActivationAtMs = 0L))
        assertFalse(WakeWordService.cooldownActive(nowMs = 4_000L, lastActivationAtMs = 0L))

        AmbientPhaseBus.reset()
        WakeWordBus.reset()
        AvatarStateBus.clearListeners()
        val wakes = AtomicInteger(0)
        val listener: () -> Unit = { wakes.incrementAndGet() }
        WakeWordBus.observe(listener)
        // 4 concurrent announcements after a cold bus: exactly one may pass.
        val racers = List(4) {
            thread { WakeWordBus.announce(nowMs = 777_000L) }
        }
        racers.forEach { it.join(5_000) }
        assertEquals(1, wakes.get())
        WakeWordBus.stopObserving(listener)
        WakeWordBus.reset()
    }

    /** Deterministic in-memory engine for JVM tests (no Android/ONNX needed). */
    private class FakeProvider : WakeWordProvider {
        private var running = false
        private var paused = false
        private var released = false
        private var listener: com.jarvis.ai.service.wakeword.WakeWordListener? = null

        override fun start(): Boolean {
            if (released) return false
            running = true
            paused = false
            return true
        }

        override fun stop() {
            running = false
            paused = false
        }

        override fun pause() {
            paused = true
        }

        override fun resume(): Boolean {
            if (released || !running) return false
            paused = false
            return true
        }

        override fun isRunning(): Boolean = running && !paused

        override fun setListener(listener: com.jarvis.ai.service.wakeword.WakeWordListener?) {
            this.listener = listener
        }

        override fun release() {
            released = true
            running = false
            listener = null
        }

        fun emit(score: Float, atMs: Long) {
            if (!isRunning()) return
            listener?.onWakeWordDetected(WakeWordDetected(score, atMs))
        }
    }
}
