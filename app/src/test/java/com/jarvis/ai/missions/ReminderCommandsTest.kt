package com.jarvis.ai.missions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Pure-JVM tests for the reminder parser and the trigger summary/labels.
 * No Android framework code is exercised here (ReminderCommands is Context-free
 * by design, MissionTrigger.label uses only String.format).
 */
class ReminderCommandsTest {

    private fun calAt(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `non reminder input returns null`() {
        assertNull(ReminderCommands.parse("open whatsapp"))
        assertNull(ReminderCommands.parse("what's my battery level"))
        assertNull(ReminderCommands.parse("remind me")) // no time, no body
    }

    @Test
    fun `relative minutes parse`() {
        val p = ReminderCommands.parse("remind me in 20 minutes to stretch")!!
        assertEquals("to stretch", p.text)
        val now = 1_000_000L
        assertEquals(now + 20 * 60_000L, p.fireAt(now))
        assertTrue(p.summary.contains("20 minute"))
    }

    @Test
    fun `relative hours and seconds parse`() {
        val now = now0()
        val h = ReminderCommands.parse("remind me after 2 hours to check the oven")!!
        assertEquals(now + 2 * 3_600_000L, h.fireAt(now))

        val s = ReminderCommands.parse("remind me in 30 seconds")!!
        assertEquals(now + 30_000L, s.fireAt(now))
        assertTrue(s.text.isNotBlank())
    }

    @Test
    fun `explicit clock time with meridiem`() {
        val p = ReminderCommands.parse("remind me to take pills at 9 pm")!!
        assertEquals(21, hourOf(p.fireAt(now0())))
        assertEquals(0, minuteOf(p.fireAt(now0())))
        // TRIGGER strips "remind me to ", so the connective "to" is gone too.
        assertEquals("take pills", p.text)
    }

    @Test
    fun `clock time 930 pm via dotted clock`() {
        val p = ReminderCommands.parse("remind me to call HR at 9.30 pm")!!
        assertEquals(21, hourOf(p.fireAt(now0())))
        assertEquals(30, minuteOf(p.fireAt(now0())))
    }

    @Test
    fun `24h clock time`() {
        val p = ReminderCommands.parse("remind me to log off at 21:30")!!
        assertEquals(21, hourOf(p.fireAt(now0())))
        assertEquals(30, minuteOf(p.fireAt(now0())))
    }

    @Test
    fun `tomorrow shifts by one day`() {
        val now = now0()
        val p = ReminderCommands.parse("remind me to call HR tomorrow at 9 am")!!
        val fire = p.fireAt(now)
        assertEquals(9, hourOf(fire))
        // Next occurrence of 09:00 strictly after now: between 1 minute and ~25h out.
        assertTrue("fire should be in the future", fire > now)
        assertTrue("fire should be within ~25h", fire - now < 25 * 3_600_000L + 60_000L)
        assertTrue(p.summary.startsWith("tomorrow"))
    }

    @Test
    fun `past time today rolls to tomorrow`() {
        // 23:58 "now" → "at 9 am" must land tomorrow 09:00
        val late = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 58)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        val p = ReminderCommands.parse("remind me to wake up at 9 am")!!
        assertEquals(9, hourOf(p.fireAt(late)))
    }

    @Test
    fun `hinglish kal 9 baje`() {
        val p = ReminderCommands.parse("remind me kal 9 baje call HR")!!
        assertEquals(9, hourOf(p.fireAt(now0())))
        assertNotNull(p.text)
        assertTrue(p.text.contains("call"))
    }

    @Test
    fun `hinglish raat 9 baje is 21 hundred`() {
        val p = ReminderCommands.parse("raat 9 baje remind me to take pills")!!
        assertEquals(21, hourOf(p.fireAt(now0())))
    }

    @Test
    fun `tonight lifts bare hour to evening`() {
        val p = ReminderCommands.parse("remind me tonight at 9 to wind down")!!
        assertEquals(21, hourOf(p.fireAt(now0())))
    }

    @Test
    fun `trigger labels cover all types`() {
        assertEquals("Manual", MissionTrigger().label)
        assertEquals("Daily at 07:30", MissionTrigger(MissionTriggerType.DAILY, 7, 30).label)
        assertEquals("Once at 21:05", MissionTrigger(MissionTriggerType.ONE_SHOT, 21, 5).label)
    }

    @Test
    fun `invalid clock times are rejected`() {
        assertNull(ReminderCommands.parse("remind me at 25:00"))
        assertNull(ReminderCommands.parse("remind me at 13 pm"))
    }

    @Test
    fun `bare at hour works without meridiem`() {
        val p = ReminderCommands.parse("remind me to stretch at 7")!!
        assertEquals(7, hourOf(p.fireAt(now0())))
        assertEquals(0, minuteOf(p.fireAt(now0())))
        assertEquals("stretch", p.text)
    }

    @Test
    fun `midnight and noon meridiem edges`() {
        assertEquals(0, hourOf(ReminderCommands.parse("remind me at 12 am")!!.fireAt(now0())))
        assertEquals(12, hourOf(ReminderCommands.parse("remind me at 12 pm")!!.fireAt(now0())))
    }

    // ---- helpers ----

    private fun now0(): Long = System.currentTimeMillis()

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    private fun minuteOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.MINUTE)
}
