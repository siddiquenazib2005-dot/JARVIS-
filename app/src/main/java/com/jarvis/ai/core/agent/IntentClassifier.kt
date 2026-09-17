package com.jarvis.ai.core.agent

data class Classification(
    val intent: String,
    val confidence: Float,
    val parameters: Map<String, Any?>,
    val requiresAi: Boolean,
    val needsConfirmation: Boolean,
    val complexity: QueryComplexity = QueryComplexity.SIMPLE
)

enum class QueryComplexity {
    SIMPLE, MULTI_STEP, COMPLEX
}

object IntentClassifier {

    private val MATH_OPERATORS = charArrayOf('+', '-', '*', '/', '%', '^', '×', '÷')

    private val TIME_KEYWORDS = listOf("what time", "the time", "current time", "time is it")
    private val DATE_KEYWORDS = listOf("what date", "today's date", "which day", "what day")

    private val SYSTEM_KEYWORDS =
        listOf("open ", "launch ", "go to settings", "open settings")

    private val AUTOMATION_KEYWORDS =
        listOf("tap ", "click ", "type ", "swipe ", "scroll ", "press ", "screenshot", "read screen",
               "open app", "launch app", "automate")

    private val VISION_KEYWORDS =
        listOf("what is on my screen", "what's on my screen", "what is on screen", "whats on screen",
               "describe screen", "describe my screen", "analyze screen", "analyze my screen",
               "ocr screen", "ocr my screen", "read screen with ocr", "vision screen")

    private val MEMORY_KEYWORDS =
        listOf("remember ", "forget ", "recall ", "what did i tell you")

    private val QUESTION_WORDS = listOf("what", "how", "why", "who", "where", "when")

    private val MULTI_STEP_KEYWORDS = listOf("then", "and then", "after that", "afterwards", "then ", " then")

    fun classifyIntent(request: String, context: Map<String, Any?> = emptyMap()): Classification {
        val normalized = request.trim().lowercase()

        if (isCalculationRequest(normalized)) {
            return Classification(
                intent = "CALCULATION",
                confidence = 0.95f,
                parameters = mapOf("expression" to normalized),
                requiresAi = false,
                needsConfirmation = false,
                complexity = QueryComplexity.SIMPLE
            )
        }

        if (isTimeDateRequest(normalized)) {
            return Classification(
                intent = "TIME_DATE",
                confidence = 0.9f,
                parameters = mapOf("type" to extractTimeDateType(normalized)),
                requiresAi = false,
                needsConfirmation = false,
                complexity = QueryComplexity.SIMPLE
            )
        }

        // Check automation FIRST - more specific than generic system commands
        if (isAutomationRequest(normalized)) {
            return Classification(
                intent = "DEVICE_AUTOMATION",
                confidence = 0.85f,
                parameters = mapOf("command" to extractAutomationCommand(normalized)),
                requiresAi = true,
                needsConfirmation = true,
                complexity = if (isMultiStepRequest(normalized)) QueryComplexity.MULTI_STEP else QueryComplexity.SIMPLE
            )
        }

        if (isVisionRequest(normalized)) {
            return Classification(
                intent = "VISION_ANALYSIS",
                confidence = 0.9f,
                parameters = mapOf("command" to normalized),
                requiresAi = true,
                needsConfirmation = false,
                complexity = QueryComplexity.SIMPLE
            )
        }

        if (isSystemCommand(normalized)) {
            return Classification(
                intent = "SYSTEM_COMMAND",
                confidence = 0.85f,
                parameters = mapOf("command" to extractCommand(normalized)),
                requiresAi = false,
                needsConfirmation = true,
                complexity = if (isMultiStepRequest(normalized)) QueryComplexity.MULTI_STEP else QueryComplexity.SIMPLE
            )
        }

        if (isMemoryOperation(normalized)) {
            return Classification(
                intent = "MEMORY",
                confidence = 0.8f,
                parameters = mapOf("operation" to extractMemoryOperation(normalized)),
                requiresAi = false,
                needsConfirmation = false,
                complexity = QueryComplexity.SIMPLE
            )
        }

        if (isChatRequest(normalized)) {
            return Classification(
                intent = "CHAT",
                confidence = 0.7f,
                parameters = mapOf("rawInput" to request),
                requiresAi = true,
                needsConfirmation = false,
                complexity = if (isMultiStepRequest(normalized)) QueryComplexity.MULTI_STEP else QueryComplexity.SIMPLE
            )
        }

        return Classification(
            intent = "UNKNOWN",
            confidence = 0.4f,
            parameters = mapOf("rawInput" to request),
            requiresAi = true,
            needsConfirmation = false,
            complexity = QueryComplexity.COMPLEX
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

    private fun isVisionRequest(text: String): Boolean = containsAny(text, VISION_KEYWORDS)

    private fun isMultiStepRequest(text: String): Boolean = containsAny(text, MULTI_STEP_KEYWORDS)

    private fun extractCommand(text: String): String = text
        .replace("launch ", "")
        .replace("open ", "")
        .replace("go to ", "")
        .trim()

    private fun extractAutomationCommand(text: String): String = text

    private fun isMemoryOperation(text: String): Boolean = containsAny(text, MEMORY_KEYWORDS)

    private fun extractMemoryOperation(text: String): String = when {
        text.startsWith("remember ") -> "store"
        text.startsWith("forget ") -> "remove"
        text.startsWith("what did i tell you") -> "recall"
        else -> "query"
    }

    private fun isChatRequest(text: String): Boolean {
        val isQuestion = QUESTION_WORDS.any { text.startsWith("$it ") }
        return isQuestion && !isCalculationRequest(text) && !isSystemCommand(text) && !isAutomationRequest(text)
    }

    private fun containsAny(text: String, needles: List<String>): Boolean =
        needles.any { text.contains(it) }
}