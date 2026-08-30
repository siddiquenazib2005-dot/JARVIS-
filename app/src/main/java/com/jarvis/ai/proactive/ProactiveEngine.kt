package com.jarvis.ai.proactive

import com.jarvis.ai.core.EventBus
import com.jarvis.ai.core.EventType
import com.jarvis.ai.system.SystemSnapshot

/**
 * Non-spammy proactive intelligence.
 *
 * Meaningful triggers only:
 *  - critical battery (unplugged, < threshold)
 *  - storage critically low
 *  - network lost (edge-triggered, not level-triggered)
 *  - due reminders
 *
 * Anti-spam guarantees:
 *  - at most ONE proactive message per evaluation
 *  - a minimum interval between any two proactive messages
 *  - each condition fires once per episode until it clears and re-appears
 *
 * Pure logic lives in [evaluate] (unit-testable with injected clock/snapshot).
 */
class ProactiveEngine(
    private val now: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val criticalBatteryPercent: Int = 15,
    private val lowStorageFreeMb: Long = 500
) {

    /** A scheduled reminder created by the user or orchestrator. */
    data class Reminder(
        val id: String,
        val text: String,
        val dueAtMs: Long,
        val fired: Boolean = false
    )

    private val lock = Any()
    private var lastMessageAtMs: Long = Long.MIN_VALUE
    private var lastNetworkOnline: Boolean? = null
    private var batteryAlertedThisEpisode = false
    private var storageAlertedThisEpisode = false
    private val reminders = linkedMapOf<String, Reminder>()

    /** Registers a reminder; returns its id. */
    fun addReminder(text: String, dueAtMs: Long, id: String = "r${now()}"): String = synchronized(lock) {
        val key = id.ifBlank { "r${now()}" }
        reminders[key] = Reminder(key, text, dueAtMs)
        key
    }

    fun removeReminder(id: String): Boolean = synchronized(lock) { reminders.remove(id) != null }

    fun pendingReminders(): List<Reminder> = synchronized(lock) {
        reminders.values.filter { !it.fired }.sortedBy { it.dueAtMs }
    }

    /**
     * Evaluates current state; returns the single most important proactive
     * message (or null). Priority: due reminder > battery > storage > network.
     */
    fun evaluate(snapshot: SystemSnapshot?, clockMs: Long = now()): String? = synchronized(lock) {
        if (lastMessageAtMs != Long.MIN_VALUE && clockMs - lastMessageAtMs < minIntervalMs) return null

        // 1. Due reminders (oldest first)
        val due = reminders.entries.firstOrNull { !it.value.fired && clockMs >= it.value.dueAtMs }
        if (due != null) {
            reminders[due.key] = due.value.copy(fired = true)
            markSent(clockMs)
            EventBus.publish(EventType.SESSION_STARTED, "reminder:${due.key}")
            return "Sir, a reminder is due: ${due.value.text}"
        }

        // 2. Critical battery — once per discharge episode
        val criticalBattery = snapshot?.batteryPercent?.let { it <= criticalBatteryPercent && it > 0 } == true &&
            snapshot.charging == false
        if (criticalBattery && !batteryAlertedThisEpisode) {
            batteryAlertedThisEpisode = true
            markSent(clockMs)
            return "Power reserves are at ${snapshot!!.batteryPercent}%, sir. Consider charging the device."
        }

        // 3. Critical storage — once per episode
        val lowStorage = snapshot?.lowStorage == true
        if (lowStorage && !storageAlertedThisEpisode) {
            storageAlertedThisEpisode = true
            markSent(clockMs)
            return "Device storage is running critically low, sir."
        }

        // 4. Network lost — edge triggered on transition to offline
        val online = snapshot?.online
        if (online != null) {
            val wasOnline = lastNetworkOnline
            lastNetworkOnline = online
            if (wasOnline == true && !online) {
                markSent(clockMs)
                return "We have lost network connectivity, sir. Operating on local reserves."
            }
        }

        null
    }

    /** Resets per-episode latches when conditions clear. */
    fun acknowledgeConditions(snapshot: SystemSnapshot?) {
        synchronized(lock) {
            if (snapshot?.charging == true || (snapshot?.batteryPercent ?: 100) > criticalBatteryPercent) {
                batteryAlertedThisEpisode = false
            }
            if (snapshot?.lowStorage != true) storageAlertedThisEpisode = false
        }
    }

    fun cancelAllReminders() = synchronized(lock) { reminders.clear() }

    private fun markSent(clockMs: Long) {
        lastMessageAtMs = clockMs
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes
    }
}
