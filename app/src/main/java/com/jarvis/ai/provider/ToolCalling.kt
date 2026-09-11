package com.jarvis.ai.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Step 2a of the brain upgrade: the wire format for model-chosen actions.
 *
 * Step 1 (ToolSchema) taught the providers how to DESCRIBE our tools to a
 * model. This file is the other direction: how to READ a model's decision
 * back off the wire, in whatever shape the provider happens to use.
 *
 * Three shapes are supported, because our seven providers do not agree:
 *
 *   OpenAI family  choices[0].message.tool_calls[].function.{name,arguments}
 *                  (arguments is a JSON string, not an object)
 *   Gemini         candidates[0].content.parts[].functionCall.{name,args}
 *                  (args is a real object)
 *   Text fallback  a bare {"tool":"x","args":{...}} object printed by a model
 *                  that has no native tool calling, possibly fenced in
 *                  markdown or wrapped in chatter
 *
 * Everything here is pure parsing over JsonElement. No serializer classes,
 * no network, no Android. Malformed input never throws: it returns null and
 * the caller falls back to treating the reply as ordinary text. That matters
 * because a swallowed crash here would look exactly like the silent-failure
 * bug we already fixed once in the command router.
 */

/** A single action the model asked us to run. */
data class ToolInvocation(
    val name: String,
    val args: Map<String, String>,
    /** Provider-side id; OpenAI needs it echoed back on the result turn. */
    val callId: String? = null
) {
    /** Argument lookup that tolerates case and underscore drift from models. */
    fun arg(key: String): String? {
        args[key]?.let { return it }
        val wanted = key.lowercase().replace("_", "")
        return args.entries.firstOrNull { (k, _) ->
            k.lowercase().replace("_", "") == wanted
        }?.value
    }

    override fun toString(): String = "$name(${args.keys.joinToString(",")})"
}

/**
 * One turn from a tool-aware model: prose, an action, or both.
 * Both null/blank means the provider returned nothing usable.
 */
data class ToolAwareReply(
    val text: String? = null,
    val invocation: ToolInvocation? = null
) {
    val hasInvocation: Boolean get() = invocation != null
    val hasText: Boolean get() = !text.isNullOrBlank()
    val isEmpty: Boolean get() = !hasInvocation && !hasText
}

/** Reads model decisions out of raw provider response bodies. */
object ToolCallParser {

    /** Longest text fallback we will scan for a bare JSON action. */
    private const val MAX_SCAN = 4000

    // ------------------------------------------------------------------
    // OpenAI-compatible
    // ------------------------------------------------------------------

    /**
     * Parses an OpenAI-style non-streaming completion body.
     * Returns null only when the body is not JSON at all.
     */
    fun fromOpenAi(body: String): ToolAwareReply? {
        val root = parse(body)?.asObject() ?: return null
        val message = root["choices"]?.asArray()?.firstOrNull()
            ?.asObject()?.get("message")?.asObject()
            ?: return null

        val text = message["content"]?.asStringOrNull()

        val call = message["tool_calls"]?.asArray()?.firstOrNull()?.asObject()
        if (call != null) {
            val function = call["function"]?.asObject()
            val name = function?.get("name")?.asStringOrNull()
            if (!name.isNullOrBlank()) {
                // "arguments" is a JSON-encoded STRING here, not an object.
                val rawArgs = function["arguments"]?.asStringOrNull().orEmpty()
                return ToolAwareReply(
                    text = text,
                    invocation = ToolInvocation(
                        name = name,
                        args = argsFromJsonString(rawArgs),
                        callId = call["id"]?.asStringOrNull()
                    )
                )
            }
        }

        // Legacy single function_call field, still used by some clones.
        val legacy = message["function_call"]?.asObject()
        val legacyName = legacy?.get("name")?.asStringOrNull()
        if (!legacyName.isNullOrBlank()) {
            return ToolAwareReply(
                text = text,
                invocation = ToolInvocation(
                    name = legacyName,
                    args = argsFromJsonString(legacy["arguments"]?.asStringOrNull().orEmpty())
                )
            )
        }

        return ToolAwareReply(text = text, invocation = null)
    }

    // ------------------------------------------------------------------
    // Gemini
    // ------------------------------------------------------------------

    /** Parses a Gemini generateContent body. */
    fun fromGemini(body: String): ToolAwareReply? {
        val root = parse(body)?.asObject() ?: return null
        val parts = root["candidates"]?.asArray()?.firstOrNull()
            ?.asObject()?.get("content")?.asObject()
            ?.get("parts")?.asArray()
            ?: return null

        val text = parts
            .mapNotNull { it.asObject()?.get("text")?.asStringOrNull() }
            .joinToString("")
            .takeIf { it.isNotBlank() }

        val functionCall = parts
            .firstNotNullOfOrNull { it.asObject()?.get("functionCall")?.asObject() }

        val name = functionCall?.get("name")?.asStringOrNull()
        if (name.isNullOrBlank()) return ToolAwareReply(text = text, invocation = null)

        return ToolAwareReply(
            text = text,
            invocation = ToolInvocation(
                name = name,
                // Gemini gives a real object, no second decode needed.
                args = flatten(functionCall["args"]?.asObject())
            )
        )
    }

    // ------------------------------------------------------------------
    // Text fallback
    // ------------------------------------------------------------------

    /**
     * Last resort for providers with no native tool calling: pull a bare
     * {"tool":"...","args":{...}} object out of plain prose. Handles markdown
     * fences and leading chatter. Returns null when the text is just prose,
     * which is the common case and must stay cheap.
     */
    fun fromLooseText(reply: String): ToolInvocation? {
        if (reply.length > MAX_SCAN) return null
        if (!reply.contains("\"tool\"") && !reply.contains("'tool'")) return null

        val cleaned = reply
            .replace("```json", " ")
            .replace("```", " ")
            .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        val candidate = cleaned.substring(start, end + 1)
        val obj = parse(candidate)?.asObject() ?: return null
        val name = (obj["tool"] ?: obj["name"] ?: obj["action"])?.asStringOrNull()
        if (name.isNullOrBlank()) return null

        val args = (obj["args"] ?: obj["arguments"] ?: obj["parameters"])?.asObject()
        return ToolInvocation(name = name, args = flatten(args))
    }

    /**
     * Strips a trailing/leading tool JSON block from prose so the user never
     * sees raw machine output when the fallback path fires.
     */
    fun stripToolJson(reply: String): String {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return reply.trim()
        return (reply.substring(0, start) + reply.substring(end + 1)).trim()
    }

    // ------------------------------------------------------------------
    // Low-level helpers
    // ------------------------------------------------------------------

    private fun parse(raw: String): JsonElement? =
        runCatching { Jsons.lenient.parseToJsonElement(raw) }.getOrNull()

    private fun JsonElement.asObject(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun JsonElement.asArray(): JsonArray? =
        runCatching { jsonArray }.getOrNull()

    /** Content of a primitive, or null for JsonNull and non-primitives. */
    private fun JsonElement.asStringOrNull(): String? {
        val primitive = runCatching { jsonPrimitive }.getOrNull() ?: return null
        if (primitive is JsonPrimitive && primitive.content == "null") return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    /** Decodes the OpenAI "arguments" string into a flat map. */
    private fun argsFromJsonString(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return flatten(parse(raw)?.asObject())
    }

    /**
     * Flattens a JSON object to string values. Nested objects and arrays are
     * kept as their JSON text so a tool can still read them if it wants;
     * every registry tool currently takes strings only.
     */
    private fun flatten(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        obj.forEach { (key, value) ->
            val text = value.asStringOrNull() ?: value.toString()
            if (text.isNotBlank() && text != "null") out[key] = text
        }
        return out
    }
}
