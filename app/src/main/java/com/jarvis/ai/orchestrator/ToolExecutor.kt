package com.jarvis.ai.orchestrator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CancellationException

/** Result of a tool execution. */
sealed class ToolResult {
    data class Success(
        val toolName: String,
        val data: Map<String, Any?>,
        val message: String
    ) : ToolResult()

    data class Failure(
        val toolName: String,
        val error: String,
        val recoverable: Boolean
    ) : ToolResult()

    data class ConfirmationRequired(
        val toolName: String,
        val message: String
    ) : ToolResult()
}

/** Centralized tool execution with permission checking and result verification. */
class ToolExecutor(private val context: Context) {

    private val TAG = "JarvisToolExecutor"
    private val executionCount = AtomicInteger(0)
    private val MAX_RETRIES = 2

    /**
     * Executes a tool behind the [com.jarvis.ai.security.PermissionGate].
     * No tool runs without passing the gate: READ_ONLY/LOW_RISK proceed
     * automatically, CONFIRM_REQUIRED needs an explicit user confirmation for
     * the current turn, HIGH_RISK is refused outright when unconfirmed and
     * audited either way. Every decision lands in the append-only AuditLog.
     *
     * "send_sms" and "send_whatsapp" are not in ToolPermissions' explicit map,
     * so they fall through to the map's default — CONFIRM_REQUIRED — which is
     * exactly right for anything that sends a message to another person.
     */
    suspend fun executeTool(
        toolName: String,
        parameters: Map<String, Any?>,
        userConfirmedThisTurn: Boolean = false
    ): ToolResult {
        val gate = com.jarvis.ai.security.PermissionGate.decide(toolName, userConfirmedThisTurn)
        com.jarvis.ai.security.AuditLog.record(toolName, gate, parameters.toString())
        return when {
            gate.denied -> ToolResult.Failure(
                toolName = toolName,
                error = gate.message,
                recoverable = false
            )
            gate.requiresConfirmation -> ToolResult.ConfirmationRequired(
                toolName = toolName,
                message = gate.message
            )
            !validateParameters(toolName, parameters) -> ToolResult.Failure(
                toolName = toolName,
                error = "Invalid or missing parameters",
                recoverable = false
            )
            else -> {
                com.jarvis.ai.core.EventBus.publish(
                    com.jarvis.ai.core.EventType.ACTION_STARTED, toolName
                )
                val result = runBody(toolName, parameters)
                com.jarvis.ai.core.EventBus.publish(
                    if (result is ToolResult.Success) com.jarvis.ai.core.EventType.ACTION_COMPLETED
                    else com.jarvis.ai.core.EventType.PROVIDER_FAILED,
                    "$toolName:${result.javaClass.simpleName}"
                )
                result
            }
        }
    }

    private suspend fun runBody(toolName: String, parameters: Map<String, Any?>): ToolResult =
        withContext(Dispatchers.IO) {
            when (toolName) {
                "open_app" -> executeOpenApp(parameters)
                "open_url" -> executeOpenUrl(parameters)
                "open_settings" -> executeOpenSettings(parameters)
                "get_battery_status" -> executeGetBatteryStatus(parameters)
                "get_device_status" -> executeGetDeviceStatus(parameters)
                "calculate" -> executeCalculate(parameters)
                "send_sms" -> executeSendSms(parameters)
                "send_whatsapp" -> executeSendWhatsapp(parameters)
                "call_contact" -> executeCallContact(parameters)
                else -> ToolResult.Failure(
                    toolName = toolName,
                    error = "Unknown tool: $toolName",
                    recoverable = false
                )
            }
        }

    /** Validates parameters for a tool before execution. */
    private fun validateParameters(toolName: String, parameters: Map<String, Any?>): Boolean {
        return when (toolName) {
            "open_app" -> {
                val raw = parameters["packageName"] ?: parameters["appName"] ?: parameters["command"]
                raw is String && raw.isNotBlank()
            }
            "open_url" -> parameters["url"] is String
            "open_settings" -> true
            "get_battery_status" -> true
            "get_device_status" -> true
            "calculate" -> parameters["expression"] is String
            "send_sms", "send_whatsapp" -> {
                val hasRecipient = (parameters["phoneNumber"] as? String)?.isNotBlank() == true ||
                    (parameters["contact"] as? String)?.isNotBlank() == true
                val hasMessage = (parameters["message"] as? String)?.isNotBlank() == true
                hasRecipient && hasMessage
            }
            "call_contact" -> {
                (parameters["phoneNumber"] as? String)?.isNotBlank() == true ||
                    (parameters["contact"] as? String)?.isNotBlank() == true
            }
            else -> false
        }
    }

    /**
     * Resolves an app identifier to a concrete package name.
     *
     * Delegates to [PackageResolver] (framework-agnostic, unit-tested) over an
     * [AppLookup] adapter around the real PackageManager. Keeps the debug logging
     * that helps diagnose package-visibility issues on Android 11+.
     */
    private fun resolvePackageByName(name: String): String? {
        Log.d(TAG, "resolvePackageByName: input '$name'")
        val resolved = PackageResolver.resolve(name, PackageManagerAppLookup.create(context.packageManager))
        Log.d(TAG, "resolvePackageByName: '$name' -> ${resolved ?: "NO MATCH"}")
        return resolved
    }

    /** Executes the open_app tool. */
    private suspend fun executeOpenApp(parameters: Map<String, Any?>): ToolResult {
        val raw = (parameters["packageName"] ?: parameters["appName"] ?: parameters["command"]) as? String
            ?: return ToolResult.Failure(
                toolName = "open_app",
                error = "Missing app name parameter",
                recoverable = false
            )
        Log.d(TAG, "executeOpenApp: raw input '$raw'")
        val className = parameters["className"] as? String

        val packageName = resolvePackageByName(raw)
            ?: return ToolResult.Failure(
                toolName = "open_app",
                error = "Application not found: $raw",
                recoverable = false
            )

        Log.d(TAG, "executeOpenApp: resolved package '$packageName' (raw input: '$raw')")
        return withContext(Dispatchers.IO) {
            try {
                val pm: PackageManager = context.packageManager
                Log.d(TAG, "executeOpenApp: checking launch intent for '$packageName'")
                val intent = pm.getLaunchIntentForPackage(packageName)
                Log.d(TAG, "executeOpenApp: launch intent null for '$packageName'")
                if (intent == null) {
                    ToolResult.Failure(
                        toolName = "open_app",
                        error = "Application not found: $packageName",
                        recoverable = false
                    )
                } else {
                    if (className != null) {
                        intent.setClassName(packageName, className)
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    Log.d(TAG, "executeOpenApp: launching resolved package '$packageName' (requested '$raw')")
                    context.startActivity(intent)
                    ToolResult.Success(
                        toolName = "open_app",
                        data = mapOf("packageName" to packageName),
                        message = "Opened $raw, sir."
                    )
                }
            } catch (e: Exception) {
                ToolResult.Failure(
                    toolName = "open_app",
                    error = "Failed to open app: ${e.message}",
                    recoverable = true
                )
            }
        }
    }

    /** Executes the open_url tool. */
    private suspend fun executeOpenUrl(parameters: Map<String, Any?>): ToolResult {
        val url = parameters["url"] as? String ?: return ToolResult.Failure(
            toolName = "open_url",
            error = "Missing url parameter",
            recoverable = false
        )

        // Validate URL format
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.Failure(
                toolName = "open_url",
                error = "URL must start with http:// or https://",
                recoverable = false
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ToolResult.Success(
                    toolName = "open_url",
                    data = mapOf("url" to url),
                    message = "Opened URL, sir."
                )
            } catch (e: Exception) {
                ToolResult.Failure(
                    toolName = "open_url",
                    error = "Failed to open URL: ${e.message}",
                    recoverable = true
                )
            }
        }
    }

    /** Executes the open_settings tool. */
    private suspend fun executeOpenSettings(parameters: Map<String, Any?>): ToolResult {
        val settingsPath = parameters["settingsPath"] as? String

        return withContext(Dispatchers.IO) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                if (settingsPath != null && settingsPath.isNotBlank()) {
                    // Try to open specific settings
                    intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = Uri.parse("package:${context.packageName}")
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ToolResult.Success(
                    toolName = "open_settings",
                    data = mapOf("settingsPath" to (settingsPath ?: "general")),
                    message = "Opened Settings, sir."
                )
            } catch (e: Exception) {
                ToolResult.Failure(
                    toolName = "open_settings",
                    error = "Failed to open settings: ${e.message}",
                    recoverable = true
                )
            }
        }
    }

    /** Executes the get_battery_status tool. */
    private suspend fun executeGetBatteryStatus(parameters: Map<String, Any?>): ToolResult {
        return withContext(Dispatchers.IO) {
            try {
                val batteryManager = context.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
                val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val isCharging = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    ?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
                    ?: false

                ToolResult.Success(
                    toolName = "get_battery_status",
                    data = mapOf(
                        "level" to level,
                        "charging" to isCharging
                    ),
                    message = "Battery at $level%, ${if (isCharging) "charging" else "discharging"}, sir."
                )
            } catch (e: Exception) {
                ToolResult.Failure(
                    toolName = "get_battery_status",
                    error = "Failed to get battery status: ${e.message}",
                    recoverable = true
                )
            }
        }
    }

    /** Executes the get_device_status tool. */
    private suspend fun executeGetDeviceStatus(parameters: Map<String, Any?>): ToolResult {
        return withContext(Dispatchers.IO) {
            try {
                val runtime = Runtime.getRuntime()
                val totalMem = runtime.totalMemory()
                val freeMem = runtime.freeMemory()
                val usedMem = totalMem - freeMem

                ToolResult.Success(
                    toolName = "get_device_status",
                    data = mapOf(
                        "totalMemory" to totalMem,
                        "freeMemory" to freeMem,
                        "usedMemory" to usedMem,
                        "availableProcessors" to Runtime.getRuntime().availableProcessors()
                    ),
                    message = "Device status retrieved, sir."
                )
            } catch (e: Exception) {
                ToolResult.Failure(
                    toolName = "get_device_status",
                    error = "Failed to get device status: ${e.message}",
                    recoverable = true
                )
            }
        }
    }

    /** Executes the calculate tool using the existing OfflineJarvisEngine. */
    private suspend fun executeCalculate(parameters: Map<String, Any?>): ToolResult {
        val expression = parameters["expression"] as? String ?: return ToolResult.Failure(
            toolName = "calculate",
            error = "Missing expression parameter",
            recoverable = false
        )

        // Use existing OfflineJarvisEngine for calculation
        val reply = com.jarvis.ai.data.repository.OfflineJarvisEngine.respond(expression)

        if (reply.startsWith("Computed")) {
            return ToolResult.Success(
                toolName = "calculate",
                data = mapOf("expression" to expression, "result" to reply),
                message = "Calculated, sir: $reply"
            )
        } else {
            return ToolResult.Failure(
                toolName = "calculate",
                error = "Could not calculate: $expression",
                recoverable = false
            )
        }
    }

    // ------------------------------------------------------------------
    // Recipient resolution (shared by send_sms and send_whatsapp)
    // ------------------------------------------------------------------

    /**
     * Resolves a recipient to a raw phone number, preferring an explicit
     * "phoneNumber" parameter over a "contact" name lookup via
     * [ContactsResolver]. Returns null (with the caller producing a Failure)
     * when neither is usable.
     */
    private fun resolveRecipientNumber(parameters: Map<String, Any?>): String? {
        val explicit = (parameters["phoneNumber"] as? String)?.trim()
        if (!explicit.isNullOrBlank()) return explicit

        val contactName = (parameters["contact"] as? String)?.trim()
        if (contactName.isNullOrBlank()) return null

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "resolveRecipientNumber: READ_CONTACTS not granted, cannot resolve '$contactName'")
            return null
        }
        return ContactsResolver.resolvePhoneNumber(context, contactName)
    }

    /** Executes the send_sms tool via SmsManager — no UI automation involved. */
    private suspend fun executeSendSms(parameters: Map<String, Any?>): ToolResult {
        val message = (parameters["message"] as? String)?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Failure(
                toolName = "send_sms", error = "Missing message parameter", recoverable = false
            )
        }
        val number = resolveRecipientNumber(parameters)
            ?: return ToolResult.Failure(
                toolName = "send_sms",
                error = "Could not resolve a phone number for the recipient",
                recoverable = false
            )

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.Failure(
                toolName = "send_sms",
                error = "SEND_SMS permission not granted. Enable it in Settings → Apps → JARVIS → Permissions.",
                recoverable = false
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val smsManager = SmsManager.getDefault()
                // Split long messages across multiple parts automatically.
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                ToolResult.Success(
                    toolName = "send_sms",
                    data = mapOf("to" to number),
                    message = "SMS sent, sir."
                )
            } catch (e: Exception) {
                ToolResult.Failure(
                    toolName = "send_sms",
                    error = "Failed to send SMS: ${e.message}",
                    recoverable = true
                )
            }
        }
    }

    /**
     * Executes the send_whatsapp tool.
     *
     * Strategy: build a `https://wa.me/<number>?text=<message>` deep link so
     * WhatsApp opens directly on the target chat with the message already
     * typed — no contact-search or text-typing automation needed, which
     * removes the two most fragile steps. The only automated UI action left
     * is a single tap on the Send button, targeted by its resource id
     * (`com.whatsapp:id/send`), which is far more stable across WhatsApp app
     * updates than matching on visible text.
     */
    private suspend fun executeSendWhatsapp(parameters: Map<String, Any?>): ToolResult {
        val message = (parameters["message"] as? String)?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Failure(
                toolName = "send_whatsapp", error = "Missing message parameter", recoverable = false
            )
        }
        val rawNumber = resolveRecipientNumber(parameters)
            ?: return ToolResult.Failure(
                toolName = "send_whatsapp",
                error = "Could not resolve a phone number for the recipient",
                recoverable = false
            )
        val digitsOnly = rawNumber.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length < 8) {
            return ToolResult.Failure(
                toolName = "send_whatsapp",
                error = "Resolved phone number looks invalid: $rawNumber",
                recoverable = false
            )
        }

        val service = JarvisAccessibilityService.instance
            ?: return ToolResult.Failure(
                toolName = "send_whatsapp",
                error = "Accessibility service not enabled. Enable JARVIS in Settings → Accessibility.",
                recoverable = false
            )

        val encodedMessage = withContext(Dispatchers.IO) {
            runCatching { URLEncoder.encode(message, "UTF-8") }.getOrDefault(message)
        }
        val uri = Uri.parse("https://wa.me/$digitsOnly?text=$encodedMessage")
        val intent = Intent(Intent.ACTION_VIEW, uri)

        val opened = service.launchIntentAndVerify(intent, "com.whatsapp")
        if (opened.isFailure) {
            return ToolResult.Failure(
                toolName = "send_whatsapp",
                error = opened.message ?: "Could not open WhatsApp chat",
                recoverable = true
            )
        }

        val sendButtonReady = service.waitForId("com.whatsapp:id/send", timeoutMs = 4000)
        if (sendButtonReady.isFailure) {
            return ToolResult.Failure(
                toolName = "send_whatsapp",
                error = "WhatsApp chat opened but Send button never appeared: ${sendButtonReady.message}",
                recoverable = true
            )
        }

        val tapped = service.tapById("com.whatsapp:id/send")
        return if (tapped.isFailure) {
            ToolResult.Failure(
                toolName = "send_whatsapp",
                error = "Message pre-filled but could not tap Send: ${tapped.message}",
                recoverable = true
            )
        } else {
            ToolResult.Success(
                toolName = "send_whatsapp",
                data = mapOf("to" to digitsOnly),
                message = "WhatsApp message sent, sir."
            )
        }
    }

    /**
     * Executes the call_contact tool via ACTION_CALL — places the call
     * directly (no dialer tap needed). Requires android.permission.CALL_PHONE
     * (dangerous permission, runtime-granted) on top of the PermissionGate
     * confirmation that already gates this tool by default (unmapped tool ->
     * CONFIRM_REQUIRED), so a call never goes out without an explicit
     * "yes" for the current turn.
     */
    private suspend fun executeCallContact(parameters: Map<String, Any?>): ToolResult {
        val rawNumber = resolveRecipientNumber(parameters)
            ?: return ToolResult.Failure(
                toolName = "call_contact",
                error = "Could not resolve a phone number for the recipient",
                recoverable = false
            )
        val digitsOnly = rawNumber.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length < 8) {
            return ToolResult.Failure(
                toolName = "call_contact",
                error = "Resolved phone number looks invalid: $rawNumber",
                recoverable = false
            )
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.Failure(
                toolName = "call_contact",
                error = "CALL_PHONE permission not granted. Enable it in Settings → Apps → JARVIS → Permissions.",
                recoverable = false
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$digitsOnly"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ToolResult.Success(
                    toolName = "call_contact",
                    data = mapOf("to" to digitsOnly),
                    message = "Calling $digitsOnly now, sir."
                )
            } catch (e: Exception) {
                ToolResult.Failure(
                    toolName = "call_contact",
                    error = "Failed to place call: ${e.message}",
                    recoverable = true
                )
            }
        }
    }
}

/** Result verifier for tool execution outcomes. */
object ResultVerifier {

    /** Verifies if a tool execution was successful based on the result. */
    fun verifyResult(result: ToolResult, expectedOutcome: Map<String, Any?>? = null): Boolean {
        return when (result) {
            is ToolResult.Success -> {
                // Basic success check
                if (result.toolName.isBlank()) return false
                // Additional verification logic could go here
                true
            }
            is ToolResult.Failure -> false
            is ToolResult.ConfirmationRequired -> false
        }
    }

    /** Creates a user-friendly message from a tool result. */
    fun getUserMessage(result: ToolResult): String {
        return when (result) {
            is ToolResult.Success -> result.message
            is ToolResult.Failure -> "I encountered an issue with ${result.toolName}: ${result.error}, sir."
            is ToolResult.ConfirmationRequired -> result.message
        }
    }
}

/** Plan for multi-step task execution. */
data class ExecutionPlan(
    val steps: List<PlanStep>,
    val intent: String,
    val originalRequest: String
)

/** A single step in an execution plan. */
data class PlanStep(
    val toolName: String,
    val parameters: Map<String, Any?>,
    val description: String,
    val dependsOn: Int? = null // Index of step this depends on, null if none
)

/** Task planner for multi-step operations. */
class TaskPlanner {

    /** Creates an execution plan for a multi-step request. */
    fun createPlan(request: String, classification: IntentClassifier.Classification): ExecutionPlan? {
        val normalized = request.trim().lowercase()

        // Check for multi-step patterns
        if (isMultiStepRequest(normalized)) {
            return createMultiStepPlan(normalized, classification)
        }

        // Single step
        val toolName = determineToolForIntent(classification.intent, classification.parameters)
        return if (toolName != null) {
            ExecutionPlan(
                steps = listOf(PlanStep(
                    toolName = toolName,
                    parameters = classification.parameters,
                    description = "Execute $toolName"
                )),
                intent = classification.intent,
                originalRequest = classification.parameters["rawInput"] as? String ?: ""
            )
        } else null
    }

    /** Determines if a request requires multiple steps. */
    private fun isMultiStepRequest(normalized: String): Boolean {
        val multiStepKeywords = setOf("then", "and then", "after that", "afterwards", "then ", " then")
        return multiStepKeywords.any { normalized.contains(it) }
    }

    /** Creates a multi-step plan from a request. */
    private fun createMultiStepPlan(normalized: String, outerClassification: IntentClassifier.Classification): ExecutionPlan {
        val parts = splitMultiStepRequest(normalized)
        val steps = parts.mapIndexed { index, part ->
            val innerClassification = IntentClassifier.classifyIntent(part, emptyMap())
            val toolName = determineToolForIntent(innerClassification.intent, innerClassification.parameters)
            PlanStep(
                toolName = toolName ?: "unknown",
                parameters = innerClassification.parameters,
                description = part.trim(),
                dependsOn = if (index > 0) index - 1 else null
            )
        }.filter { it.toolName != "unknown" }

        return ExecutionPlan(
            steps = steps,
            intent = "MULTI_STEP_TASK",
            originalRequest = outerClassification.parameters["rawInput"] as? String ?: ""
        )
    }

    /** Splits a multi-step request into individual parts. */
    private fun splitMultiStepRequest(normalized: String): List<String> {
        val delimiters = listOf(" then ", " and then ", " after that ", " afterwards ", " then ")
        var current = normalized
        var result = mutableListOf<String>()
        var minPos = normalized.length

        for (delimiter in delimiters) {
            val pos = normalized.indexOf(delimiter)
            if (pos != -1 && pos < minPos) {
                minPos = pos
            }
        }

        // Simple split for now
        val parts = normalized.split(" then ").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size <= 1) {
            return normalized.split(" and then ").map { it.trim() }.filter { it.isNotBlank() }
        }
        return parts
    }

    /** Determines the tool name for a given intent. */
    private fun determineToolForIntent(
        intent: String,
        parameters: Map<String, Any?> = emptyMap()
    ): String? {
        return when (intent) {
            "SYSTEM_COMMAND" -> {
                val command = (parameters["command"] as? String).orEmpty().lowercase()
                // "open settings"/"go to settings" must reach the open_settings
                // tool; routing them to open_app tried to resolve "settings" as
                // an app package and always failed.
                if (command.contains("settings")) "open_settings" else "open_app"
            }
            "TIME_DATE" -> "get_device_status" // or a dedicated time tool
            "CALCULATION" -> "calculate"
            "MEMORY" -> "memory" // placeholder
            "SEND_SMS" -> "send_sms"
            "SEND_WHATSAPP" -> "send_whatsapp"
            "MAKE_CALL" -> "call_contact"
            else -> null
        }
    }
}
