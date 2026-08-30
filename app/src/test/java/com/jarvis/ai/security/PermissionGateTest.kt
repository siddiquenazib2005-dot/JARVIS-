package com.jarvis.ai.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGateTest {

    @Test
    fun `read only tools auto-allow`() {
        val d = PermissionGate.decide("calculate")
        assertTrue(d.allowedWithoutConfirmation)
        assertFalse(d.denied)
        assertEquals(PermissionLevel.READ_ONLY, d.level)
    }

    @Test
    fun `memory write is low risk auto`() {
        val d = PermissionGate.decide("memory_write")
        assertTrue(d.allowedWithoutConfirmation)
        assertEquals(PermissionLevel.LOW_RISK, d.level)
    }

    @Test
    fun `open app requires confirmation first`() {
        val blocked = PermissionGate.decide("open_app")
        assertTrue(blocked.requiresConfirmation)
        assertFalse(blocked.allowedWithoutConfirmation)

        val allowed = PermissionGate.decide("open_app", userConfirmedThisTurn = true)
        assertTrue(allowed.allowedWithoutConfirmation)
        assertFalse(allowed.denied)
    }

    @Test
    fun `high risk is denied without explicit confirmation`() {
        val blocked = PermissionGate.decide("shell")
        assertTrue(blocked.denied)
        assertTrue(blocked.requiresConfirmation)

        val allowed = PermissionGate.decide("shell", userConfirmedThisTurn = true)
        assertFalse(allowed.denied)
    }

    @Test
    fun `unknown tool defaults to confirm required`() {
        val d = PermissionGate.decide("mystery_tool")
        assertTrue(d.requiresConfirmation)
        assertEquals(PermissionLevel.CONFIRM_REQUIRED, d.level)
    }

    @Test
    fun `audit log records redacted entries`() {
        AuditLog.clear()
        AuditLog.record(
            "finance_quote",
            PermissionGate.decide("finance_quote"),
            detail = "symbol=AAPL key=sk-or-v1-secret123"
        )
        val last = AuditLog.recent(1).first()
        assertTrue(last.contains("finance_quote"))
        assertFalse(last.contains("sk-or-v1"))
        assertTrue(last.contains("***REDACTED***") || !last.contains("secret123"))
    }
}
