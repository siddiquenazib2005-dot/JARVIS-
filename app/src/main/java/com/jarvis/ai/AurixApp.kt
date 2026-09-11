package com.jarvis.ai

import android.app.Application
import com.jarvis.ai.diagnostics.CrashGuard

/*
 * Process entry point.
 *
 * The crash black box used to be installed in MainActivity.onCreate, which is
 * too late: anything that throws during application startup, inside a service,
 * or on a background thread before the first Activity appears died without
 * leaving a stack trace. Installing it here means EVERY crash in the process
 * is recorded and readable in-app with the "crash log" command.
 */
class AurixApp : Application() {

    override fun onCreate() {
        CrashGuard.install(this)
        super.onCreate()
    }
}
