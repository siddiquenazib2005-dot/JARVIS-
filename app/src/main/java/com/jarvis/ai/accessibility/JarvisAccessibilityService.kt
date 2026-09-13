package com.jarvis.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production-hardened AURIX AccessibilityService.
 */
class JarvisAccessibilityService : AccessibilityService() {

    enum class A11yServiceState { DISCONNECTED, CONNECTING, AVAILABLE, SUSPENDED }

    val stateFlow = MutableStateFlow(A11yServiceState.DISCONNECTED)

    private lateinit var gestureEngine: GestureEngine
    private lateinit var screenReader: ScreenReader
    /** Serializes UI mutations so click/type/swipe never race (phase 17). */
    private val actionMutex = Mutex()

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isConnected(): Boolean =
            instance?.stateFlow?.value == A11yServiceState.AVAILABLE

        private fun expectedComponent(context: Context): ComponentName =
            ComponentName(context.packageName, JarvisAccessibilityService::class.java.name)

        private fun enabledAccessibilityEntries(context: Context): List<String> =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().split(':').map { it.trim() }.filter { it.isNotBlank() }

        /** True when the user has enabled AURIX Accessibility in system settings. */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val app = context.applicationContext
            val expected = expectedComponent(app)
            val expectedFlat = expected.flattenToString()
            val expectedShort = expected.flattenToShortString()
            val serviceClass = JarvisAccessibilityService::class.java.name
            return enabledAccessibilityEntries(app).any { raw ->
                raw.equals(expectedFlat, ignoreCase = true) ||
                    raw.equals(expectedShort, ignoreCase = true) ||
                    raw.endsWith("/$serviceClass", ignoreCase = true) ||
                    raw.contains(serviceClass, ignoreCase = true)
            }
        }

        fun diagnosticsReport(context: Context): String {
            val app = context.applicationContext
            val expected = expectedComponent(app)
            val enabledRaw = Settings.Secure.getString(
                app.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().ifBlank { "<empty>" }
            val connected = isConnected()
            val state = instance?.stateFlow?.value?.name ?: "NO_INSTANCE"
            val enabled = isAccessibilityServiceEnabled(app)
            return "AURIX Accessibility Report\n" +
                "Package: ${app.packageName}\n" +
                "Expected: ${expected.flattenToString()}\n" +
                "Enabled in Android: $enabled\n" +
                "Service connected: $connected\n" +
                "Service state: $state\n" +
                "Enabled raw: $enabledRaw"
        }

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

    /** True only after every action dependency has been constructed. */
    private fun enginesReady(): Boolean =
        ::gestureEngine.isInitialized && ::screenReader.isInitialized

    /**
     * Builds the action engines without publishing this service globally.
     * This keeps callers from observing a half-initialized service.
     */
    private fun initializeEngines(): Boolean {
        stateFlow.value = A11yServiceState.CONNECTING
        val newGestureEngine = runCatching { GestureEngine(this) }.getOrElse { error ->
            AccessibilityLogger.error("GESTURE_INIT_FAILED", AccessibilityLogger.redact(error.message))
            com.jarvis.ai.diagnostics.DiagnosticsLog.recordOnce(
                "accessibility",
                "gesture init failed: ${error.message ?: error::class.java.simpleName}"
            )
            com.jarvis.ai.diagnostics.CrashGuard.record(this, error)
            return false
        }
        val newScreenReader = runCatching { ScreenReader(this) }.getOrElse { error ->
            AccessibilityLogger.error("SCREEN_READER_INIT_FAILED", AccessibilityLogger.redact(error.message))
            com.jarvis.ai.diagnostics.DiagnosticsLog.recordOnce(
                "accessibility",
                "screen reader init failed: ${error.message ?: error::class.java.simpleName}"
            )
            com.jarvis.ai.diagnostics.CrashGuard.record(this, error)
            return false
        }
        gestureEngine = newGestureEngine
        screenReader = newScreenReader
        return true
    }

    private fun publishAvailable(recovered: Boolean = false) {
        instance = this
        stateFlow.value = A11yServiceState.AVAILABLE
        AccessibilityLogger.command(
            if (recovered) "accessibility service recovered" else "accessibility service connected"
        )
        com.jarvis.ai.diagnostics.DiagnosticsLog.recordOnce(
            "accessibility",
            if (recovered) "recovered" else "connected"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Never expose this generation until both engines are ready.
        if (instance === this) instance = null
        if (initializeEngines()) {
            publishAvailable()
        } else {
            stateFlow.value = A11yServiceState.SUSPENDED
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || stateFlow.value == A11yServiceState.AVAILABLE) return
        runCatching {
            // onInterrupt may suspend a fully initialized service. A genuine
            // initialization failure gets one safe reconstruction attempt when
            // Android starts delivering events again.
            if (enginesReady() || initializeEngines()) {
                publishAvailable(recovered = true)
            } else {
                stateFlow.value = A11yServiceState.SUSPENDED
            }
        }.onFailure { error ->
            stateFlow.value = A11yServiceState.SUSPENDED
            if (instance === this) instance = null
            AccessibilityLogger.error("EVENT_RECOVERY_FAILED", AccessibilityLogger.redact(error.message))
            com.jarvis.ai.diagnostics.CrashGuard.record(this, error)
        }
    }

    override fun onInterrupt() {
        stateFlow.value = A11yServiceState.SUSPENDED
        AccessibilityLogger.error("SERVICE_INTERRUPTED", "service interrupted")
        com.jarvis.ai.diagnostics.DiagnosticsLog.recordOnce("accessibility", "interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stateFlow.value = A11yServiceState.DISCONNECTED
        // A delayed callback from an old service generation must not clear a
        // newer instance that Android has already connected.
        if (instance === this) instance = null
        com.jarvis.ai.diagnostics.DiagnosticsLog.recordOnce("accessibility", "unbound")
        return runCatching { super.onUnbind(intent) }.getOrDefault(false)
    }

    override fun onDestroy() {
        stateFlow.value = A11yServiceState.DISCONNECTED
        if (instance === this) instance = null
        com.jarvis.ai.diagnostics.DiagnosticsLog.recordOnce("accessibility", "destroyed")
        runCatching { super.onDestroy() }
    }

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun enableGuide(): String =
        "Enable AURIX in Settings → Accessibility → AURIX, then grant permission."

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
                val success = if (ok) true else performClick(node)
                if (success) A11yResult.success(action = "tap", target = text, attempts = 1,
                    verificationStatus = VerificationStatus.NOT_CHECKED)
                else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "tap", target = text,
                    message = "Tap rejected on \"$text\"", retryable = true)
            }
        }
    }

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
        val success = if (ok) true else performClick(node)
        if (success) A11yResult.success(action = "tap_by_id", target = viewId, attempts = 1,
            verificationStatus = VerificationStatus.NOT_CHECKED)
        else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "tap_by_id", target = viewId,
            message = "Tap rejected on id \"$viewId\"", retryable = true)
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
            val resulting = runCatching { engine.getText(field) }.getOrDefault("")
            val verified = resulting.contains(value.take(40), ignoreCase = true)
            A11yResult.success(
                action = "type", target = fieldTarget ?: "",
                message = "Typed text",
                verificationStatus = if (verified) VerificationStatus.VERIFIED else VerificationStatus.NOT_CHECKED
            )
        }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN))
            A11yResult.success("lock_screen")
        else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "lock_screen",
            message = "Lock-screen action unavailable")

    suspend fun swipe(direction: String, distance: String = "medium"): A11yResult = serialized("swipe") {
        AccessibilityLogger.action("swipe", direction)
        val (sx, sy, ex, ey) = computeEndpoints(direction, distance)
        val ok = gestureEngine.swipe(sx, sy, ex, ey, 300L)
        if (ok) A11yResult.success(action = "swipe", target = direction)
        else A11yResult.failure(A11yErrorCode.GESTURE_FAILED, action = "swipe", target = direction,
            message = "Swipe rejected: $direction", retryable = true)
    }

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

    suspend fun scrollUntilText(text: String, maxScrolls: Int = 12, down: Boolean = true): A11yResult =
        serialized("scroll_until_text") {
            AccessibilityLogger.action("scroll_until_text", text)
            var lastSignature = ""
            repeat(maxScrolls) {
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
                if (scrollRes.isFailure) return@serialized scrollRes.copy(action = "scroll_until_text", target = text)
                delay(250)
            }
            A11yResult.failure(A11yErrorCode.TIMEOUT, action = "scroll_until_text", target = text,
                message = "Scrolled $maxScrolls times; '$text' not found", retryable = false)
        }

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

    fun reader(): ScreenReader = screenReader

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
