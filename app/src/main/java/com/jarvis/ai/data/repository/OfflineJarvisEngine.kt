package com.jarvis.ai.data.repository

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.random.Random

object OfflineJarvisEngine {

    private const val DEMO_NOTE =
        " Running on local reserves right now, sir. Attach an API key via Settings for my full intelligence."

    private val greetings = listOf(
        "Good day, sir. All systems are operational.",
        "Hello, sir. At your service, as always.",
        "Welcome back, sir. Standing by for your orders."
    )
    private val wellbeing = listOf(
        "Running at peak efficiency, sir. All diagnostics are green.",
        "Fully charged and fully attentive, thank you for asking, sir."
    )
    private val jokes = listOf(
        "Why do programmers prefer dark mode? Because light attracts bugs, sir.",
        "I would tell you a UDP joke, sir, but you might not get it.",
        "There are only 10 kinds of people, sir: those who understand binary and those who do not."
    )
    private val thanksReplies = listOf(
        "Always a pleasure, sir.",
        "Think nothing of it, sir."
    )
    private val farewells = listOf(
        "Goodbye, sir. Powering down non-essential systems.",
        "Good night, sir. I shall keep watch."
    )
    private val defaults = listOf(
        "Acknowledged, sir. My local core handles greetings, time, dates, arithmetic and light conversation. Deeper reasoning requires an API key in Settings.$DEMO_NOTE",
        "Interesting request, sir. Without the remote uplink I can offer time, date, calculations, jokes and diagnostics. Configure an API key for full capability.",
        "My apologies, sir. That query exceeds local reserve capacity. An uplink key would restore my full faculties."
    )

    fun respond(rawInput: String): String {
        val input = rawInput.trim().lowercase(Locale.getDefault())
        if (input.isEmpty()) return "I did not quite catch that, sir."

        evaluateMath(input)?.let { return "Computed, sir: $it." }

        val tokens = input.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        return when {
            tokens.any { it in GREETING_WORDS } -> pick(greetings)
            input.contains("how are you") || input.contains("how do you do") -> pick(wellbeing)
            hasAny(input, listOf("who are you", "your name", "what are you")) -> identity()
            input.contains("time") && !input.contains("timer") -> "The time is ${time()}, sir."
            hasAny(input, listOf("date", "what day", "today")) -> "Today is ${date()}, sir."
            input.contains("joke") -> pick(jokes)
            input.contains("weather") -> "My meteorological sensors require the remote uplink, sir. Attach an API key and I shall fetch the forecast."
            input.contains("thank") -> pick(thanksReplies)
            hasAny(input, listOf("bye", "goodbye", "good night")) -> pick(farewells)
                        hasAny(input, listOf("diagnostics", "status report", "system status")) -> diagnostics()
            hasAny(input, listOf("help", "what can you do", "commands", "features")) -> help()
            else -> pick(defaults)
        }
    }

    private val GREETING_WORDS = setOf("hello", "hi", "hey", "yo", "namaste")

    private fun identity(): String =
        "I am AURIX, Just A Rather Very Intelligent System. Presently operating on local reserves:$DEMO_NOTE"

    private fun diagnostics(): String =
        "Diagnostics complete, sir. Core: nominal. Memory: nominal. Uplink: standby pending API key. Morale: impeccably British."

    private fun help(): String =
        "At your service, sir. Local mode supports: greetings, current time and date, arithmetic (try 'calculate 12 * (4 + 8)'), jokes, diagnostics, and this help. For open-ended intelligence, open Settings and provide an API key from any OpenAI-compatible provider."

    private fun time(): String =
        SimpleDateFormat("h:mm a", Locale.US).format(Date())

    private fun date(): String =
        SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())

    private fun hasAny(text: String, keys: List<String>): Boolean = keys.any { text.contains(it) }

    private fun pick(list: List<String>): String = list[Random.nextInt(list.size)]

    private fun evaluateMath(input: String): String? {
        val candidate = extractMathCandidate(input) ?: return null
        val normalized = candidate.replace('×', '*').replace('÷', '/')
        val value = runCatching { Parser(normalized).evaluate() }.getOrNull() ?: return null
        if (!value.isFinite()) return null
        return format(value)
    }

    private fun extractMathCandidate(input: String): String? {
        val cleaned = input.replace(",", "").filter { ch ->
            ch.isDigit() || ch in "+-*/^%(). " || ch == '×' || ch == '÷'
        }
        if (!cleaned.any { it.isDigit() }) return null
        if (!cleaned.any { it in "+-*/^%×÷" }) return null
        return cleaned.trim().takeIf { it.isNotEmpty() }
    }

    private fun format(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')

    private class Parser(private val src: String) {
        private var pos = 0

        fun evaluate(): Double {
            val value = parseExpression()
            skipSpaces()
            if (pos != src.length) throw IllegalArgumentException("Trailing input")
            return value
        }

        private fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipSpaces()
                when (charAt()) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parsePower()
            while (true) {
                skipSpaces()
                when (charAt()) {
                    '*' -> { pos++; value *= parsePower() }
                    '/' -> { pos++; value /= parsePower() }
                    '%' -> { pos++; value %= parsePower() }
                    else -> return value
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            skipSpaces()
            return if (charAt() == '^') {
                pos++
                base.pow(parseUnary())
            } else base
        }

        private fun parseUnary(): Double {
            skipSpaces()
            when (charAt()) {
                '-' -> { pos++; return -parseUnary() }
                '+' -> { pos++; return parseUnary() }
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            if (charAt() == '(') {
                pos++
                val value = parseExpression()
                skipSpaces()
                if (charAt() != ')') throw IllegalArgumentException("Missing bracket")
                pos++
                return value
            }
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("Number expected")
            return src.substring(start, pos).toDouble()
        }

        private fun charAt(): Char = if (pos < src.length) src[pos] else ' '

        private fun skipSpaces() {
            while (pos < src.length && src[pos] == ' ') pos++
        }
    }
}
