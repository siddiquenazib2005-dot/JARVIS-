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
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat

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

    // ------------------------------------------------------------------
    // Launching helpers
    // ------------------------------------------------------------------

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        false
    }

    private fun ok(text: String) = text

    private fun fail(what: String) = "I could not $what on this device, sir."

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

    fun whatsapp(contact: String, message: String): String {
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
            return ok(
                if (!digits.isNullOrBlank()) "WhatsApp chat with $contact is open — tap send, sir."
                else "WhatsApp is open with your message ready, sir. Pick the contact."
            )
        }
        return if (launch(Intent(Intent.ACTION_VIEW, uri))) {
            ok("Opening WhatsApp, sir.")
        } else fail("reach WhatsApp")
    }

    fun sms(contact: String, message: String): String {
        val number = resolveNumber(contact) ?: contact
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
            .putExtra("sms_body", message)
        return if (launch(intent)) {
            ok("SMS to $contact is drafted — tap send, sir.")
        } else fail("open the messaging app")
    }

    fun email(to: String, subject: String, body: String): String {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, if (to.isBlank()) emptyArray() else arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        return if (launch(intent)) ok("Email draft ready, sir.") else fail("open an email client")
    }

    fun call(contact: String): String {
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

    fun contactLookup(name: String): String {
        val number = resolveNumber(name)
            ?: return "I found no contact matching \"$name\", sir."
        return "$name — $number"
    }

    /** Emergency broadcast: drafts one SMS to the stored SOS contact. */
    fun sos(sosContact: String?): String {
        val message = "SOS! I need help. Sent by AURIX."
        if (sosContact.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
                .putExtra("sms_body", message)
            return if (launch(intent)) {
                "No SOS contact is saved yet, sir — I opened a message so you can pick one."
            } else fail("start an SOS message")
        }
        return sms(sosContact, message)
    }

    private fun resolveNumber(contact: String): String? {
        val query = contact.trim()
        if (query.isBlank()) return null
        if (query.count { it.isDigit() } >= 6) return query
        val granted = ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query)
        )
        return runCatching {
            app.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

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

    // ------------------------------------------------------------------
    // PC connect
    // ------------------------------------------------------------------

    fun pcBridgeInfo(port: Int): String = buildString {
        append("PC Connect bridge, sir:\n")
        append("• Listening port: $port\n")
        append("• Connect from your PC on the same Wi-Fi\n")
        append("• Pair from Settings → PC Connect")
    }

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
