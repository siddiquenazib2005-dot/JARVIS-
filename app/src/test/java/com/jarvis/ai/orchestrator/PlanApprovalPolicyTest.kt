package com.jarvis.ai.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlanApprovalPolicyTest {
    @Test
    fun safeStepsDoNotRequireApproval() {
        val steps = listOf(
            PlanStep("calculate", mapOf("expression" to "1+1"), "calculate"),
            PlanStep("open_app", mapOf("appName" to "Maps"), "open app")
        )

        assertNull(PlanApprovalPolicy.requiredConfirmation(steps, userConfirmedThisTurn = false))
    }

    @Test
    fun sensitiveStepIsRequestedBeforeAnyMissionStepRuns() {
        val steps = listOf(
            PlanStep("open_app", mapOf("appName" to "WhatsApp"), "open app"),
            PlanStep("send_whatsapp", mapOf("contact" to "Rahul", "message" to "Hi"), "send")
        )

        val request = PlanApprovalPolicy.requiredConfirmation(steps, userConfirmedThisTurn = false)

        assertNotNull(request)
        assertEquals(1, request?.stepIndex)
        assertEquals("send_whatsapp", request?.toolName)
    }

    @Test
    fun currentTurnConfirmationApprovesThePlan() {
        val steps = listOf(
            PlanStep("memory_wipe_all", emptyMap(), "clear memory")
        )

        assertNull(PlanApprovalPolicy.requiredConfirmation(steps, userConfirmedThisTurn = true))
    }
}
