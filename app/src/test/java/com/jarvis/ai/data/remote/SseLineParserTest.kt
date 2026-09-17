package com.jarvis.ai.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseLineParserTest {

    @Test
    fun `parses crlf framed payloads in order`() {
        val events = SseLineParser.parseAll("data:{\"a\":1}\r\ndata:{\"b\":2}\r\n")
        assertEquals(
            listOf(
                SseEvent.DataPayload("{\"a\":1}"),
                SseEvent.DataPayload("{\"b\":2}")
            ),
            events
        )
    }

    @Test
    fun `done sentinel maps to done`() {
        assertEquals(SseEvent.Done, SseLineParser.parse("data:[DONE]"))
        assertEquals(SseEvent.Done, SseLineParser.parse("data: [DONE]"))
        assertEquals(listOf<SseEvent>(SseEvent.Done), SseLineParser.parseAll("data:[DONE]\n"))
    }

    @Test
    fun `comment lines are ignored`() {
        assertEquals(SseEvent.Ignore, SseLineParser.parse(":keep-alive"))
        assertTrue(SseLineParser.parseAll(":keep-alive\n\n").isEmpty())
    }

    @Test
    fun `event id and retry fields are ignored`() {
        val raw = "event: delta\nid: 42\nretry: 1000\ndata:{\"x\":1}\n"
        val events = SseLineParser.parseAll(raw)
        assertEquals(listOf<SseEvent>(SseEvent.DataPayload("{\"x\":1}")), events)
    }

    @Test
    fun `blank lines are ignored`() {
        assertTrue(SseLineParser.parseAll("\n\r\n\n").isEmpty())
        assertEquals(SseEvent.Ignore, SseLineParser.parse(""))
    }

    @Test
    fun `malformed data json passes through as payload`() {
        val events = SseLineParser.parseAll("data:not-json-at-all\n")
        assertEquals(listOf<SseEvent>(SseEvent.DataPayload("not-json-at-all")), events)
    }

    @Test
    fun `empty data line is ignored`() {
        assertEquals(SseEvent.Ignore, SseLineParser.parse("data:"))
        assertEquals(SseEvent.Ignore, SseLineParser.parse("data:   "))
    }

    @Test
    fun `unknown non-data lines are ignored`() {
        assertEquals(SseEvent.Ignore, SseLineParser.parse("hello world"))
        assertTrue(SseLineParser.parseAll("hello world\nmore noise\n").isEmpty())
    }
}
