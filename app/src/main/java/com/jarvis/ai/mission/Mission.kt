package com.jarvis.ai.mission

import com.jarvis.ai.accessibility.VerificationStatus
import java.util.UUID

/**
 * Phase-2 mission state machine.
 *
 * Every AURIX task that can take more than one step is modelled as a [Mission]:
 * a goal, a plan, a current position in that plan, and an explicit lifecycle.
 * This replaces scattered boolean flags (isRunning/isDone/isFailed) with one
 * source of truth that the UI, voice layer and diagnostics all observe.
 *
 * Transitions are funnelled through [MissionStateMachine.transition] so they can
 * be unit-tested and so illegal jumps are rejected instead of silently allowed.
 */
enum class MissionState {
    CREATED,
    UNDERSTANDING,
    PLANNING,
    WAITING_CONFIRMATION,
    EXECUTING,
    OBSERVING,
    VERIFYING,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** Terminal states never re-enter the active pipeline. */
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED

    /** Active states keep device work or model work running. */
    val isActive: Boolean get() = !isTerminal
}

/**
 * What a step is currently doing. Kept distinct from [MissionState] because a
 * mission in EXECUTING still needs to say WHICH step and what its verification did.
 */
data class StepProgress(
    val index: Int = 0,
    val totalSteps: Int = 0,
    val toolName: String? = null,
    val actionDescription: String? = null,
    val stepVerification: VerificationStatus = VerificationStatus.NOT_CHECKED,
    val stepRetryCount: Int = 0
) {
    val label: String
        get() = buildString {
            append("Step ${index + 1}")
            if (totalSteps > 0) append(" of $totalSteps")
            actionDescription?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
        }
}

/**
 * Immutable snapshot of a mission. All mutation goes through copy() in the state
 * machine, so observers can never see a half-updated mission.
 */
data class Mission(
    val missionId: String = UUID.randomUUID().toString(),
    val goal: String,
    val plan: List<String> = emptyList(),
    val state: MissionState = MissionState.CREATED,
    val currentStep: StepProgress = StepProgress(),
    val observation: String? = null,
    val verification: VerificationStatus = VerificationStatus.NOT_CHECKED,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val cancellationRequested: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val memoryContext: String? = null
) {
    val isTerminal: Boolean get() = state.isTerminal
    val progressFraction: Float
        get() = if (currentStep.totalSteps == 0) 0f
        else currentStep.index.coerceIn(0, currentStep.totalSteps) / currentStep.totalSteps.toFloat()
}

/**
 * The ONLY way to move a mission between states.
 *
 * Each transition validates its precondition. A mission that has already
 * terminated refuses every further move — this is what stops a cancelled or
 * failed mission from quietly resuming work.
 */
object MissionStateMachine {

    /** Applies a state change, returning the updated mission or null if illegal. */
    fun transition(mission: Mission, next: MissionState): Mission? {
        if (!isLegal(mission.state, next)) return null
        val now = System.currentTimeMillis()
        return mission.copy(
            state = next,
            updatedAt = now,
            completedAt = if (next.isTerminal) now else mission.completedAt
        )
    }

    /**
     * Legal moves. Once terminal the only accepted transition is a no-op back to
     * the same state (idempotent cancel), which [transition] handles via equality.
     */
    fun isLegal(from: MissionState, to: MissionState): Boolean {
        if (from == to) return true
        return when (from) {
            MissionState.CREATED -> to in setOf(
                MissionState.UNDERSTANDING, MissionState.CANCELLED, MissionState.FAILED
            )
            MissionState.UNDERSTANDING -> to in setOf(
                MissionState.PLANNING, MissionState.WAITING_CONFIRMATION,
                MissionState.FAILED, MissionState.CANCELLED
            )
            MissionState.PLANNING -> to in setOf(
                MissionState.WAITING_CONFIRMATION, MissionState.EXECUTING,
                MissionState.FAILED, MissionState.CANCELLED
            )
            MissionState.WAITING_CONFIRMATION -> to in setOf(
                MissionState.EXECUTING, MissionState.CANCELLED, MissionState.FAILED
            )
            MissionState.EXECUTING -> to in setOf(
                MissionState.OBSERVING, MissionState.RECOVERING,
                MissionState.VERIFYING, MissionState.FAILED, MissionState.CANCELLED
            )
            MissionState.OBSERVING -> to in setOf(
                MissionState.VERIFYING, MissionState.RECOVERING,
                MissionState.EXECUTING, MissionState.FAILED, MissionState.CANCELLED
            )
            MissionState.VERIFYING -> to in setOf(
                MissionState.EXECUTING, MissionState.RECOVERING,
                MissionState.COMPLETED, MissionState.FAILED, MissionState.CANCELLED
            )
            MissionState.RECOVERING -> to in setOf(
                MissionState.EXECUTING, MissionState.PLANNING,
                MissionState.FAILED, MissionState.CANCELLED
            )
            // Terminal states are absorbing.
            MissionState.COMPLETED, MissionState.FAILED, MissionState.CANCELLED -> false
        }
    }

    /** Records that the user asked to stop. Safe to call repeatedly. */
    fun requestCancellation(mission: Mission): Mission =
        mission.copy(
            cancellationRequested = true,
            state = if (mission.state.isTerminal) mission.state else MissionState.CANCELLED,
            updatedAt = System.currentTimeMillis(),
            completedAt = if (mission.state.isTerminal) mission.completedAt else System.currentTimeMillis()
        )

    /** Advances step bookkeeping when a step finishes. */
    fun advanceStep(mission: Mission, outcome: StepOutcome): Mission {
        val total = mission.currentStep.totalSteps
        val nextIndex = mission.currentStep.index + 1
        return mission.copy(
            currentStep = mission.currentStep.copy(
                index = if (total > 0) nextIndex.coerceIn(0, total - 1) else nextIndex,
                stepVerification = when (outcome) {
                    is StepOutcome.Verified -> VerificationStatus.VERIFIED
                    is StepOutcome.Failed -> VerificationStatus.FAILED
                    else -> VerificationStatus.NOT_CHECKED
                },
                stepRetryCount = if (outcome is StepOutcome.Retrying) mission.currentStep.stepRetryCount + 1
                    else mission.currentStep.stepRetryCount
            ),
            updatedAt = System.currentTimeMillis()
        )
    }

    /** Marks the mission failed with the given reason. */
    fun fail(mission: Mission, reason: String): Mission? =
        transition(mission, MissionState.FAILED)?.copy(lastError = reason)
}

/** Outcome of one step, as reported back to the mission. */
sealed class StepOutcome {
    /** Tool ran; verification pending. */
    data class Executed(val observation: String? = null) : StepOutcome()
    /** Tool ran AND its expected effect was verified. */
    data class Verified(val observation: String? = null) : StepOutcome()
    /** Tool failed or verification failed; a bounded retry is allowed. */
    data class Failed(val error: String, val recoverable: Boolean) : StepOutcome()
    /** A retry is being attempted. */
    data class Retrying(val attempt: Int) : StepOutcome()
}
