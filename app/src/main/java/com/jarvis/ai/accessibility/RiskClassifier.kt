package com.jarvis.ai.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/** Risk classification for accessibility actions (see hardening spec phase 14). */
enum class RiskLevel { LOW, MEDIUM, HIGH }

object RiskClassifier {
    /**
     * Classify a device-automation action. The accessibility engine mostly performs
     * LOW/MEDIUM risk operations; destructive intents (send, delete, pay) are gated
     * upstream by the orchestrator [com.jarvis.ai.security.PermissionGate].
     */
    fun classify(action: String): RiskLevel = when (action.lowercase()) {
        "type", "clear_type", "type_text" -> RiskLevel.MEDIUM
        "settings_change", "delete_item", "toggle_wifi", "toggle_bluetooth" -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }

    fun requiresConfirmation(level: RiskLevel): Boolean = level == RiskLevel.HIGH
}
