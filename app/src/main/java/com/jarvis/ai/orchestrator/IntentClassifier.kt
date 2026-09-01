package com.jarvis.ai.orchestrator

/** Classifies user intents into categories for appropriate handling. */
object IntentClassifier {

    /** Result of classification with confidence and extracted parameters. */
    data class Classification(
        val intent: String,
        val confidence: Float,
        val parameters: Map<String, Any?>,
        val requiresAi: Boolean,
        val needsConfirmation: Boolean
    )

    /** Possible intents the orchestrator can handle. */
    sealed class IntentCategory {
        object Chat : IntentCategory()
        object Calculation : IntentCategory()
        object SystemCommand : IntentCategory()
        object Weather : IntentCategory()
        object TimeDate : IntentCategory()
        object Memory : IntentCategory()
        object Unknown : IntentCategory()
    }

    private val MATH_OPERATORS = charArrayOf('+', '-', '*', '/', '%', '^', '×', '÷')

    private val TIME_KEYWORDS = listOf("what time", "the time", "current time", "time is it")
    private val DATE_KEYWORDS = listOf("what date", "today's date", "which day", "what day")

    private val SYSTEM_KEYWORDS =
        listOf("open ", "launch ", "go to settings", "open settings")

    private val AUTOMATION_KEYWORDS =
        listOf("tap ", "click ", "type ", "swipe ", "scroll ", "press ", "screenshot", "read screen",
               "open app", "launch app", "automate", "do ", "perform ", "execute ", "run ",
               "swipe ", "scroll ", "long press", "long press ", "wait for", "wait for ",
               "press back", "press home", "press recents", "go back", "go home",
               "lock screen", "lock phone", "close ", "close app")

    private val VISION_KEYWORDS =
        listOf("what is on my screen", "what's on my screen", "what is on screen", "whats on screen",
               "describe screen", "describe my screen", "analyze screen", "analyze my screen",
               "ocr screen", "ocr my screen", "read screen with ocr", "vision screen")

    private val MEMORY_KEYWORDS =
        listOf("remember ", "forget ", "recall ", "what did i tell you")

    private val QUESTION_WORDS = listOf("what", "how", "why", "who", "where", "when")

    // ------------------------------------------------------------------
    // Messaging (SMS / WhatsApp) — supports both English and common
    // Hinglish phrasings, since Levinho's voice input is Hindi-English mixed.
    // ------------------------------------------------------------------

    private val MESSAGING_TRIGGER_WORDS = listOf("sms", "whatsapp", "text ", "message ")

    /** Regexes tried in order; first match wins. Groups vary per pattern, handled in parseMessagingRequest. */
    private val MSG_PATTERN_TO = Regex("""^(?:send\s+)?(sms|whatsapp)\s+to\s+(\S+)\s+(.+)$""")
    private val MSG_PATTERN_DIRECT = Regex("""^(sms|whatsapp)\s+(\S+)\s+(.+)$""")
    private val MSG_PATTERN_TEXT = Regex("""^text\s+(\S+)\s+(.+)$""")
    private val MSG_PATTERN_MESSAGE = Regex("""^message\s+(\S+)\s+(?:saying\s+)?(.+)$""")
    /** Hinglish: "NAZIB ko whatsapp karo hii" / "ritik ko sms bhejo call me" */
    private val MSG_PATTERN_KO = Regex("""^(\S+)\s+ko\s+(sms|whatsapp)\s+(?:karo\s+|bhejo\s+|bolo\s+)?(.+)$""")

    // ------------------------------------------------------------------
    // Phone calls
    // ------------------------------------------------------------------

    private val CALL_TRIGGER_WORDS = listOf("call ", "phone ", "ring ", "dial ")
    private val CALL_PATTERN_DIRECT = Regex("""^(?:call|phone|ring|dial)\s+(.+)$""")
    /** Hinglish: "NAZIB ko call karo" / "ritik ko phone lagao" */
    private val CALL_PATTERN_KO = Regex("""^(.+?)\s+ko\s+(?:call|phone)\s+(?:karo|lagao|milao)?$""")

    /** Classifies a user request intent. */
    fun classifyIntent(request: String, context: Map<String, Any?> = emptyMap()): Classification {
        val normalized = request.trim().lowercase()

        if (isCalculationRequest(normalized)) {
            return Classification(
                intent = "CALCULATION",
                confidence = 0.95f,
                parameters = mapOf("expression" to normalized),
                requiresAi = false,
                needsConfirmation = false
            )
        }

        if (isTimeDateRequest(normalized)) {
            return Classification(
                intent = "TIME_DATE",
                confidence = 0.9f,
                parameters = mapOf("type" to extractTimeDateType(normalized)),
                requiresAi = false,
                needsConfirmation = false
            )
        }

        // Messaging: checked early (high specificity) and before automation/system
        // so "sms"/"whatsapp"/"text " keywords never get swallowed by generic buckets.
        if (isMessagingRequest(normalized)) {
            val parsed = parseMessagingRequest(normalized)
            if (parsed != null) {
                val (type, contact, message) = parsed
                return Classification(
                    intent = if (type == "whatsapp") "SEND_WHATSAPP" else "SEND_SMS",
                    confidence = 0.9f,
                    parameters = mapOf("contact" to contact, "message" to message),
                    requiresAi = false,
                    needsConfirmation = true
                )
            }
            // Keyword matched but couldn't parse contact/message — let AI chat handle
            // it as a clarifying conversation rather than silently failing here.
        }

        if (isCallRequest(normalized)) {
            val contact = parseCallRequest(normalized)
            if (contact != null) {
                return Classification(
                    intent = "MAKE_CALL",
                    confidence = 0.9f,
                    parameters = mapOf("contact" to contact),
                    requiresAi = false,
                    needsConfirmation = true
                )
            }
        }

        // Check automation FIRST - more specific than generic system commands
        if (isAutomationRequest(normalized)) {
            return Classification(
                intent = "DEVICE_AUTOMATION",
                confidence = 0.85f,
                parameters = mapOf("command" to extractAutomationCommand(normalized)),
                requiresAi = true,
                needsConfirmation = true
            )
        }

        if (isVisionRequest(normalized)) {
            return Classification(
                intent = "VISION_ANALYSIS",
                confidence = 0.9f,
                parameters = mapOf("command" to normalized),
                requiresAi = true,
                needsConfirmation = false
            )
        }

        if (isSystemCommand(normalized)) {
            return Classification(
                intent = "SYSTEM_COMMAND",
                confidence = 0.85f,
                parameters = mapOf("command" to extractCommand(normalized)),
                requiresAi = false,
                needsConfirmation = true
            )
        }

        if (isMemoryOperation(normalized)) {
            return Classification(
                intent = "MEMORY",
                confidence = 0.8f,
                parameters = mapOf("operation" to extractMemoryOperation(normalized)),
                requiresAi = false,
                needsConfirmation = false
            )
        }

        if (isChatRequest(normalized)) {
            return Classification(
                intent = "CHAT",
                confidence = 0.7f,
                parameters = mapOf("rawInput" to request),
                requiresAi = true,
                needsConfirmation = false
            )
        }

        return Classification(
            intent = "UNKNOWN",
            confidence = 0.4f,
            parameters = mapOf("rawInput" to request),
            requiresAi = true,
            needsConfirmation = false
        )
    }

    private fun isCalculationRequest(text: String): Boolean {
        val hasDigit = text.any { it.isDigit() }
        val hasOperator = text.any { it in MATH_OPERATORS }
        return hasDigit && hasOperator && !containsAny(text, TIME_KEYWORDS) &&
            !containsAny(text, DATE_KEYWORDS)
    }

    private fun isTimeDateRequest(text: String): Boolean {
        return containsAny(text, TIME_KEYWORDS) || containsAny(text, DATE_KEYWORDS)
    }

    private fun extractTimeDateType(text: String): String = when {
        text.contains("date") -> "date"
        else -> "time"
    }

    private fun isSystemCommand(text: String): Boolean = containsAny(text, SYSTEM_KEYWORDS)

    private fun isAutomationRequest(text: String): Boolean = containsAny(text, AUTOMATION_KEYWORDS)

    private fun extractCommand(text: String): String = text
        .replace("launch ", "")
        .replace("open ", "")
        .replace("go to ", "")
        .trim()

    private fun extractAutomationCommand(text: String): String = text

    private fun isVisionRequest(text: String): Boolean = containsAny(text, VISION_KEYWORDS)

    private fun isMemoryOperation(text: String): Boolean = containsAny(text, MEMORY_KEYWORDS)

    private fun extractMemoryOperation(text: String): String = when {
        text.startsWith("remember ") -> "store"
        text.startsWith("forget ") -> "remove"
        text.startsWith("what did i tell you") -> "recall"
        else -> "query"
    }

    private fun isChatRequest(text: String): Boolean {
        val isQuestion = QUESTION_WORDS.any { text.startsWith("$it ") }
        return isQuestion && !isCalculationRequest(text) && !isSystemCommand(text)
    }

    private fun isMessagingRequest(text: String): Boolean =
        containsAny(text, MESSAGING_TRIGGER_WORDS) && (text.contains(" to ") || text.contains(" ko "))

    private fun isCallRequest(text: String): Boolean = containsAny(text, CALL_TRIGGER_WORDS)

    /** Extracts the contact name/number from a call command; null if the phrasing doesn't match. */
    private fun parseCallRequest(text: String): String? {
        CALL_PATTERN_DIRECT.find(text)?.let { m ->
            return m.groupValues[1].trim().removePrefix("to ").trim()
        }
        CALL_PATTERN_KO.find(text)?.let { m ->
            return m.groupValues[1].trim()
        }
        return null
    }

    /**
     * Extracts (type, contact, message) from a normalized messaging command.
     * Tries each known phrasing in order; returns null if none match (falls
     * back to CHAT so the AI can ask a clarifying question instead of failing
     * silently).
     */
    private fun parseMessagingRequest(text: String): Triple<String, String, String>? {
        MSG_PATTERN_TO.find(text)?.let { m ->
            val (type, contact, message) = m.destructured
            return Triple(type, contact, message.trim())
        }
        MSG_PATTERN_DIRECT.find(text)?.let { m ->
            val (type, contact, message) = m.destructured
            return Triple(type, contact, message.trim())
        }
        MSG_PATTERN_KO.find(text)?.let { m ->
            val (contact, type, message) = m.destructured
            return Triple(type, contact, message.trim())
        }
        MSG_PATTERN_TEXT.find(text)?.let { m ->
            val (contact, message) = m.destructured
            return Triple("sms", contact, message.trim())
        }
        MSG_PATTERN_MESSAGE.find(text)?.let { m ->
            val (contact, message) = m.destructured
            return Triple("sms", contact, message.trim())
        }
        return null
    }

    private fun containsAny(text: String, needles: List<String>): Boolean =
        needles.any { text.contains(it) }
}
