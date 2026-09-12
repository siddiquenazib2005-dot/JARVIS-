package com.jarvis.ai.diagnostics

import android.content.Context

/** Lightweight startup black-box for ANR/slow boot diagnosis. */
object StartupTracker {
    private const val PREFS = "aurix_startup"
    private const val KEY_STAGE = "stage"
    private const val KEY_DETAIL = "detail"
    private const val KEY_AT = "at"
    private const val KEY_BOOT = "boot"

    fun boot(context: Context) {
        val now = System.currentTimeMillis()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_BOOT, now)
            .putString(KEY_STAGE, "BOOT")
            .putString(KEY_DETAIL, "process started")
            .putLong(KEY_AT, now)
            .apply()
    }

    fun stage(context: Context, stage: String, detail: String = "") {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STAGE, stage)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun report(context: Context): String {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = p.getLong(KEY_BOOT, 0L)
        val at = p.getLong(KEY_AT, 0L)
        val age = if (at > 0L) ((System.currentTimeMillis() - at) / 1000L).toString() + "s ago" else "unknown"
        val bootAge = if (boot > 0L) ((System.currentTimeMillis() - boot) / 1000L).toString() + "s" else "unknown"
        return "AURIX Startup Report\n\n" +
            "Last stage: " + (p.getString(KEY_STAGE, "unknown") ?: "unknown") + "\n" +
            "Detail: " + (p.getString(KEY_DETAIL, "") ?: "") + "\n" +
            "Stage updated: " + age + "\n" +
            "Current boot age: " + bootAge
    }
}
