package com.jarvis.ai.presence

import java.util.Calendar

/**
 * Time-based greeting engine.
 *
 * [greetingText] is a pure function (unit-testable); [PresenceGate] holds the
 * mutable once-per-period state so greetings fire exactly once per relevant
 * app session / resume period — never on recomposition or screen redraws.
 */
object PresenceEngine {

    enum class GreetingPeriod { MORNING, AFTERNOON, EVENING, NIGHT }

    fun periodFor(hour: Int): GreetingPeriod = when (hour) {
        in 5..11 -> GreetingPeriod.MORNING
        in 12..16 -> GreetingPeriod.AFTERNOON
        in 17..20 -> GreetingPeriod.EVENING
        else -> GreetingPeriod.NIGHT
    }

    fun periodForClock(clockMs: Long): GreetingPeriod =
        periodFor(Calendar.getInstance().apply { timeInMillis = clockMs }.get(Calendar.HOUR_OF_DAY))

    fun greetingText(hour: Int, backendOnline: Boolean, unfinishedTasks: Int = 0): String {
        val salutation = when (periodFor(hour)) {
            GreetingPeriod.MORNING -> "Good morning, sir."
            GreetingPeriod.AFTERNOON -> "Good afternoon, sir."
            GreetingPeriod.EVENING -> "Good evening, sir."
            GreetingPeriod.NIGHT -> "Good night, sir."
        }
        val status = if (backendOnline) {
            "AURIX is online. All primary systems are operational."
        } else {
            "AURIX is online in offline mode. Local reserves remain operational."
        }
        val taskNote = if (unfinishedTasks > 0) {
            " You have $unfinishedTasks unfinished task${if (unfinishedTasks > 1) "s" else ""} from the previous session."
        } else ""
        return "$salutation $status$taskNote"
    }
}

/**
 * Deduplication gate for greetings.
 *
 * A greeting is warranted when:
 *  - no greeting has been issued yet in this app session, OR
 *  - the day-part changed AND at least [minGapMs] elapsed since the last one
 *    (covers long-running sessions crossing morning→afternoon etc.).
 *
 * Recomposition, screen redraws and new chat sessions NEVER trigger a greeting.
 */
class PresenceGate(
    private val minGapMs: Long = DEFAULT_MIN_GAP_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Volatile
    private var lastGreetedAtMs: Long = Long.MIN_VALUE

    @Volatile
    private var lastPeriod: PresenceEngine.GreetingPeriod? = null
    private val lock = Any()

    fun shouldGreet(clockMs: Long = now()): Boolean = synchronized(lock) {
        val period = PresenceEngine.periodForClock(clockMs)
        val firstEver = lastGreetedAtMs == Long.MIN_VALUE
        val due = firstEver ||
            (period != lastPeriod && clockMs - lastGreetedAtMs >= minGapMs)
        if (due) {
            lastGreetedAtMs = clockMs
            lastPeriod = period
        }
        due
    }

    /** Direct introspection for tests/diagnostics without mutating state. */
    fun peek(): Pair<Long, PresenceEngine.GreetingPeriod?> =
        synchronized(lock) { lastGreetedAtMs to lastPeriod }

    companion object {
        const val DEFAULT_MIN_GAP_MS = 4 * 60 * 60 * 1000L // 4 hours
    }
}
