package com.jarvis.ai.service

/**
 * Pure-logic wake-word detector for the free-form SpeechRecognizer pipeline.
 *
 * Design notes:
 * - Operates on the transcripts (partial + final) the recognizer already
 *   produces, so it needs no second mic pipeline and no permissions beyond
 *   the RECORD_AUDIO the app already holds. Battery cost is unchanged from
 *   the existing hands-free loop — the gate simply decides which transcripts
 *   are acted upon.
 * - "jarvis" is matched with word-boundary anchors so words that merely
 *   CONTAIN the letters never trigger.
 * - Vendor recognizers transcribe the name as "jarvis", "javis", "jervis"
 *   and other phonetic near-misses; the tolerance list covers the common
 *   variants without becoming so broad that ordinary chatter wakes the
 *   assistant.
 */
object WakeWordDetector {

    /** Primary phrase plus transcription variants seen in the wild. */
    private val WAKE_WORDS = listOf(
        "jarvis", "javis", "jarves", "jervis", "jarvic", "jarwis", "jarviz", "jervaz"
    )

    /** Word-boundary anchored so substrings inside other words never match. */
    private val WAKE_PATTERN = Regex(
        "\\b(?:${WAKE_WORDS.joinToString("|")})\\b",
        RegexOption.IGNORE_CASE
    )

    /** True when [transcript] contains the wake phrase as a standalone word. */
    fun containsWakeWord(transcript: String): Boolean =
        transcript.isNotBlank() && WAKE_PATTERN.containsMatchIn(transcript)

    /**
     * Removes the wake word from [transcript] and returns the command part.
     * Returns null when nothing usable remains (the user said only the wake
     * word). When no wake word is present the transcript is returned intact
     * so callers can decide how to treat non-gated sessions.
     */
    fun stripWakeWord(transcript: String): String? {
        if (!containsWakeWord(transcript)) return transcript
        return WAKE_PATTERN.replace(transcript, " ")
            .trim()
            // A wake word spoken with trailing punctuation ("jarvis, status")
            // leaves the separator behind; strip leading punctuation AND the
            // whitespace after it, then settle the ends.
            .trimStart(',', '.', '!', '?', ':', ';', '-', '—')
            .trim()
            .ifEmpty { null }
    }
}
