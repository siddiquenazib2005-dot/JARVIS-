package com.jarvis.ai.core.agent

data class ApprovalConfig(
    val mode: ApprovalMode = ApprovalMode.OFF,
    val grantedActions: Map<String, Long> = emptyMap()
) {
    fun effectiveGrantedActions(): Set<String> = grantedActions.keys.toSet()
}

enum class ApprovalMode {
    OFF,    // Every plan needs manual approval
    AUTO,   // Allowlisted plans run automatically
    YOLO    // Plans run automatically except destructive actions
}

object AutoApprovalPolicy {
    fun shouldAutoApprove(approval: ApprovalConfig, grantedActions: Set<String>, plan: Plan): Boolean {
        if (approval.mode == ApprovalMode.YOLO) {
            return !hasDestructiveActions(plan)
        }
        if (approval.mode == ApprovalMode.AUTO) {
            return plan.steps.all { step ->
                step.action in grantedActions || isSafeAction(step.action)
            }
        }
        return false
    }

    private fun hasDestructiveActions(plan: Plan): Boolean {
        return plan.steps.any { step ->
            step.action in DESTRUCTIVE_ACTIONS || step.action in HIGH_RISK_ACTIONS
        }
    }

    private fun isSafeAction(action: String): Boolean = action in SAFE_ACTIONS

    private val SAFE_ACTIONS = setOf(
        "OPEN_APP", "OPEN_SETTINGS", "TAKE_SCREENSHOT", "WEB_SEARCH", "GET_WEATHER",
        "GET_NEWS", "CALCULATE", "GET_DIRECTIONS", "PLAY_MUSIC", "PAUSE_MUSIC",
        "NEXT_TRACK", "PREV_TRACK", "READ_SCREEN", "ANALYZE_SCREENSHOT", "OPEN_URL",
        "SET_ALARM", "SET_TIMER", "TOGGLE_FLASHLIGHT", "GET_BATTERY_STATUS",
        "GET_DEVICE_STATUS", "GET_SYSTEM_INFO", "TAKE_SCREENSHOT", "READ_SCREEN",
        "ANALYZE_SCREENSHOT", "OPEN_URL", "SET_BRIGHTNESS", "SET_VOLUME",
        "TOGGLE_WIFI", "TOGGLE_BLUETOOTH", "TOGGLE_FLASHLIGHT", "TOGGLE_DND"
    )

    private val DESTRUCTIVE_ACTIONS = setOf(
        "RESTART_DEVICE", "SET_WALLPAPER", "RECORD_SCREEN", "INSTALL_APP",
        "CLEAR_BROWSER_DATA", "ORDER_FOOD", "ORDER_GROCERY", "PAY_UPI",
        "SPLIT_BILL", "SMART_HOME", "LOCK_DOOR", "DELETE_MACRO"
    )

    private val HIGH_RISK_ACTIONS = setOf(
        "MAKE_CALL", "SEND_SMS", "SEND_WHATSAPP", "SEND_TELEGRAM", "SEND_EMAIL",
        "MAKE_VIDEO_CALL", "SEND_WHATSAPP_GROUP", "OPEN_TELEGRAM",
        "BOOK_UBER", "BOOK_OLA", "ORDER_FOOD", "ORDER_GROCERY", "PAY_UPI",
        "SPLIT_BILL", "LOCK_DOOR", "RESTART_DEVICE", "INSTALL_APP",
        "CLEAR_BROWSER_DATA", "DELETE_MACRO", "CLOSE_APP"
    )

    fun isGrantable(action: String): Boolean = action !in DESTRUCTIVE_ACTIONS

    fun isHighRisk(action: String): Boolean = action in HIGH_RISK_ACTIONS

    fun isDestructive(action: String): Boolean = action in DESTRUCTIVE_ACTIONS
}