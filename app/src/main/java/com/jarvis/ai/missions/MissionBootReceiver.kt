package com.jarvis.ai.missions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms everything time-based after a reboot: Android clears every
 * AlarmManager alarm on power-off, so without this receiver scheduled missions
 * and reminders would silently die on restart.
 *
 * Kept deliberately thin — it only calls the re-arm helpers. GOING_TO_SLEEP is
 * ignored (alarms persist across light sleep; only a full reboot clears them).
 */
class MissionBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        runCatching { MissionScheduler.rearmAll(ctx.applicationContext) }
        runCatching { ReminderStore.rearmAll(ctx.applicationContext) }
    }
}
