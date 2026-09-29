package com.jarvis.ai.missions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.ai.diagnostics.DiagnosticsLog
import java.util.Calendar

/**
 * Trigger metadata for missions, kept in its own store so the engine's
 * Mission/MissionStep model and execution path stay exactly as they are.
 *
 * Supported:
 *  - MANUAL:   run from the missions screen or by voice/text command.
 *  - DAILY:    fired once a day at a chosen time via AlarmManager (inexact, so
 *              no exact-alarm permission is needed and the battery is left alone).
 *  - ONE_SHOT: fired once at a chosen time (exact when Android allows it),
 *              then it clears itself back to MANUAL.
 *
 * Event triggers (charger connected, headphones, arriving somewhere) are on the
 * roadmap; the store already keeps a type string so adding one later is a
 * data change, not a rewrite.
 */
enum class MissionTriggerType { MANUAL, DAILY, ONE_SHOT }

/** Shared by both trigger objects so re-arming reads exactly what set() wrote. */
private const val TRIGGER_PREFS = "aurix_mission_triggers"

data class MissionTrigger(
    val type: MissionTriggerType = MissionTriggerType.MANUAL,
    val hour: Int = 8,
    val minute: Int = 0
) {
    val label: String
        get() = when (type) {
            MissionTriggerType.MANUAL -> "Manual"
            MissionTriggerType.DAILY -> String.format("Daily at %02d:%02d", hour, minute)
            MissionTriggerType.ONE_SHOT -> String.format("Once at %02d:%02d", hour, minute)
        }
}

object MissionTriggerStore {

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(TRIGGER_PREFS, Context.MODE_PRIVATE)

    private fun key(missionName: String) = "trigger_" + missionName.trim().lowercase()

    fun get(context: Context, missionName: String): MissionTrigger {
        val raw = prefs(context).getString(key(missionName), null) ?: return MissionTrigger()
        val parts = raw.split(":")
        val type = runCatching { MissionTriggerType.valueOf(parts[0]) }
            .getOrDefault(MissionTriggerType.MANUAL)
        val hour = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val minute = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
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

/**
 * Wraps AlarmManager so the missions screen and chat commands never touch
 * Android plumbing. Also re-arms every saved DAILY/ONE_SHOT alarm after a
 * reboot (Android clears all alarms on power-off; [MissionBootReceiver] calls
 * [rearmAll]).
 */
object MissionScheduler {

    fun apply(context: Context, missionName: String, trigger: MissionTrigger) {
        cancel(context, missionName)
        if (trigger.type != MissionTriggerType.DAILY && trigger.type != MissionTriggerType.ONE_SHOT) return

        DiagnosticsLog.record("mission", "scheduled \"$missionName\" ${trigger.label}")

        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val first = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, trigger.hour.coerceIn(0, 23))
            set(Calendar.MINUTE, trigger.minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        runCatching {
            if (trigger.type == MissionTriggerType.DAILY) {
                manager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    first.timeInMillis,
                    AlarmManager.INTERVAL_DAY,
                    pending(context, missionName)
                )
            } else {
                // One-shot: exact when the OS allows it, inexact otherwise.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                    manager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        first.timeInMillis,
                        pending(context, missionName)
                    )
                } else {
                    manager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        first.timeInMillis,
                        pending(context, missionName)
                    )
                }
            }
        }
    }

    fun cancel(context: Context, missionName: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(pending(context, missionName)) }
        DiagnosticsLog.record("mission", "cancelled schedule for \"$missionName\"")
    }

    /** Re-arms every saved DAILY/ONE_SHOT trigger. Called after boot. */
    fun rearmAll(context: Context) {
        val store = context.applicationContext
            .getSharedPreferences(TRIGGER_PREFS, Context.MODE_PRIVATE)
        var restored = 0
        for ((key, value) in store.all) {
            if (!key.startsWith("trigger_")) continue
            // prefs values are typed Any?; guard before any String operations.
            val raw = value as? String ?: continue
            val name = key.removePrefix("trigger_")
            val type = runCatching {
                MissionTriggerType.valueOf(raw.split(":").first())
            }.getOrNull() ?: continue
            val hour = raw.split(":").getOrNull(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
            val minute = raw.split(":").getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            when (type) {
                MissionTriggerType.DAILY -> {
                    apply(context, name, MissionTrigger(type, hour, minute))
                    restored++
                }
                MissionTriggerType.ONE_SHOT -> {
                    // Idempotency: if the one-shot moment already passed (device
                    // was off at fire time), EXPIRE it instead of re-arming for
                    // tomorrow — that would be a surprise duplicate execution.
                    val now = Calendar.getInstance()
                    val scheduled = (Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    })
                    if (scheduled.timeInMillis <= now.timeInMillis) {
                        MissionTriggerStore.set(context, name, MissionTrigger()) // back to MANUAL
                    } else {
                        apply(context, name, MissionTrigger(type, hour, minute))
                        restored++
                    }
                }
                MissionTriggerType.MANUAL -> Unit // nothing to restore
            }
        }
        if (restored > 0) {
            DiagnosticsLog.record("mission", "restored after boot: $restored scheduled trigger(s)")
        }
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
