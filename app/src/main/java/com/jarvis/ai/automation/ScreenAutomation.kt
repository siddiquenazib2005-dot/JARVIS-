package com.jarvis.ai.automation

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
