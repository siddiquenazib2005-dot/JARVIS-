package com.jarvis.ai.orchestrator

import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.data.model.SessionInfo
import java.util.concurrent.atomic.AtomicReference

/** Describes a tool that the orchestrator can execute. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val category: String,
    val parameters: Map<String, String>,
    val permission: String?,
    val riskLevel: RiskLevel,
    val confirmationRequired: Boolean,
    val confirmationMessage: String?
)

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
            confirmationMessage = "Do you want to open this application?"
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

/** Returns whether a tool can handle the given intent. */
fun canHandle(intent: String): Boolean = ToolRegistry.isAvailable(intent)