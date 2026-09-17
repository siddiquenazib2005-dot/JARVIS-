package com.jarvis.ai.notifications

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One captured notification. */
data class CapturedNotification(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long
)

/**
 * In-memory ring buffer of recent notifications plus the most recent OTP.
 *
 * Kept in memory only: OTPs and message previews are sensitive, so nothing is
 * written to disk. The buffer is cleared when the process dies.
 */
object NotificationStore {

    private const val MAX_ITEMS = 60
    private const val OTP_TTL_MS = 10 * 60 * 1000L

    private val items = ArrayDeque<CapturedNotification>()

    @Volatile
    private var lastOtp: String? = null

    @Volatile
    private var lastOtpAt: Long = 0L

    @Volatile
    private var lastOtpSource: String = ""

    @Synchronized
    fun add(item: CapturedNotification) {
        items.addLast(item)
        while (items.size > MAX_ITEMS) items.removeFirst()
        extractOtp(item.title + " " + item.text)?.let {
            lastOtp = it
            lastOtpAt = item.postedAt
            lastOtpSource = item.appLabel.ifBlank { item.packageName }
        }
    }

    @Synchronized
    fun recent(limit: Int = 10): List<CapturedNotification> =
        items.toList().takeLast(limit).reversed()

    @Synchronized
    fun clear() {
        items.clear()
        lastOtp = null
    }

    /** Most recent OTP, if one arrived in the last ten minutes. */
    fun latestOtp(): Pair<String, String>? {
        val code = lastOtp ?: return null
        if (System.currentTimeMillis() - lastOtpAt > OTP_TTL_MS) return null
        return code to lastOtpSource
    }

    /**
     * Pulls a 4-8 digit code out of a message when the surrounding words look
     * like a verification message. Avoids matching amounts, dates and phone
     * numbers by requiring an OTP keyword nearby.
     */
    fun extractOtp(raw: String): String? {
        val text = raw.lowercase()
        val keywords = listOf(
            "otp", "one time password", "one-time password", "verification code",
            "verify", "security code", "login code", "passcode", "auth code", "2fa"
        )
        if (keywords.none { text.contains(it) }) return null
        val match = Regex("\\b(\\d{4,8})\\b").find(raw) ?: return null
        return match.groupValues[1]
    }

    private val stamp = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Human-readable digest for the chat layer. */
    fun summary(limit: Int = 8): String {
        val recent = recent(limit)
        if (recent.isEmpty()) {
            return "No notifications captured yet, sir. Grant notification access " +
                "and I'll start reading them."
        }
        return "Recent notifications, sir:\n" + recent.joinToString("\n") {
            val time = stamp.format(Date(it.postedAt))
            val body = it.text.take(70).ifBlank { it.title }
            "• [$time] ${it.appLabel}: $body"
        }
    }
}

/**
 * Notification listener that feeds [NotificationStore].
 *
 * Requires the user to grant "Notification access" in system settings; until
 * then Android never binds the service, so this class is inert and safe.
 */
class AurixNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        runCatching {
            val extras = notification.notification?.extras ?: return
            val title = extras.getCharSequence("android.title")?.toString().orEmpty()
            val text = extras.getCharSequence("android.text")?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return
            NotificationStore.add(
                CapturedNotification(
                    packageName = notification.packageName.orEmpty(),
                    appLabel = appLabel(notification.packageName.orEmpty()),
                    title = title,
                    text = text,
                    postedAt = notification.postTime
                )
            )
        }
    }

    private fun appLabel(packageName: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))

    companion object {

        /** True when the user granted notification access to AURIX. */
        fun isEnabled(context: Context): Boolean = runCatching {
            val expected = ComponentName(context, AurixNotificationListener::class.java)
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            flat.split(":").any { it.equals(expected.flattenToString(), ignoreCase = true) }
        }.getOrDefault(false)

        /** Opens the notification-access settings page. */
        fun requestAccess(context: Context): String = runCatching {
            context.startActivity(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "I opened notification access, sir — switch AURIX on and I'll read " +
                "your notifications and OTPs."
        }.getOrElse {
            "Enable AURIX under Settings → Notification access, sir."
        }
    }
}
