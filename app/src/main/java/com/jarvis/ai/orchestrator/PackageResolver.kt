package com.jarvis.ai.orchestrator

import android.content.Intent
import android.content.pm.PackageManager

/**
 * Narrow, framework-agnostic lookup surface so package resolution is unit-testable
 * on the JVM (no Android Context subclassing required). [ToolExecutor] adapts the
 * real [android.content.pm.PackageManager] to this interface.
 */
interface AppLookup {
    /** Launch intent for a package, or null if it cannot be launched. */
    fun launchIntentFor(packageName: String): Intent?

    /** All launcher-visible activities (ACTION_MAIN + CATEGORY_LAUNCHER). */
    fun launcherActivities(): List<ResolvedApp>

    /** Every installed application. */
    fun installedApps(): List<ResolvedApp>
}

data class ResolvedApp(val packageName: String, val label: String)

/**
 * Production [AppLookup] adapter over the real [android.content.pm.PackageManager].
 *
 * Per-element `runCatching` on label loading so a single app with a broken/missing
 * label resource can never throw and empty the whole list — the root cause of the
 * "Application not found: whatsapp, sir." regression. Label falls back to the
 * package name so package-name substring matching still resolves it.
 */
object PackageManagerAppLookup {
    fun create(pm: PackageManager): AppLookup = object : AppLookup {
        override fun launchIntentFor(packageName: String): Intent? =
            runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull()

        override fun launcherActivities(): List<ResolvedApp> =
            runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
                    PackageManager.MATCH_ALL
                )
            }.getOrDefault(emptyList())
                .mapNotNull { ri ->
                    runCatching {
                        val pkg = ri.activityInfo.packageName
                        val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
                        ResolvedApp(pkg, label)
                    }.getOrNull()
                }

        override fun installedApps(): List<ResolvedApp> =
            runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }
                .getOrDefault(emptyList())
                .mapNotNull { ai ->
                    runCatching {
                        val pkg = ai.packageName
                        val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
                        ResolvedApp(pkg, label)
                    }.getOrNull()
                }
    }
}

/**
 * Resolves a human app identifier to a concrete package name.
 *
 * Strategy (no hardcoding — works for ANY installed app):
 *  1. If the value already looks like a package name, verify it launches.
 *  2. Match against launcher activities (case-insensitive containment).
 *  3. Fallback to all installed applications by display label.
 *  4. Final fuzzy fallback: substring match on the package name itself
 *     (handles localized app labels).
 */
object PackageResolver {
    fun resolve(name: String, lookup: AppLookup): String? {
        val input = name.lowercase().trim()
            .removePrefix("open ")
            .removePrefix("launch ")
            .removePrefix("close ")
            .trim()

        if (input.contains(".")) {
            if (lookup.launchIntentFor(input) != null) return input
        }

        for (app in lookup.launcherActivities()) {
            val label = app.label.lowercase().trim()
            if (label == input || label.contains(input)) return app.packageName
        }

        val allApps = lookup.installedApps()

        for (app in allApps) {
            val label = app.label.lowercase().trim()
            if (label.isNotBlank() && (label == input || label.contains(input))) return app.packageName
        }

        for (app in allApps) {
            val pkg = app.packageName.lowercase()
            if (pkg.contains(input) || input.contains(pkg)) return app.packageName
        }

        return null
    }
}
