package com.jarvis.ai.missions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Trigger metadata for missions, kept in its own store so the engine's
 * Mission/MissionStep model and execution path stay exactly as they are.
 *
 * Supported for now:
 *  - MANUAL: run from the missions screen or by voice/text command.
 *  - DAILY: fired once a day at a chosen time via AlarmManager (inexact, so no
 *    exact-alarm permission is needed and the battery is left alone).
 *
 * Event triggers (charger connected, headphones, arriving somewhere) are on the
 * roadmap; the store already keeps a type string so adding one later is a
 * data change, not a rewrite.
 */
enum class MissionTriggerType { MANUAL, DAILY }

data class MissionTrigger(
    val type: MissionTriggerType = MissionTriggerType.MANUAL,
    val hour: Int = 8,
    val minute: Int = 0
) {
    val label: String
        get() = when (type) {
            MissionTriggerType.MANUAL -> "Manual"
            MissionTriggerType.DAILY -> String.format("Daily at %02d:%02d", hour, minute)
        }
}

object MissionTriggerStore {

    private const val PREFS_NAME = "aurix_mission_triggers"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(missionName: String) = "trigger_" + missionName.trim().lowercase()

    fun get(context: Context, missionName: String): MissionTrigger {
        val raw = prefs(context).getString(key(missionName), null) ?: return MissionTrigger()
        val parts = raw.split(":")
        val type = runCatching { MissionTriggerType.valueOf(parts[0]) }
            .getOrDefault(MissionTriggerType.MANUAL)
        val hour = parts.getOrNull(1)?.toIntOrNull() ?: 8
        val minute = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return MissionTrigger(type, hour, minute)
    }

    fun set(context: Context, missionName: String, trigger: MissionTrigger) {
        prefs(context).edit()
            .putString(
                key(missionName),
                trigger.type.name + ":" + trigger.hour + ":" + trigger.minute
            )
            .apply()
        MissionScheduler.apply(context, missionName, trigger)
    }

    fun clear(context: Context, missionName: String) {
        prefs(context).edit().remove(key(missionName)).apply()
        MissionScheduler.cancel(context, missionName)
    }
}

/** Wraps AlarmManager so the missions screen never touches Android plumbing. */
object MissionScheduler {

    fun apply(context: Context, missionName: String, trigger: MissionTrigger) {
        cancel(context, missionName)
        if (trigger.type != MissionTriggerType.DAILY) return

        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val first = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, trigger.hour)
            set(Calendar.MINUTE, trigger.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        runCatching {
            manager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                first.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pending(context, missionName)
            )
        }
    }

    fun cancel(context: Context, missionName: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(pending(context, missionName)) }
    }

    private fun pending(context: Context, missionName: String): PendingIntent {
        val intent = Intent(context, MissionAlarmReceiver::class.java)
            .setAction(MissionAlarmReceiver.ACTION_RUN)
            .putExtra(MissionAlarmReceiver.EXTRA_MISSION, missionName)
        return PendingIntent.getBroadcast(
            context,
            missionName.lowercase().hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
