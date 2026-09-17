package com.jarvis.ai.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Structured, privacy-aware logging. Never emits passwords, OTPs, tokens, or
 * private message contents; long values are truncated.
 */
object AccessibilityLogger {
    private const val TAG = "JARVIS_A11Y"

    private val SENSITIVE = listOf(
        Regex("(?i)\\botp\\b"),
        Regex("(?i)\\btoken\\b"),
        Regex("(?i)password"),
        Regex("(?i)\\bpin\\b"),
        Regex("(?i)secret")
    )

    fun command(what: String) = Log.i(TAG, "COMMAND: $what")
    fun resolution(target: String, candidates: Int, chosen: String?) =
        Log.d(TAG, "RESOLUTION: target='$target' candidates=$candidates chosen=${chosen ?: "none"}")
    fun action(action: String, target: String, attempt: Int = 1) =
        Log.d(TAG, "ACTION: $action target='$target' attempt=$attempt")
    fun result(success: Boolean, code: String?, attempts: Int) =
        Log.d(TAG, "RESULT: success=$success code=${code ?: "-"} attempts=$attempts")
    fun verification(status: String, detail: String?) =
        Log.d(TAG, "VERIFICATION: $status ${detail ?: ""}")
    fun error(code: String, message: String) = Log.w(TAG, "ERROR: code=$code msg=${redact(message)}")

    /** Scrubs secrets and truncates long payloads before logging. */
    fun redact(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var out: String = text
        for (p in SENSITIVE) out = out.replace(p, "***")
        if (out.length > 120) out = out.take(120) + "...(truncated)"
        return out
    }
}

/** Lightweight immutable snapshot of a node subtree for planner reasoning. */
data class NodeSnapshot(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val packageName: String?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val focusable: Boolean,
    val visible: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val checked: Boolean?,
    val bounds: Rect,
    val children: List<NodeSnapshot>
)

object ScreenSnapshot {
    fun capture(root: AccessibilityNodeInfo?): NodeSnapshot? = root?.let { captureNode(it) }

    private fun captureNode(node: AccessibilityNodeInfo): NodeSnapshot {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val children = (0 until node.childCount).mapNotNull { i ->
            node.getChild(i)?.let { captureNode(it) }
        }
        return NodeSnapshot(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            viewId = node.viewIdResourceName,
            packageName = node.packageName?.toString(),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            focusable = node.isFocusable,
            visible = node.isVisibleToUser,
            enabled = node.isEnabled,
            selected = node.isSelected,
            checked = if (node.isCheckable) node.isChecked else null,
            bounds = bounds,
            children = children
        )
    }
}
