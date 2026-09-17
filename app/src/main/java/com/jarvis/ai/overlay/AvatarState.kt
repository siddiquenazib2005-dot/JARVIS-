package com.jarvis.ai.overlay

/**
 * Visual state of the floating AURIX bubble.
 *
 * The bubble used to be a static red dot: the owner could not tell whether
 * AURIX was listening, working or done. Each state below maps to one distinct
 * animation so the bubble is readable at a glance, without opening the app.
 *
 * Colours reuse the app palette (Accent #FF1744, Ember #FF6B00) so the bubble
 * still looks like AURIX in every state.
 *
 * @param label short spoken-word description, also used by the foreground
 *        notification so the state is visible when the bubble is hidden.
 * @param innerColor centre colour of the bubble gradient.
 * @param outerColor edge colour of the bubble gradient.
 * @param minScale smallest size during the pulse, as a fraction of full size.
 * @param maxScale largest size during the pulse.
 * @param periodMs one full pulse in milliseconds; smaller means more urgent.
 * @param glyph single character drawn in the middle of the bubble.
 */
enum class AvatarState(
    val label: String,
    val innerColor: Int,
    val outerColor: Int,
    val minScale: Float,
    val maxScale: Float,
    val periodMs: Long,
    val glyph: String
) {
    /** Nothing happening: a slow "breathing" pulse, easy to ignore. */
    IDLE(
        label = "on standby",
        innerColor = 0xFFFF1744.toInt(),
        outerColor = 0xFFFF6B00.toInt(),
        minScale = 0.94f,
        maxScale = 1.00f,
        periodMs = 2600L,
        glyph = "A"
    ),

    /** Microphone is open: fast, obvious pulse so the owner knows to speak. */
    LISTENING(
        label = "listening",
        innerColor = 0xFF34C759.toInt(),
        outerColor = 0xFF00B0FF.toInt(),
        minScale = 0.88f,
        maxScale = 1.12f,
        periodMs = 780L,
        glyph = "*"
    ),

    /** A model call or tool chain is running: steady medium pulse. */
    THINKING(
        label = "working on it",
        innerColor = 0xFFFFA000.toInt(),
        outerColor = 0xFFFF6B00.toInt(),
        minScale = 0.92f,
        maxScale = 1.06f,
        periodMs = 1200L,
        glyph = "~"
    ),

    /** AURIX is talking back: quick shimmer that reads as output, not input. */
    SPEAKING(
        label = "speaking",
        innerColor = 0xFF00B0FF.toInt(),
        outerColor = 0xFFFF1744.toInt(),
        minScale = 0.90f,
        maxScale = 1.10f,
        periodMs = 620L,
        glyph = "="
    );

    companion object {
        /**
         * Maps the chat UI flags to one bubble state.
         *
         * Priority matters: speaking wins over listening (both can be briefly
         * true while a voice turn hands over), and listening wins over a
         * background model call so the mic indicator is never hidden.
         */
        fun from(
            isListening: Boolean,
            isSpeaking: Boolean,
            isLoading: Boolean
        ): AvatarState = when {
            isSpeaking -> SPEAKING
            isListening -> LISTENING
            isLoading -> THINKING
            else -> IDLE
        }
    }
}

/**
 * Tiny observable holder shared between the chat UI and the overlay service.
 *
 * A bus is used instead of binding to the service because the bubble may not
 * be running at all: callers can always publish a state, and it is simply
 * remembered until the bubble appears. Reads are lock-free so the animation
 * loop never blocks on the UI thread.
 */
object AvatarStateBus {

    @Volatile
    private var current: AvatarState = AvatarState.IDLE

    @Volatile
    private var listener: ((AvatarState) -> Unit)? = null

    /** Last published state; the bubble uses this when it starts up. */
    fun state(): AvatarState = current

    /** Publishes a new state. No-op when the state has not changed. */
    fun set(next: AvatarState) {
        if (next == current) return
        current = next
        runCatching { listener?.invoke(next) }
    }

    /** Called by the bubble service while it is alive. */
    fun observe(block: (AvatarState) -> Unit) {
        listener = block
        runCatching { block(current) }
    }

    /** Called when the bubble goes away, so no stale view is touched. */
    fun stopObserving() {
        listener = null
    }
}
