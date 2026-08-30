package com.jarvis.ai.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineJarvisEngineTest {

    @Test
    fun `multiplication is computed`() {
        val reply = OfflineJarvisEngine.respond("Calculate 42 * 19")
        assertTrue(reply.contains("798"))
    }

    @Test
    fun `operator chain respects precedence`() {
        val reply = OfflineJarvisEngine.respond("12 * 4 + 8")
        assertTrue(reply.contains("56"))
    }

    @Test
    fun `parenthesised input still produces a computed reply`() {
        val reply = OfflineJarvisEngine.respond("12 * (4 + 8)")
        assertTrue(reply.startsWith("Computed"))
    }

    @Test
    fun `exponentiation is computed`() {
        val reply = OfflineJarvisEngine.respond("2^10")
        assertTrue(reply.contains("1024"))
    }

    @Test
    fun `empty input yields non-blank reply`() {
        val reply = OfflineJarvisEngine.respond("")
        assertTrue(reply.isNotBlank())
    }

    @Test
    fun `time query addresses the user as sir`() {
        val reply = OfflineJarvisEngine.respond("what time is it")
        assertTrue(reply.lowercase().contains("sir"))
    }

    @Test
    fun `greeting yields a non-blank reply`() {
        val reply = OfflineJarvisEngine.respond("hello")
        assertTrue(reply.isNotBlank())
    }
}
