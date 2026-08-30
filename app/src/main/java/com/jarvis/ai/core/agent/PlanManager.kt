package com.jarvis.ai.core.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class PlanManager(
    private val context: Context,
    private val workingMemory: WorkingMemory
) {
    private val _currentPlan = MutableStateFlow<Plan?>(null)
    val currentPlan: StateFlow<Plan?> = _currentPlan.asStateFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default)

    suspend fun startNewPlan(
        plan: Plan,
        context: Context,
        initialStatus: PlanStatus = PlanStatus.PENDING
    ) {
        mutex.withLock {
            val initialPlan = plan.copy(status = initialStatus)
            _currentPlan.value = initialPlan
            workingMemory.activePlan = initialPlan
            saveCurrentPlan()
        }
    }

    suspend fun updateStepStatus(stepId: String, status: StepStatus, result: String? = null, error: String? = null) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) {
                    step.copy(status = status, result = result, error = error)
                } else {
                    step
                }
            }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlan()
        }
    }

    suspend fun updatePlanStatus(status: PlanStatus) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val updatedPlan = plan.copy(status = status)
            _currentPlan.value = updatedPlan
            if (status == PlanStatus.COMPLETED || status == PlanStatus.FAILED) {
                workingMemory.activePlan = null
            } else {
                workingMemory.activePlan = updatedPlan
            }
            saveCurrentPlan()
        }
    }

    suspend fun loadPlan(planId: String): Boolean {
        // In a real implementation, this would load from a repository
        return false
    }

    suspend fun saveCurrentPlan() {
        // In a real implementation, this would save to a repository
    }

    fun clearPlan() {
        scope.launch {
            _currentPlan.value = null
        }
    }

    suspend fun cancelPlan(expectedPlanId: String? = null) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            if (plan.status.isTerminal()) return@withLock
            if (expectedPlanId != null && plan.planId != expectedPlanId) return@withLock
            val updatedPlan = plan.copy(status = PlanStatus.CANCELLED)
            _currentPlan.value = updatedPlan
            saveCurrentPlan()
        }
    }

    suspend fun updateStepDescription(stepId: String, description: String) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) step.copy(description = description) else step
            }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
        }
    }

    suspend fun updateStepParams(stepId: String, params: Map<String, String>) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) step.copy(params = params) else step
            }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
        }
    }

    suspend fun updateStep(stepId: String, description: String, params: Map<String, String>) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) step.copy(description = description, params = params) else step
            }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
        }
    }

    suspend fun removeStep(stepId: String) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            val updatedSteps = plan.steps.filterNot { it.stepId == stepId }
            _currentPlan.value = plan.copy(steps = updatedSteps)
        }
    }

    suspend fun getStepSnapshot(stepId: String): PlanStep? {
        return _currentPlan.value?.steps?.find { it.stepId == stepId }
    }

    fun getActiveStep(): PlanStep? {
        val plan = _currentPlan.value ?: return null
        if (plan.status != PlanStatus.RUNNING) return null
        return plan.steps.firstOrNull { step ->
            step.status == StepStatus.PENDING && hasDependenciesMet(step, plan.steps)
        }
    }

    private fun PlanStatus.isTerminal(): Boolean =
        this == PlanStatus.COMPLETED || this == PlanStatus.FAILED || this == PlanStatus.CANCELLED

    private fun hasDependenciesMet(step: PlanStep, allSteps: List<PlanStep>): Boolean {
        if (step.dependsOn.isEmpty()) return true
        return step.dependsOn.all { depId ->
            val depStep = allSteps.find { it.stepId == depId }
            depStep != null && (depStep.status == StepStatus.COMPLETED || depStep.status == StepStatus.FAILED)
        }
    }
}