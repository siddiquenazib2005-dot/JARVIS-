package com.jarvis.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the active window for the agent and for wait/verify checks.
 *
 * Hardening vs. the original:
 *  - Tolerates a null/transient root instead of throwing.
 *  - De-duplicates text (containers expose child text too).
 *  - Skips password fields and nodes flagged sensitive so secrets never enter
 *    prompt context or logs (phase 16).
 *  - Exposes a structured [NodeSnapshot] tree for planner reasoning (phase 15).
 */
class ScreenReader(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "JarvisScreenReader"
    }

    fun extractAllText(): String {
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        if (root == null) {
            Log.w(TAG, "Screen hierarchy unavailable")
            return ""
        }
        val sb = StringBuilder()
        val seen = LinkedHashSet<String>()
        traverse(root, sb, seen, depth = 0)
        return sb.toString().trim()
    }

    /** Structured capture of the current screen (no private payloads logged). */
    fun snapshot(): NodeSnapshot? = ScreenSnapshot.capture(runCatching { service.rootInActiveWindow }.getOrNull())

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        return NodeResolver.resolve(root, text)
    }

    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        return NodeResolver.findById(root, viewId)
    }

    private fun isSensitive(node: AccessibilityNodeInfo): Boolean = runCatching {
        node.isPassword ||
            node.className?.toString()?.contains("password", ignoreCase = true) == true
    }.getOrDefault(false)

    private fun traverse(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        seen: LinkedHashSet<String>,
        depth: Int
    ) {
        if (depth > 60) return
        if (!isSensitive(node)) {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { addUnique(seen, sb, it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { addUnique(seen, sb, it) }
        }
        repeat(node.childCount) {
            runCatching { node.getChild(it) }.getOrNull()?.let { traverse(it, sb, seen, depth + 1) }
        }
    }

    private fun addUnique(seen: LinkedHashSet<String>, sb: StringBuilder, text: String) {
        val key = text.lowercase().trim()
        if (seen.add(key)) sb.append(text).append("\n")
    }
}
