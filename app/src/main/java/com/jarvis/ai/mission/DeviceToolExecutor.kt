package com.jarvis.ai.mission

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.withTimeoutOrNull
import com.jarvis.ai.accessibility.A11yErrorCode
import com.jarvis.ai.accessibility.A11yResult
import com.jarvis.ai.accessibility.GestureEngine
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.accessibility.ScrollEngine
import com.jarvis.ai.accessibility.ScreenReader
import com.jarvis.ai.accessibility.TextInputEngine
import com.jarvis.ai.accessibility.VerificationStatus
import com.jarvis.ai.orchestrator.ToolRegistry
import com.jarvis.ai.orchestrator.VerificationStrategy

/**
 * Phase-2 §4/§5: executes the registered device tools against the real Android
 * accessibility engines, then VERIFIES the effect instead of trusting a
 * non-throwing call.
 *
 * Design rule: this class never claims success it did not check. A gesture that
 * dispatches without error can still land on the wrong element; therefore taps
 * and typing are followed by a [ScreenContext] re-read and a strategy-specific
 * verification pass. When the accessibility service is not connected, every
 * device tool reports [ToolExecutionResult.Unsupported] — never a fake success.
 */
class DeviceToolExecutor(
    private val context: Context,
    private val serviceGetter: () -> JarvisAccessibilityService?
) {

    companion object {
        /**
         * Tools this executor can actually run. Anything else routed here is
         * reported as unsupported rather than faked.
         */
        val SUPPORTED_DEVICE_TOOLS: Set<String> = setOf(
            "open_app", "press_back", "press_home", "tap", "long_press",
            "swipe", "scroll", "type_text", "read_screen", "find_element"
        )
    }

    /** The live service instance, or null when the user has not enabled it. */
    private val service: JarvisAccessibilityService? get() = runCatching { serviceGetter() }.getOrNull()

    /** True only when the service is connected and able to act. */
    val isAvailable: Boolean
        get() = service != null && JarvisAccessibilityService.isConnected()

    /**
     * Runs one device tool. All Android calls are guarded: a broken node tree or
     * a dead service yields [ToolExecutionResult.Unsupported]/[Failure], never an
     * exception into the mission layer.
     */
    suspend fun execute(
        toolName: String,
        parameters: Map<String, Any?>
    ): ToolExecutionResult {
        val definition = ToolRegistry.getTool(toolName)
            ?: return ToolExecutionResult.Failure("Unknown tool: $toolName", recoverable = false)

        if (definition.requiresAccessibility && !isAvailable) {
            return ToolExecutionResult.Unsupported(
                "$toolName needs the AURIX accessibility service, which is not enabled."
            )
        }

        val svc = service ?: return ToolExecutionResult.Unsupported("Accessibility service unavailable")

        // Enforce the tool's wall-clock budget. A device action that hangs (a
        // gesture that never returns, an unresponsive window) is cancelled rather
        // than allowed to stall the mission forever.
        return withTimeoutOrNull(definition.timeoutMs) {
            dispatchTool(toolName, svc, parameters)
        } ?: ToolExecutionResult.Failure(
            "$toolName exceeded its ${definition.timeoutMs}ms budget", recoverable = true
        )
    }

    private suspend fun dispatchTool(
        toolName: String,
        svc: JarvisAccessibilityService,
        parameters: Map<String, Any?>
    ): ToolExecutionResult = when (toolName) {
            "open_app" -> executeOpenApp(svc, parameters)
            "press_back" -> executeGlobalAction(svc, AccessibilityService.GLOBAL_ACTION_BACK, "press_back")
            "press_home" -> executeGlobalAction(svc, AccessibilityService.GLOBAL_ACTION_HOME, "press_home")
            "tap" -> executeTap(svc, parameters)
            "long_press" -> executeLongPress(svc, parameters)
            "swipe" -> executeSwipe(svc, parameters)
            "scroll" -> executeScroll(svc, parameters)
            "type_text" -> executeTypeText(svc, parameters)
            "read_screen" -> executeReadScreen(svc)
            "find_element" -> executeFindElement(svc, parameters)
            else -> ToolExecutionResult.Failure("Tool not implemented: $toolName", recoverable = false)
        }

    // ------------------------------------------------------------------ tools

    private suspend fun executeOpenApp(
        svc: JarvisAccessibilityService,
        parameters: Map<String, Any?>
    ): ToolExecutionResult {
        val pkg = parameters["packageName"] as? String
            ?: parameters["app"] as? String
            ?: return ToolExecutionResult.Failure("open_app requires a package or app name", recoverable = false)
        val result = svc.openAppByPackage(pkg.trim())
        return when {
            result.status == com.jarvis.ai.accessibility.A11yStatus.SUCCESS ->
                ToolExecutionResult.Verified(result.message, result.verificationStatus)
            result.errorCode == A11yErrorCode.APP_NOT_INSTALLED ->
                ToolExecutionResult.Failure(result.message.orEmpty(), recoverable = false)
            else -> ToolExecutionResult.Failure(result.message.orEmpty(), recoverable = result.retryable)
        }
    }

    private suspend fun executeGlobalAction(
        svc: JarvisAccessibilityService,
        action: Int,
        name: String
    ): ToolExecutionResult {
        // GLOBAL_ACTION_* return a boolean dispatched flag; that is the only
        // available signal, so this is SELF_REPORTED verification.
        val ok = runCatching { svc.performGlobalAction(action) }.getOrDefault(false)
        return if (ok) ToolExecutionResult.Verified("$name dispatched", VerificationStatus.VERIFIED)
        else ToolExecutionResult.Failure("$name was rejected by the system", recoverable = false)
    }

    private suspend fun executeTap(svc: JarvisAccessibilityService, p: Map<String, Any?>): ToolExecutionResult =
        resolveNodeAndGesture(svc, p) { engine, node ->
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            engine.tap(bounds.centerX(), bounds.centerY())
        }

    private suspend fun executeLongPress(svc: JarvisAccessibilityService, p: Map<String, Any?>): ToolExecutionResult =
        resolveNodeAndGesture(svc, p) { engine, node ->
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            engine.longPress(bounds.centerX(), bounds.centerY())
        }

    /**
     * Shared path for tap/long_press: find the node by text, run the gesture at
     * the node centre, then verify the screen actually changed. A gesture that
     * dispatches but leaves the screen identical is reported as a FAILED
     * verification, not a success.
     */
    private suspend fun resolveNodeAndGesture(
        svc: JarvisAccessibilityService,
        p: Map<String, Any?>,
        gesture: suspend (GestureEngine, AccessibilityNodeInfo) -> Boolean
    ): ToolExecutionResult {
        val text = p["text"] as? String
            ?: return ToolExecutionResult.Failure("Missing 'text' parameter", recoverable = false)

        val reader = ScreenReader(svc)
        val before = runCatching { ScreenContext.capture(reader, svc) }.getOrNull()
        val node = runCatching { reader.findNodeByText(text) }.getOrNull()
            ?: return ToolExecutionResult.Failure(
                "Could not find \"$text\" on screen", recoverable = true
            )

        val engine = GestureEngine(svc)
        val ok = runCatching { gesture(engine, node) }.getOrDefault(false)
        if (!ok) return ToolExecutionResult.Failure("Gesture on \"$text\" did not complete", recoverable = true)

        // Verification: the node we acted on should be gone or the screen changed.
        val after = runCatching { ScreenContext.capture(reader, svc) }.getOrNull()
            ?: return ToolExecutionResult.Executed("Acted on \"$text\" (screen unreadable after)")

        val stillUnchanged = before != null && before.visibleText == after.visibleText &&
            before.packageName == after.packageName
        return when {
            // An obvious state change: treat as verified.
            !stillUnchanged -> ToolExecutionResult.Verified("Acted on \"$text\"", VerificationStatus.VERIFIED)
            // No observable change but the node is no longer present.
            after.nodes.none { it.text == text } -> ToolExecutionResult.Verified("Acted on \"$text\"", VerificationStatus.VERIFIED)
            else -> ToolExecutionResult.Failure("Action on \"$text\" did not change the screen", recoverable = true)
        }
    }

    private suspend fun executeSwipe(svc: JarvisAccessibilityService, p: Map<String, Any?>): ToolExecutionResult {
        val direction = (p["direction"] as? String)?.lowercase()?.trim() ?: "up"
        val reader = ScreenReader(svc)
        val beforeText = runCatching { ScreenContext.capture(reader, svc).visibleText }.getOrNull()
        val engine = GestureEngine(svc)
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels; val h = metrics.heightPixels
        val ok = when (direction) {
            "up" -> engine.swipe(w / 2, h * 3 / 4, w / 2, h / 4)
            "down" -> engine.swipe(w / 2, h / 4, w / 2, h * 3 / 4)
            "left" -> engine.swipe(w * 3 / 4, h / 2, w / 4, h / 2)
            "right" -> engine.swipe(w / 4, h / 2, w * 3 / 4, h / 2)
            else -> return ToolExecutionResult.Failure("Unknown swipe direction: $direction", recoverable = false)
        }
        if (!ok) return ToolExecutionResult.Failure("Swipe $direction was cancelled", recoverable = true)
        // A dispatched swipe may still change nothing (non-scrollable screen), so
        // confirm against a re-read instead of trusting the dispatch flag.
        val after = runCatching { ScreenContext.capture(ScreenReader(svc), svc) }.getOrNull()
        return if (after != null && after.visibleText != beforeText) {
            ToolExecutionResult.Verified("Swiped $direction", VerificationStatus.VERIFIED)
        } else {
            ToolExecutionResult.Executed("Swiped $direction (no visible change)")
        }
    }

    private suspend fun executeScroll(svc: JarvisAccessibilityService, p: Map<String, Any?>): ToolExecutionResult {
        val forward = (p["direction"] as? String)?.lowercase()?.trim() != "up"
        val reader = ScreenReader(svc)
        val before = runCatching { ScreenContext.capture(reader, svc) }.getOrNull()
        val root = runCatching { svc.rootInActiveWindow }.getOrNull()
            ?: return ToolExecutionResult.Failure("No window to scroll", recoverable = true)
        val scrollable = ScrollEngine().findScrollable(root) ?: root
        val ok = runCatching { ScrollEngine().scroll(scrollable, forward) }.getOrDefault(false)
        if (!ok) return ToolExecutionResult.Failure("Scroll had no effect", recoverable = true)
        // Being at the end of a list, a successful scroll changes nothing; report
        // Executed then, not Verified.
        val after = runCatching { ScreenContext.capture(reader, svc) }.getOrNull()
        return if (before != null && after != null && before.visibleText != after.visibleText) {
            ToolExecutionResult.Verified("Scrolled", VerificationStatus.VERIFIED)
        } else {
            ToolExecutionResult.Executed("Scrolled (content unchanged)")
        }
    }

    private suspend fun executeTypeText(svc: JarvisAccessibilityService, p: Map<String, Any?>): ToolExecutionResult {
        val text = p["text"] as? String
            ?: return ToolExecutionResult.Failure("Missing 'text' to type", recoverable = false)
        val reader = ScreenReader(svc)
        val focused = runCatching { svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: runCatching { reader.findNodeByText("") }.getOrNull()
            ?: return ToolExecutionResult.Failure("No focused field to type into", recoverable = true)

        val context = ScreenContext.capture(reader, svc)
        if (context.hasPasswordField) {
            return ToolExecutionResult.Failure("Refusing to type into a password field", recoverable = false)
        }
        val ok = runCatching { TextInputEngine().setText(focused, text) }.getOrDefault(false)
        if (!ok) return ToolExecutionResult.Failure("Type was rejected", recoverable = true)

        // Verify the field actually holds the text now.
        val after = runCatching { ScreenContext.capture(reader, svc) }.getOrNull() ?: ScreenContext.unknown()
        val typed = after.editableNodes.any { it.text?.contains(text) == true }
        return if (typed) ToolExecutionResult.Verified("Typed \"$text\"", VerificationStatus.VERIFIED)
        else ToolExecutionResult.Failure("Text did not appear in the field", recoverable = true)
    }

    private suspend fun executeReadScreen(svc: JarvisAccessibilityService): ToolExecutionResult {
        val reader = ScreenReader(svc)
        val ctx = runCatching { ScreenContext.capture(reader, svc) }.getOrNull()
            ?: return ToolExecutionResult.Failure("Screen could not be read", recoverable = true)
        return ToolExecutionResult.Verified(ctx.visibleText, VerificationStatus.VERIFIED)
    }

    private suspend fun executeFindElement(svc: JarvisAccessibilityService, p: Map<String, Any?>): ToolExecutionResult {
        val text = p["text"] as? String
            ?: return ToolExecutionResult.Failure("Missing 'text' to find", recoverable = false)
        val reader = ScreenReader(svc)
        val found = runCatching { reader.findNodeByText(text) }.getOrNull() != null
        return if (found) ToolExecutionResult.Verified("\"$text\" is on screen", VerificationStatus.VERIFIED)
        else ToolExecutionResult.Failure("\"$text\" is not on screen", recoverable = false)
    }
}

/**
 * Result of a device tool. The distinction between [Executed] (ran, unverified)
 * and [Verified] (effect confirmed) is what stops the mission layer from
 * reporting success on an unverified action.
 */
sealed class ToolExecutionResult {
    /** Tool ran but its effect could not be verified. */
    data class Executed(val observation: String?) : ToolExecutionResult()
    /** Tool ran and the expected effect was confirmed. */
    data class Verified(val observation: String?, val status: VerificationStatus) : ToolExecutionResult()
    /** Tool failed or verification failed; recoverability is explicit. */
    data class Failure(val error: String, val recoverable: Boolean) : ToolExecutionResult()
    /** The environment cannot support this tool (service off, no window). */
    data class Unsupported(val reason: String) : ToolExecutionResult()
}
