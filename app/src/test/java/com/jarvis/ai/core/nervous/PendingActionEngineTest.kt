package com.jarvis.ai.core.nervous

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingActionEngineTest {
    private fun engineWithSelection(): PendingActionEngine {
        val engine = PendingActionEngine(now = { 100L })
        engine.set(PendingAction(
            actionId = "call-1",
            type = PendingActionType.CONTACT_SELECTION,
            originalRequest = "call Hasib",
            options = listOf(
                PendingOption("1", "Hasib Bro", "••••7429"),
                PendingOption("2", "Hasib Bro 2", "••••1032")
            ),
            createdAtMs = 0,
            expiresAtMs = 1000
        ))
        return engine
    }

    @Test fun numericSelectionResolvesWithoutAi() {
        val engine = engineWithSelection()
        val result = engine.resolve("1") as ContextResolution.Selected
        assertEquals("Hasib Bro", result.option.label)
        assertNull(engine.pending.value)
    }

    @Test fun hinglishAndHindiSelectionResolveLocally() {
        assertTrue(engineWithSelection().resolve("pehla") is ContextResolution.Selected)
        assertTrue(engineWithSelection().resolve("पहला") is ContextResolution.Selected)
    }

    @Test fun stopCancelsAndClearsPendingAction() {
        val engine = engineWithSelection()
        assertTrue(engine.resolve("stop") is ContextResolution.Cancelled)
        assertNull(engine.pending.value)
    }
}
