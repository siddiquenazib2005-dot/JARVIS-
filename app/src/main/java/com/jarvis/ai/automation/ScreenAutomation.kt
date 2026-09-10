package com.jarvis.ai.automation

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Friendly wrapper around JarvisAccessibilityService for the chat layer.
 *
 * The accessibility engine exposes suspend functions while the offline command
 * router is synchronous, so every gesture is dispatched on a background scope
 * and the user gets an immediate acknowledgement.
 */
class ScreenAutomation(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True when the user has granted AURIX accessibility access. */
    fun isReady(): Boolean = JarvisAccessibilityService.isConnected()

    /** Opens system settings so the user can switch the service on. */
    fun requestPermission(): String {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            app.startActivity(intent)
            "Screen control needs Accessibility, sir. I opened the settings screen — " +
                "enable AURIX there, then ask me again."
        }.getOrElse {
            "Enable AURIX under Settings → Accessibility to let me control the screen, sir."
        }
    }

    private fun dispatch(ack: String, block: suspend (JarvisAccessibilityService) -> Unit): String {
        val service = JarvisAccessibilityService.instance ?: return requestPermission()
        scope.launch { runCatching { block(service) } }
        return ack
    }

    fun tap(target: String): String =
        dispatch("Tapping \"$target\", sir.") { it.tapByText(target) }

    fun longPress(target: String): String =
        dispatch("Long-pressing \"$target\", sir.") { it.longPressByText(target) }

    fun type(value: String): String =
        dispatch("Typing that in, sir.") { it.typeText(value) }

    fun scroll(down: Boolean): String =
        dispatch(if (down) "Scrolling down, sir." else "Scrolling up, sir.") {
            it.performScroll(down)
        }

    fun swipe(direction: String): String =
        dispatch("Swiping $direction, sir.") { it.swipe(direction) }

    fun scrollUntil(text: String): String =
        dispatch("Looking for \"$text\", sir.") { it.scrollUntilText(text) }

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
     * Waits for [packageName] to come to the foreground and then presses its
     * send button, trying stable resource ids first and visible labels second.
     *
     * Used to finish WhatsApp / SMS sends automatically instead of leaving the
     * user to tap the arrow. Returns false when Accessibility is not granted,
     * so the caller can fall back to a "tap send" instruction.
     */
    fun autoSend(packageName: String, viewIds: List<String>, labels: List<String>): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        if (!isReady()) return false
        scope.launch {
            runCatching {
                service.waitForPackage(packageName, timeoutMs = 6000)
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

    /**
     * Opens a WhatsApp chat by its visible name and sends a message.
     *
     * Groups have no phone number, so the wa.me deep link cannot reach them.
     * The only route is the app's own search box: search -> pick the chat ->
     * type -> send. Each step waits for the next screen instead of blind
     * delays, and every failure point simply stops the chain, leaving the user
     * in WhatsApp with nothing sent rather than a message in the wrong chat.
     *
     * Returns false when Accessibility is off so the caller can fall back.
     */
    fun openChatAndSend(chatName: String, message: String): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        if (!isReady()) return false
        scope.launch {
            runCatching {
                if (!service.waitForPackage("com.whatsapp", timeoutMs = 8000).isSuccess) return@launch
                delay(600)

                // Search entry point: id first, magnifier label second.
                val searchOpened = listOf(
                    "com.whatsapp:id/menuitem_search",
                    "com.whatsapp:id/search"
                ).firstNotNullOfOrNull { id ->
                    runCatching { service.tapById(id) }.getOrNull()?.takeIf { it.isSuccess }
                } ?: runCatching { service.tapByText("Search") }.getOrNull()?.takeIf { it.isSuccess }
                if (searchOpened == null) return@launch

                delay(500)
                if (!service.typeText(chatName).isSuccess) return@launch

                // Wait for the chat row to appear, then open it.
                if (!service.waitForText(chatName, timeoutMs = 5000).isSuccess) return@launch
                delay(400)
                if (!service.tapByText(chatName).isSuccess) return@launch

                // Message field, then send.
                if (!service.waitForId("com.whatsapp:id/entry", timeoutMs = 5000).isSuccess) {
                    delay(800)
                }
                if (!service.typeInto(message, "com.whatsapp:id/entry").isSuccess) {
                    if (!service.typeText(message).isSuccess) return@launch
                }
                delay(400)
                val sent = listOf(
                    "com.whatsapp:id/send",
                    "com.whatsapp:id/send_container"
                ).firstNotNullOfOrNull { id ->
                    runCatching { service.tapById(id) }.getOrNull()?.takeIf { it.isSuccess }
                }
                if (sent == null) {
                    runCatching { service.tapByText("Send") }
                }
            }
        }
        return true
    }

    /** Reads what is currently on screen, for "what's on my screen" requests. */
    fun readScreen(limit: Int = 18): String {
        if (!isReady()) return requestPermission()
        val lines = JarvisAccessibilityService.activeWindowText()
            .filter { it.length in 2..120 }
            .take(limit)
        return if (lines.isEmpty()) {
            "I can't read anything on screen right now, sir."
        } else {
            "On screen right now, sir:\n" + lines.joinToString("\n") { "• " + it }
        }
    }
}
