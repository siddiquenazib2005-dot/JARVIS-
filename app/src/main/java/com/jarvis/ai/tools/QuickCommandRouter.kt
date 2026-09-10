package com.jarvis.ai.tools

import android.content.Context

/**
 * Deterministic, offline command layer that runs BEFORE any AI provider.
 *
 * Two reasons this exists:
 *  1. Device work must never depend on a reachable LLM. "flashlight on" should
 *     work with zero API keys configured.
 *  2. It removes an entire class of latency and cost for the most common
 *     assistant commands.
 *
 * Both English and Hinglish phrasings are matched. Anything not recognised
 * returns null and falls through to the normal orchestrator pipeline.
 */
class QuickCommandRouter(context: Context) {

    private val actions = DeviceActionPack(context)

    /** Returns a reply when the input was fully handled locally, else null. */
    fun handle(rawInput: String): String? {
        val input = rawInput.trim()
        if (input.isBlank()) return null
        val text = normalise(input)

        // ---------- Flashlight / torch ----------
        if (matches(text, "flashlight", "flash light", "torch", "tourch")) {
            return actions.torch(!isOffRequest(text))
        }

        // ---------- Battery ----------
        if (matches(text, "battery", "charge kitna", "kitni battery")) {
            return actions.battery()
        }

        // ---------- Device status ----------
        if (matches(text, "device status", "phone status", "system status", "device info", "phone info")) {
            return actions.deviceStatus()
        }

        // ---------- Volume ----------
        percentIn(text)?.let { pct ->
            if (text.contains("volume")) return actions.setVolumePercent(pct)
        }
        if (text.contains("volume")) {
            if (matches(text, "volume up", "increase volume", "volume badha", "awaz badha")) {
                return actions.volumeUp()
            }
            if (matches(text, "volume down", "decrease volume", "volume kam", "awaz kam")) {
                return actions.volumeDown()
            }
        }
        if (matches(text, "mute", "silent kar", "chup kar do phone")) {
            return if (isOffRequest(text) || text.contains("unmute")) actions.unmute() else actions.mute()
        }

        // ---------- Media transport ----------
        if (matches(text, "next track", "next song", "skip song", "agla gana", "next gana")) {
            return actions.nextTrack()
        }
        if (matches(text, "previous track", "previous song", "last song", "pichla gana")) {
            return actions.previousTrack()
        }
        if (matches(text, "pause music", "play music", "pause song", "resume music", "gana chala", "gana band")) {
            return actions.playPause()
        }
        if (matches(text, "stop music", "stop playback", "music band kar")) {
            return actions.stopPlayback()
        }

        // ---------- Alarm & timer ----------
        if (text.contains("alarm")) {
            clockTimeIn(text)?.let { (h, m) ->
                return actions.setAlarm(h, m, "AURIX alarm")
            }
        }
        if (text.contains("timer") || text.contains("remind me in")) {
            durationSecondsIn(text)?.let { seconds ->
                return actions.setTimer(seconds, "AURIX timer")
            }
        }

        // ---------- Camera / gallery / files ----------
        if (matches(text, "open camera", "camera kholo", "camera khol", "take a photo", "photo kheech")) {
            return actions.openCamera()
        }
        if (matches(text, "open gallery", "gallery kholo", "show my photos", "photos dikha")) {
            return actions.openGallery()
        }
        if (matches(text, "open files", "file manager", "my files", "files kholo")) {
            return actions.openFiles()
        }

        // ---------- Maps ----------
        afterAny(text, "navigate to", "directions to", "take me to", "route to", "chalna hai")?.let {
            return actions.navigate(it)
        }
        afterAny(text, "nearby", "near me", "paas me", "aaspaas")?.let {
            if (it.isNotBlank()) return actions.findNearby(it)
        }
        if (matches(text, "open maps", "maps kholo", "show map")) return actions.openMaps()

        // ---------- Calls ----------
        afterAny(text, "call ", "phone karo ", "call karo ", "dial ")?.let { who ->
            val target = who.removePrefix("to ").trim()
            if (target.isNotBlank() && target.length <= 40) return actions.call(target)
        }
        if (matches(text, "open dialer", "open dialpad", "dialer kholo")) return actions.dialpad()
        if (matches(text, "call log", "call history", "recent calls")) return actions.callLog()
        afterAny(text, "number of ", "contact number ", "lookup contact ")?.let {
            return actions.contactLookup(it)
        }

        // ---------- SOS ----------
        if (matches(text, "sos", "emergency help", "bachao")) return actions.sos(null)

        // ---------- Messaging ----------
        parseMessage(text, listOf("whatsapp"))?.let { (who, body) ->
            return actions.whatsapp(who, body)
        }
        parseMessage(text, listOf("sms", "text message", "message"))?.let { (who, body) ->
            return actions.sms(who, body)
        }

        // ---------- Email ----------
        if (matches(text, "send email", "send an email", "email karo", "compose email")) {
            val to = afterAny(text, " to ").orEmpty().substringBefore(" saying ").trim()
            val body = afterAny(text, " saying ", " that ").orEmpty()
            return actions.email(to, "Sent from AURIX", body)
        }

        // ---------- Search ----------
        afterAny(text, "search youtube for", "youtube search", "play on youtube", "youtube per")?.let {
            return actions.youtubeSearch(it)
        }
        afterAny(text, "google ", "search for ", "search web for ", "web search ")?.let {
            if (it.isNotBlank()) return actions.webSearch(it)
        }
        afterAny(text, "open website ", "go to site ", "open link ")?.let {
            return actions.openUrl(it)
        }

        // ---------- Apps ----------
        if (matches(text, "list apps", "installed apps", "my apps", "apps list")) {
            return actions.listApps()
        }
        afterAny(text, "uninstall ", "remove app ")?.let { return actions.uninstallApp(it) }
        afterAny(text, "open app ", "open ", "launch ", "kholo ", "khol do ")?.let { raw ->
            val target = raw.removeSuffix(" app").trim()
            if (target.isBlank()) return "Which app should I open, sir?"
            // "open settings" style requests route to the settings handler below.
            if (!target.startsWith("settings")) return actions.openApp(target)
        }

        // ---------- Settings shortcuts ----------
        if (text.contains("settings") || matches(text, "wifi", "bluetooth", "hotspot", "airplane mode")) {
            val section = listOf(
                "wifi", "bluetooth", "display", "brightness", "sound", "battery", "apps",
                "location", "storage", "accessibility", "notification", "airplane",
                "hotspot", "security", "language", "developer", "data"
            ).firstOrNull { text.contains(it) }.orEmpty()
            return actions.openSettings(section)
        }

        // ---------- App info / share ----------
        if (matches(text, "app info", "aurix permissions", "app permissions")) return actions.openAppInfo()
        afterAny(text, "share ")?.let { if (it.length in 1..500) return actions.shareText(it) }

        // ---------- PC connect ----------
        if (matches(text, "pc connect", "connect to pc", "desktop bridge")) {
            return actions.pcBridgeInfo(PC_BRIDGE_PORT)
        }

        return null
    }

    // ------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------

    private fun normalise(input: String): String =
        input.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun matches(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it) }

    private fun isOffRequest(text: String): Boolean =
        matches(text, " off", "band", "bandh", "disable", "turn off", "switch off")

    /** Returns the substring after the first matching marker, trimmed. */
    private fun afterAny(text: String, vararg markers: String): String? {
        markers.forEach { marker ->
            val index = text.indexOf(marker)
            if (index >= 0) {
                val tail = text.substring(index + marker.length).trim()
                if (tail.isNotEmpty()) return tail
            }
        }
        return null
    }

    private fun percentIn(text: String): Int? =
        Regex("(\\d{1,3})\\s*%").find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** Parses "7:30", "7 30 am", "at 7 pm". */
    private fun clockTimeIn(text: String): Pair<Int, Int>? {
        val match = Regex("(\\d{1,2})[:. ](\\d{2})\\s*(am|pm)?").find(text)
        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            val suffix = match.groupValues[3]
            if (suffix == "pm" && hour < 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            if (hour in 0..23 && minute in 0..59) return hour to minute
        }
        val hourOnly = Regex("(\\d{1,2})\\s*(am|pm)").find(text) ?: return null
        var hour = hourOnly.groupValues[1].toIntOrNull() ?: return null
        val suffix = hourOnly.groupValues[2]
        if (suffix == "pm" && hour < 12) hour += 12
        if (suffix == "am" && hour == 12) hour = 0
        return if (hour in 0..23) hour to 0 else null
    }

    /** Parses "5 minutes", "30 sec", "1 hour" into seconds. */
    private fun durationSecondsIn(text: String): Int? {
        val match = Regex("(\\d{1,4})\\s*(second|seconds|sec|minute|minutes|min|hour|hours|hr)")
            .find(text) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        return when (match.groupValues[2]) {
            "second", "seconds", "sec" -> value
            "minute", "minutes", "min" -> value * 60
            else -> value * 3600
        }
    }

    /**
     * Extracts (recipient, body) from phrasings like
     * "send whatsapp to nazib saying hello" or "whatsapp nazib hello".
     */
    private fun parseMessage(text: String, channels: List<String>): Pair<String, String>? {
        if (channels.none { text.contains(it) }) return null
        if (!matches(text, "send", "message", "msg", "bhej", "karo")) return null

        val bodyMarkers = listOf(" saying ", " that says ", " message ", " msg ", " bolo ", " likho ")
        var recipient: String? = null
        var body: String? = null

        val toIndex = text.indexOf(" to ")
        if (toIndex >= 0) {
            val tail = text.substring(toIndex + 4).trim()
            val marker = bodyMarkers.firstOrNull { tail.contains(it) }
            if (marker != null) {
                recipient = tail.substringBefore(marker).trim()
                body = tail.substringAfter(marker).trim()
            } else {
                val words = tail.split(" ")
                recipient = words.firstOrNull()?.trim()
                body = words.drop(1).joinToString(" ").trim()
            }
        }

        if (recipient.isNullOrBlank()) return null
        if (body.isNullOrBlank()) {
            return recipient!! to ""
        }
        return recipient!! to body!!
    }

    companion object {
        const val PC_BRIDGE_PORT = 8765
    }
}
