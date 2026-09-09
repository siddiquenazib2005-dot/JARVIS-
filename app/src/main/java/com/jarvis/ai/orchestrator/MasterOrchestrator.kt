package com.jarvis.ai.orchestrator

import android.content.Context
import android.util.Log
import com.jarvis.ai.agent.AgentCore
import com.jarvis.ai.core.EventBus
import com.jarvis.ai.core.EventType
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.data.repository.JarvisRepository
import com.jarvis.ai.intelligence.TaskRouter
import com.jarvis.ai.memory.vector.MemoryType
import com.jarvis.ai.memory.vector.MemoryWriteResult
import com.jarvis.ai.memory.vector.VectorMemoryManager
import com.jarvis.ai.memory.vector.WriteStatus
import com.jarvis.ai.planning.AgentLimits
import com.jarvis.ai.planning.AgentLoop
import com.jarvis.ai.planning.StepOutcome
import com.jarvis.ai.provider.Capability
import com.jarvis.ai.provider.RouteChunk
import com.jarvis.ai.provider.LlmRouteRequest
import com.jarvis.ai.provider.ProviderRouter
import com.jarvis.ai.provider.SecretsSource
import com.jarvis.ai.provider.SecretRedactor
import com.jarvis.ai.security.AuditLog
import com.jarvis.ai.security.PermissionGate
import com.jarvis.ai.system.SystemAwareness
import com.jarvis.ai.vision.ScreenshotCapture
import com.jarvis.ai.vision.VisionModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Streamed orchestrator output consumed by the UI layer. */
sealed class OrchestratorUpdate {
    /** Incremental reply text (deltas may be whole replies for local intents). */
    data class Delta(val text: String) : OrchestratorUpdate()

    /** A gated tool needs explicit user confirmation before it can run. */
    data class Confirmation(val toolName: String, val message: String) : OrchestratorUpdate()

    /** Terminal event for the request. */
    data class Completed(
        val success: Boolean,
        val provider: String,
        val latencyMs: Long,
        val memoryStored: Boolean = false
    ) : OrchestratorUpdate()
}

/**
 * Central orchestration pipeline — the ONLY path from UI to intelligence:
 *
 *   Input → Intent → Context/Memory → Model routing → Tools/Agent
 *         → Verification → Response → TTS/UI
 *
 * The UI never calls OpenAI/OpenRouter/Groq/Gemini/etc. directly; all model
 * traffic flows through [JarvisRuntime.providerRouter] (multi-provider,
 * multi-key, health-aware, backend-only credentials).
 */
class MasterOrchestrator(
    private val providerRouter: ProviderRouter,
    internal val vectorMemory: VectorMemoryManager,
    private val appContext: Context,
    /** Null only in JVM tests; device builds always bind a real executor. */
    private val toolExecutor: ToolExecutor? = null,
    private val taskRouter: TaskRouter? = null,
    /**
     * var (not val): AgentCore and MasterOrchestrator have a circular
     * dependency, so JarvisRuntime constructs this with agentCore=null and
     * wires the real instance in right after — see
     * JarvisRuntime.initializeOrchestrator(). Must stay mutable or
     * DEVICE_AUTOMATION requests silently no-op forever.
     */
    var agentCore: AgentCore? = null
) {

    private val agentLoop = AgentLoop(AgentLimits())

    /** Fast-path launcher for explicit "open <app>" system-control commands. */
    private val systemToolHandler = SystemToolHandler(appContext)

    /** Secrets seam for the vision layer (backend-only credentials). */
    internal var visionSecrets: SecretsSource? = null

    fun processRequest(
        input: String,
        history: List<Message> = emptyList(),
        userConfirmedThisTurn: Boolean = false
    ): Flow<OrchestratorUpdate> = flow {
        val startedAt = System.currentTimeMillis()
        EventBus.publish(EventType.USER_INPUT, input.take(120))
        var memoryStored = false

        try {
            // NOTE: No ConnectivityViewModel-style pre-check here — the old
            // snapshot().online probe reported false while connections were
            // perfectly usable (transient activeNetwork/caps nulls), which made
            // every request die with "check your Internet connection" even when
            // the user had just configured a valid API key. The provider router
            // performs the real network attempt and classifies genuine failures,
            // so availability is decided there — not by this heuristic.

            // Fast-path system control: explicit "open <app>" commands launch
            // instantly without an LLM round-trip or tool-classification.
            systemToolHandler.handle(input)?.let { launch ->
                if (launch.success) {
                    // Parity with the gated tool pipeline: app-launch is LOW_RISK
                    // (no confirmation) but must still be recorded in the audit log.
                    AuditLog.record(
                        "open_app",
                        PermissionGate.decide("open_app", userConfirmedThisTurn),
                        input
                    )
                    EventBus.publish(EventType.DEVICE_ACTION, "open_app")
                }
                emit(OrchestratorUpdate.Delta(launch.reply))
                emit(completed(launch.success, if (launch.success) "fast-path" else "system", startedAt))
                return@flow
            }

            val classification = IntentClassifier.classifyIntent(input)

            when (classification.intent) {
                // CALCULATION / TIME_DATE intentionally have NO local offline path:
                // under fully-online policy every answer comes from the remote uplink.

                Intent.MEMORY -> {
                    EventBus.publish(EventType.INTENT_DETECTED, Intent.MEMORY)
                    memoryStored = handleMemory(classification, input, userConfirmedThisTurn)
                }

                // SEND_SMS / SEND_WHATSAPP reuse the same single-step tool-execution
                // path as SYSTEM_COMMAND: TaskPlanner.createPlan() already maps these
                // two intents to the "send_sms"/"send_whatsapp" tools (see
                // TaskPlanner.determineToolForIntent), and classification.parameters
                // already carries {"contact":..., "message":...} from
                // IntentClassifier.parseMessagingRequest(). Without this case these
                // intents fell through to the `else` (CHAT) branch and were never
                // executed — only replied to conversationally.
                Intent.SYSTEM_COMMAND, Intent.SEND_SMS, Intent.SEND_WHATSAPP, Intent.MAKE_CALL -> {
                    EventBus.publish(EventType.INTENT_DETECTED, classification.intent)
                    handleSystemCommand(classification, input, userConfirmedThisTurn).forEach { update ->
                        emit(update)
                    }
                }

                Intent.DEVICE_AUTOMATION -> {
                    EventBus.publish(EventType.INTENT_DETECTED, Intent.DEVICE_AUTOMATION)
                    handleDeviceAutomation(input, userConfirmedThisTurn).forEach { update ->
                        emit(update)
                    }
                }

                Intent.VISION_ANALYSIS -> {
                    EventBus.publish(EventType.INTENT_DETECTED, Intent.VISION_ANALYSIS)
                    handleVisionAnalysis(input).forEach { update ->
                        emit(update)
                    }
                }

                else -> {
                    EventBus.publish(EventType.INTENT_DETECTED, Intent.CHAT)
                    // Context injection: recalled memories become part of the system prompt.
                    val memoryContext = try {
                        vectorMemory.contextBlock(input)
                    } catch (e: Exception) {
                        Log.w(TAG, "contextBlock failed: ${e.message}")
                        ""
                    }
                    // Persist notable user statements for future recall.
                    if (looksLikeDurableFact(input)
                        && rememberAsFact(input, "conversation")?.status == WriteStatus.STORED
                    ) {
                        memoryStored = true
                        EventBus.publish(EventType.MEMORY_UPDATED, "stored")
                    }

                    val systemPrompt = buildString {
                        append(JarvisRepository.SYSTEM_PROMPT)
                        if (memoryContext.isNotBlank()) {
                            append("\n\n")
                            append(memoryContext)
                        }
                    }
                    // The ViewModel feeds history WITHOUT the current input (it passes
                    // history.dropLast(1)) and providers/OfflineProvider derive the active
                    // user prompt from history.lastUserText(). Append the current input as
                    // the trailing USER message so typed text is actually seen — otherwise
                    // every message reaches the offline/degraded fallback as empty input
                    // and is answered with "I did not quite catch that, sir."
                    val withCurrentInput = history + Message(sender = Sender.USER, text = input)
                    val trimmedHistory = trimToBudget(withCurrentInput)

                    providerRouter
                        .routeText(
                            LlmRouteRequest(
                                capability = Capability.CHAT,
                                history = trimmedHistory,
                                systemPrompt = systemPrompt,
                                stream = true
                            )
                        )
                        .collect { chunk ->
                            when (chunk) {
                                is RouteChunk.Delta ->
                                    emit(OrchestratorUpdate.Delta(chunk.text))

                                is RouteChunk.Finished -> {
                                    EventBus.publish(EventType.MODEL_SELECTED, chunk.report.metadata.provider)
                                    emit(
                                        OrchestratorUpdate.Completed(
                                            success = chunk.report.success,
                                            provider = chunk.report.metadata.provider,
                                            latencyMs = chunk.report.metadata.latencyMs,
                                            memoryStored = memoryStored
                                        )
                                    )
                                }
                            }
                        }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(
                OrchestratorUpdate.Delta(
                    "An unexpected fault occurred, sir. ${SecretRedactor.redact(e.message ?: "")}"
                )
            )
            emit(completed(false, "error", startedAt))
        }
    }

    /**
     * Vision path: image + optional caption go straight to the best healthy
     * vision-capable backend through [VisionAnalyzer]; the result streams back
     * like any other reply. Never exposed to direct provider calls from the UI.
     */
    suspend fun analyzeImage(base64Image: String, mimeType: String, prompt: String): VisionAnalyzer.VisionResult {
        val secrets = visionSecrets
            ?: throw IllegalStateException("Vision secrets not injected; JarvisRuntime must set visionSecrets before image analysis.")
        return VisionAnalyzer(providerRouter, secrets).analyze(base64Image, mimeType, prompt)
    }

    /** Emits memory-operation replies; returns true when something was stored. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<OrchestratorUpdate>.handleMemory(
        classification: IntentClassifier.Classification,
        rawInput: String,
        userConfirmedThisTurn: Boolean
    ): Boolean = when (classification.parameters["operation"] as? String) {
        "store" -> {
            val fact = rawInput.removePrefix("remember").removePrefix("Remember").trim()
                .ifBlank { rawInput }
            val write = rememberAsFact(fact, "explicit-request")
            val reply = when (write?.status) {
                WriteStatus.STORED -> {
                    EventBus.publish(EventType.MEMORY_UPDATED, "stored")
                    "Committed to long-term memory, sir."
                }
                WriteStatus.DUPLICATE ->
                    "Already in my memory banks, sir."
                else -> "I could not store that memory, sir."
            }
            emit(OrchestratorUpdate.Delta(reply))
            emit(
                OrchestratorUpdate.Completed(
                    success = write?.status == WriteStatus.STORED,
                    provider = "vector-memory",
                    latencyMs = 0L,
                    memoryStored = write?.status == WriteStatus.STORED
                )
            )
            write?.status == WriteStatus.STORED
        }

        "recall", "query" -> {
            val hits = try {
                vectorMemory.recall(rawInput)
            } catch (e: Exception) {
                Log.w(TAG, "memory recall failed: ${e.message}")
                emptyList()
            }
            val reply = if (hits.isEmpty()) {
                "I hold no relevant memories on that subject yet, sir."
            } else {
                "From my memory banks:\n" + hits.joinToString("\n") {
                    "- ${it.record.content.take(200)}"
                }
            }
            emit(OrchestratorUpdate.Delta(reply))
            emit(completed(hits.isNotEmpty(), "vector-memory", System.currentTimeMillis()))
            hits.isNotEmpty()
        }

        else -> {
            val decision = PermissionGate.decide("memory_wipe_all", userConfirmedThisTurn)
            if (decision.allowedWithoutConfirmation) {
                val wiped = try {
                    vectorMemory.wipeAll()
                } catch (e: Exception) {
                    Log.w(TAG, "memory wipe failed: ${e.message}")
                    -1
                }
                emit(
                    OrchestratorUpdate.Delta(
                        if (wiped >= 0) "All memories erased, sir."
                        else "Memory wipe failed, sir."
                    )
                )
            } else {
                emit(OrchestratorUpdate.Confirmation("memory_wipe_all", decision.message))
            }
            false
        }
    }

    private suspend fun handleSystemCommand(
        classification: IntentClassifier.Classification,
        rawInput: String,
        userConfirmed: Boolean
    ): List<OrchestratorUpdate> {
        val updates = mutableListOf<OrchestratorUpdate>()
        val plan = TaskPlanner().createPlan(rawInput, classification)

        if (toolExecutor == null) {
            updates += OrchestratorUpdate.Delta("Tool subsystem is not available right now, sir.")
            updates += completed(false, "tools", System.currentTimeMillis())
            return updates
        }

        if (plan == null || plan.steps.isEmpty()) {
            updates += OrchestratorUpdate.Delta("I'm not sure how to execute that command, sir.")
            updates += completed(false, "tools", System.currentTimeMillis())
            return updates
        }

        if (plan.steps.size == 1) {
            val step = plan.steps.first()
            val result = toolExecutor.executeTool(step.toolName, step.parameters, userConfirmed)
            when (result) {
                is ToolResult.ConfirmationRequired ->
                    updates += OrchestratorUpdate.Confirmation(step.toolName, result.message)
                is ToolResult.Failure -> {
                    updates += OrchestratorUpdate.Delta(ResultVerifier.getUserMessage(result))
                    updates += completed(false, "tools", System.currentTimeMillis())
                }
                is ToolResult.Success -> {
                    EventBus.publish(EventType.DEVICE_ACTION, step.toolName)
                    updates += OrchestratorUpdate.Delta(ResultVerifier.getUserMessage(result))
                    updates += completed(true, "tools", System.currentTimeMillis())
                }
            }
            return updates
        }

        // Multi-step: controlled agent loop with bounded retries and hard limits.
        val report = agentLoop.run(
            goal = rawInput,
            steps = plan.steps,
            executor = { step, _ ->
                when (val r = toolExecutor.executeTool(step.toolName, step.parameters)) {
                    is ToolResult.Success -> StepOutcome.Success(r.message)
                    is ToolResult.ConfirmationRequired ->
                        StepOutcome.Failure(r.message, recoverable = false)
                    is ToolResult.Failure -> StepOutcome.Failure(r.error, recoverable = r.recoverable)
                }
            },
            verifier = { _, outcome -> outcome.output.isNotBlank() }
        )
        val summary = if (report.success) {
            "Task sequence complete, sir. " + report.completedSteps.joinToString("; ")
        } else {
            "I had to stop, sir. ${report.stoppedReason ?: "Step failed."}"
        }
        EventBus.publish(EventType.TASK_COMPLETED, "${report.completedSteps.size}/${plan.steps.size}")
        updates += OrchestratorUpdate.Delta(summary)
        updates += completed(report.success, "agent-loop", System.currentTimeMillis())
        return updates
    }

    private suspend fun handleDeviceAutomation(
        rawInput: String,
        userConfirmed: Boolean
    ): List<OrchestratorUpdate> = withContext(Dispatchers.IO) {
        val updates = mutableListOf<OrchestratorUpdate>()

        // Gate automation through the same permission policy as tools: it can
        // alter the device, so it must not bypass confirmation like it used to.
        val gate = PermissionGate.decide(DEVICE_AUTOMATION_TOOL, userConfirmed)
        AuditLog.record(DEVICE_AUTOMATION_TOOL, gate, rawInput)
        when {
            gate.denied -> {
                updates += OrchestratorUpdate.Delta(gate.message)
                updates += completed(false, "agent-core", System.currentTimeMillis())
                return@withContext updates
            }
            gate.requiresConfirmation -> {
                updates += OrchestratorUpdate.Confirmation(DEVICE_AUTOMATION_TOOL, gate.message)
                return@withContext updates
            }
        }

        val core = agentCore
        if (core == null) {
            updates += OrchestratorUpdate.Delta("Device automation is not available right now, sir.")
            updates += completed(false, "agent-core", System.currentTimeMillis())
            return@withContext updates
        }

        core.executeTask(rawInput)

        var lastState: AgentCore.AgentState? = null

        // Bounded wait for a terminal state; a hot MutableStateFlow.collect never
        // completes on its own, so without a timeout a stuck agent is awaited
        // forever. first{} cancels the flow the moment a terminal state arrives.
        val completed = withTimeoutOrNull(AUTOMATION_TIMEOUT_MS) {
            core.getStateFlow().first { state ->
                if (state != lastState) {
                    when (state) {
                        is AgentCore.AgentState.Running ->
                            updates += OrchestratorUpdate.Delta(state.step)

                        is AgentCore.AgentState.Done -> {
                            updates += OrchestratorUpdate.Delta(state.result)
                            updates += completed(true, "agent-core", System.currentTimeMillis())
                        }

                        is AgentCore.AgentState.Error -> {
                            updates += OrchestratorUpdate.Delta("Automation error: ${state.message}")
                            updates += completed(false, "agent-core", System.currentTimeMillis())
                        }

                        else -> {}
                    }
                    lastState = state
                }
                state is AgentCore.AgentState.Done || state is AgentCore.AgentState.Error
            }
            true
        }

        if (completed != true && updates.none { it is OrchestratorUpdate.Completed }) {
            updates += OrchestratorUpdate.Delta("Device automation timed out, sir.")
            updates += completed(false, "agent-core", System.currentTimeMillis())
        }
        return@withContext updates
    }

    private suspend fun handleVisionAnalysis(input: String): List<OrchestratorUpdate> = withContext(Dispatchers.IO) {
        val updates = mutableListOf<OrchestratorUpdate>()

        // Primary path: consent-free screen text via the accessibility tree.
        // (MediaProjection screenshots need a user consent dialog wired to an
        // activity result AND, on API 34+, a live mediaProjection foreground
        // service — without either, capture() can never succeed.)
        if (JarvisAccessibilityService.isConnected()) {
            val screenText = JarvisAccessibilityService.activeWindowText()
            if (screenText.isNotEmpty()) {
                val description = "Screen content:\n${screenText.joinToString("\n")}"
                updates += OrchestratorUpdate.Delta(description)
                updates += completed(true, "vision-a11y", System.currentTimeMillis())
                return@withContext updates
            }
        }

        // Fallback: OCR screenshot path (works only when a MediaProjection
        // session was previously granted via ScreenshotCapture.startCapture).
        val screenshotCapture = ScreenshotCapture.getInstance(appContext)
        val bitmap = try {
            screenshotCapture.capture()
        } catch (e: Exception) {
            updates += OrchestratorUpdate.Delta("Failed to capture screen: ${SecretRedactor.redact(e.message)}")
            return@withContext updates
        }
        
        if (bitmap == null) {
            updates += OrchestratorUpdate.Delta(
                "Screen reading unavailable, sir. Enable AURIX Accessibility in Settings, or grant screen-capture permission."
            )
            return@withContext updates
        }
        
        // Run OCR on the screenshot
        val visionModule = VisionModule.getInstance(appContext)
        val ocrResult = try {
            visionModule.extractStructured(bitmap)
        } catch (e: Exception) {
            updates += OrchestratorUpdate.Delta("OCR failed: ${SecretRedactor.redact(e.message)}")
            return@withContext updates
        }
        
        if (ocrResult.fullText.isBlank()) {
            updates += OrchestratorUpdate.Delta("No text detected on screen, sir.")
        } else {
            val description = "Screen content:\n${ocrResult.fullText}"
            updates += OrchestratorUpdate.Delta(description)
            updates += completed(true, "vision-ocr", System.currentTimeMillis())
        }
        
        return@withContext updates
    }

    /** Heuristic: first-person durable statements ("remember…", "my X is Y"). */
    private fun looksLikeDurableFact(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.length !in 8..300) return false
        return t.startsWith("remember ") ||
            t.startsWith("my name is ") ||
            t.startsWith("i am ") ||
            t.startsWith("my favorite") ||
            t.startsWith("i prefer") ||
            t.startsWith("i work ")
    }

    /**
     * Single storage seam shared by the explicit MEMORY intent and the ambient
     * CHAT-path auto-store, so both go through the same remember call, dedupe
     * policy and error logging. Returns the write result or null on failure.
     */
    private suspend fun rememberAsFact(content: String, source: String): MemoryWriteResult? = try {
        vectorMemory.remember(
            content = content,
            type = MemoryType.FACT,
            source = source
        )
    } catch (e: Exception) {
        Log.w(TAG, "memory.remember($source) failed: ${e.message}")
        null
    }

    /**
     * Keeps the active turn always present while budgeting the history to a
     * rough token ceiling (chars ≈ 4 tokens). Drops the oldest messages once
     * the budget is exceeded — a token-aware successor to the flat 16-message
     * cutoff that long replies previously blew straight through.
     */
    private fun trimToBudget(messages: List<Message>): List<Message> {
        if (messages.size <= MAX_HISTORY_MESSAGES &&
            messages.sumOf { it.text.length } <= MAX_HISTORY_CHARS
        ) {
            return messages
        }
        val budgeted = ArrayDeque<Message>()
        var used = 0
        for (msg in messages.asReversed()) {
            val cost = msg.text.length
            if (budgeted.isNotEmpty() && used + cost > MAX_HISTORY_CHARS) break
            budgeted.addFirst(msg)
            used += cost
        }
        return budgeted.toList().takeLast(MAX_HISTORY_MESSAGES)
    }

    private fun completed(success: Boolean, provider: String, startedAt: Long) =
        OrchestratorUpdate.Completed(success, provider, System.currentTimeMillis() - startedAt)

    companion object {
        private const val TAG = "JarvisOrchestrator"

        /** Intent category names emitted by [IntentClassifier] and dispatched here. */
        object Intent {
            const val CHAT = "CHAT"
            const val MEMORY = "MEMORY"
            const val SYSTEM_COMMAND = "SYSTEM_COMMAND"
            const val SEND_SMS = "SEND_SMS"
            const val SEND_WHATSAPP = "SEND_WHATSAPP"
            const val MAKE_CALL = "MAKE_CALL"
            const val DEVICE_AUTOMATION = "DEVICE_AUTOMATION"
            const val VISION_ANALYSIS = "VISION_ANALYSIS"
        }

        /** Fallback message-count cap for chat history (upper bound on the budget). */
        private const val MAX_HISTORY_MESSAGES = 16

        /** Rough token budget for chat history: chars ≈ 4×tokens (~16k tokens). */
        private const val MAX_HISTORY_CHARS = 64_000

        /** Tool name used to gate device automation through PermissionGate. */
        const val DEVICE_AUTOMATION_TOOL = "device_automation"

        /** Timeout guarding automation state-collection so a stuck agent is never awaited forever. */
        private const val AUTOMATION_TIMEOUT_MS = 15_000L

        /** Surfaced whenever the remote uplink cannot be reached (fully-online policy). */
        const val NOT_AVAILABLE_MESSAGE =
            "I couldn't complete that request, sir — no AI provider is reachable right now. Tap the message to retry, or check the provider key in Settings."
    }
}
