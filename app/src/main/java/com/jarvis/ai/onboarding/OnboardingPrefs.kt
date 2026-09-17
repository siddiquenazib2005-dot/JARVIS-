package com.jarvis.ai.onboarding

import android.content.Context

/**
 * Remembers whether the first-run onboarding wizard has been completed.
 *
 * Design notes:
 *  - Stores the *version* of the flow that was completed, not a bare boolean.
 *    When a future release adds a new permission card we bump [FLOW_VERSION]
 *    and the wizard reappears once, instead of silently never asking again.
 *  - Records skipped items so the chat screen can show a persistent "access is
 *    OFF" warning for anything the owner chose to skip (skip is allowed by
 *    design: some OEMs bury the Accessibility toggle and blocking would trap
 *    the user on this screen).
 *  - Object + SharedPreferences mirror, matching the existing RoutingPrefs
 *    pattern so callers without a Context can still read state.
 */
object OnboardingPrefs {

    /** Bump this when the permission catalog gains a new required card. */
    const val FLOW_VERSION = 1

    private const val PREFS_NAME = "aurix_onboarding"
    private const val KEY_COMPLETED_VERSION = "completed_version"
    private const val KEY_SKIPPED = "skipped_ids"

    @Volatile
    private var appContext: Context? = null

    /** Flow version the owner finished, or 0 when onboarding never ran. */
    @Volatile
    var completedVersion: Int = 0
        private set

    /** Catalog ids the owner explicitly skipped during the wizard. */
    @Volatile
    var skippedIds: Set<String> = emptySet()
        private set

    /** True when the wizard should be shown on launch. */
    val needsOnboarding: Boolean get() = completedVersion < FLOW_VERSION

    /** Loads persisted state. Safe to call repeatedly (e.g. from MainActivity). */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        completedVersion = prefs.getInt(KEY_COMPLETED_VERSION, 0)
        skippedIds = prefs.getString(KEY_SKIPPED, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    /** Marks the wizard finished, remembering whatever the owner skipped. */
    fun markCompleted(skipped: Set<String> = emptySet()) {
        completedVersion = FLOW_VERSION
        skippedIds = skipped
        write()
    }

    /** Records a single skipped card without ending the flow. */
    fun markSkipped(id: String) {
        if (id.isBlank() || skippedIds.contains(id)) return
        skippedIds = skippedIds + id
        write()
    }

    /** Clears a skip once the owner grants that access later. */
    fun clearSkipped(id: String) {
        if (!skippedIds.contains(id)) return
        skippedIds = skippedIds - id
        write()
    }

    /**
     * Reopens the wizard in review mode from the drawer.
     * Keeps skip history so the warning bar stays accurate until access is on.
     */
    fun resetForReview() {
        completedVersion = 0
        write()
    }

    private fun write() {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putInt(KEY_COMPLETED_VERSION, completedVersion)
            ?.putString(KEY_SKIPPED, skippedIds.joinToString(","))
            ?.apply()
    }
}
