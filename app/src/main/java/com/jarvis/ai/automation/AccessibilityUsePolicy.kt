package com.jarvis.ai.automation

/**
 * Central rule for when AURIX may use Accessibility.
 *
 * Native Android APIs always win. Accessibility is reserved for UI semantics
 * that Android does not expose through an official API (tap/type/scroll and
 * third-party app flows such as WhatsApp group search). It must never be used
 * to press Send in the system SMS app: direct SMS uses SmsManager and the safe
 * fallback is an explicit user-controlled draft.
 */
object AccessibilityUsePolicy {

    enum class Channel {
        NATIVE_API,
        ACCESSIBILITY_UI,
        USER_CONTROLLED_INTENT
    }

    private val nativeMessagingPackages = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.android.messaging"
    )

    fun channelForUiSend(packageName: String): Channel =
        if (packageName.lowercase() in nativeMessagingPackages) {
            Channel.USER_CONTROLLED_INTENT
        } else {
            Channel.ACCESSIBILITY_UI
        }

    fun mayUseAccessibilityForUiSend(packageName: String): Boolean =
        channelForUiSend(packageName) == Channel.ACCESSIBILITY_UI
}
