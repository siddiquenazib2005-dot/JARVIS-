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