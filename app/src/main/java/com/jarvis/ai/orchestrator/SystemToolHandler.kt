package com.jarvis.ai.orchestrator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * Lightweight fast-path launcher for explicit system-control commands like
 * "open youtube". Runs BEFORE the LLM / tool-classification pipeline so
 * common app launches answer instantly instead of spinning up a model call.
 *
 * Resolution order:
 *   1. Known package map for popular apps (YouTube, Spotify, WhatsApp, etc.).
 *   2. `getLaunchIntentForPackage()` for anything else the user names (incl.
 *      a raw package id like "open com.foo.bar").
 *   3. Standard intent schema fallback (settings panel, maps, browser links).
 *
 * Returns null when the phrase is not an "open <app>" command, so the normal
 * orchestrator pipeline can take over unhandled input.
 */
class SystemToolHandler(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    private val KNOWN_PACKAGES = mapOf(
        "youtube" to "com.google.android.youtube",
        "youtube music" to "com.google.android.apps.youtube.music",
        "spotify" to "com.spotify.music",
        "whatsapp" to "com.whatsapp",
        "discord" to "com.discord",
        "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "snapchat" to "com.snapchat.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "firefox" to "org.mozilla.firefox",
        "gmail" to "com.google.android.gm",
        "camera" to "com.android.camera",
        "calculator" to "com.google.android.calculator",
        "calendar" to "com.android.calendar",
        "gallery" to "com.google.android.apps.photos",
        "photos" to "com.google.android.apps.photos",
        "clock" to "com.google.android.deskclock",
        "alarm" to "com.google.android.deskclock",
        "notes" to "com.google.android.keep",
        "keep" to "com.google.android.keep",
        "phone" to "com.android.dialer",
        "dialer" to "com.android.dialer",
        "contacts" to "com.google.android.contacts",
        "play store" to "com.android.vending",
        "play" to "com.android.vending",
        "netflix" to "com.netflix.mediaclient",
        "prime video" to "com.amazon.avod.thirdpartyclient",
        "twitch" to "tv.twitch.android.app",
        "music" to "com.google.android.apps.youtube.music"
    )

    data class LaunchResult(val success: Boolean, val reply: String)

    /**
     * Attempts to parse + launch an "open/launch/start <app>" command.
     * Returns null if the phrase is not a launch command.
     */
    fun handle(input: String): LaunchResult? {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()
        val prefixLen = launchPrefixLength(lower) ?: return null
        val appArg = trimmed.substring(prefixLen).trim()
        if (appArg.isEmpty()) return null
        val appKey = appArg.lowercase()

        return when {
            "settings" in appKey -> launchSettings()
            appKey.contains("maps") -> launchMaps()
            appKey == "gemini" || appKey.contains("assistant") ->
                launchUrl("https://gemini.google.com/app", "Gemini")
            else -> launchKnownOrPackage(appKey)
        }
    }

    private fun launchKnownOrPackage(appArg: String): LaunchResult {
        // 1. Known package map — only if actually installed.
        KNOWN_PACKAGES[appArg]?.let { pkg ->
            if (pm.getLaunchIntentForPackage(pkg) != null) {
                return doLaunch(pkg, appArg)
            }
        }
        // 2. Raw package id.
        if (appArg.contains('.') && pm.getLaunchIntentForPackage(appArg) != null) {
            return doLaunch(appArg, appArg)
        }
        return LaunchResult(false, "I couldn't find an app named \"$appArg\", sir.")
    }

    private fun launchSettings(): LaunchResult {
        // Prefer the Settings app's own launch intent (present on AOSP/GMS).
        pm.getLaunchIntentForPackage("com.android.settings")?.let { intent ->
            return doLaunch("com.android.settings", "Settings")
        }
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            LaunchResult(true, "Opening Settings, sir.")
        } catch (e: Exception) {
            LaunchResult(false, "I could not open Settings, sir. ${e.message?.take(100)}")
        }
    }

    private fun launchMaps(): LaunchResult {
        val pkg = "com.google.android.apps.maps"
        if (pm.getLaunchIntentForPackage(pkg) != null) {
            return doLaunch(pkg, "Maps")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=where"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            LaunchResult(true, "Opening Maps, sir.")
        } catch (e: Exception) {
            LaunchResult(false, "I could not open Maps, sir. ${e.message?.take(100)}")
        }
    }

    private fun launchUrl(url: String, name: String): LaunchResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            LaunchResult(true, "Opening $name, sir.")
        } catch (e: Exception) {
            LaunchResult(false, "I could not open $name, sir. ${e.message?.take(100)}")
        }
    }

    private fun doLaunch(packageName: String, displayName: String): LaunchResult {
        return try {
            val intent = pm.getLaunchIntentForPackage(packageName) ?: Intent(Intent.ACTION_MAIN).apply {
                setPackage(packageName)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(intent)
            LaunchResult(true, "Opening ${displayName.replaceFirstChar(Char::uppercase)}, sir.")
        } catch (e: Exception) {
            Log.w(TAG, "launch '$packageName' failed: ${e.message}")
            LaunchResult(false, "I could not open ${displayName.replaceFirstChar(Char::uppercase)}, sir. ${e.message?.take(100)}")
        }
    }

    private fun launchPrefixLength(lower: String): Int? {
        val prefixes = listOf("open ", "launch ", "start ")
        for (p in prefixes) {
            if (lower.startsWith(p)) return p.length
        }
        return null
    }

    companion object {
        private const val TAG = "SystemToolHandler"
    }
}