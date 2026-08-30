package com.jarvis.ai.orchestrator

import android.content.Context
import com.jarvis.ai.agent.AgentCore
import com.jarvis.ai.agent.Action
import com.jarvis.ai.agent.ActionJsonParser
import com.jarvis.ai.agent.TaskWorkingMemory
import com.jarvis.ai.core.EventBus
import com.jarvis.ai.core.EventType
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.data.repository.JarvisRepository
import com.jarvis.ai.data.repository.OfflineJarvisEngine
import com.jarvis.ai.intelligence.TaskRouter
import com.jarvis.ai.memory.vector.VectorMemoryManager
import com.jarvis.ai.planning.AgentLimits
import com.jarvis.ai.planning.AgentLoop
import com.jarvis.ai.planning.StepOutcome
import com.jarvis.ai.provider.LlmRouteRequest
import com.jarvis.ai.provider.ProviderRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

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
    private val vectorMemory: VectorMemoryManager,
    private val appContext: Context,
    /** Null only in JVM tests; device builds always bind a real executor. */
    private val toolExecutor: ToolExecutor? = null,
    private val taskRouter: TaskRouter? = null,
    private val agentCore: AgentCore? = null
) {

    private val agentLoop = AgentLoop(AgentLimits())
    private var lastActionOutput: String = ""

    /** Secrets seam for the vision layer (backend-only credentials). */
    internal lateinit var visionSecrets: com.jarvis.ai.provider.SecretsSource

    fun processRequest(
        input: String,
        history: List<Message> = emptyList(),
        userConfirmedThisTurn: Boolean = false
    ): Flow<OrchestratorUpdate> = flow {
        val startedAt = System.currentTimeMillis()
        EventBus.publish(EventType.USER_INPUT, input.take(120))
        var memoryStored = false

        try {
            val classification = IntentClassifier.classifyIntent(input)

            when (classification.intent) {
                "CALCULATION", "TIME_DATE" -> {
                    val reply = OfflineJarvisEngine.respond(
                        (classification.parameters["expression"] as? String) ?: input
                    )
                    EventBus.publish(EventType.INTENT_DETECTED, classification.intent)
                    emit(OrchestratorUpdate.Delta(reply))
                    emit(completed(true, "local", startedAt))
                }

                "MEMORY" -> {
                    EventBus.publish(EventType.INTENT_DETECTED, "MEMORY")
                    memoryStored = handleMemory(classification, input)
                }

                "SYSTEM_COMMAND" -> {
                    EventBus.publish(EventType.INTENT_DETECTED, "SYSTEM_COMMAND")
                    handleSystemCommand(classification, input, userConfirmedThisTurn).forEach { update ->
                        emit(update)
                    }
                }

                "DEVICE_AUTOMATION" -> {
                    EventBus.publish(EventType.INTENT_DETECTED, "DEVICE_AUTOMATION")
                    handleDeviceAutomation(input, userConfirmedThisTurn).forEach { update ->
                        emit(update)
                    }
                }

                "VISION_ANALYSIS" -> {
                    EventBus.publish(EventType.INTENT_DETECTED, "VISION_ANALYSIS")
                    handleVisionAnalysis(input).forEach { update ->
                        emit(update)
                    }
                }

                else -> {
                    EventBus.publish(EventType.INTENT_DETECTED, "CHAT")
                    // Context injection: recalled memories become part of the system prompt.
                    val memoryContext = runCatching {
                        vectorMemory.contextBlock(input)
                    }.getOrDefault("")
                    // Persist notable user statements for future recall.
                    if (looksLikeDurableFact(input)) {
                        runCatching {
                            val write = vectorMemory.remember(
                                content = input,
                                type = com.jarvis.ai.memory.vector.MemoryType.FACT,
                                source = "conversation"
                            )
                            if (write.status == com.jarvis.ai.memory.vector.WriteStatus.STORED) {
                                memoryStored = true
                                EventBus.publish(EventType.MEMORY_UPDATED, "stored")
                            }
                        }
                    }

                    val systemPrompt = buildString {
                        append(JarvisRepository.SYSTEM_PROMPT)
                        if (memoryContext.isNotBlank()) {
                            append("\n\n")
                            append(memoryContext)
                        }
                    }
                    val trimmedHistory = history.takeLast(MAX_HISTORY_MESSAGES)

                    providerRouter
                        .routeText(
                            LlmRouteRequest(
                                capability = com.jarvis.ai.provider.Capability.CHAT,
                                history = trimmedHistory,
                                systemPrompt = systemPrompt,
                                stream = true
                            )
                        )
                        .collect { chunk ->
                            when (chunk) {
                                is com.jarvis.ai.provider.RouteChunk.Delta ->
                                    emit(OrchestratorUpdate.Delta(chunk.text))

                                is com.jarvis.ai.provider.RouteChunk.Finished -> {
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
                    "An unexpected fault occurred, sir. ${com.jarvis.ai.provider.SecretRedactor.redact(e.message ?: "")}"
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
    suspend fun analyzeImage(base64Image: String, mimeType: String, prompt: String): VisionAnalyzer.VisionResult =
        VisionAnalyzer(providerRouter, visionSecrets).analyze(base64Image, mimeType, prompt)
    /** Emits memory-operation replies; returns true when something was stored. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<OrchestratorUpdate>.handleMemory(
        classification: IntentClassifier.Classification,
        rawInput: String
    ): Boolean = when (classification.parameters["operation"] as? String) {
        "store" -> {
            val fact = rawInput.removePrefix("remember").removePrefix("Remember").trim()
                .ifBlank { rawInput }
            val write = runCatching {
                vectorMemory.remember(
                    content = fact,
                    type = com.jarvis.ai.memory.vector.MemoryType.FACT,
                    source = "explicit-request"
                )
            }.getOrNull()
            val reply = when (write?.status) {
                com.jarvis.ai.memory.vector.WriteStatus.STORED -> {
                    EventBus.publish(EventType.MEMORY_UPDATED, "stored")
                    "Committed to long-term memory, sir."
                }
                com.jarvis.ai.memory.vector.WriteStatus.DUPLICATE ->
                    "Already in my memory banks, sir."
                else -> "I could not store that memory, sir."
            }
            emit(
                OrchestratorUpdate.Completed(
                    success = write?.status == com.jarvis.ai.memory.vector.WriteStatus.STORED,
                    provider = "vector-memory",
                    latencyMs = 0L,
                    memoryStored = write?.status == com.jarvis.ai.memory.vector.WriteStatus.STORED
                )
            )
            emit(OrchestratorUpdate.Delta(reply))
            write?.status == com.jarvis.ai.memory.vector.WriteStatus.STORED
        }

        "recall", "query" -> {
            val hits = runCatching {
                vectorMemory.recall(rawInput)
            }.getOrDefault(emptyList())
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
            val decision = com.jarvis.ai.security.PermissionGate.decide("memory_wipe_all")
            emit(OrchestratorUpdate.Confirmation("memory_wipe_all", decision.message))
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
        val done = kotlinx.coroutines.sync.Mutex()
        
        agentCore?.executeTask(rawInput)
        
        val stateFlow = agentCore?.getStateFlow()
        if (stateFlow != null) {
            var lastState: AgentCore.AgentState? = null
            stateFlow.collect { state ->
                if (state != lastState) {
                    when (state) {
                        is AgentCore.AgentState.Running -> {
                            updates += OrchestratorUpdate.Delta(state.step)
                        }
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
                    if (state is AgentCore.AgentState.Done || state is AgentCore.AgentState.Error) {
                        return@collect
                    }
                }
            }
        }
        
        if (updates.isEmpty()) {
            updates += OrchestratorUpdate.Delta("Device automation executed, sir.")
            updates += completed(true, "agent-core", System.currentTimeMillis())
        }
        return@withContext updates
    }

    private suspend fun handleVisionAnalysis(input: String): List<OrchestratorUpdate> = withContext(Dispatchers.IO) {
        val updates = mutableListOf<OrchestratorUpdate>()
        
        // Capture screenshot
        val screenshotCapture = com.jarvis.ai.vision.ScreenshotCapture.getInstance(appContext)
        val bitmap = try {
            screenshotCapture.capture()
        } catch (e: Exception) {
            updates += OrchestratorUpdate.Delta("Failed to capture screen: ${e.message}")
            return@withContext updates
        }
        
        if (bitmap == null) {
            updates += OrchestratorUpdate.Delta("Could not capture screen. Make sure screen capture permission is granted.")
            return@withContext updates
        }
        
        // Run OCR on the screenshot
        val visionModule = com.jarvis.ai.vision.VisionModule.getInstance(appContext)
        val ocrResult = try {
            visionModule.extractStructured(bitmap)
        } catch (e: Exception) {
            updates += OrchestratorUpdate.Delta("OCR failed: ${e.message}")
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

    private fun completed(success: Boolean, provider: String, startedAt: Long) =
        OrchestratorUpdate.Completed(success, provider, System.currentTimeMillis() - startedAt)

    companion object {
        private const val MAX_HISTORY_MESSAGES = 16
    }
}

/** Cooperative cancellation signal shared with the agent loop. */
class CancellationToken {
    internal var _cancelled = false
    private val lock = Any()

    var isCancelled: Boolean
        get() = synchronized(lock) { _cancelled }
        set(value) = synchronized(lock) { _cancelled = value }

    fun cancel() {
        synchronized(lock) { _cancelled = true }
    }

    fun reset() {
        synchronized(lock) { _cancelled = false }
    }

    fun checkCancellation() {
        if (isCancelled) {
            throw java.lang.InterruptedException("Operation cancelled by user")
        }
    }
}
