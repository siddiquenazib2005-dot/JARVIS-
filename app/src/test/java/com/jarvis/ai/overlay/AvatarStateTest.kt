package com.jarvis.ai.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Priority ladder for the five assistant visuals. The whole point of ACTING is
 * that a device/tool action is visible as such — not folded into THINKING —
 * and that it is never masked by a lower-priority flag.
 */
class AvatarStateTest {

    @Test
    fun `idle when nothing is active`() {
        assertEquals(AvatarState.IDLE, AvatarState.from(false, false, false, false))
    }

    @Test
    fun `each flag maps to its own state`() {
        assertEquals(AvatarState.LISTENING, AvatarState.from(true, false, false, false))
        assertEquals(AvatarState.THINKING, AvatarState.from(false, false, true, false))
        assertEquals(AvatarState.SPEAKING, AvatarState.from(false, true, false, false))
        assertEquals(AvatarState.ACTING, AvatarState.from(false, false, false, true))
    }

    @Test
    fun `acting outranks listening and thinking`() {
        // An in-flight device action can change the screen, so it must not be
        // hidden behind the mic indicator or a plain spinner.
        assertEquals(AvatarState.ACTING, AvatarState.from(true, false, false, true))
        assertEquals(AvatarState.ACTING, AvatarState.from(false, false, true, true))
    }

    @Test
    fun `speaking outranks acting`() {
        // While AURIX talks it owns the turn, even if a follow-up action is queued.
        assertEquals(AvatarState.SPEAKING, AvatarState.from(false, true, false, true))
    }

    @Test
    fun `acting is visually distinct from thinking`() {
        assertNotEquals(AvatarState.ACTING.innerColor, AvatarState.THINKING.innerColor)
        assertNotEquals(AvatarState.ACTING.glyph, AvatarState.THINKING.glyph)
        assertNotEquals(AvatarState.ACTING.periodMs, AvatarState.THINKING.periodMs)
    }

    @Test
    fun `isActing defaults off so existing callers are unaffected`() {
        assertEquals(AvatarState.IDLE, AvatarState.from(false, false, false))
        assertEquals(AvatarState.THINKING, AvatarState.from(false, false, true))
    }
}
