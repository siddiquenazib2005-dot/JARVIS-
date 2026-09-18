package com.jarvis.ai.mission

import com.jarvis.ai.accessibility.VerificationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-2 §8/§10: the voice bridge must only speak when there is something to
 * say, and must consume a spoken phrase exactly when the mission needs it to.
 */
class MissionVoiceBridgeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun mission(state: MissionState): Mission {
        val base = Mission(
            goal = "Open Chrome and check the weather",
            currentStep = StepProgress(index = 1, totalSteps = 3, toolName = "open_app")
        )
        return when (state) {
            MissionState.COMPLETED -> base.copy(state = state, verification = VerificationStatus.VERIFIED)
            MissionState.FAILED -> base.copy(state = state, lastError = "element not found")
            else -> base.copy(state = state)
        }
    }

    private fun bridgeWith(mission: Mission?): Pair<MissionRunner, MissionVoiceBridge> {
        val runner = MissionRunner()
        mission?.let { runner.publishForTest(it) }
        val speech = RecordingSpeechPort()
        val bridge = MissionVoiceBridge(speech, scope).also { it.bind(runner) }
        return runner to bridge
    }

    @Test
    fun `announcement is produced only for states the user must hear about`() {
        // Progress states stay silent — the UI already shows them.
        assertNull(mission(MissionState.CREATED).announcementForState())
        assertNull(mission(MissionState.UNDERSTANDING).announcementForState())
        assertNull(mission(MissionState.EXECUTING).announcementForState())
        assertNull(mission(MissionState.VERIFYING).announcementForState())

        // Terminal and decision states are spoken.
        assertNotNull(mission(MissionState.WAITING_CONFIRMATION).announcementForState())
        assertNotNull(mission(MissionState.COMPLETED).announcementForState())
        assertNotNull(mission(MissionState.FAILED).announcementForState())
        assertNotNull(mission(MissionState.CANCELLED).announcementForState())
        assertNotNull(mission(MissionState.RECOVERING).announcementForState())
    }

    @Test
    fun `completed announcement includes the goal`() {
        val text = mission(MissionState.COMPLETED).announcementForState()
        assertTrue(text!!.contains("check the weather"))
    }

    @Test
    fun `failed announcement includes the reason`() {
        val text = mission(MissionState.FAILED).announcementForState()
        assertTrue(text!!.contains("element not found"))
    }

    @Test
    fun `confirmation announcement names the tool without exposing internals`() {
        val text = mission(MissionState.WAITING_CONFIRMATION).announcementForState()
        assertTrue(text!!.contains("open_app"))
        // Plan internals must never leak into speech.
        assertFalse(text.contains("PlanStep") || text.contains("parameters"))
    }

    @Test
    fun `cancel phrase stops a running mission and is consumed`() {
        val (runner, bridge) = bridgeWith(mission(MissionState.EXECUTING))

        val consumed = bridge.handleSpokenPhrase("please cancel that")

        assertTrue(consumed)
        assertEquals(MissionState.CANCELLED, runner.activeMission.value?.state)
    }

    @Test
    fun `confirm phrase is consumed only while waiting for confirmation`() {
        val (runner, bridge) = bridgeWith(mission(MissionState.EXECUTING))

        // EXECUTING + "yes" must NOT be treated as an approval.
        assertFalse(bridge.handleSpokenPhrase("yes"))
        assertNull(bridge.pendingApproval)

        // WAITING_CONFIRMATION + "yes" IS an approval.
        runner.publishForTest(mission(MissionState.WAITING_CONFIRMATION))
        assertTrue(bridge.handleSpokenPhrase("yes"))
        assertEquals("Open Chrome and check the weather", bridge.pendingApproval)
    }

    @Test
    fun `a terminal mission ignores further spoken phrases`() {
        val (_, bridge) = bridgeWith(mission(MissionState.COMPLETED))

        assertFalse(bridge.handleSpokenPhrase("cancel"))
        assertFalse(bridge.handleSpokenPhrase("yes"))
    }

    @Test
    fun `phrases are matched on whole words, not substrings`() {
        val (runner, bridge) = bridgeWith(mission(MissionState.EXECUTING))

        // A cancel word embedded inside a longer word must not fire.
        assertFalse(bridge.handleSpokenPhrase("cancellation of the meeting"))

        // A standalone cancel word still stops the mission.
        runner.publishForTest(mission(MissionState.EXECUTING))
        assertTrue(bridge.handleSpokenPhrase("cancel"))
    }

    @Test
    fun `empty phrase is never consumed`() {
        val (_, bridge) = bridgeWith(mission(MissionState.EXECUTING))
        assertFalse(bridge.handleSpokenPhrase("   "))
    }

    @Test
    fun `unbind stops consuming phrases`() {
        val (runner, bridge) = bridgeWith(mission(MissionState.EXECUTING))
        bridge.unbind()
        // After unbind the bridge holds no runner, so nothing is consumed.
        runner.publishForTest(mission(MissionState.EXECUTING))
        assertFalse(bridge.handleSpokenPhrase("cancel"))
    }

    @Test
    fun `a muted engine is still told to speak, and decides for itself`() {
        val speech = RecordingSpeechPort(muted = true)
        val runner = MissionRunner().also { it.publishForTest(mission(MissionState.COMPLETED)) }
        val bridge = MissionVoiceBridge(speech, scope).also { it.bind(runner) }

        bridge.announceNow("Done.")
        // The port received the text; muting is the engine's decision, so the
        // bridge must not pre-filter and swallow a legitimate announcement.
        assertEquals(listOf("Done."), speech.spoken)
    }

    /** Records speech instead of speaking, for assertions. */
    private class RecordingSpeechPort(override val muted: Boolean = false) : MissionSpeechPort {
        val spoken = mutableListOf<String>()
        override fun speak(text: String) { spoken += text }
    }
}
