package com.jarvis.ai.proactive

import com.jarvis.ai.system.SystemSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveEngineTest {

    private var clock = 1_000_000L
    private val engine = ProactiveEngine(
        now = { clock },
        minIntervalMs = 10 * 60 * 1000L,
        criticalBatteryPercent = 15,
        lowStorageFreeMb = 500
    )

    private fun snap(
        battery: Int? = 80,
        charging: Boolean? = false,
        online: Boolean? = true,
        lowStorage: Boolean? = false
    ) = SystemSnapshot(
        batteryPercent = battery, charging = charging, online = online,
        lowStorage = lowStorage, memoryPressurePercent = 40
    )

    @Test
    fun `due reminder fires exactly once`() {
        engine.addReminder("standup meeting", dueAtMs = clock + 1_000)

        assertNull("not yet due", engine.evaluate(snap(), clock))
        clock += 2_000
        val fired = engine.evaluate(snap(), clock)
        assertNotNull(fired)
        assertTrue(fired!!.contains("standup meeting"))
        // Fired reminders do not repeat.
        clock += 20 * 60 * 1000L
        assertNull(engine.evaluate(snap(), clock))
    }

    @Test
    fun `critical battery alerts once per episode then latches`() {
        clock += 30 * 60 * 1000L
        val first = engine.evaluate(snap(battery = 10, charging = false), clock)
        assertNotNull(first)
        assertTrue(first!!.contains("10%"))

        // Same episode: no repeat.
        assertNull(engine.evaluate(snap(battery = 9, charging = false), clock + 20 * 60 * 1000L))

        // Charging clears the episode; a later critical drop may warn again.
        engine.acknowledgeConditions(snap(charging = true, battery = 90))
        val again = engine.evaluate(snap(battery = 12, charging = false), clock + 40 * 60 * 1000L)
        assertNotNull(again)
    }

    @Test
    fun `min anti-spam interval is enforced across triggers`() {
        clock += 30 * 60 * 1000L
        assertNotNull(engine.evaluate(snap(battery = 10), clock))

        // Network drops immediately after — but inside the quiet window.
        assertNull(
            "quiet window blocks new proactive message",
            engine.evaluate(snap(online = false), clock + 60_000)
        )
    }

    @Test
    fun `network loss fires only on transition to offline`() {
        clock += 30 * 60 * 1000L
        // Establish baseline (online).
        assertNull(engine.evaluate(snap(online = true), clock))

        // Drop → alert (outside quiet window).
        clock += 11 * 60 * 1000L
        val alert = engine.evaluate(snap(online = false), clock)
        assertNotNull(alert)
        assertTrue(alert!!.contains("network"))

        // Still offline → no repeat.
        clock += 20 * 60 * 1000L
        assertNull(engine.evaluate(snap(online = false), clock))
    }

    @Test
    fun `healthy system produces no proactive noise`() {
        clock += 30 * 60 * 1000L
        assertNull(engine.evaluate(snap(), clock))
        clock += 30 * 60 * 1000L
        assertNull(engine.evaluate(snap(), clock))
    }

    @Test
    fun `reminder removal works`() {
        val id = engine.addReminder("temp reminder", dueAtMs = clock + 500_000)
        assertTrue(engine.removeReminder(id))
        assertFalse(engine.pendingReminders().any { it.id == id })
        assertEquals(0, engine.pendingReminders().size)
    }
}
