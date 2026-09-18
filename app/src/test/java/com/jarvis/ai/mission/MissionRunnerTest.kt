package com.jarvis.ai.mission

import com.jarvis.ai.orchestrator.PlanStep
import com.jarvis.ai.orchestrator.ToolResult
import com.jarvis.ai.planning.AgentLimits
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-2 §10: execution behaviour. These exercise the runner through the real
 * AgentLoop, so the bounded-retry, timeout and cancellation guarantees being
 * claimed are the ones actually in force.
 */
class MissionRunnerTest {

    private fun plan(vararg tools: String): List<PlanStep> = tools.mapIndexed { i, t ->
        PlanStep(toolName = t, parameters = emptyMap(), description = "step $i: $t")
    }

    @Test
    fun `successful steps complete the mission`() = runBlocking {
        val runner = MissionRunner(toolPort = FakeToolExecutor(success = true))
        val mission = runner.run("test goal", plan("read_screen", "find_element"))
        assertEquals(MissionState.COMPLETED, mission.state)
        assertTrue(mission.isTerminal)
    }

    @Test
    fun `a failing tool fails the mission rather than reporting success`() = runBlocking {
        val runner = MissionRunner(toolPort = FakeToolExecutor(success = false))
        val mission = runner.run("test goal", plan("read_screen"))
        assertEquals(MissionState.FAILED, mission.state)
        assertFalse(mission.state == MissionState.COMPLETED)
    }

    @Test
    fun `user cancellation stops the run and marks the mission cancelled`() = runBlocking {
        val runner = MissionRunner(toolPort = SlowToolExecutor())
        // Start a run that will not finish on its own, cancel from outside.
        coroutineScope {
            val job = async { runner.run("long goal", plan("read_screen", "read_screen", "read_screen")) }
            // Let the loop enter the first step, then cancel.
            delay(200)
            runner.cancel()
            val mission = job.await()
            assertEquals(MissionState.CANCELLED, mission.state)
            assertTrue(mission.cancellationRequested)
        }
    }

    @Test
    fun `an unknown tool fails the step without faking success`() = runBlocking {
        val runner = MissionRunner(toolPort = FakeToolExecutor(success = true))
        val mission = runner.run("goal", plan("does_not_exist"))
        // The tool registry has no such tool: the executor must fail it.
        assertEquals(MissionState.FAILED, mission.state)
    }

    @Test
    fun `a run publishes a mission and leaves a terminal one behind`() = runBlocking {
        val runner = MissionRunner(toolPort = FakeToolExecutor(success = true))
        // Before running there is no active mission.
        assertNull(runner.activeMission.value)
        val mission = runner.run("goal", plan("read_screen"))
        // After completion the observable mission is the terminal one.
        assertEquals(mission, runner.activeMission.value)
        assertTrue(mission.isTerminal)
    }

    @Test
    fun `limits are finite so a stuck mission cannot loop forever`() {
        val limits = AgentLimits(maxIterations = 3, maxToolCalls = 5, maxFailures = 2, timeoutMs = 2_000)
        // A mission whose every step fails must terminate, not spin.
        val runner = MissionRunner(
            toolPort = FakeToolExecutor(success = false),
            limits = limits
        )
        val mission = runBlocking { runner.run("goal", plan("read_screen")) }
        assertTrue(mission.isTerminal)
        assertEquals(MissionState.FAILED, mission.state)
    }

    /**
     * In-memory stand-in for the real ToolExecutor.
     *
     * The production [com.jarvis.ai.orchestrator.ToolExecutor] is final and takes
     * an Android Context, so tests exercise the runner through this type which
     * speaks the same [ToolResult] contract.
     */
    private class FakeToolExecutor(private val success: Boolean) : ToolExecutionPort {
        override suspend fun execute(
            toolName: String,
            parameters: Map<String, Any?>,
            userConfirmedThisTurn: Boolean
        ): ToolResult = if (success) {
            ToolResult.Success(toolName, emptyMap(), "ok")
        } else {
            ToolResult.Failure(toolName, "boom", recoverable = false)
        }
    }

    /** Executor that blocks long enough for a cancellation race to be won. */
    private class SlowToolExecutor : ToolExecutionPort {
        override suspend fun execute(
            toolName: String,
            parameters: Map<String, Any?>,
            userConfirmedThisTurn: Boolean
        ): ToolResult {
            delay(5_000) // Long enough for the test to cancel mid-flight.
            return ToolResult.Success(toolName, emptyMap(), "late")
        }
    }
}
