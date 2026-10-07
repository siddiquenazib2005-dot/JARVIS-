package com.jarvis.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jarvis.ai.automation.ScreenAutomation
import com.jarvis.ai.memory.MemoryEngine
import com.jarvis.ai.memory.SecureKvStore
import com.jarvis.ai.missions.Mission
import com.jarvis.ai.missions.MissionEngine
import com.jarvis.ai.missions.MissionTrigger
import com.jarvis.ai.missions.MissionTriggerStore
import com.jarvis.ai.missions.MissionTriggerType
import com.jarvis.ai.missions.ReminderCommands
import com.jarvis.ai.missions.ReminderStore
import com.jarvis.ai.notifications.AurixNotificationListener
import com.jarvis.ai.notifications.NotificationStore
import com.jarvis.ai.overlay.FloatingAvatarService

/** A parsed "send email" turn. Parsing only -- no network work here. */
data class EmailRequest(
    val to: String,
    val subject: String,
    val body: String
)

data class WhatsAppGroupRequest(val chatName: String, val body: String)

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
    private val memory = MemoryEngine(SecureKvStore(context))

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

    /** Parses a group message so the caller can require approval before automation. */
    fun whatsappGroupRequest(rawInput: String): WhatsAppGroupRequest? {
        val text = normalise(rawInput)
        val rest = afterAny(text, "whatsapp group ", "group message ", "message group ") ?: return null
        val chatName = rest.substringBefore(" saying ")
            .substringBefore(" that ")
            .substringBefore(" bolo ")
            .trim()
        val body = afterAny(rest, " saying ", " that ", " bolo ").orEmpty().trim()
        return WhatsAppGroupRequest(chatName, body).takeIf {
            it.chatName.isNotBlank() && it.body.isNotBlank()
        }
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
        // Scheduled missions MUST be matched before the plain run-mission route,
        // otherwise "every day at 7 am run mission x" would run immediately.
        Regex("(?:every day|everyday|daily)\\s+at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s+(run\\s+mission\\s+.+)", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val h12 = m.groupValues[1].toIntOrNull() ?: return@let
                val min = m.groupValues[2].toIntOrNull() ?: 0
                val meridiem = m.groupValues[3].lowercase()
                val hour = when {
                    meridiem == "pm" && h12 != 12 -> h12 + 12
                    meridiem == "am" && h12 == 12 -> 0
                    else -> h12
                }
                if (hour !in 0..23 || min !in 0..59) {
                    return "That time does not look right, sir — try: every day at 7 am run mission good morning."
                }
                val missionName = m.groupValues[4]
                    .replace(Regex("^run\\s+mission\\s+", RegexOption.IGNORE_CASE), "").trim()
                val saved = missions.all().firstOrNull { it.name.equals(missionName, ignoreCase = true) }
                    ?: missions.all().firstOrNull { it.name.contains(missionName, ignoreCase = true) }
                    ?: return "I do not have a mission called \"$missionName\", sir. Create it first, then schedule it."
                MissionTriggerStore.set(app, saved.name, MissionTrigger(MissionTriggerType.DAILY, hour, min))
                return "Scheduled, sir — \"${saved.name}\" will run every day at %02d:%02d.".format(hour, min)
            }
        Regex("stop\\s+(?:the\\s+)?(?:daily\\s+)?schedule\\s+(?:for\\s+)?(.+)", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val name = m.groupValues[1].trim()
                val saved = missions.all().firstOrNull { it.name.contains(name, ignoreCase = true) }
                    ?: return "I could not find that mission, sir."
                MissionTriggerStore.clear(app, saved.name)
                return "Schedule removed for \"${saved.name}\", sir."
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

        // ---------- Reminders ----------
        if (matches(text, "my reminders", "list reminders", "show reminders", "pending reminders")) {
            val all = ReminderStore.listAll(app)
            if (all.isEmpty()) return "No reminders are pending, sir."
            val fmt = java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
            return "Pending reminders, sir:\n" + all.joinToString("\n") { (at, t) ->
                "• ${fmt.format(java.util.Date(at))} — $t"
            }
        }
        afterAny(text, "cancel reminder ", "remove reminder ", "delete reminder ")?.let { target ->
            val hit = ReminderStore.listAll(app).firstOrNull { it.second.contains(target.trim(), ignoreCase = true) }
                ?: return "No reminder matching \"$target\" found, sir."
            ReminderStore.cancel(app, hit.second)
            return "Reminder cancelled, sir: \"${hit.second}\"."
        }
        ReminderCommands.parse(input)?.let { parsed ->
            val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!notificationsAllowed) {
                return "I can store that reminder, sir, but to actually show it I need notification permission. " +
                    "Allow notifications for AURIX in Settings, then say it again."
            }
            val fireAt = parsed.fireAt(System.currentTimeMillis())
            ReminderStore.schedule(app, parsed.text, fireAt)
            return "Reminder set for ${parsed.summary}, sir: \"${parsed.text}\"."
        }
        if (text.contains("remind")) {
            return "Tell me when, sir — for example: remind me to call HR tomorrow at 9 am, or remind me in 20 minutes to stretch."
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

        // ---------- Ambient entity controls (directive §13: user control) ----
        if (matches(text, "aurix sleep", "aurix so jao", "minimize bubble", "bubble minimize")) {
            FloatingAvatarService.minimize(app)
            return "AURIX is resting, sir. Swipe up on the orb or say wake up to bring me back."
        }
        if (matches(text, "aurix wake up", "aurix jago", "bubble wake up")) {
            FloatingAvatarService.wakeUp(app)
            return "AURIX is awake, sir."
        }
        if (matches(text, "aurix voice visuals off", "bubble voice off")) {
            com.jarvis.ai.overlay.ambient.AmbientSettings.setAudioReactiveEnabled(app, false)
            FloatingAvatarService.applySettings(app)
            return "Voice-reactive visuals are off, sir."
        }
        if (matches(text, "aurix voice visuals on", "bubble voice on")) {
            com.jarvis.ai.overlay.ambient.AmbientSettings.setAudioReactiveEnabled(app, true)
            FloatingAvatarService.applySettings(app)
            return "Voice-reactive visuals are on, sir."
        }

        // ---------- Offline memory recall ----------
        if (matches(text, "what is my name", "my name is ?", "my name?", "mera naam kya", "mera name kya")) {
            val saved = memory.recallFact("name")
                ?: memory.allFacts()["name"]
            return if (!saved.isNullOrBlank()) "Your name is $saved, sir."
            else "I do not know your name yet, sir. Say: remember my name is Nazib."
        }

        // ---------- Screen automation ----------
        if (matches(text, "accessibility report", "screen control report", "a11y report")) {
            return screen.report()
        }
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

        // ---------- Brightness ----------
        percentIn(text)?.let { pct ->
            if (text.contains("brightness") || text.contains("screen light") || text.contains("roshni")) {
                return actions.setBrightnessPercent(pct)
            }
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
        whatsappGroupRequest(input)?.let { request ->
            return actions.whatsappGroup(request.chatName, request.body)
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
        val wantsStartup = matches(
            text,
            "startup report", "boot report", "startup status", "why startup stuck",
            "app start report", "launch report", "opening report"
        )
        if (wantsStartup) {
            return com.jarvis.ai.diagnostics.StartupTracker.report(app)
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
        if (!matches(text, "send", "message", "msg", "bhej", "karo", "saying", "bolo", "whatsapp", "sms")) return null

        val bodyMarkers = listOf(" saying ", " that says ", " message ", " msg ", " bolo ki ", " bolo ", " keh do ", " bol do ", " likho ")
        var recipient: String? = null
        var body: String? = null

        val directChannel = channels.firstOrNull { text.startsWith("$it to ") }
        if (directChannel != null) {
            val tail = text.removePrefix("$directChannel to " ).trim()
            val marker = bodyMarkers.firstOrNull { tail.contains(it) }
            if (marker != null) {
                recipient = tail.substringBefore(marker).trim()
                body = tail.substringAfter(marker).trim()
            }
        }

        val toIndex = text.indexOf(" to ")
        if (recipient.isNullOrBlank() && toIndex >= 0) {
            val tail = text.substring(toIndex + 4).trim()
            val marker = bodyMarkers.firstOrNull { tail.contains(it) }
            if (marker != null) {
                recipient = tail.substringBefore(marker).trim()
                body = tail.substringAfter(marker).trim()
            } else {
                val words = tail.split(" ").filter { it.isNotBlank() }
                // Prefer the longest likely contact name before the message.
                // Example: "to boss madam sorry" -> contact="boss madam", body="sorry".
                recipient = when {
                    words.size >= 3 -> words.take(2).joinToString(" ")
                    else -> words.firstOrNull().orEmpty()
                }.trim()
                body = when {
                    words.size >= 3 -> words.drop(2).joinToString(" ")
                    else -> words.drop(1).joinToString(" ")
                }.trim()
            }
        }

        // Hinglish shortcut: "boss madam ko whatsapp per bolo ki sorry yr"
        if (recipient.isNullOrBlank()) {
            val koIndex = text.indexOf(" ko ")
            val boloMarkers = listOf(" bolo ki ", " bolo ", " keh do ", " bol do ")
            val marker = boloMarkers.firstOrNull { text.contains(it) }
            if (koIndex > 0 && marker != null && text.contains("whatsapp")) {
                recipient = text.substring(0, koIndex).trim()
                body = text.substringAfter(marker).trim()
            }
        }

        if (recipient.isNullOrBlank()) return null
        if (body.isNullOrBlank()) {
            return recipient!! to ""
        }
        return recipient!! to body!!
    }

}
