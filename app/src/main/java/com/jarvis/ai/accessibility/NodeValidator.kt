package com.jarvis.ai.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Validates a node immediately before acting on it. UI can change between
 * resolution and execution, so callers must re-validate (and re-resolve on failure).
 */
object NodeValidator {

    data class Outcome(val valid: Boolean, val reason: String? = null)

    fun validate(
        node: AccessibilityNodeInfo?,
        requireVisible: Boolean = true,
        requireEnabled: Boolean = true,
        requireClickable: Boolean = false,
        requireEditable: Boolean = false,
        requireFocusable: Boolean = false,
        expectedPackage: String? = null
    ): Outcome {
        if (node == null) return Outcome(false, "node is null")
        if (requireVisible && !node.isVisibleToUser) return Outcome(false, "node not visible")
        if (requireEnabled && !node.isEnabled) return Outcome(false, "node disabled")
        if (requireClickable && !(node.isClickable || node.isLongClickable))
            return Outcome(false, "node not clickable/long-clickable")
        if (requireEditable && !node.isEditable) return Outcome(false, "node not editable")
        if (requireFocusable && !node.isFocusable) return Outcome(false, "node not focusable")
        if (expectedPackage != null) {
            val pkg = node.packageName?.toString()
            if (pkg != null && pkg != expectedPackage)
                return Outcome(false, "node belongs to $pkg, expected $expectedPackage")
        }
        return Outcome(true)
    }

    /** Refresh the node in case the underlying tree changed; returns false if stale/dead. */
    fun isAlive(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            node.refresh() || node.isVisibleToUser || node.packageName != null
        } catch (_: Exception) {
            false
        }
    }
}
