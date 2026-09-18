package com.jarvis.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responsibility: verify the home-screen greeting behaves for every hour of the
 * day, including the edge hours and a blank user name.
 */
class TimeOfDayGreetingTest {

    @Test
    fun `morning hours greet with Good morning`() {
        for (h in 5..11) assertEquals("Good morning", greetingForHour(h))
    }

    @Test
    fun `afternoon hours greet with Good afternoon`() {
        for (h in 12..16) assertEquals("Good afternoon", greetingForHour(h))
    }

    @Test
    fun `evening hours greet with Good evening`() {
        for (h in 17..21) assertEquals("Good evening", greetingForHour(h))
    }

    @Test
    fun `late night uses Still up rather than Good night`() {
        for (h in listOf(22, 23, 0, 1, 2, 3, 4)) assertEquals("Still up", greetingForHour(h))
    }

    @Test
    fun `hours out of range are coerced, not crashed on`() {
        assertEquals(greetingForHour(0), greetingForHour(-5))
        assertEquals(greetingForHour(23), greetingForHour(99))
    }

    @Test
    fun `headline includes the name when present`() {
        val headline = homeHeadline("Hunter", hour = 18)
        assertEquals("Good evening, Hunter. This is Aurix.", headline)
    }

    @Test
    fun `headline degrades gracefully without a name`() {
        // Must never render "Good evening, ."
        val headline = homeHeadline("", hour = 9)
        assertEquals("Good morning. This is Aurix.", headline)
        assertFalse(headline.contains(", ."))
    }

    @Test
    fun `headline trims whitespace in the stored name`() {
        assertEquals(
            "Good afternoon, Nazib. This is Aurix.",
            homeHeadline("  Nazib ", hour = 15)
        )
    }

    @Test
    fun `time of day classification matches the greeting boundaries`() {
        assertEquals(TimeOfDay.MORNING, timeOfDayForHour(8))
        assertEquals(TimeOfDay.AFTERNOON, timeOfDayForHour(13))
        assertEquals(TimeOfDay.EVENING, timeOfDayForHour(19))
        assertEquals(TimeOfDay.NIGHT, timeOfDayForHour(23))
    }

    @Test
    fun `greetingNow is one of the four known prefixes`() {
        assertTrue(
            greetingNow() in setOf("Good morning", "Good afternoon", "Good evening", "Still up")
        )
    }
}
