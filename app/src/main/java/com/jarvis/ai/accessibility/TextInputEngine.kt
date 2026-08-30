package com.jarvis.ai.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Production-grade text input. Supports multiple fields, ID/hint/text/content-desc
 * targeting (handled by [NodeResolver] + [TextInputEngine.findTarget]), and the
 * set / clear / append / replace modes required by the planner.
 */
class TextInputEngine {

    fun setText(node: AccessibilityNodeInfo, text: CharSequence): Boolean {
        if (!node.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun clear(node: AccessibilityNodeInfo): Boolean = setText(node, "")

    fun append(node: AccessibilityNodeInfo, text: CharSequence): Boolean {
        val current = node.text?.toString() ?: ""
        return setText(node, current + text)
    }

    fun replace(node: AccessibilityNodeInfo, old: CharSequence, new: CharSequence): Boolean {
        val current = node.text?.toString() ?: ""
        return setText(node, current.replaceFirst(old.toString().toRegex(), new.toString()))
    }

    fun getText(node: AccessibilityNodeInfo): String = node.text?.toString() ?: ""
}
