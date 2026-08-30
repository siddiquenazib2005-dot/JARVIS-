package com.jarvis.ai.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jarvis.ai.core.EventBus
import com.jarvis.ai.core.EventType
import com.jarvis.ai.core.JarvisRuntime
import com.jarvis.ai.data.local.ChatDb
import com.jarvis.ai.data.model.LatencyInfo
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.data.model.SessionInfo
import com.jarvis.ai.data.model.UiState
import com.jarvis.ai.health.SystemHealthReporter
import com.jarvis.ai.memory.MemoryEngine
import com.jarvis.ai.orchestrator.MasterOrchestrator
import com.jarvis.ai.orchestrator.OrchestratorUpdate
import com.jarvis.ai.orchestrator.ToolExecutor
import com.jarvis.ai.presence.PresenceGate
import com.jarvis.ai.proactive.ProactiveEngine
import com.jarvis.ai.service.AudioLevelEngine
import com.jarvis.ai.service.SpeechRecognitionManager
import com.jarvis.ai.service.SentenceParser
import com.jarvis.ai.service.TtsEngine
import com.jarvis.ai.system.SystemAwareness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-side state holder. ALL intelligence flows through [MasterOrchestrator];
 * this class never touches provider credentials or endpoints directly.
 */
class JarvisViewModel(
    private val runtime: JarvisRuntime,
    private val db: ChatDb,
    private val tts: TtsEngine,
    private val stt: SpeechRecognitionManager,
    val audioEngine: AudioLevelEngine,
    private val memory: MemoryEngine,
    private val orchestrator: MasterOrchestrator,
    private val awareness: SystemAwareness,
    private val proactive: ProactiveEngine,
    private val presenceGate: PresenceGate
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val orbLevel: Flow<Float> =
        combine(audioEngine.level, tts.envelope) { mic, speech -> maxOf(mic, speech) }
            .distinctUntilChanged()

    val ttsMuted: Boolean get() = tts.muted

    private var generationJob: Job? = null
    private var listening = false

    /** Tool awaiting explicit confirmation ("yes" routes back through the gate). */
    private var pendingConfirmation: String? = null

    private val sentenceParser = SentenceParser()

    private val ttsQueue: MutableStateFlow<String?> = MutableStateFlow(null)

    private val healthReporter by lazy {
        SystemHealthReporter(
            runtime = runtime,
            awareness = awareness,
            voiceInputAvailable = { stt.isAvailable },
            ttsAvailable = { tts.available }
        )
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            var sessions = db.sessions()
            if (sessions.isEmpty()) {
                val created = SessionInfo(title = DEFAULT_SESSION_TITLE)
                db.createSession(created)
                sessions = db.sessions()
            }
            val active = sessions.first()
            val restored = db.messages(active.id)
            _uiState.update {
                it.copy(
                    messages = restored.ifEmpty { listOf(greetingMessage()) },
                    backendOnline = runtime.keys.registeredProviders().isNotEmpty(),
                    sessions = sessions,
                    activeSessionId = active.id
                )
            }
        }

        viewModelScope.launch {
            tts.isSpeaking.collect { speaking ->
                _uiState.update { it.copy(isSpeaking = speaking) }
            }
        }

        // Greeting is spoken ONCE per session/period (PresenceGate dedups);
        // recompositions never reach here.
        viewModelScope.launch {
            if (presenceGate.shouldGreet()) {
                EventBus.publish(EventType.SESSION_STARTED)
                val text = _uiState.value.messages.firstOrNull()?.text
                if (!text.isNullOrBlank() && !tts.muted) tts.speak(text)
            }
        }

        // Proactive intelligence: one evaluation per minute while foregrounded;
        // ProactiveEngine itself enforces anti-spam intervals.
        viewModelScope.launch {
            while (isActive) {
                delay(PROACTIVE_POLL_MS)
                runCatching {
                    val snap = awareness.snapshot()
                    proactive.evaluate(snap)?.let { message ->
                        announce(message)
                    } ?: proactive.acknowledgeConditions(snap)
                }
            }
        }

        // Sentence-level TTS queue manager.
        // Sentences become available via the sentence parser as the LLM streams.
        // They are played one-at-a-time; the next auto-plays when the current finishes.
        viewModelScope.launch {
            var pending = sentenceParser.flush() // flush any leftover from previous session
            if (pending.isNotBlank()) {
                ttsQueue.value = pending
            }
            while (isActive) {
                val toSpeak = ttsQueue.value
                if (toSpeak != null && toSpeak.isNotBlank() && !tts.muted) {
                    if (!tts.isSpeaking.value) {
                        tts.speak(toSpeak)
                    }
                }
                // Wait for TTS to finish; poll isSpeaking since we have no
                // direct callback beyond the existing StateFlow.
                delay(200L)
            }
        }
    }

    fun send(rawInput: String) {
        dispatchToOrchestrator(rawInput, confirmed = false)
    }

    /** Routes an affirmative confirmation back through the PermissionGate. */
    fun confirmPendingAction() {
        val pending = pendingConfirmation ?: return
        pendingConfirmation = null
        dispatchToOrchestrator(pending, confirmed = true)
    }

    private fun dispatchToOrchestrator(text: String, confirmed: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isLoading) return
        val sessionId = _uiState.value.activeSessionId
        if (sessionId.isBlank()) return

        clearNotice()
        val userMessage = Message(sender = Sender.USER, text = trimmed)
        val history = _uiState.value.messages.filterNot { it.text.startsWith(GREETING_MARK) } + userMessage
        _uiState.update { it.copy(messages = history, isLoading = true, latency = LatencyInfo()) }

        viewModelScope.launch(Dispatchers.IO) {
            db.appendMessage(sessionId, userMessage)
            if (_uiState.value.messages.none {
                    it.sender == Sender.USER && it.id != userMessage.id && !it.text.startsWith(GREETING_MARK)
                }
            ) {
                db.renameSession(sessionId, trimmed.take(TITLE_MAX_LENGTH))
            } else {
                db.touchSession(sessionId)
            }
        }

        generationJob = viewModelScope.launch {
            val replyId = newReplyId()
            var started = false
            val fullText = StringBuilder()
            startedAtForReply = System.nanoTime()
            try {
                orchestrator.processRequest(
                    input = trimmed,
                    history = history.dropLast(1),
                    userConfirmedThisTurn = confirmed
                ).collect { update ->
                    when (update) {
                        is OrchestratorUpdate.Delta -> {
                            sentenceParser.addChunk(update.text)
                            val sentence = sentenceParser.nextSentence()
                            if (sentence?.isNotBlank() == true) {
                                fullText.append(sentence).append(" ")
                                ttsQueue.value = sentence
                            }
                            if (!started) {
                                started = true
                                _uiState.update { s ->
                                    s.copy(
                                        messages = s.messages + Message(
                                            id = replyId,
                                            sender = Sender.JARVIS,
                                            text = fullText.toString().trim()
                                        )
                                    )
                                }
                            } else {
                                updateReply(replyId, fullText.toString().trim())
                            }
                        }

                        is OrchestratorUpdate.Confirmation -> {
                            pendingConfirmation = trimmed
                            setNotice("${update.message} (Tap SEND to confirm.)")
                        }

                        is OrchestratorUpdate.Completed -> {
                            val remaining = sentenceParser.flush()
                            if (remaining.isNotBlank()) {
                                fullText.append(remaining).append(" ")
                                ttsQueue.value = remaining.trim()
                            }
                            _uiState.update { s ->
                                s.copy(
                                    activeProvider = update.provider,
                                    backendOnline = update.success || s.backendOnline
                                )
                            }
                        }
                    }
                }

                // Final flush: any leftover text becomes the tail sentence
                val remaining = sentenceParser.flush()
                if (remaining.isNotBlank()) {
                    fullText.append(remaining).append(" ")
                    ttsQueue.value = remaining.trim()
                }

                // Enqueue the full accumulated text if no sentences were emitted
                // (e.g. model returned text without sentence-ending punctuation)
                val currentQueue = ttsQueue.value
                if (currentQueue == null || currentQueue.isBlank()) {
                    val text = fullText.toString().trim()
                    if (text.isNotBlank()) {
                        ttsQueue.value = text
                    }
                }

                publishTotalLatency(startedAtForReply)

                if (started && !tts.muted) {
                    EventBus.publish(EventType.TTS_STARTED)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendMessage(
                    Message(sender = Sender.JARVIS, text = friendlyError(e), isError = true)
                )
            } finally {
                _uiState.update { it.copy(isLoading = false) }
                persistReplyIfAny(sessionId, replyId)
            }
        }
    }

    private var startedAtForReply: Long = 0L

    /**
     * Vision pipeline entry point: downscaled base64 image + prompt go through
     * the orchestrator's vision routing (backend-only credentials).
     */
    fun analyzeImage(base64Image: String, mimeType: String, prompt: String) {
        if (_uiState.value.isLoading) return
        val sessionId = _uiState.value.activeSessionId
        if (sessionId.isBlank()) return
        clearNotice()

        val caption = prompt.ifBlank { DEFAULT_VISION_PROMPT }
        val userMessage = Message(sender = Sender.USER, text = "[Image] $caption")
        _uiState.update { it.copy(messages = it.messages + userMessage, isLoading = true) }

        generationJob = viewModelScope.launch {
            try {
                val startedAt = System.currentTimeMillis()
                val result = orchestrator.analyzeImage(base64Image, mimeType, caption)
                val reply = Message(
                    sender = Sender.JARVIS,
                    text = result.text.ifBlank { "I could not extract anything from that image, sir." }
                )
                appendMessage(reply)
                _uiState.update {
                    it.copy(activeProvider = result.providerId)
                }
                if (!tts.muted) tts.speak(reply.text)
                withContext(Dispatchers.IO) {
                    runCatching {
                        db.appendMessage(sessionId, userMessage)
                        db.appendMessage(sessionId, reply)
                        db.touchSession(sessionId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendMessage(
                    Message(sender = Sender.JARVIS, text = friendlyError(e), isError = true)
                )
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun healthSnapshot(): com.jarvis.ai.health.HealthReport = healthReporter.report()

    fun addReminder(text: String, delayMs: Long): String =
        proactive.addReminder(text, System.currentTimeMillis() + delayMs)

    fun cancelReminder(id: String): Boolean = proactive.removeReminder(id)

    fun stopGeneration() {
        if (_uiState.value.isSpeaking) {
            EventBus.publish(EventType.TTS_INTERRUPTED)
        }
        generationJob?.cancel()
        generationJob = null
        tts.stop()
        _uiState.update { s ->
            s.copy(
                isLoading = false,
                messages = s.messages.filterNot { it.sender == Sender.JARVIS && it.text.isBlank() }
            )
        }
    }

    fun newSession() {
        stopGeneration()
        viewModelScope.launch(Dispatchers.IO) {
            val created = SessionInfo(title = DEFAULT_SESSION_TITLE)
            db.createSession(created)
            val sessions = db.sessions()
            _uiState.update {
                it.copy(
                    // New chat sessions deliberately do NOT re-greet (PresenceGate owns greetings).
                    messages = listOf(Message(sender = Sender.JARVIS, text = STANDBY_LINE)),
                    sessions = sessions,
                    activeSessionId = created.id,
                    latency = LatencyInfo(),
                    notice = null
                )
            }
        }
    }

    fun selectSession(id: String) {
        if (id == _uiState.value.activeSessionId) return
        stopGeneration()
        viewModelScope.launch(Dispatchers.IO) {
            val messages = db.messages(id)
            _uiState.update {
                it.copy(
                    messages = messages.ifEmpty { listOf(Message(sender = Sender.JARVIS, text = STANDBY_LINE)) },
                    activeSessionId = id,
                    latency = LatencyInfo(),
                    notice = null
                )
            }
        }
    }

    fun deleteSession(id: String) {
        if (id == _uiState.value.activeSessionId) stopGeneration()
        viewModelScope.launch(Dispatchers.IO) {
            db.deleteSession(id)
            val sessions = db.sessions()
            if (id == _uiState.value.activeSessionId) {
                val next = sessions.firstOrNull()
                if (next != null) {
                    _uiState.update {
                        it.copy(
                            sessions = sessions,
                            activeSessionId = next.id,
                            messages = db.messages(next.id)
                                .ifEmpty { listOf(Message(sender = Sender.JARVIS, text = STANDBY_LINE)) }
                        )
                    }
                } else {
                    val created = SessionInfo(title = DEFAULT_SESSION_TITLE)
                    db.createSession(created)
                    _uiState.update {
                        it.copy(
                            sessions = db.sessions(),
                            activeSessionId = created.id,
                            messages = listOf(Message(sender = Sender.JARVIS, text = STANDBY_LINE))
                        )
                    }
                }
            } else {
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
    }

    fun startVoiceInput(): Boolean {
        if (listening) return true
        if (!stt.isAvailable) {
            setNotice("Voice input is not available on this device, sir.")
            return false
        }
        if (!audioEngine.start()) {
            setNotice("Microphone access was denied, sir. Grant RECORD_AUDIO to proceed.")
            return false
        }
        listening = true
        _uiState.update { it.copy(isListening = true, notice = null) }
        val began = stt.start(
            onPartial = { partial -> showListeningDraft(partial) },
            onFinal = { final ->
                if (_uiState.value.isSpeaking) {
                    stopGeneration()
                }
                endListening()
                send(final)
            },
            onError = { code ->
                endListening()
                when (code) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        setNotice("I did not catch that, sir. Hold the microphone and speak.")
                    else -> setNotice("Voice uplink fault (code $code), sir.")
                }
            }
        )
        if (!began) endListening()
        return began
    }

    fun stopVoiceInput() {
        if (!listening) return
        stt.stop()
    }

    private fun endListening() {
        if (!listening) return
        listening = false
        audioEngine.stop()
        _uiState.update { it.copy(isListening = false) }
    }

    private fun showListeningDraft(partial: String) {
        _uiState.update { it.copy(notice = LISTENING_PREFIX + partial) }
    }

    fun toggleMuted() {
        tts.muted = !tts.muted
        if (tts.muted) tts.stop()
    }

    fun setTtsMuted(muted: Boolean) {
        tts.muted = muted
        if (muted) tts.stop()
    }

    fun voicePermissionDenied() {
        endListening()
        setNotice("Microphone access was denied, sir. Enable it to speak with me.")
    }

    private suspend fun persistReplyIfAny(sessionId: String, replyId: String) {
        val messages = _uiState.value.messages
        val reply = messages.firstOrNull { it.id == replyId }
            ?: messages.lastOrNull { it.sender == Sender.JARVIS }
            ?: return
        if (reply.text.isBlank() || reply.text == "…") return
        withContext(Dispatchers.IO) {
            runCatching {
                db.appendMessage(sessionId, reply)
                db.touchSession(sessionId)
            }
        }
    }

    /** Announces a proactive message once (notice bar + TTS). */
    private fun announce(message: String) {
        _uiState.update { it.copy(notice = message) }
        if (!tts.muted) {
            EventBus.publish(EventType.TTS_STARTED)
            tts.speak(message)
        }
    }

    private fun newReplyId(): String = java.util.UUID.randomUUID().toString()

    private fun greetingMessage(): Message {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val online = runtime.keys.registeredProviders().isNotEmpty()
        val text = com.jarvis.ai.presence.PresenceEngine.greetingText(hour, online)
            .let { "$GREETING_MARK$it" }
        return Message(sender = Sender.JARVIS, text = text)
    }

    private fun appendMessage(message: Message) {
        _uiState.update { it.copy(messages = it.messages + message) }
    }

    private fun publishTotalLatency(startedAt: Long) {
        val totalMs = (System.nanoTime() - startedAt) / 1_000_000L
        _uiState.update { it.copy(latency = it.latency.copy(totalMs = totalMs)) }
    }

    private fun updateReply(id: String, text: String) {
        _uiState.update { s ->
            s.copy(messages = s.messages.map { if (it.id == id) it.copy(text = text) else it })
        }
    }

    private fun setNotice(text: String) {
        _uiState.update { it.copy(notice = text) }
    }

    fun clearNotice() {
        if (_uiState.value.notice != null) {
            _uiState.update { it.copy(notice = null) }
        }
    }

    private fun friendlyError(e: Exception): String {
        val detail = com.jarvis.ai.provider.SecretRedactor.redact(e.message.orEmpty())
        return when {
            detail.contains("401") ->
                "Authentication rejected by the uplink, sir (401). Kindly verify provider credentials."
            detail.contains("404") ->
                "The endpoint was not found, sir (404). Please review the configuration."
            detail.contains("429") ->
                "The provider is rate limiting us, sir (429). Allow a moment and try again."
            detail.contains("Unable to resolve", true) ||
                detail.contains("ECONNREFUSED", true) ||
                detail.contains("Failed to connect", true) ||
                detail.contains("timeout", true) ->
                "I could not reach the network, sir. Please check connectivity and retry."
            else -> "An unexpected fault occurred, sir. ${detail.take(160)}"
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        stt.destroy()
        audioEngine.stop()
        tts.shutdown()
        super.onCleared()
    }

    companion object {
        private const val TITLE_MAX_LENGTH = 36
        private const val DEFAULT_SESSION_TITLE = "New session"
        private const val LISTENING_PREFIX = "Listening · "
        private const val PROACTIVE_POLL_MS = 60_000L
        private const val DEFAULT_VISION_PROMPT = "Describe what you see concisely."
        private const val GREETING_MARK = "\u200B" // zero-width: marks the greeting bubble
        private const val STANDBY_LINE = "At your service, sir."
        private const val NO_REPLY_SENTINEL = "…"

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val appContext = context.applicationContext
                val runtime = JarvisRuntime.get(appContext)
                JarvisViewModel(
                    runtime = runtime,
                    db = ChatDb(appContext),
                    tts = TtsEngine(appContext),
                    stt = SpeechRecognitionManager(appContext),
                    audioEngine = AudioLevelEngine(appContext),
                    memory = MemoryEngine(com.jarvis.ai.memory.SecureKvStore(appContext)),
                    orchestrator = runtime.masterOrchestrator,
                    awareness = SystemAwareness(appContext),
                    proactive = ProactiveEngine(),
                    presenceGate = PresenceGate()
                )
            }
        }
    }
}
