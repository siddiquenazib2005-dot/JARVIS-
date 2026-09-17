package com.jarvis.ai.planning

import com.jarvis.ai.orchestrator.CancellationToken
import com.jarvis.ai.orchestrator.PlanStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    private val okSteps = listOf(
        PlanStep(toolName = "a", parameters = emptyMap(), description = "step A"),
        PlanStep(
            toolName = "b", parameters = emptyMap(), description = "step B",
            dependsOn = 0
        ),
        PlanStep(
            toolName = "c", parameters = emptyMap(), description = "step C",
            dependsOn = 1
        )
    )

    @Test
    fun `all steps complete successfully in order`() = runBlocking {
        val loop = AgentLoop(AgentLimits(maxIterations = 5))
        val report = loop.run(
            goal = "demo goal",
            steps = okSteps,
            executor = { step, _ -> StepOutcome.Success("out-${step.toolName}") }
        )
        assertTrue(report.success)
        assertEquals(3, report.outputs.size)
        assertEquals(listOf("step A", "step B", "step C"), report.completedSteps)
    }

    @Test
    fun `iteration limit halts the loop`() = runBlocking {
        val loop = AgentLoop(AgentLimits(maxIterations = 2))
        val report = loop.run(
            goal = "goal",
            steps = okSteps,
            executor = { _, _ -> StepOutcome.Success("ok") }
        )
        assertFalse(report.success)
        assertNotNull(report.stoppedReason)
        assertTrue(report.stoppedReason!!.contains("iteration limit"))
    }

    @Test
    fun `failure stops the run with honest reason`() = runBlocking {
        val loop = AgentLoop(AgentLimits(retryPerStep = 0))
        val steps = listOf(
            PlanStep("bad", emptyMap(), "failing step"),
            PlanStep("never", emptyMap(), "should never run")
        )
        val report = loop.run(
            goal = "goal",
            steps = steps,
            executor = { step, _ ->
                if (step.toolName == "bad") StepOutcome.Failure("boom", recoverable = false)
                else StepOutcome.Success("nope")
            }
        )
        assertFalse(report.success)
        assertTrue(report.stoppedReason.orEmpty().contains("boom"))
        assertFalse(report.completedSteps.any { it.contains("never") })
    }

    @Test
    fun `recoverable failure retries once then succeeds`() = runBlocking {
        var attempts = 0
        val loop = AgentLoop(AgentLimits(retryPerStep = 1))
        val report = loop.run(
            goal = "goal",
            steps = listOf(PlanStep("flaky", emptyMap(), "flaky step")),
            executor = { _, _ ->
                attempts += 1
                if (attempts == 1) throw java.io.IOException("transient")
                StepOutcome.Success("recovered")
            }
        )
        assertTrue(report.success)
        assertEquals(2, attempts)
    }

    @Test
    fun `verification failure triggers retry`() = runBlocking {
        var verifyCalls = 0
        val loop = AgentLoop(AgentLimits(retryPerStep = 1))
        val report = loop.run(
            goal = "goal",
            steps = listOf(PlanStep("check", emptyMap(), "verified step")),
            executor = { _, _ -> StepOutcome.Success("suspect") },
            verifier = { _, _ ->
                verifyCalls += 1
                verifyCalls >= 2
            }
        )
        assertTrue(report.success)
        assertEquals(2, verifyCalls)
    }

    @Test
    fun `cancellation stops immediately`() = runBlocking {
        val token = CancellationToken()
        token.cancel()
        val loop = AgentLoop()
        val report = loop.run(
            goal = "goal",
            steps = okSteps,
            cancellationToken = token,
            executor = { _, _ -> StepOutcome.Success("x") }
        )
        assertFalse(report.success)
        assertTrue(report.stoppedReason.orEmpty().contains("cancelled"))
    }

    @Test
    fun `failed dependency blocks dependent step`() = runBlocking {
        val loop = AgentLoop(AgentLimits(maxIterations = 5))
        val steps = listOf(
            PlanStep("root", emptyMap(), "root fails"),
            PlanStep("child", emptyMap(), "depends on root", dependsOn = 0)
        )
        val report = loop.run(
            goal = "goal",
            steps = steps,
            executor = { step, _ ->
                if (step.toolName == "root") StepOutcome.Failure("root broke", recoverable = false)
                else StepOutcome.Success("unreachable")
            }
        )
        assertFalse(report.success)
        assertTrue(report.outputs.isEmpty())
    }
}
