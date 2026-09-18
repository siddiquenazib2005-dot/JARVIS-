package com.jarvis.ai.mission

import com.jarvis.ai.accessibility.VerificationStatus
import com.jarvis.ai.memory.MemoryEngine
import com.jarvis.ai.orchestrator.CancellationToken
import com.jarvis.ai.orchestrator.PlanStep
import com.jarvis.ai.orchestrator.ToolExecutor
import com.jarvis.ai.orchestrator.ToolResult
import com.jarvis.ai.planning.AgentLimits
import com.jarvis.ai.planning.AgentLoop
import com.jarvis.ai.planning.AgentRunReport
import com.jarvis.ai.planning.StepOutcome as LoopStepOutcome

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase-2 §2/§5/§6: runs a [Mission] through the EXISTING [AgentLoop] and drives
 * its state machine from real tool outcomes.
 *
 * Pipeline:  Mission → AgentLoop → ToolExecutor/DeviceToolExecutor →
 *            Observation (ScreenContext) → Verification → Mission update.
 *
 * The AgentLoop is not replaced or duplicated; this class is the adapter that
 * speaks Mission state to the UI while the loop speaks StepOutcome to it.
 *
 * Cancellation: a single [CancellationToken] is shared by the mission and the
 * loop, so a user cancel or a mission timeout stops the loop's step iteration
 * AND the in-flight device action beneath it.
 *
 * Tool-execution seam: the production [ToolExecutor] is final and Context-bound,
 * so [ToolExecutionPort] keeps the runner (and its tests) decoupled from Android
 * while still speaking the real [ToolExecutor.ToolResult] contract.
 */
fun interface ToolExecutionPort {
    suspend fun execute(
        toolName: String,
        parameters: Map<String, Any?>,
        userConfirmedThisTurn: Boolean
    ): ToolResult
}

class MissionRunner(
    private val toolExecutor: ToolExecutor? = null,
    private val toolPort: ToolExecutionPort? = null,
    private val deviceToolExecutor: DeviceToolExecutor? = null,
    private val memory: MemoryEngine? = null,
    private val limits: AgentLimits = AgentLimits(),
    private val loop: AgentLoop = AgentLoop(limits)
) {

    private val _active = MutableStateFlow<Mission?>(null)
    /** The live mission, observed by the UI. Null when no mission is running. */
    val activeMission: StateFlow<Mission?> = _active.asStateFlow()

    /** Cancellation token of the current run; cancelled on user request/timeout. */
    @Volatile
    private var currentToken: CancellationToken? = null

    /**
     * Executes a mission end to end.
     *
     * @param planSteps ordered tool steps; produced by the planner.
     * @param memoryQuery optional recall performed BEFORE planning, surfaced to
     *        the caller via [Mission.memoryContext] (Phase 2 §7).
     * @return the terminal mission.
     */
    suspend fun run(
        goal: String,
        planSteps: List<PlanStep>,
        memoryQuery: String? = null,
        userConfirmed: Boolean = false
    ): Mission {
        var mission = Mission(
            goal = goal,
            plan = planSteps.map { it.description },
            currentStep = StepProgress(index = 0, totalSteps = planSteps.size)
        )
        _active.value = mission

        // §7: recall relevant context BEFORE planning. Only non-sensitive recall
        // is performed; nothing is stored here (storage happens on success).
        memoryQuery?.let { q ->
            mission = mission.copy(memoryContext = memory?.recallFact(q))
            publish(mission)
        }

        mission = requireNotNull(MissionStateMachine.transition(mission, MissionState.UNDERSTANDING)) { "UNDERSTANDING" }
        mission = requireNotNull(MissionStateMachine.transition(mission, MissionState.PLANNING)) { "PLANNING" }
        publish(mission)

        // Device tools need confirmation handling; the gate is consulted per step.
        mission = requireNotNull(MissionStateMachine.transition(mission, MissionState.EXECUTING)) { "EXECUTING" }

        val token = CancellationToken().also { currentToken = it }
        val outputs = linkedMapOf<Int, String>()

        val report: AgentRunReport = loop.run(
            goal = goal,
            steps = planSteps,
            cancellationToken = token,
            executor = { step, prior ->
                // Reflect the current step in the observable mission BEFORE work.
                mission = mission.copy(
                    currentStep = mission.currentStep.copy(
                        index = planSteps.indexOf(step).coerceAtLeast(0),
                        toolName = step.toolName,
                        actionDescription = step.description,
                        stepVerification = VerificationStatus.NOT_CHECKED
                    )
                )
                publish(mission)

                val ourOutcome = executeStep(step, planSteps.indexOf(step), prior, outputs, userConfirmed, token, mission)
                mission = MissionStateMachine.advanceStep(mission, ourOutcome)
                publish(mission)
                // Translate to the loop's own StepOutcome contract.
                when (ourOutcome) {
                    is MissionStepOutcome.Verified ->
                        LoopStepOutcome.Success(ourOutcome.observation.orEmpty())
                    is MissionStepOutcome.Executed ->
                        LoopStepOutcome.Success(ourOutcome.observation ?: "executed")
                    is MissionStepOutcome.Failure ->
                        LoopStepOutcome.Failure(ourOutcome.error, ourOutcome.recoverable)
                    is MissionStepOutcome.Retrying ->
                        LoopStepOutcome.Success("retry ${ourOutcome.attempt}")
                }
            },
            verifier = { step, success ->
                // The loop's own verifier hook maps to mission VERIFYING.
                mission = MissionStateMachine.transition(mission, MissionState.VERIFYING) ?: mission
                publish(mission)
                success is LoopStepOutcome.Success
            }
        )

        mission = finish(mission, report, token)
        publish(mission)
        return mission
    }

    /**
     * Runs ONE plan step through the appropriate executor and converts the raw
     * result into a [StepOutcome] the AgentLoop understands.
     */
    private suspend fun executeStep(
        step: PlanStep,
        stepIndex: Int,
        prior: Map<Int, String>,
        outputs: MutableMap<Int, String>,
        userConfirmed: Boolean,
        token: CancellationToken,
        mission: Mission
    ): MissionStepOutcome {
        token.checkCancellation()

        val isDeviceTool = DeviceToolExecutor.SUPPORTED_DEVICE_TOOLS.contains(step.toolName)
        val def = com.jarvis.ai.orchestrator.ToolRegistry.getTool(step.toolName)

        // Confirmation gate: a tool requiring confirmation that was not confirmed
        // this turn asks instead of executing.
        if (def?.confirmationRequired == true && !userConfirmed) {
            return MissionStepOutcome.Failure("Confirmation required for ${step.toolName}", recoverable = true)
        }

        return try {
            if (isDeviceTool) {
                val device = deviceToolExecutor
                    ?: return MissionStepOutcome.Failure("Device tools unavailable", recoverable = false)
                when (val r = device.execute(step.toolName, step.parameters)) {
                    is ToolExecutionResult.Verified -> {
                        outputs[stepIndex] = r.observation.orEmpty()
                        MissionStepOutcome.Verified(r.observation)
                    }
                    is ToolExecutionResult.Executed -> {
                        outputs[stepIndex] = r.observation.orEmpty()
                        MissionStepOutcome.Executed(r.observation)
                    }
                    is ToolExecutionResult.Failure ->
                        MissionStepOutcome.Failure(r.error, r.recoverable)
                    is ToolExecutionResult.Unsupported ->
                        MissionStepOutcome.Failure(r.reason, recoverable = false)
                }
            } else {
                val result = when {
                    toolPort != null -> toolPort.execute(step.toolName, step.parameters, userConfirmed)
                    toolExecutor != null -> toolExecutor.executeTool(step.toolName, step.parameters, userConfirmed)
                    else -> return MissionStepOutcome.Failure("Tool subsystem unavailable", recoverable = false)
                }
                when (result) {
                    is ToolResult.Success -> {
                        outputs[stepIndex] = result.message
                        MissionStepOutcome.Verified(result.message)
                    }
                    is ToolResult.Failure ->
                        MissionStepOutcome.Failure(result.error, result.recoverable)
                    is ToolResult.ConfirmationRequired ->
                        MissionStepOutcome.Failure("Confirmation required", recoverable = true)
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            MissionStepOutcome.Failure(e.message ?: e::class.java.simpleName, recoverable = true)
        }
    }

    /** Maps the loop's aggregate report onto a terminal mission state. */
    private fun finish(mission: Mission, report: AgentRunReport, token: CancellationToken): Mission {
        // A user cancellation wins over the report's own success flag.
        if (token.isCancelled || mission.cancellationRequested) {
            return MissionStateMachine.requestCancellation(mission)
        }
        val terminal = if (report.success) MissionState.COMPLETED else MissionState.FAILED
        val updated = MissionStateMachine.transition(mission, terminal) ?: mission
        return when (terminal) {
            MissionState.COMPLETED -> {
                // §7: store a durable pattern ONLY on verified success. Content is
                // the goal text, never tool arguments or screen payloads.
                memory?.rememberFact("mission_pattern", goalOf(mission))
                updated.copy(
                    verification = VerificationStatus.VERIFIED,
                    observation = report.completedSteps.lastOrNull()
                )
            }
            else -> updated.copy(
                verification = VerificationStatus.FAILED,
                lastError = report.stoppedReason ?: "Mission did not complete"
            )
        }
    }

    private fun goalOf(mission: Mission): String = mission.goal.take(200)

    /** Requests the active mission to stop. Propagates to the loop and device layer. */
    fun cancel() {
        currentToken?.cancel()
        _active.value?.let { current ->
            _active.value = MissionStateMachine.requestCancellation(current)
        }
    }

    private fun publish(mission: Mission) {
        _active.value = mission
    }
}
