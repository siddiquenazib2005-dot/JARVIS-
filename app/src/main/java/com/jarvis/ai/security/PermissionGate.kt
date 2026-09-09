package com.jarvis.ai.security

import com.jarvis.ai.provider.SecretRedactor

/** Four-tier permission model for every tool/action. */
enum class PermissionLevel {
    READ_ONLY,        // automatic
    LOW_RISK,         // automatic (writes that stay inside AURIX data)
    CONFIRM_REQUIRED, // explicit user confirmation
    HIGH_RISK         // explicit confirmation + never auto-executed
}

data class GateDecision(
    val level: PermissionLevel,
    val allowedWithoutConfirmation: Boolean,
    val requiresConfirmation: Boolean,
    val denied: Boolean,
    val message: String
)

/**
 * Central permission gate. The AI can REQUEST an action; it can never bypass
 * this gate. Destructive/high-risk actions are refused unless the user has
 * explicitly confirmed in the current turn.
 */
object ToolPermissions {

private val map: Map<String, PermissionLevel> = buildMap {
        put("calculate", PermissionLevel.READ_ONLY)
        put("get_time", PermissionLevel.READ_ONLY)
        put("get_battery_status", PermissionLevel.READ_ONLY)
        put("get_device_status", PermissionLevel.READ_ONLY)
        put("web_search", PermissionLevel.READ_ONLY)
        put("news_search", PermissionLevel.READ_ONLY)
        put("weather", PermissionLevel.READ_ONLY)
        put("maps_geocode", PermissionLevel.READ_ONLY)
        put("finance_quote", PermissionLevel.READ_ONLY)
        put("space_apod", PermissionLevel.READ_ONLY)
        put("memory_read", PermissionLevel.READ_ONLY)
        put("memory_write", PermissionLevel.LOW_RISK)
        put("open_url", PermissionLevel.READ_ONLY)
        put("open_app", PermissionLevel.LOW_RISK)
        put("open_settings", PermissionLevel.LOW_RISK)
        put("memory_delete", PermissionLevel.CONFIRM_REQUIRED)
        put("memory_wipe_all", PermissionLevel.HIGH_RISK)
        put("device_automation", PermissionLevel.CONFIRM_REQUIRED)
        put("file_write", PermissionLevel.HIGH_RISK)
        put("shell", PermissionLevel.HIGH_RISK)
    }

    fun levelFor(toolName: String): PermissionLevel =
        map[toolName.lowercase()] ?: PermissionLevel.CONFIRM_REQUIRED
}

object PermissionGate {

    fun decide(toolName: String, userConfirmedThisTurn: Boolean = false): GateDecision {
        val level = ToolPermissions.levelFor(toolName)
        return when (level) {
            PermissionLevel.READ_ONLY -> GateDecision(
                level, allowedWithoutConfirmation = true, requiresConfirmation = false, denied = false,
                message = "read-only action"
            )
            PermissionLevel.LOW_RISK -> GateDecision(
                level, allowedWithoutConfirmation = true, requiresConfirmation = false, denied = false,
                message = "low-risk write inside AURIX storage"
            )
            PermissionLevel.CONFIRM_REQUIRED -> if (userConfirmedThisTurn) {
                GateDecision(level, true, false, false, "user confirmed")
            } else {
                GateDecision(level, false, true, false, "Confirmation needed: $toolName — shall I proceed?")
            }
            PermissionLevel.HIGH_RISK -> if (userConfirmedThisTurn) {
                // User explicitly confirmed in this turn → may execute NOW.
                // (Previously allowedWithoutConfirmation stayed false here, so callers
                // that gate on it never reached the destructive action even after the
                // user repeatedly confirmed — "forget all my memories" never ran.)
                GateDecision(level, true, false, false, "user explicitly confirmed high-risk action")
            } else {
                GateDecision(
                    level, false, true, denied = true,
                    message = "$toolName is high-risk and was blocked pending explicit confirmation."
                )
            }
        }
    }
}

/**
 * Append-only audit trail. Entries are redacted before storage so secrets can
 * never leak through the audit surface either.
 */
object AuditLog {
    private const val CAPACITY = 200
    private val buffer = ArrayDeque<String>(CAPACITY)

    @Synchronized
    fun record(toolName: String, decision: GateDecision, detail: String = "") {
        if (buffer.size >= CAPACITY) buffer.removeFirst()
        val line = "[${System.currentTimeMillis()}] $toolName → ${decision.level} " +
            "(auto=${decision.allowedWithoutConfirmation}, denied=${decision.denied})" +
            detail.take(160).let { " | ${SecretRedactor.redact(it)}" }
        buffer.addLast(line)
    }

    @Synchronized
    fun recent(count: Int): List<String> = buffer.toList().takeLast(count)

    @Synchronized
    fun clear() = buffer.clear()
}
