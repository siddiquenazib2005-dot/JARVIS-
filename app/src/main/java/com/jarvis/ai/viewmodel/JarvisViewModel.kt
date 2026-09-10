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
import com.jarvis.ai.core.VoiceSessionGate
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
import com.jarvis.ai.provider.Capability
import com.jarvis.ai.data.remote.AurixBackendClient
import com.jarvis.ai.data.remote.BackendPrefs
import com.jarvis.ai.provider.ModelCatalog
import com.jarvis.ai.provider.ModelOption
import com.jarvis.ai.provider.ProviderRegistry
import com.jarvis.ai.provider.RoutingPrefs
import com.jarvis.ai.tools.QuickCommandRouter
import com.jarvis.ai.service.AudioLevelEngine
import com.jarvis.ai.service.SpeechRecognitionManager
import com.jarvis.ai.service.SentenceParser
import com.jarvis.ai.service.TtsEngine
import com.jarvis.ai.service.WakeWordDetector
import com.jarvis.ai.system.SystemAwareness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    private val appContext: Context,
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
    // Tracks whether the user is currently HOLDING the mic. Guards the permission
    // flow: if the user releases while the system permission dialog is up, we must
    // not start a silent listening session that would yield "I did not catch that."
    private var micHeld = false
    /** True while a reply's sentences are being voiced via the TTS queue manager. */
    private var handsFreeVoicing = false
    /** Consecutive idle restarts (no speech detected) before hands-free backs off. */
    private var handsFreeIdleRestarts = 0
    /** Consecutive wake-word misses before the gate stops re-arming the mic. */
    private var wakeWordMissCount = 0

    private val _handsFreeActive = MutableStateFlow(false)
    /** Hands-free state exposed to the UI (mic button + settings switch). */
    val handsFreeActive: StateFlow<Boolean> = _handsFreeActive.asStateFlow()

    /**
     * Wake-word gate for hands-free mode. When enabled, an armed mic only
     * acts on transcripts that address the assistant ("jarvis, …"); when
     * disabled every final transcript is executed (legacy behaviour).
     * Purely in-memory like the other session toggles; defaults to OFF so
     * existing push-to-talk users see zero behaviour change.
     */
    private val _wakeWordEnabled = MutableStateFlow(false)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled.asStateFlow()

    private val PROVIDER_LABELS = mapOf(
        "GEMINI_API_KEY" to "Gemini",
        "OPENAI_API_KEY" to "OpenAI",
        "GROQ_API_KEY" to "Groq",
        "OPENROUTER_API_KEY" to "OpenRouter",
        "DEEPSEEK_API_KEY" to "DeepSeek"
    )

    /** Tool awaiting explicit confirmation ("yes" routes back through the gate). */
    private var pendingConfirmation: String? = null

    private val sentenceParser = SentenceParser()

    /** Offline device-command layer. Runs before any provider call. */
    private val quickCommands = QuickCommandRouter(appContext)
    private val imageGen = com.jarvis.ai.tools.ImageGenerator(appContext)

    private val _selectedModel = MutableStateFlow<ModelOption?>(null)

    /** Currently pinned model, or null while routing is automatic. */
    val selectedModel: StateFlow<ModelOption?> = _selectedModel.asStateFlow()

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
        RoutingPrefs.init(appContext)
        BackendPrefs.init(appContext)

        // Item 4: screen gestures run fire-and-forget on a background scope,
        // so their real outcome arrives late. When one genuinely fails after
        // retries, the automation layer pushes a follow-up line here instead
        // of letting the optimistic acknowledgement stand as a lie.
        com.jarvis.ai.automation.AutomationFeedback.observe { line ->
            appendAssistantLine(line)
        }
        _selectedModel.value = ModelCatalog.find(RoutingPrefs.pinnedProviderId, RoutingPrefs.pinnedModel)

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
                    backendOnline = runtime.backendOnline(),
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

        // Lifecycle-aware hands-free: release the mic when the app goes to the
        // background (privacy + battery), re-arm it when the user returns.
        viewModelScope.launch {
            runtime.appForeground.collect { foreground ->
                if (!foreground && listening) endListening()
                if (foreground && _handsFreeActive.value && !listening && !_uiState.value.isLoading) {
                    // Wake-word sessions re-arm silently — the mic is armed but
                    // waiting for the name, so there is nothing audible to miss.
                    rearmHandsFreeListening()
                }
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
                        // Consume the queued utterance so it is spoken exactly once.
                        // Without clearing, the finished utterance is re-played in an
                        // infinite loop every 200ms once TTS finishes.
                        ttsQueue.value = null
                        handsFreeVoicing = true
                        tts.speak(toSpeak)
                    }
                } else if (handsFreeVoicing && !tts.isSpeaking.value) {
                    // The reply has been fully voiced and the queue is drained.
                    handsFreeVoicing = false
                    rearmHandsFreeListening()
                }
                // Wait for TTS to finish; poll isSpeaking since we have no
                // direct callback beyond the existing StateFlow.
                delay(200L)
            }
        }
    }

    /** Backend base URL currently configured, or an empty string. */
    val backendUrl: String get() = BackendPrefs.baseUrl

    /** Saves the backend pointer and reports the live connection state. */
    fun saveBackend(url: String, token: String, onResult: (String) -> Unit) {
        BackendPrefs.save(url, token)
        if (!BackendPrefs.isEnabled) {
            onResult("Backend cleared — using on-device routing, sir.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val summary = runCatching {
                AurixBackendClient(BackendPrefs.baseUrl, BackendPrefs.appToken).describeConnection()
            }.getOrElse { "Backend unreachable — ${it.message.orEmpty()}" }
            onResult(summary)
        }
    }

    /**
     * Streams a reply from the user's own AURIX backend, which owns the API
     * keys, provider failover and long-term memory.
     */
    private fun streamFromBackend(sessionId: String, prompt: String) {
        clearNotice()
        val userMessage = Message(sender = Sender.USER, text = prompt)
        val history = _uiState.value.messages.filterNot { it.text.startsWith(GREETING_MARK) }
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isLoading = true,
                latency = LatencyInfo()
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            db.appendMessage(sessionId, userMessage)
            val firstTurn = _uiState.value.messages.count {
                it.sender == Sender.USER && !it.text.startsWith(GREETING_MARK)
            } <= 1
            if (firstTurn) {
                db.renameSession(sessionId, prompt.take(TITLE_MAX_LENGTH))
            } else {
                db.touchSession(sessionId)
            }
            refreshSessionsBlocking()
        }

        val pinned = _selectedModel.value
        generationJob = viewModelScope.launch {
            val replyId = newReplyId()
            val buffer = StringBuilder()
            var started = false
            try {
                val client = AurixBackendClient(BackendPrefs.baseUrl, BackendPrefs.appToken)
                client.streamChat(
                    history = history,
                    prompt = prompt,
                    sessionId = sessionId,
                    model = pinned?.modelId,
                    providerId = pinned?.providerId
                ).collect { delta ->
                    buffer.append(delta)
                    val text = buffer.toString()
                    if (!started) {
                        started = true
                        _uiState.update { s ->
                            s.copy(
                                messages = s.messages + Message(
                                    id = replyId,
                                    sender = Sender.AURIX,
                                    text = text
                                )
                            )
                        }
                    } else {
                        _uiState.update { s ->
                            s.copy(
                                messages = s.messages.map { m ->
                                    if (m.id == replyId) m.copy(text = text) else m
                                }
                            )
                        }
                    }
                }

                val finalText = buffer.toString().trim()
                if (finalText.isEmpty()) {
                    throw IllegalStateException("Backend returned no content.")
                }
                ttsQueue.value = finalText
                _uiState.update { it.copy(isLoading = false) }
                withContext(NonCancellable + Dispatchers.IO) {
                    db.appendMessage(
                        sessionId,
                        Message(id = replyId, sender = Sender.AURIX, text = finalText)
                    )
                    db.touchSession(sessionId)
                    refreshSessionsBlocking()
                }
            } catch (cancellation: CancellationException) {
                _uiState.update { it.copy(isLoading = false) }
                throw cancellation
            } catch (error: Throwable) {
                val text = error.message?.takeIf { it.isNotBlank() }
                    ?: "I could not reach the backend, sir."
                val failure = Message(sender = Sender.AURIX, text = text, isError = true)
                _uiState.update { it.copy(messages = it.messages + failure, isLoading = false) }
                withContext(Dispatchers.IO) { db.appendMessage(sessionId, failure) }
            }
        }
    }

    /**
     * Persists a user turn plus a locally generated reply without touching any
     * AI provider. Used by the offline quick-command layer.
     */
    private fun handleLocally(sessionId: String, input: String, reply: String) {
        clearNotice()
        val userMessage = Message(sender = Sender.USER, text = input)
        val replyMessage = Message(sender = Sender.AURIX, text = reply)
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage + replyMessage,
                isLoading = false,
                latency = LatencyInfo()
            )
        }
        ttsQueue.value = reply
        viewModelScope.launch(Dispatchers.IO) {
            db.appendMessage(sessionId, userMessage)
            db.appendMessage(sessionId, replyMessage)
            val firstTurn = _uiState.value.messages.count {
                it.sender == Sender.USER && !it.text.startsWith(GREETING_MARK)
            } <= 1
            if (firstTurn) {
                db.renameSession(sessionId, input.take(TITLE_MAX_LENGTH))
            } else {
                db.touchSession(sessionId)
            }
            refreshSessionsBlocking()
        }
    }

    /**
     * Item 9: text to image. Kept separate from [handleLocally] because the
     * provider call can take many seconds, so the user message and the typing
     * state must land immediately and the reply must arrive afterwards.
     */
    private fun generateImage(sessionId: String, input: String, prompt: String) {
        clearNotice()
        val userMessage = Message(sender = Sender.USER, text = input)
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isLoading = true,
                latency = LatencyInfo()
            )
        }
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching { imageGen.generate(prompt) }
                    .getOrElse { "Image generation failed, sir: " + (it.message ?: "unknown error") }
            }
            val replyMessage = Message(sender = Sender.AURIX, text = reply)
            _uiState.update {
                it.copy(messages = it.messages + replyMessage, isLoading = false)
            }
            ttsQueue.value = reply
            withContext(Dispatchers.IO) {
                db.appendMessage(sessionId, userMessage)
                db.appendMessage(sessionId, replyMessage)
                val firstTurn = _uiState.value.messages.count {
                    it.sender == Sender.USER && !it.text.startsWith(GREETING_MARK)
                } <= 1
                if (firstTurn) {
                    db.renameSession(sessionId, input.take(TITLE_MAX_LENGTH))
                } else {
                    db.touchSession(sessionId)
                }
                refreshSessionsBlocking()
            }
        }
    }

    /**
     * Appends a standalone AURIX line with no matching user turn, then
     * persists it. Used by late-arriving screen-automation reports (item 4).
     */
    private fun appendAssistantLine(text: String) {
        val sessionId = _uiState.value.activeSessionId
        val message = Message(sender = Sender.AURIX, text = text)
        _uiState.update { it.copy(messages = it.messages + message) }
        if (sessionId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            db.appendMessage(sessionId, message)
            db.touchSession(sessionId)
        }
    }

    fun send(rawInput: String) {
        pendingConfirmation = null
        _uiState.update { it.copy(hasPendingConfirmation = false) }
        dispatchToOrchestrator(rawInput, confirmed = false)
    }

    /** Routes an affirmative confirmation back through the PermissionGate. */
    fun confirmPendingAction() {
        val pending = pendingConfirmation ?: return
        pendingConfirmation = null
        _uiState.update { it.copy(hasPendingConfirmation = false) }
        dispatchToOrchestrator(pending, confirmed = true)
    }

    /** Pins a specific provider/model pair for every future request. */
    fun selectModel(option: ModelOption?) {
        if (option == null) {
            RoutingPrefs.clear()
            _selectedModel.value = null
            _uiState.update { it.copy(notice = "Routing set to Auto — AURIX will pick the best live provider.") }
            return
        }
        RoutingPrefs.pin(option.providerId, option.modelId)
        _selectedModel.value = option
        val configured = runtime.secureStore.contains(option.envVarName)
        _uiState.update {
            it.copy(
                notice = if (configured) {
                    "Model set to ${option.label} (${option.providerLabel})."
                } else {
                    "${option.label} selected — add your ${option.providerLabel} key in Settings to use it."
                }
            )
        }
    }

    /** Every model the picker can offer. */
    val availableModels: List<ModelOption> get() = ModelCatalog.options

    /** True when a key exists for the provider backing this model. */
    fun isModelReady(option: ModelOption): Boolean =
        runCatching { runtime.secureStore.contains(option.envVarName) }.getOrDefault(false)

    private fun dispatchToOrchestrator(text: String, confirmed: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isLoading) return
        val sessionId = _uiState.value.activeSessionId
        if (sessionId.isBlank()) return

        // Offline fast path: device commands must work with zero API keys.
        //
        // The router now reports its own failures as text, so no runCatching
        // here: swallowing the exception is what made feature taps look dead.
        // A blank/null result means "not a device command" and legitimately
        // falls through to the AI path below.
        if (!confirmed) {
            // Item 9: image requests are network work, so they get their own
            // async path instead of blocking the main thread inside the router.
            quickCommands.imagePrompt(trimmed)?.let { prompt ->
                generateImage(sessionId, trimmed, prompt)
                return
            }
            val local = quickCommands.handle(trimmed)
            if (!local.isNullOrBlank()) {
                handleLocally(sessionId, trimmed, local)
                return
            }
        }

        // Backend path: the user's own server owns keys, routing and memory.
        if (BackendPrefs.isEnabled) {
            streamFromBackend(sessionId, trimmed)
            return
        }

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
                                            sender = Sender.AURIX,
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
                            _uiState.update { it.copy(hasPendingConfirmation = true) }
                            setNotice("${update.message} (Tap SEND to confirm.)")
                        }

                        is OrchestratorUpdate.Completed -> {
                            val remaining = sentenceParser.flush()
                            if (remaining.isNotBlank()) {
                                fullText.append(remaining).append(" ")
                                ttsQueue.value = remaining.trim()
                                // Persist the flushed tail into the live bubble.
                                // Without this the final sentence is spoken but the
                                // displayed/persisted reply ends early (truncation).
                                updateReply(replyId, fullText.toString().trim())
                            }
                            // Local/tool intents that emitted a whole reply as a single
                            // Delta may never have created the bubble yet via `started`;
                            // ensure the bubble reflects the full text in that case too.
                            if (!started && fullText.isNotBlank()) {
                                started = true
                                _uiState.update { s ->
                                    s.copy(
                                        messages = s.messages + Message(
                                            id = replyId,
                                            sender = Sender.AURIX,
                                            text = fullText.toString().trim()
                                        )
                                    )
                                }
                            }
                            // backendOnline must reflect the REAL success of this attempt.
                            // OR-ing it leaves the flag stuck true forever once any
                            // request succeeds, even while offline. Use the actual outcome.
                            _uiState.update { s ->
                                s.copy(
                                    activeProvider = update.provider,
                                    backendOnline = update.success
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
                    // Write the flushed tail into the live bubble.
                    updateReply(replyId, fullText.toString().trim())
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

                // Reconcile the bubble: if no sentence boundary was ever produced
                // (started stayed false) the reply was never shown — create it now
                // so unpadded model output is not silently dropped from the UI.
                if (!started && fullText.isNotBlank()) {
                    started = true
                    _uiState.update { s ->
                        s.copy(
                            messages = s.messages + Message(
                                id = replyId,
                                sender = Sender.AURIX,
                                text = fullText.toString().trim()
                            )
                        )
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
                    Message(sender = Sender.AURIX, text = friendlyError(e), isError = true)
                )
            } finally {
                _uiState.update { it.copy(isLoading = false) }
                persistReplyIfAny(sessionId, replyId)
                // Hands-free: the reply is fully generated (or cancelled); once any
                // remaining queued sentences have been voiced, the mic re-arms.
                markHandsFreeReplyDelivered()
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
                    sender = Sender.AURIX,
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
                    Message(sender = Sender.AURIX, text = friendlyError(e), isError = true)
                )
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun healthSnapshot(): com.jarvis.ai.health.HealthReport = healthReporter.report()

    /** Persists a provider API key into the AndroidKeyStore-encrypted secure store. */
    fun saveProviderKey(envName: String, value: String) {
        val key = value.trim()
        if (key.isEmpty()) return
        runtime.secureStore.put(envName, key)
        // Force provider manager to re-discover configuration so routing picks it up
        // without requiring an app restart.
        runtime.providerManager.bootstrapFromSecrets()
    }

    /** Returns the providers that currently have a key stored (configured=true). */
    fun configuredProviders(): List<String> =
        runtime.providerManager.configuredProviderIds()

    /**
     * Saves a single provider key and re-discovers configuration so routing
     * picks it up immediately. Returns a user-facing confirmation string.
     */
    fun saveKeyForField(envName: String, value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "••••••••") {
            return "Key cannot be blank, sir."
        }
        saveProviderKey(envName, trimmed)
        runtime.providerManager.bootstrapFromSecrets()
        val label = PROVIDER_LABELS[envName] ?: envName
        return "$label key saved ✓"
    }

    fun hasKey(envName: String): Boolean =
        runtime.secrets.get("$envName#1").orEmpty().isNotBlank()

    /** Presents the typed keys, stores them, and live-probes all configured LLM providers. */
    fun testAndSaveKeys(
        typed: Map<String, String>,
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch {
            // Persist non-blank typed keys first.
            typed.forEach { (envName, value) ->
                if (value.isNotBlank() && value != "••••••••") {
                    saveProviderKey(envName, value)
                }
            }
            runtime.providerManager.bootstrapFromSecrets()
            val results = runCatching {
                runtime.providerManager.healthCheck(Capability.CHAT)
            }.getOrDefault(emptyList())
            val lines = results.filter { it.providerId != "huggingface" }
                .sortedBy { it.providerId }
                .map { p ->
                    val keyState = if (runtime.secrets.get("${ProviderRegistry.byId(p.providerId)?.envVarName ?: ""}#1").orEmpty().isNotBlank()) "key" else "no-key"
                    "${p.providerId}: ${if (p.reachable) "REACHABLE ✓" else p.stateHint} (${keyState})"
                }
            onResult(lines.joinToString("\n"))
        }
    }

    fun addReminder(text: String, delayMs: Long): String =
        proactive.addReminder(text, System.currentTimeMillis() + delayMs)

    fun cancelReminder(id: String): Boolean = proactive.removeReminder(id)

    fun stopGeneration() {
        if (_uiState.value.isSpeaking) {
            EventBus.publish(EventType.TTS_INTERRUPTED)
        }
        generationJob?.cancel()
        generationJob = null
        // Drop any half-buffered sentence so a cancelled reply does not leak
        // text into the next request's first chunk.
        sentenceParser.clear()
        handsFreeVoicing = false
        wakeWordMissCount = 0
        tts.stop()
        _uiState.update { s ->
            s.copy(
                isLoading = false,
                messages = s.messages.filterNot { it.sender == Sender.AURIX && it.text.isBlank() }
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
                    messages = listOf(Message(sender = Sender.AURIX, text = STANDBY_LINE)),
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
                    messages = messages.ifEmpty { listOf(Message(sender = Sender.AURIX, text = STANDBY_LINE)) },
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
                                .ifEmpty { listOf(Message(sender = Sender.AURIX, text = STANDBY_LINE)) }
                        )
                    }
                } else {
                    val created = SessionInfo(title = DEFAULT_SESSION_TITLE)
                    db.createSession(created)
                    _uiState.update {
                        it.copy(
                            sessions = db.sessions(),
                            activeSessionId = created.id,
                            messages = listOf(Message(sender = Sender.AURIX, text = STANDBY_LINE))
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
        // When reached through the permission flow, the user may have already
        // released the mic while the system dialog was covering the screen. In that
        // case do NOT open a session that will only hear silence (yielding the
        // false "I did not catch that, sir."). The mic-press/release intent is
        // tracked via recordMicPress()/recordMicRelease().
        if (!micHeld) return false
        if (!stt.isAvailable) {
            setNotice("Voice input is not available on this device, sir.")
            return false
        }
        if (!runtime.hasRecordAudioPermission()) {
            setNotice("Microphone access was denied, sir. Grant RECORD_AUDIO to proceed.")
            return false
        }
        return beginRecognition()
    }

    /**
     * Opens a recognition session. Callers must have validated availability,
     * permission and mic intent — this owns session activation + STT wiring.
     */
    private fun beginRecognition(): Boolean {
        // Let SpeechRecognizer own the microphone exclusively: no second
        // AudioRecord (orb level already pulses via the UI). VoiceSessionGate
        // signals other listeners to back off. Two competing listeners would
        // make the recognizer hear silence and time out.
        VoiceSessionGate.active = true
        listening = true
        _uiState.update { it.copy(isListening = true, notice = null) }
        val began = stt.start(
            onPartial = { partial -> showListeningDraft(partial) },
            onFinal = { final -> handleFinalTranscript(final) },
            onError = { code ->
                endListening()
                when (code) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Idle session. Stay armed: hands-free keeps listening
                        // through silence — the user asked for persistent
                        // hands-free (BUG: "re-arms after each reply" and
                        // disengages after idle gaps). No backoff, no disengage.
                        if (_handsFreeActive.value) {
                            rearmHandsFreeListening()
                        } else {
                            setNotice("I did not catch that, sir. Tap the microphone and speak.")
                        }
                    }
                    else -> {
                        // Fatal recognizer faults end hands-free mode; push-to-talk
                        // just reports the failure.
                        if (_handsFreeActive.value) _handsFreeActive.value = false
                        setNotice("Voice uplink fault (code $code), sir.")
                    }
                }
            }
        )
        if (!began) endListening()
        return began
    }

    /**
     * Wake-word mode: armed mic only acts on transcripts that address the
     * assistant. Idle sessions (pure silence) still re-arm quietly; noise
     * without the name re-arms once, then the mode backs off so an empty
     * room cannot drain the battery.
     */
    fun setWakeWordEnabled(enabled: Boolean) {
        _wakeWordEnabled.value = enabled
    }

    /**
     * Shared sink for every finalized recognition result (push-to-talk,
     * hands-free and wake-word sessions all land here).
     */
    private fun handleFinalTranscript(final: String) {
        if (_uiState.value.isSpeaking) {
            stopGeneration()
        }
        endListening()
        val transcript = final.trim()
        when {
            // Silence in hands-free mode: quietly re-arm instead of nagging.
            transcript.isEmpty() && _handsFreeActive.value -> rearmHandsFreeListening()
            // If a previous generation is still in flight, sending now would be
            // silently dropped by dispatchToOrchestrator's isLoading guard. Give
            // the user explicit feedback instead of losing their spoken input.
            transcript.isNotEmpty() && _uiState.value.isLoading ->
                setNotice("Finishing that reply, sir. Say it again once it's done.")
            // Wake-word gate: in gated hands-free sessions a transcript without
            // the name is ignored (re-arm and KEEP listening — persistent
            // hands-free must never disengage itself on misses).
            transcript.isNotEmpty() &&
                _handsFreeActive.value &&
                _wakeWordEnabled.value &&
                !WakeWordDetector.containsWakeWord(transcript) -> {
                // Persistent hands-free: a miss never disengages the session.
                rearmHandsFreeListening()
            }
            else -> {
                handsFreeIdleRestarts = 0
                wakeWordMissCount = 0
                // Strip the address prefix ("jarvis, what's the time" → "what's
                // the time") so the orchestrator never sees its own name.
                val command = if (_handsFreeActive.value) {
                    // Live hands-free session: a bare "jarvis" is the user
                    // checking the wake link — re-arm quietly instead of
                    // dropping the session.
                    WakeWordDetector.stripWakeWord(transcript) ?: run {
                        rearmHandsFreeListening()
                        return
                    }
                } else {
                    // Push-to-talk: never lose an utterance; keep the raw
                    // transcript when no wake word was spoken.
                    WakeWordDetector.stripWakeWord(transcript) ?: transcript
                }
                send(command)
            }
        }
    }

    /** Enables/disables hands-free (continuous conversation) mode. */
    fun toggleHandsFreeMode() {
        if (_handsFreeActive.value) {
            _handsFreeActive.value = false
            handsFreeIdleRestarts = 0
            if (listening) stopVoiceInput()
            setNotice("Voice mode off.")
        } else {
            if (!runtime.hasRecordAudioPermission()) {
                setNotice("Microphone access was denied, sir. Grant RECORD_AUDIO to proceed.")
                return
            }
            if (!stt.isAvailable) {
                setNotice("Voice input is not available on this device, sir.")
                return
            }
            if (_uiState.value.isLoading) {
                setNotice("One moment, sir — still finishing the previous request.")
                return
            }
            _handsFreeActive.value = true
            handsFreeIdleRestarts = 0
            micHeld = true
            beginRecognition()
        }
    }

    /**
     * Re-arms the microphone after a hands-free reply has been fully delivered.
     * Bail-outs (backgrounded app, revoked permission, missing recognizer)
     * disable the mode instead of leaving it silently armed.
     */
    private fun rearmHandsFreeListening() {
        if (!_handsFreeActive.value) return
        // Foreground exit only PAUSES the mic — the appForeground collector re-arms
        // on return, so the mode must survive a background trip. Only a revoked
        // permission or a missing recognizer disables the mode outright.
        if (!runtime.hasRecordAudioPermission() || !stt.isAvailable) {
            _handsFreeActive.value = false
            return
        }
        // If the pipeline has drifted (mic ended but mode says armed), restore it.
        // Idle restart caps no longer apply to wake-word silent sessions: a quiet
        // room must not disengage a mode the user explicitly armed.
        if (!listening && !_uiState.value.isLoading) {
            handsFreeIdleRestarts = 0
            micHeld = true
            beginRecognition()
            return
        }
        viewModelScope.launch {
            delay(HANDS_FREE_REARM_DELAY_MS)
            if (!_handsFreeActive.value) return@launch
            if (listening || _uiState.value.isLoading) return@launch
            micHeld = true
            beginRecognition()
        }
    }

    /** Called when a generation fully ends (reply delivered or cancelled). */
    private fun markHandsFreeReplyDelivered() {
        handsFreeVoicing = false
        rearmHandsFreeListening()
    }

    fun stopVoiceInput() {
        // Track release so the permission callback does not start a silent session.
        micHeld = false
        if (!listening) return
        // Signal the recognizer to finalize (delivers onFinal with the transcript)...
        stt.stop()
        // ...and IMMEDIATELY clear the active session + UI so the pulsing mic and
        // "Listening, sir…" state do not stay stuck until an async onFinal/onError
        // arrives (or is never delivered on flaky vendor services). A subsequent
        // press of the mic can then start a fresh session right away.
        endListening()
    }

    /** Records that the user is pressing the mic (guards the permission flow). */
    fun recordMicPress() {
        micHeld = true
    }

    private fun endListening() {
        if (!listening) return
        listening = false
        VoiceSessionGate.active = false
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
        // Only persist into the session that is still active. If the user switched
        // sessions while a generation was being cancelled, the state's messages now
        // belong to a different session and must not leak into this one.
        if (_uiState.value.activeSessionId != sessionId) return
        val messages = _uiState.value.messages
        // Find ONLY the reply we generated for this request. The prior fallback to
        // lastOrNull { it.sender == Sender.AURIX } could grab an unrelated bubble
        // after a session switch and write it into the wrong session.
        val reply = messages.firstOrNull { it.id == replyId } ?: return
        if (reply.text.isBlank() || reply.text == "…") return
        // NonCancellable: this runs in a `finally` block, and a cancelled or
        // completing coroutine would otherwise skip the write entirely, which
        // silently dropped replies from chat history on the next app launch.
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                db.appendMessage(sessionId, reply)
                db.touchSession(sessionId)
            }
            refreshSessionsBlocking()
        }
    }

    /** Re-reads the session list so drawer titles and ordering stay current. */
    private fun refreshSessionsBlocking() {
        runCatching {
            val sessions = db.sessions()
            _uiState.update { it.copy(sessions = sessions) }
        }
    }

    /** Announces a proactive message once (notice bar + TTS). */
    private fun announce(message: String) {
        _uiState.update { it.copy(notice = message) }
        if (!tts.muted) {
            EventBus.publish(EventType.TTS_STARTED)
            if (listening) endListening() // never let the mic hear AURIX himself
            // Route through the sentence queue so hands-free re-arms once the
            // announcement finishes speaking (exactly-once consumption).
            handsFreeVoicing = true
            ttsQueue.value = message
        }
    }

    private fun newReplyId(): String = java.util.UUID.randomUUID().toString()

    private fun greetingMessage(): Message {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val online = runtime.keys.registeredProviders().isNotEmpty()
        val text = com.jarvis.ai.presence.PresenceEngine.greetingText(hour, online)
            .let { "$GREETING_MARK$it" }
        return Message(sender = Sender.AURIX, text = text)
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
            detail.contains("vision:", true) ->
                "Vision request could not be completed, sir. ${detail.removePrefix("vision:").trim().take(160)}"
            else -> "An unexpected fault occurred, sir. ${detail.take(160)}"
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        VoiceSessionGate.active = false
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
        private const val HANDS_FREE_REARM_DELAY_MS = 1_200L
        private const val HANDS_FREE_MAX_IDLE_RESTARTS = 3
        /** Wake-word misses tolerated before hands-free backs off. */
        private const val WAKE_WORD_MAX_MISSES = 2
        private const val DEFAULT_VISION_PROMPT = "Describe what you see concisely."
        private const val GREETING_MARK = "\u200B" // zero-width: marks the greeting bubble
        private const val STANDBY_LINE = "At your service, sir."
        private const val NO_REPLY_SENTINEL = "…"

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val appContext = context.applicationContext
                val runtime = JarvisRuntime.get(appContext)
                JarvisViewModel(
                    appContext = appContext,
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
