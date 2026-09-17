package com.jarvis.ai.planning

import com.jarvis.ai.orchestrator.CancellationToken
import com.jarvis.ai.orchestrator.PlanStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Hard limits — the agent loop can never exceed these. */
data class AgentLimits(
    val maxIterations: Int = 6,
    val maxToolCalls: Int = 10,
    val maxFailures: Int = 3,
    val retryPerStep: Int = 1,
    val timeoutMs: Long = 45_000L,
    val stepBackoffMs: Long = 300L
)

/** Outcome of a single executed step. */
sealed class StepOutcome {
    data class Success(val output: String) : StepOutcome()
    data class Failure(val error: String, val recoverable: Boolean) : StepOutcome()
}

/** Aggregate result of one full agent run. */
data class AgentRunReport(
    val success: Boolean,
    val completedSteps: List<String>,
    val outputs: Map<String, String>,
    val iterationsUsed: Int,
    val toolCallsUsed: Int,
    val stoppedReason: String?,
    val elapsedMs: Long
)

/**
 * Controlled agent loop:
 *   PLAN → EXECUTE → OBSERVE → (retry ≤ limit) → VERIFY → next | stop.
 *
 * Enforced limits: iterations, tool-calls, failures, per-run timeout,
 * cancellation token. There is no code path that loops forever.
 */
class AgentLoop(
    private val limits: AgentLimits = AgentLimits(),
    private val now: () -> Long = System::currentTimeMillis
) {

    /**
     * @param steps ordered plan; [PlanStep.dependsOn] must reference an earlier index.
     * @param executor executes ONE step and returns a structured outcome.
     * @param verifier post-execution verification; returning false converts a
     *        success into a recoverable failure (self-healing trigger).
     */
    suspend fun run(
        goal: String,
        steps: List<PlanStep>,
        cancellationToken: CancellationToken = CancellationToken(),
        executor: suspend (PlanStep, Map<Int, String>) -> StepOutcome,
        verifier: suspend (PlanStep, StepOutcome.Success) -> Boolean = { _, _ -> true }
    ): AgentRunReport = withContext(Dispatchers.IO) {
        val startedAt = now()
        var iterations = 0
        var toolCalls = 0
        var totalFailures = 0
        val outputs = linkedMapOf<Int, String>()
        val completedDescriptions = mutableListOf<String>()
        var stoppedReason: String? = null

        for ((index, step) in steps.withIndex()) {
            if (cancellationToken.isCancelled) {
                stoppedReason = "cancelled by user"; break
            }
            if (iterations >= limits.maxIterations) {
                stoppedReason = "iteration limit (${limits.maxIterations}) reached"; break
            }
            if (toolCalls >= limits.maxToolCalls) {
                stoppedReason = "tool-call limit (${limits.maxToolCalls}) reached"; break
            }
            if (totalFailures >= limits.maxFailures) {
                stoppedReason = "failure limit (${limits.maxFailures}) reached"; break
            }
            if (now() - startedAt >= limits.timeoutMs) {
                stoppedReason = "timeout (${limits.timeoutMs}ms) reached"; break
            }

            // Dependency check — a step runs only after its dependency succeeded.
            val dependency = step.dependsOn
            if (dependency != null && !outputs.containsKey(dependency)) {
                stoppedReason = "dependency of step $index failed"
                break
            }

            iterations += 1
            var outcome: StepOutcome = executeVerified(
                step,
                executor,
                verifier,
                cancellationToken,
                outputs.toMap()
            )

            when (outcome) {
                is StepOutcome.Success -> {
                    toolCalls += 1
                    outputs[index] = outcome.output
                    completedDescriptions += step.description
                }
                is StepOutcome.Failure -> {
                    totalFailures += 1
                    stoppedReason = "step $index failed: ${outcome.error.take(120)}"
                    break
                }
            }
        }

        val allDone = stoppedReason == null && outputs.size == steps.size && steps.isNotEmpty()
        AgentRunReport(
            success = allDone,
            completedSteps = completedDescriptions,
            outputs = outputs.mapKeys { it.key.toString() },
            iterationsUsed = iterations,
            toolCallsUsed = toolCalls,
            stoppedReason = stoppedReason,
            elapsedMs = now() - startedAt
        )
    }

    /**
     * Executes a step with bounded retries. A success that FAILS verification
     * is treated as recoverable and retried; after retries are exhausted an
     * unverified success is reported as FAILURE (never as success).
     */
    private suspend fun executeVerified(
        step: PlanStep,
        executor: suspend (PlanStep, Map<Int, String>) -> StepOutcome,
        verifier: suspend (PlanStep, StepOutcome.Success) -> Boolean,
        cancellationToken: CancellationToken,
        previousOutputs: Map<Int, String>
    ): StepOutcome {
        var attempt = 0
        var lastUnverified: StepOutcome.Success? = null
        var lastFailure: StepOutcome.Failure =
            StepOutcome.Failure("not attempted", recoverable = true)

        while (attempt <= limits.retryPerStep) {
            cancellationToken.checkCancellation()
            val outcome: StepOutcome = try {
                executor(step, previousOutputs)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                StepOutcome.Failure(e.message ?: e.javaClass.simpleName, recoverable = true)
            }

            when (outcome) {
                is StepOutcome.Success -> {
                    if (verifier(step, outcome)) return outcome
                    lastUnverified = outcome
                }
                is StepOutcome.Failure -> {
                    lastFailure = outcome
                    if (!outcome.recoverable) return outcome
                }
            }
            attempt += 1
            if (attempt <= limits.retryPerStep) delay(limits.stepBackoffMs)
        }

        return lastUnverified
            ?.let { StepOutcome.Failure("verification failed", recoverable = false) }
            ?: lastFailure
    }
}
