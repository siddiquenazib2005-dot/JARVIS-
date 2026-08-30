package com.jarvis.ai.core.agent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ActionSequenceExecutor(
    private val executeAction: suspend (String, Map<String, String>, Context) -> ActionResult,
    private val hasAction: (String) -> Boolean
) {

    data class StepExecution(
        val step: PlanStep,
        val resolvedParams: Map<String, String>,
        val primaryResult: ActionResult,
        val fallbackResult: ActionResult? = null
    ) {
        val finalResult: ActionResult
            get() = fallbackResult ?: primaryResult

        val usedFallback: Boolean
            get() = fallbackResult != null
    }

    suspend fun dispatch(
        action: String,
        params: Map<String, String>,
        context: Context
    ): ActionResult = try {
        executeAction(action, params, context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ActionResult.Failure(
            errorMsg = e.localizedMessage ?: "Unknown execution error"
        )
    }

    fun resolveParameters(
        params: Map<String, String>,
        completedResults: Map<String, String>
    ): Map<String, String> = params.mapValues { (_, value) ->
        completedResults.entries
            .sortedByDescending { it.key.length }
            .fold(value) { resolved, (stepId, result) ->
                val doubleReference = "\$\$" + stepId
                val singleReference = "\$" + stepId
                resolved
                    .replace(doubleReference, result)
                    .replace(singleReference, result)
            }
    }

    fun resolveParameters(
        params: Map<String, String>,
        priorSteps: List<PlanStep>
    ): Map<String, String> = resolveParameters(
        params = params,
        completedResults = priorSteps.asSequence()
            .filter { it.status == StepStatus.COMPLETED && it.result != null }
            .associate { it.stepId to it.result!! }
    )

    suspend fun executeStep(
        step: PlanStep,
        completedResults: Map<String, String>,
        context: Context
    ): StepExecution {
        val resolvedParams = resolveParameters(step.params, completedResults)
        val primaryResult = dispatch(step.action, resolvedParams, context)
        val fallbackResult = if (shouldAttemptFallback(primaryResult, step)) {
            dispatch(step.fallback, resolvedParams, context)
        } else {
            null
        }
        return StepExecution(
            step = step,
            resolvedParams = resolvedParams,
            primaryResult = primaryResult,
            fallbackResult = fallbackResult
        )
    }

    fun shouldAttemptFallback(result: ActionResult, step: PlanStep): Boolean =
        !result.success &&
            step.fallback.isNotBlank() &&
            hasAction(step.fallback) &&
            result !is ActionResult.UnknownAction &&
            result !is ActionResult.PendingUserAction &&
            result !is ActionResult.UserActionRequired

    suspend fun execute(
        steps: List<PlanStep>,
        context: Context
    ): ActionResult {
        if (steps.isEmpty()) {
            return ActionResult.Failure("Plan contains no executable steps.")
        }

        val orderedSteps = steps.sortedWith(compareBy<PlanStep> { it.order })
        val completedResults = linkedMapOf<String, String>()

        for ((index, step) in orderedSteps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val execution = executeStep(step, completedResults, context)
            val result = execution.finalResult
            if (!result.success) {
                val actionName = step.action.ifBlank { "unknown action" }
                val detail = result.error
                    ?.takeIf { it.isNotBlank() }
                    ?: "Action execution failed."
                return ActionResult.Failure(
                    "Plan stopped at step \${index + 1} (\$actionName): \$detail"
                )
            }

            completedResults[step.stepId] = result.data ?: "Completed successfully."
        }

        return ActionResult.Success(
            dataMap = mapOf(
                "message" to "Plan completed successfully (\${orderedSteps.size} steps)."
            )
        )
    }
}