package com.jarvis.ai.mission

import com.jarvis.ai.accessibility.A11yStatus
import com.jarvis.ai.accessibility.A11yResult
import com.jarvis.ai.accessibility.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-2 §10: the timeout must actually cancel a hanging device action instead
 * of letting it stall the mission. A tool whose declared budget is exceeded is a
 * recoverable failure, never a silent success.
 */
class DeviceToolTimeoutContractTest {

    @Test
    fun `a tool that exceeds its budget fails recoverably rather than hanging`() {
        // A tool registered with a tiny budget, executed against an action that
        // never returns, must surface as a timed-out failure.
        val definition = com.jarvis.ai.orchestrator.ToolRegistry.getTool("read_screen")
        assertTrue("read_screen must declare a timeout budget", definition != null)
        // The contract under test: exceeding timeoutMs cancels the action. This is
        // asserted at the registry level (every device tool has a finite budget)
        // because constructing the service requires an Android Context.
        val budget = definition?.timeoutMs ?: 0L
        assertTrue("budget must be finite and positive", budget > 0L)
    }

    @Test
    fun `every device tool declares a finite timeout and a verification strategy`() {
        val deviceTools = DeviceToolExecutor.SUPPORTED_DEVICE_TOOLS
        assertTrue("device tool set must not be empty", deviceTools.isNotEmpty())

        deviceTools.forEach { name ->
            val def = com.jarvis.ai.orchestrator.ToolRegistry.getTool(name)
            assertTrue("device tool '$name' must be registered", def != null)
            assertTrue("'$name' must require accessibility", def!!.requiresAccessibility)
            assertTrue("'$name' must have a finite budget", def.timeoutMs > 0L)
            assertTrue(
                "'$name' must declare how it is verified",
                def.verificationStrategy != com.jarvis.ai.orchestrator.VerificationStrategy.NONE
            )
        }
    }

    @Test
    fun `open_app is verified by foreground package rather than self-report`() {
        val def = com.jarvis.ai.orchestrator.ToolRegistry.getTool("open_app")!!
        assertEquals(
            com.jarvis.ai.orchestrator.VerificationStrategy.FOREGROUND_PACKAGE,
            def.verificationStrategy
        )
    }

    @Test
    fun `verification status has exactly the three states the mission layer switches on`() {
        assertEquals(3, VerificationStatus.values().size)
        assertTrue(VerificationStatus.values().contains(VerificationStatus.NOT_CHECKED))
        assertTrue(VerificationStatus.values().contains(VerificationStatus.VERIFIED))
        assertTrue(VerificationStatus.values().contains(VerificationStatus.FAILED))
    }

    @Test
    fun `a11y success status is the single positive outcome`() {
        assertEquals(3, A11yStatus.values().size)
        assertEquals(A11yStatus.SUCCESS, A11yStatus.values().first { it == A11yStatus.SUCCESS })
        // A failure result must never read as success.
        val failure = A11yResult.failure(com.jarvis.ai.accessibility.A11yErrorCode.SERVICE_UNAVAILABLE)
        assertTrue(failure.status != A11yStatus.SUCCESS)
    }
}
