package com.jarvis.ai.service

/**
 * Incremental sentence parser for streaming LLM output.
 *
 * Accepts arbitrary text chunks as they arrive from the LLM stream.
 * After each chunk, client code may call [nextSentence()] to retrieve
 * any newly-complete sentences (FIFO order). Remaining text stays buffered
 * for the next chunk. When the stream ends, call [flush()] to retrieve
 * whatever incomplete text remains.
 *
 * Sentence boundary rules:
 *   - . ! ? followed by whitespace (incl. NBSP) or end-of-text are treated
 *     as sentence terminators.
 *   - A . preceded by a digit is treated as a decimal point, NOT a
 *     sentence boundary (e.g. "3.14", "0.5").
 *   - A . ! ? that forms part of a known English abbreviation is ignored
 *     (e.g. "Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.", "St.",
 *      "ft.", "inc.", "ltd.", "corp.", "gov.", "dept.", "govt.",
 *      "approx.", "est.").
 *   - Hindi/Hinglish text: the same . ! ? punctuation serves as sentence
 *     delimiters; no special Devanagari handling is required beyond
 *     treating those codepoints as sentence terminators.
 */
class SentenceParser {

    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st",
        "ft", "inc", "ltd", "corp", "gov", "dept", "govt",
        "approx", "est"
    )

    private var buffer = ""

    /** Append a new text chunk from the LLM stream. */
    fun addChunk(chunk: String) {
        buffer += chunk
    }

    /**
     * Returns the next complete sentence, or null if none is available yet.
     * Consumes the sentence from the internal buffer.  Thread-safe for
     * single-consumer (viewModelScope) use.
     */
    fun nextSentence(): String? {
        val idx = findFirstBoundary(buffer)
        if (idx < 0) return null

        val sentence = buffer.substring(0, idx + 1).trim()
        if (sentence.isNotBlank()) {
            buffer = buffer.substring(idx + 1)
            return sentence
        }
        // Boundary landed on whitespace/etc; discard it and try again
        buffer = buffer.substring(idx + 1)
        return nextSentence()
    }

    /** Return the index of the first sentence boundary, or -1 if none. */
    private fun findFirstBoundary(text: String): Int {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '.' || ch == '!' || ch == '?') {
                // Decimal check: . preceded by a digit → not a boundary
                if (i > 0 && text[i - 1].isDigit()) {
                    i++
                    continue
                }

                // Abbreviation check: word before . ! ? is in the abbreviation set
                val before = wordBefore(text, i)
                val isAbbr = before.length in 2..11 && abbreviations.contains(before.toLowerCase())
                if (isAbbr) {
                    i++
                    continue
                }

                // Sentence boundary: end-of-text, OR whitespace (incl. NBSP U+00A0,
                // which Char.isWhitespace() does not report). We intentionally do NOT
                // require a following capital letter: LLM streaming output frequently
                // continues in lowercase after a period ("is ready. ask me anything."),
                // and requiring a capital would delay incremental TTS/streaming until
                // the very end of the stream.
                val after = if (i + 1 < text.length) text[i + 1] else Char.MIN_VALUE
                if (after == Char.MIN_VALUE || after.isWhitespace() || after == '\u00a0') {
                    return i
                }
            }
            i++
        }
        return -1
    }

    /** Extract the word immediately before the punctuation position. */
    private fun wordBefore(text: String, punctIdx: Int): String {
        var i = punctIdx - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0) return ""
        var j = i
        while (j >= 0 && !text[j].isWhitespace() && text[j] != '.' && text[j] != '!' && text[j] != '?') j--
        return text.substring(j + 1, i + 1)
    }

    /** Flush any remaining text in the buffer (called when the LLM stream ends).
     *  Returns the leftover text; the buffer is cleared.
     */
    fun flush(): String {
        val remaining = buffer
        buffer = ""
        return remaining
    }

    /** Drop any partially-buffered text (called when a generation is cancelled so a
     *  half-sentence from a cancelled reply does not leak into the next request). */
    fun clear() {
        buffer = ""
    }
}