package com.jarvis.ai.core.agent

import kotlinx.serialization.Serializable

@Serializable
data class Plan(
    val planId: String,
    val goal: String,
    val estimatedDuration: String,
    val estimatedSteps: Int,
    val steps: List<PlanStep>,
    val status: PlanStatus = PlanStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)

enum class PlanStatus {
    PROPOSED, PENDING, RUNNING, COMPLETED, FAILED, PAUSED, CANCELLED
}

@Serializable
data class PlanStep(
    val stepId: String,
    val order: Int,
    val description: String,
    val action: String,
    val params: Map<String, String> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val canParallelize: Boolean = false,
    val fallback: String = "",
    var status: StepStatus = StepStatus.PENDING,
    var result: String? = null,
    var error: String? = null
)

enum class StepStatus {
    PENDING, RUNNING, COMPLETED, FAILED
}
