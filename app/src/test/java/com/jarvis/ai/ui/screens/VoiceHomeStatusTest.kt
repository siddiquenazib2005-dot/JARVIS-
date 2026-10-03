package com.jarvis.ai.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceHomeStatusTest {

    private fun status(
        acting: Boolean = false,
        loading: Boolean = false,
        speaking: Boolean = false,
        listening: Boolean = false,
        handsFree: Boolean = false
    ) = voiceHomeStatus(acting, loading, speaking, listening, handsFree)

    @Test fun idleShowsPrompt() = assertEquals("How can I assist you today?", status())

    @Test fun actingWinsOverEverything() =
        assertEquals("Working on it…", status(acting = true, loading = true, speaking = true, listening = true))

    @Test fun loadingBeatsSpeakingAndListening() =
        assertEquals("Thinking…", status(loading = true, speaking = true, listening = true))

    @Test fun speakingBeatsListening() =
        assertEquals("Speaking…", status(speaking = true, listening = true))

    @Test fun handsFreeAloneReadsAsListening() = assertEquals("Listening…", status(handsFree = true))
}
