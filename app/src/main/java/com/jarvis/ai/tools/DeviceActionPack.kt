package com.jarvis.ai.tools

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.ai.automation.ScreenAutomation

/**
 * Basic-level implementation of the whole MYRA feature surface.
 *
 * Every action here is deterministic, offline-capable and implemented with
 * plain Android intents / system services, so the assistant can carry out
 * device work even when no AI provider is reachable. Each function returns a
 * short user-facing sentence (never throws) so the chat layer can render the
 * outcome directly.
 */
class DeviceActionPack(context: Context) {

    private val app: Context = context.applicationContext
    private val screen = ScreenAutomation(context)

    // ------------------------------------------------------------------
    // Launching helpers
    // ------------------------------------------------------------------

    /**
     * Item 3: the reason the most recent [launch] failed.
     *
     * Every action used to collapse three very different failures -- no app
     * installed, permission denied, malformed intent -- into one flat "I could
     * not do that" line, which made real bugs indistinguishable from a missing
     * app. The reason is captured here and appended by [fail].
     */
    private var lastFailure: String? = null

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        lastFailure = null
        true
    } catch (e: ActivityNotFoundException) {
        lastFailure = "no app on this phone handles that"
        false
    } catch (e: SecurityException) {
        lastFailure = "Android blocked it for permissions"
        false
    } catch (e: Exception) {
        lastFailure = e.message?.takeIf { it.isNotBlank() }?.take(120)
            ?: e::class.java.simpleName
        false
    }

    private fun ok(text: String) = text

    /** Honest failure line: always says WHY when the cause is known. */
    private fun fail(what: String): String {
        val reason = lastFailure
        lastFailure = null
        return if (reason.isNullOrBlank()) {
            "I could not $what on this device, sir."
        } else {
            "I could not $what, sir - $reason."
        }
    }

    // ------------------------------------------------------------------
    // Apps & search
    // ------------------------------------------------------------------

    /** Opens an installed app by fuzzy label or package name. */
    fun openApp(query: String): String {
        val target = query.trim()
        if (target.isBlank()) return "Which app should I open, sir?"
        val pm = app.packageManager
        val installed = runCatching {
            pm.getInstalledApplications(0).mapNotNull { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
                if (label.isNullOrBlank()) null else label to info.packageName
            }
        }.getOrDefault(emptyList())

        val lower = target.lowercase()
        val match = installed.firstOrNull { it.first.equals(target, true) }
            ?: installed.firstOrNull { it.second.equals(target, true) }
            ?: installed.firstOrNull { it.first.lowercase().startsWith(lower) }
            ?: installed.firstOrNull { it.first.lowercase().contains(lower) }
            ?: installed.firstOrNull { it.second.lowercase().contains(lower) }
            ?: return "I could not find an app called \"$target\", sir."

        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: return "${match.first} has no launchable screen, sir."
        return if (launch(intent)) ok("Opening ${match.first}.") else fail("open ${match.first}")
    }

    /** Lists installed launchable apps, alphabetically. */
    fun listApps(limit: Int = 40): String {
        val pm = app.packageManager
        val names = runCatching {
            pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .mapNotNull { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
        if (names.isEmpty()) return "I could not read the app list, sir."
        val shown = names.take(limit)
        val tail = if (names.size > limit) "\n… and ${names.size - limit} more." else ""
        return "You have ${names.size} apps installed, sir:\n" +
            shown.joinToString("\n") { "• $it" } + tail
    }

    fun uninstallApp(query: String): String {
        val pm = app.packageManager
        val lower = query.trim().lowercase()
        val pkg = runCatching {
            pm.getInstalledApplications(0).firstOrNull { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
                label?.lowercase()?.contains(lower) == true
            }?.packageName
        }.getOrNull() ?: return "I could not find \"$query\", sir."
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
        return if (launch(intent)) ok("Uninstall screen opened, sir.") else fail("open the uninstaller")
    }

    fun webSearch(query: String): String {
        if (query.isBlank()) return "What should I search for, sir?"
        val url = "https://www.google.com/search?q=" + Uri.encode(query)
        return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            ok("Searching the web for \"$query\".")
        } else fail("open the browser")
    }

    fun youtubeSearch(query: String): String {
        val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            ok("Searching YouTube for \"$query\".")
        } else fail("open YouTube")
    }

    fun openUrl(raw: String): String {
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> "https://$raw"
        }
        return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            ok("Opening $url.")
        } else fail("open that link")
    }

    // ------------------------------------------------------------------
    // Communication
    // ------------------------------------------------------------------

    /**
     * WhatsApp a person.
     *
     * Ambiguity is surfaced instead of guessed: messaging the wrong "Papa" is
     * far worse than one clarifying question.
     */
    fun whatsapp(contact: String, message: String): String {
        when (val lookup = ContactResolver.lookup(app, contact)) {
            is ContactLookup.Ambiguous -> return ambiguityPrompt(contact, lookup.matches)
            is ContactLookup.None -> if (ContactResolver.hasPermission(app)) {
                // Named target that matched nobody is very likely a group chat.
                return whatsappGroup(contact, message)
            }
            else -> Unit
        }
        val number = resolveNumber(contact)
        val text = Uri.encode(message)
        val digits = number?.filter { it.isDigit() }
        val uri = if (!digits.isNullOrBlank()) {
            Uri.parse("https://wa.me/" + digits + "?text=" + text)
        } else {
            Uri.parse("https://wa.me/?text=" + text)
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")
        if (launch(intent)) {
            if (digits.isNullOrBlank()) {
                return ok("WhatsApp is open with your message ready, sir. Pick the contact.")
            }
            // Finish the send automatically when Accessibility is available;
            // otherwise the user still gets a pre-filled chat to tap.
            val automated = screen.autoSend(
                packageName = "com.whatsapp",
                viewIds = listOf(
                    "com.whatsapp:id/send",
                    "com.whatsapp:id/bottom_sheet_send_button",
                    "com.whatsapp:id/send_container"
                ),
                labels = listOf("Send", "send")
            )
            return ok(
                if (automated) "Sending \"$message\" to $contact on WhatsApp now, sir."
                else "WhatsApp chat with $contact is open — tap send, sir. " +
                    "Enable AURIX in Accessibility settings and I'll press send myself."
            )
        }
        return if (launch(Intent(Intent.ACTION_VIEW, uri))) {
            ok("Opening WhatsApp, sir.")
        } else fail("reach WhatsApp")
    }

    /**
     * WhatsApp a group (or any chat) by its visible name.
     *
     * Groups have no phone number, so wa.me cannot address them at all. The
     * only path is WhatsApp's own search box, driven through Accessibility.
     */
    fun whatsappGroup(chatName: String, message: String): String {
        val name = chatName.trim()
        if (name.isBlank()) return "Which chat should I message, sir?"
        if (message.isBlank()) return "What should I send to $name, sir?"

        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage("com.whatsapp")
        if (!launch(launcher)) return fail("reach WhatsApp")

        return if (screen.openChatAndSend(name, message)) {
            ok("Searching WhatsApp for \"$name\" and sending your message, sir.")
        } else {
            ok(
                "WhatsApp is open, sir — I need Accessibility to search for \"$name\" " +
                    "and send it myself. Turn it on from menu → Permissions & access."
            )
        }
    }

    fun sms(contact: String, message: String): String {
        when (val lookup = ContactResolver.lookup(app, contact)) {
            is ContactLookup.Ambiguous -> return ambiguityPrompt(contact, lookup.matches)
            else -> Unit
        }
        val number = resolveNumber(contact) ?: contact
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
            .putExtra("sms_body", message)
        if (!launch(intent)) return fail("open the messaging app")
        val automated = screen.autoSend(
            packageName = "com.google.android.apps.messaging",
            viewIds = listOf(
                "com.google.android.apps.messaging:id/send_message_button_icon",
                "com.google.android.apps.messaging:id/send_message_button"
            ),
            labels = listOf("Send SMS", "Send message", "Send")
        )
        return ok(
            if (automated) "Sending that SMS to $contact now, sir."
            else "SMS to $contact is drafted — tap send, sir."
        )
    }

    fun email(to: String, subject: String, body: String): String {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, if (to.isBlank()) emptyArray() else arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        return if (launch(intent)) ok("Email draft ready, sir.") else fail("open an email client")
    }

    fun call(contact: String): String {
        when (val lookup = ContactResolver.lookup(app, contact)) {
            is ContactLookup.Ambiguous -> return ambiguityPrompt(contact, lookup.matches)
            else -> Unit
        }
        val number = resolveNumber(contact) ?: contact.filter { it.isDigit() || it == '+' }
        if (number.isBlank()) return "I could not find a number for $contact, sir."
        val granted = ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val action = if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$number"))
        return if (launch(intent)) {
            if (granted) ok("Calling $contact.") else ok("Dialler ready for $contact, sir — tap call.")
        } else fail("place the call")
    }

    fun dialpad(): String =
        if (launch(Intent(Intent.ACTION_DIAL))) ok("Dialler open, sir.") else fail("open the dialler")

    fun callLog(): String {
        val intent = Intent(Intent.ACTION_VIEW).setType("vnd.android.cursor.dir/calls")
        return if (launch(intent)) ok("Call log open, sir.") else fail("open the call log")
    }

    fun contactLookup(name: String): String =
        when (val lookup = ContactResolver.lookup(app, name)) {
            is ContactLookup.RawNumber -> "That is already a number, sir: ${lookup.number}"
            is ContactLookup.Single ->
                lookup.match.name + " — " + lookup.match.number +
                    if (lookup.match.label.isNotBlank()) " (${lookup.match.label})" else ""
            is ContactLookup.Ambiguous ->
                "I found ${lookup.matches.size} matches for \"$name\", sir:\n" +
                    lookup.matches.joinToString("\n") { "• " + it.name + " — " + it.number }
            is ContactLookup.None -> "I found no contact matching \"$name\", sir — ${lookup.reason}."
        }

    /** Saves who to alert in an emergency. */
    fun setSosContact(contact: String): String {
        when (val lookup = ContactResolver.lookup(app, contact)) {
            is ContactLookup.RawNumber -> {
                SosStore.save(app, contact.trim(), lookup.number)
                return ok("SOS contact saved: ${lookup.number}, sir.")
            }
            is ContactLookup.Single -> {
                SosStore.save(app, lookup.match.name, lookup.match.number)
                return ok("SOS contact saved: ${lookup.match.name}, sir.")
            }
            is ContactLookup.Ambiguous -> return ambiguityPrompt(contact, lookup.matches)
            is ContactLookup.None ->
                return "I could not find \"$contact\", sir — ${lookup.reason}. " +
                    "Give me the number instead."
        }
    }

    /** Human-readable ambiguity question, used by every messaging path. */
    private fun ambiguityPrompt(query: String, matches: List<ContactMatch>): String =
        "I found more than one \"$query\", sir — which one?\n" +
            matches.joinToString("\n") { "• " + ContactResolver.describe(it) } +
            "\nSay the full name, or give me the number."

    /**
     * Emergency message to the saved SOS contact, with location when available.
     *
     * Falls back to the stored contact when the caller passes nothing, which is
     * what every caller actually does.
     */
    fun sos(sosContact: String? = null): String {
        val target = sosContact?.takeIf { it.isNotBlank() }
            ?: SosStore.contactNumber(app)
        val link = SosStore.locationLink(app)
        val message = "SOS! I need help. Sent by AURIX." +
            if (link != null) " My location: $link" else ""

        if (target.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
                .putExtra("sms_body", message)
            return if (launch(intent)) {
                "No SOS contact is saved yet, sir — I opened a message so you can pick one. " +
                    "Say \"set sos contact <name>\" and next time I'll send it straight away."
            } else fail("start an SOS message")
        }
        val name = SosStore.contactName(app) ?: target
        val result = sms(target, message)
        return if (link == null) {
            result + " Location was unavailable, so I sent the alert without it."
        } else result.replace(target, name)
    }

    /**
     * Single number for a spoken name.
     *
     * Now delegates to [ContactResolver], which scores candidates instead of
     * taking the provider's first row. Callers that care about ambiguity check
     * [ContactResolver.lookup] themselves before reaching here.
     */
    private fun resolveNumber(contact: String): String? =
        ContactResolver.bestNumber(app, contact)

    // ------------------------------------------------------------------
    // Media
    // ------------------------------------------------------------------

    private fun audio(): AudioManager? =
        app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun mediaKey(code: Int): Boolean {
        val am = audio() ?: return false
        return runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            true
        }.getOrDefault(false)
    }

    fun playPause(): String =
        if (mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) ok("Toggled playback, sir.")
        else fail("control playback")

    fun nextTrack(): String =
        if (mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)) ok("Next track, sir.") else fail("skip the track")

    fun previousTrack(): String =
        if (mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)) ok("Previous track, sir.")
        else fail("go back a track")

    fun stopPlayback(): String =
        if (mediaKey(KeyEvent.KEYCODE_MEDIA_STOP)) ok("Playback stopped, sir.")
        else fail("stop playback")

    fun setVolumePercent(percent: Int): String {
        val am = audio() ?: return fail("reach the audio service")
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent.coerceIn(0, 100) / 100).coerceIn(0, max)
        return runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            ok("Media volume set to ${percent.coerceIn(0, 100)}%, sir.")
        }.getOrElse { fail("change the volume") }
    }

    fun volumeUp(): String = stepVolume(AudioManager.ADJUST_RAISE, "up")

    fun volumeDown(): String = stepVolume(AudioManager.ADJUST_LOWER, "down")

    private fun stepVolume(direction: Int, word: String): String {
        val am = audio() ?: return fail("reach the audio service")
        return runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            ok("Volume $word, sir.")
        }.getOrElse { fail("change the volume") }
    }

    fun mute(): String {
        val am = audio() ?: return fail("reach the audio service")
        return runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            ok("Muted, sir.")
        }.getOrElse { fail("mute audio") }
    }

    fun unmute(): String {
        val am = audio() ?: return fail("reach the audio service")
        return runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            ok("Unmuted, sir.")
        }.getOrElse { fail("unmute audio") }
    }

    fun openMusicApp(): String {
        val intent = Intent(Intent.ACTION_VIEW).setType("audio/*")
        return if (launch(intent)) ok("Music library open, sir.") else fail("open a music app")
    }

    // ------------------------------------------------------------------
    // Device controls
    // ------------------------------------------------------------------

    fun torch(on: Boolean): String {
        val manager = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return fail("reach the camera service")
        return runCatching {
            val id = manager.cameraIdList.firstOrNull { camId ->
                manager.getCameraCharacteristics(camId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This device has no flash, sir."
            manager.setTorchMode(id, on)
            ok(if (on) "Flashlight on, sir." else "Flashlight off, sir.")
        }.getOrElse { fail("toggle the flashlight") }
    }

    fun battery(): String {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        if (level < 0) return fail("read the battery")
        val state = if (charging) "charging" else "on battery"
        return "Battery is at $level% and $state, sir."
    }

    fun deviceStatus(): String {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return buildString {
            append("Device report, sir:\n")
            append("• Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            append("• Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
            if (level >= 0) append("• Battery: $level%\n")
            append("• Charging: ${if (bm?.isCharging == true) "yes" else "no"}")
        }
    }

    fun setAlarm(hour: Int, minute: Int, label: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val pretty = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
        return if (launch(intent)) ok("Alarm set for $pretty, sir.") else fail("set the alarm")
    }

    fun setTimer(seconds: Int, label: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return if (launch(intent)) {
            ok("Timer running for ${seconds / 60}m ${seconds % 60}s, sir.")
        } else fail("start a timer")
    }

    fun openSettings(section: String): String {
        val action = when (section.lowercase().trim()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "data", "mobile data", "network" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notification", "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "airplane", "flight" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "hotspot", "tethering" -> Settings.ACTION_WIRELESS_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "date", "time" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (launch(Intent(action))) {
            ok("Settings opened, sir.")
        } else fail("open that settings page")
    }

    fun openAccessibilitySettings(): String = openSettings("accessibility")

    fun lockScreenHint(): String =
        "Screen lock needs a device-admin grant, sir. I opened Security so you can enable it."
            .also { openSettings("security") }

    // ------------------------------------------------------------------
    // Files, photos & camera
    // ------------------------------------------------------------------

    fun openCamera(): String {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        return if (launch(intent)) ok("Camera open, sir.") else fail("open the camera")
    }

    fun openGallery(): String {
        val intent = Intent(Intent.ACTION_VIEW).setType("image/*")
        return if (launch(intent)) ok("Gallery open, sir.") else fail("open the gallery")
    }

    fun openFiles(): String {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
        return if (launch(intent)) ok("File picker open, sir.") else fail("open a file manager")
    }

    fun shareText(text: String): String {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        return if (launch(Intent.createChooser(intent, "Share"))) {
            ok("Share sheet open, sir.")
        } else fail("open the share sheet")
    }

    // ------------------------------------------------------------------
    // Maps & navigation
    // ------------------------------------------------------------------

    fun navigate(destination: String): String {
        if (destination.isBlank()) return "Where should I navigate to, sir?"
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(destination))
        if (launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))) {
            return ok("Navigating to $destination, sir.")
        }
        val web = Uri.parse(
            "https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination)
        )
        return if (launch(Intent(Intent.ACTION_VIEW, web))) {
            ok("Route to $destination opened, sir.")
        } else fail("open maps")
    }

    fun findNearby(place: String): String {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(place))
        return if (launch(Intent(Intent.ACTION_VIEW, uri))) {
            ok("Showing nearby $place, sir.")
        } else fail("open maps")
    }

    fun openMaps(): String = findNearby("")

    /** Opens the app's own info screen (permissions, storage, force stop). */
    fun openAppInfo(): String {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${app.packageName}"))
        return if (launch(intent)) ok("App info open, sir.") else fail("open app info")
    }

    /** Explicit component launch used by the automation layer. */
    fun launchComponent(packageName: String, className: String): String {
        val intent = Intent().setComponent(ComponentName(packageName, className))
        return if (launch(intent)) ok("Launched $className, sir.") else fail("launch that screen")
    }
}
