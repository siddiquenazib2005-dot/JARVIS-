package com.jarvis.ai.core.agent

import android.content.Context
import com.jarvis.ai.provider.ProviderRouter
import com.jarvis.ai.provider.LlmRouteRequest
import com.jarvis.ai.provider.Capability
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReEvaluationEngine(
    private val providerRouter: ProviderRouter
) {

    suspend fun reEvaluate(
        failedStep: PlanStep,
        error: String,
        context: Context,
        plan: Plan,
        completedSteps: List<PlanStep>
    ): ReEvaluationResult {
        return withContext(Dispatchers.Default) {
            val completedContext = completedSteps.map { step ->
                "Step ${step.order}: ${step.description} (${step.action}) -> ${step.result ?: "completed"}"
            }.joinToString("\n")

            val prompt = """
                A step in a multi-step plan has failed. Analyze the failure and provide a re-evaluation.
                
                Original Goal: ${plan.goal}
                Failed Step: ${failedStep.description} (${failedStep.action})
                Error: $error
                
                Completed Steps:
                $completedContext
                
                Remaining Steps:
                ${plan.steps.filter { it.order > failedStep.order }.map { "${it.order}. ${it.description} (${it.action})" }.joinToString("\n")}
                
                Provide a JSON response with:
                {
                    "shouldContinue": boolean,
                    "retryStrategy": "RETRY_SAME" | "MODIFY_PARAMS" | "ALTERNATIVE_ACTION" | "SKIP_STEP" | "ABORT",
                    "modifiedParams": { ... } or null,
                    "alternativeAction": "ACTION_NAME" or null,
                    "reason": "explanation"
                }
                
                Guidelines:
                - If the error is transient (network timeout, temporary failure), suggest RETRY_SAME
                - If the error is due to wrong parameters, suggest MODIFY_PARAMS with corrected params
                - If there's an alternative way to achieve the same goal, suggest ALTERNATIVE_ACTION
                - If the step is not critical, suggest SKIP_STEP
                - If the failure makes the whole plan impossible, suggest ABORT
            """.trimIndent()

            val responseText = providerRouter.routeText(
                LlmRouteRequest(
                    capability = Capability.CHAT,
                    history = listOf(Message(sender = Sender.USER, text = prompt)),
                    systemPrompt = "You are an expert re-evaluation engine for autonomous Android automation. Provide concise, actionable re-evaluation in valid JSON only.",
                    stream = false
                )
            ).toString()

            parseReEvaluation(responseText)
        }
    }

    private fun parseReEvaluation(json: String): ReEvaluationResult {
        return try {
            val obj = org.json.JSONObject(json)
            ReEvaluationResult(
                shouldContinue = obj.getBoolean("shouldContinue"),
                retryStrategy = ReEvaluationStrategy.valueOf(obj.getString("retryStrategy")),
                modifiedParams = obj.optJSONObject("modifiedParams")?.let { jsonObj ->
                    jsonObj.keys().asSequence().associate { key ->
                        key to jsonObj.getString(key)
                    }
                },
                alternativeAction = obj.optString("alternativeAction").takeIf { it.isNotBlank() },
                reason = obj.getString("reason")
            )
        } catch (e: Exception) {
            ReEvaluationResult(
                shouldContinue = true,
                retryStrategy = ReEvaluationStrategy.RETRY_SAME,
                reason = "Re-evaluation failed, defaulting to retry: ${e.message}"
            )
        }
    }
}

enum class ReEvaluationStrategy {
    RETRY_SAME,
    MODIFY_PARAMS,
    ALTERNATIVE_ACTION,
    SKIP_STEP,
    ABORT
}

data class ReEvaluationResult(
    val shouldContinue: Boolean,
    val retryStrategy: ReEvaluationStrategy,
    val modifiedParams: Map<String, String>? = null,
    val alternativeAction: String? = null,
    val reason: String
)
