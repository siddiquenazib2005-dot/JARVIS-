package com.jarvis.ai.mission

import com.jarvis.ai.orchestrator.PlanStep
import com.jarvis.ai.orchestrator.ToolResult
import com.jarvis.ai.planning.AgentLimits
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-2 §10: execution behaviour. These exercise the runner through the real
 * AgentLoop, so the bounded-retry, timeout and cancellation guarantees being
 * claimed are the ones actually in force.
 *
 * Tool routing note: read_screen/find_element and the other device tools are
 * routed to DeviceToolExecutor, which needs a live accessibility service. These
 * tests therefore drive the non-device path through [ToolExecutionPort] with a
 * synthetic tool name, which is exactly the seam production code uses.
 */
class MissionRunnerTest {

    private fun plan(vararg tools: String): List<PlanStep> = tools.mapIndexed { i, t ->
        PlanStep(toolName = t, parameters = emptyMap(), description = "step $i: $t")
    }

    @Test
    fun `successful steps complete the mission`() = runBlocking {
        val runner = MissionRunner(toolPort = FakePort(setOf("probe"), success = true))
        val mission = runner.run("test goal", plan("probe", "probe"))
        assertEquals(MissionState.COMPLETED, mission.state)
        assertTrue(mission.isTerminal)
    }

    @Test
    fun `a failing tool fails the mission rather than reporting success`() = runBlocking {
        val runner = MissionRunner(toolPort = FakePort(setOf("probe"), success = false))
        val mission = runner.run("test goal", plan("probe"))
        assertEquals(MissionState.FAILED, mission.state)
        assertFalse(mission.state == MissionState.COMPLETED)
    }

    @Test
    fun `a tool the port does not support is not faked as success`() = runBlocking {
        val runner = MissionRunner(toolPort = FakePort(setOf("probe"), success = true))
        val mission = runner.run("goal", plan("not_a_real_tool"))
        // The port reports failure for anything it does not support.
        assertEquals(MissionState.FAILED, mission.state)
    }

    @Test
    fun `user cancellation stops the run and marks the mission cancelled`() = runBlocking {
        val runner = MissionRunner(toolPort = SlowPort(setOf("probe")))
        coroutineScope {
            val job = async { runner.run("long goal", plan("probe", "probe", "probe")) }
            // Let the loop enter the first step, then cancel from outside.
            delay(150)
            runner.cancel()
            val mission = job.await()
            assertEquals(MissionState.CANCELLED, mission.state)
            assertTrue(mission.cancellationRequested)
        }
    }

    @Test
    fun `a run publishes a mission and leaves a terminal one behind`() = runBlocking {
        val runner = MissionRunner(toolPort = FakePort(setOf("probe"), success = true))
        // Before running there is no active mission.
        assertNull(runner.activeMission.value)
        val mission = runner.run("goal", plan("probe"))
        // After completion the observable mission is the terminal one.
        assertEquals(mission, runner.activeMission.value)
        assertTrue(mission.isTerminal)
    }

    @Test
    fun `limits are finite so a stuck mission cannot loop forever`() {
        val limits = AgentLimits(maxIterations = 3, maxToolCalls = 5, maxFailures = 2, timeoutMs = 2_000)
        // A mission whose every step fails must terminate, not spin.
        val runner = MissionRunner(
            toolPort = FakePort(setOf("probe"), success = false),
            limits = limits
        )
        val mission = runBlocking { runner.run("goal", plan("probe")) }
        assertTrue(mission.isTerminal)
        assertEquals(MissionState.FAILED, mission.state)
    }

    /**
     * In-memory stand-in for the tool layer. Reports success or failure
     * deterministically, and refuses any tool it does not support — it never
     * fakes a capability it does not have.
     */
    private class FakePort(private val supported: Set<String>, private val success: Boolean) : ToolExecutionPort {
        override suspend fun execute(
            toolName: String,
            parameters: Map<String, Any?>,
            userConfirmedThisTurn: Boolean
        ): ToolResult = if (toolName !in supported) {
            ToolResult.Failure(toolName, "unsupported in test", recoverable = false)
        } else if (success) {
            ToolResult.Success(toolName, emptyMap(), "ok")
        } else {
            ToolResult.Failure(toolName, "boom", recoverable = false)
        }
    }

    /** Port that blocks long enough for a cancellation race to be won. */
    private class SlowPort(private val supported: Set<String>) : ToolExecutionPort {
        override suspend fun execute(
            toolName: String,
            parameters: Map<String, Any?>,
            userConfirmedThisTurn: Boolean
        ): ToolResult {
            delay(3_000) // Long enough for the test to cancel mid-flight.
            return ToolResult.Success(toolName, emptyMap(), "late")
        }
    }
}
