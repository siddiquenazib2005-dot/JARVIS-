package com.jarvis.ai.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only tests for the framework-agnostic accessibility logic. */
class AccessibilityLogicTest {

    @Test fun textMatcher_exactAndCaseInsensitive() {
        assertTrue(TextMatcher.contains("whatsapp", "WhatsApp"))
        assertTrue(TextMatcher.contains("Settings", "settings"))
        assertTrue(TextMatcher.contains("open", "Open App"))
    }

    @Test fun textMatcher_fuzzy() {
        assertTrue(TextMatcher.matches("watsapp", "WhatsApp", 0.6))
        assertFalse(TextMatcher.matches("completelydifferent", "WhatsApp", 0.6))
    }

    @Test fun textMatcher_scoreBounds() {
        assertEquals(1.0, TextMatcher.fuzzyScore("hello", "hello"), 0.0001)
        val partial = TextMatcher.fuzzyScore("abc", "xyz")
        assertTrue(partial >= 0.0 && partial <= 1.0)
    }

    @Test fun risk_defaults() {
        assertEquals(RiskLevel.LOW, RiskClassifier.classify("tap"))
        assertEquals(RiskLevel.MEDIUM, RiskClassifier.classify("type"))
        assertFalse(RiskClassifier.requiresConfirmation(RiskLevel.LOW))
    }

    @Test fun result_builders() {
        val ok = A11yResult.success(action = "tap", target = "x")
        assertTrue(ok.isSuccess)
        assertEquals(A11yStatus.SUCCESS, ok.status)

        val fail = A11yResult.failure(
            A11yErrorCode.NODE_NOT_FOUND, action = "tap", target = "x", message = "missing"
        )
        assertTrue(fail.isFailure)
        assertEquals(A11yErrorCode.NODE_NOT_FOUND, fail.errorCode)
        assertEquals("missing", fail.message)
    }

    @Test fun newErrorCodes_exist() {
        assertTrue(A11yErrorCode.ACCESSIBILITY_DISABLED.name.startsWith("ACCESSIBILITY"))
        assertTrue(A11yErrorCode.USER_ACTION_REQUIRED.name.startsWith("USER_ACTION"))
    }

    @Test fun result_needsConfirmation() {
        val pending = A11yResult.needsConfirmation(action = "type", message = "Confirm?")
        assertTrue(pending.needsConfirmation)
    }
}
