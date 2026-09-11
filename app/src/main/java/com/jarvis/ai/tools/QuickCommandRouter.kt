package com.jarvis.ai.tools

import android.content.Context
import com.jarvis.ai.automation.ScreenAutomation
import com.jarvis.ai.missions.Mission
import com.jarvis.ai.missions.MissionEngine
import com.jarvis.ai.notifications.AurixNotificationListener
import com.jarvis.ai.notifications.NotificationStore
import com.jarvis.ai.overlay.FloatingAvatarService

/** A parsed "send email" turn. Parsing only -- no network work here. */
data class EmailRequest(
    val to: String,
    val subject: String,
    val body: String
)

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

    private val app = context.applicationContext
    private val actions = DeviceActionPack(context)
    private val screen = ScreenAutomation(context)
    private val missions = MissionEngine(context)
    private val world = MyWorldStore(context)

    /**
     * Returns a reply when the input was fully handled locally, else null.
     *
     * Deep wiring rule: a recognised command must never fail silently. If the
     * action throws, the caller used to swallow the exception and quietly fall
     * through to the AI provider, which then answered "no API key" or nothing
     * at all -- the user saw a dead button. Now the error itself is the reply.
     */
    fun handle(rawInput: String): String? =
        runCatching { selfCheckReply(rawInput) ?: dispatch(rawInput) }.getOrElse { error ->
            val reason = error.message?.takeIf { it.isNotBlank() }
                ?: error::class.java.simpleName
            // Real-time feed for the health agent: every silently-failed
            // command becomes evidence the next self-check can report.
            com.jarvis.ai.diagnostics.DiagnosticsLog.record("command", reason)
            // Brain step 4: the failure is still reported, but it is TAGGED so
            // the caller knows the regex layer did not actually satisfy the
            // user. An untagged reply means "done, stop here"; a tagged one
            // means "I tried and failed -- let the brain have a turn too".
            SOFT_FAIL_MARK + "That command failed on the device, sir: $reason"
        }

    companion object {
        /** Prefix marking a recognised-but-failed command. Never shown raw. */
        const val SOFT_FAIL_MARK = "\u0007softfail:"

        /** True when [handle] matched a command but could not complete it. */
        fun isSoftFail(reply: String?): Boolean =
            reply != null && reply.startsWith(SOFT_FAIL_MARK)

        /** Strips the marker for display. */
        fun cleanFailure(reply: String): String =
            reply.removePrefix(SOFT_FAIL_MARK).trim()
    }

    /**
     * Item 9 detection seam. Image generation is a network call, so it must
     * NOT run inside [handle], which the view model calls on the main thread.
     * The view model checks this first and runs the work on an IO dispatcher.
     *
     * Returns the subject to draw, or null when this is not an image request.
     */
    fun imagePrompt(rawInput: String): String? {
        val text = normalise(rawInput)
        val markers = listOf(
            "generate image of ", "generate an image of ", "generate image ",
            "create image of ", "create an image of ",
            "make an image of ", "make image of ",
            "draw me ", "draw a ", "draw an ", "draw ",
            "image banao ", "photo banao ", "tasveer banao ",
            "picture of ", "text to image "
        )
        if (!startsWithAny(text, *markers.toTypedArray())) return null
        val subject = afterAny(text, *markers.toTypedArray())?.trim().orEmpty()
        return subject.takeIf { it.length in 2..400 }
    }

    /**
     * Parses a "send email" turn without performing any network work.
     *
     * Same seam as [imagePrompt]: SMTP is blocking IO, so the router only
     * PARSES here and the caller does the sending off the main thread. The
     * compose-intent path in [DeviceActionPack.email] stays reachable as the
     * fallback when SMTP credentials are absent.
     */
    fun emailRequest(rawInput: String): EmailRequest? {
        val text = normalise(rawInput)
        val markers = arrayOf(
            "send email", "send an email", "send a mail", "email karo",
            "mail karo", "compose email", "email bhejo", "mail bhejo"
        )
        if (!matches(text, *markers)) return null
        val to = afterAny(text, " to ")
            .orEmpty()
            .substringBefore(" saying ")
            .substringBefore(" subject ")
            .trim()
            // Speech input renders "@" as " at " and "." as " dot ".
            .replace(" at ", "@")
            .replace(" dot ", ".")
            .replace(" ", "")
        val subject = afterAny(text, " subject ")?.substringBefore(" saying ")?.trim().orEmpty()
        val body = afterAny(text, " saying ", " that ", " body ").orEmpty().trim()
        if (to.isBlank()) return null
        return EmailRequest(to = to, subject = subject, body = body)
    }

    /**
     * Detects "what is in my last photo"-style turns. Returns the question to
     * ask the vision model, or null when this is not a photo request.
     */
    fun lastPhotoQuestion(rawInput: String): String? {
        val text = normalise(rawInput)
        val markers = arrayOf(
            "last photo", "latest photo", "last picture", "latest picture",
            "recent photo", "last screenshot", "latest screenshot",
            "photo me kya", "photo mein kya", "tasveer me kya",
            "last image", "pichli photo", "meri photo"
        )
        if (!matches(text, *markers)) return null
        return rawInput.trim().ifBlank { "Describe this photo." }
    }

    private fun dispatch(rawInput: String): String? {
        val input = rawInput.trim()
        if (input.isBlank()) return null
        val text = normalise(input)

        // ---------- Missions (multi-step routines) ----------
        if (matches(text, "list missions", "my missions", "show missions", "missions list")) {
            return missions.describeAll()
        }
        afterAny(text, "run mission ", "start mission ", "mission run ")?.let {
            return missions.run(it)
        }
        afterAny(text, "delete mission ", "remove mission ")?.let {
            return missions.delete(it)
        }
        afterAny(text, "create mission ", "save mission ", "new mission ")?.let { spec ->
            val name = spec.substringBefore(":").trim()
            val stepText = spec.substringAfter(":", "")
            val steps = stepText.split(",", " then ")
                .mapNotNull { missions.parseStep(it) }
            if (name.isBlank() || steps.isEmpty()) {
                return "Give it a name and steps, sir — for example: " +
                    "create mission night: torch off, mute, open settings."
            }
            return missions.save(Mission(name, steps))
        }
        if (text.contains(" then ") && text.split(" then ").size in 2..6) {
            val steps = text.split(" then ")
            if (steps.all { missions.parseStep(it) != null }) return missions.runChain(steps)
        }

        // ---------- Notifications & OTP ----------
        if (matches(text, "otp", "verification code", "code aaya", "last code")) {
            if (!AurixNotificationListener.isEnabled(app)) {
                return AurixNotificationListener.requestAccess(app)
            }
            val otp = NotificationStore.latestOtp()
                ?: return "No fresh OTP in the last ten minutes, sir."
            return "Your latest OTP is ${otp.first} (from ${otp.second}), sir."
        }
        if (matches(text, "notifications", "notification read", "notification dikha", "my alerts")) {
            if (!AurixNotificationListener.isEnabled(app)) {
                return AurixNotificationListener.requestAccess(app)
            }
            return NotificationStore.summary()
        }

        // ---------- Floating bubble ----------
        if (matches(text, "show bubble", "floating avatar", "floating bubble", "bubble on")) {
            return FloatingAvatarService.show(app)
        }
        if (matches(text, "hide bubble", "close bubble", "bubble off", "remove bubble")) {
            return FloatingAvatarService.hide(app)
        }

        // ---------- Screen automation ----------
        if (matches(text, "what's on my screen", "whats on my screen", "read screen", "read my screen", "screen padho")) {
            return screen.readScreen()
        }
        if (startsWithAny(text, "tap on ", "tap ", "click on ", "click ", "press button ")) {
            afterAny(text, "tap on ", "tap ", "click on ", "click ", "press button ")?.let {
                if (it.length in 1..40) return screen.tap(it)
            }
        }
        if (startsWithAny(text, "long press ", "hold on ")) {
            afterAny(text, "long press ", "hold on ")?.let {
                if (it.length in 1..40) return screen.longPress(it)
            }
        }
        if (startsWithAny(text, "type ", "likho ", "enter text ")) {
            afterAny(text, "type ", "likho ", "enter text ")?.let {
                if (it.length in 1..300) return screen.type(it)
            }
        }
        afterAny(text, "scroll until ", "find on screen ")?.let { return screen.scrollUntil(it) }
        if (matches(text, "scroll down", "neeche scroll")) return screen.scroll(true)
        if (matches(text, "scroll up", "upar scroll")) return screen.scroll(false)
        afterAny(text, "swipe ")?.let { direction ->
            val dir = listOf("up", "down", "left", "right").firstOrNull { direction.contains(it) }
            if (dir != null) return screen.swipe(dir)
        }
        if (matches(text, "go back", "press back", "back jao")) return screen.back()
        if (matches(text, "go home", "home screen", "press home")) return screen.home()
        if (matches(text, "recent apps", "app switcher", "recents")) return screen.recents()
        if (matches(text, "lock screen", "lock the phone", "phone lock")) return screen.lock()

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

        // ---------- My World (item 8, option B) ----------
        // Checked before Maps so "navigate to gym" can resolve a pinned place
        // into coordinates instead of sending Maps a meaningless search word.
        afterAny(
            text,
            "remember this place as ", "remember this spot as ",
            "pin this place as ", "save this place as ", "yaad rakho yeh jagah "
        )?.let { spec ->
            val name = spec.substringBefore(" because ").substringBefore(" note ").trim()
            val note = afterAny(spec, " because ", " note ").orEmpty()
            if (name.isNotBlank()) return world.remember(name, note)
        }
        afterAny(text, "where is ", "kaha hai ")?.let { name ->
            world.coordinatesFor(name)?.let { return world.where(name) }
        }
        if (matches(text, "my world", "my places", "saved places", "pinned places")) {
            return world.list()
        }
        afterAny(text, "forget place ", "remove place ", "delete place ")?.let {
            return world.forget(it)
        }

        // ---------- Maps ----------
        afterAny(text, "navigate to", "directions to", "take me to", "route to", "chalna hai")?.let {
            // A pinned place beats a text search: exact coordinates, no guessing.
            world.coordinatesFor(it)?.let { coords -> return actions.navigate(coords) }
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
        // Setup must be checked before the bare "sos" trigger, otherwise
        // "set sos contact papa" would fire an actual emergency message.
        afterAny(
            text,
            "set sos contact ", "sos contact ", "emergency contact ", "sos number "
        )?.let { who ->
            val target = who.removePrefix("to ").removePrefix("is ").trim()
            if (target.isNotBlank() && target.length <= 40) return actions.setSosContact(target)
        }
        if (matches(text, "sos", "emergency help", "bachao")) return actions.sos()

        // ---------- Messaging ----------
        // Group phrasing first: groups have no number, so they need the
        // search-and-send path instead of a wa.me link.
        afterAny(text, "whatsapp group ", "group message ", "message group ")?.let { rest ->
            val name = rest.substringBefore(" saying ")
                .substringBefore(" that ")
                .substringBefore(" bolo ")
                .trim()
            val body = afterAny(rest, " saying ", " that ", " bolo ").orEmpty().trim()
            if (name.isNotBlank() && body.isNotBlank()) {
                return actions.whatsappGroup(name, body)
            }
        }
        parseMessage(text, listOf("whatsapp"))?.let { (who, body) ->
            return actions.whatsapp(who, body)
        }
        parseMessage(text, listOf("sms", "text message", "message"))?.let { (who, body) ->
            return actions.sms(who, body)
        }

        // ---------- Wake word (opt-in, never auto-started) ----------
        if (matches(text, "wake word", "hotword", "always listening", "always listen", "hands free mode")) {
            return if (isOffRequest(text)) {
                com.jarvis.ai.service.WakeWordService.stop(app)
                "Wake word listening is off, sir."
            } else {
                com.jarvis.ai.service.WakeWordService.start(app)
                "Listening for \"Aurix\", sir. Battery use is higher while this is on."
            }
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
        // startsWith, not contains: "ok google" mid-sentence or "search for"
        // inside a longer question should not hijack the whole request.
        if (startsWithAny(text, "google ", "search for ", "search web for ", "web search ")) {
            afterAny(text, "google ", "search for ", "search web for ", "web search ")?.let {
                if (it.isNotBlank()) return actions.webSearch(it)
            }
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
        // startsWith only: "share market kya hai" is a question, not a share.
        if (startsWithAny(text, "share ")) {
            afterAny(text, "share ")?.let { if (it.length in 1..500) return actions.shareText(it) }
        }

        return null
    }

    /**
     * "self check" / "diagnostics" / "sab theek hai" -> on-device report.
     *
     * Handled before [dispatch] so it can never be shadowed by another
     * command, and kept offline so it still answers when the very thing
     * being diagnosed is a dead AI provider.
     */
    private fun selfCheckReply(rawInput: String): String? {
        val text = normalise(rawInput)
        // Black box read-out. Answered before the self-check so "crash log"
        // never gets swallowed by the broader "diagnostic" matcher.
        val wantsCrash = matches(
            text,
            "crash log", "crashlog", "crash report", "last crash", "why did you crash",
            "why app closed", "app band kyu hua", "crash kyu hua", "stack trace"
        )
        if (wantsCrash) {
            return com.jarvis.ai.diagnostics.CrashGuard.report(app)
        }
        val asked = matches(
            text,
            "self check", "selfcheck", "self-check", "diagnostic", "diagnose",
            "health check", "system check", "run checkup", "status report",
            "sab theek hai", "sab thik hai", "kya kya kaam kar raha"
        )
        if (!asked) return null
        return com.jarvis.ai.diagnostics.SelfCheck.run(app).toChatMessage()
    }

    // ------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------

    private fun normalise(input: String): String =
        input.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun matches(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it) }

    private fun startsWithAny(text: String, vararg prefixes: String): Boolean =
        prefixes.any { text.startsWith(it) }

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

}
