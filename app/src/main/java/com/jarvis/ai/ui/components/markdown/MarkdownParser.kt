package com.jarvis.ai.ui.components.markdown

sealed class MdSpan {
    abstract val text: String

    data class Plain(override val text: String) : MdSpan()
    data class Bold(override val text: String) : MdSpan()
    data class Italic(override val text: String) : MdSpan()
    data class Strike(override val text: String) : MdSpan()
    data class InlineCode(override val text: String) : MdSpan()
    data class Link(override val text: String, val url: String) : MdSpan()
}

sealed class MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock()
    data class Paragraph(val spans: List<MdSpan>) : MdBlock()

    // closed=false when the stream ended before the closing fence (streaming-friendly)
    data class CodeFence(val language: String, val content: String, val closed: Boolean) : MdBlock()
    data class Quote(val spans: List<MdSpan>) : MdBlock()
    data class ListItem(val ordered: Boolean, val number: Int, val spans: List<MdSpan>) : MdBlock()
    object Rule : MdBlock()
}

object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})[ \\t]+(.*)$")
    private val QUOTE = Regex("^[ \\t]*(?:>[ \\t]?)+(.*)$")
    private val RULE = Regex("^([-*_])\\1{2,}$")
    private val BULLET = Regex("^[ \\t]*[-*\u2022][ \\t]+(.*)$")
    private val ORDERED = Regex("^(\\d+)[.)][ \\t]+(.*)$")
    private val WHITESPACE = Regex("\\s+")

    fun parse(source: String): List<MdBlock> {
        if (source.isEmpty()) return emptyList()
        val lines = source.split('\n')
        val blocks = mutableListOf<MdBlock>()
        val paragraphLines = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraphLines.isNotEmpty()) {
                blocks += MdBlock.Paragraph(parseInline(paragraphLines.joinToString("\n")))
                paragraphLines.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            if (trimmed.startsWith("```")) {
                flushParagraph()
                val language = trimmed.removePrefix("```").trim()
                    .split(WHITESPACE).first().lowercase()
                val body = mutableListOf<String>()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith("```")) {
                        closed = true
                        i++
                        break
                    }
                    body += lines[i]
                    i++
                }
                blocks += MdBlock.CodeFence(language, body.joinToString("\n"), closed)
                continue
            }

            val headingMatch = HEADING.matchEntire(trimmed)
            val quoteMatch = QUOTE.matchEntire(trimmed)
            val ruleMatch = RULE.matchEntire(trimmed)
            val bulletMatch = BULLET.matchEntire(trimmed)
            val orderedMatch = ORDERED.matchEntire(trimmed)

            when {
                headingMatch != null -> {
                    flushParagraph()
                    blocks += MdBlock.Heading(
                        level = headingMatch.groupValues[1].length,
                        spans = parseInline(headingMatch.groupValues[2])
                    )
                }

                quoteMatch != null -> {
                    flushParagraph()
                    blocks += MdBlock.Quote(parseInline(quoteMatch.groupValues[1]))
                }

                ruleMatch != null -> {
                    flushParagraph()
                    blocks += MdBlock.Rule
                }

                bulletMatch != null -> {
                    flushParagraph()
                    blocks += MdBlock.ListItem(false, 0, parseInline(bulletMatch.groupValues[1]))
                }

                orderedMatch != null -> {
                    flushParagraph()
                    blocks += MdBlock.ListItem(
                        ordered = true,
                        number = orderedMatch.groupValues[1].toIntOrNull() ?: 0,
                        spans = parseInline(orderedMatch.groupValues[2])
                    )
                }

                line.isBlank() -> flushParagraph()

                else -> paragraphLines += line
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    fun parseInline(text: String): List<MdSpan> {
        if (text.isEmpty()) return emptyList()
        val spans = mutableListOf<MdSpan>()
        val literal = StringBuilder()
        var i = 0
        val n = text.length

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                spans += MdSpan.Plain(literal.toString())
                literal.clear()
            }
        }

        while (i < n) {
            val c = text[i]

            when {
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close == -1) {
                        literal.append(c)
                        i++
                    } else {
                        flushLiteral()
                        spans += MdSpan.InlineCode(text.substring(i + 1, close))
                        i = close + 1
                    }
                }

                c == '~' && i + 1 < n && text[i + 1] == '~' -> {
                    val close = text.indexOf("~~", i + 2)
                    if (close == -1) {
                        literal.append(c)
                        i++
                    } else {
                        flushLiteral()
                        spans += MdSpan.Strike(text.substring(i + 2, close))
                        i = close + 2
                    }
                }

                (c == '*' || c == '_') && i + 1 < n && text[i + 1] == c -> {
                    val close = text.indexOf("$c$c", i + 2)
                    if (close == -1) {
                        literal.append(c)
                        i++
                    } else {
                        flushLiteral()
                        spans += MdSpan.Bold(text.substring(i + 2, close))
                        i = close + 2
                    }
                }

                c == '*' || c == '_' -> {
                    val close = text.indexOf(c, i + 1)
                    if (close == -1) {
                        literal.append(c)
                        i++
                    } else {
                        flushLiteral()
                        spans += MdSpan.Italic(text.substring(i + 1, close))
                        i = close + 1
                    }
                }

                c == '[' -> {
                    val bracketEnd = text.indexOf(']', i + 1)
                    if (bracketEnd != -1 && bracketEnd + 1 < n && text[bracketEnd + 1] == '(') {
                        val parenEnd = text.indexOf(')', bracketEnd + 2)
                        if (parenEnd != -1) {
                            flushLiteral()
                            spans += MdSpan.Link(
                                text = text.substring(i + 1, bracketEnd),
                                url = text.substring(bracketEnd + 2, parenEnd)
                            )
                            i = parenEnd + 1
                        } else {
                            literal.append(c)
                            i++
                        }
                    } else {
                        literal.append(c)
                        i++
                    }
                }

                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flushLiteral()
        return spans
    }
}
