package com.jarvis.ai.mission

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.ai.accessibility.NodeSnapshot
import com.jarvis.ai.accessibility.ScreenReader

/**
 * Phase-2 §3: the single normalized representation of "what is on screen right now".
 *
 * Planning, device tools and verification all consume this instead of each one
 * rummaging through the accessibility tree. It is built ONLY from data Android's
 * AccessibilityService actually exposes — no reflection, no bypassing the
 * security model — and it degrades to an empty context when the service has no
 * window (locked screen, permission revoked, service stopped), rather than
 * guessing. Callers must treat an empty context as "screen unknown".
 */
data class ScreenContext(
    val packageName: String?,
    val activityName: String?,
    val visibleText: String,
    val nodes: List<NodeSnapshot>,
    val clickableNodes: List<NodeSnapshot>,
    val editableNodes: List<NodeSnapshot>,
    val contentDescriptions: List<String>,
    val focusedNode: NodeSnapshot?,
    val screenshotAvailable: Boolean,
    val timestamp: Long
) {

    val isAvailable: Boolean
        get() = packageName != null || nodes.isNotEmpty() || visibleText.isNotBlank()

    /** True when the screen carries a password field — tools must refuse to type. */
    val hasPasswordField: Boolean
        get() = nodes.any { it.className?.contains("password", ignoreCase = true) == true }

    companion object {

        /**
         * Builds a context from the live accessibility service. Never throws:
         * every Android call is guarded because [AccessibilityService.rootInActiveWindow]
         * is null whenever the service is not connected to a window.
         */
        fun capture(service: AccessibilityService): ScreenContext {
            val reader = ScreenReader(service)
            return capture(reader, service)
        }

        /** Test-friendly overload: takes an injected [ScreenReader]. */
        fun capture(reader: ScreenReader, service: AccessibilityService): ScreenContext {
            val root: AccessibilityNodeInfo? = runCatching {
                service.rootInActiveWindow
            }.getOrNull()

            val snapshot: NodeSnapshot? = runCatching { reader.snapshot() }.getOrNull()
                ?: runCatching { com.jarvis.ai.accessibility.ScreenSnapshot.capture(root) }.getOrNull()

            val all = flatten(snapshot)
            val visible = reader.extractAllText()

            return ScreenContext(
                packageName = root?.packageName?.toString() ?: snapshot?.packageName,
                // Android deliberately does NOT expose the foreground activity
                // name through the accessibility tree. Reporting null here is the
                // honest answer; callers must not infer it from the package.
                activityName = null,
                visibleText = visible,
                nodes = all,
                clickableNodes = all.filter { it.clickable && it.visible },
                editableNodes = all.filter { it.editable && it.enabled },
                contentDescriptions = all.mapNotNull { it.contentDescription?.takeIf { d -> d.isNotBlank() } },
                focusedNode = all.firstOrNull { it.focusable && it.selected },
                screenshotAvailable = false,
                timestamp = System.currentTimeMillis()
            )
        }

        /** Depth-first flattening of the node tree into a list. */
        private fun flatten(root: NodeSnapshot?): List<NodeSnapshot> {
            if (root == null) return emptyList()
            val out = mutableListOf<NodeSnapshot>()
            val stack = ArrayDeque<NodeSnapshot>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                out += n
                for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
            }
            return out
        }

        /** Empty context — the honest answer when the screen cannot be read. */
        fun unknown(): ScreenContext = ScreenContext(
            packageName = null,
            activityName = null,
            visibleText = "",
            nodes = emptyList(),
            clickableNodes = emptyList(),
            editableNodes = emptyList(),
            contentDescriptions = emptyList(),
            focusedNode = null,
            screenshotAvailable = false,
            timestamp = System.currentTimeMillis()
        )
    }
}
