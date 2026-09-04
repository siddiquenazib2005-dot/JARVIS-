package com.jarvis.ai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordDetectorTest {

    @Test
    fun `exact wake word triggers`() {
        assertTrue(WakeWordDetector.containsWakeWord("jarvis"))
        assertTrue(WakeWordDetector.containsWakeWord("Jarvis"))
        assertTrue(WakeWordDetector.containsWakeWord("JARVIS what's the time"))
    }

    @Test
    fun `wake word mid sentence triggers`() {
        assertTrue(WakeWordDetector.containsWakeWord("hey jarvis open whatsapp"))
        assertTrue(WakeWordDetector.containsWakeWord("jarvis, send a message to nazib"))
    }

    @Test
    fun `phonetic variants trigger`() {
        assertTrue(WakeWordDetector.containsWakeWord("javis status report"))
        assertTrue(WakeWordDetector.containsWakeWord("jervis, what's my battery"))
        assertTrue(WakeWordDetector.containsWakeWord("jarviz weather update"))
    }

    @Test
    fun `substrings inside other words never trigger`() {
        assertFalse(WakeWordDetector.containsWakeWord("jarvisite convention"))
        assertFalse(WakeWordDetector.containsWakeWord("my jarvisson is visiting"))
    }

    @Test
    fun `ordinary chatter does not trigger`() {
        assertFalse(WakeWordDetector.containsWakeWord(""))
        assertFalse(WakeWordDetector.containsWakeWord("what's the weather like in tokyo"))
        assertFalse(WakeWordDetector.containsWakeWord("remind me about the gardiner street meeting"))
    }

    @Test
    fun `strip removes wake word and keeps command`() {
        assertEquals(
            "what's the time",
            WakeWordDetector.stripWakeWord("jarvis what's the time")
        )
        assertEquals(
            "open whatsapp",
            WakeWordDetector.stripWakeWord("JARVIS open whatsapp")
        )
    }

    @Test
    fun `strip handles punctuation around the wake word`() {
        assertEquals("status report", WakeWordDetector.stripWakeWord("jarvis, status report"))
    }

    @Test
    fun `strip returns null for name-only utterance`() {
        assertNull(WakeWordDetector.stripWakeWord("jarvis"))
        assertNull(WakeWordDetector.stripWakeWord("jarvis!"))
    }

    @Test
    fun `strip passes through transcripts without the wake word`() {
        val raw = "open whatsapp"
        assertEquals(raw, WakeWordDetector.stripWakeWord(raw))
    }
}
