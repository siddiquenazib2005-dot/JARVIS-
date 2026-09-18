package com.jarvis.ai.orchestrator

import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.data.model.SessionInfo
import java.util.concurrent.atomic.AtomicReference

/**
 * Describes a tool that the orchestrator can execute.
 *
 * Phase 2 adds explicit execution metadata so the mission layer can bound and
 * verify device work instead of trusting a non-throwing call:
 *  - [timeoutMs]: the wall-clock budget; exceeding it must cancel the action.
 *  - [verificationStrategy]: how the mission layer proves the action landed.
 *  - [maxRetries]: bounded retries; never infinite.
 *  - [requiresAccessibility]: the tool cannot run without the service connected.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val category: String,
    val parameters: Map<String, String>,
    val permission: String?,
    val riskLevel: RiskLevel,
    val confirmationRequired: Boolean,
    val confirmationMessage: String?,
    val timeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
    val verificationStrategy: VerificationStrategy = VerificationStrategy.NONE,
    val maxRetries: Int = 0,
    val requiresAccessibility: Boolean = false
) {
    companion object {
        /** Default budget for a single tool call. Device actions must not hang. */
        const val DEFAULT_TOOL_TIMEOUT_MS = 8_000L
    }
}

/**
 * How the mission layer verifies a tool's effect instead of assuming success.
 * Mirrors [com.jarvis.ai.accessibility.VerificationStatus] semantics.
 */
enum class VerificationStrategy {
    /** No verification possible; the tool's return value is the only signal. */
    NONE,
    /** The effect is observable in the accessibility tree after the action. */
    SCREEN_STATE,
    /** A foreground-package check confirms the target app came to front. */
    FOREGROUND_PACKAGE,
    /** The tool itself returns a verified/failed status (e.g. A11yResult). */
    SELF_REPORTED
}

enum class RiskLevel {
    LOW, MEDIUM, HIGH
}

/** Registry of available tools discoverable by the orchestrator. */
object ToolRegistry {

    /** All available tools registered with the orchestrator. */
    private val toolMap = mutableMapOf(
        "open_app" to ToolDefinition(
            name = "open_app",
            description = "Open an Android application by package name",
            category = "system",
            parameters = mapOf(
                "packageName" to "The package name of the application (e.g., 'com.package.name')",
                "className" to "Optional class name within the activity"
            ),
            permission = "android.permission.PACKAGE_USAGE_STATS",
            riskLevel = RiskLevel.MEDIUM,
            confirmationRequired = true,
            confirmationMessage = "Do you want to open this application?",
            timeoutMs = 6_000L,
            // Verified by checking the foreground package actually changed.
            verificationStrategy = VerificationStrategy.FOREGROUND_PACKAGE,
            maxRetries = 1,
            requiresAccessibility = true
        ),
        "open_url" to ToolDefinition(
            name = "open_url",
            description = "Open a URL in the default web browser",
            category = "system",
            parameters = mapOf(
                "url" to "The URL to open (must include http:// or https://)"
            ),
            permission = "android.permission.INTERNET",
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null
        ),
        "open_settings" to ToolDefinition(
            name = "open_settings",
            description = "Open Android Settings page",
            category = "system",
            parameters = mapOf(
                "settingsPath" to "Optional settings path (e.g., 'wifi', 'bluetooth', 'apps')"
            ),
            permission = "android.permission.Settings",
            riskLevel = RiskLevel.LOW,
            confirmationRequired = true,
            confirmationMessage = "Do you want to open Android Settings?"
        ),
        "get_battery_status" to ToolDefinition(
            name = "get_battery_status",
            description = "Get device battery level and charging status",
            category = "device",
            parameters = mapOf(),
            permission = null,
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null
        ),
        "get_device_status" to ToolDefinition(
            name = "get_device_status",
            description = "Get basic device status information",
            category = "device",
            parameters = mapOf(),
            permission = null,
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null
        ),
        "calculate" to ToolDefinition(
            name = "calculate",
            description = "Evaluate a mathematical expression",
            category = "math",
            parameters = mapOf(
                "expression" to "The mathematical expression to evaluate (e.g., '25*48')"
            ),
            permission = null,
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null
        ),
        "send_sms" to ToolDefinition(
            name = "send_sms",
            description = "Send an SMS message to a phone number",
            category = "messaging",
            parameters = mapOf(
                "contact" to "Contact name or phone number",
                "message" to "The message to send"
            ),
            permission = "android.permission.SEND_SMS",
            riskLevel = RiskLevel.MEDIUM,
            confirmationRequired = true,
            confirmationMessage = "Do you want to send this SMS?"
        ),
        "send_whatsapp" to ToolDefinition(
            name = "send_whatsapp",
            description = "Send a WhatsApp message to a contact",
            category = "messaging",
            parameters = mapOf(
                "contact" to "Contact name or phone number",
                "message" to "The message to send"
            ),
            permission = null,
            riskLevel = RiskLevel.MEDIUM,
            confirmationRequired = true,
            confirmationMessage = "Do you want to send this WhatsApp message?"
        ),
        "send_email" to ToolDefinition(
            name = "send_email",
            description = "Send an email directly over SMTP to an email address",
            category = "messaging",
            parameters = mapOf(
                "to" to "Recipient email address",
                "subject" to "Subject line",
                "body" to "The message body"
            ),
            permission = null,
            riskLevel = RiskLevel.MEDIUM,
            confirmationRequired = true,
            confirmationMessage = "Do you want to send this email?"
        ),
        "call_contact" to ToolDefinition(
            name = "call_contact",
            description = "Call a contact or phone number",
            category = "phone",
            parameters = mapOf(
                "contact" to "Contact name or phone number"
            ),
            permission = "android.permission.CALL_PHONE",
            riskLevel = RiskLevel.MEDIUM,
            confirmationRequired = true,
            confirmationMessage = "Do you want to place this call?"
        ),
        "memory_read" to ToolDefinition(
            name = "memory_read",
            description = "Read stored memories",
            category = "memory",
            parameters = mapOf("query" to "What to search for in memory"),
            permission = null,
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null
        ),
        "memory_write" to ToolDefinition(
            name = "memory_write",
            description = "Store a new memory",
            category = "memory",
            parameters = mapOf("content" to "The content to remember"),
            permission = null,
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null
        ),
        "memory_delete" to ToolDefinition(
            name = "memory_delete",
            description = "Delete a specific memory",
            category = "memory",
            parameters = mapOf("memoryId" to "ID of the memory to delete"),
            permission = null,
            riskLevel = RiskLevel.MEDIUM,
            confirmationRequired = true,
            confirmationMessage = "Do you want to delete this memory?"
        ),
        "memory_wipe_all" to ToolDefinition(
            name = "memory_wipe_all",
            description = "Wipe all stored memories",
            category = "memory",
            parameters = mapOf(),
            permission = null,
            riskLevel = RiskLevel.HIGH,
            confirmationRequired = true,
            confirmationMessage = "This will permanently erase ALL memories. Are you sure?"
        ),
        // ---- Phase 2: first-class device tools ---------------------------------
        // These wrap the accessibility primitives (GestureEngine / ScrollEngine /
        // TextInputEngine / ScreenReader). Each declares how its effect is
        // VERIFIED, so a tool that returns without throwing is never alone taken
        // as success. None of these fake work: if the accessibility service is
        // not connected, the executor reports unsupported instead of pretending.
        // (open_app is already registered above with FOREGROUND_PACKAGE verification.)
        "press_back" to ToolDefinition(
            name = "press_back",
            description = "Press the Android Back key",
            category = "device",
            parameters = mapOf(),
            permission = null,
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null,
            timeoutMs = 2_000L,
            verificationStrategy = VerificationStrategy.SELF_REPORTED,
            maxRetries = 0,
            requiresAccessibility = true
        ),
        "press_home" to ToolDefinition(
            name = "press_home",
            description = "Press the Android Home key",
            category = "device",
            parameters = mapOf(),
            permission = null,
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null,
            timeoutMs = 2_000L,
            verificationStrategy = VerificationStrategy.SELF_REPORTED,
            maxRetries = 0,
            requiresAccessibility = true
        ),
        "tap" to ToolDefinition(
            name = "tap",
            description = "Tap a screen element identified by its visible text",
            category = "device",
            parameters = mapOf(
                "text" to "Visible text of the element to tap"
            ),
            permission = null,
            riskLevel = RiskLevel.MEDIUM,
            confirmationRequired = false,
            confirmationMessage = null,
            timeoutMs = 5_000L,
            verificationStrategy = VerificationStrategy.SCREEN_STATE,
            maxRetries = 1,
            requiresAccessibility = true
        ),
        "long_press" to ToolDefinition(
            name = "long_press",
            description = "Long-press a screen element identified by its visible text",
            category = "device",
            parameters = mapOf(
                "text" to "Visible text of the element to long-press"
            ),
            permission = null,
            riskLevel = RiskLevel.MEDIUM,
            confirmationRequired = false,
            confirmationMessage = null,
            timeoutMs = 6_000L,
            verificationStrategy = VerificationStrategy.SCREEN_STATE,
            maxRetries = 1,
            requiresAccessibility = true
        ),
        "swipe" to ToolDefinition(
            name = "swipe",
            description = "Swipe in a direction (up, down, left, right)",
            category = "device",
            parameters = mapOf(
                "direction" to "up | down | left | right"
            ),
            permission = null,
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null,
            timeoutMs = 4_000L,
            verificationStrategy = VerificationStrategy.SELF_REPORTED,
            maxRetries = 0,
            requiresAccessibility = true
        ),
        "scroll" to ToolDefinition(
            name = "scroll",
            description = "Scroll the current screen in a direction",
            category = "device",
            parameters = mapOf(
                "direction" to "up | down"
            ),
            permission = null,
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null,
            timeoutMs = 5_000L,
            verificationStrategy = VerificationStrategy.SELF_REPORTED,
            maxRetries = 0,
            requiresAccessibility = true
        ),
        "type_text" to ToolDefinition(
            name = "type_text",
            description = "Type text into the focused editable field",
            category = "device",
            parameters = mapOf(
                "text" to "The text to enter"
            ),
            permission = null,
            riskLevel = RiskLevel.MEDIUM,
            confirmationRequired = false,
            confirmationMessage = null,
            timeoutMs = 5_000L,
            verificationStrategy = VerificationStrategy.SCREEN_STATE,
            maxRetries = 1,
            requiresAccessibility = true
        ),
        "read_screen" to ToolDefinition(
            name = "read_screen",
            description = "Read the visible text of the current screen",
            category = "screen",
            parameters = mapOf(),
            permission = null,
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null,
            timeoutMs = 4_000L,
            verificationStrategy = VerificationStrategy.SELF_REPORTED,
            maxRetries = 0,
            requiresAccessibility = true
        ),
        "find_element" to ToolDefinition(
            name = "find_element",
            description = "Locate a screen element by text and report whether it exists",
            category = "screen",
            parameters = mapOf(
                "text" to "Visible text to search for"
            ),
            permission = null,
            riskLevel = RiskLevel.LOW,
            confirmationRequired = false,
            confirmationMessage = null,
            timeoutMs = 4_000L,
            verificationStrategy = VerificationStrategy.SELF_REPORTED,
            maxRetries = 0,
            requiresAccessibility = true
        )
    )

    /** Returns whether a tool is available for the given intent. */
    fun isAvailable(intent: String): Boolean = toolMap.containsKey(intent)

    /** Returns the tool definition for the given intent, or null. */
    fun getTool(intent: String): ToolDefinition? = toolMap[intent]

    /** Returns all registered tool names. */
    fun getAllToolNames(): List<String> = toolMap.keys.toList()

    /** Returns the tool definition for the given intent. */
    fun getToolDetails(intent: String): ToolDefinition? = toolMap[intent]

    /** Returns whether confirmation is required for the given tool. */
    fun requiresConfirmation(intent: String): Boolean = toolMap[intent]?.confirmationRequired ?: false

    /** Returns the confirmation message for the given tool. */
    fun getConfirmationMessage(intent: String): String? = toolMap[intent]?.confirmationMessage
}

/** Returns the tool definition for the given intent. */
fun tool(intent: String): ToolDefinition? = ToolRegistry.getTool(intent)

/** Returns all tool definitions. */
fun allTools(): List<ToolDefinition> = ToolRegistry.getAllToolNames().mapNotNull { ToolRegistry.getTool(it) }
