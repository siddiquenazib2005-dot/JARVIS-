package com.jarvis.ai.mission

import com.jarvis.ai.service.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Phase-2 §8: the link between the voice channel and the mission layer.
 *
 * Two directions, both driven by real state rather than guesses:
 *
 *  * Mission → voice. When the observable mission reaches a state the user needs
 *    to hear about, it is spoken exactly once. Only meaningful transitions are
 *    announced — not every step tick, and never chain-of-thought.
 *
 *  * Voice → mission. A spoken command is checked against the mission's needs:
 *    a confirmation phrase approves a waiting mission, a cancel phrase stops it.
 *    Phrases match on whole words so "cancel" inside "cancellation" is ignored,
 *    and a phrase that matches nothing is left alone for the normal chat path.
 *
 * The bridge never starts or stops the speech recognizer itself; that stays with
 * the view model, which owns the mic lifecycle.
 */
class MissionVoiceBridge(
    private val speech: MissionSpeechPort,
    private val scope: CoroutineScope
) {

    /** The runner this bridge is bound to, or null before [bind]. */
    private var runner: MissionRunner? = null
    private var announceJob: Job? = null

    private val _lastAnnouncement = MutableStateFlow<String?>(null)
    /** The most recent spoken mission announcement, for inspection and tests. */
    val lastAnnouncement: StateFlow<String?> = _lastAnnouncement.asStateFlow()

    /**
     * Wires the bridge to a runner. Safe to call repeatedly: the previous
     * observer is cancelled so announcements are never duplicated.
     */
    fun bind(runner: MissionRunner) {
        this.runner = runner
        announceJob?.cancel()
        announceJob = scope.launch {
            // distinctUntilChanged on the pair means a repeated identical state
            // (e.g. two consecutive EXECUTING ticks) is spoken at most once.
            runner.activeMission.filterNotNull()
                .map { it.state to it.announcementForState() }
                .distinctUntilChanged()
                .drop(1) // The initial binding must not speak out loud.
                .collect { (_, message) ->
                    message ?: return@collect
                    _lastAnnouncement.value = message
                    speech.speak(message)
                }
        }
    }

    /** Stops observing. Called when the owner is destroyed. */
    fun unbind() {
        announceJob?.cancel()
        announceJob = null
        runner = null
    }

    /**
     * Interprets a recognized phrase in the context of the live mission.
     *
     * @return true if the phrase was consumed by the mission layer (so the caller
     * should NOT also route it to chat), false if it is a normal utterance.
     */
    fun handleSpokenPhrase(phrase: String): Boolean {
        val mission = runner?.activeMission?.value ?: return false
        val normalized = phrase.trim().lowercase()
        if (normalized.isEmpty()) return false

        // A terminal mission has nothing to accept or cancel.
        if (mission.isTerminal) return false

        return when {
            isCancelPhrase(normalized) -> {
                runner?.cancel()
                announceNow("Cancelling.")
                true
            }
            isConfirmPhrase(normalized) && mission.state == MissionState.WAITING_CONFIRMATION -> {
                // Approval is recorded for the dispatcher; the runner performs the
                // permission-gate check itself on the confirmed re-run.
                pendingApproval = mission.goal
                true
            }
            else -> false
        }
    }

    /** Goal awaiting spoken approval, consumed by the dispatcher on the next turn. */
    @Volatile
    var pendingApproval: String? = null
        internal set

    private fun announceNow(text: String) {
        _lastAnnouncement.value = text
        speech.speak(text)
    }

    // ------------------------------------------------------------- matching

    private fun isCancelPhrase(text: String): Boolean =
        CANCEL_PHRASES.any { word -> matchesWholeWord(text, word) }

    private fun isConfirmPhrase(text: String): Boolean =
        CONFIRM_PHRASES.any { word -> matchesWholeWord(text, word) }

    /**
     * Whole-word match so "cancel" does not fire on "cancellation" and "yes"
     * does not fire on "yesterday". Phrase lists are short and intentional.
     */
    private fun matchesWholeWord(text: String, word: String): Boolean {
        var start = 0
        while (true) {
            val idx = text.indexOf(word, start)
            if (idx < 0) return false
            val before = idx == 0 || !text[idx - 1].isLetterOrDigit()
            val after = idx + word.length == text.length || !text[idx + word.length].isLetterOrDigit()
            if (before && after) return true
            start = idx + 1
        }
    }

    private companion object {
        val CANCEL_PHRASES = listOf("cancel", "stop it", "never mind", "abort", "stop this")
        val CONFIRM_PHRASES = listOf("yes", "yeah", "confirm", "go ahead", "do it", "sure", "okay")
    }
}

/**
 * The speech surface the bridge talks to. [TtsEngine] is final and Context-bound,
 * so this port keeps the bridge (and its tests) decoupled from Android; the
 * production adapter is a one-liner in the view model.
 */
interface MissionSpeechPort {
    val muted: Boolean
    fun speak(text: String)
}

/** Adapts the real TTS engine to the bridge's speech port. */
fun TtsEngine.asMissionSpeechPort(): MissionSpeechPort = object : MissionSpeechPort {
    override val muted: Boolean get() = this@asMissionSpeechPort.muted
    override fun speak(text: String) = this@asMissionSpeechPort.speak(text)
}

/**
 * Maps a mission to the announcement it warrants, or null when it should stay
 * silent. Extracted as a function so the speech decision is testable without a
 * TTS engine or a coroutine scope.
 */
fun Mission.announcementForState(): String? = when (state) {
    MissionState.WAITING_CONFIRMATION ->
        "This will use ${currentStep.toolName ?: "a device action"}. Say yes to confirm, or cancel to stop."
    MissionState.COMPLETED -> "Done. $goal"
    MissionState.FAILED -> "I couldn't finish. ${lastError ?: "Something went wrong"}."
    MissionState.CANCELLED -> "Cancelled."
    MissionState.RECOVERING -> "That didn't work. Trying another way."
    // Silence: CREATED/UNDERSTANDING/PLANNING/EXECUTING/OBSERVING/VERIFYING are
    // progress the UI already shows; speaking every tick would be noise.
    else -> null
}
