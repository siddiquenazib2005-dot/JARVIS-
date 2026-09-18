package com.jarvis.ai.util

import java.util.Calendar

/**
 * Responsibility: pure, dependency-free time-of-day greeting.
 *
 * Used by the AURIX home screen header ("Good evening, Name. This is Aurix.").
 * Kept in `util/` with no Android or Compose imports so it is unit-testable
 * without Robolectric, and so the greeting can be previewed deterministically.
 *
 * [TimeOfDay] is the part of the day, in the terms the greeting copy uses.
 */
enum class TimeOfDay {
    MORNING, AFTERNOON, EVENING, NIGHT
}

/**
 * Returns the greeting prefix for [hour] (0-23 on the 24-hour clock), e.g.
 * "Good morning". Hour is taken explicitly rather than read from the clock so a
 * test or a preview can render any time of day.
 */
fun greetingForHour(hour: Int): String = when (timeOfDayForHour(hour)) {
    TimeOfDay.MORNING -> "Good morning"
    TimeOfDay.AFTERNOON -> "Good afternoon"
    TimeOfDay.EVENING -> "Good evening"
    TimeOfDay.NIGHT -> "Still up"
}

/** Maps a 24-hour clock value onto the greeting's part of day. */
fun timeOfDayForHour(hour: Int): TimeOfDay {
    val h = hour.coerceIn(0, 23)
    return when (h) {
        in 5..11 -> TimeOfDay.MORNING
        in 12..16 -> TimeOfDay.AFTERNOON
        in 17..21 -> TimeOfDay.EVENING
        else -> TimeOfDay.NIGHT
    }
}

/** Convenience: the greeting for the current wall-clock hour. */
fun greetingNow(): String = greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))

/**
 * Builds the full home-screen headline. A blank or null name falls back to a
 * warm generic so the header never reads "Good evening, .".
 */
fun homeHeadline(userName: String?, hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String {
    val name = userName?.trim().orEmpty()
    return if (name.isEmpty()) {
        "${greetingForHour(hour)}. This is Aurix."
    } else {
        "${greetingForHour(hour)}, $name. This is Aurix."
    }
}
