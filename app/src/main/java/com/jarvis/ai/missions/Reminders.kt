package com.jarvis.ai.missions

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.ai.MainActivity
import java.util.Calendar

/**
 * Natural-language reminders, scheduled as exact alarms and delivered as a
 * notification with the reminder text.
 *
 * Example commands handled by ReminderCommands:
 *   "remind me to call HR tomorrow at 9 am"
 *   "remind me to take pills at 21:30"
 *   "remind me in 20 minutes to stretch"
 *
 * Design notes:
 *  - Android 13+ needs POST_NOTIFICATIONS granted at runtime; without it the
 *    alarm still fires (silently logged), so nothing is lost.
 *  - Scheduling degrades to inexact when exact-alarm access is not granted on
 *    Android 12+ (mirrors MissionScheduler behaviour).
 *  - ReminderCommands is a pure-Kotlin object (Context-free parsing) so the
 *    utterance grammar is unit-testable on the JVM.
 *  - The store keeps "fireAt|text" per reminder, so re-arming after a reboot
 *    needs no re-parsing of already-stripped text.
 */
object ReminderStore {

    const val CHANNEL_ID = "aurix_reminders"
    const val EXTRA_TEXT = "reminder_text"
    const val ACTION_FIRE = "com.aurix.ai.action.FIRE_REMINDER"
    private const val PREFS = "aurix_reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AURIX reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Reminders AURIX scheduled for you"
                }
            )
        }
    }

    fun notificationId(text: String): Int =
        ("aurix_reminder:" + text.trim().lowercase()).hashCode()

    fun buildNotification(context: Context, text: String): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            notificationId(text),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("AURIX reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    /** Persists "fireAt|text" so a reboot can re-arm without re-parsing. */
    fun rememberScheduled(context: Context, text: String, fireAt: Long) {
        prefs(context).edit()
            .putString("r_" + notificationId(text), fireAt.toString() + "|" + text)
            .apply()
    }

    fun forgetScheduled(context: Context, text: String) {
        prefs(context).edit().remove("r_" + notificationId(text)).apply()
    }

    /** Every pending reminder as (fireAt, text), soonest first. */
    fun listAll(context: Context): List<Pair<Long, String>> =
        prefs(context).all.mapNotNull { (key, value) ->
            if (!key.startsWith("r_") || value !is String) return@mapNotNull null
            val separator = value.indexOf('|')
            if (separator <= 0) return@mapNotNull null
            val fireAt = value.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
            val text = value.substring(separator + 1).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            fireAt to text
        }.sortedBy { it.first }

    /** Cancels a scheduled reminder by its exact text. */
    fun cancel(context: Context, text: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            alarm.cancel(
                PendingIntent.getBroadcast(
                    context,
                    notificationId(text),
                    Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        forgetScheduled(context, text)
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Re-arms every stored reminder after a reboot. Called by MissionBootReceiver. */
    fun rearmAll(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val now = System.currentTimeMillis()
        for ((key, value) in prefs(context).all) {
            if (!key.startsWith("r_") || value !is String) continue
            val separator = value.indexOf('|')
            if (separator <= 0) continue
            val fireAt = value.substring(0, separator).toLongOrNull() ?: continue
            val text = value.substring(separator + 1)
            if (text.isBlank()) continue
            if (fireAt <= now) {
                // Due while the device was off: deliver late instead of dropping it.
                fire(context, text)
                forgetScheduled(context, text)
            } else {
                scheduleAlarm(alarm, context, text, fireAt)
            }
        }
    }

    /** Full schedule path: persist + arm. */
    fun schedule(context: Context, text: String, fireAt: Long) {
        rememberScheduled(context, text, fireAt)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        scheduleAlarm(alarm, context, text, fireAt)
    }

    fun scheduleAlarm(alarm: AlarmManager, context: Context, text: String, fireAt: Long) {
        val pending = PendingIntent.getBroadcast(
            context,
            notificationId(text),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
            }
        }
    }

    /** Fires the notification when POST_NOTIFICATIONS is granted; never throws. */
    fun fire(context: Context, text: String) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val postable = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (postable) {
            runCatching { manager.notify(notificationId(text), buildNotification(context, text)) }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        if (intent?.action != ReminderStore.ACTION_FIRE) return
        val text = intent.getStringExtra(ReminderStore.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        ReminderStore.fire(ctx, text)
        ReminderStore.forgetScheduled(ctx, text)
    }
}

/**
 * Pure parser for natural-language reminder phrases. No Android imports so it
 * stays unit-testable on the JVM. Returns the reminder text + a resolver that
 * computes the absolute fire time from "now".
 */
object ReminderCommands {

    data class Parsed(val text: String, val fireAt: (now: Long) -> Long, val summary: String)

    private val RELATIVE = Regex(
        "(\\d{1,3})\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b", RegexOption.IGNORE_CASE
    )
    private val CLOCK = Regex("\\b(\\d{1,2})[:.](\\d{2})\\b")
    private val MERIDIEM = Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)\\b", RegexOption.IGNORE_CASE)
    private val BAJE = Regex("\\b(\\d{1,2})\\s*baje\\b", RegexOption.IGNORE_CASE)
    private val BARE_AT = Regex("\\bat\\s+(\\d{1,2})\\b(?!\\s*[:.]\\d)", RegexOption.IGNORE_CASE)
    private val NIGHT_WORD = Regex("\\b(raat|shaam|sham|night|evening)\\b", RegexOption.IGNORE_CASE)
    private val DAY_WORD = Regex("\\b(tomorrow|kal|aaj|today|tonight)\\b", RegexOption.IGNORE_CASE)
    private val TRIGGER = Regex(
        "^\\s*(please\\s+)?(aurix[\\s,]*)?remind me( to | that | about | )", RegexOption.IGNORE_CASE
    )

    /** Returns null when the input is not a reminder command with a time. */
    fun parse(raw: String): Parsed? {
        val lower = raw.trim().lowercase()
        if (!lower.contains("remind")) return null
        val body = TRIGGER.replaceFirst(lower, "")
            .replace(Regex("^\\s*remind me\\s*"), "")
            .trim()
        if (body.isEmpty()) return null

        // --- relative delays: "in 20 minutes", "after 2 hours" ---
        RELATIVE.find(body)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@let
            val unit = m.groupValues[2].lowercase()
            val millis = when {
                unit.startsWith("sec") -> n * 1_000L
                unit.startsWith("min") -> n * 60_000L
                else -> n * 3_600_000L
            }
            val unitLabel = when {
                unit.startsWith("sec") -> "second" + if (n == 1L) "" else "s"
                unit.startsWith("min") -> "minute" + if (n == 1L) "" else "s"
                else -> "hour" + if (n == 1L) "" else "s"
            }
            return Parsed(
                text = body.replaceFirst(m.value, " ")
                    .replace(Regex("\\b(in|after)\\b"), " ")
                    .collapse()
                    .ifBlank { "your reminder" },
                fireAt = { now -> now + millis },
                summary = "in $n $unitLabel"
            )
        }

        // --- clock times: "9 am", "21:30", "9.30 pm" ---
        val meridiem = MERIDIEM.find(body)
        val clock = CLOCK.find(body)
        val baje = if (meridiem == null && clock == null) BAJE.find(body) else null
        // Bare "at 9" — only consulted when no other time form matched, so it
        // never competes with "9:30", "9.30 pm" or "9 baje".
        val bareAt = if (meridiem == null && clock == null && baje == null) BARE_AT.find(body) else null
        val hour: Int
        val minute: Int
        var dayOffset = 0
        val hadMeridiem: Boolean
        when {
            meridiem != null -> {
                hadMeridiem = true
                val h12 = meridiem.groupValues[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
                minute = meridiem.groupValues[2].toIntOrNull()?.takeIf { it in 0..59 } ?: 0
                val pm = meridiem.groupValues[3].equals("pm", ignoreCase = true)
                hour = when {
                    pm && h12 != 12 -> h12 + 12
                    !pm && h12 == 12 -> 0
                    else -> h12
                }
            }
            clock != null -> {
                hadMeridiem = false
                hour = clock.groupValues[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
                minute = clock.groupValues[2].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            }
            baje != null -> {
                // Hinglish: "kal 9 baje", "raat 9 baje" → 9:00 / 21:00
                hadMeridiem = false
                val h = baje.groupValues[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
                hour = if (NIGHT_WORD.containsMatchIn(body)) h + 12 else h
                minute = 0
            }
            bareAt != null -> {
                // "tonight at 9", "at 9" → hour with zero minutes
                hadMeridiem = false
                hour = bareAt.groupValues[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
                minute = 0
            }
            else -> return null
        }
        if (body.contains("tomorrow") || body.contains("kal")) dayOffset = 1

        // Bare hour + "tonight" means the evening of the parsed hour.
        val effectiveHour = if (!hadMeridiem && lower.contains("tonight") && hour in 1..11) hour + 12 else hour

        val text = stripTimeWords(body)
        val summary = when {
            dayOffset == 1 -> "tomorrow at %02d:%02d".format(effectiveHour, minute)
            lower.contains("tonight") -> "tonight at %02d:%02d".format(effectiveHour, minute)
            else -> "today at %02d:%02d".format(effectiveHour, minute)
        }
        return Parsed(text, { now -> calAt(now, effectiveHour, minute, dayOffset) }, summary)
    }

    private fun stripTimeWords(s: String): String = s
        .replace(MERIDIEM, " ")
        .replace(CLOCK, " ")
        .replace(BAJE, " ")
        .replace(BARE_AT, " ")
        .replace(DAY_WORD, " ")
        .replace(NIGHT_WORD, " ")
        .replace(Regex("\\bremind me\\b(\\s+to|\\s+that|\\s+about)?"), " ")
        .replace(Regex("\\b(in|after|at|on|me|baje)\\b"), " ")
        .collapse()
        .ifBlank { "your reminder" }

    private fun String.collapse(): String =
        replace(Regex("\\s+"), " ").trim().trimEnd(',', '.', '-', '\u2013')

    private fun calAt(now: Long, hour: Int, minute: Int, dayOffset: Int): Long =
        (Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (dayOffset > 0) add(Calendar.DAY_OF_YEAR, dayOffset)
            else if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }).timeInMillis
}
