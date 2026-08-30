package com.jarvis.ai.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceEngineTest {

    private val hour8ms = hoursMs(8)   // morning
    private val hour14ms = hoursMs(14) // afternoon
    private val hour21ms = hoursMs(21) // evening

    private fun hoursMs(hour: Int): Long =
        // Fixed reference day: 2026-01-15 (Thursday), local-time components via Calendar.
        java.util.Calendar.getInstance().apply {
            set(2026, 0, 15, hour, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `periods map correctly across the day`() {
        assertEquals(PresenceEngine.GreetingPeriod.MORNING, PresenceEngine.periodFor(5))
        assertEquals(PresenceEngine.GreetingPeriod.MORNING, PresenceEngine.periodFor(11))
        assertEquals(PresenceEngine.GreetingPeriod.AFTERNOON, PresenceEngine.periodFor(12))
        assertEquals(PresenceEngine.GreetingPeriod.AFTERNOON, PresenceEngine.periodFor(16))
        assertEquals(PresenceEngine.GreetingPeriod.EVENING, PresenceEngine.periodFor(17))
        assertEquals(PresenceEngine.GreetingPeriod.EVENING, PresenceEngine.periodFor(20))
        assertEquals(PresenceEngine.GreetingPeriod.NIGHT, PresenceEngine.periodFor(23))
        assertEquals(PresenceEngine.GreetingPeriod.NIGHT, PresenceEngine.periodFor(3))
    }

    @Test
    fun `greeting text follows the day part`() {
        assertTrue(PresenceEngine.greetingText(9, true).startsWith("Good morning, sir."))
        assertTrue(PresenceEngine.greetingText(14, true).startsWith("Good afternoon, sir."))
        assertTrue(PresenceEngine.greetingText(19, true).startsWith("Good evening, sir."))
        assertTrue(PresenceEngine.greetingText(2, true).startsWith("Good night, sir."))
    }

    @Test
    fun `gate greets once per session and never on repeat calls`() {
        val gate = PresenceGate(minGapMs = 4 * 60 * 60 * 1000L, now = { hour8ms })
        assertTrue(gate.shouldGreet(hour8ms))
        assertFalse("recomposition must not re-greet", gate.shouldGreet(hour8ms + 1_000))
        assertFalse(gate.shouldGreet(hour8ms + 60_000))
    }

    @Test
    fun `period change inside min gap does not re-greet`() {
        val morning9 = hoursMs(9)
        val afternoonNoon = hoursMs(12) // day-part changed, but only 3h elapsed
        var clock = morning9
        val gate = PresenceGate(minGapMs = 4 * 60 * 60 * 1000L, now = { clock })
        assertTrue(gate.shouldGreet(clock))
        assertFalse("day-part changed too soon — no greeting", gate.shouldGreet(afternoonNoon))
    }

    @Test
    fun `period change after sufficient gap re-greets`() {
        val gate = PresenceGate(minGapMs = 3 * 60 * 60 * 1000L, now = { hour8ms })
        assertTrue(gate.shouldGreet(hour8ms)) // morning
        assertTrue(gate.shouldGreet(hour14ms + 1)) // afternoon ~6h later → new greeting warranted
    }

    @Test
    fun `offline mode is reflected honestly`() {
        val text = PresenceEngine.greetingText(10, backendOnline = false)
        assertTrue(text.contains("offline mode"))
        assertFalse(text.contains("Tony Stark"))
        assertFalse(text.contains("Stark Industries"))
    }
}
