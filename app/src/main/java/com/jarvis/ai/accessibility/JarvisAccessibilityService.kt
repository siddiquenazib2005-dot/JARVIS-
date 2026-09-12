package com.jarvis.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production-hardened AURIX AccessibilityService.
 *
 * Responsibilities (see hardening spec):
 *  - Robust lifecycle + observable [A11yServiceState] (phase 1).
 *  - Reliable [isAccessibilityServiceEnabled] + safe settings-intent (phase 2).
 *  - Serialized UI actions via [actionMutex] (phase 17).
 *  - Node resolution, validation, click/long-press/swipe/scroll/text engines (3–9).
 *  - Centralized wait/retry engine (10) and before/after verification (11).
 *  - Structured [A11yResult] for every action (13) + risk logging (14).
 *  - Privacy-aware logging (16); display-metric-aware coordinates (18).
 *  - id-based tap (19) — for third-party apps (e.g. WhatsApp) whose send/action
 *    buttons don't reliably expose stable text/content-description, but do
 *    expose a stable resource id across app updates.
 */
class JarvisAccessibilityService : AccessibilityService() {

    enum class A11yServiceState { DISCONNECTED, CONNECTING, AVAILABLE, SUSPENDED }

    val stateFlow = MutableStateFlow(A11yServiceState.DISCONNECTED)

    private lateinit var gestureEngine: GestureEngine
    private lateinit var screenReader: ScreenReader
    /** Serializes UI mutations so click/type/swipe never race (phase 17). */
    private val actionMutex = Mutex()

    companion object {
        private const val TAG = "AurixA11y"
        private const val MAX_ACTION_RETRIES = 2

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isConnected(): Boolean =
            instance?.stateFlow?.value == A11yServiceState.AVAILABLE

        /** True when the user has enabled AURIX Accessibility in system settings. */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expected = ComponentName(context, JarvisAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(":").any { it.equals("$expected", ignoreCase = true) }
        }

        /**
         * Best-effort snapshot of the active window's visible text, read from
         * the accessibility tree (BFS, depth-bounded by [maxNodes]).
         *
         * Consent-free (the user already granted Accessibility) and available
         * on every supported Android version — unlike MediaProjection
         * screenshots, which on API 34+ additionally require a live
         * mediaProjection foreground service. Returns an empty list when the
         * service is not connected or the window tree is unavailable.
         */
        fun activeWindowText(maxNodes: Int = 400): List<String> {
            val service = instance
                ?.takeIf { it.stateFlow.value == A11yServiceState.AVAILABLE }
                ?: return emptyList()
            val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return emptyList()
            val out = ArrayList<String>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < maxNodes) {
                val node = queue.removeFirst()
                visited++
                val text = runCatching { node.text?.toString()?.trim() }.getOrNull()
                if (!text.isNullOrEmpty()) out.add(text)
                runCatching {
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(it) }
                    }
                }.getOrNull() ?: continue
            }
            return out.distinct()
        }
    }

    override fun onServiceConnected() {
        runCatching {
            super.onServiceConnected()
            instance = this
            stateFlow.value = A11yServiceState.CONNECTING
            gestureEngine = GestureEngine(this)
            screenReader = ScreenReader(this)
            stateFlow.value = A11yServiceState.AVAILABLE
            AccessibilityLogger.command("accessibility service connected")
            com.jarvis.ai.diagnostics.DiagnosticsLog.record("accessibility", "connected")
        }.onFailure { error ->
            stateFlow.value = A11yServiceState.SUSPENDED
            AccessibilityLogger.error("SERVICE_CONNECT_FAILED", AccessibilityLogger.redact(error.message))
            com.jarvis.ai.diagnostics.CrashGuard.record(this, error)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            if (event == null) return
            if (stateFlow.value != A11yServiceState.AVAILABLE) {
                stateFlow.value = A11yServiceState.AVAILABLE
            }
        }.onFailure { error ->
            AccessibilityLogger.error("EVENT_FAILED", AccessibilityLogger.redact(error.message))
            com.jarvis.ai.diagnostics.CrashGuard.record(this, error)
        }
    }

    override fun onInterrupt() {
        runCatching {
            stateFlow.value = A11yServiceState.SUSPENDED
            AccessibilityLogger.error("SERVICE_INTERRUPTED", "service interrupted")
            com.jarvis.ai.diagnostics.DiagnosticsLog.record("accessibility", "interrupted")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stateFlow.value = A11yServiceState.DISCONNECTED
        instance = null
        com.jarvis.ai.diagnostics.DiagnosticsLog.record("accessibility", "unbound")
        return runCatching { super.onUnbind(intent) }.getOrDefault(false)
    }

    override fun onDestroy() {
        stateFlow.value = A11yServiceState.DISCONNECTED
        instance = null
        com.jarvis.ai.diagnostics.DiagnosticsLog.record("accessibility", "destroyed")
        runCatching { super.onDestroy() }
    }

    /** Safe intent to open Android Accessibility Settings (phase 2). */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun enableGuide(): String =
        "Enable AURIX in Settings → Accessibility → AURIX, then grant permission."

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private suspend fun getRoot(retries: Int = 3): AccessibilityNodeInfo? {
        repeat(retries) { attempt ->
            val root = runCatching { rootInActiveWindow }.getOrNull()
            if (root != null) return root
            if (attempt < retries - 1) delay(150)
        }
        return null
    }

    private fun isAppForeground(pkg: String): Boolean {
        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (rootPkg == pkg) return true
        return runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName == pkg
        }.getOrDefault(false)
    }

    private suspend fun verifyForeground(pkg: String, timeoutMs: Long = 3000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isAppForeground(pkg)) return true
            delay(200)
        }
        return isAppForeground(pkg)
    }

    private suspend fun <T> serialized(actionName: String, block: suspend () -> T): T {
        AccessibilityLogger.command("action=$actionName risk=${RiskClassifier.classify(actionName)}")
        return actionMutex.withLock { block() }
    }

    // ------------------------------------------------------------------
    // App launching (phase 12)
    // ------------------------------------------------------------------

    suspend fun openAppByPackage(packageName: String): A11yResult = serialized("open_app") {
        AccessibilityLogger.command("open_app:$packageName")
        if (!isAccessibilityServiceEnabled(this@JarvisAccessibilityService)) {
            return@serialized A11yResult.failure(
                A11yErrorCode.SERVICE_UNAVAILABLE, action = "open_app", packageName = packageName,
                message = "AURIX accessibility is not enabled. ${enableGuide()}"
            )
        }
        if (isAppForeground(packageName)) {
            return@serialized A11yResult.success(
                action = "open_app", packageName = packageName,
                message = "Already running: $packageName",
                verificationStatus = VerificationStatus.VERIFIED
            )
        }
        val launchIntent = runCatching {
            packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull()
        if (launchIntent == null) {
            return@serialized A11yResult.failure(
                A11yErrorCode.APP_NOT_INSTALLED, action = "open_app", packageName = packageName,
                message = "No launchable activity for $packageName"
            )
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { startActivity(launchIntent) }.onFailure { e ->
            return@serialized A11yResult.failure(
                A11yErrorCode.PACKAGE_NOT_FOUND, action = "open_app", packageName = packageName,
                message = "Failed to launch $packageName: ${AccessibilityLogger.redact(e.message)}",
                retryable = true
            )
        }
        val confirmed = verifyForeground(packageName)
        A11yResult.success(
            action = "open_app", packageName = packageName,
            message = "Launched $packageName",
            verificationStatus = if (confirmed) VerificationStatus.VERIFIED else VerificationStatus.NOT_CHECKED
        )
    }

    /**
     * Launches an arbitrary [Intent] (e.g. a wa.me deep-link) and waits for
     * [expectedPackage] to come to the foreground. Used by higher-level senders
     * (WhatsApp/SMS) that need a pre-filled screen rather than a bare app launch.
     */
    suspend fun launchIntentAndVerify(intent: Intent, expectedPackage: String): A11yResult =
        serialized("launch_intent") {
            AccessibilityLogger.command("launch_intent:$expectedPackage")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }.onFailure { e ->
                return@serialized A11yResult.failure(
                    A11yErrorCode.PACKAGE_NOT_FOUND, action = "launch_intent", packageName = expectedPackage,
                    message = "Failed to launch intent: ${AccessibilityLogger.redact(e.message)}",
                    retryable = true
                )
            }
            val confirmed = verifyForeground(expectedPackage, timeoutMs = 4000)
            if (confirmed) {
                A11yResult.success(action = "launch_intent", packageName = expectedPackage,
                    verificationStatus = VerificationStatus.VERIFIED)
            } else {
                A11yResult.failure(A11yErrorCode.TIMEOUT, action = "launch_intent", packageName = expectedPackage,
                    message = "$expectedPackage did not come to foreground in time", retryable = true)
            }
        }

    // ------------------------------------------------------------------
    // App closing (fixed: previously CloseApp re-launched the app)
    // ------------------------------------------------------------------

    /**
     * Closes an app by (1) bringing it out of the foreground via HOME if it is
     * the focused window and (2) killing its background processes through
     * [ActivityManager.killBackgroundProcesses]. Requires
     * `android.permission.KILL_BACKGROUND_PROCESSES`.
     */
    suspend fun closeAppByPackage(packageName: String): A11yResult = serialized("close_app") {
        AccessibilityLogger.command("close_app:$packageName")
        if (!isAccessibilityServiceEnabled(this@JarvisAccessibilityService)) {
            return@serialized A11yResult.failure(
                A11yErrorCode.ACCESSIBILITY_DISABLED, action = "close_app", packageName = packageName,
                message = "AURIX accessibility is not enabled. ${enableGuide()}"
            )
        }
        val launched = runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
        if (launched == null) {
            return@serialized A11yResult.failure(
                A11yErrorCode.APP_NOT_INSTALLED, action = "close_app", packageName = packageName,
                message = "No launchable activity for $packageName"
            )
        }
        // Step out of the app first so killBackgroundProcesses can act on it.
        if (isAppForeground(packageName)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            delay(200)
        }
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val killed = runCatching { am.killBackgroundProcesses(packageName) }.isSuccess
        val nowForeground = verifyForeground(packageName, timeoutMs = 1500)
        A11yResult.success(
            action = "close_app", packageName = packageName,
            message = if (nowForeground) "Could not fully close $packageName (still foreground)"
            else "Closed $packageName" + (if (killed) "" else " (background kill unavailable)"),
            verificationStatus = if (!nowForeground) VerificationStatus.VERIFIED else VerificationStatus.NOT_CHECKED
        )
    }

    // ------------------------------------------------------------------
    // Click engine (phase 5)
    // ------------------------------------------------------------------

    suspend fun tapByText(text: String): A11yResult = serialized("tap") {
        AccessibilityLogger.action("tap", text)
        val root = getRoot() ?: return@serialized hierarchyUnavailable("tap", text)
        when (val res = NodeResolver.resolveDetailed(root, text)) {
            is NodeResolver.Resolution.NotFound ->
                return@serialized A11yResult.failure(
                    A11yErrorCode.NODE_NOT_FOUND, action = "tap", target = text,
                    message = "No element found for \"$text\""
                )
            is NodeResolver.Resolution.Ambiguous ->
                return@serialized A11yResult.failure(
                    A11yErrorCode.MULTIPLE_MATCHES, action = "tap", target = text,
                    message = "Multiple elements match \"$text\" (${res.candidates.size}); disambiguate.",
                    retryable = false
                )
            is NodeResolver.Resolution.Found -> {
                val node = res.node
                val v = NodeValidator.validate(node, requireClickable = true)
                if (!v.valid) {
                    return@serialized A11yResult.failure(
                        if (!node.isVisibleToUser) A11yErrorCode.NODE_NOT_VISIBLE else A11yErrorCode.NODE_DISABLED,
                        action = "tap", target = text, message = v.reason
                    )
                }
                val ok = performClick(node)
                val success = if (ok) true else performClick(node) // one retry
                if (success) {
                    A11yResult.success(action = "tap", target = text, attempts = 1,
                        verificationStatus = VerificationStatus.NOT_CHECKED)
                } else {
                    A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "tap", target = text,
                        message = "Tap rejected on \"$text\"", retryable = true)
                }
            }
        }
    }

    /**
     * Taps a node identified by its stable resource id, e.g.
     * "com.whatsapp:id/send". More reliable than [tapByText] for third-party
     * app buttons whose visible text/content-description changes across
     * locales or app updates, but whose id tends to stay stable.
     */
    suspend fun tapById(viewId: String): A11yResult = serialized("tap_by_id") {
        AccessibilityLogger.action("tap_by_id", viewId)
        val root = getRoot() ?: return@serialized hierarchyUnavailable("tap_by_id", viewId)
        val node = NodeResolver.findById(root, viewId) ?: return@serialized A11yResult.failure(
            A11yErrorCode.NODE_NOT_FOUND, action = "tap_by_id", target = viewId,
            message = "No element found with id \"$viewId\""
        )
        val v = NodeValidator.validate(node, requireClickable = true)
        if (!v.valid) {
            return@serialized A11yResult.failure(
                if (!node.isVisibleToUser) A11yErrorCode.NODE_NOT_VISIBLE else A11yErrorCode.NODE_DISABLED,
                action = "tap_by_id", target = viewId, message = v.reason
            )
        }
        val ok = performClick(node)
        val success = if (ok) true else performClick(node) // one retry
        if (success) {
            A11yResult.success(action = "tap_by_id", target = viewId, attempts = 1,
                verificationStatus = VerificationStatus.NOT_CHECKED)
        } else {
            A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "tap_by_id", target = viewId,
                message = "Tap rejected on id \"$viewId\"", retryable = true)
        }
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)

    suspend fun tapByCoords(x: Int, y: Int): A11yResult = serialized("tap_coords") {
        AccessibilityLogger.action("tap_coords", "$x,$y")
        if (!gestureEngine.inBounds(x, y)) {
            return@serialized A11yResult.failure(
                A11yErrorCode.INVALID_ACTION, action = "tap_coords", target = "$x,$y",
                message = "Coordinates out of screen bounds"
            )
        }
        val ok = gestureEngine.tap(x, y)
        if (ok) A11yResult.success(action = "tap_coords", target = "$x,$y")
        else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "tap_coords",
            target = "$x,$y", message = "Tap gesture rejected", retryable = true)
    }

    // ------------------------------------------------------------------
    // Long press (phase 6)
    // ------------------------------------------------------------------

    suspend fun longPressByText(text: String, durationMs: Long = 600): A11yResult = serialized("long_press") {
        AccessibilityLogger.action("long_press", text)
        val root = getRoot() ?: return@serialized hierarchyUnavailable("long_press", text)
        val node = NodeResolver.resolve(root, text) ?: return@serialized A11yResult.failure(
            A11yErrorCode.NODE_NOT_FOUND, action = "long_press", target = text,
            message = "No element found for \"$text\""
        )
        val (cx, cy) = NodeResolver.center(node)
        if (!gestureEngine.inBounds(cx, cy)) {
            return@serialized A11yResult.failure(A11yErrorCode.INVALID_ACTION, action = "long_press",
                target = text, message = "Resolved node center out of bounds")
        }
        val ok = gestureEngine.longPress(cx, cy, durationMs)
        if (ok) A11yResult.success(action = "long_press", target = text)
        else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "long_press", target = text,
            message = "Long-press rejected on \"$text\"", retryable = true)
    }

    // ------------------------------------------------------------------
    // Text input (phase 9)
    // ------------------------------------------------------------------

    suspend fun typeText(value: String, clearFirst: Boolean = true): A11yResult =
        typeInto(value, fieldTarget = null, clearFirst = clearFirst)

    suspend fun typeInto(value: String, fieldTarget: String?, clearFirst: Boolean = true): A11yResult =
        serialized("type") {
            AccessibilityLogger.action("type", fieldTarget ?: "<focused>")
            val root = getRoot() ?: return@serialized hierarchyUnavailable("type", fieldTarget ?: "")
            val field = if (fieldTarget != null) NodeResolver.findEditable(root, fieldTarget)
            else NodeResolver.findEditable(root)
            if (field == null) {
                return@serialized A11yResult.failure(
                    A11yErrorCode.INPUT_FAILED, action = "type", target = fieldTarget ?: "",
                    message = "No editable text field found"
                )
            }
            val engine = TextInputEngine()
            val applied = if (clearFirst) engine.clear(field) && engine.setText(field, value)
            else engine.setText(field, value)
            if (!applied) {
                return@serialized A11yResult.failure(A11yErrorCode.INPUT_FAILED, action = "type",
                    target = fieldTarget ?: "", message = "Failed to set text", retryable = true)
            }
            // Best-effort verification of resulting text.
            val resulting = runCatching { engine.getText(field) }.getOrDefault("")
            val verified = resulting.contains(value.take(40), ignoreCase = true)
            A11yResult.success(
                action = "type", target = fieldTarget ?: "",
                message = "Typed text",
                verificationStatus = if (verified) VerificationStatus.VERIFIED else VerificationStatus.NOT_CHECKED
            )
        }

    // ------------------------------------------------------------------
    // Global navigation (phase 5 low-risk)
    // ------------------------------------------------------------------

    fun pressBack(): A11yResult =
        if (performGlobalAction(GLOBAL_ACTION_BACK)) A11yResult.success("press_back")
        else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "press_back", message = "Back rejected")

    fun pressHome(): A11yResult =
        if (performGlobalAction(GLOBAL_ACTION_HOME)) A11yResult.success("press_home")
        else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "press_home", message = "Home rejected")

    fun pressRecents(): A11yResult =
        if (performGlobalAction(GLOBAL_ACTION_RECENTS)) A11yResult.success("press_recents")
        else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "press_recents", message = "Recents rejected")

    fun lockScreen(): A11yResult =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        ) A11yResult.success("lock_screen")
        else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "lock_screen",
            message = "Lock-screen action unavailable")

    // ------------------------------------------------------------------
    // Swipe (phase 7)
    // ------------------------------------------------------------------

    suspend fun swipe(direction: String, distance: String = "medium"): A11yResult = serialized("swipe") {
        AccessibilityLogger.action("swipe", direction)
        val (sx, sy, ex, ey) = computeEndpoints(direction, distance)
        val ok = gestureEngine.swipe(sx, sy, ex, ey, 300L)
        if (ok) A11yResult.success(action = "swipe", target = direction)
        else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "swipe", target = direction,
            message = "Swipe rejected: $direction", retryable = true)
    }

    // ------------------------------------------------------------------
    // Scroll (phase 8)
    // ------------------------------------------------------------------

    suspend fun performScroll(down: Boolean = true): A11yResult = serialized("scroll") { performScrollInternal(down) }

    private suspend fun performScrollInternal(down: Boolean): A11yResult {
        AccessibilityLogger.action("scroll", if (down) "down" else "up")
        val root = getRoot() ?: return hierarchyUnavailable("scroll", "")
        val scrollable = ScrollEngine().findScrollable(root)
        if (scrollable == null) {
            val (sx, sy, ex, ey) = computeEndpoints(if (down) "up" else "down", "medium")
            val ok = gestureEngine.swipe(sx, sy, ex, ey, 300L)
            return if (ok) A11yResult.success(action = "scroll", target = if (down) "down" else "up")
            else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "scroll",
                message = "Nothing scrollable on screen", retryable = false)
        }
        val ok = ScrollEngine().scroll(scrollable, down)
        return if (ok) A11yResult.success(action = "scroll", target = if (down) "down" else "up")
        else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "scroll", message = "Scroll rejected")
    }

    /** Scroll until [text] appears, with max-scroll + duplicate-screen detection (no infinite loop). */
    suspend fun scrollUntilText(text: String, maxScrolls: Int = 12, down: Boolean = true): A11yResult =
        serialized("scroll_until_text") {
            AccessibilityLogger.action("scroll_until_text", text)
            var lastSignature = ""
            repeat(maxScrolls) { i ->
                if (screenReader.extractAllText().contains(text, ignoreCase = true)) {
                    return@serialized A11yResult.success(action = "scroll_until_text", target = text,
                        verificationStatus = VerificationStatus.VERIFIED)
                }
                val sig = screenReader.extractAllText().hashCode().toString()
                if (sig == lastSignature) {
                    return@serialized A11yResult.failure(A11yErrorCode.WINDOW_CHANGED, action = "scroll_until_text",
                        target = text, message = "No progress scrolling; '$text' not found")
                }
                lastSignature = sig
                val scrollRes = performScrollInternal(down)
                if (scrollRes.isFailure) {
                    return@serialized scrollRes.copy(action = "scroll_until_text", target = text)
                }
                delay(250)
            }
            A11yResult.failure(A11yErrorCode.TIMEOUT, action = "scroll_until_text", target = text,
                message = "Scrolled $maxScrolls times; '$text' not found", retryable = false)
        }

    // ------------------------------------------------------------------
    // Wait / retry engine (phase 10)
    // ------------------------------------------------------------------

    suspend fun waitForText(text: String, timeoutMs: Long = 5000): A11yResult {
        AccessibilityLogger.action("wait_for_text", text)
        val engine = WaitEngine(
            rootProvider = { runCatching { rootInActiveWindow }.getOrNull() },
            textProvider = { screenReader.extractAllText() }
        )
        return engine.waitForText(text, timeoutMs)
    }

    suspend fun waitForNode(text: String, timeoutMs: Long = 5000): A11yResult {
        val engine = WaitEngine(
            rootProvider = { runCatching { rootInActiveWindow }.getOrNull() },
            textProvider = { screenReader.extractAllText() }
        )
        return engine.waitForNode(text, timeoutMs)
    }

    suspend fun waitForPackage(packageName: String, timeoutMs: Long = 5000): A11yResult {
        val engine = WaitEngine(
            rootProvider = { runCatching { rootInActiveWindow }.getOrNull() },
            textProvider = { screenReader.extractAllText() }
        )
        return engine.waitForPackage(packageName, timeoutMs)
    }

    /** Waits until an element with resource id [viewId] appears (used after launchIntentAndVerify). */
    suspend fun waitForId(viewId: String, timeoutMs: Long = 5000): A11yResult {
        AccessibilityLogger.action("wait_for_id", viewId)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = runCatching { rootInActiveWindow }.getOrNull()
            if (root != null && NodeResolver.findById(root, viewId) != null) {
                return A11yResult.success(action = "wait_for_id", target = viewId,
                    verificationStatus = VerificationStatus.VERIFIED)
            }
            delay(150)
        }
        return A11yResult.failure(A11yErrorCode.TIMEOUT, action = "wait_for_id", target = viewId,
            message = "Element with id \"$viewId\" did not appear in time", retryable = true)
    }

    // ------------------------------------------------------------------
    // Screen reader access
    // ------------------------------------------------------------------

    fun reader(): ScreenReader = screenReader

    // ------------------------------------------------------------------
    // Small utilities
    // ------------------------------------------------------------------

    private fun hierarchyUnavailable(action: String, target: String) = A11yResult.failure(
        A11yErrorCode.HIERARCHY_UNAVAILABLE, action = action, target = target,
        message = "Screen hierarchy unavailable", retryable = true
    )

    private fun computeEndpoints(direction: String, distance: String): IntArray {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val cx = w / 2
        val cy = h / 2
        val dist = when (distance.lowercase()) {
            "short" -> (h * 0.2).toInt()
            "long" -> (h * 0.75).toInt()
            else -> (h * 0.45).toInt()
        }
        return when (direction.lowercase()) {
            "up" -> intArrayOf(cx, cy + dist / 2, cx, cy - dist / 2)
            "down" -> intArrayOf(cx, cy - dist / 2, cx, cy + dist / 2)
            "left" -> intArrayOf(cx + dist / 2, cy, cx - dist / 2, cy)
            "right" -> intArrayOf(cx - dist / 2, cy, cx + dist / 2, cy)
            else -> intArrayOf(cx, cy + dist / 2, cx, cy - dist / 2)
        }
    }
}