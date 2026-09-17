package com.jarvis.ai.orchestrator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Step 1 of the brain upgrade.
 *
 * [ToolRegistry] already describes every tool with a name, a human
 * description, named parameters, a risk level and a confirmation message.
 * Until now that catalogue was only ever read back by our own Kotlin code:
 * the language model never saw it, so tool selection was done entirely by
 * keyword matching in IntentClassifier / QuickCommandRouter. That is why a
 * phrasing like "nazib ko bata do main late hu" fell through to plain chat
 * even though send_whatsapp exists.
 *
 * This object is the missing translation layer. It turns the registry into
 * the exact JSON shapes the providers expect:
 *
 *   OpenAI / Groq / OpenRouter / DeepSeek / Cerebras / Mistral
 *       -> [openAiTools]        ("tools": [{ "type": "function", ... }])
 *   Gemini
 *       -> [geminiFunctionDeclarations]  ("tools": [{ "functionDeclarations": [...] }])
 *   Anything without native tool calling
 *       -> [promptCatalog]      (plain text catalogue for the system prompt)
 *
 * Deliberately pure: no Android imports, no network, no state. It can be
 * unit tested on the JVM and it cannot break any existing call path,
 * because nothing calls it yet. Step 2 wires it into the adapters.
 */
object ToolSchema {

    /**
     * A parameter is treated as optional when its registry description
     * starts with "optional". That matches how ToolRegistry is already
     * written, e.g. open_app.className = "Optional class name ...".
     */
    private const val OPTIONAL_MARKER = "optional"

    /** Tools the model must never be allowed to pick on its own. */
    private val MODEL_BLOCKED = setOf("memory_wipe_all")

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    /**
     * Every tool the model is allowed to choose from.
     *
     * HIGH risk tools are withheld: a destructive action should be started
     * by the user in words we can see, not inferred by a model. MEDIUM risk
     * tools stay in the list because PermissionGate still forces an
     * explicit confirmation before they run.
     */
    fun exposedTools(): List<ToolDefinition> =
        ToolRegistry.getAllToolNames()
            .mapNotNull { ToolRegistry.getTool(it) }
            .filter { it.riskLevel != RiskLevel.HIGH }
            .filter { it.name !in MODEL_BLOCKED }
            .sortedBy { it.name }

    /** True when [name] is a tool the model was actually offered. */
    fun isExposed(name: String): Boolean =
        exposedTools().any { it.name == name }

    private fun isRequired(description: String): Boolean =
        !description.trim().lowercase().startsWith(OPTIONAL_MARKER)

    // ------------------------------------------------------------------
    // OpenAI-compatible shape
    // ------------------------------------------------------------------

    /**
     * Builds the value for the request field "tools".
     *
     * Shape per entry:
     * {
     *   "type": "function",
     *   "function": {
     *     "name": "send_whatsapp",
     *     "description": "...",
     *     "parameters": {
     *       "type": "object",
     *       "properties": { "contact": { "type": "string", "description": "..." } },
     *       "required": ["contact", "message"]
     *     }
     *   }
     * }
     */
    fun openAiTools(tools: List<ToolDefinition> = exposedTools()): JsonArray =
        buildJsonArray {
            tools.forEach { tool ->
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", describe(tool))
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                tool.parameters.forEach { (param, detail) ->
                                    putJsonObject(param) {
                                        put("type", "string")
                                        put("description", detail)
                                    }
                                }
                            }
                            putJsonArray("required") {
                                tool.parameters
                                    .filter { isRequired(it.value) }
                                    .forEach { add(it.key) }
                            }
                        }
                    }
                }
            }
        }

    // ------------------------------------------------------------------
    // Gemini shape
    // ------------------------------------------------------------------

    /**
     * Builds the value for Gemini's "tools" field, which is an array with a
     * single object holding "functionDeclarations".
     *
     * Gemini rejects an empty "properties" object, so parameterless tools
     * omit the "parameters" field entirely instead of sending an empty one.
     * Its type names are upper case (OBJECT / STRING), unlike OpenAI.
     */
    fun geminiFunctionDeclarations(tools: List<ToolDefinition> = exposedTools()): JsonArray =
        buildJsonArray {
            addJsonObject {
                putJsonArray("functionDeclarations") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("name", tool.name)
                            put("description", describe(tool))
                            if (tool.parameters.isNotEmpty()) {
                                putJsonObject("parameters") {
                                    put("type", "OBJECT")
                                    putJsonObject("properties") {
                                        tool.parameters.forEach { (param, detail) ->
                                            putJsonObject(param) {
                                                put("type", "STRING")
                                                put("description", detail)
                                            }
                                        }
                                    }
                                    putJsonArray("required") {
                                        tool.parameters
                                            .filter { isRequired(it.value) }
                                            .forEach { add(it.key) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    // ------------------------------------------------------------------
    // Text fallback
    // ------------------------------------------------------------------

    /**
     * Plain-text catalogue for providers that cannot do native tool calling.
     * The model is asked to answer with a single JSON object, which Step 2
     * parses with the same code path as a native tool call.
     */
    fun promptCatalog(tools: List<ToolDefinition> = exposedTools()): String {
        if (tools.isEmpty()) return ""
        val lines = StringBuilder()
        lines.append("You can run actions on the user's Android phone.\n")
        lines.append("When an action is needed, reply with ONLY this JSON and nothing else:\n")
        lines.append("{\"tool\":\"<name>\",\"args\":{\"<param>\":\"<value>\"}}\n")
        lines.append("If no action is needed, reply normally in plain language.\n")
        lines.append("Available actions:\n")
        tools.forEach { tool ->
            lines.append("- ").append(tool.name).append(": ").append(tool.description)
            if (tool.parameters.isNotEmpty()) {
                lines.append(" | params: ")
                lines.append(
                    tool.parameters.entries.joinToString(", ") { (param, detail) ->
                        if (isRequired(detail)) param else "$param (optional)"
                    }
                )
            }
            lines.append("\n")
        }
        return lines.toString().trimEnd()
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * The description handed to the model. The category is appended so that
     * near neighbours such as send_sms and send_whatsapp stay separable, and
     * gated tools say so, which measurably reduces wrong picks.
     */
    private fun describe(tool: ToolDefinition): String {
        val base = StringBuilder(tool.description)
        if (tool.category.isNotBlank()) {
            base.append(" (category: ").append(tool.category).append(")")
        }
        if (tool.confirmationRequired) {
            base.append(" Requires the user to confirm before it runs.")
        }
        return base.toString()
    }

    /** Debug aid: the schema as a pretty string, used by tests and logs. */
    fun describeAll(): String =
        exposedTools().joinToString("\n") { tool ->
            val params =
                if (tool.parameters.isEmpty()) "none"
                else tool.parameters.keys.joinToString(",")
            "${tool.name} [${tool.riskLevel}] params=$params"
        }

    /** Number of tools currently offered to the model. */
    fun exposedCount(): Int = exposedTools().size

    /** Empty object reused by callers that need a no-args payload. */
    val EMPTY_ARGS: JsonObject = buildJsonObject { }
}
