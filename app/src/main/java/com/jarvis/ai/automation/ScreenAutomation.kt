package com.jarvis.ai.automation

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.ai.accessibility.A11yResult
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Accessibility wrapper used only for actions that require screen semantics. */
class ScreenAutomation(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun isReady(): Boolean =
        JarvisAccessibilityService.isConnected() ||
            JarvisAccessibilityService.isAccessibilityServiceEnabled(app)

    fun report(): String = JarvisAccessibilityService.diagnosticsReport(app)

    fun requestPermission(): String {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            app.startActivity(intent)
            "Screen control needs Accessibility, sir. I opened the settings screen — enable AURIX there, then ask me again."
        }.getOrElse {
            "Enable AURIX under Settings → Accessibility to let me control the screen, sir."
        }
    }

    private fun dispatch(
        ack: String,
        what: String,
        block: suspend (JarvisAccessibilityService) -> A11yResult
    ): String {
        val service = JarvisAccessibilityService.instance ?: return requestPermission()
        scope.launch {
            var last: A11yResult? = null
            for (attempt in 0 until MAX_ATTEMPTS) {
                if (attempt > 0) delay(BACKOFF_MS[attempt - 1])
                last = runCatching { block(service) }.getOrNull()
                if (last?.isSuccess == true) break
                if (last?.isFailure == true && !last.retryable) break
            }
            when {
                last == null -> AutomationFeedback.report(
                    "I could not $what, sir - the screen control layer threw an error."
                )
                last.isSuccess -> Unit
                last.needsConfirmation -> AutomationFeedback.report(
                    "That looked risky, sir, so I stopped before I could $what. Say it again to confirm."
                )
                else -> {
                    val reason = last.message?.takeIf { it.isNotBlank() }
                        ?: last.errorCode?.name?.lowercase()?.replace('_', ' ')
                        ?: "nothing matched on screen"
                    AutomationFeedback.report(
                        "I could not $what after $MAX_ATTEMPTS tries, sir - $reason."
                    )
                }
            }
        }
        return ack
    }

    fun tap(target: String): String =
        dispatch("Tapping \"$target\", sir.", "tap \"$target\"") { it.tapByText(target) }

    fun longPress(target: String): String =
        dispatch("Long-pressing \"$target\", sir.", "long-press \"$target\"") {
            it.longPressByText(target)
        }

    fun type(value: String): String =
        dispatch("Typing that in, sir.", "type that in") { it.typeText(value) }

    fun scroll(down: Boolean): String = dispatch(
        if (down) "Scrolling down, sir." else "Scrolling up, sir.",
        if (down) "scroll down" else "scroll up"
    ) { it.performScroll(down) }

    fun swipe(direction: String): String =
        dispatch("Swiping $direction, sir.", "swipe $direction") { it.swipe(direction) }

    fun scrollUntil(text: String): String =
        dispatch("Looking for \"$text\", sir.", "find \"$text\" on screen") {
            it.scrollUntilText(text)
        }

    fun back(): String {
        val service = JarvisAccessibilityService.instance ?: return requestPermission()
        service.pressBack()
        return "Back, sir."
    }

    fun home(): String {
        val service = JarvisAccessibilityService.instance ?: return requestPermission()
        service.pressHome()
        return "Home screen, sir."
    }

    fun recents(): String {
        val service = JarvisAccessibilityService.instance ?: return requestPermission()
        service.pressRecents()
        return "Recent apps, sir."
    }

    fun lock(): String {
        val service = JarvisAccessibilityService.instance ?: return requestPermission()
        return runCatching {
            service.lockScreen()
            "Locking the screen, sir."
        }.getOrElse { "I could not lock the screen on this device, sir." }
    }

    /**
     * Finishes a third-party UI send only when no official Android API exists.
     * System SMS packages are blocked here by policy: SMS uses SmsManager in
     * ToolExecutor; the fallback remains a user-controlled draft.
     */
    fun autoSend(packageName: String, viewIds: List<String>, labels: List<String>): Boolean {
        if (!AccessibilityUsePolicy.mayUseAccessibilityForUiSend(packageName)) return false
        val service = JarvisAccessibilityService.instance ?: return false
        if (!isReady()) return false
        scope.launch {
            runCatching {
                if (!service.waitForPackage(packageName, timeoutMs = 6000).isSuccess) return@launch
                delay(700)
                val byId = viewIds.firstNotNullOfOrNull { id ->
                    runCatching { service.tapById(id) }.getOrNull()?.takeIf { it.isSuccess }
                }
                if (byId == null) {
                    labels.firstNotNullOfOrNull { label ->
                        runCatching { service.tapByText(label) }.getOrNull()?.takeIf { it.isSuccess }
                    }
                }
            }
        }
        return true
    }

    /** WhatsApp groups have no public send API, so this is a valid UI-only use. */
    fun openChatAndSend(chatName: String, message: String): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        if (!isReady()) return false
        scope.launch {
            runCatching {
                if (!service.waitForPackage("com.whatsapp", timeoutMs = 8000).isSuccess) return@launch
                delay(600)
                val searchOpened = listOf(
                    "com.whatsapp:id/menuitem_search",
                    "com.whatsapp:id/search"
                ).firstNotNullOfOrNull { id ->
                    runCatching { service.tapById(id) }.getOrNull()?.takeIf { it.isSuccess }
                } ?: runCatching { service.tapByText("Search") }.getOrNull()?.takeIf { it.isSuccess }
                if (searchOpened == null) return@launch

                delay(500)
                if (!service.typeText(chatName).isSuccess) return@launch
                if (!service.waitForText(chatName, timeoutMs = 5000).isSuccess) return@launch
                delay(400)
                if (!service.tapByText(chatName).isSuccess) return@launch
                if (!service.waitForId("com.whatsapp:id/entry", timeoutMs = 5000).isSuccess) delay(800)
                if (!service.typeInto(message, "com.whatsapp:id/entry").isSuccess &&
                    !service.typeText(message).isSuccess
                ) return@launch

                delay(400)
                val sent = listOf(
                    "com.whatsapp:id/send",
                    "com.whatsapp:id/send_container"
                ).firstNotNullOfOrNull { id ->
                    runCatching { service.tapById(id) }.getOrNull()?.takeIf { it.isSuccess }
                }
                if (sent == null) runCatching { service.tapByText("Send") }
            }
        }
        return true
    }

    fun readScreen(limit: Int = 18): String {
        if (!isReady()) return requestPermission()
        val lines = JarvisAccessibilityService.activeWindowText()
            .filter { it.length in 2..120 }
            .take(limit)
        return if (lines.isEmpty()) {
            "I can't read anything on screen right now, sir."
        } else {
            "On screen right now, sir:\n" + lines.joinToString("\n") { "• $it" }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val BACKOFF_MS = longArrayOf(450L, 1000L)
    }
}
