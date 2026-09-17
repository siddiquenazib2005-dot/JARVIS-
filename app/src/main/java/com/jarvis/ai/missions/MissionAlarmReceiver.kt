package com.jarvis.ai.missions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires a scheduled mission.
 *
 * Kept intentionally thin: it only calls MissionEngine.run(name), the exact
 * same entry point used by chat commands and by the missions screen, so a timed
 * mission behaves identically to a manual one.
 */
class MissionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        if (intent?.action != ACTION_RUN) return
        val name = intent.getStringExtra(EXTRA_MISSION)?.takeIf { it.isNotBlank() } ?: return
        runCatching { MissionEngine(ctx.applicationContext).run(name) }
    }

    companion object {
        const val ACTION_RUN = "com.aurix.ai.action.RUN_MISSION"
        const val EXTRA_MISSION = "mission_name"
    }
}
