package com.jarvis.ai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEngineTest {

    private fun engine() = MemoryEngine(InMemoryKv())

    @Test
    fun `remember and recall fact roundtrip`() {
        val mem = engine()
        assertTrue(mem.rememberFact("favorite model", "llama-3.3-70b"))
        assertEquals("llama-3.3-70b", mem.recallFact("Favorite Model"))
    }

    @Test
    fun `forget removes fact and reports existence`() {
        val mem = engine()
        mem.rememberFact("name", "sir")
        assertTrue(mem.forgetFact("name"))
        assertNull(mem.recallFact("name"))
        assertFalse(mem.forgetFact("never-existed"))
    }

    @Test
    fun `blank writes are rejected`() {
        val mem = engine()
        assertFalse(mem.rememberFact("  ", "x"))
        assertFalse(mem.rememberFact("topic", "  "))
        assertNull(mem.recallFact("topic"))
    }

    @Test
    fun `allFacts lists everything stored`() {
        val mem = engine()
        mem.rememberFact("a", "1")
        mem.rememberFact("b", "2")
        assertEquals(2, mem.allFacts().size)
    }

    @Test
    fun `episodes cap at fifty newest kept`() {
        val mem = engine()
        repeat(60) { mem.recordEpisode("event-$it") }
        val recent = mem.recentEpisodes(100)
        assertEquals(50, recent.size)
        assertTrue(recent.last().contains("event-59"))
        assertTrue(recent.first().contains("event-10"))
    }

    @Test
    fun `wipe removes all user data`() {
        val mem = engine()
        mem.rememberFact("k", "v")
        mem.setPreference("tts", "on")
        mem.setProjectContext("p", "summary", "s")
        mem.recordEpisode("e")
        val removed = mem.deleteAllUserData()
        assertTrue(removed >= 4)
        assertNull(mem.recallFact("k"))
        assertEquals(emptyList<String>(), mem.recentEpisodes(10))
    }
}

class ContextBuilderTest {

    private fun msg(user: Boolean, text: String) =
        com.jarvis.ai.data.model.Message(
            sender = if (user) com.jarvis.ai.data.model.Sender.USER
            else com.jarvis.ai.data.model.Sender.JARVIS,
            text = text
        )

    @Test
    fun `system instructions always first`() {
        val built = ContextBuilder(MemoryEngine(InMemoryKv()))
            .build("You are JARVIS.", emptyList(), "hello")
        assertEquals("You are JARVIS.", built.messages.first().content)
    }

    @Test
    fun `relevant facts are injected when query overlaps`() {
        val mem = MemoryEngine(InMemoryKv())
        mem.rememberFact("favorite model", "llama-3.3-70b-versatile on groq")
        mem.rememberFact("unrelated", "likes tea")

        val built = ContextBuilder(mem).build(
            systemInstructions = "sys",
            recentConversation = emptyList(),
            currentRequest = "switch my model to the favourite one"
        )

        val factBlock = built.messages.firstOrNull { it.content.startsWith("Known facts") }
        assertTrue(factBlock != null)
        assertTrue(factBlock!!.content.contains("llama-3.3-70b-versatile"))
        assertFalse(factBlock.content.contains("likes tea"))
        assertTrue(built.includedFactCount >= 1)
    }

    @Test
    fun `recent conversation is trimmed to limit and keeps order`() {
        val mem = MemoryEngine(InMemoryKv())
        val convo = (1..30).map { msg(it % 2 == 0, "message number $it") }
        val built = ContextBuilder(mem, recentMessageLimit = 5)
            .build("sys", convo, "continue")
        val chatMessages = built.messages.filter { it.role != "system" }
        assertEquals(6, chatMessages.size)
        assertEquals("message number 26", chatMessages.first().content)
        assertEquals("continue", chatMessages.last().content)
    }

    @Test
    fun `char budget bounds context size`() {
        val mem = MemoryEngine(InMemoryKv())
        mem.rememberFact("big", "x".repeat(2000))
        val longConvo = (1..40).map { msg(true, "word ".repeat(120)) }
        val built = ContextBuilder(mem, maxChars = 3000, recentMessageLimit = 50)
            .build("sys", longConvo, "q")
        val totalChars = built.messages.sumOf { it.content.length } + 400
        assertTrue(totalChars <= 3000 + 800)
    }

    @Test
    fun `tool results block included when provided`() {
        val mem = MemoryEngine(InMemoryKv())
        val built = ContextBuilder(mem).build(
            "sys", emptyList(), "summarise",
            toolResults = "Search found: JARVIS wins award"
        )
        assertTrue(built.messages.any { it.content.contains("Search found") })
    }
}
