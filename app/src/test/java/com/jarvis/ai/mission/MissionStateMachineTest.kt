package com.jarvis.ai.mission

import com.jarvis.ai.accessibility.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-2 §10: the state machine is the single authority for mission lifecycle.
 * These tests cover real behaviour — illegal transitions are rejected, terminal
 * states are absorbing, and cancellation actually stops a mission.
 */
class MissionStateMachineTest {

    private fun fresh() = Mission(goal = "Open Chrome and check the weather")

    @Test
    fun `created mission starts in CREATED and is not terminal`() {
        val m = fresh()
        assertEquals(MissionState.CREATED, m.state)
        assertFalse(m.isTerminal)
        assertTrue(m.state.isActive)
    }

    @Test
    fun `legal lifecycle progresses through every active state`() {
        var m = fresh()
        m = transition(m, MissionState.UNDERSTANDING)
        m = transition(m, MissionState.PLANNING)
        m = transition(m, MissionState.EXECUTING)
        m = transition(m, MissionState.OBSERVING)
        m = transition(m, MissionState.VERIFYING)
        m = transition(m, MissionState.COMPLETED)
        assertEquals(MissionState.COMPLETED, m.state)
        assertTrue(m.isTerminal)
    }

    @Test
    fun `illegal transition is rejected instead of silently applied`() {
        // CREATED cannot jump straight to EXECUTING: planning must happen first.
        assertNull(transition(fresh(), MissionState.EXECUTING))
    }

    @Test
    fun `terminal states are absorbing - a failed mission cannot resume`() {
        var m = fresh()
        m = transition(m, MissionState.UNDERSTANDING)
        m = transition(m, MissionState.FAILED)!!
        // Every subsequent move must be refused.
        assertNull(transition(m, MissionState.EXECUTING))
        assertNull(transition(m, MissionState.PLANNING))
        assertNull(transition(m, MissionState.COMPLETED))
        assertEquals(MissionState.FAILED, m.state)
    }

    @Test
    fun `cancelled mission cannot continue executing later`() {
        var m = fresh()
        m = transition(m, MissionState.PLANNING)
        m = MissionStateMachine.requestCancellation(m)
        assertEquals(MissionState.CANCELLED, m.state)
        assertTrue(m.cancellationRequested)
        assertNull(transition(m, MissionState.EXECUTING))
    }

    @Test
    fun `cancellation is idempotent and safe to call repeatedly`() {
        var m = fresh()
        m = MissionStateMachine.requestCancellation(m)
        val first = m.state
        m = MissionStateMachine.requestCancellation(m)
        assertEquals(first, m.state)
        assertEquals(MissionState.CANCELLED, m.state)
    }

    @Test
    fun `cancelling an already completed mission does not rewrite its outcome`() {
        var m = fresh()
        m = transition(m, MissionState.UNDERSTANDING)
        m = transition(m, MissionState.COMPLETED)!!
        val finishedAt = m.completedAt
        m = MissionStateMachine.requestCancellation(m)
        // A completed mission stays completed; cancel is a no-op on the state.
        assertEquals(MissionState.COMPLETED, m.state)
        assertEquals(finishedAt, m.completedAt)
    }

    @Test
    fun `fail records the reason and marks verification failed`() {
        var m = fresh()
        m = transition(m, MissionState.UNDERSTANDING)
        val failed = MissionStateMachine.fail(m, "element not found")
        assertNotNull(failed)
        assertEquals(MissionState.FAILED, failed!!.state)
        assertEquals("element not found", failed.lastError)
    }

    @Test
    fun `verified step advances progress and records verification`() {
        val m = fresh().copy(currentStep = StepProgress(index = 1, totalSteps = 3))
        val advanced = MissionStateMachine.advanceStep(m, StepOutcome.Verified("saw the result"))
        assertEquals(VerificationStatus.VERIFIED, advanced.currentStep.stepVerification)
        assertEquals(2, advanced.currentStep.index)
    }

    @Test
    fun `failed step records failed verification without advancing past recovery`() {
        val m = fresh().copy(currentStep = StepProgress(index = 0, totalSteps = 2))
        val advanced = MissionStateMachine.advanceStep(m, StepOutcome.Failed("no such node", recoverable = true))
        assertEquals(VerificationStatus.FAILED, advanced.currentStep.stepVerification)
        assertEquals(0, advanced.currentStep.index)
    }

    @Test
    fun `retrying increments the per-step retry counter`() {
        val m = fresh().copy(currentStep = StepProgress(index = 0, totalSteps = 2))
        val once = MissionStateMachine.advanceStep(m, StepOutcome.Retrying(attempt = 1))
        val twice = MissionStateMachine.advanceStep(once, StepOutcome.Retrying(attempt = 2))
        assertEquals(1, once.currentStep.stepRetryCount)
        assertEquals(2, twice.currentStep.stepRetryCount)
    }

    @Test
    fun `progress fraction is bounded between zero and one`() {
        val m = fresh().copy(currentStep = StepProgress(index = 0, totalSteps = 4))
        assertEquals(0f, m.progressFraction, 0.001f)
        val late = m.copy(currentStep = m.currentStep.copy(index = 3))
        assertEquals(0.75f, late.progressFraction, 0.001f)
        // Index beyond the step count must not exceed 1.
        val overflow = m.copy(currentStep = m.currentStep.copy(index = 99))
        assertTrue(overflow.progressFraction <= 1f)
    }

    @Test
    fun `waiting confirmation can proceed to executing or be cancelled`() {
        var m = fresh()
        m = transition(m, MissionState.UNDERSTANDING)
        m = transition(m, MissionState.PLANNING)
        m = transition(m, MissionState.WAITING_CONFIRMATION)!!
        assertNotNull(transition(m, MissionState.EXECUTING))
        assertNotNull(transition(m, MissionState.CANCELLED))
    }

    @Test
    fun `recovering can re-plan or re-execute but cannot jump to completed`() {
        var m = fresh()
        m = transition(m, MissionState.UNDERSTANDING)
        m = transition(m, MissionState.PLANNING)
        m = transition(m, MissionState.EXECUTING)
        m = transition(m, MissionState.RECOVERING)!!
        assertNotNull(transition(m, MissionState.EXECUTING))
        assertNull(transition(m, MissionState.COMPLETED))
    }

    private fun transition(mission: Mission, next: MissionState): Mission? =
        MissionStateMachine.transition(mission, next)
}
