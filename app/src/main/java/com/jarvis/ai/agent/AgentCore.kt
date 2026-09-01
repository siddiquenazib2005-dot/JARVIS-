package com.jarvis.ai.agent

import android.content.Context
import com.jarvis.ai.accessibility.A11yErrorCode
import com.jarvis.ai.accessibility.A11yResult
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.accessibility.ScreenReader
import com.jarvis.ai.intelligence.TaskRouter
import com.jarvis.ai.orchestrator.MasterOrchestrator
import com.jarvis.ai.orchestrator.PackageManagerAppLookup
import com.jarvis.ai.orchestrator.PackageResolver
import com.jarvis.ai.data.model.Message
import com.jarvis.ai.data.model.Sender
import com.jarvis.ai.provider.LlmRouteRequest
import com.jarvis.ai.provider.ProviderRouter
import com.jarvis.ai.provider.RouteChunk
import com.jarvis.ai.vision.VisionModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.toList
import kotlin.collections.joinToString

class AgentCore(
    private val context: Context,
    internal var orchestrator: MasterOrchestrator?,
    private val providerRouter: ProviderRouter,
    private val taskRouter: TaskRouter
) {

    private var screenReader: ScreenReader? = null
    private var workingMemory = TaskWorkingMemory()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val taskMutex = Mutex()

    private fun getScreenReader(): ScreenReader {
        return screenReader ?: run {
            val instance = JarvisAccessibilityService.instance
                ?: throw IllegalStateException("Accessibility service not enabled. Enable JARVIS Accessibility in Settings → Accessibility.")
            ScreenReader(instance).also { screenReader = it }
        }
    }

    private suspend fun findElementByOCR(text: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val visionModule = VisionModule.getInstance(context)
        val screenshotCapture = com.jarvis.ai.vision.ScreenshotCapture.getInstance(context)
        
        val bitmap = screenshotCapture.capture() ?: return@withContext null
        val ocrResult = visionModule.extractStructured(bitmap)
        
        val targetLower = text.lowercase()
        
        // Search in blocks
        for (block in ocrResult.blocks) {
            if (block.text.lowercase().contains(targetLower)) {
                block.boundingBox?.let { box ->
                    val centerX = (box.left + box.right) / 2
                    val centerY = (box.top + box.bottom) / 2
                    return@withContext Pair(centerX, centerY)
                }
            }
            
            // Search in lines
            for (line in block.lines) {
                if (line.text.lowercase().contains(targetLower)) {
                    line.boundingBox?.let { box ->
                        val centerX = (box.left + box.right) / 2
                        val centerY = (box.top + box.bottom) / 2
                        return@withContext Pair(centerX, centerY)
                    }
                }
            }
        }
        
        null
    }

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state

    private val systemPrompt = """
    You are JARVIS — an Android device control AI agent.
    The user gives you a command in natural language.
    You must respond with ONLY a valid JSON array of actions. No explanation. No markdown fences. No preamble. Pure JSON array only.

    AVAILABLE ACTIONS:
    open_app     → {"action":"open_app","package":"com.package","label":"AppName"}
    tap          → {"action":"tap","text":"Button text on screen"}
    tap_coords   → {"action":"tap_coords","x":540,"y":960,"text":"button text to find via OCR"}
    long_press   → {"action":"long_press","text":"Element text"}
    type         → {"action":"type","value":"text to type"}
    clear_type   → {"action":"clear_type","value":"clears field then types"}
    swipe        → {"action":"swipe","direction":"up|down|left|right","distance":"short|medium|long"}
    scroll       → {"action":"scroll","direction":"up|down"}
    press_back   → {"action":"press_back"}
    press_home   → {"action":"press_home"}
    press_recents → {"action":"press_recents"}
    wait_for     → {"action":"wait_for","text":"expected text","timeout_ms":3000}
    screenshot   → {"action":"screenshot"}
    read_screen  → {"action":"read_screen"}
    ai_prompt    → {"action":"ai_prompt","package":"com.openai.chatgpt","prompt":"{prompt}","outputKey":"result"}
    extract_text → {"action":"extract_text","outputKey":"page_text"}

    CURRENT SCREEN CONTENT: {SCREEN_OCR}

    APP SELECTION REASONING: {APP_REASONING}

    RECENT MEMORY CONTEXT: {MEMORY_CONTEXT}

    RULES:
    - Always start complex tasks with open_app
    - Add wait_for after open_app to confirm app loaded
    - If screen content is empty or unclear, add read_screen as first action
    - Never assume UI state — always verify with wait_for
    - Keep action arrays short: 2-8 steps per task
    - If a task is impossible to do safely, return: [{"action":"error","message":"reason"}]
    """.trimIndent()

    fun executeTask(cleanCommand: String) {
        workingMemory = TaskWorkingMemory()
        
        scope.launch {
            taskMutex.withLock {
                var shouldReturn = false
                
                try {
                    _state.value = AgentState.Running("analyzing task...")
                
                    val plan = taskRouter.analyze(cleanCommand)
                    
                    _state.value = AgentState.Running("reading screen...")
                    
                    val screenText = withContext(Dispatchers.IO) {
                        getScreenReader().extractAllText()
                    }
                    
                    _state.value = AgentState.Running("getting context...")
                    
                    val memoryContext = getMemoryContext(cleanCommand)
                    
                    val fullSystem = systemPrompt
                        .replace("{SCREEN_OCR}", screenText.take(2000))
                        .replace("{APP_REASONING}", plan.reasoning)
                        .replace("{MEMORY_CONTEXT}", if (memoryContext.isBlank()) "No recent memory" else memoryContext)
                    
                    _state.value = AgentState.Running("thinking...")
                    
                    val startTime = System.currentTimeMillis()
                    
                    // Fetch the agent's action-JSON directly from the provider layer with
                    // the SCREEN_OCR/APP_REASONING/MEMORY_CONTEXT system prompt. Calling
                    // MasterOrchestrator.processRequest() here would run the normal CHAT
                    // pipeline (JarvisRepository.SYSTEM_PROMPT) instead — the action schema
                    // was being constructed but never sent to the model, so the result was
                    // never a parseable JSON action array and every task failed validation.
                    val rawJson = providerRouter.routeText(
                        LlmRouteRequest(
                            capability = com.jarvis.ai.provider.Capability.CHAT,
                            history = listOf(Message(sender = Sender.USER, text = cleanCommand)),
                            systemPrompt = fullSystem,
                            stream = false
                        )
                    ).fold("") { acc, chunk ->
                        when (chunk) {
                            is RouteChunk.Delta -> acc + chunk.text
                            is RouteChunk.Finished -> acc
                        }
                    }
                    
                    val validation = ActionJsonParser.validate(rawJson)
                    val rawForParsing = if (!validation.isValid && validation.errors.isNotEmpty()) {
                        _state.value = AgentState.Error("Invalid response: ${validation.errors.first()}")
                        shouldReturn = true
                        rawJson
                    } else {
                        rawJson
                    }
                    
                    if (shouldReturn) {
                        return@withLock
                    }
                    
                    val actions = ActionJsonParser.parse(rawForParsing)
                        ?: run {
                            // Retry with explicit instruction
                            val retryJson = providerRouter.routeText(
                                LlmRouteRequest(
                                    capability = com.jarvis.ai.provider.Capability.CHAT,
                                    history = listOf(Message(sender = Sender.USER, text = cleanCommand)),
                                    systemPrompt = "Respond with a JSON array of actions ONLY. No other text.\n\n$fullSystem",
                                    stream = false
                                )
                            ).fold("") { acc, chunk ->
                                when (chunk) {
                                    is RouteChunk.Delta -> acc + chunk.text
                                    is RouteChunk.Finished -> acc
                                }
                            }
                            ActionJsonParser.parse(retryJson)
                        }
                    
                    if (actions == null) {
                        _state.value = AgentState.Error("Could not parse AI response")
                        return@withLock
                    }
                    
                    _state.value = AgentState.Running("executing ${actions.size} actions...")
                    
                    executeActions(actions)
                    
                    val latency = System.currentTimeMillis() - startTime
                    _state.value = AgentState.Done("done in ${latency}ms")
                } catch (e: Exception) {
                    _state.value = AgentState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    private suspend fun getMemoryContext(command: String): String {
        val vecMem = orchestrator?.vectorMemory ?: return ""
        return runCatching {
            vecMem.recall(command).joinToString("\n") { "- ${it.record.content.take(200)}" }
        }.getOrDefault("")
    }

    private suspend fun executeActions(actions: List<Action>) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            _state.value = AgentState.Error(
                "Accessibility service not enabled. Enable JARVIS in Settings → Accessibility."
            )
            return
        }

        for ((index, action) in actions.withIndex()) {
            _state.value = AgentState.Running("action ${index + 1}/${actions.size}")

            val result: A11yResult = when (action) {
                is Action.OpenApp -> {
                    val pkg = action.packageName
                    val label = action.label
                    if (pkg != null) {
                        service.openAppByPackage(pkg)
                    } else if (label != null) {
                        val packageName = findPackageByLabel(label)
                        if (packageName != null) service.openAppByPackage(packageName)
                        else A11yResult.failure(A11yErrorCode.APP_NOT_FOUND, action = "open_app", target = label, message = "App not found: $label")
                    } else {
                        A11yResult.failure(A11yErrorCode.INVALID_ACTION, action = "open_app", message = "open_app missing package or label")
                    }
                }
                is Action.Tap -> {
                    val text = action.text
                    if (text != null) service.tapByText(text)
                    else A11yResult.failure(A11yErrorCode.UNKNOWN, action = "tap", message = "tap missing text")
                }
                is Action.TapCoords -> {
                    val text = action.text ?: ""
                    val coords = findElementByOCR(text)
                    if (coords != null) service.tapByCoords(coords.first, coords.second)
                    else A11yResult.failure(A11yErrorCode.NODE_NOT_FOUND, action = "tap_coords", target = text, message = "OCR could not find: $text")
                }
                is Action.Type -> {
                    val value = action.value
                    if (value != null) service.typeText(value) else A11yResult.success(action = "type")
                }
                is Action.ClearType -> {
                    val value = action.value
                    if (value != null) service.typeText(value, clearFirst = true)
                    else A11yResult.success(action = "type")
                }
                is Action.Swipe -> {
                    service.swipe(action.direction ?: "up", action.distance ?: "medium")
                }
                is Action.Scroll -> {
                    service.performScroll((action.direction ?: "down") == "down")
                }
                is Action.CloseApp -> {
                    val label = action.label
                    val pkg = action.packageName
                    if (pkg != null) {
                        service.closeAppByPackage(pkg)
                    } else if (label != null) {
                        val packageName = findPackageByLabel(label)
                        if (packageName != null) service.closeAppByPackage(packageName)
                        else A11yResult.failure(A11yErrorCode.APP_NOT_FOUND, action = "close_app", target = label, message = "App not found: $label")
                    } else {
                        A11yResult.failure(A11yErrorCode.INVALID_ACTION, action = "close_app", message = "close_app missing label or package")
                    }
                }
                is Action.LongPress -> {
                    val text = action.text
                    if (text != null) service.longPressByText(text)
                    else A11yResult.failure(A11yErrorCode.UNKNOWN, action = "long_press", message = "long_press missing text")
                }
                is Action.WaitFor -> {
                    service.waitForText(action.text ?: "", action.timeoutMs.toLong())
                }
                is Action.ExtractText -> {
                    val outputKey = action.outputKey ?: "page_text"
                    val text = runCatching { getScreenReader().extractAllText() }.getOrDefault("")
                    workingMemory.set(outputKey, text)
                    A11yResult.success(action = "extract_text", message = "extracted text")
                }
                is Action.PressBack -> service.pressBack()
                is Action.PressHome -> service.pressHome()
                is Action.PressRecents -> service.pressRecents()
                is Action.LockScreen -> service.lockScreen()
                is Action.Error -> {
                    _state.value = AgentState.Error(action.message ?: "Task failed")
                    return
                }
                // Vision-only actions are not executed by the automation loop.
                is Action.Screenshot, is Action.ReadScreen, is Action.AiPrompt ->
                    A11yResult.success(action = action.action)
            }

            if (result.isFailure) {
                _state.value = AgentState.Error(
                    result.message ?: "Action failed: ${result.errorCode}"
                )
                return
            }

            kotlinx.coroutines.delay(500)
        }
    }

    /**
     * Resolves a human app identifier to a package name via [PackageResolver].
     *
     * Shares the same defensive [AppLookup] strategy as [ToolExecutor]: per-element
     * `runCatching` on label loading so a single app with a broken/missing label
     * resource can never empty the whole list (the old inline loop threw and broke
     * "open whatsapp" for the entire device).
     */
    private fun findPackageByLabel(label: String): String? =
        PackageResolver.resolve(label, PackageManagerAppLookup.create(context.packageManager))

    suspend fun testConnection(): Boolean {
        return try {
            val currentOrchestrator = orchestrator
                ?: throw IllegalStateException("Orchestrator not initialized")
            val result = currentOrchestrator.processRequest(
                input = "test",
                history = emptyList(),
                userConfirmedThisTurn = false
            ).toList()
            result.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
    
    fun getCurrentProviderName(): String {
        return "JARVIS"
    }
    
    fun getStateFlow(): StateFlow<AgentState> = state

    sealed class AgentState {
        object Idle : AgentState()
        data class Running(val step: String) : AgentState()
        data class Done(val result: String) : AgentState()
        data class Error(val message: String) : AgentState()
    }
}

class TaskWorkingMemory {
    private val memory = mutableMapOf<String, String>()
    
    fun set(key: String, value: String) {
        memory[key] = value
    }
    
    fun get(key: String): String? = memory[key]
    
    fun interpolate(text: String): String {
        var result = text
        for ((key, value) in memory) {
            result = result.replace("{$key}", value)
        }
        return result
    }
}

sealed class Action {
    abstract val action: String
    
    data class OpenApp(val label: String?, val packageName: String? = null) : Action() {
        override val action = "open_app"
    }
    data class Tap(val text: String?) : Action() {
        override val action = "tap"
    }
    data class TapCoords(val x: Int, val y: Int, val text: String? = null) : Action() {
        override val action = "tap_coords"
    }
    data class Type(val value: String?) : Action() {
        override val action = "type"
    }
    data class ClearType(val value: String?) : Action() {
        override val action = "clear_type"
    }
    data class Swipe(val direction: String?, val distance: String?) : Action() {
        override val action = "swipe"
    }
    data class Scroll(val direction: String?, val distance: String?) : Action() {
        override val action = "scroll"
    }
    data class CloseApp(val label: String?, val packageName: String? = null) : Action() {
        override val action = "close_app"
    }
    data class LongPress(val text: String?) : Action() {
        override val action = "long_press"
    }
    object LockScreen : Action() {
        override val action = "lock_screen"
    }
    object PressBack : Action() {
        override val action = "press_back"
    }
    object PressHome : Action() {
        override val action = "press_home"
    }
    object PressRecents : Action() {
        override val action = "press_recents"
    }
    data class WaitFor(val text: String?, val timeoutMs: Int) : Action() {
        override val action = "wait_for"
    }
    object Screenshot : Action() {
        override val action = "screenshot"
    }
    object ReadScreen : Action() {
        override val action = "read_screen"
    }
    data class AiPrompt(
        val packageName: String?,
        val prompt: String?,
        val outputKey: String?
    ) : Action() {
        override val action = "ai_prompt"
    }
    data class ExtractText(val outputKey: String?) : Action() {
        override val action = "extract_text"
    }
    data class Error(val message: String?) : Action() {
        override val action = "error"
    }
    
    companion object {
        const val OPEN_APP = "open_app"
        const val TAP = "tap"
        const val TAP_COORDS = "tap_coords"
        const val TYPE = "type"
        const val PRESS_BACK = "press_back"
        const val PRESS_HOME = "press_home"
        const val PRESS_RECENTS = "press_recents"
        const val EXTRACT_TEXT = "extract_text"
        const val ERROR = "error"
        const val SWIPE = "swipe"
        const val SCROLL = "scroll"
        const val WAIT_FOR = "wait_for"
        const val LONG_PRESS = "long_press"
        const val LOCK_SCREEN = "lock_screen"
        const val CLOSE_APP = "close_app"
    }
}

object ActionJsonParser {
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>
    )
    
    fun validate(json: String): ValidationResult {
        val errors = mutableListOf<String>()
        // Try the raw payload first, then re-try after extracting the JSON array
        // from markdown fences / surrounding prose (very common LLM output).
        for (candidate in listOf(json, extractArray(json)).distinct()) {
            try {
                val array = org.json.JSONArray(candidate)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (!obj.has("action")) {
                        errors.add("Action at index $i missing 'action' field")
                    }
                }
                return ValidationResult(errors.isEmpty(), errors)
            } catch (e: Exception) {
                errors.add("Invalid JSON: ${e.message}")
            }
        }
        return ValidationResult(false, errors)
    }
    
    fun parse(json: String): List<Action>? {
        for (candidate in listOf(json, extractArray(json)).distinct()) {
            val parsed = parseArray(candidate)
            if (parsed != null) return parsed
        }
        return null
    }

    /**
     * Extracts the JSON array payload from text that may be wrapped in markdown
     * fences (```json … ```) or preceded/followed by LLM prose. Falls back to
     * the raw input when no `[ … ]` span can be isolated.
     */
    private fun extractArray(raw: String): String {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    private fun parseArray(json: String): List<Action>? {
        return try {
            val array = org.json.JSONArray(json)
            val actions = mutableListOf<Action>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val actionType = obj.optString("action", "")
                
                when (actionType) {
                    Action.OPEN_APP -> actions.add(Action.OpenApp(
                        obj.optString("label").ifBlank { null },
                        obj.optString("package").ifBlank { null }
                    ))
                    Action.TAP -> actions.add(Action.Tap(obj.optString("text")))
                    Action.TAP_COORDS -> actions.add(Action.TapCoords(
                        obj.optInt("x"),
                        obj.optInt("y"),
                        obj.optString("text")
                    ))
                    Action.TYPE -> actions.add(Action.Type(obj.optString("value")))
                    Action.PRESS_BACK -> actions.add(Action.PressBack)
                    Action.PRESS_HOME -> actions.add(Action.PressHome)
                    Action.PRESS_RECENTS -> actions.add(Action.PressRecents)
                    Action.EXTRACT_TEXT -> actions.add(Action.ExtractText(obj.optString("outputKey")))
                    Action.ERROR -> actions.add(Action.Error(obj.optString("message")))
                    Action.SWIPE -> actions.add(Action.Swipe(
                        obj.optString("direction"),
                        obj.optString("distance")
                    ))
                    Action.SCROLL -> actions.add(Action.Scroll(
                        obj.optString("direction"),
                        obj.optString("distance")
                    ))
                    Action.WAIT_FOR -> actions.add(Action.WaitFor(
                        obj.optString("text"),
                        obj.optInt("timeoutMs", 5000)
                    ))
                    Action.LONG_PRESS -> actions.add(Action.LongPress(obj.optString("text")))
                    Action.LOCK_SCREEN -> actions.add(Action.LockScreen)
                    Action.CLOSE_APP -> actions.add(Action.CloseApp(
                        obj.optString("label").ifBlank { null },
                        obj.optString("package").ifBlank { null }
                    ))
                    else -> {}
                }
            }
            actions
        } catch (e: Exception) {
            null
        }
    }
}