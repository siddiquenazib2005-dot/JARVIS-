package com.jarvis.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventBusAndRedactionTest {

    @Test
    fun `publish notifies subscribers and stores bounded history`() {
        var received: SystemEvent? = null
        val unsubscribe = EventBus.subscribe(EventType.USER_INPUT) { received = it }
        try {
            EventBus.publish(EventType.USER_INPUT, "hello core")
            assertEquals("hello core", received?.detail)
            assertTrue(EventBus.recent(10).any { it.type == EventType.USER_INPUT })
        } finally {
            unsubscribe()
        }
    }

    @Test
    fun `subscriber exceptions never break the bus`() {
        val boom = EventBus.subscribe(EventType.TASK_COMPLETED) { error("listener blew up") }
        var ok = false
        val safe = EventBus.subscribe(EventType.TASK_COMPLETED) { ok = true }
        try {
            EventBus.publish(EventType.TASK_COMPLETED, "x")
            assertTrue(ok)
        } finally {
            boom(); safe()
        }
    }

    @Test
    fun `secret redactor masks provider keys and jwt tokens`() {
        val dirty = """
            openrouter sk-or-v1-abcdef123456 groq gsk_qq11223344 qdrant
            eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig123 pinecone pcsk_abc123def456
            Authorization: Bearer abc.def.ghi api_key = plain-secret
        """.trimIndent()
        val clean = com.jarvis.ai.provider.SecretRedactor.redact(dirty)
        assertTrue(!clean.contains("sk-or-v1-abcdef"))
        assertTrue(!clean.contains("gsk_qq11223344"))
        assertTrue(!clean.contains("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig123"))
        assertTrue(!clean.contains("pcsk_abc123def456"))
        assertTrue(clean.contains("***REDACTED***"))
    }

    @Test
    fun `benign text passes through redactor untouched`() {
        val benign = "Computed, sir: 48."
        assertEquals(benign, com.jarvis.ai.provider.SecretRedactor.redact(benign))
    }
}
