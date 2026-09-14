package com.jarvis.ai.automation

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.ai.accessibility.A11yResult
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.provider.SecretRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Accessibility wrapper used only when no supported Android API can perform the UI action. */
class ScreenAutomation(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Enabled-but-disconnected is unhealthy and must never be treated as ready. */
    fun isReady(): Boolean = JarvisAccessibilityService.isConnected()

    fun report(): String = JarvisAccessibilityService.diagnosticsReport(app)

    fun requestPermission(): String {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            app.startActivity(intent)
            if (JarvisAccessibilityService.isAccessibilityServiceEnabled(app)) {
                "AURIX Accessibility is enabled but not connected. Turn it off and on once, then return and retry."
            } else {
                "Enable AURIX under Settings → Accessibility, return here, then retry the action."
            }
        }.getOrElse {
            "Open Settings → Accessibility and enable AURIX, then retry."
        }
    }

    private fun dispatch(
        attempting: String,
        what: String,
        block: suspend (JarvisAccessibilityService) -> A11yResult
    ): String {
        val service = JarvisAccessibilityService.instance ?: return requestPermission()
        scope.launch {
            var last: A11yResult? = null
            for (attempt in 0 until MAX_ATTEMPTS) {
                if (attempt > 0) delay(BACKOFF_MS[attempt - 1])
                last = runCatching { block(service) }.getOrNull()
                if (last?.isSuccess == true || last?.needsConfirmation == true) break
                if (last?.isFailure == true && !last.retryable) break
            }
            when {
                last == null -> AutomationFeedback.report("Could not $what: screen control returned no result.")
                last.isSuccess && last.verificationStatus == com.jarvis.ai.accessibility.VerificationStatus.VERIFIED ->
                    AutomationFeedback.report("Verified: $what completed.")
                last.isSuccess -> AutomationFeedback.report("Dispatched but not verified: $what. Check the screen before relying on it.")
                last.needsConfirmation -> AutomationFeedback.report("Stopped before $what because another confirmation is required.")
                else -> {
                    val reason = last.message?.takeIf { it.isNotBlank() }
                        ?: last.errorCode?.name?.lowercase()?.replace('_', ' ')
                        ?: "nothing matched on screen"
                    AutomationFeedback.report("Could not $what after $MAX_ATTEMPTS attempts: ${SecretRedactor.redact(reason)}.")
                }
            }
        }
        return attempting
    }

    fun tap(target: String): String = dispatch("Attempting to tap “$target”…", "tap “$target”") { it.tapByText(target) }
    fun longPress(target: String): String = dispatch("Attempting to long-press “$target”…", "long-press “$target”") { it.longPressByText(target) }
    fun type(value: String): String = dispatch("Attempting to enter text…", "enter text") { it.typeText(value) }
    fun scroll(down: Boolean): String = dispatch(
        if (down) "Attempting to scroll down…" else "Attempting to scroll up…",
        if (down) "scroll down" else "scroll up"
    ) { it.performScroll(down) }
    fun swipe(direction: String): String = dispatch("Attempting to swipe $direction…", "swipe $direction") { it.swipe(direction) }
    fun scrollUntil(text: String): String = dispatch("Looking for “$text”…", "find “$text”") { it.scrollUntilText(text) }

    fun back(): String = immediate("go back") { it.pressBack() }
    fun home(): String = immediate("go to the Home screen") { it.pressHome() }
    fun recents(): String = immediate("open Recent apps") { it.pressRecents() }
    fun lock(): String = immediate("lock the screen") { it.lockScreen() }

    private fun immediate(label: String, action: (JarvisAccessibilityService) -> A11yResult): String {
        val service = JarvisAccessibilityService.instance ?: return requestPermission()
        return runCatching { action(service) }.fold(
            onSuccess = { result ->
                if (result.isSuccess) {
                    if (result.verificationStatus == com.jarvis.ai.accessibility.VerificationStatus.VERIFIED) "Verified: $label completed."
                    else "Dispatched but not verified: $label."
                } else "Could not $label: ${SecretRedactor.redact(result.message ?: result.errorCode?.name)}."
            },
            onFailure = { "Could not $label: ${SecretRedactor.redact(it.message)}." }
        )
    }

    /**
     * UI sends are asynchronous. Returning true used to be interpreted as
     * “message sent” before the delayed tap even ran. Until an observable
     * post-send state is confirmed this method deliberately returns false, so
     * callers accurately describe a prepared draft instead of inventing success.
     */
    fun autoSend(packageName: String, viewIds: List<String>, labels: List<String>): Boolean {
        if (!AccessibilityUsePolicy.mayUseAccessibilityForUiSend(packageName)) return false
        val service = JarvisAccessibilityService.instance ?: return false
        if (!isReady()) return false
        scope.launch {
            val packageReady = runCatching { service.waitForPackage(packageName, timeoutMs = 6000) }.getOrNull()
            if (packageReady?.isSuccess != true) {
                AutomationFeedback.report("Could not verify that the target app opened; message was not reported as sent.")
                return@launch
            }
            delay(700)
            val tapped = viewIds.firstNotNullOfOrNull { id ->
                runCatching { service.tapById(id) }.getOrNull()?.takeIf { it.isSuccess }
            } ?: labels.firstNotNullOfOrNull { label ->
                runCatching { service.tapByText(label) }.getOrNull()?.takeIf { it.isSuccess }
            }
            if (tapped == null) {
                AutomationFeedback.report("Message is prepared, but the Send control was not found. Tap Send manually.")
            } else {
                AutomationFeedback.report("Send control was tapped, but delivery is not yet verifiable. Check the conversation.")
            }
        }
        return false
    }

    /** WhatsApp groups have no public send API; prepare the UI but never claim delivery. */
    fun openChatAndSend(chatName: String, message: String): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        if (!isReady()) return false
        scope.launch {
            runCatching {
                if (!service.waitForPackage("com.whatsapp", timeoutMs = 8000).isSuccess) return@runCatching
                delay(600)
                val searchOpened = listOf("com.whatsapp:id/menuitem_search", "com.whatsapp:id/search")
                    .firstNotNullOfOrNull { id -> runCatching { service.tapById(id) }.getOrNull()?.takeIf { it.isSuccess } }
                    ?: runCatching { service.tapByText("Search") }.getOrNull()?.takeIf { it.isSuccess }
                if (searchOpened == null) return@runCatching
                delay(500)
                if (!service.typeText(chatName).isSuccess) return@runCatching
                if (!service.waitForText(chatName, timeoutMs = 5000).isSuccess) return@runCatching
                if (!service.tapByText(chatName).isSuccess) return@runCatching
                delay(700)
                val visibleTitle = JarvisAccessibilityService.activeWindowText().any { it.equals(chatName, ignoreCase = true) }
                if (!visibleTitle) {
                    AutomationFeedback.report("Stopped: WhatsApp chat title did not match “$chatName”.")
                    return@runCatching
                }
                if (!service.typeInto(message, "com.whatsapp:id/entry").isSuccess && !service.typeText(message).isSuccess) return@runCatching
                AutomationFeedback.report("Verified the target chat and prepared the message. Review it and tap Send.")
            }.onFailure {
                AutomationFeedback.report("Could not prepare the WhatsApp message: ${SecretRedactor.redact(it.message)}.")
            }
        }
        return false
    }

    fun readScreen(limit: Int = 18): String {
        if (!isReady()) return requestPermission()
        val lines = JarvisAccessibilityService.activeWindowText()
            .map { SecretRedactor.redact(it) }
            .filter { it.length in 2..120 }
            .take(limit)
        return if (lines.isEmpty()) "Nothing readable is currently exposed by the active app."
        else "Visible screen text:\n" + lines.joinToString("\n") { "• $it" }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val BACKOFF_MS = longArrayOf(450L, 1000L)
    }
}
