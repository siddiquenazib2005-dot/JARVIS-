package com.jarvis.ai.automation

/**
 * Item 4: honest feedback channel for screen automation.
 *
 * Every gesture is fire-and-forget: the router answers "Tapping X, sir."
 * instantly and the real work happens on a background scope. That made every
 * failure invisible -- the assistant claimed success even when nothing on
 * screen moved.
 *
 * This tiny bus closes the loop. The automation layer reports the verified
 * outcome, and the view model turns it into a follow-up chat line.
 *
 * Kept lock-free and single-listener on purpose: exactly one chat surface is
 * alive at a time, and a queue would replay stale gestures after a rotation.
 */
object AutomationFeedback {

    @Volatile
    private var listener: ((String) -> Unit)? = null

    /** Registers the chat surface. A second call replaces the first. */
    fun observe(block: (String) -> Unit) {
        listener = block
    }

    fun stopObserving() {
        listener = null
    }

    /**
     * Reports a follow-up sentence. Dropped silently when no chat surface is
     * listening, which is correct: the user is not looking at AURIX.
     */
    fun report(text: String) {
        if (text.isBlank()) return
        listener?.invoke(text)
    }
}
