package com.jarvis.ai.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.notifications.AurixNotificationListener
import com.jarvis.ai.overlay.FloatingAvatarService

/** Single source of truth for every permission and special access AURIX requests. */
sealed interface PermissionKind {
    data class Runtime(val permission: String) : PermissionKind
    data class SpecialAccess(
        val status: (Context) -> Boolean,
        val open: (Context) -> Unit
    ) : PermissionKind
}

data class PermissionItem(
    val id: String,
    val title: String,
    val why: String,
    val unlocks: String,
    val required: Boolean,
    val kind: PermissionKind
) {
    fun isGranted(context: Context): Boolean = when (val current = kind) {
        is PermissionKind.Runtime -> ContextCompat.checkSelfPermission(
            context, current.permission
        ) == PackageManager.PERMISSION_GRANTED
        is PermissionKind.SpecialAccess -> runCatching { current.status(context) }.getOrDefault(false)
    }
}

object PermissionCatalog {
    const val ID_ACCESSIBILITY = "accessibility"
    const val ID_NOTIFICATION_ACCESS = "notification_access"
    const val ID_OVERLAY = "overlay"
    const val ID_MIC = "mic"
    const val ID_CONTACTS = "contacts"
    const val ID_SMS = "sms"
    const val ID_PHONE = "phone"
    const val ID_POST_NOTIFICATIONS = "post_notifications"
    const val ID_LOCATION = "location"

    fun items(): List<PermissionItem> = buildList {
        add(PermissionItem(
            id = ID_ACCESSIBILITY,
            title = "Accessibility (screen control)",
            why = "Lets AURIX read the visible screen and perform UI-only actions when Android provides no supported direct API. Enabling the switch is not enough: AURIX also verifies that the service is connected.",
            unlocks = "WhatsApp UI send · screen automation · UI-only mission steps",
            required = true,
            kind = PermissionKind.SpecialAccess(
                status = { JarvisAccessibilityService.isConnected() },
                open = { ctx ->
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            )
        ))
        add(PermissionItem(
            ID_MIC, "Microphone",
            "Needed only while you explicitly use voice, hands-free, or wake-word listening.",
            "Voice commands · wake word · hands-free mode", true,
            PermissionKind.Runtime(Manifest.permission.RECORD_AUDIO)
        ))
        add(PermissionItem(
            ID_CONTACTS, "Contacts",
            "Resolves a spoken name into candidate phone numbers. Ambiguous matches must be confirmed before communication.",
            "Name-based calling, WhatsApp and SMS", true,
            PermissionKind.Runtime(Manifest.permission.READ_CONTACTS)
        ))
        add(PermissionItem(
            ID_SMS, "Send SMS",
            "Allows direct SMS through Android's SmsManager after explicit confirmation. Accessibility is never used to send SMS.",
            "Verified native SMS dispatch · SOS alert", false,
            PermissionKind.Runtime(Manifest.permission.SEND_SMS)
        ))
        add(PermissionItem(
            ID_PHONE, "Phone calls",
            "Allows a confirmed call to be placed directly. Without it AURIX only opens the dialler.",
            "Direct calling", false,
            PermissionKind.Runtime(Manifest.permission.CALL_PHONE)
        ))
        add(PermissionItem(
            ID_LOCATION, "Location",
            "Reads location only when you trigger SOS so the alert can include where you are.",
            "SOS with location", false,
            PermissionKind.Runtime(Manifest.permission.ACCESS_COARSE_LOCATION)
        ))
        add(PermissionItem(
            ID_NOTIFICATION_ACCESS, "Notification access",
            "Allows notification summaries and OTP extraction. Notification contents stay on device unless you explicitly include them in a request.",
            "Notification summary · OTP auto-read", false,
            PermissionKind.SpecialAccess(
                status = { AurixNotificationListener.isEnabled(it) },
                open = { ctx -> AurixNotificationListener.requestAccess(ctx) }
            )
        ))
        add(PermissionItem(
            ID_OVERLAY, "Display over other apps",
            "Shows the user-enabled floating AURIX companion. The overlay itself never starts the microphone.",
            "Floating AURIX companion", false,
            PermissionKind.SpecialAccess(
                status = { FloatingAvatarService.canDrawOverlay(it) },
                open = { ctx -> FloatingAvatarService.requestPermission(ctx) }
            )
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermissionItem(
                ID_POST_NOTIFICATIONS, "Show notifications",
                "Shows mission results, reminders, and required foreground-service controls.",
                "Mission alerts · companion controls", false,
                PermissionKind.Runtime(Manifest.permission.POST_NOTIFICATIONS)
            ))
        }
    }

    fun runtimePermissions(): List<String> = items().mapNotNull { (it.kind as? PermissionKind.Runtime)?.permission }
    fun missing(context: Context): List<PermissionItem> = items().filterNot { it.isGranted(context) }
    fun missingRequired(context: Context): List<PermissionItem> = missing(context).filter { it.required }
    fun isAccessibilityOn(context: Context): Boolean = JarvisAccessibilityService.isConnected()
    fun byId(id: String): PermissionItem? = items().firstOrNull { it.id == id }
}
