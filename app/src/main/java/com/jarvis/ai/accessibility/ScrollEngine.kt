package com.jarvis.ai.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/** Semantic (accessibility-action) scrolling with gesture fallback handled by the caller. */
class ScrollEngine {

    fun findScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isScrollable) return root
        repeat(root.childCount) {
            val child = root.getChild(it) ?: return@repeat
            val found = findScrollable(child)
            if (found != null) return found
        }
        return null
    }

    fun scroll(node: AccessibilityNodeInfo, forward: Boolean): Boolean =
        node.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
}
