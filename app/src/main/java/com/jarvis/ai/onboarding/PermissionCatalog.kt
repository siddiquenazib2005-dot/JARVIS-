package com.jarvis.ai.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.notifications.AurixNotificationListener
import com.jarvis.ai.overlay.FloatingAvatarService

/**
 * Single source of truth for every permission AURIX asks for.
 *
 * Two very different Android mechanisms are unified behind one shape:
 *  - [PermissionKind.Runtime] -> normal dangerous permission, granted by a
 *    system dialog we can launch in-app.
 *  - [PermissionKind.SpecialAccess] -> Accessibility / notification listener /
 *    overlay. These can only be toggled by the user on a Settings screen, so we
 *    open the screen and re-check status afterwards.
 *
 * Every entry carries a plain-language "why" and the concrete AURIX feature
 * that stops working without it, because the previous build failed silently:
 * WhatsApp auto-send, screen automation and OTP reading all no-op when their
 * access is off, and nothing told the owner why.
 *
 * This file only reads status and opens the relevant screen. It never changes
 * the behaviour of the modules it inspects.
 */
sealed interface PermissionKind {
    /** Standard runtime permission, requestable with a system dialog. */
    data class Runtime(val permission: String) : PermissionKind

    /** Settings-screen toggle: we can only check it and navigate there. */
    data class SpecialAccess(
        val status: (Context) -> Boolean,
        val open: (Context) -> Unit
    ) : PermissionKind
}

/**
 * One onboarding card.
 *
 * @param id stable key persisted in [OnboardingPrefs] skip history.
 * @param title short human label.
 * @param why plain-language reason, shown on the card.
 * @param unlocks the AURIX feature this enables, shown as a subtitle.
 * @param required true when core features break without it (skip is still
 *        allowed, but the chat screen keeps warning).
 */
data class PermissionItem(
    val id: String,
    val title: String,
    val why: String,
    val unlocks: String,
    val required: Boolean,
    val kind: PermissionKind
) {
    /**
     * Live grant state.
     *
     * `kind` is captured into a local val first: Kotlin will not smart-cast a
     * class property inside a lambda, so `runCatching { kind.status(...) }`
     * does not compile without it.
     */
    fun isGranted(context: Context): Boolean = when (val current = kind) {
        is PermissionKind.Runtime -> ContextCompat.checkSelfPermission(
            context, current.permission
        ) == PackageManager.PERMISSION_GRANTED

        is PermissionKind.SpecialAccess -> runCatching { current.status(context) }
            .getOrDefault(false)
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

    /**
     * Ordered as the wizard presents them: highest impact first, so an owner
     * who abandons the flow early still ends up with a working assistant.
     */
    fun items(): List<PermissionItem> = buildList {
        add(
            PermissionItem(
                id = ID_ACCESSIBILITY,
                title = "Accessibility (screen control)",
                why = "This is the big one. It lets AURIX read what is on screen and " +
                    "tap buttons for you — that is how a WhatsApp message actually gets " +
                    "sent instead of sitting in the box waiting for your thumb.",
                unlocks = "WhatsApp/SMS auto-send · screen automation · missions",
                required = true,
                kind = PermissionKind.SpecialAccess(
                    status = { JarvisAccessibilityService.isAccessibilityServiceEnabled(it) },
                    open = { ctx ->
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            )
        )
        add(
            PermissionItem(
                id = ID_MIC,
                title = "Microphone",
                why = "Needed for wake-word listening and voice commands. Without it " +
                    "AURIX is text-only and the mic button does nothing.",
                unlocks = "Voice commands · wake word · hands-free mode",
                required = true,
                kind = PermissionKind.Runtime(Manifest.permission.RECORD_AUDIO)
            )
        )
        add(
            PermissionItem(
                id = ID_CONTACTS,
                title = "Contacts",
                why = "Lets AURIX turn a name into a number. Say \"message Nazib\" and " +
                    "it finds the contact instead of asking you to type digits.",
                unlocks = "Name-based calling, WhatsApp and SMS",
                required = true,
                kind = PermissionKind.Runtime(Manifest.permission.READ_CONTACTS)
            )
        )
        add(
            PermissionItem(
                id = ID_SMS,
                title = "Send SMS",
                why = "Lets AURIX send texts and fire the SOS message without you " +
                    "opening the messaging app first.",
                unlocks = "SMS sending · SOS alert",
                required = false,
                kind = PermissionKind.Runtime(Manifest.permission.SEND_SMS)
            )
        )
        add(
            PermissionItem(
                id = ID_PHONE,
                title = "Phone calls",
                why = "With this, \"call Nazib\" dials straight away. Without it AURIX " +
                    "can only open the dialler and you tap call yourself.",
                unlocks = "Direct calling",
                required = false,
                kind = PermissionKind.Runtime(Manifest.permission.CALL_PHONE)
            )
        )
        add(
            PermissionItem(
                id = ID_LOCATION,
                title = "Location",
                why = "Attaches your location to an SOS message so help knows where " +
                    "to go. Only read when you trigger SOS.",
                unlocks = "SOS with location",
                required = false,
                kind = PermissionKind.Runtime(Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        )
        add(
            PermissionItem(
                id = ID_NOTIFICATION_ACCESS,
                title = "Notification access",
                why = "Lets AURIX read incoming notifications so it can summarise them " +
                    "and pull OTP codes out automatically.",
                unlocks = "Notification summary · OTP auto-read",
                required = false,
                kind = PermissionKind.SpecialAccess(
                    status = { AurixNotificationListener.isEnabled(it) },
                    open = { ctx -> AurixNotificationListener.requestAccess(ctx) }
                )
            )
        )
        add(
            PermissionItem(
                id = ID_OVERLAY,
                title = "Display over other apps",
                why = "Needed for the floating AURIX bubble that stays on screen while " +
                    "you use other apps.",
                unlocks = "Floating assistant bubble",
                required = false,
                kind = PermissionKind.SpecialAccess(
                    status = { FloatingAvatarService.canDrawOverlay(it) },
                    open = { ctx ->
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + ctx.packageName)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                PermissionItem(
                    id = ID_POST_NOTIFICATIONS,
                    title = "Show notifications",
                    why = "Lets AURIX post its own notifications — mission results, " +
                        "reminders and the bubble's foreground notice.",
                    unlocks = "Mission alerts · bubble service",
                    required = false,
                    kind = PermissionKind.Runtime(Manifest.permission.POST_NOTIFICATIONS)
                )
            )
        }
    }

    /** Runtime permissions only — safe to batch into one system dialog. */
    fun runtimePermissions(): List<String> = items()
        .mapNotNull { (it.kind as? PermissionKind.Runtime)?.permission }

    /** Items still not granted, in catalog order. */
    fun missing(context: Context): List<PermissionItem> =
        items().filterNot { it.isGranted(context) }

    /** Required items still not granted — these drive the chat warning bar. */
    fun missingRequired(context: Context): List<PermissionItem> =
        missing(context).filter { it.required }

    /** Convenience for the "is Accessibility ON?" check surfaced in-app. */
    fun isAccessibilityOn(context: Context): Boolean =
        JarvisAccessibilityService.isAccessibilityServiceEnabled(context)

    fun byId(id: String): PermissionItem? = items().firstOrNull { it.id == id }
}
