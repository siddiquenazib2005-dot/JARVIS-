package com.jarvis.ai.ui.components.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `heading levels parsed`() {
        val h1 = MarkdownParser.parse("# Title").single() as MdBlock.Heading
        assertEquals(1, h1.level)
        assertEquals(listOf<MdSpan>(MdSpan.Plain("Title")), h1.spans)

        val h3 = MarkdownParser.parse("### Sub").single() as MdBlock.Heading
        assertEquals(3, h3.level)
        assertEquals(listOf<MdSpan>(MdSpan.Plain("Sub")), h3.spans)
    }

    @Test
    fun `bold italic strike and inline code spans`() {
        assertEquals(listOf<MdSpan>(MdSpan.Bold("hi")), MarkdownParser.parseInline("**hi**"))
        assertEquals(listOf<MdSpan>(MdSpan.Bold("hi")), MarkdownParser.parseInline("__hi__"))
        assertEquals(listOf<MdSpan>(MdSpan.Italic("yo")), MarkdownParser.parseInline("*yo*"))
        assertEquals(listOf<MdSpan>(MdSpan.Strike("gone")), MarkdownParser.parseInline("~~gone~~"))
        assertEquals(listOf<MdSpan>(MdSpan.InlineCode("x=1")), MarkdownParser.parseInline("`x=1`"))
    }

    @Test
    fun `fenced code captures language and closed flag`() {
        val closed = MarkdownParser.parse("```kotlin\nval x = 1\n```").single() as MdBlock.CodeFence
        assertEquals("kotlin", closed.language)
        assertEquals("val x = 1", closed.content)
        assertTrue(closed.closed)

        val open = MarkdownParser.parse("```PY\nprint(1)").single() as MdBlock.CodeFence
        assertEquals("py", open.language)
        assertEquals("print(1)", open.content)
        assertFalse(open.closed)
    }

    @Test
    fun `bullet and ordered lists extract numbers`() {
        val bullets = MarkdownParser.parse("- a\n* b\n\u2022 c")
        val contents = listOf("a", "b", "c")
        assertEquals(3, bullets.size)
        bullets.forEachIndexed { index, block ->
            val item = block as MdBlock.ListItem
            assertFalse(item.ordered)
            assertEquals(0, item.number)
            assertEquals(listOf<MdSpan>(MdSpan.Plain(contents[index])), item.spans)
        }

        val ordered = MarkdownParser.parse("1. first\n2) second")
        val first = ordered[0] as MdBlock.ListItem
        val second = ordered[1] as MdBlock.ListItem
        assertTrue(first.ordered)
        assertEquals(1, first.number)
        assertTrue(second.ordered)
        assertEquals(2, second.number)
    }

    @Test
    fun `link span extracts url`() {
        val link = MarkdownParser.parseInline("[docs](https://example.com)").single() as MdSpan.Link
        assertEquals("docs", link.text)
        assertEquals("https://example.com", link.url)
    }

    @Test
    fun `multiline paragraph merged into single paragraph with newline`() {
        val merged = MarkdownParser.parse("one line\ntwo line").single() as MdBlock.Paragraph
        assertEquals(listOf<MdSpan>(MdSpan.Plain("one line\ntwo line")), merged.spans)

        val splitByBlank = MarkdownParser.parse("a\n\nb")
        assertEquals(2, splitByBlank.size)
        assertTrue(splitByBlank.all { it is MdBlock.Paragraph })
    }

    @Test
    fun `rule detected`() {
        listOf("---", "***", "___").forEach { marker ->
            assertEquals(listOf<MdBlock>(MdBlock.Rule), MarkdownParser.parse(marker))
        }
    }

    @Test
    fun `partial bold mid stream stays literal without exception`() {
        val spans = MarkdownParser.parseInline("**bo")
        assertEquals(listOf<MdSpan>(MdSpan.Plain("**bo")), spans)
    }

    @Test
    fun `empty input yields empty list`() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parseInline("").isEmpty())
    }

    @Test
    fun `quote block parsed`() {
        val quote = MarkdownParser.parse("> quoted text").single() as MdBlock.Quote
        assertEquals(listOf<MdSpan>(MdSpan.Plain("quoted text")), quote.spans)
    }
}
