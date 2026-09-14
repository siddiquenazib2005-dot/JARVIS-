package com.jarvis.ai.core.nervous

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Test

class JarvisStateMachineTest {
    @Test fun actionFollowsExecutionAndVerificationLifecycle() {
        val machine = JarvisStateMachine(now = { 10L }, newId = { "action-1" })
        machine.begin("turn torch on")
        machine.transition(JarvisPhase.PLANNING)
        machine.transition(JarvisPhase.EXECUTING)
        machine.transition(JarvisPhase.VERIFYING)
        machine.transition(JarvisPhase.SUCCESS)
        assertEquals(JarvisPhase.SUCCESS, machine.state.value.phase)
        assertEquals("action-1", machine.state.value.actionId)
    }

    @Test fun impossibleJumpIsRejected() {
        val machine = JarvisStateMachine()
        assertFailsWith<IllegalArgumentException> { machine.transition(JarvisPhase.SUCCESS) }
    }

    @Test fun cancellationIsTerminal() {
        val machine = JarvisStateMachine(newId = { "a" })
        machine.begin("call contact")
        machine.cancel()
        assertEquals(JarvisPhase.CANCELLED, machine.state.value.phase)
    }
}
