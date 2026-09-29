package com.jarvis.ai.missions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.ai.diagnostics.DiagnosticsLog

/**
 * Fires a scheduled mission.
 *
 * Kept intentionally thin: it only calls MissionEngine.run(name), the exact
 * same entry point used by chat commands and by the missions screen, so a timed
 * mission behaves identically to a manual one.
 *
 * Trigger lifecycle lives here too: a ONE_SHOT trigger expires back to MANUAL
 * right after firing, so it can never re-arm and execute twice (boot re-arm
 * would otherwise resurrect it the next day).
 */
class MissionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        if (intent?.action != ACTION_RUN) return
        val name = intent.getStringExtra(EXTRA_MISSION)?.takeIf { it.isNotBlank() } ?: return
        runCatching { MissionEngine(ctx.applicationContext).run(name) }
            .onFailure { DiagnosticsLog.record("mission", "trigger failed for \"$name\": ${it.message?.take(120)}") }
        runCatching {
            if (MissionTriggerStore.get(ctx, name).type == MissionTriggerType.ONE_SHOT) {
                MissionTriggerStore.set(ctx, name, MissionTrigger()) // expire to MANUAL
                DiagnosticsLog.record("mission", "triggered \"$name\" (one-shot expired to manual)")
            }
        }
    }

    companion object {
        const val ACTION_RUN = "com.aurix.ai.action.RUN_MISSION"
        const val EXTRA_MISSION = "mission_name"
    }
}
