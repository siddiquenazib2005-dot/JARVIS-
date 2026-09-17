package com.jarvis.ai

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.jarvis.ai.diagnostics.CrashGuard
import com.jarvis.ai.overlay.FloatingAvatarService

/** Process entry point for crash capture and the user-enabled companion. */
class AurixApp : Application(), Application.ActivityLifecycleCallbacks {

    override fun onCreate() {
        CrashGuard.install(this)
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    /**
     * Android returns to the app after the owner grants overlay permission.
     * There is no activity-result callback for this special-access screen, so
     * resume is the reliable hand-off. A persisted false value always wins:
     * once the owner chooses HIDE, normal app resumes never resurrect it.
     */
    override fun onActivityResumed(activity: Activity) {
        val prefs = getSharedPreferences("aurix_companion", MODE_PRIVATE)
        val explicitlyDisabled = prefs.contains("enabled") &&
            !prefs.getBoolean("enabled", false)
        if (!explicitlyDisabled && FloatingAvatarService.canDrawOverlay(this)) {
            FloatingAvatarService.show(this)
        } else {
            FloatingAvatarService.resumeIfRequested(this)
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
